package com.veyarc.automation.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MAX_CAPTURE_DIMENSION_PX = 16_384
private const val MAX_CAPTURE_PIXEL_COUNT = 16_777_216L

/** Selects pixels to copy without changing the projection or its full-resolution surface. */
sealed interface ScreenCaptureArea {
    data object FullScreen : ScreenCaptureArea
    data class Region(val rect: NormalizedRect) : ScreenCaptureArea
    data class Pixel(val point: NormalizedCoordinate) : ScreenCaptureArea
}

internal data class FramePixelBounds(val left: Int, val top: Int, val rightExclusive: Int, val bottomExclusive: Int) {
    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top

    fun relativeTo(captured: FramePixelBounds): FramePixelBounds {
        require(left >= captured.left && top >= captured.top && rightExclusive <= captured.rightExclusive && bottomExclusive <= captured.bottomExclusive) {
            "The captured frame does not contain the requested pixels"
        }
        return FramePixelBounds(left - captured.left, top - captured.top, rightExclusive - captured.left, bottomExclusive - captured.top)
    }

    companion object {
        fun from(region: NormalizedRect, width: Int, height: Int): FramePixelBounds {
            val left = floor(region.x * width).toInt().coerceIn(0, width - 1)
            val top = floor(region.y * height).toInt().coerceIn(0, height - 1)
            return FramePixelBounds(
                left,
                top,
                ceil((region.x + region.width) * width).toInt().coerceIn(left + 1, width),
                ceil((region.y + region.height) * height).toInt().coerceIn(top + 1, height),
            )
        }

        fun pixel(point: NormalizedCoordinate, width: Int, height: Int): FramePixelBounds {
            val x = (point.x * (width - 1)).roundToInt().coerceIn(0, width - 1)
            val y = (point.y * (height - 1)).roundToInt().coerceIn(0, height - 1)
            return FramePixelBounds(x, y, x + 1, y + 1)
        }
    }
}

/** Display dimensions used to size a temporary MediaProjection virtual display. */
data class MediaProjectionDisplaySpec(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
) {
    init {
        require(widthPx in 1..MAX_CAPTURE_DIMENSION_PX) { "Projection width is outside the safe range" }
        require(heightPx in 1..MAX_CAPTURE_DIMENSION_PX) { "Projection height is outside the safe range" }
        require(widthPx.toLong() * heightPx <= MAX_CAPTURE_PIXEL_COUNT) {
            "Projection frame is too large"
        }
        require(densityDpi in 1..MAX_DENSITY_DPI) { "Projection density is outside the safe range" }
    }

    companion object {
        private const val MAX_DENSITY_DPI = 1_000

        fun fromContext(context: Context): MediaProjectionDisplaySpec {
            val metrics = context.resources.displayMetrics
            return MediaProjectionDisplaySpec(
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
            )
        }
    }
}

data class RgbaColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
) {
    init {
        require(red in 0..255) { "Red must fit one byte" }
        require(green in 0..255) { "Green must fit one byte" }
        require(blue in 0..255) { "Blue must fit one byte" }
        require(alpha in 0..255) { "Alpha must fit one byte" }
    }
}

/**
 * An independent in-memory copy of selected screen pixels. [close] clears the copied pixels
 * so callers cannot accidentally retain a screenshot.
 */
class EphemeralScreenFrame internal constructor(
    val widthPx: Int,
    val heightPx: Int,
    val timestampNanos: Long,
    private var rgbaBytes: ByteArray,
    internal val capturedBounds: FramePixelBounds = FramePixelBounds(0, 0, widthPx, heightPx),
    private val buffers: ScreenFrameBuffers? = null,
) : Closeable {
    private var closed = false

    init {
        require(widthPx in 1..MAX_CAPTURE_DIMENSION_PX && heightPx in 1..MAX_CAPTURE_DIMENSION_PX && widthPx.toLong() * heightPx <= MAX_CAPTURE_PIXEL_COUNT) { "Projection frame dimensions are invalid" }
        require(capturedBounds.left >= 0 && capturedBounds.top >= 0 && capturedBounds.rightExclusive <= widthPx && capturedBounds.bottomExclusive <= heightPx && capturedBounds.width > 0 && capturedBounds.height > 0) { "Captured region is outside the frame" }
        require(rgbaBytes.size.toLong() == capturedBounds.width.toLong() * capturedBounds.height * BYTES_PER_PIXEL) { "Captured pixels do not match their dimensions" }
    }

    val isClosed: Boolean
        get() = closed

    @Synchronized
    fun pixelAt(x: Int, y: Int): RgbaColor {
        check(!closed) { "The ephemeral frame has already been released" }
        require(x in 0 until widthPx) { "Pixel x is outside the frame" }
        require(y in 0 until heightPx) { "Pixel y is outside the frame" }
        require(x in capturedBounds.left until capturedBounds.rightExclusive && y in capturedBounds.top until capturedBounds.bottomExclusive) {
            "Pixel is outside the captured region"
        }
        val offset = ((y - capturedBounds.top) * capturedBounds.width + x - capturedBounds.left) * BYTES_PER_PIXEL
        return RgbaColor(
            red = rgbaBytes[offset].toInt() and 0xFF,
            green = rgbaBytes[offset + 1].toInt() and 0xFF,
            blue = rgbaBytes[offset + 2].toInt() and 0xFF,
            alpha = rgbaBytes[offset + 3].toInt() and 0xFF,
        )
    }

    /**
     * Copies a user-selected normalized crop into a bounded template and leaves this frame
     * releasable as usual. The returned template is independent of the frame and must be cleared
     * by the caller after it has been encrypted or otherwise consumed.
     */
    fun copyRegion(region: NormalizedRect): RgbaTemplate {
        check(!closed) { "The ephemeral frame has already been released" }
        val bounds = FramePixelBounds.from(region, widthPx, heightPx).relativeTo(capturedBounds)
        val cropWidth = bounds.width
        val cropHeight = bounds.height
        require(cropWidth.toLong() * cropHeight <= RgbaTemplate.MAX_TEMPLATE_PIXELS) {
            "Template crop is too large to store safely"
        }
        val output = ByteArray(cropWidth * cropHeight * BYTES_PER_PIXEL)
        withRgbaBytes { bytes ->
            for (row in 0 until cropHeight) {
                val sourceOffset = ((bounds.top + row) * capturedBounds.width + bounds.left) * BYTES_PER_PIXEL
                val destinationOffset = row * cropWidth * BYTES_PER_PIXEL
                bytes.copyInto(
                    destination = output,
                    destinationOffset = destinationOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + cropWidth * BYTES_PER_PIXEL,
                )
            }
        }
        return try {
            RgbaTemplate.fromRgba(cropWidth, cropHeight, output)
        } finally {
            output.fill(0)
        }
    }

    /** Copies the captured region into a caller-owned bitmap that must be recycled. */
    fun copyBitmap(): android.graphics.Bitmap = withRgbaBytes { bytes ->
        val width = capturedBounds.width
        val height = capturedBounds.height
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        try {
            for (y in 0 until height) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Screen snapshot cancelled")
                for (x in 0 until width) {
                    val offset = (y * width + x) * 4
                    row[x] = ((bytes[offset + 3].toInt() and 255) shl 24) or ((bytes[offset].toInt() and 255) shl 16) or ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        } finally {
            row.fill(0)
        }
    }

    /** Runs a bounded in-memory inspection without exposing the frame buffer to callers. */
    @Synchronized
    internal fun <T> withRgbaBytes(block: (ByteArray) -> T): T {
        check(!closed) { "The ephemeral frame has already been released" }
        return block(rgbaBytes)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        val released = rgbaBytes
        rgbaBytes = ByteArray(0)
        closed = true
        if (buffers == null) released.fill(0) else buffers.release(released)
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

class MediaProjectionCaptureException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Minimal lifecycle contract used by the process bridge and its cleanup tests. */
internal interface MediaProjectionSession : Closeable {
    val isActive: Boolean

    fun captureLatestFrame(): EphemeralScreenFrame?

    fun captureLatestFrame(area: ScreenCaptureArea): EphemeralScreenFrame? = captureLatestFrame()
}

/** Builds the one-shot consent request required for each capture session. */
object MediaProjectionConsent {
    fun createRequestIntent(context: Context, fullDisplay: Boolean = false): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: throw MediaProjectionCaptureException("Android MediaProjection is unavailable")
        return if (fullDisplay && android.os.Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
    }

    fun wasGranted(resultCode: Int, data: Intent?): Boolean = resultCode == Activity.RESULT_OK && data != null
}

/**
 * Owns one MediaProjection virtual display and never writes frames to disk. A new instance is
 * required for every workflow run so consent and cleanup cannot leak across runs.
 */
class MediaProjectionFrameCapture(
    context: Context,
    private val onRevoked: (String) -> Unit = {},
) : MediaProjectionSession {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val callbackHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var frames: ProjectionFrames? = null
    private var state = State.NEW

    override val isActive: Boolean
        get() = synchronized(lock) { state == State.ACTIVE }

    fun start(
        resultCode: Int,
        resultData: Intent,
        displaySpec: MediaProjectionDisplaySpec,
    ) {
        require(MediaProjectionConsent.wasGranted(resultCode, resultData)) {
            "MediaProjection consent was not granted"
        }
        synchronized(lock) {
            check(state == State.NEW) { "A MediaProjection capture can only be started once" }
            val manager = appContext.getSystemService(MediaProjectionManager::class.java)
                ?: throw MediaProjectionCaptureException("Android MediaProjection is unavailable")
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw MediaProjectionCaptureException("Android did not return a MediaProjection")
            val reader = try {
                ImageReader.newInstance(
                    displaySpec.widthPx,
                    displaySpec.heightPx,
                    PixelFormat.RGBA_8888,
                    ProjectionFrames.MAX_IMAGES,
                )
            } catch (error: Throwable) {
                runCatching { mediaProjection.stop() }
                throw MediaProjectionCaptureException("MediaProjection capture buffers could not be created", error)
            }
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    handleRevocation("MediaProjection consent was revoked or the capture stopped")
                }
            }
            try {
                mediaProjection.registerCallback(callback, callbackHandler)
                val display = mediaProjection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    displaySpec.widthPx,
                    displaySpec.heightPx,
                    displaySpec.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    callbackHandler,
                ) ?: throw MediaProjectionCaptureException("Android did not create a capture display")
                projection = mediaProjection
                projectionCallback = callback
                virtualDisplay = display
                frames = ProjectionFrames(reader)
                state = State.ACTIVE
            } catch (error: Throwable) {
                runCatching { mediaProjection.unregisterCallback(callback) }
                runCatching { reader.close() }
                runCatching { mediaProjection.stop() }
                throw if (error is MediaProjectionCaptureException) {
                    error
                } else {
                    MediaProjectionCaptureException("MediaProjection could not start", error)
                }
            }
        }
    }

    /** Copies requested pixels from the latest image, including when the screen has not changed. */
    override fun captureLatestFrame(): EphemeralScreenFrame? = captureLatestFrame(ScreenCaptureArea.FullScreen)

    override fun captureLatestFrame(area: ScreenCaptureArea): EphemeralScreenFrame? = synchronized(lock) {
        check(state == State.ACTIVE) { "MediaProjection capture is not active" }
        checkNotNull(frames).capture(area)
    }

    override fun close() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            releaseLocked(stopProjection = true)
        }
    }

    private fun handleRevocation(reason: String) {
        val notify = synchronized(lock) {
            if (state != State.ACTIVE) {
                false
            } else {
                state = State.REVOKED
                releaseLocked(stopProjection = false)
                true
            }
        }
        if (notify) runCatching { onRevoked(reason) }
    }

    private fun releaseLocked(stopProjection: Boolean) {
        val currentProjection = projection
        val currentCallback = projectionCallback
        val currentDisplay = virtualDisplay
        val currentFrames = frames
        projection = null
        projectionCallback = null
        virtualDisplay = null
        frames = null
        runCatching { currentDisplay?.release() }
        runCatching { currentFrames?.close() }
        if (currentProjection != null && currentCallback != null) {
            runCatching { currentProjection.unregisterCallback(currentCallback) }
        }
        if (stopProjection) runCatching { currentProjection?.stop() }
    }

    private enum class State {
        NEW,
        ACTIVE,
        REVOKED,
        CLOSED,
    }

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "Veyarc ephemeral workflow frame"
    }
}

/** Retains one native image only until replacement or capture teardown, without a full-screen heap copy. */
internal class ProjectionFrames(private val reader: ImageReader) : Closeable {
    private val buffers = ScreenFrameBuffers()
    private var latest: Image? = null
    private var closed = false

    init {
        require(reader.maxImages >= MAX_IMAGES) { "Capture needs one retained image and two acquisition slots" }
    }

    @Synchronized
    fun capture(area: ScreenCaptureArea): EphemeralScreenFrame? {
        check(!closed) { "MediaProjection capture is closed" }
        val deadline = SystemClock.elapsedRealtime() + FIRST_FRAME_TIMEOUT_MS
        do {
            val image = try {
                reader.acquireLatestImage()
            } catch (error: IllegalStateException) {
                throw MediaProjectionCaptureException("MediaProjection frame capture is unavailable", error)
            }
            if (image != null) {
                val previous = latest
                latest = image
                previous?.close()
            }
            if (latest != null) break
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(10)
        } while (true)
        val image = checkNotNull(latest)
        return try {
            require(image.format == PixelFormat.RGBA_8888) { "MediaProjection returned an unsupported pixel format" }
            val plane = image.planes.singleOrNull()
                ?: throw MediaProjectionCaptureException("MediaProjection returned an unexpected image layout")
            copyRgbaFrame(image.width, image.height, image.timestamp, plane.buffer, plane.pixelStride, plane.rowStride, area, buffers)
        } catch (error: Throwable) {
            latest = null
            image.close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val image = latest
        latest = null
        try {
            image?.close()
        } finally {
            buffers.close()
            reader.close()
        }
    }

    companion object {
        // acquireLatestImage needs two free slots to discard old queued frames while one is held.
        const val MAX_IMAGES = 3
        private const val FIRST_FRAME_TIMEOUT_MS = 1_000L
    }
}

/** Copies only needed pixels, using bulk row reads for the usual tightly packed RGBA layout. */
internal fun copyRgbaFrame(width: Int, height: Int, timestampNanos: Long, source: ByteBuffer, pixelStride: Int, rowStride: Int, area: ScreenCaptureArea = ScreenCaptureArea.FullScreen, buffers: ScreenFrameBuffers? = null): EphemeralScreenFrame {
    require(width in 1..MAX_CAPTURE_DIMENSION_PX && height in 1..MAX_CAPTURE_DIMENSION_PX && width.toLong() * height <= MAX_CAPTURE_PIXEL_COUNT) { "Projection frame is too large or invalid" }
    require(pixelStride >= 4 && rowStride.toLong() >= (width - 1L) * pixelStride + 4) { "MediaProjection returned invalid pixel strides" }
    val bounds = when (area) {
        ScreenCaptureArea.FullScreen -> FramePixelBounds(0, 0, width, height)
        is ScreenCaptureArea.Region -> FramePixelBounds.from(area.rect, width, height)
        is ScreenCaptureArea.Pixel -> FramePixelBounds.pixel(area.point, width, height)
    }
    val lastPixelEnd = (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
    require(lastPixelEnd <= source.limit()) { "MediaProjection returned a truncated image buffer" }
    val size = bounds.width * bounds.height * 4
    val output = buffers?.take(size) ?: ByteArray(size)
    try {
        val buffer = source.duplicate()
        for (row in 0 until bounds.height) {
            buffer.position(((bounds.top + row).toLong() * rowStride + bounds.left.toLong() * pixelStride).toInt())
            val destination = row * bounds.width * 4
            if (pixelStride == 4) {
                buffer.get(output, destination, bounds.width * 4)
            } else {
                for (column in 0 until bounds.width) {
                    buffer.position(((bounds.top + row).toLong() * rowStride + (bounds.left + column).toLong() * pixelStride).toInt())
                    buffer.get(output, destination + column * 4, 4)
                }
            }
        }
        return EphemeralScreenFrame(width, height, timestampNanos, output, bounds, buffers)
    } catch (error: Throwable) {
        if (buffers == null) output.fill(0) else buffers.release(output)
        throw error
    }
}
