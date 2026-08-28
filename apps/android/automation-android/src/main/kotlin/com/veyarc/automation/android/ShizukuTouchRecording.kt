package com.veyarc.automation.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Events emitted by the bounded, read-only evdev recording helper. */
sealed interface ShizukuTouchRecordingEvent {
    data class Started(
        val startTimestampMicros: Long,
        val deviceSummary: String,
        val minX: Long,
        val maxX: Long,
        val minY: Long,
        val maxY: Long,
        /** Monotonic timestamp captured when this callback reached the app process. */
        val receivedTimestampMicros: Long = startTimestampMicros,
    ) : ShizukuTouchRecordingEvent

    data class Frame(
        val timestampMicros: Long,
        val action: Action,
        val x: Long,
        val y: Long,
        /** Monotonic timestamp captured when this callback reached the app process. */
        val receivedTimestampMicros: Long = timestampMicros,
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
    private var activeServiceConnection: ServiceConnection? = null

    @Volatile
    private var closed = false

    private val bindingLock = Any()
    private val bindingGeneration = AtomicLong(0L)

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val generation = bindingGeneration.get()
        if (closed || activeServiceConnection == null) return@OnBinderDeadListener
        mainHandler.post {
            if (!isCurrentGeneration(generation)) return@post
            publishOnMainForGeneration(
                generation,
                ShizukuTouchRecordingEvent.Error(
                    "Shizuku stopped while the raw-input helper was recording.",
                ),
            )
            finishServiceOnMain(generation)
        }
    }

    private fun newCallback(generation: Long) = object : IShizukuTouchRecordingCallback.Stub() {
        override fun onStarted(
            startTimestampMicros: Long,
            deviceSummary: String?,
            minX: Long,
            maxX: Long,
            minY: Long,
            maxY: Long,
        ) {
            val receivedTimestampMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
            publishForGeneration(
                generation,
                ShizukuTouchRecordingEvent.Started(
                    startTimestampMicros = startTimestampMicros,
                    deviceSummary = deviceSummary.orEmpty(),
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                    receivedTimestampMicros = receivedTimestampMicros,
                ),
            )
        }

        override fun onFrame(timestampMicros: Long, action: Int, x: Long, y: Long) {
            val receivedTimestampMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
            val mappedAction = when (action) {
                0 -> ShizukuTouchRecordingEvent.Action.DOWN

                1 -> ShizukuTouchRecordingEvent.Action.MOVE

                2 -> ShizukuTouchRecordingEvent.Action.UP

                else -> {
                    publishForGeneration(
                        generation,
                        ShizukuTouchRecordingEvent.Error(
                            "The raw-input helper returned an unknown frame action.",
                        ),
                    )
                    return
                }
            }
            publishForGeneration(
                generation,
                ShizukuTouchRecordingEvent.Frame(
                    timestampMicros = timestampMicros,
                    action = mappedAction,
                    x = x,
                    y = y,
                    receivedTimestampMicros = receivedTimestampMicros,
                ),
            )
        }

        override fun onError(message: String?) {
            publishForGeneration(
                generation,
                ShizukuTouchRecordingEvent.Error(message ?: "The raw-input helper failed."),
            )
        }

        override fun onCompleted() {
            mainHandler.post {
                if (closed || !isCurrentGeneration(generation)) return@post
                publishOnMainForGeneration(generation, ShizukuTouchRecordingEvent.Completed)
                finishServiceOnMain(generation)
            }
        }
    }

    private fun newServiceConnection(
        generation: Long,
        durationMillis: Long,
        callback: IShizukuTouchRecordingCallback,
    ) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IShizukuTouchProbeService.Stub.asInterface(binder)
            val current = synchronized(bindingLock) {
                if (closed || !isCurrentConnection(generation, this)) {
                    false
                } else {
                    remoteService = service
                    true
                }
            }
            if (!current) {
                runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
                return
            }
            try {
                service.startRecording(durationMillis, callback)
            } catch (error: Throwable) {
                publishForGeneration(
                    generation,
                    ShizukuTouchRecordingEvent.Error(
                        "Shizuku could not start recording: ${error.message ?: error.javaClass.simpleName}",
                    ),
                )
                mainHandler.post { finishServiceOnMain(generation) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mainHandler.post {
                if (closed || !isCurrentConnection(generation, this)) return@post
                publishOnMainForGeneration(
                    generation,
                    ShizukuTouchRecordingEvent.Error(
                        "The Shizuku raw-input helper disconnected before recording completed.",
                    ),
                )
                publishOnMainForGeneration(generation, ShizukuTouchRecordingEvent.Completed)
                finishServiceOnMain(generation, unbind = false)
            }
        }
    }

    init {
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    /** Returns null when the transaction was accepted, otherwise a user-readable reason. */
    fun start(
        durationMillis: Long = DEFAULT_DURATION_MILLIS,
        listener: (ShizukuTouchRecordingEvent) -> Unit,
    ): String? {
        if (closed) return "The raw-input recorder is closed."
        if (remoteService != null || activeServiceConnection != null) {
            return "A raw-input recording is already running."
        }
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) return "Shizuku is not running or its binder is not ready."
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) return "Grant Veyarc permission in Shizuku before recording."

        val checkedDuration = durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
        val generation = bindingGeneration.incrementAndGet()
        val callback = newCallback(generation)
        val connection = newServiceConnection(generation, checkedDuration, callback)
        val registered = synchronized(bindingLock) {
            if (closed || activeServiceConnection != null || remoteService != null) {
                false
            } else {
                this.listener = listener
                activeServiceConnection = connection
                true
            }
        }
        if (!registered) return "A raw-input recording is already running."
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
            return null
        } catch (error: Throwable) {
            synchronized(bindingLock) {
                if (isCurrentConnection(generation, connection)) clearBindingLocked()
            }
            return "Could not start the Shizuku raw-input helper: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Requests a cooperative stop; the helper still sends Completed through the callback. */
    fun stop() {
        runCatching { remoteService?.stopObservation() }
    }

    /**
     * Aborts a transaction when its callback cannot be trusted to arrive. The object remains
     * reusable for a later recording, unlike [close], which permanently shuts it down.
     */
    fun abort() {
        if (closed) return
        val generation = bindingGeneration.get()
        runCatching { remoteService?.stopObservation() }
        mainHandler.post { finishServiceOnMain(generation) }
    }

    fun isReady(): Boolean = !closed && runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    override fun close() {
        if (closed) return
        closed = true
        val generation = bindingGeneration.get()
        runCatching { remoteService?.stopObservation() }
        listener = null
        mainHandler.post { finishServiceOnMain(generation) }
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    private fun publishForGeneration(generation: Long, event: ShizukuTouchRecordingEvent) {
        mainHandler.post { publishOnMainForGeneration(generation, event) }
    }

    private fun publishOnMainForGeneration(generation: Long, event: ShizukuTouchRecordingEvent) {
        if (closed || !isCurrentGeneration(generation)) return
        listener?.invoke(event)
    }

    private fun finishServiceOnMain(generation: Long, unbind: Boolean = true) {
        val (service, connection) = synchronized(bindingLock) {
            val currentConnection = activeServiceConnection
            if (bindingGeneration.get() != generation || currentConnection == null) return
            val currentService = remoteService
            clearBindingLocked()
            currentService to currentConnection
        }
        runCatching { service?.stopObservation() }
        if (unbind) runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
    }

    private fun isCurrentGeneration(generation: Long): Boolean = bindingGeneration.get() == generation && activeServiceConnection != null

    private fun isCurrentConnection(generation: Long, connection: ServiceConnection): Boolean = bindingGeneration.get() == generation && activeServiceConnection === connection

    /** Invalidates every late callback before the next session can register. Must hold the lock. */
    private fun clearBindingLocked() {
        remoteService = null
        activeServiceConnection = null
        listener = null
        bindingGeneration.incrementAndGet()
    }

    private companion object {
        const val MIN_DURATION_MILLIS = 1_000L
        const val DEFAULT_DURATION_MILLIS = 60_000L
        const val MAX_DURATION_MILLIS = 5L * 60L * 1_000L
    }
}
