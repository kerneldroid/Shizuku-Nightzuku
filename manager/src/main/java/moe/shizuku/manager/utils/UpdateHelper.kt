package moe.shizuku.manager.utils

import android.text.TextUtils
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ApkUtils.*
import okhttp3.OkHttpClient
import okhttp3.Request

object UpdateHelper {
    private val app = ShizukuApplication.application
    private val appContext = ShizukuApplication.appContext

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val commit: Int = 0,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(
                this,
                other,
                { it.major },
                { it.minor },
                { it.patch },
                { it.commit },
            )

        override fun toString(): String =
            if (commit == 0) "$major.$minor.$patch" else "$major.$minor.$patch.r$commit"

        companion object {
            fun parse(tag: String): Version? {
                val regex = Regex("""(\d+)\.(\d+)\.(\d+)(?:\.r(\d+))?""")
                val match = regex.find(tag) ?: return null
                val (major, minor, patch, commit) = match.destructured
                return Version(
                    major.toInt(),
                    minor.toInt(),
                    patch.toInt(),
                    commit.toIntOrNull() ?: 0
                )
            }
        }
    }

    data class Release(
        val version: Version,
        val filename: String,
        val url: String,
        val digest: String?
    )

    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val prerelease: Boolean,
        val assets: List<GitHubAsset>
    )

    @Serializable
    data class GitHubAsset(
        val name: String,
        val browser_download_url: String,
        val digest: String? = null
    )

    @Volatile
    private var latestRelease: Release? = null

    private suspend fun showToast(resId: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, appContext.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun checkAndInstallUpdates() {
        if (isUpdateAvailable()) {
            update()
        } else {
            showToast(R.string.update_latest_installed)
        }
    }

    fun isCheckForUpdatesEnabled(): Boolean = ShizukuSettings.getUpdateMode() != ShizukuSettings.UpdateMode.OFF

    suspend fun isNewUpdateAvailable(): Boolean {
        val lastPromptedVersion =
            Version.parse(ShizukuSettings.getLastPromptedVersion())
                ?: Version.parse(getVersionName())
                ?: return false
        if (!isUpdateAvailable()) return false
        val rel = latestRelease ?: return false
        return rel.version > lastPromptedVersion
    }

    suspend fun isUpdateAvailable(): Boolean {
        return try {
            val latest = fetchLatestRelease().version
            val current = Version.parse(getVersionName()) ?: return false
            latest > current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showToast(R.string.update_check_failed)
            false
        }
    }

    fun updateLastPromptedVersion() {
        val rel = latestRelease ?: return
        ShizukuSettings.setLastPromptedVersion(rel.version.toString())
    }

    suspend fun update() {
        val rel = latestRelease ?: run {
            if (!isUpdateAvailable()) return
            latestRelease ?: return
        }

        showToast(R.string.update_downloading)

        val downloadedFile = try {
            rel.download()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showToast(R.string.update_download_failed)
            return
        }

        val apk = try {
            val pm = appContext.packageManager
            val apkPackageName = pm.getPackageArchiveInfo(downloadedFile.path, 0)?.packageName
            if (!TextUtils.isEmpty(apkPackageName) && app.packageName != apkPackageName) {
                android.util.Log.d("UpdateHelper", "Changing package name from $apkPackageName to ${app.packageName}")
                withContext(Dispatchers.IO) {
                    downloadedFile.changePackageName(app.packageName)
                }
            } else {
                downloadedFile
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showToast(R.string.update_failed)
            return
        }

        appContext.installPackage(apk) { isSuccess, _ ->
            val toastMsg =
                if (isSuccess) appContext.getString(R.string.update_success)
                else appContext.getString(R.string.update_failed)
            Toast.makeText(appContext, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fetchLatestRelease(): Release =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com/repos/kerneldroid/Shizuku-Nightzuku/releases"
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected HTTP code ${response.code}")
                }
                response.body?.string() ?: throw IOException("Couldn't fetch releases")
            }

            val releases = json.decodeFromString<List<GitHubRelease>>(body)
            val filtered =
                if (ShizukuSettings.getUpdateMode() == ShizukuSettings.UpdateMode.BETA) {
                    releases
                } else {
                    releases.filter { !it.prerelease }
                }

            filtered
                .mapNotNull { release ->
                    val version =
                        Version.parse(release.tag_name)
                            ?: return@mapNotNull null
                    val asset =
                        release.assets.firstOrNull { it.name.endsWith(".apk") }
                            ?: return@mapNotNull null

                    Release(
                        version = version,
                        filename = asset.name,
                        url = asset.browser_download_url,
                        digest = asset.digest
                    )
                }.maxByOrNull { it.version }
                ?.also { latestRelease = it }
                ?: throw IOException("No valid releases found")
        }

    private suspend fun Release.download(): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val safeName = File(filename).name.ifEmpty { "update.apk" }
            val apkFile = File(appContext.cacheDir, safeName)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                apkFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }

            if (!digest.isNullOrBlank()) {
                val downloadedDigest = "sha256:" + apkFile.sha256()
                if (!downloadedDigest.equals(digest, ignoreCase = true)) {
                    apkFile.delete()
                    throw SecurityException("Digest of downloaded file does not match the one reported by GitHub")
                }
            }

            apkFile
        }

    fun File.sha256(): String {
        val bytes = readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

}
