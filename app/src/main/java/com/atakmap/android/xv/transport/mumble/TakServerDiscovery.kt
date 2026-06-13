package com.atakmap.android.xv.transport.mumble

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.atakmap.comms.NetConnectString
import com.atakmap.comms.TAKServer
import com.atakmap.comms.TAKServerListener

// Discovers TAK servers ATAK is currently configured/connected to. OTS hosts
// Mumble (Murmur) on the same hostname as the TAK CoT stream, so Mumble's
// host = the TAK server's host. Port is independent (Mumble default 64738,
// configurable in OTS).
//
// Source of truth: TAKServerListener.getInstance() — singleton populated by
// ATAK's CommsMapComponent. Returns getConnectString() in the form
// "ssl:host:port" (or "tcp:host:port" for plaintext, rare in CIV).
object TakServerDiscovery {
    private const val TAG = "XvTakDiscovery"

    // Log de-duplication. selectAuto is called from the dropdown's 2s
    // refresh loop AND from connect-time code; without this, the "picked
    // connected TAK server" line fires every 2s at INFO and buries real
    // events in the buffer. Tracks the last value we logged for each
    // outcome so a steady-state poll is silent while a genuine state
    // change still surfaces in logcat.
    @Volatile private var lastAutoPickedHost: String? = null

    @Volatile private var lastWarnedEmpty: Boolean = false

    @Volatile private var lastFallbackHost: String? = null

    data class TakHost(
        val description: String,
        val host: String,
        val takPort: Int,
        val connected: Boolean,
        // Nullable so tests can construct TakHost instances without
        // standing up a real TAKServer (an ATAK runtime class that
        // doesn't resolve under unit tests). Production code always
        // supplies a real TAKServer via [collect]; nobody outside this
        // file reads .raw, so the relaxation has no caller impact.
        val raw: TAKServer? = null,
    )

    fun all(): List<TakHost> = collect(connectedOnly = false)

    fun connected(): List<TakHost> = collect(connectedOnly = true)

    private fun collect(connectedOnly: Boolean): List<TakHost> {
        val listener = TAKServerListener.getInstance() ?: return emptyList()
        val servers =
            if (connectedOnly) listener.connectedServers ?: emptyArray() else listener.servers ?: emptyArray()
        return servers.mapNotNull { s ->
            val ncs = NetConnectString.fromString(s.connectString) ?: return@mapNotNull null
            TakHost(
                description = s.description ?: ncs.host,
                host = ncs.host,
                takPort = ncs.port,
                connected = s.isConnected,
                raw = s,
            )
        }
    }

    // Pick the TAK server matching an explicit host preference (exact match,
    // case-insensitive). Used by the multi-server picker UI: operator selects
    // a host in Settings; that exact host string is what we want, not a
    // substring guess. Falls back to [pick] (auto) when [preferredHost] is
    // null/blank OR no longer present in ATAK's server list (e.g. operator
    // unenrolled it after picking). Caller is responsible for clearing the
    // preference on persistent miss if the UX should stop offering it.
    fun pickPreferred(preferredHost: String?): TakHost? = selectByExactHost(all(), preferredHost)

    // Pick a TAK server to derive the Mumble host from. Preference order:
    //   1. If pattern given: substring match against description or host (any
    //      state — connected or not, the host is the same either way).
    //   2. Else: first currently-connected server.
    //   3. Else: first configured server (Mumble can connect even when ATAK
    //      isn't streaming CoT — the auth still uses the cert).
    fun pick(pattern: String?): TakHost? = selectByPatternOrAuto(all(), pattern)

    /**
     * Auto-pick: first currently-connected → first configured → null.
     * Pure function — same selection logic as the no-arg path of
     * [pick], but parameterized on the host list so tests can pin
     * the behavior without standing up TAKServerListener.
     */
    @VisibleForTesting
    internal fun selectAuto(hosts: List<TakHost>): TakHost? {
        if (hosts.isEmpty()) {
            if (!lastWarnedEmpty) {
                Log.w(TAG, "no TAK servers configured in ATAK")
                lastWarnedEmpty = true
            }
            lastAutoPickedHost = null
            lastFallbackHost = null
            return null
        }
        lastWarnedEmpty = false
        val live = hosts.firstOrNull { it.connected }
        if (live != null) {
            if (lastAutoPickedHost != live.host) {
                Log.i(TAG, "picked connected TAK server: ${live.description} (${live.host})")
                lastAutoPickedHost = live.host
            }
            lastFallbackHost = null
            return live
        }
        lastAutoPickedHost = null
        val first = hosts.first()
        if (lastFallbackHost != first.host) {
            Log.i(TAG, "no connected TAK server; falling back to first configured: ${first.description} (${first.host})")
            lastFallbackHost = first.host
        }
        return first
    }

    /**
     * Exact-host pick: case-insensitive match on [TakHost.host]. Falls
     * back to [selectAuto] when [preferredHost] is null/blank OR the
     * host is no longer in the list. Pure function — testable directly.
     */
    @VisibleForTesting
    internal fun selectByExactHost(
        hosts: List<TakHost>,
        preferredHost: String?,
    ): TakHost? {
        if (preferredHost.isNullOrBlank()) return selectAuto(hosts)
        val match = hosts.firstOrNull { it.host.equals(preferredHost, ignoreCase = true) }
        if (match != null) {
            Log.i(
                TAG,
                "honoring preferred TAK server: ${match.description} (${match.host}) " +
                    "[connected=${match.connected}]",
            )
            return match
        }
        Log.w(TAG, "preferred TAK server '$preferredHost' not configured in ATAK — auto-picking instead")
        return selectAuto(hosts)
    }

    /**
     * Substring pick: case-insensitive match on description OR host.
     * Empty/null pattern delegates to [selectAuto]. Pure function —
     * testable directly.
     */
    @VisibleForTesting
    internal fun selectByPatternOrAuto(
        hosts: List<TakHost>,
        pattern: String?,
    ): TakHost? {
        if (hosts.isEmpty()) {
            Log.w(TAG, "no TAK servers configured in ATAK")
            return null
        }
        if (!pattern.isNullOrBlank()) {
            val needle = pattern.lowercase()
            return hosts
                .firstOrNull {
                    it.description.lowercase().contains(needle) || it.host.lowercase().contains(needle)
                }?.also {
                    Log.i(TAG, "matched TAK server '$pattern' → ${it.description} (${it.host}) [connected=${it.connected}]")
                }
        }
        return selectAuto(hosts)
    }

    fun logAll() {
        val list = all()
        Log.i(TAG, "${list.size} TAK server(s) configured:")
        for (h in list) {
            Log.i(TAG, "  ${if (h.connected) "[UP]" else "[--]"} ${h.description} → ${h.host}:${h.takPort}")
        }
    }
}
