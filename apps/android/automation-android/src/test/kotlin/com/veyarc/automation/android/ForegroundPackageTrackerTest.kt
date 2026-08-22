package com.veyarc.automation.android

import com.veyarc.model.PackageName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPackageTrackerTest {
    @Test
    fun recognizesKnownOemTransientOverlayPackages() {
        assertTrue(ForegroundPackageTracker.isTransientSystemPackage(PackageName("com.vivo.upslide")))
        assertTrue(ForegroundPackageTracker.isTransientSystemPackage(PackageName("com.vivo.smartshot")))
        assertTrue(ForegroundPackageTracker.isTransientSystemPackage(PackageName("com.coloros.screenshot")))
        assertTrue(ForegroundPackageTracker.isTransientSystemPackage(PackageName("com.oplus.screenshot")))
    }

    @Test
    fun doesNotIgnoreDeclaredApplicationPackages() {
        assertFalse(ForegroundPackageTracker.isTransientSystemPackage(PackageName("com.example.target")))
    }
}
