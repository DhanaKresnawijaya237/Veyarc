package com.veyarc.automation.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.io.Closeable

/** Events emitted by the bounded, read-only evdev recording helper. */
sealed interface ShizukuTouchRecordingEvent {
    data class Started(
        val startTimestampMicros: Long,
        val deviceSummary: String,
        val minX: Long,
        val maxX: Long,
        val minY: Long,
        val maxY: Long,
    ) : ShizukuTouchRecordingEvent

    data class Frame(
        val timestampMicros: Long,
        val action: Action,
        val x: Long,
        val y: Long,
    ) : ShizukuTouchRecordingEvent

    data class Error(val message: String) : ShizukuTouchRecordingEvent

    data object Completed : ShizukuTouchRecordingEvent

    enum class Action { DOWN, MOVE, UP }
}

/**
 * Owns one Shizuku raw-input recording transaction.
 *
 * The helper exposes only the fixed touchscreen observer; it cannot execute arbitrary shell
 * commands and never injects, intercepts, or persists input. All callbacks are delivered on the
 * main thread so the recorder can enqueue them before writing its encrypted journal.
 */
class ShizukuTouchRecording(
    context: Context,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(applicationContext.packageName, ShizukuTouchProbeUserService::class.java.name),
    ).daemon(false)
        .tag("veyarc-touch-recording")
        .version(2)
        .processNameSuffix("touch-recording")

    @Volatile
    private var listener: ((ShizukuTouchRecordingEvent) -> Unit)? = null

    @Volatile
    private var remoteService: IShizukuTouchProbeService? = null

    @Volatile
    private var bound = false

    @Volatile
    private var closed = false

    @Volatile
    private var expectedServiceDisconnect = false

    private val callback = object : IShizukuTouchRecordingCallback.Stub() {
        override fun onStarted(
            startTimestampMicros: Long,
            deviceSummary: String?,
            minX: Long,
            maxX: Long,
            minY: Long,
            maxY: Long,
        ) {
            publish(
                ShizukuTouchRecordingEvent.Started(
                    startTimestampMicros = startTimestampMicros,
                    deviceSummary = deviceSummary.orEmpty(),
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                ),
            )
        }

        override fun onFrame(timestampMicros: Long, action: Int, x: Long, y: Long) {
            val mappedAction = when (action) {
                0 -> ShizukuTouchRecordingEvent.Action.DOWN

                1 -> ShizukuTouchRecordingEvent.Action.MOVE

                2 -> ShizukuTouchRecordingEvent.Action.UP

                else -> {
                    publish(ShizukuTouchRecordingEvent.Error("The raw-input helper returned an unknown frame action."))
                    return
                }
            }
            publish(ShizukuTouchRecordingEvent.Frame(timestampMicros, mappedAction, x, y))
        }

        override fun onError(message: String?) {
            publish(ShizukuTouchRecordingEvent.Error(message ?: "The raw-input helper failed."))
        }

        override fun onCompleted() {
            mainHandler.post {
                if (closed) return@post
                publishOnMain(ShizukuTouchRecordingEvent.Completed)
                finishServiceOnMain()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            val service = IShizukuTouchProbeService.Stub.asInterface(binder)
            remoteService = service
            try {
                service.startRecording(requestedDurationMillis, callback)
            } catch (error: Throwable) {
                publish(
                    ShizukuTouchRecordingEvent.Error(
                        "Shizuku could not start recording: ${error.message ?: error.javaClass.simpleName}",
                    ),
                )
                finishServiceOnMain()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            bound = false
            val expected = expectedServiceDisconnect
            expectedServiceDisconnect = false
            if (!expected && !closed) {
                mainHandler.post {
                    if (closed) return@post
                    publishOnMain(
                        ShizukuTouchRecordingEvent.Error(
                            "The Shizuku raw-input helper disconnected before recording completed.",
                        ),
                    )
                    publishOnMain(ShizukuTouchRecordingEvent.Completed)
                    listener = null
                }
            } else if (expected) {
                listener = null
            }
        }
    }

    @Volatile
    private var requestedDurationMillis: Long = DEFAULT_DURATION_MILLIS

    /** Returns null when the transaction was accepted, otherwise a user-readable reason. */
    fun start(
        durationMillis: Long = DEFAULT_DURATION_MILLIS,
        listener: (ShizukuTouchRecordingEvent) -> Unit,
    ): String? {
        if (closed) return "The raw-input recorder is closed."
        if (remoteService != null || bound) return "A raw-input recording is already running."
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) return "Shizuku is not running or its binder is not ready."
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) return "Grant Veyarc permission in Shizuku before recording."

        this.listener = listener
        requestedDurationMillis = durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
        expectedServiceDisconnect = false
        try {
            bound = true
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            return null
        } catch (error: Throwable) {
            bound = false
            this.listener = null
            return "Could not start the Shizuku raw-input helper: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Requests a cooperative stop; the helper still sends Completed through the callback. */
    fun stop() {
        runCatching { remoteService?.stopObservation() }
    }

    fun isReady(): Boolean = !closed && runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { remoteService?.stopObservation() }
        mainHandler.post { finishServiceOnMain() }
        listener = null
    }

    private fun publish(event: ShizukuTouchRecordingEvent) {
        mainHandler.post { publishOnMain(event) }
    }

    private fun publishOnMain(event: ShizukuTouchRecordingEvent) {
        if (closed) return
        listener?.invoke(event)
    }

    private fun finishServiceOnMain() {
        if (!bound && remoteService == null) {
            listener = null
            return
        }
        val service = remoteService
        remoteService = null
        runCatching { service?.stopObservation() }
        expectedServiceDisconnect = true
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        bound = false
        listener = null
    }

    private companion object {
        const val MIN_DURATION_MILLIS = 1_000L
        const val DEFAULT_DURATION_MILLIS = 60_000L
        const val MAX_DURATION_MILLIS = 5L * 60L * 1_000L
    }
}
