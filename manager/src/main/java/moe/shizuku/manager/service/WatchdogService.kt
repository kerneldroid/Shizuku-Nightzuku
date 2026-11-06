package moe.shizuku.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.nightdog.NightDogClient
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine

class WatchdogService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var crashRecoveryJob: Job? = null
    private var telemetryPollJob: Job? = null

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when (state) {
            ShizukuStateMachine.State.RUNNING -> {
                crashRecoveryJob?.cancel()
                crashRecoveryJob = null
                serviceScope.launch {
                    NightDogClient.syncWithServer()
                    NightDogClient.refreshWatchdogStatus()
                }
            }
            ShizukuStateMachine.State.CRASHED -> {
                scheduleCrashRecovery()
            }
            else -> {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        NightDogClient.init(applicationContext)
        NightDogClient.setEnabled(true)
        ShizukuStateMachine.addListener(stateListener)
        startTelemetryPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            NightDogClient.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        NightDogClient.setEnabled(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification()
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        crashRecoveryJob?.cancel()
        crashRecoveryJob = null
        telemetryPollJob?.cancel()
        telemetryPollJob = null
        serviceScope.cancel()
        ShizukuStateMachine.removeListener(stateListener)
        isRunning.set(false)
        NightDogClient.setEnabled(false)
        ShizukuSettings.setWatchdog(applicationContext, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Coordinates with the native C++ NightDog supervisor (`libshizuku.so`):
     * when `shizuku_server` crashes or is terminated by `NightDog` for recovery, the native
     * C++ supervisor respawns `shizuku_server` after 5 seconds. Waiting briefly avoids
     * unnecessarily triggering Wireless ADB discovery when the native supervisor already
     * recovered the server process.
     */
    private fun scheduleCrashRecovery() {
        crashRecoveryJob?.cancel()
        crashRecoveryJob = serviceScope.launch {
            delay(NATIVE_SUPERVISOR_GRACE_MS)
            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                Log.i(TAG, "Server was recovered by native C++ NightDog supervisor")
                NightDogClient.syncWithServer()
                NightDogClient.refreshWatchdogStatus()
                return@launch
            }
            showCrashNotification()
            ShizukuReceiverStarter.start(applicationContext)
        }
    }

    private fun startTelemetryPolling() {
        telemetryPollJob?.cancel()
        telemetryPollJob = serviceScope.launch {
            while (isActive) {
                if (ShizukuStateMachine.isRunning()) {
                    NightDogClient.syncWithServer()
                    NightDogClient.refreshWatchdogStatus()
                }
                delay(TELEMETRY_POLL_INTERVAL_MS)
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "shizuku_watchdog"
        val channelName = "Watchdog"

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun showCrashNotification() {
        val channelId = CRASH_CHANNEL_ID
        val channelName = "Crash Reports"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val learnMoreIntent = Intent(Intent.ACTION_VIEW).apply {
            setData(Uri.parse("https://github.com/thedjchi/Shizuku/wiki#shizuku-keeps-stopping-randomly"))
        }
        val learnMorePendingIntent = PendingIntent.getActivity(
            this, 0, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(
            this, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_shizuku_crashed_title))
            .setContentText(getString(R.string.watchdog_shizuku_crashed_text))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        private const val NATIVE_SUPERVISOR_GRACE_MS = 6_500L
        private const val TELEMETRY_POLL_INTERVAL_MS = 60_000L
        const val CRASH_CHANNEL_ID = "crash_reports"

        private val isRunning = AtomicBoolean(false)

        @JvmStatic
        fun start(context: Context) {
            try {
                NightDogClient.setEnabled(true)
                context.startForegroundService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                Log.e("ShizukuApplication", "Failed to start WatchdogService: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            NightDogClient.setEnabled(false)
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}