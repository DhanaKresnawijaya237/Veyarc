package com.veyarc.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.automation.android.MediaProjectionCaptureRuntime
import com.veyarc.automation.android.MediaProjectionConsent
import com.veyarc.feature.editor.VeyarcChoice
import com.veyarc.feature.editor.VeyarcChoicePicker
import com.veyarc.feature.recorder.CoordinateRecordingController
import com.veyarc.feature.recorder.CoordinateRecordingRuntime
import com.veyarc.feature.recorder.RecordingImageBlock

/** A small, temporary popup over the recorded app. No editor or input interception is started. */
class RecordingImageCaptureActivity : ComponentActivity() {
    private val controller by lazy { CoordinateRecordingRuntime.controller(this) }
    private val token by lazy { intent.getStringExtra(CoordinateRecordingController.EXTRA_IMAGE_REQUEST) }

    private fun closePopup() {
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!controller.acceptsImageCapture(token)) {
            closePopup()
            return
        }
        setContent {
            val preferences by remember { AppPreferences.observe(this@RecordingImageCaptureActivity) }.collectAsState(initial = AppUiPreferences())
            val state by controller.state.collectAsState()
            val builder by controller.builder.collectAsState()
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            var requested by rememberSaveable { mutableStateOf(false) }
            val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val kind = RecordingImageBlock.entries.firstOrNull { it.name == selected }
                if (kind != null && MediaProjectionConsent.wasGranted(result.resultCode, result.data) && controller.acceptsImageCapture(token) && controller.builder.value.showAdd) {
                    controller.captureImageBlock(requireNotNull(token), kind, result.resultCode, requireNotNull(result.data))
                } else {
                    controller.dismissAdd(token)
                }
                closePopup()
            }
            LaunchedEffect(state, builder.showAdd) { if (!controller.acceptsImageCapture(token) || !builder.showAdd) closePopup() }
            LaunchedEffect(selected) {
                if (selected != null && !requested) {
                    requested = true
                    runCatching {
                        check(!MediaProjectionCaptureRuntime.isActive) { "Finish the current screen capture first." }
                        consent.launch(MediaProjectionConsent.createRequestIntent(this@RecordingImageCaptureActivity, fullDisplay = true))
                    }.onFailure {
                        Toast.makeText(this@RecordingImageCaptureActivity, it.message ?: "Screen capture is unavailable.", Toast.LENGTH_LONG).show()
                        controller.dismissAdd(token)
                        closePopup()
                    }
                }
            }
            VeyarcTheme(
                darkTheme = when (preferences.theme) {
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                },
                particlesEnabled = false,
            ) {
                if (selected == null) {
                    VeyarcChoicePicker(
                        title = "Add from screen",
                        options = controller.imageBlockOptions.map { kind ->
                            VeyarcChoice(kind.name, kind.label, group = if (kind.edit == null) 1 else 0)
                        },
                        onDismiss = ::closePopup,
                        onSelect = { selected = it.id },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && controller.builder.value.showAdd && controller.acceptsImageCapture(token)) controller.dismissAdd(token)
        super.onDestroy()
    }
}
