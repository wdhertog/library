package nl.wdhertog.library.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.conscrypt.Conscrypt
import java.security.Security
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Versleutelde verbinding met vastgepinde certificaten, met echte EC-sleutels (gemaakt met keytool).
 * Zowel met de TLS van Java als met Conscrypt, de TLS die Android zelf gebruikt.
 */
@RunWith(Parameterized::class)
class SyncTlsTest(private val providerName: String) {

    @Before
    fun useProvider() {
        if (providerName == "Conscrypt") Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }

    @After
    fun restoreProvider() {
        Security.removeProvider("Conscrypt")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers() = listOf("SunJSSE", "Conscrypt")

        private lateinit var a: SyncIdentity
        private lateinit var b: SyncIdentity
        private lateinit var intruder: SyncIdentity

        private fun identity(dir: File, name: String): SyncIdentity {
            val file = File(dir, "$name.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=$name", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "testtest", "-keypass", "testtest",
            ).redirectErrorStream(true).start()
            check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
            val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "testtest".toCharArray()) } }
            return SyncIdentity(store.getKey(name, "testtest".toCharArray()) as PrivateKey, store.getCertificate(name) as X509Certificate)
        }

        @JvmStatic
        @BeforeClass
        fun keys() {
            val dir = kotlin.io.path.createTempDirectory("synctls").toFile()
            a = identity(dir, "a"); b = identity(dir, "b"); intruder = identity(dir, "c")
        }
    }

    /** [client] verbindt met [server]; geeft de vingerafdruk die de server zag, of de fout van de verbinder. */
    private fun handshake(client: SyncIdentity, server: SyncIdentity, pinned: String): Result<String> = runBlocking(Dispatchers.IO) {
        val listener = SyncTls(server).serverSocket()
        val seen = async {
            runCatching {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    SyncTls.peerFingerprint(socket).also { socket.outputStream.write(1); socket.outputStream.flush() }
                }
            }
        }
        val connected = runCatching {
            SyncTls(client).connect("127.0.0.1", listener.localPort, pinned).use { it.inputStream.read() }
        }
        val result = connected.map { seen.await().getOrThrow() }
        listener.close()
        seen.await()
        result
    }

    @Test
    fun `paired phones connect and see each other's certificate`() {
        assertEquals(a.fingerprint, handshake(a, b, pinned = b.fingerprint).getOrThrow())
    }

    @Test
    fun `connecting refuses a phone with another certificate`() {
        assertTrue(handshake(a, intruder, pinned = b.fingerprint).isFailure)
    }

    @Test
    fun `fingerprint is sha-256 in hex`() {
        assertTrue(a.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}
