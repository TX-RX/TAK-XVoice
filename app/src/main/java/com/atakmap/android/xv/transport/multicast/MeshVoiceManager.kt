package com.atakmap.android.xv.transport.multicast

import java.security.MessageDigest

/**
 * The activation layer for mesh voice: owns the per-channel multicast
 * legs and every decision about what flows over them. One instance
 * per plugin session.
 *
 * Responsibilities:
 *   - **Leg lifecycle** — reconcile the set of live legs against the
 *     joined primary channel, the global mesh-voice toggle, and each
 *     channel's [ChannelMulticastConfig] (auto-derived FAILOVER default
 *     when no override is stored). A well-known rendezvous leg carries
 *     discovery beacons for peers with no server profile at all.
 *   - **TX routing** — [FailoverPolicy] decides Mumble vs multicast;
 *     ALWAYS-mode channels TX on both every burst. The Mumble leg is
 *     never gated here (a connected Mumble transport always gets the
 *     frame; a dead one drops it), so failover adds the mesh copy the
 *     instant the server disappears with zero coordination.
 *   - **RX merge + dedup** — both legs' frames funnel through here;
 *     [RxDeduper] keeps a speaker heard on two legs from playing twice.
 *     Speaker identity is canonicalized to the peer's device UID via
 *     CoT presence (Mumble session → uid) and FNV-1a SSRC derivation
 *     (multicast `ssrc:` key → uid).
 *   - **Key management** — per-channel [ChannelKeyRegistry] +
 *     [KeyElection], with [TakCertCryptoBox]-wrapped key delivery over
 *     the control plane. Pre-shared keys from an imported comms plan
 *     short-circuit the election for offline operation.
 *   - **Bridge election + relay** — [BridgeElection] picks the single
 *     server-connected client that relays voice between the Mumble
 *     channel and its mesh group whenever some peer lacks server
 *     access. Relay preserves speaker attribution mesh-ward
 *     (per-speaker SSRC); server-ward the bridge's own Mumble session
 *     carries the audio (Mumble has no speaker spoofing — inherent).
 *   - **Discovery** — periodic [ControlPacket.Message.PeerBeacon]s on
 *     every leg + the rendezvous group; received beacons feed the
 *     bridge election and the discovered-channels table.
 *
 * Pure-JVM by construction: all Android/ATAK touchpoints come in as
 * constructor lambdas, so the whole decision surface is unit-testable.
 * All public entry points are synchronized — call rates are modest
 * (50 Hz voice frames at worst) and the critical sections are short.
 *
 * Caller contract: invoke [tick] at ~1 Hz; deliver joined-channel
 * changes via [onChannelJoined]/[onChannelsCleared]; route Mumble RX
 * through [onMumbleRxFrame]; wire each leg's callbacks to
 * [onVoice]/[onControl] (the [MeshLegSink] this class implements).
 */
class MeshVoiceManager(
    private val ourUid: String,
    private val ourCallsign: () -> String? = { null },
    /** Global mesh-voice master toggle (XvSettings). */
    private val meshEnabled: () -> Boolean,
    /** Stored override or [ChannelMulticastConfig.defaultFor]. */
    private val configForChannel: (String) -> ChannelMulticastConfig,
    /** Canonical identity of the active server; null when offline / no profile. */
    private val serverIdentity: () -> ServerIdentity?,
    /** Live Mumble TCP state. */
    private val mumbleConnected: () -> Boolean,
    private val legFactory: MeshLegFactory,
    /** Forward one playable Opus frame to the service plant (AIDL onRxOpus). */
    private val onRxOpus: (opus: ByteArray, speakerLabel: String) -> Unit,
    /** Bridge relay: push one mesh-originated Opus frame onto the Mumble leg. */
    private val relayToMumble: (opus: ByteArray, burstStart: Boolean) -> Unit = { _, _ -> },
    /** Mesh TX became (un)available — service uses it to keep PTT live server-less. */
    private val onMeshTxStateChanged: (meshTxActive: Boolean) -> Unit = {},
    /** CoT presence: Mumble session → device uid. */
    private val deviceUidForMumbleSession: (Int) -> String? = { null },
    /** CoT presence: is this uid currently server-connected? */
    private val uidMumbleConnected: (String) -> Boolean = { false },
    /** CoT presence: display callsign for an XV/ATAK peer uid. */
    private val callsignForUid: (String) -> String? = { null },
    /** Mumble roster: display username for a live session id (uuid suffix stripped). */
    private val mumbleUsernameForSession: (Int) -> String? = { null },
    /** CoT presence: all currently-known XV peer uids (for SSRC mapping). */
    private val knownPeerUids: () -> Collection<String> = { emptyList() },
    /** CoT presence: peer cert SHA-256 fingerprint (lowercase hex), for cert exchange. */
    private val certFpForUid: (String) -> String? = { null },
    /** Our own enrollment cert (DER), for CertReply. Null when unenrolled. */
    private val ourCertDer: () -> ByteArray? = { null },
    /** Unwrap a KeyOffer addressed to us with our private key. Null on failure. */
    private val unwrapKey: (wrapped: ByteArray) -> ByteArray? = { null },
    /** Wrap a channel key to a recipient cert (DER). Null on failure. */
    private val wrapKeyFor: (recipientCertDer: ByteArray, key: ByteArray) -> ByteArray? = { _, _ -> null },
    /** Warn sink for rare operator-relevant anomalies. No-op by default (pure-JVM tests). */
    private val logWarn: (String) -> Unit = {},
    private val generateKey: () -> ByteArray = { AeadCodec.generateChannelKey() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val failoverPolicy: FailoverPolicy = FailoverPolicy(),
    private val deduper: RxDeduper = RxDeduper(),
    /**
     * Optional publisher that broadcasts bridge-election state over the
     * local mesh using ATAK's SA-multicast (never to TAK Server). Null in
     * tests that don't exercise CoT dispatch.
     */
    private val bridgeCotPublisher: com.atakmap.android.xv.presence.XvBridgeCotPublisher? = null,
) : MeshLegSink {
    // ---- leg + channel state ----

    private val legs = LinkedHashMap<String, MeshLeg>() // canonical channel → leg
    private val patchLegs = LinkedHashMap<String, MeshLeg>() // canonical channel → patch leg
    private var rendezvousLeg: MeshLeg? = null
    private var primaryChannel: String? = null // canonical

    private val registries = HashMap<String, ChannelKeyRegistry>()
    private val elections = HashMap<String, KeyElection>()
    private val keyBootstrapTicks = HashMap<String, Int>()

    // Peer cert directory (uid → DER) learned via CertReq/CertReply.
    private val peerCerts = HashMap<String, ByteArray>()

    // KeyReqs we couldn't answer yet because the requester's cert
    // isn't in the directory. Keyed by requester uid.
    private data class PendingOffer(
        val channel: String,
        val epoch: Int,
    )

    private val pendingOffers = HashMap<String, PendingOffer>()

    // ChannelKeyRegistry doesn't expose raw key bytes (deliberately);
    // the manager keeps its own copy of the current key per channel
    // strictly for wrapping KeyOffers to peers. Updated at every
    // install site (bootstrap, rotation, PSK import, accepted offer).
    private val currentKeys = HashMap<String, ByteArray>()

    // ---- bridge + discovery state ----

    private val bridgeElection = BridgeElection(ourUid)
    private var bridging = false

    /** One discovered channel as advertised by a peer beacon. */
    data class DiscoveredChannel(
        val name: String,
        val group: String,
        val port: Int,
        val viaUid: String,
        val viaCallsign: String,
        val lastSeenMs: Long,
    )

    private val discovered = HashMap<String, DiscoveredChannel>() // name → latest sighting

    // Per-channel beacon-advertised key epochs: channel → (uid → epoch
    // + seen-at). Drives the keyless-bootstrap tie-break so two devices
    // starting simultaneously don't both generate an epoch-0 key.
    private data class PeerEpoch(
        val epoch: Int,
        val seenMs: Long,
    )

    private val channelPeerEpochs = HashMap<String, HashMap<String, PeerEpoch>>()

    // ---- RX/TX bookkeeping ----

    private var meshTxActive = false
    private var lastBeaconAtMs = Long.MIN_VALUE
    private var lastUnresolvedRelayWarnMs = Long.MIN_VALUE

    // Last time we observed the server actually delivering (a voice frame
    // or a ping-ack liveness feed) — NOT just "socket connected". The
    // failover RX gate keys on this so a connected-but-silent server can't
    // keep muting mesh RX; see decideVoiceRx.
    private var lastMumbleActivityMs = Long.MIN_VALUE

    // Memoized fingerprint of OUR current per-channel key so the per-beacon
    // (and beacon-driven) fingerprinting doesn't re-run SHA-256 every call.
    // Keyed by channel; the identity check invalidates on key rotation,
    // which installs a fresh ByteArray into currentKeys.
    private val currentKeyFpCache = HashMap<String, Pair<ByteArray, Int>>()

    private fun ourKeyFingerprint(
        channel: String,
        key: ByteArray,
    ): Int {
        val hit = currentKeyFpCache[channel]
        if (hit != null && hit.first === key) return hit.second
        val fp = keyFingerprint(key)
        currentKeyFpCache[channel] = key to fp
        return fp
    }

    // Bounded, access-ordered so the hottest speakers survive: every
    // Mumble RX frame and every peer-refresh stamps an entry here, and on
    // a long mission with peer churn a plain HashMap grows without bound
    // (synthetic "mumble:<session>" keys are never covered by a uid-based
    // departure, so departure-eviction alone can't bound it). The LRU cap
    // keeps it to any realistic team size regardless of churn. The third
    // ctor arg (accessOrder=true) makes a get() count as use, so the
    // hottest speakers are the last evicted.
    private val ssrcKeyToUid =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_SSRC_ENTRIES
        }

    // Burst-gap tracking for the two relay directions. Server→mesh keys
    // on the canonical speaker (session-derived, stable). Mesh→server
    // keys on the WIRE ssrc — the canonical can flip mid-burst when
    // refreshSsrcMap learns a uid mapping on a tick.
    private val relayLastFrameMs = HashMap<String, Long>() // canonical → last server→mesh relay

    private val relayToServerLastMs = HashMap<String, Long>() // ssrc speakerKey → last mesh→server relay

    // ---- speaker attribution ----

    // uid → callsign learned from peer beacons; covers mesh-only XV
    // peers whose CoT presence hasn't reached us (or never will,
    // fully offline).
    private val beaconCallsigns = HashMap<String, String>()

    // speakerKey → display name learned from SpeakerName control
    // packets — a bridge announcing who its relayed frames belong to
    // (non-XV Mumble clients have no presence and no beacon).
    private val announcedSpeakerNames = HashMap<String, String>()

    // canonical speaker → (speakerKey, last mesh frame we PLAYED).
    // Backs meshActiveSpeakers() for the channel-row talker display.
    private data class MeshTalker(
        val speakerKey: String,
        var lastFrameMs: Long,
    )

    private val meshTalking = HashMap<String, MeshTalker>()

    // ---- channel membership (from the plugin's Mumble events) ----

    @Synchronized
    fun onChannelJoined(
        slot: Int,
        channelName: String,
    ) {
        if (slot != 0) return // mesh legs bind to the primary channel only
        val canonical = MulticastGroupDerivation.canonicalChannelName(channelName)
        if (canonical == primaryChannel) return
        primaryChannel = canonical
        deduper.reset()
        reconcileLegs()
    }

    @Synchronized
    fun onChannelsCleared() {
        // Mumble teardown deliberately does NOT clear [primaryChannel]
        // or the legs — a leg outliving the server is the entire
        // failover story. The leg goes away only when mesh is disabled,
        // the channel is configured OFF, or the operator joins a
        // different channel once the server returns.
        reconcileLegs()
    }

    /**
     * Operator forgot a single channel: tear down its leg, drop any
     * discovered sighting + cached key, and clear it as primary. A peer
     * actively beaconing the channel can re-surface it in discovery — you
     * can't unilaterally forget a channel someone else is still using —
     * but the leg and stored state are gone.
     */
    @Synchronized
    fun forgetChannel(channelName: String) {
        val canonical = MulticastGroupDerivation.canonicalChannelName(channelName)
        legs.remove(canonical)?.let { runCatching { it.close() } }
        discovered.keys.removeAll { MulticastGroupDerivation.canonicalChannelName(it) == canonical }
        currentKeys.remove(canonical)
        currentKeyFpCache.remove(canonical)
        if (primaryChannel == canonical) primaryChannel = null
    }

    /** Operator cleared the whole channel list: drop every leg + discovery. */
    @Synchronized
    fun forgetAllChannels() {
        legs.values.forEach { runCatching { it.close() } }
        legs.clear()
        discovered.clear()
        currentKeys.clear()
        currentKeyFpCache.clear()
        primaryChannel = null
    }

    /** Peer left (Mumble UserRemove / CoT stale-purge). Feeds key rotation. */
    @Synchronized
    fun onPeerDeparted(uid: String) {
        bridgeElection.observePeerDeparted(uid)
        // Evict this peer's long-lived state so a multi-hour mission with
        // 40-peer churn doesn't accumulate stale entries: peerCerts holds
        // 1-2 KB of DER each, pendingOffers dangles if the peer left before
        // its cert arrived, and the SSRC map keeps a dead mapping. (The
        // SSRC map also self-bounds via LRU for the synthetic keys.)
        peerCerts.remove(uid)
        pendingOffers.remove(uid)
        ssrcKeyToUid.remove("ssrc:%08x".format(RtpFraming.fnv1aSsrc(uid)))
        legs.forEach { (channel, leg) ->
            val election = elections[channel] ?: return@forEach
            if (election.observePeerDeparted(uid)) {
                when (val action = election.onKeyHolderDeparted()) {
                    is KeyElection.Action.RotateKey -> rotateKey(channel, leg, action.nextEpoch)
                    else -> {}
                }
            }
        }
    }

    // ---- TX path (called from the plugin's AIDL TX callbacks) ----

    /** PTT-down edge: reset burst state on every mesh leg. */
    @Synchronized
    fun beginTxBurst() {
        legs.values.forEach { it.beginVoiceBurst() }
    }

    /**
     * One of OUR Opus frames is going on the air. The Mumble copy is
     * the caller's job (unconditional); this fans out the mesh copies
     * per channel mode + failover state.
     */
    @Synchronized
    fun sendTxOpus(
        opus: ByteArray,
        targetSlot: Int,
    ) {
        if (targetSlot != 0) return
        if (legs.isEmpty()) return
        failoverPolicy.observeTxFrame(nowMs())
        legs.values.forEach { leg ->
            val txNow =
                when (leg.config.mode) {
                    MulticastMode.ALWAYS -> true
                    // While we hold the bridge role our OWN mic must
                    // also go out on the mesh: the server never echoes
                    // our voice back, so the Mumble→mesh relay path
                    // structurally cannot carry it — without this the
                    // bridge operator is inaudible to every mesh-only
                    // peer. Doubly-connected receivers get two copies
                    // (server + mesh); the dedup layer collapses them
                    // via ssrc↔uid correlation like any relayed burst.
                    MulticastMode.FAILOVER -> meshTxActive || bridging
                    MulticastMode.OFF -> false
                }
            if (txNow) leg.sendOpus(opus)
        }
        patchLegs.values.forEach { leg ->
            val txNow =
                when (leg.config.mode) {
                    MulticastMode.ALWAYS -> true
                    MulticastMode.FAILOVER -> meshTxActive || bridging
                    MulticastMode.OFF -> false
                }
            if (txNow) leg.sendOpus(opus)
        }
    }

    /** Is mesh currently the active TX leg (failover engaged)? */
    @Synchronized
    fun isMeshTxActive(): Boolean = meshTxActive

    // ---- Mumble RX (called from the plugin's onIncomingOpus hook) ----

    /**
     * One Opus frame arrived from the Mumble leg. Returns true when
     * the frame should be forwarded to playback, false when it's a
     * cross-leg duplicate of something the mesh leg already delivered.
     * Also drives Mumble health for failback, and the server→mesh
     * bridge relay when we hold the bridge role.
     */
    @Synchronized
    fun onMumbleRxFrame(
        slot: Int,
        speakerSession: Int,
        opus: ByteArray,
    ): Boolean {
        val now = nowMs()
        failoverPolicy.observeMumbleRx(now)
        lastMumbleActivityMs = now
        if (slot != 0) return true // VS2 has no mesh leg to collide with
        val canonical = deviceUidForMumbleSession(speakerSession) ?: "mumble:$speakerSession"
        // Bind this speaker's SSRC to their canonical id for every
        // Mumble speaker, INCLUDING non-XV clients whose synthetic
        // "mumble:<session>" uid never appears in CoT presence (so
        // refreshSsrcMap can't map them). Without this a doubly-
        // connected receiver can't correlate a bridge's mesh relay of
        // this speaker with the server copy, and the dedup layer plays
        // both — the "ghost packet" double audio.
        ssrcKeyToUid["ssrc:%08x".format(RtpFraming.fnv1aSsrc(canonical))] = canonical
        val play = deduper.shouldPlay(legId = "mumble", speaker = canonical, nowMs = now)
        if (play && bridging) {
            relayServerFrameToMesh(canonical, opus, now)
        }
        return play
    }

    /** Ping acks etc. — Mumble RX health without a voice frame. */
    @Synchronized
    fun observeMumbleHealth() {
        val now = nowMs()
        failoverPolicy.observeMumbleRx(now)
        lastMumbleActivityMs = now
    }

    // ---- mesh leg RX (MeshLegSink) ----

    // NOT synchronized: the playback IPC (AIDL onRxOpus → service
    // AudioTrack.write, which paces at real time) and the Mumble relay
    // (TCP write) MUST NOT run under the manager lock. At 50 frames/s
    // a lock held ~20 ms per frame saturates the (unfair) monitor and
    // starves the main-thread 1 Hz tick indefinitely — field repro
    // 2026-07-15 14:43: sustained mesh RX froze ATAK's main thread,
    // input dispatch timed out (ANR), and the PTT release was never
    // delivered ("stuck transmitting"). Decisions happen under the
    // lock in [decideVoiceRx]; side effects happen out here.
    override fun onVoice(
        channelName: String,
        opus: ByteArray,
        speakerKey: String,
        seqInBurst: Int?,
        sourceHost: String,
        isPatchLeg: Boolean,
    ) {
        val action = decideVoiceRx(channelName, speakerKey, sourceHost, isPatchLeg)
        if (!action.play) return
        onRxOpus(opus, "mcast:$channelName:$speakerKey")
        if (action.relay) {
            relayToMumble(opus, action.relayBurstStart)
            if (!isPatchLeg) {
                patchLegs[channelName]?.sendRelayOpus(action.canonical, opus, action.relayBurstStart)
            } else {
                legs[channelName]?.sendRelayOpus(action.canonical, opus, action.relayBurstStart)
            }
        }
    }

    private data class VoiceRxAction(
        val play: Boolean,
        val relay: Boolean,
        val relayBurstStart: Boolean,
        val canonical: String = "",
        val serverOriginated: Boolean = false,
    )

    @Synchronized
    private fun decideVoiceRx(
        channelName: String,
        speakerKey: String,
        sourceHost: String,
        isPatchLeg: Boolean,
    ): VoiceRxAction {
        if (channelName == RENDEZVOUS_CHANNEL) {
            return VoiceRxAction(play = false, relay = false, relayBurstStart = false)
        }
        val now = nowMs()
        // Failover playback gate. On a FAILOVER channel with a healthy
        // server, a non-bridge client already gets everything via Mumble
        // (server clients directly; serverless peers via the bridge's relay
        // onto Mumble). Playing the multicast copy TOO is the double audio
        // operators hear — RxDeduper can't always collapse it, because a
        // relayed frame whose SSRC doesn't resolve to the same canonical id
        // as its Mumble copy (unresolved "ssrc:", presence asymmetry,
        // OPENMANET "ip:" attribution) reads as a distinct speaker. Drop it
        // here, BEFORE the deduper, so the deduper's per-speaker state isn't
        // polluted for a frame we won't play (which would then suppress the
        // real Mumble copy). The bridge is exempt: it must play serverless
        // peers to hear them, and relays them onward. Once the server is
        // gone (meshTxActive) nobody is gated — that is failover working.
        //
        // The gate keys on STABLE LIVENESS, not raw socket state: a
        // connected-but-silent server (socket up, not delivering) must NOT
        // keep muting mesh RX, or a serverless peer goes unheard. We require
        // recent observed server activity (voice or ping-ack, fed ~1 Hz);
        // the grace is comfortably longer than the ~5 s ping interval, so a
        // healthy-but-idle server stays gated with no flap while a truly
        // silent one lifts the gate after the grace.
        val mode = legs[channelName]?.config?.mode
        val serverLive = mumbleConnected() && lastMumbleActivityMs != Long.MIN_VALUE && now - lastMumbleActivityMs <= MUMBLE_LIVE_GRACE_MS
        if (mode == MulticastMode.FAILOVER && serverLive && !meshTxActive && !bridging) {
            return VoiceRxAction(play = false, relay = false, relayBurstStart = false)
        }
        val canonical = ssrcKeyToUid[speakerKey] ?: speakerKey
        // The dedup leg id includes the datagram's SOURCE: during a
        // bridge handoff two bridges relay the SAME server speaker
        // (same SSRC, independent sequence bases) for a few seconds,
        // and without source granularity both copies share one leg id
        // and both play — interleaved streams the operators heard as
        // "packet collisions" (field repro 2026-07-16 ~23:15). With it,
        // the first source to deliver owns the speaker for the burst;
        // the second bridge's copy drops until the owner goes silent.
        if (!deduper.shouldPlay(legId = "mesh:$channelName:$sourceHost", speaker = canonical, nowMs = now)) {
            return VoiceRxAction(play = false, relay = false, relayBurstStart = false)
        }
        // Relay eligibility: never bounce server-originated audio back
        // onto the server. A "mumble:<session>" canonical id exists
        // ONLY because the speaker was heard on a live Mumble session
        // — such frames on the mesh are some bridge's relay (ours are
        // already dropped leg-side; another bridge's can slip through
        // during a handoff overlap).
        val serverOriginated = canonical.startsWith("mumble:")
        val relay = bridging && canonical != ourUid && !serverOriginated && !uidMumbleConnected(canonical)
        var relayBurstStart = false
        if (relay) {
            // A speaker key still in raw "ssrc:" form means we never
            // resolved it to a uid. That's LEGITIMATE for a fully-offline
            // mesh-only peer we bridge (no CoT presence for them), so we do
            // NOT gate the relay on it — but it can also be a misconfigured
            // or rogue sender, so surface a rate-limited warning for the
            // operator/logs rather than relaying silently.
            if (canonical.startsWith("ssrc:") &&
                // Explicit sentinel check — (now - MIN_VALUE) overflows.
                (lastUnresolvedRelayWarnMs == Long.MIN_VALUE || now - lastUnresolvedRelayWarnMs > UNRESOLVED_RELAY_WARN_THROTTLE_MS)
            ) {
                lastUnresolvedRelayWarnMs = now
                logWarn(
                    "bridging an unresolved mesh speaker ($canonical) onto Mumble on '$channelName' — " +
                        "no presence for this SSRC (offline mesh-only peer, or a misconfigured/rogue sender)",
                )
            }
            // Burst tracking keys on the WIRE identity (ssrc), not the
            // canonical: refreshSsrcMap can learn the uid mapping on a
            // tick mid-burst, flipping the canonical and minting a
            // spurious second burst-start — which resets the Mumble TX
            // sequence mid-stream (field repro 2026-07-16: doubled
            // beginMumbleVoiceBurst ~15 ms apart on every relay).
            val last = relayToServerLastMs[speakerKey]
            relayBurstStart = last == null || now - last > RELAY_BURST_GAP_MS
            relayToServerLastMs[speakerKey] = now
        }
        meshTalking.getOrPut(canonical) { MeshTalker(speakerKey, now) }.lastFrameMs = now
        return VoiceRxAction(
            play = true,
            relay = relay,
            relayBurstStart = relayBurstStart,
            canonical = canonical,
            serverOriginated = serverOriginated
        )
    }

    /**
     * Human display names of speakers whose mesh audio we played
     * within the talking TTL. Resolution order: CoT presence callsign
     * (ATAK users) → beacon callsign (mesh-only XV peers) → Mumble
     * roster username (server clients heard via a bridge, resolvable
     * when we're connected ourselves) → bridge-announced SpeakerName →
     * the raw canonical id as last resort. Merged into the channel
     * row's talker list alongside the Mumble roster names.
     */
    @Synchronized
    fun meshActiveSpeakers(): List<String> {
        val now = nowMs()
        meshTalking.entries.removeAll { now - it.value.lastFrameMs > TALKING_TTL_MS }
        return meshTalking.map { (canonical, talker) -> displayNameFor(canonical, talker.speakerKey) }
    }

    private fun displayNameFor(
        canonical: String,
        speakerKey: String,
    ): String {
        callsignForUid(canonical)?.takeIf { it.isNotBlank() }?.let { return it }
        beaconCallsigns[canonical]?.let { return it }
        if (canonical.startsWith("mumble:")) {
            canonical
                .removePrefix("mumble:")
                .toIntOrNull()
                ?.let(mumbleUsernameForSession)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        announcedSpeakerNames[speakerKey]?.let { return it }
        return canonical
    }

    @Synchronized
    override fun onControl(
        channelName: String,
        msg: ControlPacket.Message,
        sourceHost: String,
        isPatchLeg: Boolean,
    ) {
        when (msg) {
            is ControlPacket.Message.PeerBeacon -> handleBeacon(msg)
            is ControlPacket.Message.KeyReq -> handleKeyReq(channelName, msg)
            is ControlPacket.Message.KeyOffer -> handleKeyOffer(channelName, msg)
            is ControlPacket.Message.CertReq -> handleCertReq(channelName, msg)
            is ControlPacket.Message.CertReply -> handleCertReply(msg)
            is ControlPacket.Message.SpeakerName -> {
                // Bounded: one entry per distinct relayed speaker;
                // deployments have tens, not thousands. Reset rather
                // than evict on (never-expected) overflow.
                if (announcedSpeakerNames.size >= MAX_ANNOUNCED_NAMES) announcedSpeakerNames.clear()
                announcedSpeakerNames[msg.speakerKey] = msg.name
            }
        }
    }

    // ---- periodic tick (~1 Hz from the plugin's scheduler) ----

    @Synchronized
    fun tick() {
        val now = nowMs()
        reconcileLegs()
        refreshSsrcMap()

        // Failover evaluation. The policy's "active leg" drives
        // FAILOVER-mode TX routing and the operator badge.
        val decision = failoverPolicy.evaluate(now, mumbleConnected())
        val newMeshTx = legs.isNotEmpty() && decision.active == FailoverPolicy.Leg.MULTICAST
        if (newMeshTx != meshTxActive) {
            meshTxActive = newMeshTx
            onMeshTxStateChanged(newMeshTx)
        }

        // Key elections.
        legs.forEach { (channel, leg) -> tickKeyElection(channel, leg) }

        // Bridge election (primary channel scope). CoT presence feeds
        // arrive via observePeerConnectivity; beacons via handleBeacon.
        val wasBridging = bridging
        bridging = legs.isNotEmpty() && bridgeElection.evaluate(now, mumbleConnected())
        if (bridging != wasBridging) {
            // Notify local-mesh peers of our bridge role change via CoT.
            bridgeCotPublisher?.setBridging(bridging)
            bridgeCotPublisher?.setMumbleSession(if (mumbleConnected()) 0 else null)
        }
        if (wasBridging && !bridging) {
            // Lost the bridge role: shed all per-speaker relay state so a
            // later re-acquire starts clean and codecs/timestamps for
            // speakers we'll never relay again don't linger.
            legs.values.forEach { runCatching { it.clearRelayState() } }
            rendezvousLeg?.let { runCatching { it.clearRelayState() } }
            relayLastFrameMs.clear()
            relayToServerLastMs.clear()
        }

        // Discovery + election beacons.
        if (lastBeaconAtMs == Long.MIN_VALUE || now - lastBeaconAtMs >= BEACON_INTERVAL_MS) {
            lastBeaconAtMs = now
            broadcastBeacons()
        }

        deduper.prune(now)
        pruneDiscovered(now)
        // Idle-prune the relay burst-gap maps: a speaker unheard for the
        // TTL can drop its bookkeeping regardless of bridge-role changes
        // (belt-and-suspenders for a very long single bridge session).
        relayLastFrameMs.entries.removeAll { now - it.value > RELAY_IDLE_TTL_MS }
        relayToServerLastMs.entries.removeAll { now - it.value > RELAY_IDLE_TTL_MS }
    }

    /**
     * Direct election-observation seam (tests; beacon-equivalent
     * injections). Production feeds the election only from mesh
     * beacons — see the note above [tick] on why CoT must not.
     */
    @Synchronized
    fun observePeerConnectivity(
        uid: String,
        mumbleConnected: Boolean,
    ) {
        bridgeElection.observePeer(uid, mumbleConnected, nowMs())
    }

    // ---- comms plan + PSK ----

    /**
     * Install a pre-shared channel key (comms plan import). Lands at
     * epoch 0; if the live mesh has already rotated the same key bytes
     * to a higher epoch label, the next peer beacon converges us via
     * the fingerprint-match adoption rule in [handleBeacon].
     */
    @Synchronized
    fun installPresharedKey(
        channelName: String,
        key: ByteArray,
    ) {
        val canonical = MulticastGroupDerivation.canonicalChannelName(channelName)
        if (registryFor(canonical).install(PSK_EPOCH, key)) {
            currentKeys[canonical] = key
        }
    }

    /**
     * Live key bytes for a channel (defensive copy), or null when
     * keying hasn't converged. Comms-plan EXPORT must use this, not the
     * provisioning-time snapshot: the election may have rotated the key
     * since the channel was created, and stale bytes import cleanly on
     * the receiver and then decrypt nothing.
     */
    @Synchronized
    fun currentKeyFor(channelName: String): ByteArray? = currentKeys[MulticastGroupDerivation.canonicalChannelName(channelName)]?.copyOf()

    /**
     * Every live channel key (canonical name → defensive copy) — the
     * key-at-rest seal must use this, not just the provisioned set: a
     * key obtained via ELECTION on a server-derived channel never
     * appears in the provisioned map, was therefore never sealed, and
     * the device came up keyless (deaf) after every restart — field
     * repro 2026-07-16, confirmed by the vault instrumentation ("no
     * sealed vault" on a device that had been keyed all afternoon).
     */
    @Synchronized
    fun allCurrentKeys(): Map<String, ByteArray> = currentKeys.mapValues { (_, k) -> k.copyOf() }

    // ---- operator-facing state ----

    /** True while this client holds the bridge relay role. */
    @Synchronized
    fun isBridging(): Boolean = bridging

    /** Live legs: canonical channel → endpoint (diagnostics + UI). */
    @Synchronized
    fun activeLegs(): Map<String, MulticastEndpoint> = legs.mapValues { it.value.endpoint }

    /** Per-leg diagnostics lines (rendezvous leg included) for MESH_STATUS. */
    @Synchronized
    fun legStats(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        legs.forEach { (channel, leg) -> out[channel] = leg.stats() }
        rendezvousLeg?.let { out[RENDEZVOUS_CHANNEL] = it.stats() }
        return out
    }

    /** Network link changed: rebind every leg's socket to the fresh interface. */
    @Synchronized
    fun notifyNetworkSwap() {
        legs.values.forEach { runCatching { it.notifyNetworkSwap() } }
        rendezvousLeg?.let { runCatching { it.notifyNetworkSwap() } }
    }

    /** Channels other peers advertise that we haven't joined (offline discovery). */
    @Synchronized
    fun discoveredChannels(): List<DiscoveredChannel> =
        discovered.values
            .filter { it.name !in legs.keys }
            .sortedBy { it.name }

    /**
     * Structured mesh state for the operator UI, or null when mesh
     * voice is dormant (no legs). The UI renders [cleartext] as a
     * distinct warning so an operator can never transmit unencrypted
     * over the mesh without knowing.
     *
     * @property active mesh is the live TX leg right now (Mumble down /
     *   failover engaged), vs. merely joined and standing by.
     * @property bridging this device is relaying between the server and
     *   the mesh for server-less peers.
     * @property cleartext at least one live leg is sending unencrypted
     *   (crypto policy CLEARTEXT, or keying hasn't converged on a
     *   PREFERRED channel).
     * @property keyNeeded at least one leg is receiving peers'
     *   ENCRYPTED traffic while holding no key — this device is deaf
     *   on that channel until the operator imports a plan or
     *   re-enrolls. Distinct from [cleartext]: CLEAR says "we send
     *   unencrypted", KEY NEEDED says "we can't hear the others".
     */
    data class StatusSnapshot(
        val active: Boolean,
        val bridging: Boolean,
        val cleartext: Boolean,
        val keyNeeded: Boolean,
        val legCount: Int,
        val patched: Boolean = false,
    )

    @Synchronized
    fun statusSnapshot(): StatusSnapshot? {
        if (legs.isEmpty() && patchLegs.isEmpty()) return null
        return StatusSnapshot(
            active = meshTxActive,
            bridging = bridging,
            cleartext = legs.values.any { !it.encryptedNow } || patchLegs.values.any { !it.encryptedNow },
            keyNeeded = legs.values.any { it.awaitingKey } || patchLegs.values.any { it.awaitingKey },
            legCount = legs.size + patchLegs.size,
            patched = patchLegs.isNotEmpty(),
        )
    }

    /**
     * Short status badge for diagnostics (MESH_STATUS debug dump), or
     * null when mesh voice is dormant. Examples: "MESH READY",
     * "MESH ACTIVE · CLEAR", "MESH READY · BRIDGING". The operator UI
     * uses [statusSnapshot] instead so it can colour the CLEAR state.
     */
    @Synchronized
    fun statusBadge(): String? {
        val snap = statusSnapshot() ?: return null
        return buildString {
            append(if (snap.active) "MESH ACTIVE" else "MESH READY")
            if (snap.bridging) append(" · BRIDGING")
            if (snap.patched) append(" · PATCHED")
            if (snap.cleartext) append(" · CLEAR")
            if (snap.keyNeeded) append(" · KEY NEEDED")
        }
    }

    /** Tear everything down (plugin unload). */
    @Synchronized
    fun shutdown() {
        legs.values.forEach { runCatching { it.close() } }
        legs.clear()
        patchLegs.values.forEach { runCatching { it.close() } }
        patchLegs.clear()
        rendezvousLeg?.let { runCatching { it.close() } }
        rendezvousLeg = null
        deduper.reset()
        bridgeElection.reset()
        bridgeCotPublisher?.stop()
        relayLastFrameMs.clear()
        relayToServerLastMs.clear()
        if (meshTxActive) {
            meshTxActive = false
            onMeshTxStateChanged(false)
        }
    }

    // ---- internals ----

    private fun reconcileLegs() {
        val enabled = meshEnabled()
        val desired = mutableMapOf<String, ChannelMulticastConfig>()
        if (enabled) {
            primaryChannel?.let { channel ->
                val cfg = configForChannel(channel)
                if (cfg.mode != MulticastMode.OFF && cfg.validate() == null) {
                    desired[channel] = cfg
                }
            }
        }

        // Tear down legs no longer wanted — including legs whose
        // RESOLVED endpoint no longer matches what they're bound to:
        // switching servers while staying on the same channel name
        // re-derives to a different group, and a leg keyed only by
        // channel name would otherwise silently stay on the old
        // server's multicast group forever.
        val stale =
            legs.filter { (channel, leg) ->
                val cfg = desired[channel] ?: return@filter true
                val endpoint = resolveEndpoint(cfg)
                endpoint != null && endpoint != leg.endpoint
            }.keys
        stale.forEach { channel ->
            legs.remove(channel)?.let { runCatching { it.close() } }
            patchLegs.remove(channel)?.let { runCatching { it.close() } }
        }

        val stalePatch = patchLegs.filter { (channel, patchLeg) ->
            val cfg = desired[channel] ?: return@filter true
            if (cfg.patchGroup == null || cfg.patchPort == null) return@filter true
            val endpoint = MulticastEndpoint(cfg.patchGroup, cfg.patchPort)
            endpoint != patchLeg.endpoint
        }.keys
        stalePatch.forEach { channel ->
            patchLegs.remove(channel)?.let { runCatching { it.close() } }
        }

        // Start newly-wanted legs. A derived endpoint needs a server
        // identity; a pinned one doesn't (offline comms-plan channels).
        desired.forEach { (channel, cfg) ->
            if (channel !in legs) {
                val endpoint = resolveEndpoint(cfg)
                if (endpoint != null) {
                    val leg = runCatching { legFactory.create(cfg, endpoint, registryFor(channel), this) }.getOrNull()
                    if (leg != null) {
                        legs[channel] = leg
                        elections.getOrPut(channel) {
                            KeyElection(ourUid, stableChannelId(channel), registryFor(channel))
                        }
                        keyBootstrapTicks[channel] = 0
                    }
                }
            }
            if (cfg.patchGroup != null && cfg.patchPort != null && channel !in patchLegs) {
                val patchEndpoint = MulticastEndpoint(cfg.patchGroup, cfg.patchPort)
                val patchCfg = ChannelMulticastConfig(
                    channelName = channel,
                    mode = cfg.mode,
                    wireFormat = cfg.patchWireFormat,
                    cryptoPolicy = cfg.patchCryptoPolicy,
                    pinnedGroup = cfg.patchGroup,
                    pinnedPort = cfg.patchPort,
                )
                val patchSink = object : MeshLegSink {
                    override fun onVoice(
                        channelName: String,
                        opus: ByteArray,
                        speakerKey: String,
                        seqInBurst: Int?,
                        sourceHost: String,
                        isPatchLeg: Boolean
                    ) {
                        this@MeshVoiceManager.onVoice(channelName, opus, speakerKey, seqInBurst, sourceHost, true)
                    }
                    override fun onControl(channelName: String, msg: ControlPacket.Message, sourceHost: String, isPatchLeg: Boolean) {
                        this@MeshVoiceManager.onControl(channelName, msg, sourceHost, true)
                    }
                }
                val patchLeg = runCatching { legFactory.create(patchCfg, patchEndpoint, registryFor(channel), patchSink) }.getOrNull()
                if (patchLeg != null) {
                    patchLegs[channel] = patchLeg
                }
            }
        }

        // Rendezvous leg: up whenever mesh voice is on, so discovery
        // beacons flow even before (or without) any channel leg.
        if (enabled && rendezvousLeg == null) {
            rendezvousLeg =
                runCatching {
                    legFactory.create(RENDEZVOUS_CONFIG, RENDEZVOUS_ENDPOINT, registryFor(RENDEZVOUS_CHANNEL), this)
                }.getOrNull()
        } else if (!enabled && rendezvousLeg != null) {
            rendezvousLeg?.let { runCatching { it.close() } }
            rendezvousLeg = null
        }
    }

    private fun resolveEndpoint(cfg: ChannelMulticastConfig): MulticastEndpoint? {
        if (cfg.pinnedGroup != null && cfg.pinnedPort != null) {
            return MulticastEndpoint(cfg.pinnedGroup, cfg.pinnedPort)
        }
        val identity = serverIdentity() ?: return null
        return MulticastGroupDerivation.derive(identity, cfg.channelName)
    }

    private fun registryFor(channel: String): ChannelKeyRegistry = registries.getOrPut(channel) {
        ChannelKeyRegistry(stableChannelId(channel))
    }

    private fun tickKeyElection(
        channel: String,
        leg: MeshLeg,
    ) {
        if (leg.config.cryptoPolicy == CryptoPolicy.CLEARTEXT) return
        val registry = registryFor(channel)
        val election = elections[channel] ?: return
        when (val action = election.tick()) {
            is KeyElection.Action.RequestKey -> {
                leg.safeSendControl(
                    ControlPacket.Message.KeyReq(
                        channelId = stableChannelId(channel),
                        forEpoch = action.forEpoch,
                        requesterUid = ourUid,
                    ),
                )
            }
            is KeyElection.Action.RotateKey -> rotateKey(channel, leg, action.nextEpoch)
            KeyElection.Action.Idle -> {
                // Bootstrap: nobody on the group is advertising a key
                // and we've waited a couple of ticks for offers —
                // generate the channel key ourselves so encrypted TX
                // can start. First-mover races resolve via the
                // election (higher epoch wins; identical epochs from a
                // simultaneous start converge on the next rotation).
                if (!registry.hasKey()) {
                    val ticksWaited = (keyBootstrapTicks[channel] ?: 0) + 1
                    keyBootstrapTicks[channel] = ticksWaited
                    if (ticksWaited > KEY_BOOTSTRAP_WAIT_TICKS && mayBootstrapKey(channel)) {
                        val fresh = generateKey()
                        registry.install(0, fresh)
                        currentKeys[channel] = fresh
                    }
                }
            }
        }
    }

    private fun rotateKey(
        channel: String,
        leg: MeshLeg,
        nextEpoch: Int,
    ) {
        val registry = registryFor(channel)
        val fresh = generateKey()
        registry.install(nextEpoch, fresh)
        currentKeys[channel] = fresh
        // Offer the new key to every peer whose cert we hold.
        peerCerts.forEach { (uid, certDer) ->
            val wrapped = wrapKeyFor(certDer, fresh) ?: return@forEach
            leg.safeSendControl(
                ControlPacket.Message.KeyOffer(
                    channelId = stableChannelId(channel),
                    epoch = nextEpoch,
                    recipientUid = uid,
                    wrappedKey = wrapped,
                ),
            )
        }
    }

    private fun handleBeacon(msg: ControlPacket.Message.PeerBeacon) {
        if (msg.uid == ourUid) return
        val now = nowMs()
        // Bridge election is now fed exclusively by CoT via observePeerConnectivity (Task 3)
        // Callsign directory for talker attribution — covers mesh-only
        // peers whose CoT presence hasn't reached us. Beacons default
        // the callsign to the uid; only store real display names.
        if (msg.callsign.isNotBlank() && msg.callsign != msg.uid) {
            beaconCallsigns[msg.uid] = msg.callsign
        }
        msg.channels.forEach { ch ->
            discovered[ch.name] =
                DiscoveredChannel(
                    name = ch.name,
                    group = ch.group,
                    port = ch.port,
                    viaUid = msg.uid,
                    viaCallsign = msg.callsign,
                    lastSeenMs = now,
                )
            // Epoch advertisement feeds the key election on channels we
            // carry: a peer with a key becomes someone we can KeyReq;
            // a keyless peer becomes a bootstrap-tie-break contender.
            if (ch.name in legs.keys) {
                channelPeerEpochs
                    .getOrPut(ch.name) { HashMap() }[msg.uid] = PeerEpoch(ch.keyEpoch, now)
                elections[ch.name]?.observePeerEpoch(
                    peerUid = msg.uid,
                    peerEpoch = ch.keyEpoch,
                    hadCurrentKey = ch.keyEpoch != ChannelKeyRegistry.NO_EPOCH &&
                        ch.keyEpoch == registryFor(ch.name).currentEpoch(),
                )
                resolveKeySplitBrain(ch, msg.uid, now)
                adoptAdvertisedEpoch(ch)
            }
        }
    }

    // Same key bytes under a LOWER epoch label than a peer advertises
    // (fingerprints match, epochs don't): adopt the peer's label. This
    // is how a comms-plan import converges — the plan carries the live
    // key but installs at epoch 0, while the mesh may have rotated that
    // same key to a higher epoch; without adoption the importer
    // encrypts under a label nobody else recognizes and both directions
    // drop as UnknownEpoch. One-directional (higher epoch wins, same
    // convention as the election) so two importers can't flap, and
    // fingerprint-gated so a genuinely different key still goes through
    // the split-brain / KeyReq paths instead. The registry rolls our
    // old label into its previous-epoch slot, so frames from a
    // not-yet-adopted importer stay decryptable for the grace window.
    private fun adoptAdvertisedEpoch(ch: ControlPacket.Message.PeerBeacon.Channel) {
        val registry = registryFor(ch.name)
        val ourEpoch = registry.currentEpoch()
        if (ourEpoch == ChannelKeyRegistry.NO_EPOCH || ch.keyEpoch <= ourEpoch) return
        val key = currentKeys[ch.name] ?: return
        if (ch.keyFp == 0 || ch.keyFp != ourKeyFingerprint(ch.name, key)) return
        registry.install(ch.keyEpoch, key)
    }

    // Same-epoch/different-key split-brain (simultaneous bootstrap or
    // partition merge): epoch numbers match, key bytes don't, so both
    // sides think they're in sync while dropping 100% of each other's
    // voice as BAD_TAG (observed on-device 2026-07-15 after restarting
    // both devices together). Resolution is deterministic and one-
    // sided: the LOWEST uid among the conflicted holders rotates
    // forward to a fresh epoch; everyone else sees the higher epoch in
    // its next beacon and converges through the normal KeyReq path —
    // no loser-side special case, no registry overwrite semantics.
    private val lastConflictRotateMs = HashMap<String, Long>()

    private fun resolveKeySplitBrain(
        ch: ControlPacket.Message.PeerBeacon.Channel,
        peerUid: String,
        now: Long,
    ) {
        val ourEpoch = registryFor(ch.name).currentEpoch()
        if (ourEpoch == ChannelKeyRegistry.NO_EPOCH || ch.keyEpoch != ourEpoch) return
        val ourFp = currentKeys[ch.name]?.let { ourKeyFingerprint(ch.name, it) } ?: 0
        if (ourFp == 0 || ch.keyFp == 0 || ch.keyFp == ourFp) return
        if (ourUid > peerUid) return // the lower uid rotates; we wait for their new epoch
        // No MIN_VALUE sentinel here: (now - MIN_VALUE) overflows
        // negative and would silence the throttle check forever.
        val last = lastConflictRotateMs[ch.name]
        if (last != null && now - last < CONFLICT_ROTATE_THROTTLE_MS) return
        lastConflictRotateMs[ch.name] = now
        legs[ch.name]?.let { leg ->
            rotateKey(ch.name, leg, (ourEpoch + 1) and 0xFF)
        }
    }

    /**
     * Keyless-bootstrap tie-break: generate the channel's first key
     * only when no fresh peer advertises a key AND we hold the lowest
     * uid among the keyless contenders. Everyone else waits for the
     * generator's beacon (epoch 0) and requests it via the election.
     */
    private fun mayBootstrapKey(channel: String): Boolean {
        val now = nowMs()
        val fresh =
            channelPeerEpochs[channel]
                .orEmpty()
                .filterValues { now - it.seenMs <= PEER_EPOCH_STALE_MS }
        if (fresh.values.any { it.epoch != ChannelKeyRegistry.NO_EPOCH }) return false
        return fresh.keys.all { ourUid < it }
    }

    private fun handleKeyReq(
        channelName: String,
        msg: ControlPacket.Message.KeyReq,
    ) {
        if (msg.requesterUid == ourUid) return
        val channel = legs.keys.firstOrNull { stableChannelId(it) == msg.channelId } ?: channelName
        val leg = legs[channel] ?: return
        val election = elections[channel] ?: return
        election.observePeerEpoch(msg.requesterUid, ChannelKeyRegistry.NO_EPOCH, hadCurrentKey = false)
        if (!election.shouldAnswerKeyRequest(msg.requesterUid, msg.forEpoch)) return
        answerKeyReq(channel, leg, msg.requesterUid, msg.forEpoch)
    }

    private fun answerKeyReq(
        channel: String,
        leg: MeshLeg,
        requesterUid: String,
        epoch: Int,
    ) {
        val registry = registryFor(channel)
        if (registry.currentEpoch() != epoch) return
        val certDer = peerCerts[requesterUid]
        if (certDer == null) {
            // We can't wrap to them yet — ask the group for their cert
            // (fingerprint pinned via CoT presence) and answer when
            // the CertReply lands.
            val fp = certFpForUid(requesterUid) ?: return
            pendingOffers[requesterUid] = PendingOffer(channel, epoch)
            leg.safeSendControl(ControlPacket.Message.CertReq(wantedCertFp = fp))
            return
        }
        // The registry holds the raw key only transiently for wrap: we
        // re-derive nothing — KeyOffer carries the wrapped CURRENT key.
        val key = currentKeyForWrap(channel) ?: return
        val wrapped = wrapKeyFor(certDer, key) ?: return
        leg.safeSendControl(
            ControlPacket.Message.KeyOffer(
                channelId = stableChannelId(channel),
                epoch = epoch,
                recipientUid = requesterUid,
                wrappedKey = wrapped,
            ),
        )
    }

    private fun currentKeyForWrap(channel: String): ByteArray? = currentKeys[channel]

    private fun handleKeyOffer(
        channelName: String,
        msg: ControlPacket.Message.KeyOffer,
    ) {
        val channel = legs.keys.firstOrNull { stableChannelId(it) == msg.channelId } ?: channelName
        if (msg.recipientUid != ourUid) return
        val key = unwrapKey(msg.wrappedKey) ?: return
        if (key.size != AeadCodec.KEY_BYTES) return
        val registry = registryFor(channel)
        if (registry.install(msg.epoch, key)) {
            currentKeys[channel] = key
        }
    }

    private fun handleCertReq(
        channelName: String,
        msg: ControlPacket.Message.CertReq,
    ) {
        val ours = ourCertDer() ?: return
        val ourFp = sha256Hex(ours)
        if (!ourFp.equals(msg.wantedCertFp, ignoreCase = true)) return
        (legs[channelName] ?: rendezvousLeg)?.safeSendControl(ControlPacket.Message.CertReply(certDer = ours))
    }

    private fun handleCertReply(msg: ControlPacket.Message.CertReply) {
        val fp = sha256Hex(msg.certDer)
        // Bind the cert to whichever uid CoT presence pins to this
        // fingerprint. An unpinned cert is not trusted — silently drop.
        val uid = knownPeerUids().firstOrNull { certFpForUid(it)?.equals(fp, ignoreCase = true) == true } ?: return
        peerCerts[uid] = msg.certDer
        // Answer any offer that was blocked on this cert.
        pendingOffers.remove(uid)?.let { pending ->
            legs[pending.channel]?.let { leg ->
                answerKeyReq(pending.channel, leg, uid, pending.epoch)
            }
        }
    }

    private fun broadcastBeacons() {
        val channelInfos =
            legs.map { (name, leg) ->
                ControlPacket.Message.PeerBeacon.Channel(
                    name = name,
                    group = leg.endpoint.groupAddress,
                    port = leg.endpoint.port,
                    keyEpoch = registryFor(name).currentEpoch(),
                    keyFp = currentKeys[name]?.let { ourKeyFingerprint(name, it) } ?: 0,
                )
            }
        val beacon =
            ControlPacket.Message.PeerBeacon(
                uid = ourUid,
                callsign = ourCallsign() ?: ourUid,
                mumbleConnected = mumbleConnected(),
                bridging = bridging,
                channels = channelInfos,
            )
        legs.values.forEach { it.safeSendControl(beacon) }
        rendezvousLeg?.safeSendControl(beacon)
    }

    private fun relayServerFrameToMesh(
        canonicalSpeaker: String,
        opus: ByteArray,
        now: Long,
    ) {
        val channel = primaryChannel ?: return
        val leg = legs[channel]
        val patchLeg = patchLegs[channel]
        if (leg == null && patchLeg == null) return
        val last = relayLastFrameMs[canonicalSpeaker]
        val burstStart = last == null || now - last > RELAY_BURST_GAP_MS
        relayLastFrameMs[canonicalSpeaker] = now
        if (burstStart) {
            // Tell mesh receivers who this relayed burst belongs to.
            // Non-XV Mumble clients have no presence and no beacon, so
            // without this a mesh-only device shows a bare SSRC where
            // the Mumble username belongs. Once per burst — cheap.
            val name = displayNameFor(canonicalSpeaker, speakerKey = "")
            if (name != canonicalSpeaker) {
                leg?.safeSendControl(
                    ControlPacket.Message.SpeakerName(
                        channelId = stableChannelId(channel),
                        speakerKey = "ssrc:%08x".format(RtpFraming.fnv1aSsrc(canonicalSpeaker)),
                        name = name,
                    ),
                )
            }
        }
        leg?.sendRelayOpus(canonicalSpeaker, opus, burstStart)
        patchLeg?.sendRelayOpus(canonicalSpeaker, opus, burstStart)
    }

    // Snapshot of the peer set the SSRC map was last built from, so the
    // per-tick refresh is a no-op unless membership actually changed —
    // the mapping is pure (uid → derived ssrc key), so re-deriving it for
    // an unchanged set every second is wasted work under the tick monitor.
    private var lastSsrcPeerSet: Set<String> = emptySet()

    private fun refreshSsrcMap() {
        val peers = knownPeerUids().toSet()
        if (peers == lastSsrcPeerSet) return
        lastSsrcPeerSet = peers
        peers.forEach { uid ->
            val key = "ssrc:%08x".format(RtpFraming.fnv1aSsrc(uid))
            ssrcKeyToUid[key] = uid
        }
    }

    // The bridge election is fed EXCLUSIVELY from mesh beacons — see
    // handleBeacon. CoT presence must NOT feed it: CoT travels via the
    // TAK server and crosses network boundaries, so a device on
    // network A would defer to a lower-uid candidate on network B
    // that it cannot reach over multicast, leaving network A with no
    // relay at all. The relay domain IS mesh reachability; beacons
    // only travel within it. With operator groups on separate
    // networks this correctly elects one bridge PER NETWORK, each
    // relaying its own island's serverless speakers — and cross-
    // island loops can't form because a relay arrives at the other
    // island's bridge as server-originated audio, which is never
    // relayed back (see decideVoiceRx / the leg's own-relay filter).

    private fun pruneDiscovered(now: Long) {
        discovered.entries.removeAll { now - it.value.lastSeenMs > DISCOVERED_STALE_MS }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Comms-plan PSKs install at epoch 0 by convention. */
        const val PSK_EPOCH = 0

        const val BEACON_INTERVAL_MS: Long = 5_000

        /** Gap that separates two relay bursts from the same speaker. */
        const val RELAY_BURST_GAP_MS: Long = 300

        /** Ticks (~seconds) to wait for a key offer before self-generating. */
        const val KEY_BOOTSTRAP_WAIT_TICKS = 2

        const val DISCOVERED_STALE_MS: Long = 60_000

        /** Beacon-advertised epochs older than this stop influencing bootstrap. */
        const val PEER_EPOCH_STALE_MS: Long = 17_000

        /**
         * Well-known rendezvous endpoint for offline discovery: fixed
         * inputs through the same v1 derivation every XV build shares,
         * so any two offline clients land on the same group with no
         * configuration at all.
         */
        const val RENDEZVOUS_CHANNEL = "xv-rendezvous"

        val RENDEZVOUS_ENDPOINT: MulticastEndpoint =
            MulticastGroupDerivation.derive(
                ServerIdentity("xv-offline"),
                RENDEZVOUS_CHANNEL,
            )

        val RENDEZVOUS_CONFIG: ChannelMulticastConfig =
            ChannelMulticastConfig(
                channelName = RENDEZVOUS_CHANNEL,
                mode = MulticastMode.ALWAYS,
                wireFormat = WireFormat.XV_NATIVE,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = RENDEZVOUS_ENDPOINT.groupAddress,
                pinnedPort = RENDEZVOUS_ENDPOINT.port,
            )

        /**
         * Stable 32-bit channel id for control-plane messages, derived
         * from the canonical channel name (Mumble numeric ids are
         * server-assigned and unavailable offline). FNV-1a like the
         * SSRC derivation, truncated to Int.
         */
        fun stableChannelId(canonicalChannelName: String): Int = RtpFraming.fnv1aSsrc(canonicalChannelName).toInt()

        /**
         * Short key fingerprint advertised in beacons so peers can
         * detect same-epoch/different-key split-brain. First 4 bytes
         * (big-endian) of SHA-256("xv-keyfp|" || key). 0 is reserved
         * for "no key"; the 2^-32 all-zero digest prefix maps to 1.
         * Not secret material — 32 bits of a hash identify the key
         * without revealing it.
         */
        fun keyFingerprint(key: ByteArray): Int {
            val d = java.security.MessageDigest.getInstance("SHA-256")
            d.update("xv-keyfp|".toByteArray(Charsets.US_ASCII))
            val h = d.digest(key)
            val fp =
                ((h[0].toInt() and 0xFF) shl 24) or
                    ((h[1].toInt() and 0xFF) shl 16) or
                    ((h[2].toInt() and 0xFF) shl 8) or
                    (h[3].toInt() and 0xFF)
            return if (fp == 0) 1 else fp
        }

        /**
         * Minimum spacing between conflict-driven rotations per
         * channel: two beacon intervals, so the loser has time to see
         * the new epoch and converge before we rotate again.
         */
        private const val CONFLICT_ROTATE_THROTTLE_MS = 10_000L

        /**
         * A mesh speaker unheard for this long stops showing in the
         * talker list. Matches MumbleTransport.TALKING_TTL_MS so mesh
         * and server talkers age out of the channel row identically.
         */
        private const val TALKING_TTL_MS = 600L

        /** Sanity cap on the bridge-announced name directory. */
        private const val MAX_ANNOUNCED_NAMES = 256

        /**
         * LRU cap on the SSRC→uid map. Covers any realistic team size
         * with headroom; bounds the synthetic "mumble:<session>" keys
         * that no uid-based departure eviction can reach.
         */
        private const val MAX_SSRC_ENTRIES = 64

        /**
         * A relay burst-gap entry unused for this long is pruned. Only
         * gates the burst-start heuristic, so an over-eager prune at
         * worst marks one extra burst boundary — cheap. Comfortably
         * longer than any real inter-burst gap.
         */
        private const val RELAY_IDLE_TTL_MS = 60_000L

        /** Min spacing between "bridging an unresolved SSRC" warnings. */
        private const val UNRESOLVED_RELAY_WARN_THROTTLE_MS = 30_000L

        /**
         * How stale the last observed server activity may be before the
         * failover RX gate treats the server as no-longer-delivering and
         * lets mesh RX through. Longer than the ~5 s Mumble ping interval
         * so a healthy-but-idle server stays gated without flapping.
         */
        private const val MUMBLE_LIVE_GRACE_MS = 8_000L
    }

    private fun MeshLeg.safeSendControl(msg: ControlPacket.Message) {
        if (config.cryptoPolicy != com.atakmap.android.xv.transport.multicast.CryptoPolicy.CLEARTEXT) {
            sendControl(msg)
        }
    }
}
