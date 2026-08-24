package com.veyarc.automation.android

import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A deliberately narrow Shizuku UserService for input-injection feasibility testing.
 *
 * The service accepts only validated tap/swipe commands and invokes Android's fixed `input`
 * command through ProcessBuilder. It never accepts an arbitrary shell string, writes to an input
 * device, calls sendevent/uinput, or executes a workflow. This is a diagnostic backend, not yet
 * the production replay implementation.
 */
class ShizukuInputReplayUserService : IShizukuInputReplayService.Stub() {
    private sealed interface ReplayCommand {
        val description: String
        val cancelX: Float
        val cancelY: Float

        fun arguments(): List<String>

        data class Tap(
            val x: Float,
            val y: Float,
        ) : ReplayCommand {
            override val description: String = "tap ${format(x)} ${format(y)}"
            override val cancelX: Float = x
            override val cancelY: Float = y

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

            override fun arguments(): List<String> = inputArguments(
                "swipe",
                format(startX),
                format(startY),
                format(endX),
                format(endY),
                durationMillis.toString(),
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
        const val MAX_COORDINATE = 30_000f
        const val MIN_SWIPE_DURATION_MILLIS = 1L
        const val MAX_SWIPE_DURATION_MILLIS = 5_000L
        const val COMMAND_TIMEOUT_MILLIS = 6_000L
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
    }

    private fun validateCoordinate(name: String, value: Float) {
        require(value.isFinite()) { "$name must be finite" }
        require(value in 0f..MAX_COORDINATE) { "$name is outside the supported display range" }
    }
}
