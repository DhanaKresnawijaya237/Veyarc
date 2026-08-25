package com.veyarc.automation.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.veyarc.automation.android.IShizukuInputReplayService.Stub
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ShizukuInputReplaySnapshot(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val queuedCount: Int = 0,
    val completedCount: Int = 0,
    val remoteProcessUid: Int? = null,
    val lastCommand: String? = null,
    val lastError: String? = null,
    /** Null until the bound helper has completed its read-only capability probe. */
    val rawMotionTimelineSupported: Boolean? = null,
    /** True when replay can inject the whole timeline without one shell process per frame. */
    val directMotionTimelineSupported: Boolean? = null,
    val message: String = "Shizuku is not connected.",
)

enum class ShizukuMotionAction(val wireValue: Int) {
    DOWN(0),
    MOVE(1),
    UP(2),
}

data class ShizukuMotionFrame(
    val action: ShizukuMotionAction,
    val x: Float,
    val y: Float,
    val delayMillis: Long,
)

/**
 * Normal-app side of the bounded Shizuku input-injection client.
 *
 * This client exposes only fixed tap/swipe requests and a validated raw single-pointer trace. It
 * does not provide a general shell runner or connect the diagnostic backend to workflow execution.
 */
class ShizukuInputReplay(
    context: Context,
) : Closeable {
    private sealed interface ReplayCommand {
        data class Tap(
            val x: Float,
            val y: Float,
        ) : ReplayCommand

        data class Swipe(
            val startX: Float,
            val startY: Float,
            val endX: Float,
            val endY: Float,
            val durationMillis: Long,
        ) : ReplayCommand

        data class MotionTrace(
            val frames: List<ShizukuMotionFrame>,
        ) : ReplayCommand
    }

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(applicationContext.packageName, ShizukuInputReplayUserService::class.java.name),
    ).daemon(false)
        .tag("veyarc-input-replay")
        .version(1)
        .processNameSuffix("input-replay")

    @Volatile
    private var currentSnapshot = ShizukuInputReplaySnapshot()

    @Volatile
    private var callback: ((ShizukuInputReplaySnapshot) -> Unit)? = null

    @Volatile
    private var remoteService: IShizukuInputReplayService? = null

    @Volatile
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile
    private var pendingCommands: List<ReplayCommand>? = null

    @Volatile
    private var activeRequest = false

    @Volatile
    private var expectedServiceDisconnect = false

    @Volatile
    private var closed = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        stopPolling()
        remoteService = null
        activeRequest = false
        pendingCommands = null
        publish(
            currentSnapshot.copy(
                binderAvailable = false,
                permissionGranted = false,
                serviceConnected = false,
                running = false,
                rawMotionTimelineSupported = null,
                directMotionTimelineSupported = null,
                message = "Shizuku stopped. Start it again, then refresh.",
            ),
        )
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            refresh(messageOverride = "Shizuku permission granted for replay testing.")
        } else {
            publish(currentSnapshot.copy(message = "Shizuku permission was not granted."))
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            remoteService = Stub.asInterface(binder)
            val service = remoteService ?: return
            publish(
                currentSnapshot.copy(
                    serviceConnected = true,
                    remoteProcessUid = runCatching { service.getProcessUid() }.getOrNull(),
                    message = "Replay helper connected. Preparing the bounded test…",
                ),
            )
            pendingCommands?.let { commands -> submit(commands, service) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            stopPolling()
            val expected = expectedServiceDisconnect
            expectedServiceDisconnect = false
            if (expected) {
                publish(currentSnapshot.copy(serviceConnected = false, running = false))
            } else {
                activeRequest = false
                pendingCommands = null
                publish(
                    currentSnapshot.copy(
                        serviceConnected = false,
                        running = false,
                        message = "The Shizuku replay helper disconnected unexpectedly.",
                    ),
                )
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun snapshot(): ShizukuInputReplaySnapshot = currentSnapshot

    fun setCallback(callback: ((ShizukuInputReplaySnapshot) -> Unit)?) {
        this.callback = callback
        callback?.let { listener -> mainHandler.post { listener(currentSnapshot) } }
    }

    fun refresh(messageOverride: String? = null) {
        if (closed) return
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            publish(
                currentSnapshot.copy(
                    binderAvailable = false,
                    permissionGranted = false,
                    serviceConnected = false,
                    running = false,
                    message = messageOverride ?: "Shizuku is not running or its binder is not ready.",
                ),
            )
            return
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        publish(
            currentSnapshot.copy(
                binderAvailable = true,
                permissionGranted = permissionGranted,
                message = messageOverride ?: if (permissionGranted) {
                    "Shizuku is ready for the bounded replay test."
                } else {
                    "Grant Veyarc permission in Shizuku before replay testing."
                },
            ),
        )
    }

    fun requestPermission() {
        if (closed) return
        try {
            if (!Shizuku.pingBinder()) {
                refresh("Start Shizuku first, then refresh Veyarc.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh("Shizuku permission is already granted.")
                return
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            publish(currentSnapshot.copy(message = "Approve Veyarc in the Shizuku permission prompt."))
        } catch (error: Throwable) {
            publish(
                currentSnapshot.copy(
                    message = "Could not request Shizuku permission: " +
                        (error.message ?: error.javaClass.simpleName),
                ),
            )
        }
    }

    fun dispatchTap(x: Float, y: Float) {
        request(
            listOf(ReplayCommand.Tap(x, y)),
            "Preparing one bounded Shizuku tap…",
        )
    }

    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long,
    ) {
        request(
            listOf(
                ReplayCommand.Swipe(
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                    durationMillis = durationMillis,
                ),
            ),
            "Preparing one bounded Shizuku swipe…",
        )
    }

    fun dispatchQuickSequence(
        firstTapX: Float,
        firstTapY: Float,
        secondTapX: Float,
        secondTapY: Float,
        swipeStartX: Float,
        swipeStartY: Float,
        swipeEndX: Float,
        swipeEndY: Float,
        swipeDurationMillis: Long = DEFAULT_SWIPE_DURATION_MILLIS,
    ) {
        request(
            listOf(
                ReplayCommand.Tap(firstTapX, firstTapY),
                ReplayCommand.Tap(secondTapX, secondTapY),
                ReplayCommand.Swipe(
                    startX = swipeStartX,
                    startY = swipeStartY,
                    endX = swipeEndX,
                    endY = swipeEndY,
                    durationMillis = swipeDurationMillis,
                ),
            ),
            "Preparing two quick taps followed by a bounded swipe…",
        )
    }

    /** Requests a raw trace replay after the helper proves motionevent support. */
    fun dispatchRawMotionTrace(frames: List<ShizukuMotionFrame>) {
        validateMotionTrace(frames)
        request(
            listOf(ReplayCommand.MotionTrace(frames.toList())),
            "Preparing the approved raw touch trace…",
        )
    }

    fun cancel() {
        if (closed) return
        val service = remoteService
        activeRequest = false
        pendingCommands = null
        stopPolling()
        runCatching { service?.cancel() }
        finishService("Replay cancelled. The helper was stopped and the queue was cleared.")
    }

    override fun close() {
        if (closed) return
        closed = true
        val service = remoteService
        runCatching { service?.cancel() }
        finishService(null)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        callback = null
        executor.shutdownNow()
    }

    private fun request(commands: List<ReplayCommand>, message: String) {
        if (closed) return
        if (activeRequest || currentSnapshot.running) {
            publish(currentSnapshot.copy(message = "A Shizuku replay is already running."))
            return
        }
        refresh()
        val snapshot = currentSnapshot
        if (!snapshot.binderAvailable) {
            publish(snapshot.copy(message = "Start Shizuku first, then refresh Veyarc."))
            return
        }
        if (!snapshot.permissionGranted) {
            publish(snapshot.copy(message = "Grant Veyarc permission in Shizuku first."))
            return
        }
        activeRequest = true
        pendingCommands = commands
        publish(snapshot.copy(message = message))
        val service = remoteService
        if (service != null) {
            submit(commands, service)
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (error: Throwable) {
            activeRequest = false
            pendingCommands = null
            publish(
                currentSnapshot.copy(
                    message = "Could not start the Shizuku replay UserService: " +
                        (error.message ?: error.javaClass.simpleName),
                ),
            )
        }
    }

    private fun submit(commands: List<ReplayCommand>, service: IShizukuInputReplayService) {
        executor.execute {
            try {
                service.clearQueue()
                val motionTrace = commands.filterIsInstance<ReplayCommand.MotionTrace>().singleOrNull()
                val capabilities = if (motionTrace != null) service.getCapabilities() else null
                if (motionTrace != null && capabilities?.and(CAPABILITY_RAW_MOTION_TRACE) == 0) {
                    throw IllegalStateException(
                        "The Shizuku helper cannot prove motionevent replay support on this device",
                    )
                }
                commands.forEach { command ->
                    when (command) {
                        is ReplayCommand.Tap -> service.enqueueTap(command.x, command.y)

                        is ReplayCommand.Swipe ->
                            service.enqueueSwipe(
                                command.startX,
                                command.startY,
                                command.endX,
                                command.endY,
                                command.durationMillis,
                            )

                        is ReplayCommand.MotionTrace -> {
                            val actions = IntArray(command.frames.size)
                            val xs = FloatArray(command.frames.size)
                            val ys = FloatArray(command.frames.size)
                            val delays = LongArray(command.frames.size)
                            command.frames.forEachIndexed { index, frame ->
                                actions[index] = frame.action.wireValue
                                xs[index] = frame.x
                                ys[index] = frame.y
                                delays[index] = frame.delayMillis
                            }
                            service.enqueueMotionTrace(actions, xs, ys, delays)
                        }
                    }
                }
                service.startQueue()
                pendingCommands = null
                publish(
                    currentSnapshot.copy(
                        serviceConnected = true,
                        running = true,
                        rawMotionTimelineSupported = capabilities?.let {
                            it and CAPABILITY_RAW_MOTION_TRACE != 0
                        } ?: currentSnapshot.rawMotionTimelineSupported,
                        directMotionTimelineSupported = capabilities?.let {
                            it and CAPABILITY_DIRECT_MOTION_TRACE != 0
                        } ?: currentSnapshot.directMotionTimelineSupported,
                        remoteProcessUid = runCatching { service.getProcessUid() }.getOrNull(),
                        message = if (capabilities?.and(CAPABILITY_DIRECT_MOTION_TRACE) != 0) {
                            "Replay running through direct InputManager injection."
                        } else {
                            "Replay running through the shell-identity fallback."
                        },
                    ),
                )
                startPolling()
            } catch (error: Throwable) {
                finishService(
                    "Shizuku replay could not start: ${error.message ?: error.javaClass.simpleName}",
                    cancelRemote = true,
                )
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        pollTask = executor.scheduleAtFixedRate(::pollRemoteService, 0L, 100L, TimeUnit.MILLISECONDS)
    }

    private fun stopPolling() {
        pollTask?.cancel(false)
        pollTask = null
    }

    private fun pollRemoteService() {
        val service = remoteService ?: return
        try {
            val running = service.isRunning()
            val completedCount = service.getCompletedCount()
            val queuedCount = service.getQueuedCount()
            val lastError = service.getLastError()
            publish(
                currentSnapshot.copy(
                    serviceConnected = true,
                    running = running,
                    queuedCount = queuedCount,
                    completedCount = completedCount,
                    remoteProcessUid = service.getProcessUid(),
                    lastCommand = service.getLastCommand(),
                    lastError = lastError,
                    message = when {
                        running && currentSnapshot.directMotionTimelineSupported == true ->
                            "Replay running through direct InputManager injection."

                        running -> "Replay running through the shell-identity fallback."

                        lastError != null -> "Replay failed: $lastError"

                        else -> "Replay completed: $completedCount command(s)."
                    },
                ),
            )
            if (!running) finishService(null, keepSnapshot = true)
        } catch (error: Throwable) {
            finishService(
                "The Shizuku replay helper failed: ${error.message ?: error.javaClass.simpleName}",
                cancelRemote = true,
            )
        }
    }

    private fun finishService(
        message: String?,
        keepSnapshot: Boolean = false,
        cancelRemote: Boolean = false,
    ) {
        stopPolling()
        pendingCommands = null
        activeRequest = false
        val service = remoteService
        remoteService = null
        if (cancelRemote) runCatching { service?.cancel() }
        expectedServiceDisconnect = true
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        if (!closed) {
            if (keepSnapshot) {
                publish(currentSnapshot.copy(serviceConnected = false, running = false))
            } else {
                publish(
                    currentSnapshot.copy(
                        serviceConnected = false,
                        running = false,
                        message = message ?: currentSnapshot.message,
                    ),
                )
            }
        }
    }

    private fun publish(snapshot: ShizukuInputReplaySnapshot) {
        if (closed) return
        currentSnapshot = snapshot
        val listener = callback ?: return
        mainHandler.post { if (!closed) listener(snapshot) }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 20_427
        const val DEFAULT_SWIPE_DURATION_MILLIS = 700L
        const val CAPABILITY_RAW_MOTION_TRACE = 1
        const val CAPABILITY_DIRECT_MOTION_TRACE = 2
        const val MAX_MOTION_FRAME_COUNT = 16_384
        const val MAX_MOTION_DELAY_MILLIS = 300_000L
        const val MAX_COORDINATE = 30_000f
    }

    private fun validateMotionTrace(frames: List<ShizukuMotionFrame>) {
        require(frames.isNotEmpty() && frames.size <= MAX_MOTION_FRAME_COUNT) {
            "Raw replay must contain between 1 and $MAX_MOTION_FRAME_COUNT frames"
        }
        var active = false
        frames.forEach { frame ->
            require(frame.x.isFinite() && frame.y.isFinite()) { "Raw replay coordinates must be finite" }
            require(frame.x in 0f..MAX_COORDINATE && frame.y in 0f..MAX_COORDINATE) {
                "Raw replay coordinates are outside the supported display range"
            }
            require(frame.delayMillis in 0L..MAX_MOTION_DELAY_MILLIS) {
                "Raw replay frame delay is outside the supported range"
            }
            when (frame.action) {
                ShizukuMotionAction.DOWN -> check(!active) {
                    "Raw replay cannot start a second touch before UP"
                }.also { active = true }

                ShizukuMotionAction.MOVE -> check(active) { "Raw replay MOVE requires an active touch" }

                ShizukuMotionAction.UP -> check(active) {
                    "Raw replay UP requires an active touch"
                }.also { active = false }
            }
        }
        check(!active) { "Raw replay must end with UP" }
    }
}
