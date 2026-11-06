package moe.shizuku.manager.authorization

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.shizuku.common.util.LabelSanitizer
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.ConfirmationDialogBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.res.resolveColor
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

class RequestPermissionActivity : AppActivity() {

    private lateinit var dialog: Dialog
    private var requestUid: Int = -1
    private var requestPid: Int = -1
    private var requestCode: Int = -1
    private var resultDispatched: Boolean = false

    private fun setResult(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        if (resultDispatched) return
        resultDispatched = true
        val data = Bundle()
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: Throwable) {
            LOGGER.e("dispatchPermissionConfirmationResult")
        }
    }

    private fun checkSelfPermission(): Boolean {
        val permission = Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        if (permission) return true

        val icon = getDrawable(R.drawable.ic_system_icon)
        icon?.setTint(theme.resolveColor(android.R.attr.colorAccent))

        val dialog = MaterialAlertDialogBuilder(this)
            .setIcon(icon)
            .setTitle("Shizuku: ${getString(R.string.app_management_dialog_adb_is_limited_title)}")
            .setMessage(getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get()).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .create()
        dialog.setOnShowListener {
            (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        }
        try {
            dialog.show()
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun waitForBinder(): Boolean {
        return runBlocking {
            try {
                withTimeout(5000) {
                    ShizukuStateMachine.asFlow().first { it == ShizukuStateMachine.State.RUNNING }
                }
                true
            } catch (e: TimeoutCancellationException) {
                LOGGER.e(e, "Binder not received in 5s")
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(true) }
        }
        window.decorView.filterTouchesWhenObscured = true

        if (!waitForBinder()) {
            finish()
            return
        }

        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val code = intent.getIntExtra("requestCode", -1)
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("applicationInfo")
        }
        if (uid <= 0 || pid <= 0 || ai == null || ai.uid != uid || ai.packageName.isNullOrEmpty()) {
            finish()
            return
        }

        val uidPackages = runCatching {
            packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (uidPackages.isNotEmpty() && !uidPackages.contains(ai.packageName)) {
            LOGGER.w("Rejected permission prompt: package %s does not belong to uid %d", ai.packageName, uid)
            finish()
            return
        }

        requestUid = uid
        requestPid = pid
        requestCode = code

        if (!checkSelfPermission()) {
            setResult(uid, pid, code, allowed = false, onetime = true)
            return
        }

        val rawLabel = try {
            ai.loadLabel(packageManager)
        } catch (e: Exception) {
            ai.packageName
        }
        val sanitizedLabel = LabelSanitizer.sanitize(rawLabel, ai.packageName)
        val escapedLabel = TextUtils.htmlEncode(sanitizedLabel)
        val escapedPermDescription = TextUtils.htmlEncode(getString(R.string.permission_group_description))

        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            button1.setOnClickListener {
                setResult(uid, pid, code, allowed = true, onetime = false)
                dialog.dismiss()
            }
            button3.setOnClickListener {
                setResult(uid, pid, code, allowed = false, onetime = true)
                dialog.dismiss()
            }
            title.text = HtmlCompat.fromHtml(
                getString(
                    R.string.permission_warning_template,
                    escapedLabel,
                    escapedPermDescription
                )
            )
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(false)
            .setOnDismissListener {
                if (!resultDispatched) {
                    setResult(uid, pid, code, allowed = false, onetime = true)
                }
                finish()
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    override fun onDestroy() {
        if (!resultDispatched && !isChangingConfigurations && requestUid > 0 && requestPid > 0) {
            setResult(requestUid, requestPid, requestCode, allowed = false, onetime = true)
        }
        super.onDestroy()
    }
}
