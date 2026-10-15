package com.atakmap.android.xv.transport.mumble

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Server chain validation against real certificates from a throwaway test CA
 * (src/test/resources/tls, valid until 2126): root -> intermediate -> leaf for
 * voice.example.test, plus an unrelated CA.
 */
class PinnedAnchorTrustManagerTest {
    private val root = cert("root")
    private val intermediate = cert("intermediate")
    private val leaf = cert("leaf")
    private val otherRoot = cert("other-root")

    @Test
    fun `accepts a full chain when only its intermediate is trusted`() {
        trusting(intermediate).checkServerTrusted(arrayOf(leaf, intermediate, root), "ECDHE_ECDSA")
    }

    @Test
    fun `accepts a full chain when only its root is trusted`() {
        trusting(root).checkServerTrusted(arrayOf(leaf, intermediate, root), "ECDHE_ECDSA")
    }

    @Test
    fun `accepts a chain without its root`() {
        trusting(root).checkServerTrusted(arrayOf(leaf, intermediate), "ECDHE_ECDSA")
    }

    @Test
    fun `refuses a chain from an untrusted CA`() {
        assertThrows(CertificateException::class.java) {
            trusting(otherRoot).checkServerTrusted(arrayOf(leaf, intermediate, root), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `refuses an empty chain`() {
        assertThrows(CertificateException::class.java) {
            trusting(root).checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
    }

    private fun trusting(ca: X509Certificate) = MumbleAuth.PinnedAnchorTrustManager(setOf(TrustAnchor(ca, null)))

    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/tls/$name.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
}
