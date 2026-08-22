package com.veyarc.automation.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.veyarc.engine.AppLauncher
import com.veyarc.model.PackageName

/** Launches only an explicitly declared package using its launcher activity. */
internal class AndroidAppLauncher(
    context: Context,
) : AppLauncher {
    private val applicationContext = context.applicationContext

    override fun launchApp(packageName: PackageName) {
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(packageName.value)
            ?: throw AndroidAutomationException(
                "No launcher activity was found for package ${packageName.value}",
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            applicationContext.startActivity(launchIntent)
        } catch (error: ActivityNotFoundException) {
            throw AndroidAutomationException(
                "The launcher activity for package ${packageName.value} could not be started",
                error,
            )
        } catch (error: SecurityException) {
            throw AndroidAutomationException(
                "Android denied launching package ${packageName.value}",
                error,
            )
        }
    }
}
