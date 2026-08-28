package com.veyarc.automation.android

import android.os.SystemClock
import android.view.MotionEvent
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * A deliberately narrow Shizuku UserService for approved input replay.
 *
 * The service accepts only validated tap/swipe commands or a bounded single-pointer motion trace
 * and injects it through the Shizuku shell identity. It prefers Android's input manager and falls
 * back to the fixed `input` command when the OEM hides the direct API. It never accepts an
 * arbitrary shell string, writes to an input device, calls sendevent/uinput, or executes a
 * workflow. A capability probe must report support for a bounded motion trace before one is queued.
 */
class ShizukuInputReplayUserService : IShizukuInputReplayService.Stub() {
    private sealed interface ReplayCommand {
        val description: String
        val cancelX: Float
        val cancelY: Float
        val delayBeforeMillis: Long
        val targetUid: Int?
            get() = null

        fun arguments(): List<String>

        data class Tap(
            val x: Float,
            val y: Float,
        ) : ReplayCommand {
            override val description: String = "tap ${format(x)} ${format(y)}"
            override val cancelX: Float = x
            override val cancelY: Float = y
            override val delayBeforeMillis: Long = 0L

            override fun arguments(): List<String> = inputArguments(
                "tap",
                format(x),
                format(y),
            )
        }

        data class Swipe(
            val startX: Float,
            val startY: Float,
            val endX: Float,
            val endY: Float,
            val durationMillis: Long,
        ) : ReplayCommand {
            override val description: String =
                "swipe ${format(startX)} ${format(startY)} " +
                    "${format(endX)} ${format(endY)} ${durationMillis}ms"
            override val cancelX: Float = endX
            override val cancelY: Float = endY
            override val delayBeforeMillis: Long = 0L

            override fun arguments(): List<String> = inputArguments(
                "swipe",
                format(startX),
                format(startY),
                format(endX),
                format(endY),
                durationMillis.toString(),
            )
        }

        data class MotionFrame(
            val action: Int,
            val x: Float,
            val y: Float,
            override val delayBeforeMillis: Long,
            override val targetUid: Int? = null,
        ) : ReplayCommand {
            override val description: String =
                "motionevent ${actionName(action)} ${format(x)} ${format(y)}"
            override val cancelX: Float = x
            override val cancelY: Float = y

            override fun arguments(): List<String> = inputArguments(
                "motionevent",
                actionName(action),
                format(x),
                format(y),
            )
        }
    }

    private val queue = ArrayDeque<ReplayCommand>()
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    private val completedCount = AtomicInteger(0)
    private val directMotionLock = Any()

    @Volatile
    private var directMotionInjector: DirectMotionEventInjector? = null

    @Volatile
    private var directMotionInjectionFailed = false

    private var directTouchActive = false
    private var directDownTimeMillis = 0L
    private var directNextEventDeadlineMillis = 0L

    private var shellTouchActive = false
    private var shellNextEventDeadlineMillis = 0L

    @Volatile
    private var cancelRequested = false

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val pauseLock = java.lang.Object()

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var paused = false

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var currentCommand: ReplayCommand? = null

    @Volatile
    private var lastCommand: String? = null

    @Volatile
    private var lastError: String? = null

    override fun getProcessUid(): Int = android.os.Process.myUid()

    override fun isRunning(): Boolean = running.get()

    override fun getQueuedCount(): Int = synchronized(stateLock) { queue.size }

    override fun getCompletedCount(): Int = completedCount.get()

    override fun getLastCommand(): String? = lastCommand

    override fun getLastError(): String? = lastError

    override fun pause() {
        if (!running.get()) return
        pauseRequested = true
    }

    override fun resume() {
        synchronized(pauseLock) {
            pauseRequested = false
            pauseLock.notifyAll()
        }
    }

    override fun isPaused(): Boolean = paused

    @Volatile
    private var capabilities: Int? = null

    override fun getCapabilities(): Int = synchronized(stateLock) {
        capabilities ?: run {
            val direct = DirectMotionEventInjector.createOrNull()
            directMotionInjector = direct
            val shellSupported = supportsMotionEvents()
            val value = when {
                direct != null ->
                    CAPABILITY_RAW_MOTION_TRACE or
                        CAPABILITY_DIRECT_MOTION_TRACE or
                        if (direct.supportsTargetedInjection) {
                            CAPABILITY_TARGETED_DIRECT_MOTION_TRACE
                        } else {
                            0
                        }

                shellSupported -> CAPABILITY_RAW_MOTION_TRACE

                else -> 0
            }
            capabilities = value
            value
        }
    }

    override fun clearQueue() {
        synchronized(stateLock) {
            check(!running.get()) { "Cannot clear a replay that is running" }
            queue.clear()
            completedCount.set(0)
            lastCommand = null
            lastError = null
        }
        resetReplayTiming()
        directMotionInjectionFailed = false
        pauseRequested = false
        paused = false
    }

    override fun enqueueTap(x: Float, y: Float) {
        check(!cancelRequested) { "Replay was cancelled" }
        validateCoordinate("x", x)
        validateCoordinate("y", y)
        synchronized(stateLock) {
            check(!running.get()) { "Cannot enqueue while replay is running" }
            check(queue.size < MAX_QUEUE_SIZE) { "Replay queue is full" }
            queue.addLast(ReplayCommand.Tap(x, y))
        }
    }

    override fun enqueueSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long,
    ) {
        check(!cancelRequested) { "Replay was cancelled" }
        validateCoordinate("startX", startX)
        validateCoordinate("startY", startY)
        validateCoordinate("endX", endX)
        validateCoordinate("endY", endY)
        require(durationMillis in MIN_SWIPE_DURATION_MILLIS..MAX_SWIPE_DURATION_MILLIS) {
            "Swipe duration must be between $MIN_SWIPE_DURATION_MILLIS and " +
                "$MAX_SWIPE_DURATION_MILLIS milliseconds"
        }
        synchronized(stateLock) {
            check(!running.get()) { "Cannot enqueue while replay is running" }
            check(queue.size < MAX_QUEUE_SIZE) { "Replay queue is full" }
            queue.addLast(
                ReplayCommand.Swipe(
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                    durationMillis = durationMillis,
                ),
            )
        }
    }

    override fun enqueueMotionTrace(
        actions: IntArray?,
        xs: FloatArray?,
        ys: FloatArray?,
        delaysMillis: LongArray?,
    ) {
        enqueueMotionTraceInternal(actions, xs, ys, delaysMillis, targetUid = null)
    }

    override fun enqueueTargetedMotionTrace(
        actions: IntArray?,
        xs: FloatArray?,
        ys: FloatArray?,
        delaysMillis: LongArray?,
        targetUid: Int,
    ) {
        require(targetUid >= 0) { "Target UID must be non-negative" }
        check(getCapabilities() and CAPABILITY_TARGETED_DIRECT_MOTION_TRACE != 0) {
            "This device's input helper does not support targeted replay protection"
        }
        enqueueMotionTraceInternal(actions, xs, ys, delaysMillis, targetUid)
    }

    private fun enqueueMotionTraceInternal(
        actions: IntArray?,
        xs: FloatArray?,
        ys: FloatArray?,
        delaysMillis: LongArray?,
        targetUid: Int?,
    ) {
        check(!cancelRequested) { "Replay was cancelled" }
        check(getCapabilities() and CAPABILITY_RAW_MOTION_TRACE != 0) {
            "This device's input helper does not support motionevent replay"
        }
        val checkedActions = requireNotNull(actions) { "Motion actions are required" }
        val checkedXs = requireNotNull(xs) { "Motion x coordinates are required" }
        val checkedYs = requireNotNull(ys) { "Motion y coordinates are required" }
        val checkedDelays = requireNotNull(delaysMillis) { "Motion delays are required" }
        require(checkedActions.isNotEmpty() && checkedActions.size <= MAX_MOTION_FRAME_COUNT) {
            "Motion trace must contain between 1 and $MAX_MOTION_FRAME_COUNT frames"
        }
        require(
            checkedActions.size == checkedXs.size &&
                checkedActions.size == checkedYs.size &&
                checkedActions.size == checkedDelays.size,
        ) { "Motion trace arrays must have equal lengths" }

        var touchActive = false
        val frames = ArrayList<ReplayCommand.MotionFrame>(checkedActions.size)
        checkedActions.indices.forEach { index ->
            val action = checkedActions[index]
            val x = checkedXs[index]
            val y = checkedYs[index]
            val delay = checkedDelays[index]
            validateCoordinate("x", x)
            validateCoordinate("y", y)
            require(delay in 0L..MAX_MOTION_DELAY_MILLIS) {
                "Motion frame delay must be between 0 and $MAX_MOTION_DELAY_MILLIS milliseconds"
            }
            when (action) {
                MOTION_DOWN -> check(!touchActive) {
                    "A motion trace cannot start a second touch before UP"
                }.also { touchActive = true }

                MOTION_MOVE -> check(touchActive) { "A motion MOVE requires an active touch" }

                MOTION_UP -> check(touchActive) {
                    "A motion UP requires an active touch"
                }.also { touchActive = false }

                else -> error("Motion action must be DOWN, MOVE, or UP")
            }
            frames += ReplayCommand.MotionFrame(action, x, y, delay, targetUid)
        }
        check(!touchActive) { "A motion trace must end with UP" }
        synchronized(stateLock) {
            check(!running.get()) { "Cannot enqueue while replay is running" }
            check(queue.isEmpty()) { "A raw motion trace cannot be mixed with other commands" }
            queue.addAll(frames)
        }
    }

    override fun startQueue() {
        synchronized(stateLock) {
            if (running.get()) return
            if (cancelRequested) {
                queue.clear()
                return
            }
            check(queue.isNotEmpty()) { "Replay queue is empty" }
            cancelRequested = false
            pauseRequested = false
            paused = false
            lastError = null
            resetReplayTiming()
            directMotionInjectionFailed = false
            running.set(true)
            workerThread = thread(
                start = true,
                name = "VeyarcShizukuInputReplay",
                block = ::runQueue,
            )
        }
    }

    override fun cancel() {
        val command = currentCommand
        cancelRequested = true
        synchronized(pauseLock) { pauseLock.notifyAll() }
        running.set(false)
        currentProcess?.destroy()
        workerThread?.interrupt()
        synchronized(stateLock) { queue.clear() }
        val directCancel = synchronized(directMotionLock) {
            if (!directTouchActive) {
                null
            } else {
                DirectCancel(
                    x = command?.cancelX ?: 0f,
                    y = command?.cancelY ?: 0f,
                    downTimeMillis = directDownTimeMillis,
                    eventTimeMillis = max(directNextEventDeadlineMillis, SystemClock.uptimeMillis()),
                    targetUid = command?.targetUid,
                )
            }
        }
        if (directCancel != null && directMotionInjector != null &&
            (directCancel.targetUid != null || !directMotionInjectionFailed)
        ) {
            runCatching {
                directMotionInjector?.inject(
                    action = MotionEvent.ACTION_CANCEL,
                    x = directCancel.x,
                    y = directCancel.y,
                    downTimeMillis = directCancel.downTimeMillis,
                    eventTimeMillis = directCancel.eventTimeMillis,
                    displayId = DISPLAY_ID.toInt(),
                    targetUid = directCancel.targetUid,
                )
            }.onFailure {
                if (directCancel.targetUid == null) sendCancel(directCancel.x, directCancel.y)
            }
        } else if (command != null) {
            // Best effort: if a swipe was interrupted between DOWN and UP, explicitly send a
            // CANCEL through the same shell input path. This never writes to the touchscreen node.
            if (command.targetUid == null) sendCancel(command.cancelX, command.cancelY)
        }
    }

    /** Reserved transaction used by Shizuku to tear down a UserService. */
    override fun destroy() {
        cancel()
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private fun runQueue() {
        try {
            while (true) {
                val command = synchronized(stateLock) {
                    if (cancelRequested || queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                awaitPauseBefore(command)
                if (cancelRequested) break
                currentCommand = command
                lastCommand = command.description
                execute(command)
                if (cancelRequested) break
                completedCount.incrementAndGet()
                currentCommand = null
                awaitPauseAfter(command)
            }
        } catch (error: Throwable) {
            if (!cancelRequested) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        } finally {
            cancelDirectTouchIfNeeded()
            resetReplayTiming()
            running.set(false)
            currentCommand = null
            currentProcess = null
            workerThread = null
            synchronized(stateLock) { queue.clear() }
            synchronized(pauseLock) {
                pauseRequested = false
                paused = false
                pauseLock.notifyAll()
            }
        }
    }

    /** Pausing before a DOWN prevents a new gesture from starting after the request. */
    private fun awaitPauseBefore(command: ReplayCommand) {
        if (command is ReplayCommand.MotionFrame && command.action == MOTION_DOWN) {
            awaitPauseIfRequested()
        }
    }

    /** Pausing after an UP preserves the complete gesture that was already in flight. */
    private fun awaitPauseAfter(command: ReplayCommand) {
        if (command is ReplayCommand.MotionFrame && command.action != MOTION_UP) return
        val hasMoreCommands = synchronized(stateLock) { queue.isNotEmpty() }
        if (hasMoreCommands) awaitPauseIfRequested()
    }

    private fun awaitPauseIfRequested() {
        if (!pauseRequested || cancelRequested) return
        synchronized(pauseLock) {
            if (!pauseRequested || cancelRequested) return
            paused = true
            try {
                while (pauseRequested && !cancelRequested) {
                    try {
                        pauseLock.wait(PAUSE_POLL_MILLIS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        cancelRequested = true
                        break
                    }
                }
            } finally {
                paused = false
            }
        }
    }

    private fun execute(command: ReplayCommand) {
        if (command is ReplayCommand.MotionFrame) {
            if (command.targetUid != null && directMotionInjector == null) {
                throw IllegalStateException(
                    "Targeted replay protection requires direct InputManager injection",
                )
            }
            if (directMotionInjector != null && !directMotionInjectionFailed) {
                executeDirectMotionFrame(command)
            } else {
                if (command.targetUid != null) {
                    throw IllegalStateException(
                        "Targeted replay protection is unavailable after direct injection failed",
                    )
                }
                executeShellMotionFrame(command)
            }
            return
        }
        executeShellCommand(command)
    }

    /** Uses absolute deadlines so process startup time does not accumulate across fallback frames. */
    private fun executeShellMotionFrame(command: ReplayCommand.MotionFrame) {
        val deadline = synchronized(directMotionLock) {
            if (command.action == MOTION_DOWN) {
                shellTouchActive = true
                shellNextEventDeadlineMillis = SystemClock.uptimeMillis() + command.delayBeforeMillis
            } else {
                shellNextEventDeadlineMillis += command.delayBeforeMillis
            }
            shellNextEventDeadlineMillis
        }
        if (!waitUntil(deadline)) return
        executeShellCommand(command, includeDelay = false)
        synchronized(directMotionLock) {
            if (command.action == MOTION_UP) {
                shellTouchActive = false
            } else {
                shellNextEventDeadlineMillis = max(shellNextEventDeadlineMillis, SystemClock.uptimeMillis())
            }
        }
    }

    /**
     * Injects directly through InputManagerGlobal/InputManager when the Shizuku shell exposes it.
     * Event times use one monotonic absolute timeline for the complete touch, avoiding one
     * process launch and one fresh `now` timestamp per frame.
     */
    private fun executeDirectMotionFrame(command: ReplayCommand.MotionFrame) {
        val injector = directMotionInjector ?: return executeShellMotionFrame(command)
        val deadline = synchronized(directMotionLock) {
            if (command.action == MOTION_DOWN) {
                directDownTimeMillis = SystemClock.uptimeMillis() + command.delayBeforeMillis
                directNextEventDeadlineMillis = directDownTimeMillis
                directTouchActive = true
            } else {
                directNextEventDeadlineMillis += command.delayBeforeMillis
            }
            directNextEventDeadlineMillis
        }
        if (!waitUntil(deadline)) return
        val downTimeMillis = synchronized(directMotionLock) { directDownTimeMillis }
        val action = when (command.action) {
            MOTION_DOWN -> MotionEvent.ACTION_DOWN
            MOTION_MOVE -> MotionEvent.ACTION_MOVE
            MOTION_UP -> MotionEvent.ACTION_UP
            else -> error("Motion action must be DOWN, MOVE, or UP")
        }
        val attemptLimit = if (command.targetUid != null) {
            TARGETED_INJECTION_ATTEMPTS
        } else {
            1
        }
        var attempt = 0
        var delivered = false
        var lastError: Throwable? = null
        var eventTimeMillis = SystemClock.uptimeMillis()
        while (attempt < attemptLimit && !delivered) {
            if (cancelRequested) return
            eventTimeMillis = SystemClock.uptimeMillis()
            try {
                injector.inject(
                    action = action,
                    x = command.x,
                    y = command.y,
                    downTimeMillis = downTimeMillis,
                    eventTimeMillis = eventTimeMillis,
                    displayId = DISPLAY_ID.toInt(),
                    targetUid = command.targetUid,
                )
                delivered = true
            } catch (error: Throwable) {
                lastError = error
                val canRetry = command.targetUid != null &&
                    isTargetWindowMismatch(error) &&
                    attempt + 1 < attemptLimit
                if (!canRetry) break
                if (!waitUntil(SystemClock.uptimeMillis() + TARGETED_INJECTION_RETRY_DELAY_MILLIS)) {
                    return
                }
            }
            attempt += 1
        }
        if (!delivered) {
            directMotionInjectionFailed = true
            if (command.action == MOTION_DOWN) {
                synchronized(directMotionLock) { directTouchActive = false }
            }
            throw lastError ?: IllegalStateException("Input event injection failed")
        }
        synchronized(directMotionLock) {
            if (command.action == MOTION_UP) {
                directTouchActive = false
            } else {
                directNextEventDeadlineMillis = max(directNextEventDeadlineMillis, eventTimeMillis)
            }
        }
    }

    private fun isTargetWindowMismatch(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("Targeted input event injection", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun executeShellCommand(command: ReplayCommand, includeDelay: Boolean = true) {
        if (includeDelay && command.delayBeforeMillis > 0L &&
            !waitUntil(SystemClock.uptimeMillis() + command.delayBeforeMillis)
        ) {
            return
        }
        val process = ProcessBuilder(command.arguments())
            .redirectErrorStream(true)
            .start()
        currentProcess = process
        try {
            val finished = waitForProcess(process, COMMAND_TIMEOUT_MILLIS)
            if (!finished) {
                process.destroy()
                waitForProcess(process, PROCESS_STOP_TIMEOUT_MILLIS)
                throw IllegalStateException("${command.description} timed out")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException(
                    buildString {
                        append("input exited with code ")
                        append(exitCode)
                        if (output.isNotEmpty()) {
                            append(": ")
                            append(output.take(MAX_ERROR_LENGTH))
                        }
                    },
                )
            }
        } finally {
            currentProcess = null
        }
    }

    private fun waitUntil(deadlineMillis: Long): Boolean {
        while (!cancelRequested) {
            val remainingMillis = deadlineMillis - SystemClock.uptimeMillis()
            if (remainingMillis <= 0L) return true
            try {
                Thread.sleep(minOf(remainingMillis, SCHEDULER_SLEEP_MILLIS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun cancelDirectTouchIfNeeded() {
        val cancel = synchronized(directMotionLock) {
            if (!directTouchActive) return
            DirectCancel(
                x = currentCommand?.cancelX ?: 0f,
                y = currentCommand?.cancelY ?: 0f,
                downTimeMillis = directDownTimeMillis,
                eventTimeMillis = max(directNextEventDeadlineMillis, SystemClock.uptimeMillis()),
                targetUid = currentCommand?.targetUid,
            )
        }
        synchronized(directMotionLock) { directTouchActive = false }
        if (directMotionInjector != null &&
            (cancel.targetUid != null || !directMotionInjectionFailed)
        ) {
            runCatching {
                directMotionInjector?.inject(
                    action = MotionEvent.ACTION_CANCEL,
                    x = cancel.x,
                    y = cancel.y,
                    downTimeMillis = cancel.downTimeMillis,
                    eventTimeMillis = cancel.eventTimeMillis,
                    displayId = DISPLAY_ID.toInt(),
                    targetUid = cancel.targetUid,
                )
            }.onFailure {
                if (cancel.targetUid == null) sendCancel(cancel.x, cancel.y)
            }
        } else {
            // If direct injection failed after DOWN, still clear the target's active touch
            // through the validated shell path. Leaving the stream open can make the target
            // appear frozen until Android times out the pointer.
            if (cancel.targetUid == null) sendCancel(cancel.x, cancel.y)
        }
    }

    private fun resetReplayTiming() {
        synchronized(directMotionLock) {
            directTouchActive = false
            directDownTimeMillis = 0L
            directNextEventDeadlineMillis = 0L
            shellTouchActive = false
            shellNextEventDeadlineMillis = 0L
        }
    }

    private fun sendCancel(x: Float, y: Float) {
        runCatching {
            val process = ProcessBuilder(
                inputArguments(
                    "motionevent",
                    "CANCEL",
                    format(x),
                    format(y),
                ),
            ).redirectErrorStream(true).start()
            if (!waitForProcess(process, CANCEL_TIMEOUT_MILLIS)) process.destroy()
        }
    }

    /**
     * Process.waitFor(timeout), isAlive, and destroyForcibly were added after this app's API 24
     * minimum. Polling exitValue keeps the bounded timeout behavior available on older devices.
     */
    private fun waitForProcess(process: Process, timeoutMillis: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            runCatching {
                process.exitValue()
                return true
            }.onFailure { error ->
                if (error !is IllegalThreadStateException) throw error
            }
            val remaining = deadline - android.os.SystemClock.uptimeMillis()
            if (remaining <= 0L) return false
            try {
                Thread.sleep(minOf(PROCESS_POLL_INTERVAL_MILLIS, remaining))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private companion object {
        data class DirectCancel(
            val x: Float,
            val y: Float,
            val downTimeMillis: Long,
            val eventTimeMillis: Long,
            val targetUid: Int?,
        )

        const val INPUT_BINARY = "/system/bin/input"
        const val DISPLAY_ID = "0"
        const val MAX_QUEUE_SIZE = 16
        const val MAX_MOTION_FRAME_COUNT = 16_384
        const val MAX_MOTION_DELAY_MILLIS = 300_000L
        const val CAPABILITY_RAW_MOTION_TRACE = 1
        const val CAPABILITY_DIRECT_MOTION_TRACE = 2
        const val CAPABILITY_TARGETED_DIRECT_MOTION_TRACE = 4
        const val MOTION_DOWN = 0
        const val MOTION_MOVE = 1
        const val MOTION_UP = 2
        const val MAX_COORDINATE = 30_000f
        const val MIN_SWIPE_DURATION_MILLIS = 1L
        const val MAX_SWIPE_DURATION_MILLIS = 5_000L
        const val COMMAND_TIMEOUT_MILLIS = 6_000L
        const val CAPABILITY_PROBE_TIMEOUT_MILLIS = 1_000L
        const val PROCESS_STOP_TIMEOUT_MILLIS = 250L
        const val CANCEL_TIMEOUT_MILLIS = 500L
        const val PROCESS_POLL_INTERVAL_MILLIS = 10L
        const val SCHEDULER_SLEEP_MILLIS = 2L
        const val TARGETED_INJECTION_ATTEMPTS = 10
        const val TARGETED_INJECTION_RETRY_DELAY_MILLIS = 100L
        const val MAX_ERROR_LENGTH = 240
        const val PAUSE_POLL_MILLIS = 100L

        fun inputArguments(command: String, vararg args: String): List<String> = buildList {
            add(INPUT_BINARY)
            add("-d")
            add(DISPLAY_ID)
            add(command)
            addAll(args)
        }

        fun format(value: Float): String = String.format(Locale.US, "%.3f", value)

        fun actionName(action: Int): String = when (action) {
            MOTION_DOWN -> "DOWN"
            MOTION_MOVE -> "MOVE"
            MOTION_UP -> "UP"
            else -> error("Motion action must be DOWN, MOVE, or UP")
        }
    }

    private fun supportsMotionEvents(): Boolean {
        val process = runCatching {
            ProcessBuilder(INPUT_BINARY, "help")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false
        val finished = waitForProcess(process, CAPABILITY_PROBE_TIMEOUT_MILLIS)
        if (!finished) {
            process.destroy()
            return false
        }
        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            .getOrDefault("")
        return process.exitValue() == 0 && output.contains("motionevent", ignoreCase = true)
    }

    private fun validateCoordinate(name: String, value: Float) {
        require(value.isFinite()) { "$name must be finite" }
        require(value in 0f..MAX_COORDINATE) { "$name is outside the supported display range" }
    }
}
