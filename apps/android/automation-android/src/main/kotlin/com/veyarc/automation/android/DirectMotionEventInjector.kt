package com.veyarc.automation.android

import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Injects one bounded single-pointer MotionEvent through the system input manager.
 *
 * The helper process runs with Shizuku's shell identity, so this uses the same privileged input
 * manager permission as the platform `input` command without starting a new process for every
 * frame. Reflection is intentional: InputManagerGlobal/InputManager is hidden from the normal
 * application SDK. If the OEM does not expose either hidden API, the caller keeps its validated
 * `/system/bin/input motionevent` fallback.
 */
internal class DirectMotionEventInjector private constructor(
    private val inputManager: Any,
    private val injectInputEvent: Method,
    private val injectInputEventToTarget: Method?,
) {
    fun inject(
        action: Int,
        x: Float,
        y: Float,
        downTimeMillis: Long,
        eventTimeMillis: Long,
        displayId: Int,
        targetUid: Int? = null,
    ) {
        if (targetUid != null && injectInputEventToTarget == null) {
            throw UnsupportedOperationException(
                "This device does not expose targeted input injection for replay protection",
            )
        }
        val pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            NO_PRESSURE
        } else {
            DEFAULT_PRESSURE
        }
        val pointerProperties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pointerCoords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = DEFAULT_SIZE
        }
        val event = MotionEvent.obtain(
            downTimeMillis,
            eventTimeMillis.coerceAtLeast(downTimeMillis),
            action,
            1,
            arrayOf(pointerProperties),
            arrayOf(pointerCoords),
            DEFAULT_META_STATE,
            DEFAULT_BUTTON_STATE,
            DEFAULT_PRECISION_X,
            DEFAULT_PRECISION_Y,
            DEFAULT_DEVICE_ID,
            DEFAULT_EDGE_FLAGS,
            InputDevice.SOURCE_TOUCHSCREEN,
            displayId,
        )
        try {
            try {
                if (targetUid != null) {
                    injectInputEventToTarget?.invoke(
                        inputManager,
                        event,
                        INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH,
                        targetUid,
                    )
                } else {
                    injectInputEvent.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
                }
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } finally {
            event.recycle()
        }
    }

    val supportsTargetedInjection: Boolean
        get() = injectInputEventToTarget != null

    companion object {
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
        private const val DEFAULT_PRESSURE = 1.0f
        private const val NO_PRESSURE = 0.0f
        private const val DEFAULT_SIZE = 1.0f
        private const val DEFAULT_META_STATE = 0
        private const val DEFAULT_BUTTON_STATE = 0
        private const val DEFAULT_PRECISION_X = 1.0f
        private const val DEFAULT_PRECISION_Y = 1.0f
        private const val DEFAULT_DEVICE_ID = 0
        private const val DEFAULT_EDGE_FLAGS = 0

        fun createOrNull(): DirectMotionEventInjector? = sequenceOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager",
        ).mapNotNull { className -> resolve(className) }
            .firstOrNull()

        private fun resolve(className: String): DirectMotionEventInjector? = runCatching {
            val managerClass = Class.forName(className)
            val manager = managerClass.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }.invoke(null) ?: return@runCatching null
            val injectMethod = managerClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply {
                isAccessible = true
            }
            val targetedMethod = runCatching {
                managerClass.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply {
                    isAccessible = true
                }
            }.getOrNull()
            DirectMotionEventInjector(manager, injectMethod, targetedMethod)
        }.getOrNull()
    }
}
