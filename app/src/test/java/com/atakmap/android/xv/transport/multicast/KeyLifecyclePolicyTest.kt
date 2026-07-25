package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLifecyclePolicyTest {
    @Test
    fun `default is the 5-day ceiling`() {
        assertEquals(
            KeyLifecyclePolicy.HARD_CEILING_MS,
            KeyLifecyclePolicy.DEFAULT.maxKeyAgeMs,
        )
        assertEquals(5L * 24 * 60 * 60 * 1000, KeyLifecyclePolicy.HARD_CEILING_MS)
    }

    @Test
    fun `a shorter window is allowed`() {
        val p = KeyLifecyclePolicy.ofDays(1.0)
        assertEquals(KeyLifecyclePolicy.ONE_DAY_MS, p.maxKeyAgeMs)
        assertTrue(p.maxKeyAgeMs < KeyLifecyclePolicy.HARD_CEILING_MS)
    }

    @Test
    fun `ofDays never exceeds the ceiling`() {
        val p = KeyLifecyclePolicy.ofDays(30.0)
        assertEquals(KeyLifecyclePolicy.HARD_CEILING_MS, p.maxKeyAgeMs)
    }

    @Test
    fun `clampMs pins into the valid range`() {
        assertEquals(1L, KeyLifecyclePolicy.clampMs(0))
        assertEquals(1L, KeyLifecyclePolicy.clampMs(-5))
        assertEquals(KeyLifecyclePolicy.ONE_DAY_MS, KeyLifecyclePolicy.clampMs(KeyLifecyclePolicy.ONE_DAY_MS))
        assertEquals(
            KeyLifecyclePolicy.HARD_CEILING_MS,
            KeyLifecyclePolicy.clampMs(Long.MAX_VALUE),
        )
    }

    @Test
    fun `constructor rejects a window past the ceiling`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyLifecyclePolicy(KeyLifecyclePolicy.HARD_CEILING_MS + 1)
        }
    }

    @Test
    fun `constructor rejects a non-positive window`() {
        assertThrows(IllegalArgumentException::class.java) { KeyLifecyclePolicy(0) }
        assertThrows(IllegalArgumentException::class.java) { KeyLifecyclePolicy(-1) }
    }
}
