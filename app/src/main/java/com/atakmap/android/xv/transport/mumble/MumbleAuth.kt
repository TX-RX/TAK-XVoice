package com.atakmap.android.xv.transport.mumble

import android.util.Log
import com.atakmap.android.maps.MapView
import com.atakmap.net.AtakAuthenticationDatabase
import com.atakmap.net.AtakCertificateDatabase
import gov.tak.api.engine.net.ICertificateStore
import gov.tak.api.engine.net.ICredentialsStore
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// Auth glue between ATAK and the Mumble server, conforming to the
// OpenTAKServer Mumble authenticator contract (OTS commit 85deb99):
//   - Username = "<callsign>---<slot-suffix>" with spaces in callsign
//     converted to underscores. Slot suffix is deterministic per
//     (device, VS1|VS2) — see MumbleInstallId.
//   - Auth-secret field on the wire is left blank — the TLS client cert
//     (ATAK enrollment) is the actual credential. OTS's authenticator
//     falls back to cert-CN matching the EUD UID if the callsign lookup
//     misses.
object MumbleAuth {
    private const val TAG = "XvMumbleAuth"

    fun deviceCallsign(): String? =
        try {
            MapView.getMapView()?.deviceCallsign
        } catch (t: Throwable) {
            Log.w(TAG, "could not read device callsign", t)
            null
        }

    // ATAK device UID — what VX writes into Mumble UserState.comment as a
    // presence beacon so other VX clients can map a Mumble session back to
    // an ATAK contact (and decide whether to show the call button).
    fun deviceUid(): String? =
        try {
            MapView.getDeviceUid()
        } catch (t: Throwable) {
            Log.w(TAG, "could not read device uid", t)
            null
        }

    /**
     * Identity material for cert-based signing on the encrypted-
     * multicast layer. All XV traffic that needs sender authentication
     * uses the same TAK enrollment cert that the Mumble TLS path uses
     * — no ad-hoc keypair generation, no parallel PKI.
     *
     *   leaf       — the device's TAK enrollment leaf cert (X509). Pubkey
     *                advertised to peers (or fingerprint thereof) so they
     *                can RSA-OAEP-wrap channel keys to us.
     *   privateKey — the matching RSA private key. Used to unwrap inbound
     *                channel keys; never leaves the device. Loaded from
     *                the same PKCS12 that the TLS path consumes.
     *
     * Returns null when the cert isn't loaded yet (plugin started without
     * an enrolled TAK server, or the credentials lookup failed) — caller
     * is responsible for handling that gracefully (multicast layer
     * disabled, fall back to Mumble-only).
     */
    data class TakIdentity(
        val leaf: X509Certificate,
        val privateKey: java.security.PrivateKey,
    )

    /** SHA-256 fingerprint of the leaf cert's DER encoding, hex-encoded
     *  lowercase. Stable identifier for the device's enrollment cert;
     *  used in the `<__xv certFp="...">` CoT advertisement. */
    fun certFingerprint(cert: X509Certificate): String =
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update(cert.encoded)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "certFingerprint failed", t)
            ""
        }

    fun loadTakIdentity(takServerHost: String): TakIdentity? =
        try {
            val (ks, pwd) = loadClientKeyStore(takServerHost)
            // The keystore can carry multiple cert/key entries when an
            // operator has re-enrolled (old cert + new cert both present
            // until the operator manually clears the old one). Previously
            // we picked the FIRST entry by alias-order, which is
            // KeyStore-impl-defined and routinely surfaced an EXPIRED
            // cert on a re-enrolled device. Pick the latest-issued
            // entry (max notBefore) so an enrollment refresh
            // immediately takes effect without a manual cleanup.
            // Audit L8.
            val aliasList = mutableListOf<String>()
            val it = ks.aliases()
            while (it.hasMoreElements()) aliasList += it.nextElement()
            val candidates =
                aliasList.mapNotNull { a ->
                    val cert = ks.getCertificate(a) as? X509Certificate ?: return@mapNotNull null
                    val key = ks.getKey(a, pwd) as? java.security.PrivateKey ?: return@mapNotNull null
                    Triple(a, cert, key)
                }
            if (candidates.isEmpty()) {
                Log.w(TAG, "loadTakIdentity: no key entry in keystore for $takServerHost (aliases=$aliasList)")
                null
            } else {
                val chosen = candidates.maxByOrNull { it.second.notBefore.time }!!
                if (candidates.size > 1) {
                    val summary =
                        candidates.joinToString { (a, c, _) -> "$a (notBefore=${c.notBefore})" }
                    Log.i(TAG, "loadTakIdentity: ${candidates.size} candidates — picking latest by notBefore: $summary")
                }
                TakIdentity(chosen.second, chosen.third)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadTakIdentity failed for $takServerHost", t)
            null
        }

    /**
     * Build the Mumble username for our session.
     *
     * Wire format: `<callsign>---<slotSuffix>`. The `---` separator is
     * load-bearing — XV's own roster scanner (`MumbleTransport.kt`)
     * uses it as the XV-peer detection signal.
     *
     * Caller passes a slot suffix that's deterministic per
     * (device, VS) — see [MumbleInstallId.primarySuffix] /
     * [MumbleInstallId.secondarySuffix]. Murmur enforces username
     * uniqueness, so a duplicate connect from the same device gets
     * UsernameInUseException — [ReconnectingMumbleTransport] backs off
     * + retries with the same name instead of papering over the ghost
     * with a new identity.
     */
    fun mumbleUsername(
        callsign: String?,
        slotSuffix: String,
    ): String {
        val safe = (callsign ?: "ATAK").trim().replace(' ', '_')
        return "$safe---$slotSuffix"
    }

    // Open a TLS socket to the Mumble server using the client cert + CA that
    // ATAK already provisioned for the TAK CoT connection on the same host.
    //
    // Why this is hand-rolled rather than CertificateManager.getSockFactory:
    // ATAK's central trust manager looks up trust anchors keyed by hostname
    // (and the cache misses for our context). We instead pull the CA + client
    // cert blobs straight out of AtakCertificateDatabase and build a clean
    // SSLContext, which validates the OTS Murmur cert through the same chain
    // that the TAK CoT stream already trusts.
    //
    // takServerHost must be the hostname ATAK has stored the cert under (i.e.
    // the TAK server hostname). Same hostname for OTS deployments since
    // Murmur is co-hosted.
    fun connectTls(
        host: String,
        port: Int,
        takServerHost: String = host,
    ): SSLSocket {
        val ctx = buildSslContext(takServerHost)
        val socket = ctx.socketFactory.createSocket(host, port) as SSLSocket
        // Hostname verification — without this, any cert chained to one of our
        // trust anchors (TAK private CA OR system trust store) would pass the
        // PinnedAnchorTrustManager regardless of CN/SAN. "HTTPS" is the
        // standard SSLParameters identification algorithm; on Conscrypt it
        // performs RFC 6125-style endpoint matching against `host` (the
        // string we passed to createSocket) using the SNI sent during
        // handshake. Set BEFORE startHandshake.
        socket.sslParameters =
            socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
        return socket
    }

    private fun buildSslContext(takServerHost: String): SSLContext {
        val (clientKs, clientPwd) = loadClientKeyStore(takServerHost)
        val trustKs = loadTrustKeyStore(takServerHost)

        // When ATAK has more than one client cert stored for the same
        // server (re-enrollment without manual cleanup of the old cert),
        // the default KeyManagerFactory picks an alias in implementation-
        // defined order — Conscrypt iterates alphabetically by alias
        // string, which is unrelated to notBefore. If KMF picks the older
        // cert during the TLS handshake, OTS Murmur rejects with
        // `WrongUserPW: Wrong certificate or password for existing user`
        // because the Mumble account was already registered against the
        // newer cert fingerprint (or vice versa). Verified 2026-06-06 on
        // a Galaxy S24 with 2 keystore entries for tak.example.com:
        // `loaded client cert (2 entries)` followed immediately by an
        // auth reject and a 60s backoff loop.
        //
        // loadTakIdentity already picks the latest-by-notBefore entry
        // for the CoT presence fingerprint. Apply the same policy to the
        // TLS handshake by wrapping the default KMF in a single-alias
        // key manager that always returns the freshest cert. If only one
        // entry is present this is a transparent pass-through; the wrap
        // only changes behavior in the multi-cert case.
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(clientKs, clientPwd)
        val keyManagers = pinLatestClientCertKeyManager(kmf, clientKs, takServerHost)

        // Bypass TrustManagerFactory entirely — Android's default TMF on this
        // platform (Conscrypt) returns a TrustManagerImpl whose checkServerTrusted
        // delegates to NetworkSecurityTrustManager / RootTrustManager (the app's
        // network security config), NOT to the keystore we passed in. Our own
        // CA never ends up being a trust anchor. We work around this by writing
        // a thin X509TrustManager that runs PKIX cert-path validation against
        // a combined anchor set:
        //   - The OTS / TAK private CA from ATAK's cert store (for deployments
        //     where Mumble is signed by the same private CA as TAK).
        //   - The Android system trust store (for OTS deployments where Mumble
        //     uses a public cert, e.g. Let's Encrypt — common when the host is
        //     reachable on a real public DNS name).
        // Either anchor set accepting the chain is fine.
        val anchors = collectTrustAnchors(trustKs) + systemTrustAnchors()
        Log.i(TAG, "anchor set: ${anchors.size} (private + system)")
        val tm = PinnedAnchorTrustManager(anchors)

        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(keyManagers, arrayOf<TrustManager>(tm), null)
        Log.i(TAG, "SSLContext built for $takServerHost (km=${keyManagers.size} anchors=${anchors.size})")
        return ctx
    }

    /**
     * Wrap [kmf]'s key managers so the handshake always presents the
     * latest-by-notBefore client cert from [clientKs]. When the keystore
     * has exactly one private-key entry the wrap is a pass-through —
     * `chooseClientAlias` returns that one alias. When there are several
     * (re-enrollment scenario), the wrap forces the freshest cert
     * regardless of Conscrypt's alias-iteration order, mirroring the
     * picker in [loadTakIdentity].
     *
     * Logs the chosen alias's notBefore so a future "still rejected"
     * report has an immediate pointer to which cert was on the wire.
     */
    private fun pinLatestClientCertKeyManager(
        kmf: KeyManagerFactory,
        clientKs: KeyStore,
        takServerHost: String,
    ): Array<javax.net.ssl.KeyManager> {
        val base = kmf.keyManagers
        val baseX509 = base.firstOrNull { it is javax.net.ssl.X509KeyManager } as? javax.net.ssl.X509KeyManager
        if (baseX509 == null) {
            Log.w(TAG, "no X509KeyManager from KMF — leaving handshake to default")
            return base
        }
        // Enumerate private-key entries and pick the latest notBefore.
        val keyAliases = mutableListOf<Pair<String, X509Certificate>>()
        val it = clientKs.aliases()
        while (it.hasMoreElements()) {
            val a = it.nextElement()
            if (!clientKs.isKeyEntry(a)) continue
            val cert = clientKs.getCertificate(a) as? X509Certificate ?: continue
            keyAliases += a to cert
        }
        if (keyAliases.isEmpty()) {
            Log.w(TAG, "no private-key entries in keystore for $takServerHost — KMF default")
            return base
        }
        val chosen = keyAliases.maxByOrNull { it.second.notBefore.time }!!
        if (keyAliases.size > 1) {
            val summary = keyAliases.joinToString { (a, c) -> "$a (notBefore=${c.notBefore})" }
            Log.i(
                TAG,
                "buildSslContext: ${keyAliases.size} client cert(s) for $takServerHost — pinning latest: " +
                    "alias=${chosen.first} notBefore=${chosen.second.notBefore} (candidates: $summary)",
            )
        } else {
            Log.i(
                TAG,
                "buildSslContext: pinning client cert alias=${chosen.first} notBefore=${chosen.second.notBefore}",
            )
        }
        val pinnedAlias = chosen.first
        val wrapped =
            object : javax.net.ssl.X509ExtendedKeyManager() {
                override fun getClientAliases(
                    keyType: String?,
                    issuers: Array<out java.security.Principal>?,
                ): Array<String> = arrayOf(pinnedAlias)

                override fun chooseClientAlias(
                    keyTypes: Array<out String>?,
                    issuers: Array<out java.security.Principal>?,
                    socket: java.net.Socket?,
                ): String = pinnedAlias

                override fun chooseEngineClientAlias(
                    keyTypes: Array<out String>?,
                    issuers: Array<out java.security.Principal>?,
                    engine: javax.net.ssl.SSLEngine?,
                ): String = pinnedAlias

                override fun getServerAliases(
                    keyType: String?,
                    issuers: Array<out java.security.Principal>?,
                ): Array<String>? = baseX509.getServerAliases(keyType, issuers)

                override fun chooseServerAlias(
                    keyType: String?,
                    issuers: Array<out java.security.Principal>?,
                    socket: java.net.Socket?,
                ): String? = baseX509.chooseServerAlias(keyType, issuers, socket)

                override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                    val out =
                        baseX509.getCertificateChain(alias) ?: return null
                    @Suppress("UNCHECKED_CAST")
                    return out.filterIsInstance<X509Certificate>().toTypedArray()
                }

                override fun getPrivateKey(alias: String?): java.security.PrivateKey? = baseX509.getPrivateKey(alias)
            }
        return arrayOf<javax.net.ssl.KeyManager>(wrapped)
    }

    private fun collectTrustAnchors(ks: KeyStore): Set<TrustAnchor> {
        val out = HashSet<TrustAnchor>()
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val a = aliases.nextElement()
            val cert = ks.getCertificate(a)
            if (cert is X509Certificate) {
                out += TrustAnchor(cert, null)
            }
        }
        return out
    }

    private fun systemTrustAnchors(): Set<TrustAnchor> =
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            tm?.acceptedIssuers?.map { TrustAnchor(it, null) }?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            Log.w(TAG, "could not load system trust anchors", t)
            emptySet()
        }

    // PKIX validation against a fixed set of anchors. Bypasses the platform's
    // network-security-config wrapper entirely — Android won't substitute its
    // own trust manager in front of this one because it's not a TMF-derived
    // TrustManagerImpl.
    private class PinnedAnchorTrustManager(
        private val anchors: Set<TrustAnchor>,
    ) : X509TrustManager {
        private val cf = CertificateFactory.getInstance("X.509")

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            // We don't validate clients (we are the client).
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            if (chain.isEmpty()) throw java.security.cert.CertificateException("empty chain")
            val leaf = chain.first()
            Log.i(TAG, "trust: server check leaf=${leaf.subjectDN} (${chain.size} certs)")
            // Try the chain as-is first.
            if (tryValidate(chain.toList())) {
                Log.i(TAG, "trust: server OK")
                return
            }
            // The server might not have included the root in its chain; that's
            // fine — PKIX completes the chain to the anchor automatically. The
            // failure path means we genuinely don't trust it.
            throw java.security.cert.CertificateException(
                "server cert chain does not validate against any pinned anchor (leaf=${leaf.subjectDN})",
            )
        }

        private fun tryValidate(certs: List<X509Certificate>): Boolean =
            try {
                val path = cf.generateCertPath(certs)
                val params = PKIXParameters(anchors)
                // Revocation checking deliberately disabled. Two reasons:
                //   1. CRL distribution points + OCSP responders are
                //      usually internet-facing services, but XV runs on
                //      tactical mesh / cellular-spotty networks where
                //      they aren't reachable. PKIX with revocation
                //      enabled fails the entire validation on a missed
                //      fetch, which would tank Mumble auth on every
                //      slow-link operator (REVOKED and UNKNOWN treated
                //      the same by Android's CertPathValidator).
                //   2. TAK enrollment certs are short-lived (~30 days
                //      typical) and re-issued via the standard ATAK
                //      enrollment flow, which already revokes the
                //      prior cert server-side. The OTS Murmur
                //      authenticator rejects on cert-issuer mismatch
                //      against the current TAK CA — a revoked cert
                //      whose serial has been rotated out can't auth
                //      regardless of what the client validator thinks.
                //
                // OCSP stapling support on the OTS Murmur side would
                // let us flip this back to true safely; tracked as a
                // server-side enhancement, not a client fix.
                // Audit M6.
                params.isRevocationEnabled = false
                CertPathValidator.getInstance("PKIX").validate(path, params)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "trust: validate failed — ${t.message}")
                false
            }

        override fun getAcceptedIssuers(): Array<X509Certificate> = anchors.map { it.trustedCert }.toTypedArray()
    }

    private fun loadClientKeyStore(server: String): Pair<KeyStore, CharArray> {
        val bytes =
            AtakCertificateDatabase.getCertificateForServer(ICertificateStore.TYPE_CLIENT_CERTIFICATE, server)
                ?: error("no client cert in ATAK for server $server (TYPE_CLIENT_CERTIFICATE)")
        // Cert-blob types and credential types are different namespaces.
        // The PKCS12 unlock secret for TYPE_CLIENT_CERTIFICATE lives
        // under the credentials store's TYPE_clientPassword key, not
        // its own type.
        val pwd = certPasswordFor(ICredentialsStore.Credentials.TYPE_clientPassword, server)
        val ks = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(bytes).use { ks.load(it, pwd) }
        Log.i(TAG, "loaded client cert (${ks.size()} entries)")
        return ks to pwd
    }

    private fun loadTrustKeyStore(server: String): KeyStore {
        val bytes =
            AtakCertificateDatabase.getCertificateForServer(ICertificateStore.TYPE_TRUST_STORE_CA, server)
                ?: error("no trust-store CA in ATAK for server $server (TYPE_TRUST_STORE_CA)")
        // Trust-store CA uses TYPE_caPassword in the credentials store.
        val pwd = certPasswordFor(ICredentialsStore.Credentials.TYPE_caPassword, server)
        val src = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(bytes).use { src.load(it, pwd) }
        // Re-emit as a JKS so TrustManagerFactory will accept the entries as
        // root anchors. PKCS12 keystores work fine with TMF too on Android,
        // but copying through guarantees `setCertificateEntry` semantics
        // regardless of how the source was packaged.
        val out = KeyStore.getInstance(KeyStore.getDefaultType())
        out.load(null, null)
        var idx = 0
        val aliases = src.aliases()
        while (aliases.hasMoreElements()) {
            val a = aliases.nextElement()
            val cert = src.getCertificate(a)
            if (cert != null) {
                out.setCertificateEntry("ca-$idx", cert)
                idx++
            }
        }
        Log.i(TAG, "loaded trust store ($idx CA entries)")
        return out
    }

    private fun certPasswordFor(
        type: String,
        server: String,
    ): CharArray {
        val raw =
            try {
                AtakAuthenticationDatabase.getCredentials(type, server)?.password
            } catch (t: Throwable) {
                Log.w(TAG, "credentials lookup failed for $type/$server", t)
                null
            }
        return (raw ?: "").toCharArray()
    }
}
