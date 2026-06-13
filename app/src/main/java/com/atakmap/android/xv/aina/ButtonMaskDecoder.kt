package com.atakmap.android.xv.aina

class ButtonMaskDecoder {
    private var lastButtonState: Int = 0

    fun process(rawMask: Int): List<ButtonEdge> {
        val current = rawMask and AinaButton.ALL_BUTTON_MASK
        val previous = lastButtonState
        if (current == previous) return emptyList()

        val edges = ArrayList<ButtonEdge>(2)
        for (b in AinaButton.entries) {
            val nowDown = (current and b.bitMask) != 0
            val wasDown = (previous and b.bitMask) != 0
            if (nowDown != wasDown) {
                edges += ButtonEdge(b, nowDown)
            }
        }
        lastButtonState = current
        return edges
    }

    fun reset() {
        lastButtonState = 0
    }

    data class ButtonEdge(
        val button: AinaButton,
        val isDown: Boolean,
    )
}
