package com.atakmap.android.xv.security

import android.util.Log
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A *non-rejecting* TrustManager wrapper that audits TLS chains for
 * "did this chain actually terminate at a TAK CA?" and surfaces a WARN
 * to logcat when it didn't.
 *
 * Why non-rejecting: ATAK's trust store is ground truth for what's
 * acceptable in a deployed environment. If the operator (or sysadmin)
 * has provisioned a non-TAK CA into ATAK's store on purpose, we don't
 * want to break that. But we DO want a noisy audit log if a TLS chain
 * terminates at a CA whose Subject DN doesn't contain "TAK" — that's
 * the failure mode where someone has polluted ATAK's trust store with
 * a public root and a hostile cert chains through it. An operator
 * doing post-incident forensics on logcat will see the warning and
 * know to investigate.
 *
 * Wire-in (when MumbleAuth.kt is editable; currently owned by another
 * agent in this worktree round):
 *
 *   // in MumbleAuth.buildSslContext, just before `return ctx`:
 *   TakCaPinValidator.applyTo(ctx, anchors)
 *
 * Or, equivalently, swap the TrustManager passed to ctx.init:
 *
 *   val tm = TakCaPinValidator.wrap(PinnedAnchorTrustManager(anchors))
 *   ctx.init(kmf.keyManagers, arrayOf<TrustManager>(tm), null)
 *
 * The wrapped TM still fully delegates chain validation to the
 * PinnedAnchorTrustManager — it only adds an audit-only check on top.
 */
object TakCaPinValidator {
    private const val TAG = "XvTakCaPin"

    /** Substring matched (case-insensitive) against the Subject DN of
     *  the chain terminator (last cert in the chain after PKIX has
     *  validated it). "TAK" is broad enough to catch every TAK
     *  enrollment CA we've seen in the wild — "TAK Server CA",
     *  "OpenTAKServer CA", etc. — without false-matching common
     *  public roots. Tighten if the deployment ever needs a stricter
     *  pin. */
    private const val TAK_CA_DN_SUBSTRING = "TAK"

    /**
     * Wrap an existing X509TrustManager so that successful chain
     * validations are also audited for TAK-CA termination. Failing
     * chains still throw; passing chains are returned to the caller
     * regardless of whether the audit warned.
     */
    fun wrap(delegate: X509TrustManager): X509TrustManager = AuditingTrustManager(delegate)

    /**
     * Convenience: replace the trust managers on an already-built
     * SSLContext. Note that SSLContext.init can only be called once
     * per context on most JSSE providers — prefer [wrap] when you
     * still control the SSLContext construction. This helper exists
     * for callers that receive a pre-built SSLContext from elsewhere
     * and need to add the audit layer in place.
     *
     * Returns the SSLContext for fluent chaining.
     */
    fun applyTo(
        ctx: SSLContext,
        delegate: X509TrustManager,
    ): SSLContext {
        // SSLContext.init can be called more than once on Conscrypt
        // (the Android default JSSE provider) — it tears down any
        // pre-existing SSLSessionContext and rebuilds. Callers using
        // a non-Conscrypt provider should use wrap() instead.
        ctx.init(null, arrayOf<TrustManager>(wrap(delegate)), null)
        return ctx
    }

    /** Audits the chain that the [delegate] just accepted. Logs a
     *  WARN if the leaf->root chain doesn't terminate at a TAK-named
     *  CA. Pure observation; never throws. */
    fun audit(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) {
            Log.w(TAG, "audit: empty chain (delegate accepted nothing?)")
            return
        }
        val terminator = chain.last()
        val subject = terminator.subjectDN?.name.orEmpty()
        val issuer = terminator.issuerDN?.name.orEmpty()
        val matchesTak =
            subject.contains(TAK_CA_DN_SUBSTRING, ignoreCase = true) ||
                issuer.contains(TAK_CA_DN_SUBSTRING, ignoreCase = true)
        if (!matchesTak) {
            Log.w(
                TAG,
                "TLS chain validated but did NOT terminate at a TAK-named CA " +
                    "(terminator subject=\"$subject\", issuer=\"$issuer\"). " +
                    "This is acceptable when ATAK's trust store has been " +
                    "provisioned with a non-TAK root by design, but is also " +
                    "the signature of a polluted trust store + hostile cert. " +
                    "Operator: audit your trust anchors.",
            )
        } else {
            Log.d(TAG, "TLS chain terminator matches TAK substring (subject=\"$subject\")")
        }
    }

    private class AuditingTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            // We never authenticate clients (we are the client). Pass through.
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            // Delegate first; if it rejects, propagate the exception unchanged.
            delegate.checkServerTrusted(chain, authType)
            // Only audit on successful acceptance.
            audit(chain)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
