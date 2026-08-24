package com.veyarc.automation.android

import com.veyarc.model.PackageName

/** Public, package-only view of the accessibility service's active application tracker. */
object AndroidForegroundPackage {
    fun current(): PackageName? = ForegroundPackageTracker.current()
}
