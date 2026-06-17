package com.atakmap.android.xv.aina

// Sensitive-content rules (CLAUDE.md) require that operator-tied
// Bluetooth MAC addresses never appear verbatim in committed log
// output. AINA readers log frequently during pairing diagnostics —
// route every MAC through [redactMac] so the surface stays safe
// without each call site needing to remember the rule.
//
// The redaction preserves the first and last octet, which is enough
// for an operator to correlate two log lines that refer to the same
// device without exposing the full address. Already-redacted strings
// (and non-MAC strings) pass through unchanged so this is safe to
// call defensively.

private val MAC_REGEX =
    Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

// Already-redacted: first/last octet are real hex, middle four are
// the literal `XX` placeholder (case-insensitive). Pass-through.
private val REDACTED_REGEX =
    Regex("^[0-9A-Fa-f]{2}(:[Xx]{2}){4}:[0-9A-Fa-f]{2}$")

internal fun redactMac(mac: String?): String {
    if (mac.isNullOrEmpty()) return "??"
    if (REDACTED_REGEX.matches(mac)) return mac
    if (!MAC_REGEX.matches(mac)) return mac
    val first = mac.substring(0, 2)
    val last = mac.substring(mac.length - 2)
    return "$first:XX:XX:XX:XX:$last"
}
