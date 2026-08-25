package com.veyarc.recording

import com.veyarc.model.NormalizedCoordinate

/** Android display rotations expressed independently of the Android framework. */
enum class TouchDisplayRotation {
    ROTATION_0,
    ROTATION_90,
    ROTATION_180,
    ROTATION_270,
    ;

    companion object {
        fun fromAndroidRotation(rotation: Int): TouchDisplayRotation? = when (rotation) {
            0 -> ROTATION_0
            1 -> ROTATION_90
            2 -> ROTATION_180
            3 -> ROTATION_270
            else -> null
        }
    }
}

/** Converts native touchscreen samples into normalized coordinates in the current logical display. */
object RawTouchCoordinateMapper {
    fun toLogicalNormalized(
        rawX: Long,
        rawY: Long,
        minX: Long,
        maxX: Long,
        minY: Long,
        maxY: Long,
        rotation: TouchDisplayRotation,
    ): NormalizedCoordinate {
        require(maxX > minX) { "The raw touchscreen X range must be positive" }
        require(maxY > minY) { "The raw touchscreen Y range must be positive" }

        val nativeX = ((rawX.coerceIn(minX, maxX) - minX).toDouble() / (maxX - minX).toDouble())
        val nativeY = ((rawY.coerceIn(minY, maxY) - minY).toDouble() / (maxY - minY).toDouble())
        return when (rotation) {
            TouchDisplayRotation.ROTATION_0 -> NormalizedCoordinate(nativeX, nativeY)
            TouchDisplayRotation.ROTATION_90 -> NormalizedCoordinate(1.0 - nativeY, nativeX)
            TouchDisplayRotation.ROTATION_180 -> NormalizedCoordinate(1.0 - nativeX, 1.0 - nativeY)
            TouchDisplayRotation.ROTATION_270 -> NormalizedCoordinate(nativeY, 1.0 - nativeX)
        }
    }
}
