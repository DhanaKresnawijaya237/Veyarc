package com.veyarc.feature.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the visible stop control while a raw-input recording is armed or active. */
class RecordingForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        val notification = buildNotification(this, text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            serviceRunning.set(true)
            START_NOT_STICKY
        } catch (error: Throwable) {
            CoordinateRecordingRuntime.notifyRecordingServiceLost(
                "The recording notification could not be started: ${error.message ?: error.javaClass.simpleName}",
            )
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val expected = expectedStop.compareAndSet(true, false)
        val wasRunning = serviceRunning.getAndSet(false)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        RecordingQuickSettingsTileService.requestRefresh(this)
        if (wasRunning && !expected) {
            CoordinateRecordingRuntime.notifyRecordingServiceLost()
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.veyarc.feature.recorder.action.START"
        private const val CHANNEL_ID = "veyarc_recording"
        private const val EXTRA_TEXT = "text"
        private const val NOTIFICATION_ID = 42_701
        private const val STOP_REQUEST_CODE = 42_702
        private const val DEFAULT_TEXT = "Recording armed. Open the target app, then stop here."
        private val serviceRunning = AtomicBoolean(false)
        private val expectedStop = AtomicBoolean(false)

        fun isNotificationReady(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!manager.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun notificationUnavailableReason(context: Context): String? {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return "Android's notification service is unavailable."
            if (!manager.areNotificationsEnabled()) {
                return "Veyarc notifications are disabled by Android. Enable them before recording so the emergency Stop control is always visible."
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                return "The Veyarc recording notification channel is disabled. Enable it before recording so the emergency Stop control is always visible."
            }
            return null
        }

        fun openNotificationSettings(context: Context) {
            createChannel(context)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun start(context: Context, text: String = DEFAULT_TEXT) {
            createChannel(context)
            expectedStop.set(false)
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            RecordingQuickSettingsTileService.requestRefresh(context)
        }

        fun update(context: Context, text: String) {
            createChannel(context)
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(context, text))
            RecordingQuickSettingsTileService.requestRefresh(context)
        }

        fun stop(context: Context) {
            if (serviceRunning.get()) expectedStop.set(true)
            context.stopService(Intent(context, RecordingForegroundService::class.java))
            RecordingQuickSettingsTileService.requestRefresh(context)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Veyarc recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows the emergency stop control while recording."
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context, text: String): Notification {
            val stopIntent = Intent(context, RecordingStopReceiver::class.java)
            val stopPendingIntent = PendingIntent.getBroadcast(
                context,
                STOP_REQUEST_CODE,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                Notification.Builder(context)
            }
            return builder
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("Veyarc recording")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "Stop recording", stopPendingIntent)
                .build()
        }
    }
}

class RecordingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CoordinateRecordingRuntime.controller(context).stop()
    }
}
