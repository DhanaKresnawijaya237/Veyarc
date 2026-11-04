package com.veyarc.automation.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one consented MediaProjection session alive until its latch session ends.
 * The service owns only the temporary frame source; it never encodes video or writes screenshots.
 */
class MediaProjectionCaptureService : Service() {
    private var capture: MediaProjectionFrameCapture? = null
    private var requestToken: String? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            stop(context, "Screen capture ended because the screen turned off.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (capture?.isActive == true) return START_NOT_STICKY
        capture?.close()
        capture = null
        val token = intent?.getStringExtra(EXTRA_REQUEST_TOKEN)
        if (token == null || !MediaProjectionCaptureRuntime.isPrepared(token)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        var startedSession: MediaProjectionFrameCapture? = null
        var runtimeAttached = false
        return try {
            val notification = buildNotification(this, intent.getBooleanExtra(EXTRA_WORKFLOW_SESSION, false))
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
                if (MediaProjectionCaptureRuntime.notifyTerminated(reason, token = token)) {
                    expectedStop.set(true)
                    stopSelf(startId)
                }
            }
            startedSession = session
            session.start(resultCode, resultData, displaySpec)
            check(MediaProjectionCaptureRuntime.attach(session, token)) { "The screen-capture request ended before startup finished" }
            runtimeAttached = true
            capture = session
            requestToken = token
            serviceRunning.set(true)
            START_NOT_STICKY
        } catch (error: Throwable) {
            startedSession?.close()
            val reason = error.message ?: error.javaClass.simpleName
            expectedStop.set(true)
            if (runtimeAttached || !MediaProjectionCaptureRuntime.isActive) {
                MediaProjectionCaptureRuntime.notifyTerminated("MediaProjection capture could not start: $reason", token = token)
            }
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        val expected = expectedStop.compareAndSet(true, false)
        val wasRunning = serviceRunning.getAndSet(false)
        val session = capture
        capture = null
        if (session != null) MediaProjectionCaptureRuntime.detach(session)
        session?.close()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (wasRunning && !expected && requestToken != null) {
            MediaProjectionCaptureRuntime.notifyTerminated(
                "MediaProjection capture stopped unexpectedly. The workflow must stop safely.",
                token = requestToken,
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
        private const val EXTRA_REQUEST_TOKEN = "requestToken"
        private const val EXTRA_WORKFLOW_SESSION = "workflowSession"
        private val serviceRunning = AtomicBoolean(false)
        private val expectedStop = AtomicBoolean(false)

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            displaySpec: MediaProjectionDisplaySpec = MediaProjectionDisplaySpec.fromContext(context),
            owner: Any? = null,
            workflowSession: Boolean = false,
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
            if (workflowSession) check(ReplayFloatingController.isEnabled(context)) { "Turn on the edge latch before sharing the screen." }
            val token = MediaProjectionCaptureRuntime.prepare(owner, workflowSession)
            createChannel(context)
            expectedStop.set(false)
            val intent = Intent(context, MediaProjectionCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_WIDTH_PX, displaySpec.widthPx)
                .putExtra(EXTRA_HEIGHT_PX, displaySpec.heightPx)
                .putExtra(EXTRA_DENSITY_DPI, displaySpec.densityDpi)
                .putExtra(EXTRA_REQUEST_TOKEN, token)
                .putExtra(EXTRA_WORKFLOW_SESSION, workflowSession)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Throwable) {
                MediaProjectionCaptureRuntime.notifyTerminated("Screen capture could not start.", token = token)
                throw error
            }
        }

        fun endWorkflowSession(context: Context, reason: String = "Screen capture ended with the edge latch.") {
            stop(context, reason, owner = MediaProjectionCaptureRuntime.invalidateWorkflowSession())
        }

        /** A deliberate editor capture can replace an idle workflow session, never an active run. */
        fun stopIdleWorkflowSession(context: Context) {
            MediaProjectionCaptureRuntime.idleWorkflowOwner()?.let { stop(context, "Starting a separate screen capture.", owner = it) }
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

        private fun buildNotification(context: Context, workflowSession: Boolean): Notification {
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
                .setContentText(if (workflowSession) "Shared across runs until the edge latch or screen sharing is turned off" else "Capturing a temporary screenshot")
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
    private var requestToken: String? = null
    private var workflowRequest = false
    private var workflowOwner = Any()
    private var workflowBorrower: Any? = null

    fun workflowConsentOwner(): Any = synchronized(lock) { workflowOwner }

    fun isCurrentWorkflowOwner(owner: Any): Boolean = synchronized(lock) { workflowOwner === owner }

    val workflowCaptureAvailable: Boolean
        get() = synchronized(lock) { workflowRequest && captureOwner === workflowOwner && activeCapture?.isActive == true }

    internal fun idleWorkflowOwner(): Any? = synchronized(lock) {
        if (!workflowRequest || captureOwner !== workflowOwner || workflowBorrower != null) return@synchronized null
        workflowOwner.also { workflowOwner = Any() }
    }

    internal fun invalidateWorkflowSession(): Any = synchronized(lock) {
        val previous = workflowOwner
        workflowOwner = Any()
        previous
    }

    fun reserveWorkflowCapture(borrower: Any, allowPending: Boolean = false): Any? = synchronized(lock) {
        if (!workflowRequest || captureOwner !== workflowOwner || (activeCapture?.isActive != true && !allowPending) ||
            (workflowBorrower != null && workflowBorrower !== borrower)
        ) {
            return@synchronized null
        }
        workflowBorrower = borrower
        workflowOwner
    }

    fun resumeWorkflowCapture(borrower: Any, displaySpec: MediaProjectionDisplaySpec) = synchronized(lock) {
        check(workflowBorrower === borrower && captureOwner === workflowOwner) { "This run no longer owns screen capture." }
        checkNotNull(activeCapture).resume(displaySpec)
    }

    fun releaseWorkflowCapture(borrower: Any): Boolean = synchronized(lock) {
        if (workflowBorrower !== borrower) return@synchronized false
        activeCapture?.pause()
        workflowBorrower = null
        true
    }

    internal fun isPrepared(token: String): Boolean = synchronized(lock) {
        requestToken == token && (!workflowRequest || captureOwner === workflowOwner)
    }

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

    internal fun attach(capture: MediaProjectionSession, token: String? = null): Boolean {
        synchronized(lock) {
            if (token != null && !isPrepared(token)) return false
            check(activeCapture == null) { "Another MediaProjection capture is already active" }
            activeCapture = capture
            lastTerminationReason = null
            activeSignal.countDown()
            return true
        }
    }

    internal fun detach(capture: MediaProjectionSession) {
        synchronized(lock) {
            if (activeCapture === capture) activeCapture = null
            activeSignal.countDown()
        }
    }

    internal fun notifyTerminated(reason: String, owner: Any? = null, token: String? = null): Boolean {
        val (capture, listener) = synchronized(lock) {
            if (owner != null && captureOwner !== owner) return false
            if (token != null && requestToken != token) return false
            val currentCapture = activeCapture
            activeCapture = null
            captureOwner = null
            requestToken = null
            if (workflowRequest) workflowOwner = Any()
            workflowRequest = false
            workflowBorrower = null
            lastTerminationReason = reason
            currentCapture to terminationListener
        }
        capture?.close()
        synchronized(lock) { activeSignal.countDown() }
        runCatching { listener?.invoke(reason) }
        return true
    }

    internal fun prepare(owner: Any? = null, workflowSession: Boolean = false): String {
        synchronized(lock) {
            check(activeCapture == null && captureOwner == null && requestToken == null) { "Another MediaProjection capture is already active" }
            check(!workflowSession || owner === workflowOwner) { "The edge latch session ended. Start the run again." }
            activeSignal = CountDownLatch(1)
            captureOwner = owner
            workflowRequest = workflowSession
            lastTerminationReason = null
            return UUID.randomUUID().toString().also { requestToken = it }
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(key, T::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(key)
}
