package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsPlanCarrierTest {
    private fun plainPlan(): CommsPlan =
        CommsPlan(
            planId = "plan-0001",
            name = "Exercise Alpha",
            createdAtMs = 1_750_000_000_000L,
            serverIdentity = "tak.example.com",
            channels = listOf(CommsPlan.Channel("Ops 1", ChannelMulticastConfig.defaultFor("ops-1"))),
        )

    private fun keyedPlan(): CommsPlan =
        plainPlan().copy(
            channels =
            plainPlan().channels +
                CommsPlan.Channel(
                    displayName = "Bravo",
                    config = ChannelMulticastConfig.defaultFor("bravo"),
                    preSharedKey = ByteArray(AeadCodec.KEY_BYTES) { 0x42 },
                ),
        )

    @Test
    fun `clear carrier round-trips a keyless plan`() {
        val text = CommsPlanCarrier.encodeClear(plainPlan())
        assertTrue(text.startsWith(CommsPlanCarrier.CLEAR_PREFIX))
        assertTrue(CommsPlanCarrier.isCarrierText(text))
        assertFalse(CommsPlanCarrier.needsPassphrase(text))
        assertEquals(plainPlan(), CommsPlanCarrier.decode(text))
    }

    @Test
    fun `clear carrier refuses plans that carry keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.encodeClear(keyedPlan())
        }
    }

    @Test
    fun `locked carrier round-trips with the right passphrase`() {
        val text = CommsPlanCarrier.encodeLocked(keyedPlan(), "correct horse battery".toCharArray())
        assertTrue(text.startsWith(CommsPlanCarrier.LOCKED_PREFIX))
        assertTrue(CommsPlanCarrier.needsPassphrase(text))
        val back = CommsPlanCarrier.decode(text, "correct horse battery".toCharArray())
        assertEquals(keyedPlan(), back)
        assertArrayEquals(
            ByteArray(AeadCodec.KEY_BYTES) { 0x42 },
            back.channels[1].preSharedKey,
        )
    }

    @Test
    fun `wrong passphrase fails loudly instead of half-loading`() {
        val text = CommsPlanCarrier.encodeLocked(keyedPlan(), "correct horse battery".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(text, "wrong words entirely".toCharArray())
        }
    }

    @Test
    fun `locked carrier without a passphrase is rejected up front`() {
        val text = CommsPlanCarrier.encodeLocked(keyedPlan(), "correct horse battery".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(text, passphrase = null)
        }
    }

    @Test
    fun `tampered payloads are rejected`() {
        val text = CommsPlanCarrier.encodeLocked(keyedPlan(), "correct horse battery".toCharArray())
        // Flip one character deep in the sealed payload.
        val idx = text.length - 5
        val flipped = if (text[idx] != 'A') 'A' else 'B'
        val tampered = text.substring(0, idx) + flipped + text.substring(idx + 1)
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(tampered, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `unknown prefixes and non-carrier text are rejected`() {
        assertFalse(CommsPlanCarrier.isCarrierText("https://tak.example.com/whatever"))
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode("XVCP9:AAAA")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(CommsPlanCarrier.CLEAR_PREFIX + "!!not-base64!!")
        }
    }

    @Test
    fun `two locked encodings of the same plan differ (fresh salt) but both decode`() {
        val phrase = "correct horse battery".toCharArray()
        val a = CommsPlanCarrier.encodeLocked(keyedPlan(), phrase)
        val b = CommsPlanCarrier.encodeLocked(keyedPlan(), phrase)
        assertTrue(a != b)
        assertEquals(CommsPlanCarrier.decode(a, phrase), CommsPlanCarrier.decode(b, phrase))
    }
}
