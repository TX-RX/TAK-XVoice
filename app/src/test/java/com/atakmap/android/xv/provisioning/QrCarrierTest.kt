package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.AeadCodec
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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
        return MultiFormatReader().decode(bitmap).text
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
    fun `capacity guard trips on an oversize payload`() {
        assertTrue(QrCarrier.wouldLikelyExceedQrCapacity("x".repeat(QrCarrier.COMFORTABLE_BYTE_CEILING + 1)))
        assertFalse(QrCarrier.wouldLikelyExceedQrCapacity("x".repeat(10)))
    }
}
