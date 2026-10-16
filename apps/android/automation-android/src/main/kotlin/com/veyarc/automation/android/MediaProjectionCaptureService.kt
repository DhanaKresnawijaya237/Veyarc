package com.veyarc.automation.android

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
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one consented MediaProjection session alive while a workflow evaluates visual conditions.
 * The service owns only the temporary frame source; it never encodes video or writes screenshots.
 */
class MediaProjectionCaptureService : Service() {
    private var capture: MediaProjectionFrameCapture? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var startedSession: MediaProjectionFrameCapture? = null
        var runtimeAttached = false
        return try {
            val notification = buildNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            notificationUnavailableReason(this)?.let { reason ->
                throw MediaProjectionCaptureException(reason)
            }
            if (capture != null) return START_NOT_STICKY
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val resultData = intent?.parcelableExtra<Intent>(EXTRA_RESULT_DATA)
            val displaySpec = intent?.let(::readDisplaySpec)
            require(resultData != null) { "MediaProjection consent data is missing" }
            require(displaySpec != null) { "MediaProjection display dimensions are missing" }
            val session = MediaProjectionFrameCapture(this) { reason ->
                MediaProjectionCaptureRuntime.notifyTerminated(reason)
                expectedStop.set(true)
                stopSelf()
            }
            startedSession = session
            session.start(resultCode, resultData, displaySpec)
            MediaProjectionCaptureRuntime.attach(session)
            runtimeAttached = true
            capture = session
            serviceRunning.set(true)
            START_NOT_STICKY
        } catch (error: Throwable) {
            startedSession?.close()
            val reason = error.message ?: error.javaClass.simpleName
            expectedStop.set(true)
            if (runtimeAttached || !MediaProjectionCaptureRuntime.isActive) {
                MediaProjectionCaptureRuntime.notifyTerminated("MediaProjection capture could not start: $reason")
            }
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val expected = expectedStop.compareAndSet(true, false)
        val wasRunning = serviceRunning.getAndSet(false)
        val session = capture
        capture = null
        if (session != null) MediaProjectionCaptureRuntime.detach(session)
        session?.close()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (wasRunning && !expected) {
            MediaProjectionCaptureRuntime.notifyTerminated(
                "MediaProjection capture stopped unexpectedly. The workflow must stop safely.",
            )
        }
        super.onDestroy()
    }

    private fun readDisplaySpec(intent: Intent): MediaProjectionDisplaySpec? = runCatching {
        MediaProjectionDisplaySpec(
            widthPx = intent.getIntExtra(EXTRA_WIDTH_PX, -1),
            heightPx = intent.getIntExtra(EXTRA_HEIGHT_PX, -1),
            densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, -1),
        )
    }.getOrNull()

    companion object {
        private const val CHANNEL_ID = "veyarc_media_projection"
        private const val NOTIFICATION_ID = 42_705
        private const val STOP_REQUEST_CODE = 42_706
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_WIDTH_PX = "widthPx"
        private const val EXTRA_HEIGHT_PX = "heightPx"
        private const val EXTRA_DENSITY_DPI = "densityDpi"
        private val serviceRunning = AtomicBoolean(false)
        private val expectedStop = AtomicBoolean(false)

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            displaySpec: MediaProjectionDisplaySpec = MediaProjectionDisplaySpec.fromContext(context),
            owner: Any? = null,
        ) {
            require(MediaProjectionConsent.wasGranted(resultCode, resultData)) {
                "MediaProjection consent was not granted"
            }
            check(!MediaProjectionCaptureRuntime.isActive) {
                "Another MediaProjection capture is already active"
            }
            notificationUnavailableReason(context)?.let { reason ->
                throw MediaProjectionCaptureException(reason)
            }
            MediaProjectionCaptureRuntime.prepare(owner)
            createChannel(context)
            expectedStop.set(false)
            val intent = Intent(context, MediaProjectionCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_WIDTH_PX, displaySpec.widthPx)
                .putExtra(EXTRA_HEIGHT_PX, displaySpec.heightPx)
                .putExtra(EXTRA_DENSITY_DPI, displaySpec.densityDpi)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, reason: String = "MediaProjection capture stopped.", owner: Any? = null) {
            if (owner != null && !MediaProjectionCaptureRuntime.isOwnedBy(owner)) return
            if (!MediaProjectionCaptureRuntime.notifyTerminated(reason, owner)) return
            expectedStop.set(true)
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }

        fun notificationUnavailableReason(context: Context): String? {
            createChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return "Android's notification service is unavailable."
            if (!manager.areNotificationsEnabled()) {
                return "Veyarc notifications are disabled. Enable them before using screen conditions."
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                return "The Veyarc screen-capture notification channel is disabled. Enable it before the run."
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

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Veyarc screen capture",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when temporary screen frames are available to a workflow."
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context): Notification {
            val stopIntent = Intent(context, MediaProjectionStopReceiver::class.java)
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
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Veyarc screen capture active")
                .setContentText("Temporary frames are available to this workflow")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop capture", stopPendingIntent)
                .build()
        }
    }
}

class MediaProjectionStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MediaProjectionCaptureService.stop(context, "Screen capture stopped from the emergency notification.")
    }
}

/** Process-owned bridge between the foreground service and the workflow runner. */
object MediaProjectionCaptureRuntime {
    private val lock = Any()
    private var activeCapture: MediaProjectionSession? = null
    private var terminationListener: ((String) -> Unit)? = null
    private var lastTerminationReason: String? = null
    private var activeSignal = CountDownLatch(1)
    private var captureOwner: Any? = null

    internal fun isOwnedBy(owner: Any): Boolean = synchronized(lock) { captureOwner === owner }

    val isActive: Boolean
        get() = synchronized(lock) { activeCapture?.isActive == true }

    val lastError: String?
        get() = synchronized(lock) { lastTerminationReason }

    fun captureLatestFrame(area: ScreenCaptureArea = ScreenCaptureArea.FullScreen): EphemeralScreenFrame? {
        val capture = synchronized(lock) { activeCapture }
            ?: throw MediaProjectionCaptureException("MediaProjection capture is not active")
        return capture.captureLatestFrame(area)
    }

    /** Waits for the service to attach its session without allowing a run to race service startup. */
    fun awaitActive(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0L) { "MediaProjection wait timeout must be nonnegative" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            if (isActive) return true
            if (synchronized(lock) { lastTerminationReason != null }) return false
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) return false
            val signal = synchronized(lock) { activeSignal }
            signal.await(remainingNanos, TimeUnit.NANOSECONDS)
        }
    }

    fun registerTerminationListener(listener: (String) -> Unit): Closeable {
        synchronized(lock) {
            terminationListener = listener
        }
        return Closeable {
            synchronized(lock) {
                if (terminationListener === listener) terminationListener = null
            }
        }
    }

    internal fun attach(capture: MediaProjectionSession) {
        synchronized(lock) {
            check(activeCapture == null) { "Another MediaProjection capture is already active" }
            activeCapture = capture
            lastTerminationReason = null
            activeSignal.countDown()
        }
    }

    internal fun detach(capture: MediaProjectionSession) {
        synchronized(lock) {
            if (activeCapture === capture) activeCapture = null
            activeSignal.countDown()
        }
    }

    internal fun notifyTerminated(reason: String, owner: Any? = null): Boolean {
        val (capture, listener) = synchronized(lock) {
            if (owner != null && captureOwner !== owner) return false
            val currentCapture = activeCapture
            activeCapture = null
            captureOwner = null
            lastTerminationReason = reason
            currentCapture to terminationListener
        }
        capture?.close()
        synchronized(lock) { activeSignal.countDown() }
        runCatching { listener?.invoke(reason) }
        return true
    }

    internal fun prepare(owner: Any? = null) {
        synchronized(lock) {
            check(activeCapture == null && captureOwner == null) { "Another MediaProjection capture is already active" }
            activeSignal = CountDownLatch(1)
            captureOwner = owner
            lastTerminationReason = null
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(key, T::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(key)
}
