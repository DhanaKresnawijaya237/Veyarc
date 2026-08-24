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
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps an emergency replay stop action visible while the target app is foreground. */
class RawReplayForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        return try {
            val notification = buildNotification(this, targetPackage)
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
            CoordinateRawTraceReplayRuntime.cancel(
                "Replay emergency control could not start: ${error.message ?: error.javaClass.simpleName}",
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
        if (wasRunning && !expected) {
            CoordinateRawTraceReplayRuntime.cancel(
                "Replay emergency control stopped unexpectedly. Any active replay was cancelled.",
            )
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "veyarc_raw_replay"
        private const val NOTIFICATION_ID = 42_703
        private const val STOP_REQUEST_CODE = 42_704
        private const val EXTRA_TARGET_PACKAGE = "targetPackage"
        private val serviceRunning = AtomicBoolean(false)
        private val expectedStop = AtomicBoolean(false)

        fun notificationUnavailableReason(context: Context): String? {
            createChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return "Android's notification service is unavailable."
            if (!manager.areNotificationsEnabled()) {
                return "Veyarc notifications are disabled. Enable them before raw replay so the emergency Stop control is visible."
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                return "The Veyarc raw replay notification channel is disabled. Enable it before replay."
            }
            return null
        }

        fun start(context: Context, targetPackage: String) {
            createChannel(context)
            expectedStop.set(false)
            val intent = Intent(context, RawReplayForegroundService::class.java)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (serviceRunning.get()) expectedStop.set(true)
            context.stopService(Intent(context, RawReplayForegroundService::class.java))
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Veyarc raw replay",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows the emergency stop control while raw touch replay runs."
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context, targetPackage: String): Notification {
            val stopIntent = Intent(context, RawReplayStopReceiver::class.java)
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
                .setContentTitle("Veyarc raw replay")
                .setContentText("Replaying ${targetPackage.ifBlank { "the target app" }}")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "Stop replay", stopPendingIntent)
                .build()
        }
    }
}

class RawReplayStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CoordinateRawTraceReplayRuntime.cancel("Replay cancelled from the emergency notification.")
    }
}

/** Process-owned bridge used by the notification receiver to reach the active replay controller. */
object CoordinateRawTraceReplayRuntime {
    @Volatile
    private var activeController: CoordinateRawTraceReplayController? = null

    @Synchronized
    fun register(controller: CoordinateRawTraceReplayController) {
        activeController = controller
    }

    @Synchronized
    fun unregister(controller: CoordinateRawTraceReplayController) {
        if (activeController === controller) activeController = null
    }

    fun cancel(reason: String) {
        activeController?.cancel(reason)
    }
}
