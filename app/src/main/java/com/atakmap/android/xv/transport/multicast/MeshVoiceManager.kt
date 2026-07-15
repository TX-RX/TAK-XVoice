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
    /** CoT presence: all currently-known XV peer uids (for SSRC mapping). */
    private val knownPeerUids: () -> Collection<String> = { emptyList() },
    /**
     * CoT presence: only the uids whose presence is FRESH right now.
     * Feeds the bridge election — stale entries must not: a snapshot
     * from before a peer dropped off the network still advertises its
     * old mumbleSession, and re-observing it every 1 Hz tick would
     * outvote that peer's own fresh mesh beacons (5 s cadence) five
     * to one, pinning the election to a stale "everyone's connected"
     * view and keeping the bridge role off.
     */
    private val freshPeerUids: () -> Collection<String> = knownPeerUids,
    /** CoT presence: peer cert SHA-256 fingerprint (lowercase hex), for cert exchange. */
    private val certFpForUid: (String) -> String? = { null },
    /** Our own enrollment cert (DER), for CertReply. Null when unenrolled. */
    private val ourCertDer: () -> ByteArray? = { null },
    /** Unwrap a KeyOffer addressed to us with our private key. Null on failure. */
    private val unwrapKey: (wrapped: ByteArray) -> ByteArray? = { null },
    /** Wrap a channel key to a recipient cert (DER). Null on failure. */
    private val wrapKeyFor: (recipientCertDer: ByteArray, key: ByteArray) -> ByteArray? = { _, _ -> null },
    private val generateKey: () -> ByteArray = { AeadCodec.generateChannelKey() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val failoverPolicy: FailoverPolicy = FailoverPolicy(),
    private val deduper: RxDeduper = RxDeduper(),
) : MeshLegSink {
    // ---- leg + channel state ----

    private val legs = LinkedHashMap<String, MeshLeg>() // canonical channel → leg
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
    private val ssrcKeyToUid = HashMap<String, String>()
    private val relayLastFrameMs = HashMap<String, Long>() // canonical speaker → last relayed frame

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

    /** Peer left (Mumble UserRemove / CoT stale-purge). Feeds key rotation. */
    @Synchronized
    fun onPeerDeparted(uid: String) {
        bridgeElection.observePeerDeparted(uid)
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
        if (slot != 0) return true // VS2 has no mesh leg to collide with
        val canonical = deviceUidForMumbleSession(speakerSession) ?: "mumble:$speakerSession"
        val play = deduper.shouldPlay(legId = "mumble", speaker = canonical, nowMs = now)
        if (play && bridging) {
            relayServerFrameToMesh(canonical, opus, now)
        }
        return play
    }

    /** Ping acks etc. — Mumble RX health without a voice frame. */
    @Synchronized
    fun observeMumbleHealth() {
        failoverPolicy.observeMumbleRx(nowMs())
    }

    // ---- mesh leg RX (MeshLegSink) ----

    @Synchronized
    override fun onVoice(
        channelName: String,
        opus: ByteArray,
        speakerKey: String,
        seqInBurst: Int?,
    ) {
        if (channelName == RENDEZVOUS_CHANNEL) return // control-plane only
        val now = nowMs()
        val canonical = ssrcKeyToUid[speakerKey] ?: speakerKey
        if (!deduper.shouldPlay(legId = "mesh:$channelName", speaker = canonical, nowMs = now)) return
        onRxOpus(opus, "mcast:$channelName:$speakerKey")
        if (bridging && canonical != ourUid && !uidMumbleConnected(canonical)) {
            // Server-less speaker heard on the mesh while we hold the
            // bridge: relay onto the Mumble channel. The frame rides
            // OUR Mumble session (protocol limitation); mesh-side
            // attribution stays intact for everyone on the group.
            val last = relayLastFrameMs[canonical]
            val burstStart = last == null || now - last > RELAY_BURST_GAP_MS
            relayLastFrameMs[canonical] = now
            relayToMumble(opus, burstStart)
        }
    }

    @Synchronized
    override fun onControl(
        channelName: String,
        msg: ControlPacket.Message,
        sourceHost: String,
    ) {
        when (msg) {
            is ControlPacket.Message.PeerBeacon -> handleBeacon(msg)
            is ControlPacket.Message.KeyReq -> handleKeyReq(channelName, msg)
            is ControlPacket.Message.KeyOffer -> handleKeyOffer(channelName, msg)
            is ControlPacket.Message.CertReq -> handleCertReq(channelName, msg)
            is ControlPacket.Message.CertReply -> handleCertReply(msg)
        }
    }

    // ---- periodic tick (~1 Hz from the plugin's scheduler) ----

    @Synchronized
    fun tick() {
        val now = nowMs()
        reconcileLegs()
        refreshSsrcMap()
        refreshPresencePeers(now)

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
        bridging = legs.isNotEmpty() && bridgeElection.evaluate(now, mumbleConnected())

        // Discovery + election beacons.
        if (lastBeaconAtMs == Long.MIN_VALUE || now - lastBeaconAtMs >= BEACON_INTERVAL_MS) {
            lastBeaconAtMs = now
            broadcastBeacons()
        }

        deduper.prune(now)
        pruneDiscovered(now)
    }

    /** CoT presence sighting: peer uid + live server connectivity. */
    @Synchronized
    fun observePeerConnectivity(
        uid: String,
        mumbleConnected: Boolean,
    ) {
        bridgeElection.observePeer(uid, mumbleConnected, nowMs())
    }

    // ---- comms plan + PSK ----

    /** Install a pre-shared channel key (comms plan import). Epoch 0. */
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
     * Short status badge for the dropdown, or null when mesh voice is
     * dormant. Examples: "MESH READY", "MESH ACTIVE · CLEAR",
     * "MESH READY · BRIDGING".
     */
    @Synchronized
    fun statusBadge(): String? {
        if (legs.isEmpty()) return null
        val base = if (meshTxActive) "MESH ACTIVE" else "MESH READY"
        val clear = legs.values.any { !it.encryptedNow }
        return buildString {
            append(base)
            if (bridging) append(" · BRIDGING")
            if (clear) append(" · CLEAR")
        }
    }

    /** Tear everything down (plugin unload). */
    @Synchronized
    fun shutdown() {
        legs.values.forEach { runCatching { it.close() } }
        legs.clear()
        rendezvousLeg?.let { runCatching { it.close() } }
        rendezvousLeg = null
        deduper.reset()
        bridgeElection.reset()
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

        // Tear down legs no longer wanted.
        val stale = legs.keys.filter { it !in desired.keys }
        stale.forEach { channel ->
            legs.remove(channel)?.let { runCatching { it.close() } }
        }

        // Start newly-wanted legs. A derived endpoint needs a server
        // identity; a pinned one doesn't (offline comms-plan channels).
        desired.forEach { (channel, cfg) ->
            if (channel in legs) return@forEach
            val endpoint = resolveEndpoint(cfg) ?: return@forEach
            val leg =
                runCatching { legFactory.create(cfg, endpoint, registryFor(channel), this) }
                    .getOrNull() ?: return@forEach
            legs[channel] = leg
            elections.getOrPut(channel) {
                KeyElection(ourUid, stableChannelId(channel), registryFor(channel))
            }
            keyBootstrapTicks[channel] = 0
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
                leg.sendControl(
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
            leg.sendControl(
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
        bridgeElection.observePeer(msg.uid, msg.mumbleConnected, now)
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
            }
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
            leg.sendControl(ControlPacket.Message.CertReq(wantedCertFp = fp))
            return
        }
        // The registry holds the raw key only transiently for wrap: we
        // re-derive nothing — KeyOffer carries the wrapped CURRENT key.
        val key = currentKeyForWrap(channel) ?: return
        val wrapped = wrapKeyFor(certDer, key) ?: return
        leg.sendControl(
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
        (legs[channelName] ?: rendezvousLeg)?.sendControl(ControlPacket.Message.CertReply(certDer = ours))
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
        legs.values.forEach { it.sendControl(beacon) }
        rendezvousLeg?.sendControl(beacon)
    }

    private fun relayServerFrameToMesh(
        canonicalSpeaker: String,
        opus: ByteArray,
        now: Long,
    ) {
        val channel = primaryChannel ?: return
        val leg = legs[channel] ?: return
        val last = relayLastFrameMs[canonicalSpeaker]
        val burstStart = last == null || now - last > RELAY_BURST_GAP_MS
        relayLastFrameMs[canonicalSpeaker] = now
        leg.sendRelayOpus(canonicalSpeaker, opus, burstStart)
    }

    private fun refreshSsrcMap() {
        knownPeerUids().forEach { uid ->
            val key = "ssrc:%08x".format(RtpFraming.fnv1aSsrc(uid))
            ssrcKeyToUid[key] = uid
        }
    }

    // Feed the bridge election from CoT presence every tick. A peer
    // whose `<__xv>` detail carries a live Mumble session is
    // server-connected; one without it (but still present) is a
    // candidate that needs a bridge. Beacons supply the same fact for
    // peers CoT hasn't reached — both paths land in observePeer.
    // FRESH presence only: see [freshPeerUids] — a stale snapshot
    // re-observed at tick rate would overwrite beacon truth.
    private fun refreshPresencePeers(now: Long) {
        freshPeerUids().forEach { uid ->
            if (uid == ourUid) return@forEach
            bridgeElection.observePeer(uid, uidMumbleConnected(uid), now)
        }
    }

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
    }
}
