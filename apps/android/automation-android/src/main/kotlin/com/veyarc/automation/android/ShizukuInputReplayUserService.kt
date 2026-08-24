package com.veyarc.automation.android

import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A deliberately narrow Shizuku UserService for approved input replay.
 *
 * The service accepts only validated tap/swipe commands or a bounded single-pointer motion trace
 * and invokes Android's fixed `input` command through ProcessBuilder. It never accepts an
 * arbitrary shell string, writes to an input device, calls sendevent/uinput, or executes a
 * workflow. A capability probe must report support for `motionevent` before a raw trace is queued.
 */
class ShizukuInputReplayUserService : IShizukuInputReplayService.Stub() {
    private sealed interface ReplayCommand {
        val description: String
        val cancelX: Float
        val cancelY: Float
        val delayBeforeMillis: Long

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

    @Volatile
    private var cancelRequested = false

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

    @Volatile
    private var capabilities: Int? = null

    override fun getCapabilities(): Int = synchronized(stateLock) {
        capabilities ?: run {
            val value = if (supportsMotionEvents()) CAPABILITY_RAW_MOTION_TRACE else 0
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
    }

    override fun enqueueTap(x: Float, y: Float) {
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
            frames += ReplayCommand.MotionFrame(action, x, y, delay)
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
            check(queue.isNotEmpty()) { "Replay queue is empty" }
            cancelRequested = false
            lastError = null
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
        running.set(false)
        currentProcess?.destroy()
        workerThread?.interrupt()
        synchronized(stateLock) { queue.clear() }
        if (command != null) {
            // Best effort: if a swipe was interrupted between DOWN and UP, explicitly send a
            // CANCEL through the same shell input path. This never writes to the touchscreen node.
            sendCancel(command.cancelX, command.cancelY)
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
                currentCommand = command
                lastCommand = command.description
                execute(command)
                if (cancelRequested) break
                completedCount.incrementAndGet()
                currentCommand = null
            }
        } catch (error: Throwable) {
            if (!cancelRequested) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        } finally {
            running.set(false)
            currentCommand = null
            currentProcess = null
            workerThread = null
            synchronized(stateLock) { queue.clear() }
        }
    }

    private fun execute(command: ReplayCommand) {
        if (command.delayBeforeMillis > 0L) {
            try {
                Thread.sleep(command.delayBeforeMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (cancelRequested) return
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
        const val INPUT_BINARY = "/system/bin/input"
        const val DISPLAY_ID = "0"
        const val MAX_QUEUE_SIZE = 16
        const val MAX_MOTION_FRAME_COUNT = 16_384
        const val MAX_MOTION_DELAY_MILLIS = 300_000L
        const val CAPABILITY_RAW_MOTION_TRACE = 1
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
        const val MAX_ERROR_LENGTH = 240

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
