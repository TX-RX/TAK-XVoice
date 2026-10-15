package com.atakmap.android.xv.transport.mumble

import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * verifyServerHostname with Android's own default HostnameVerifier and a real
 * certificate: the JVM's default verifier rejects every name, so only a device
 * shows that the production default checks the certificate's names.
 * Run with ./gradlew connectedCivDebugAndroidTest -PatakBaselineVersion=<x.y.z>.
 */
class MumbleHostnameTest {
    @Test
    fun acceptsTheNameOnTheCertificate() {
        val socket = FakeSocket(LEAF)
        MumbleAuth.verifyServerHostname(socket, "voice.example.test")
        assertFalse(socket.closed)
    }

    @Test
    fun refusesAnotherNameAndClosesTheSocket() {
        val socket = FakeSocket(LEAF)
        assertThrows(SSLPeerUnverifiedException::class.java) {
            MumbleAuth.verifyServerHostname(socket, "other.example.test")
        }
        assertTrue(socket.closed)
    }

    /** An SSLSocket whose session presents [leaf], as after a handshake. */
    private class FakeSocket(
        private val leaf: X509Certificate,
    ) : SSLSocket() {
        var closed = false

        override fun getSession(): SSLSession =
            Proxy.newProxyInstance(SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "getPeerCertificates" -> arrayOf(leaf)
                    "getPeerHost" -> null
                    "getPeerPort" -> -1
                    "isValid" -> true
                    else -> null
                }
            } as SSLSession

        override fun close() {
            closed = true
        }

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun getEnabledCipherSuites(): Array<String> = emptyArray()

        override fun setEnabledCipherSuites(suites: Array<String>) = Unit

        override fun getSupportedProtocols(): Array<String> = emptyArray()

        override fun getEnabledProtocols(): Array<String> = emptyArray()

        override fun setEnabledProtocols(protocols: Array<String>) = Unit

        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = Unit

        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = Unit

        override fun startHandshake() = Unit

        override fun setUseClientMode(mode: Boolean) = Unit

        override fun getUseClientMode(): Boolean = true

        override fun setNeedClientAuth(need: Boolean) = Unit

        override fun getNeedClientAuth(): Boolean = false

        override fun setWantClientAuth(want: Boolean) = Unit

        override fun getWantClientAuth(): Boolean = false

        override fun setEnableSessionCreation(flag: Boolean) = Unit

        override fun getEnableSessionCreation(): Boolean = true
    }

    private companion object {
        // CN and SAN voice.example.test, from a throwaway test CA; valid until 2126.
        val LEAF: X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(
                    """
-----BEGIN CERTIFICATE-----
MIIB2TCCAX+gAwIBAgIUagc9OoBfunEyLoYyZyp+Kcp301EwCgYIKoZIzj0EAwIw
IjEgMB4GA1UEAwwXWFYgVGVzdCBJbnRlcm1lZGlhdGUgQ0EwIBcNMjYxMDExMDEw
ODUzWhgPMjEyNjA5MTcwMTA4NTNaMB0xGzAZBgNVBAMMEnZvaWNlLmV4YW1wbGUu
dGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABOrvmw6mn3KigXYgJ1PMp5Ms
DC8AmSACtFZdYmNmuPoTV2KWCaVJmZ3ZekzqRlgWmol0/0YK4DCoa6uIckP6XwSj
gZUwgZIwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwEwYDVR0lBAwwCgYI
KwYBBQUHAwEwHQYDVR0RBBYwFIISdm9pY2UuZXhhbXBsZS50ZXN0MB0GA1UdDgQW
BBRUc7KT12wuuay0Cf2o4zQP0jK8HDAfBgNVHSMEGDAWgBSfv2M48/IqpIFTJn6j
ahFhyQ/RjDAKBggqhkjOPQQDAgNIADBFAiAN4iwE3DpUen2l1J09Plnzb4egvhy4
dkM5QV25CJpuBwIhAJZBYwbwdFVmnRiEF5hdWLa5JAJzHfN9/QDNsqlupTMS
-----END CERTIFICATE-----
                    """.trimIndent().toByteArray(),
                ),
            ) as X509Certificate
    }
}
