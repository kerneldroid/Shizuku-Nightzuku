package moe.shizuku.manager.nightdog

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager-side IPC coordinator for the `NightDog` watchdog daemon inside `shizuku_server`,
 * ported from Nightzuku and integrated with thedjchi's [moe.shizuku.manager.service.WatchdogService].
 */
object NightDogClient {

    private const val KEY_NIGHTDOG_PENDING_SYNC = "nightdog_pending_sync"

    data class ServerWatchdogStatus(
        val enabled: Boolean,
        val started: Boolean,
        val boundServicesCount: Int,
        val totalRebinds: Int,
        val beatCount: Long,
        val uptimeMs: Long,
        val boundServices: List<String>
    )

    private val initialized = AtomicBoolean(false)

    private val _watchdogStatus = MutableStateFlow<ServerWatchdogStatus?>(null)
    val watchdogStatus: StateFlow<ServerWatchdogStatus?> = _watchdogStatus.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        syncWithServer()
        refreshWatchdogStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _watchdogStatus.value = null
    }

    @JvmStatic
    @JvmOverloads
    fun init(context: Context? = null) {
        if (!initialized.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    @JvmStatic
    fun isServerAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            null
        }
        val cached = prefs?.getBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false) ?: false
        val pendingSync = prefs?.getBoolean(KEY_NIGHTDOG_PENDING_SYNC, false) ?: false

        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) {
            return cached
        }

        if (pendingSync && sendSetEnabledTransaction(binder, cached)) {
            prefs?.edit()?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)?.apply()
            refreshWatchdogStatus()
            return cached
        }

        val remoteEnabled = sendGetEnabledTransaction(binder) ?: return cached
        return remoteEnabled
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean): Boolean {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            null
        }

        val binder = Shizuku.getBinder()
        val appliedToServer = if (binder != null && binder.pingBinder()) {
            sendSetEnabledTransaction(binder, enabled)
        } else {
            false
        }

        prefs?.edit()
            ?.putBoolean(KEY_NIGHTDOG_PENDING_SYNC, !appliedToServer)
            ?.apply()

        refreshWatchdogStatus()
        return appliedToServer
    }

    @JvmStatic
    fun syncWithServer() {
        val prefs = try {
            ShizukuSettings.getPreferences()
        } catch (_: Throwable) {
            return
        } ?: return

        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) return

        val desired = prefs.getBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false)
        val pendingSync = prefs.getBoolean(KEY_NIGHTDOG_PENDING_SYNC, false)
        val remoteEnabled = sendGetEnabledTransaction(binder)

        if (pendingSync || remoteEnabled == null || remoteEnabled != desired) {
            if (sendSetEnabledTransaction(binder, desired)) {
                prefs.edit().putBoolean(KEY_NIGHTDOG_PENDING_SYNC, false).apply()
            }
        }
    }

    @JvmStatic
    fun refreshWatchdogStatus(): ServerWatchdogStatus? {
        val binder = Shizuku.getBinder()
        if (binder == null || !binder.pingBinder()) {
            _watchdogStatus.value = null
            return null
        }
        val status = sendGetStatusTransaction(binder)
        _watchdogStatus.value = status
        return status
    }

    private fun sendGetStatusTransaction(binder: IBinder): ServerWatchdogStatus? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_getNightDogStatus, data, reply, 0)
            if (!ok) return null
            reply.readException()
            val bundle: Bundle = reply.readBundle(NightDogClient::class.java.classLoader) ?: return null
            ServerWatchdogStatus(
                enabled = bundle.getBoolean("enabled", false),
                started = bundle.getBoolean("started", false),
                boundServicesCount = bundle.getInt("boundServicesCount", 0),
                totalRebinds = bundle.getInt("totalRebinds", 0),
                beatCount = bundle.getLong("beatCount", 0L),
                uptimeMs = bundle.getLong("uptimeMs", 0L),
                boundServices = bundle.getStringArrayList("boundServices")?.toList().orEmpty()
            )
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: getNightDogStatus failed")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendGetEnabledTransaction(binder: IBinder): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_getNightDogEnabled, data, reply, 0)
            if (!ok) return null
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: getNightDogEnabled failed")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendSetEnabledTransaction(binder: IBinder, enabled: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            val ok = binder.transact(ServerConstants.BINDER_TRANSACTION_setNightDogEnabled, data, reply, 0)
            if (!ok) return false
            reply.readException()
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "NightDogClient: setNightDogEnabled(%s) failed", enabled.toString())
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
