package com.atakmap.android.xv.aina

enum class AinaButton(
    val bitMask: Int,
) {
    PTT(0x01),
    PTTE(0x02),
    PTTS(0x04),
    PTTB1(0x08),
    PTTB2(0x10),
    MFB(0x20),
    ;

    companion object {
        const val HEARTBEAT_MASK: Int = 0x80
        const val ALL_BUTTON_MASK: Int = 0x3F
    }
}
