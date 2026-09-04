package com.veyarc.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies API 33+ recording prerequisites without starting a recording or injecting input. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class Api33RecordingConfigurationTest {
    @Test
    fun mergedAppDeclaresNotificationPermissionAndPrivateForegroundServices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = packageInfo(context)

        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS))

        val recording = packageInfo.services.orEmpty().singleOrNull {
            it.name == "com.veyarc.feature.recorder.RecordingForegroundService"
        }
        val projection = packageInfo.services.orEmpty().singleOrNull {
            it.name == "com.veyarc.automation.android.MediaProjectionCaptureService"
        }

        assertNotNull(recording)
        assertNotNull(projection)
        assertFalse(requireNotNull(recording).exported)
        assertFalse(requireNotNull(projection).exported)
        assertTrue(recording.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        assertTrue(projection.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context) = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
    )
}
