package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robustness / fuzz-adjacent characterization for [CommsPlanCarrier].
 *
 * API contract PINNED here: [CommsPlanCarrier.decode] NEVER returns
 * null and NEVER lets a raw/undeclared exception escape — every
 * malformed, truncated, wrong-prefix, or wrong-passphrase input is
 * rejected with a typed [IllegalArgumentException] carrying an
 * operator-readable message. Imports are explicit UI actions and must
 * fail loudly, not half-load. These cases complement the happy-path /
 * tamper coverage in CommsPlanCarrierTest.
 *
 * Robolectric for a real org.json + the platform ChaCha20-Poly1305
 * provider (encodeLocked/decrypt exercise it), matching the sibling
 * carrier suite.
 */
@RunWith(RobolectricTestRunner::class)
class CommsPlanCarrierRobustnessTest {
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

    private fun b64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    // ---- empty / wrong-prefix / non-carrier text ----

    @Test
    fun `empty string is rejected with a typed exception (not a crash)`() {
        assertThrows(IllegalArgumentException::class.java) { CommsPlanCarrier.decode("") }
        assertThrows(IllegalArgumentException::class.java) { CommsPlanCarrier.decode("   ") }
    }

    @Test
    fun `wrong prefix is rejected as unknown carrier`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                CommsPlanCarrier.decode("NOTXV1:" + b64Url("whatever".toByteArray()))
            }
        assertTrue("message names the unknown prefix: '${ex.message}'", ex.message!!.contains("unknown prefix"))
        // A close-but-wrong version prefix (XVCP2:) is also unknown.
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode("XVCP2:" + b64Url(plainPlan().toCanonicalBytes()))
        }
    }

    // ---- corrupted / truncated body under a valid prefix ----

    @Test
    fun `valid clear prefix with non-base64 body is rejected`() {
        // '*' and '#' are outside the base64url alphabet.
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(CommsPlanCarrier.CLEAR_PREFIX + "***not base64***")
        }
    }

    @Test
    fun `valid clear prefix with valid base64 but garbage json is rejected`() {
        // Decodes cleanly as base64url, but the bytes are not a plan.
        val carrier = CommsPlanCarrier.CLEAR_PREFIX + b64Url("this is not json at all".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { CommsPlanCarrier.decode(carrier) }
    }

    @Test
    fun `clear prefix wrapping a truncated canonical json is rejected`() {
        // Chop the canonical JSON in half so base64 still decodes but the
        // JSON is unterminated → fromJson fails loudly.
        val whole = plainPlan().toCanonicalBytes()
        val half = whole.copyOfRange(0, whole.size / 2)
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(CommsPlanCarrier.CLEAR_PREFIX + b64Url(half))
        }
    }

    // ---- locked carrier: wrong / blank / missing passphrase, truncation ----

    @Test
    fun `locked carrier with a blank (empty) passphrase fails loudly, not a crash`() {
        val text = CommsPlanCarrier.encodeLocked(keyedPlan(), "correct horse battery".toCharArray())
        // An empty CharArray is non-null, so it clears the requireNotNull
        // gate and instead derives the wrong key → BadTag → typed IAE.
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(text, CharArray(0))
        }
    }

    @Test
    fun `locked carrier with a wrong non-empty passphrase is rejected`() {
        val text = CommsPlanCarrier.encodeLocked(keyedPlan(), "correct horse battery".toCharArray())
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                CommsPlanCarrier.decode(text, "definitely not it".toCharArray())
            }
        assertTrue("operator-readable wrong-passphrase message: '${ex.message}'", ex.message!!.contains("passphrase"))
    }

    @Test
    fun `locked prefix with a too-short body is rejected as truncated`() {
        // Below the salt + header + tag floor: must hit the "truncated"
        // guard rather than an index-out-of-bounds on copyOfRange.
        val tooShort = ByteArray(4) { 0x01 }
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(CommsPlanCarrier.LOCKED_PREFIX + b64Url(tooShort), "anything".toCharArray())
        }
    }

    @Test
    fun `locked prefix with non-base64 body is rejected before any crypto`() {
        assertThrows(IllegalArgumentException::class.java) {
            CommsPlanCarrier.decode(CommsPlanCarrier.LOCKED_PREFIX + "%%% not base64 %%%", "anything".toCharArray())
        }
    }

    @Test
    fun `leading and trailing whitespace around a valid carrier still decodes`() {
        // decode() trims first; confirms the robustness cases above fail
        // on content, not on incidental surrounding whitespace.
        val text = CommsPlanCarrier.encodeClear(plainPlan())
        assertTrue(CommsPlanCarrier.isCarrierText("  \n$text\t "))
        org.junit.Assert.assertEquals(plainPlan(), CommsPlanCarrier.decode("  \n$text\t "))
    }
}
