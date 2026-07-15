package com.atakmap.android.xv.provisioning

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes a comms-plan carrier string ([CommsPlanCarrier]) into a QR
 * code — the in-person, no-network transport for sharing a channel plan:
 * one device shows the QR, the other scans it.
 *
 * This is the pure, platform-independent half (text → [BitMatrix]). The
 * Android bitmap rendering (BitMatrix → `Bitmap` for an ImageView) and
 * the camera scan live in the UI layer, but the correctness question —
 * *does a passphrase-locked plan actually fit in a QR and decode back
 * intact?* — is answered here and unit-tested with real zxing.
 *
 * Error-correction level M (~15% recoverable) balances density against
 * robustness to a smudged screen / camera glare. A passphrase-locked
 * plan for a handful of channels is a few hundred base64url bytes, well
 * within QR capacity at M; [wouldLikelyExceedQrCapacity] flags the rare
 * oversize case so the UI can fall back to the share sheet instead of
 * rendering an unscannable code.
 *
 * zxing is provided by ATAK at runtime (the plugin declares it
 * `compileOnly`), so nothing new is bundled.
 */
object QrCarrier {
    /** A comfortable on-screen size; the UI may override for the device DPI. */
    const val DEFAULT_SIZE_PX = 640

    /**
     * Conservative byte ceiling beyond which a QR at ECC-M gets dense
     * enough to be flaky to scan off a phone screen. Above this the UI
     * should steer the operator to the share sheet. (QR v40 at M holds
     * ~2300 bytes; we stay well under for reliable phone-to-phone scans.)
     */
    const val COMFORTABLE_BYTE_CEILING = 1_200

    fun encode(
        text: String,
        sizePx: Int = DEFAULT_SIZE_PX,
    ): BitMatrix {
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** True when [text] is large enough that a phone-screen QR scan gets unreliable. */
    fun wouldLikelyExceedQrCapacity(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > COMFORTABLE_BYTE_CEILING
}
