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
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ShizukuInputReplaySnapshot(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
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
            val targetUid: Int? = null,
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

    /**
     * Each UserService bind gets a generation and a distinct ServiceConnection. Shizuku callbacks
     * are asynchronous, so an old disconnect must never tear down a later replay request.
     */
    private val bindingLock = Any()
    private val bindingGeneration = AtomicLong(0L)

    @Volatile
    private var activeServiceConnection: ServiceConnection? = null

    @Volatile
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile
    private var pendingCommands: List<ReplayCommand>? = null

    @Volatile
    private var activeRequest = false

    @Volatile
    private var pendingPause = false

    @Volatile
    private var closed = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        finishService("Shizuku stopped. Start it again, then refresh.", cancelRemote = true)
        publish(
            currentSnapshot.copy(
                binderAvailable = false,
                permissionGranted = false,
                serviceConnected = false,
                running = false,
                paused = false,
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

    private fun newServiceConnection(generation: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = Stub.asInterface(binder)
            if (service == null) {
                handleServiceDisconnected(generation, this, "The Shizuku replay helper returned no binder.")
                return
            }
            val dispose = synchronized(bindingLock) {
                if (!isCurrentBinding(generation, this) ||
                    ReplayServiceBindingPolicy.shouldDisposeConnectedService(
                        closed = closed,
                        activeRequest = activeRequest,
                        hasPendingCommands = pendingCommands != null,
                    )
                ) {
                    if (isCurrentBinding(generation, this)) retireBindingLocked()
                    true
                } else {
                    remoteService = service
                    false
                }
            }
            if (dispose) {
                disposeLateServiceBinding(service, this)
                return
            }
            publish(
                currentSnapshot.copy(
                    serviceConnected = true,
                    remoteProcessUid = runCatching { service.getProcessUid() }.getOrNull(),
                    message = "Replay helper connected. Preparing the bounded test…",
                ),
            )
            pendingCommands?.let { commands -> submit(commands, service, generation) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleServiceDisconnected(generation, this, "The Shizuku replay helper disconnected unexpectedly.")
        }

        override fun onBindingDied(name: ComponentName) {
            handleServiceDisconnected(generation, this, "The Shizuku replay helper binding died unexpectedly.")
        }

        override fun onNullBinding(name: ComponentName) {
            handleServiceDisconnected(generation, this, "The Shizuku replay helper returned no binding.")
        }
    }

    private fun disposeLateServiceBinding(
        service: IShizukuInputReplayService,
        connection: ServiceConnection,
    ) {
        runCatching { service.cancel() }
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
    }

    private fun handleServiceDisconnected(
        generation: Long,
        connection: ServiceConnection,
        unexpectedMessage: String,
    ) {
        val current = synchronized(bindingLock) {
            if (!isCurrentBinding(generation, connection)) {
                false
            } else {
                retireBindingLocked()
                true
            }
        }
        if (!current) return
        stopPolling()
        activeRequest = false
        pendingCommands = null
        pendingPause = false
        publish(
            currentSnapshot.copy(
                serviceConnected = false,
                running = false,
                paused = false,
                message = unexpectedMessage,
            ),
        )
    }

    private fun isCurrentBinding(generation: Long, connection: ServiceConnection): Boolean = bindingGeneration.get() == generation && activeServiceConnection === connection

    /** Invalidates callbacks from the current and every older bind. Must be called under the lock. */
    private fun retireBindingLocked() {
        activeServiceConnection = null
        remoteService = null
        bindingGeneration.incrementAndGet()
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
                    paused = false,
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
    fun dispatchRawMotionTrace(frames: List<ShizukuMotionFrame>, targetUid: Int? = null) {
        validateMotionTrace(frames)
        require(targetUid == null || targetUid >= 0) { "Target UID must be non-negative" }
        request(
            listOf(ReplayCommand.MotionTrace(frames.toList(), targetUid)),
            "Preparing the approved raw touch trace…",
        )
    }

    /** Requests a pause that the helper applies at the next safe gesture boundary. */
    fun pause() {
        if (closed) return
        pendingPause = true
        val service = remoteService
        if (!activeRequest || service == null) {
            publish(currentSnapshot.copy(message = "Pause will apply when replay starts."))
            return
        }
        executor.execute {
            runCatching { service.pause() }
                .onFailure { error ->
                    pendingPause = false
                    publish(
                        currentSnapshot.copy(
                            message = "Replay pause could not be requested: " +
                                (error.message ?: error.javaClass.simpleName),
                        ),
                    )
                }
        }
    }

    /** Resumes a helper paused between complete gestures. */
    fun resume() {
        if (closed) return
        pendingPause = false
        val service = remoteService ?: return
        if (!activeRequest) return
        executor.execute {
            runCatching { service.resume() }
                .onFailure { error ->
                    publish(
                        currentSnapshot.copy(
                            message = "Replay resume could not be requested: " +
                                (error.message ?: error.javaClass.simpleName),
                        ),
                    )
                }
        }
    }

    fun cancel() {
        if (closed) return
        val service = remoteService
        activeRequest = false
        pendingPause = false
        pendingCommands = null
        stopPolling()
        runCatching { service?.cancel() }
        finishService("Replay cancelled. The helper was stopped and the queue was cleared.")
    }

    override fun close() {
        if (closed) return
        closed = true
        val service = remoteService
        pendingPause = false
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
            val generation = bindingGeneration.get()
            val connection = activeServiceConnection
            if (connection == null || !isCurrentBinding(generation, connection)) {
                finishService(
                    "The Shizuku replay helper binding is settling. Try again shortly.",
                    cancelRemote = true,
                )
                return
            }
            submit(commands, service, generation)
            return
        }
        val generation = bindingGeneration.incrementAndGet()
        val connection = newServiceConnection(generation)
        val registered = synchronized(bindingLock) {
            if (closed || !activeRequest) {
                false
            } else {
                activeServiceConnection = connection
                true
            }
        }
        if (!registered) {
            activeRequest = false
            pendingCommands = null
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (error: Throwable) {
            synchronized(bindingLock) {
                if (isCurrentBinding(generation, connection)) retireBindingLocked()
            }
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

    private fun submit(
        commands: List<ReplayCommand>,
        service: IShizukuInputReplayService,
        generation: Long,
    ) {
        executor.execute {
            try {
                requireActiveSubmission(service, generation)
                service.clearQueue()
                val motionTrace = commands.filterIsInstance<ReplayCommand.MotionTrace>().singleOrNull()
                val capabilities = if (motionTrace != null) service.getCapabilities() else null
                if (motionTrace != null && capabilities?.and(CAPABILITY_RAW_MOTION_TRACE) == 0) {
                    throw IllegalStateException(
                        "The Shizuku helper cannot prove motionevent replay support on this device",
                    )
                }
                if (motionTrace?.targetUid != null &&
                    capabilities?.and(CAPABILITY_TARGETED_DIRECT_MOTION_TRACE) == 0
                ) {
                    throw IllegalStateException(
                        "This device cannot target replay input beneath the freeze guard",
                    )
                }
                commands.forEach { command ->
                    requireActiveSubmission(service, generation)
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
                            if (command.targetUid == null) {
                                service.enqueueMotionTrace(actions, xs, ys, delays)
                            } else {
                                service.enqueueTargetedMotionTrace(
                                    actions,
                                    xs,
                                    ys,
                                    delays,
                                    command.targetUid,
                                )
                            }
                        }
                    }
                }
                requireActiveSubmission(service, generation)
                service.startQueue()
                if (pendingPause && isActiveSubmission(service, generation)) service.pause()
                val submissionStillCurrent = synchronized(bindingLock) {
                    val connection = activeServiceConnection
                    if (!activeRequest || connection == null || !isCurrentBinding(generation, connection)) {
                        false
                    } else {
                        pendingCommands = null
                        true
                    }
                }
                if (!submissionStillCurrent) {
                    runCatching { service.cancel() }
                    return@execute
                }
                publish(
                    currentSnapshot.copy(
                        serviceConnected = true,
                        running = true,
                        paused = false,
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
                if (isActiveSubmission(service, generation)) startPolling(service, generation)
            } catch (error: Throwable) {
                if (isActiveSubmission(service, generation)) {
                    finishService(
                        "Shizuku replay could not start: ${error.message ?: error.javaClass.simpleName}",
                        cancelRemote = true,
                        expectedService = service,
                        expectedGeneration = generation,
                    )
                } else {
                    runCatching { service.cancel() }
                }
            }
        }
    }

    private fun requireActiveSubmission(service: IShizukuInputReplayService, generation: Long) {
        if (!isActiveSubmission(service, generation)) {
            throw CancellationException("Replay request ended before the helper could start")
        }
    }

    private fun isActiveSubmission(service: IShizukuInputReplayService, generation: Long): Boolean {
        if (closed || !activeRequest || remoteService !== service) return false
        val connection = activeServiceConnection ?: return false
        return isCurrentBinding(generation, connection)
    }

    private fun startPolling(service: IShizukuInputReplayService, generation: Long) {
        stopPolling()
        pollTask = executor.scheduleWithFixedDelay(
            { pollRemoteService(service, generation) },
            0L,
            100L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopPolling() {
        pollTask?.cancel(false)
        pollTask = null
    }

    private fun pollRemoteService(service: IShizukuInputReplayService, generation: Long) {
        if (!isActiveSubmission(service, generation)) return
        try {
            val running = service.isRunning()
            val paused = service.isPaused()
            val completedCount = service.getCompletedCount()
            val queuedCount = service.getQueuedCount()
            val lastError = service.getLastError()
            publish(
                currentSnapshot.copy(
                    serviceConnected = true,
                    running = running,
                    paused = paused,
                    queuedCount = queuedCount,
                    completedCount = completedCount,
                    remoteProcessUid = service.getProcessUid(),
                    lastCommand = service.getLastCommand(),
                    lastError = lastError,
                    message = when {
                        paused -> "Replay paused safely after the current gesture."

                        running && currentSnapshot.directMotionTimelineSupported == true ->
                            "Replay running through direct InputManager injection."

                        running -> "Replay running through the shell-identity fallback."

                        lastError != null -> "Replay failed: $lastError"

                        else -> "Replay completed: $completedCount command(s)."
                    },
                ),
            )
            if (!running) {
                finishService(
                    null,
                    keepSnapshot = true,
                    expectedService = service,
                    expectedGeneration = generation,
                )
            }
        } catch (error: Throwable) {
            if (isActiveSubmission(service, generation)) {
                finishService(
                    "The Shizuku replay helper failed: ${error.message ?: error.javaClass.simpleName}",
                    cancelRemote = true,
                    expectedService = service,
                    expectedGeneration = generation,
                )
            } else {
                runCatching { service.cancel() }
            }
        }
    }

    private fun finishService(
        message: String?,
        keepSnapshot: Boolean = false,
        cancelRemote: Boolean = false,
        expectedService: IShizukuInputReplayService? = null,
        expectedGeneration: Long? = null,
    ) {
        val teardown = synchronized(bindingLock) {
            val currentConnection = activeServiceConnection
            if (expectedService != null &&
                (
                    remoteService !== expectedService ||
                        expectedGeneration == null ||
                        currentConnection == null ||
                        !isCurrentBinding(expectedGeneration, currentConnection)
                    )
            ) {
                return
            }
            val currentPollTask = pollTask
            pollTask = null
            val currentService = remoteService
            pendingCommands = null
            pendingPause = false
            retireBindingLocked()
            activeRequest = false
            ServiceTeardown(currentService, currentConnection, currentPollTask)
        }
        teardown.pollTask?.cancel(false)
        val service = teardown.service
        val connection = teardown.connection
        if (cancelRemote) runCatching { service?.cancel() }
        connection?.let { boundConnection ->
            runCatching { Shizuku.unbindUserService(userServiceArgs, boundConnection, true) }
        }
        if (!closed) {
            if (keepSnapshot) {
                publish(currentSnapshot.copy(serviceConnected = false, running = false, paused = false))
            } else {
                publish(
                    currentSnapshot.copy(
                        serviceConnected = false,
                        running = false,
                        paused = false,
                        message = message ?: currentSnapshot.message,
                    ),
                )
            }
        }
    }

    private data class ServiceTeardown(
        val service: IShizukuInputReplayService?,
        val connection: ServiceConnection?,
        val pollTask: ScheduledFuture<*>?,
    )

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
        const val CAPABILITY_TARGETED_DIRECT_MOTION_TRACE = 4
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
