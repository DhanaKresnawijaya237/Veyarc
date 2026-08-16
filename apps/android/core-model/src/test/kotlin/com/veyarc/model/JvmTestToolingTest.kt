package com.veyarc.model

import org.junit.Assert.assertEquals
import org.junit.Test

class JvmTestToolingTest {
    @Test
    fun junitExecutesKotlinTests() {
        assertEquals(4, 2 + 2)
    }
}
