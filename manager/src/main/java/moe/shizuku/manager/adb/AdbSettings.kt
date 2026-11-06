package moe.shizuku.manager.adb

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Writes the debug settings needed to bring up or manage wireless/USB ADB,
 * ported from Nightzuku.
 *
 * Android 17 QPR3 tightened `SettingsProvider`: writing `adb_enabled`,
 * `adb_wifi_enabled` or `adb_allowed_connection_time` now also requires
 * `MANAGE_DEBUGGING`, which is signature|privileged and cannot be held by a
 * normal app even when `WRITE_SECURE_SETTINGS` was granted by Shizuku's
 * server. Instead of letting an unguarded `Settings.Global.putInt` throw
 * `SecurityException` and crash the app, this helper:
 *
 * 1. skips keys that already have the desired value;
 * 2. tries the direct write inside `runCatching` when the app holds `WRITE_SECURE_SETTINGS`;
 * 3. falls back to the Shizuku server (shell/root UID), where `settings put global`
 *    does hold `MANAGE_DEBUGGING`;
 * 4. verifies the resulting values before reporting success.
 */
object AdbSettings {

    const val KEY_WIFI_ADB = "adb_wifi_enabled"
    const val KEY_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    /** Keys written as integers when bringing up wireless ADB. */
    private val INT_KEYS = linkedMapOf(
        KEY_WIFI_ADB to 1,
        Settings.Global.ADB_ENABLED to 1
    )

    /** The system disables the ADB connection timer with 0. */
    private const val ALLOWED_CONNECTION_TIME = 0L

    /**
     * Enables wireless ADB (`adb_wifi_enabled = 1`), USB/base ADB (`adb_enabled = 1`),
     * and disables the connection timeout (`adb_allowed_connection_time = 0`).
     *
     * @return true when every key holds the expected value afterwards.
     */
    @JvmStatic
    fun enableWirelessAdb(context: Context): Boolean {
        val resolver = context.contentResolver
        if (isWirelessAdbEnabled(resolver)) {
            return true
        }

        val directAllowed = hasWriteSecureSettings(context)
        val serverFallback = mutableListOf<Pair<String, String>>()

        for ((key, value) in INT_KEYS) {
            if (readInt(resolver, key) == value) {
                continue
            }
            if (directAllowed) {
                runCatching { Settings.Global.putInt(resolver, key, value) }
            }
            if (readInt(resolver, key) != value) {
                serverFallback += key to value.toString()
            }
        }

        if (readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) != ALLOWED_CONNECTION_TIME) {
            if (directAllowed) {
                runCatching {
                    Settings.Global.putLong(
                        resolver,
                        KEY_ALLOWED_CONNECTION_TIME,
                        ALLOWED_CONNECTION_TIME
                    )
                }
            }
            if (readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) != ALLOWED_CONNECTION_TIME) {
                serverFallback += KEY_ALLOWED_CONNECTION_TIME to ALLOWED_CONNECTION_TIME.toString()
            }
        }

        if (serverFallback.isNotEmpty()) {
            writeThroughServer(serverFallback)
        }

        return isWirelessAdbEnabled(resolver)
    }

    /**
     * Ensures `adb_enabled = 1` and `adb_allowed_connection_time = 0` without throwing
     * on Android 17 QPR3 where `MANAGE_DEBUGGING` is enforced by `SettingsProvider`.
     */
    @JvmStatic
    fun enableAdbBaseSettings(context: Context): Boolean {
        val resolver = context.contentResolver
        val directAllowed = hasWriteSecureSettings(context)
        val serverFallback = mutableListOf<Pair<String, String>>()

        if (readInt(resolver, Settings.Global.ADB_ENABLED) != 1) {
            if (directAllowed) {
                runCatching { Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1) }
            }
            if (readInt(resolver, Settings.Global.ADB_ENABLED) != 1) {
                serverFallback += Settings.Global.ADB_ENABLED to "1"
            }
        }

        if (readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) != ALLOWED_CONNECTION_TIME) {
            if (directAllowed) {
                runCatching {
                    Settings.Global.putLong(
                        resolver,
                        KEY_ALLOWED_CONNECTION_TIME,
                        ALLOWED_CONNECTION_TIME
                    )
                }
            }
            if (readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) != ALLOWED_CONNECTION_TIME) {
                serverFallback += KEY_ALLOWED_CONNECTION_TIME to ALLOWED_CONNECTION_TIME.toString()
            }
        }

        if (serverFallback.isNotEmpty()) {
            writeThroughServer(serverFallback)
        }

        return isAdbEnabled(resolver)
    }

    /**
     * Safely sets `adb_wifi_enabled` (`1` or `0`) without crashing on Android 17 QPR3.
     */
    @JvmStatic
    fun setWifiAdbEnabled(context: Context, enabled: Boolean): Boolean {
        val resolver = context.contentResolver
        val target = if (enabled) 1 else 0
        if (readInt(resolver, KEY_WIFI_ADB) == target) {
            return true
        }
        if (hasWriteSecureSettings(context)) {
            runCatching { Settings.Global.putInt(resolver, KEY_WIFI_ADB, target) }
        }
        if (readInt(resolver, KEY_WIFI_ADB) != target) {
            writeThroughServer(listOf(KEY_WIFI_ADB to target.toString()))
        }
        return readInt(resolver, KEY_WIFI_ADB) == target
    }

    /**
     * Safely sets `adb_enabled` (`1` or `0`) without crashing on Android 17 QPR3.
     */
    @JvmStatic
    fun setUsbDebuggingEnabled(context: Context, enabled: Boolean): Boolean {
        val resolver = context.contentResolver
        val target = if (enabled) 1 else 0
        if (readInt(resolver, Settings.Global.ADB_ENABLED) == target) {
            return true
        }
        if (hasWriteSecureSettings(context)) {
            runCatching { Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, target) }
        }
        if (readInt(resolver, Settings.Global.ADB_ENABLED) != target) {
            writeThroughServer(listOf(Settings.Global.ADB_ENABLED to target.toString()))
        }
        return readInt(resolver, Settings.Global.ADB_ENABLED) == target
    }

    @JvmStatic
    fun isWirelessAdbEnabled(resolver: ContentResolver): Boolean =
        INT_KEYS.all { (key, value) -> readInt(resolver, key) == value } &&
            readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) == ALLOWED_CONNECTION_TIME

    @JvmStatic
    fun isAdbEnabled(resolver: ContentResolver): Boolean =
        readInt(resolver, Settings.Global.ADB_ENABLED) == 1 ||
            readInt(resolver, KEY_WIFI_ADB) == 1

    private fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs `settings put global <key> <value>` through the Shizuku server when available.
     * The server runs as shell or root and therefore holds `MANAGE_DEBUGGING`.
     */
    private fun writeThroughServer(entries: List<Pair<String, String>>): Boolean {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return false
        }
        val service = runCatching {
            IShizukuService.Stub.asInterface(Shizuku.getBinder())
        }.getOrNull() ?: return false

        var allSucceeded = true
        for ((key, value) in entries) {
            val ok = runCatching {
                val proc = service.newProcess(
                    arrayOf("settings", "put", "global", key, value),
                    null,
                    null
                )
                val finished = proc.waitForTimeout(3, TimeUnit.SECONDS.name)
                if (!finished) {
                    proc.destroy()
                    false
                } else {
                    proc.exitValue() == 0
                }
            }.getOrDefault(false)
            if (!ok) {
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    private fun readInt(resolver: ContentResolver, key: String): Int =
        runCatching { Settings.Global.getInt(resolver, key, -1) }.getOrDefault(-1)

    private fun readLong(resolver: ContentResolver, key: String): Long =
        runCatching { Settings.Global.getLong(resolver, key, -1L) }.getOrDefault(-1L)
}
