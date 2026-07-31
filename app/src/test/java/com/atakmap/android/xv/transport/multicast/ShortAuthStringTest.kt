package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortAuthStringTest {
    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `sas is six digits by default`() {
        val sas = ShortAuthString.derive(keyA, keyB)
        assertEquals(6, sas.length)
        assertTrue("must be all digits: $sas", sas.all { it.isDigit() })
    }

    @Test
    fun `sas is role-symmetric`() {
        assertEquals(
            ShortAuthString.derive(keyA, keyB),
            ShortAuthString.derive(keyB, keyA),
        )
    }

    @Test
    fun `sas is deterministic`() {
        assertEquals(
            ShortAuthString.derive(keyA, keyB),
            ShortAuthString.derive(keyA.copyOf(), keyB.copyOf()),
        )
    }

    @Test
    fun `different keys generally yield different codes`() {
        val keyC = ByteArray(32) { (it xor 0x5A).toByte() }
        // Not a security assertion — just a sanity check that the code
        // actually varies with the inputs (a MITM's substituted key
        // produces a different SAS on the other leg).
        assertNotEquals(
            ShortAuthString.derive(keyA, keyB),
            ShortAuthString.derive(keyA, keyC),
        )
    }

    @Test
    fun `digit count is honored and zero-padded`() {
        val sas4 = ShortAuthString.derive(keyA, keyB, digits = 4)
        assertEquals(4, sas4.length)
        assertTrue(sas4.all { it.isDigit() })
    }

    @Test
    fun `invalid digit counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShortAuthString.derive(keyA, keyB, digits = 0) }
        assertThrows(IllegalArgumentException::class.java) { ShortAuthString.derive(keyA, keyB, digits = 10) }
    }

    @Test
    fun `empty keys are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ShortAuthString.derive(ByteArray(0), keyB) }
        assertThrows(IllegalArgumentException::class.java) { ShortAuthString.derive(keyA, ByteArray(0)) }
    }

    @Test
    fun `short keys are supported and symmetric`() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(5, 6, 7, 8)
        val sas = ShortAuthString.derive(a, b)
        assertEquals(6, sas.length)
        assertTrue(sas.all { it.isDigit() })
        assertEquals(sas, ShortAuthString.derive(b, a))
    }
}
