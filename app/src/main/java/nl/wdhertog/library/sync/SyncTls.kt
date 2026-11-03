package nl.wdhertog.library.sync

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS tussen twee telefoons, zonder certificaatautoriteit:
 * - de verbinder accepteert alleen het certificaat met de verwachte vingerafdruk;
 * - de ontvanger eist een certificaat van de verbinder en geeft de vingerafdruk door aan
 *   [SyncSession], die bepaalt of dat een gekoppelde telefoon is.
 */
class SyncTls(private val identity: SyncIdentity) {

    fun serverSocket(port: Int = 0): SSLServerSocket =
        (context(AcceptAnyClient).serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply { needClientAuth = true }

    fun connect(host: String, port: Int, expectedFingerprint: String, timeoutMillis: Int = 10_000): SSLSocket {
        val plain = Socket().apply {
            connect(InetSocketAddress(host, port), timeoutMillis)
            soTimeout = timeoutMillis * 3
        }
        val socket = context(PinnedServer(expectedFingerprint)).socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket.startHandshake()
        return socket
    }

    private fun context(trust: X509ExtendedTrustManager) = SSLContext.getInstance("TLS").apply {
        init(arrayOf(OwnKey(identity.privateKey, identity.certificate)), arrayOf(trust), SecureRandom())
    }

    companion object {
        /** Vingerafdruk van het certificaat van de andere kant van een verbinding. */
        fun peerFingerprint(socket: SSLSocket): String = SyncIdentity.fingerprint(socket.session.peerCertificates.first())
    }

    private class OwnKey(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS.takeIf { keyType == null || keyType.startsWith("EC") }
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS.takeIf { keyType == null || keyType.startsWith("EC") }
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key

        private companion object { const val ALIAS = "sync" }
    }

    /** Verbinder: alleen de vastgepinde telefoon. */
    private class PinnedServer(private val fingerprint: String) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val actual = chain?.firstOrNull()?.let(SyncIdentity::fingerprint)
            if (actual != fingerprint) throw CertificateException("Onbekende telefoon")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    /** Ontvanger: elk certificaat mag de handdruk afmaken; [SyncSession] beslist daarna. */
    private object AcceptAnyClient : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Geen certificaat")
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
}
