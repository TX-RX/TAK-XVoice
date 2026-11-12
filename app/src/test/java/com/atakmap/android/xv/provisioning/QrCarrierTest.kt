package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric supplies a real org.json — the mockable android.jar stubs
// return defaults, which silently zeroes the comms-plan schema version.
@RunWith(RobolectricTestRunner::class)
class QrCarrierTest {
    // Round-trips through zxing-core only: BitMatrix → ARGB pixels →
    // RGBLuminanceSource → decode. No zxing-javase / java.awt — the
    // Android unit-test compilation puts android.jar on the boot
    // classpath, which has no java.awt, so an AWT-based round-trip
    // does not even compile in this source set.
    //
    // The source is a synthetic, axis-aligned render of a single QR, so
    // we decode it as exactly that: PURE_BARCODE skips the probabilistic
    // finder-pattern grid search (whose fast path intermittently missed
    // valid-but-dense codes — the payload length is fixed, but the random
    // PBKDF2 salt + AEAD nonce vary the module pattern run to run, and
    // some patterns tripped the heuristic). TRY_HARDER keeps it exhaustive
    // and a QR-only reader avoids multi-format ambiguity. Together these
    // make the clean round-trip deterministic regardless of payload bytes.
    private val qrHints =
        mapOf(
            DecodeHintType.PURE_BARCODE to true,
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )

    private fun decode(text: String): String {
        val matrix = QrCarrier.encode(text, sizePx = 900)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(w, h, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        // A fresh reader per call: QRCodeReader keeps no state we want to
        // carry between decodes.
        return QRCodeReader().decode(bitmap, qrHints).text
    }

    @Test
    fun `clear carrier round-trips through a QR`() {
        val plan =
            CommsPlan(
                planId = "plan-0001",
                name = "Exercise Alpha",
                createdAtMs = 1_750_000_000_000L,
                serverIdentity = "tak.example.com",
                channels = listOf(CommsPlan.Channel("Ops 1", ChannelMulticastConfig.defaultFor("ops-1"))),
            )
        val text = CommsPlanCarrier.encodeClear(plan)
        assertEquals(text, decode(text))
    }

    @Test
    fun `passphrase-locked multi-channel plan fits in a QR and decodes intact`() {
        // The real case: an encrypted plan with keys — the payload the
        // in-person QR transport must actually carry.
        val channels =
            (1..4).map { i ->
                CommsPlan.Channel(
                    displayName = "Falcon-$i",
                    config = ChannelMulticastConfig.defaultFor("falcon-$i"),
                    preSharedKey = ByteArray(AeadCodec.KEY_BYTES) { (i * 7).toByte() },
                )
            }
        val plan =
            CommsPlan(
                planId = "plan-9999",
                name = "adhoc",
                createdAtMs = 1_750_000_000_000L,
                channels = channels,
            )
        val carrier = CommsPlanCarrier.encodeLocked(plan, "correct horse battery".toCharArray())
        assertFalse("payload should stay within comfortable QR capacity", QrCarrier.wouldLikelyExceedQrCapacity(carrier))

        val scannedBack = decode(carrier)
        assertEquals(carrier, scannedBack)
        // And the plan itself survives the whole trip.
        assertEquals(plan, CommsPlanCarrier.decode(scannedBack, "correct horse battery".toCharArray()))
    }

    @Test
    fun `locked carrier round-trips deterministically across many random nonces`() {
        // Regression guard for the decode flake: encodeLocked draws a fresh
        // random salt + AEAD nonce every call, so each iteration renders a
        // different module pattern at the same QR geometry. Every one must
        // decode. Before the PURE_BARCODE / TRY_HARDER fix this failed on a
        // sizeable fraction of iterations with NotFoundException; 64 rounds
        // makes a lingering heuristic miss statistically certain to surface.
        val channels =
            (1..4).map { i ->
                CommsPlan.Channel(
                    displayName = "Falcon-$i",
                    config = ChannelMulticastConfig.defaultFor("falcon-$i"),
                    preSharedKey = ByteArray(AeadCodec.KEY_BYTES) { (i * 7).toByte() },
                )
            }
        val plan =
            CommsPlan(
                planId = "plan-9999",
                name = "adhoc",
                createdAtMs = 1_750_000_000_000L,
                channels = channels,
            )
        val passphrase = "correct horse battery"
        repeat(64) { iteration ->
            val carrier = CommsPlanCarrier.encodeLocked(plan, passphrase.toCharArray())
            assertFalse(
                "payload should stay within comfortable QR capacity",
                QrCarrier.wouldLikelyExceedQrCapacity(carrier),
            )
            val scannedBack = decode(carrier)
            assertEquals("QR round-trip failed on iteration $iteration", carrier, scannedBack)
            assertEquals(plan, CommsPlanCarrier.decode(scannedBack, passphrase.toCharArray()))
        }
    }

    @Test
    fun `capacity guard trips on an oversize payload`() {
        assertTrue(QrCarrier.wouldLikelyExceedQrCapacity("x".repeat(QrCarrier.COMFORTABLE_BYTE_CEILING + 1)))
        assertFalse(QrCarrier.wouldLikelyExceedQrCapacity("x".repeat(10)))
    }
}
