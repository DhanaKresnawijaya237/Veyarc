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
import com.veyarc.model.NormalizedRect
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor

/** Display dimensions used to size a temporary MediaProjection virtual display. */
data class MediaProjectionDisplaySpec(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
) {
    init {
        require(widthPx in 1..MAX_DIMENSION_PX) { "Projection width is outside the safe range" }
        require(heightPx in 1..MAX_DIMENSION_PX) { "Projection height is outside the safe range" }
        require(widthPx.toLong() * heightPx <= MAX_PIXEL_COUNT) {
            "Projection frame is too large"
        }
        require(densityDpi in 1..MAX_DENSITY_DPI) { "Projection density is outside the safe range" }
    }

    companion object {
        private const val MAX_DIMENSION_PX = 16_384
        private const val MAX_PIXEL_COUNT = 16_777_216L
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
 * A copied in-memory frame. The underlying Image is closed before this object is returned, and
 * [close] clears the copied pixels so callers cannot accidentally retain a screenshot.
 */
class EphemeralScreenFrame internal constructor(
    val widthPx: Int,
    val heightPx: Int,
    val timestampNanos: Long,
    private var rgbaBytes: ByteArray,
) : Closeable {
    private var closed = false

    val isClosed: Boolean
        get() = closed

    fun pixelAt(x: Int, y: Int): RgbaColor {
        check(!closed) { "The ephemeral frame has already been released" }
        require(x in 0 until widthPx) { "Pixel x is outside the frame" }
        require(y in 0 until heightPx) { "Pixel y is outside the frame" }
        val offset = (y * widthPx + x) * BYTES_PER_PIXEL
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
        val left = floor(region.x * widthPx).toInt().coerceIn(0, widthPx - 1)
        val top = floor(region.y * heightPx).toInt().coerceIn(0, heightPx - 1)
        val right = ceil((region.x + region.width) * widthPx).toInt().coerceIn(left + 1, widthPx)
        val bottom = ceil((region.y + region.height) * heightPx).toInt().coerceIn(top + 1, heightPx)
        val cropWidth = right - left
        val cropHeight = bottom - top
        require(cropWidth.toLong() * cropHeight <= RgbaTemplate.MAX_TEMPLATE_PIXELS) {
            "Template crop is too large to store safely"
        }
        val output = ByteArray(cropWidth * cropHeight * BYTES_PER_PIXEL)
        withRgbaBytes { bytes ->
            for (row in 0 until cropHeight) {
                val sourceOffset = ((top + row) * widthPx + left) * BYTES_PER_PIXEL
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

    /** Runs a bounded in-memory inspection without exposing the frame buffer to callers. */
    internal fun <T> withRgbaBytes(block: (ByteArray) -> T): T {
        check(!closed) { "The ephemeral frame has already been released" }
        return block(rgbaBytes)
    }

    override fun close() {
        if (closed) return
        rgbaBytes.fill(0)
        rgbaBytes = ByteArray(0)
        closed = true
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

class MediaProjectionCaptureException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Builds the one-shot consent request required for each capture session. */
object MediaProjectionConsent {
    fun createRequestIntent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: throw MediaProjectionCaptureException("Android MediaProjection is unavailable")
        return manager.createScreenCaptureIntent()
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
) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val callbackHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var state = State.NEW

    val isActive: Boolean
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
            val reader = ImageReader.newInstance(
                displaySpec.widthPx,
                displaySpec.heightPx,
                PixelFormat.RGBA_8888,
                MAX_RETAINED_IMAGES,
            )
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
                imageReader = reader
                state = State.ACTIVE
            } catch (error: Throwable) {
                runCatching { mediaProjection.unregisterCallback(callback) }
                reader.close()
                runCatching { mediaProjection.stop() }
                throw if (error is MediaProjectionCaptureException) {
                    error
                } else {
                    MediaProjectionCaptureException("MediaProjection could not start", error)
                }
            }
        }
    }

    /** Copies the newest frame, closes Android's Image immediately, and returns memory-only data. */
    fun captureLatestFrame(): EphemeralScreenFrame? = synchronized(lock) {
        check(state == State.ACTIVE) { "MediaProjection capture is not active" }
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (error: IllegalStateException) {
            throw MediaProjectionCaptureException("MediaProjection frame capture is unavailable", error)
        } ?: return@synchronized null
        try {
            copyImage(image)
        } finally {
            image.close()
        }
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
        projection = null
        projectionCallback = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        if (currentProjection != null && currentCallback != null) {
            runCatching { currentProjection.unregisterCallback(currentCallback) }
        }
        if (stopProjection) runCatching { currentProjection?.stop() }
    }

    private fun copyImage(image: Image): EphemeralScreenFrame {
        require(image.format == PixelFormat.RGBA_8888) {
            "MediaProjection returned an unsupported pixel format"
        }
        val plane = image.planes.singleOrNull()
            ?: throw MediaProjectionCaptureException("MediaProjection returned an unexpected image layout")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= BYTES_PER_PIXEL && rowStride > 0) {
            "MediaProjection returned invalid pixel strides"
        }
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * BYTES_PER_PIXEL)
        val buffer = plane.buffer.duplicate()
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val sourceStart = rowStart + x * pixelStride
                val destinationStart = (y * width + x) * BYTES_PER_PIXEL
                require(sourceStart >= 0 && sourceStart + BYTES_PER_PIXEL <= buffer.limit()) {
                    "MediaProjection returned a truncated image buffer"
                }
                repeat(BYTES_PER_PIXEL) { component ->
                    output[destinationStart + component] = buffer.get(sourceStart + component)
                }
            }
        }
        return EphemeralScreenFrame(
            widthPx = width,
            heightPx = height,
            timestampNanos = image.timestamp,
            rgbaBytes = output,
        )
    }

    private enum class State {
        NEW,
        ACTIVE,
        REVOKED,
        CLOSED,
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val MAX_RETAINED_IMAGES = 2
        const val VIRTUAL_DISPLAY_NAME = "Veyarc ephemeral workflow frame"
    }
}
