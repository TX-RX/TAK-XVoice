package com.atakmap.android.xv.aina

// Parser for AINA APTT V1 ASCII button protocol over Bluetooth Classic SPP.
// Frames are `+CMD=P` (press) or `+CMD=R` (release); V1 firmware does not use
// CR/LF terminators, so we delimit on three signals: explicit CR/LF, a leading
// `+` while the buffer already has data, or end-of-chunk if the buffer matches
// the `+CMD=[PR]` shape.
class AinaAsciiParser {
    private val buf = StringBuilder(32)

    fun process(
        bytes: ByteArray,
        length: Int,
    ): List<ButtonEdge> {
        if (length <= 0) return emptyList()
        val out = ArrayList<ButtonEdge>(2)
        for (i in 0 until length) {
            val c = (bytes[i].toInt() and 0xFF).toChar()
            when {
                c == '\r' || c == '\n' -> {
                    flushFrame(out)
                }
                c == '+' && buf.isNotEmpty() -> {
                    flushFrame(out)
                    buf.append(c)
                }
                else -> {
                    if (buf.length < MAX_FRAME) buf.append(c)
                }
            }
        }
        if (looksComplete(buf)) {
            flushFrame(out)
        }
        return out
    }

    fun reset() {
        buf.setLength(0)
    }

    private fun flushFrame(out: MutableList<ButtonEdge>) {
        val edge = parseFrame(buf.toString())
        buf.setLength(0)
        if (edge != null) out += edge
    }

    private fun looksComplete(b: CharSequence): Boolean {
        if (b.length < 4) return false
        if (b[0] != '+') return false
        val last = b[b.length - 1]
        if (last != 'P' && last != 'R' && last != 'p' && last != 'r') return false
        val eq = b.indexOf('=')
        return eq in 2..(b.length - 2)
    }

    private fun parseFrame(raw: String): ButtonEdge? {
        val frame = raw.trim()
        if (frame.isEmpty() || !frame.startsWith('+')) return null
        val eq = frame.indexOf('=')
        if (eq <= 1 || eq == frame.length - 1) return null
        val cmd = frame.substring(1, eq).uppercase()
        val arg = frame.substring(eq + 1).uppercase()
        val button = COMMAND_TO_BUTTON[cmd] ?: return null
        val isDown =
            when (arg) {
                "P" -> true
                "R" -> false
                else -> return null
            }
        return ButtonEdge(button, isDown)
    }

    data class ButtonEdge(
        val button: AinaButton,
        val isDown: Boolean,
    )

    companion object {
        private const val MAX_FRAME = 32

        private val COMMAND_TO_BUTTON: Map<String, AinaButton> =
            mapOf(
                "PTT" to AinaButton.PTT,
                "PTTS" to AinaButton.PTTS,
                "PTTE" to AinaButton.PTTE,
                "PTTB1" to AinaButton.PTTB1,
                "PTTB2" to AinaButton.PTTB2,
            )
    }
}
