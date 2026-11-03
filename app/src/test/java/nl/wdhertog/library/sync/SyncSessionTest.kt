package nl.wdhertog.library.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.data.sync.HybridClock
import nl.wdhertog.library.data.sync.InMemorySyncStore
import nl.wdhertog.library.data.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Het gesprek tussen twee telefoons over een verbinding (ontwerp §11, S3). */
class SyncSessionTest {

    private class MemoryPeers : PeerStore {
        val peers = mutableMapOf<String, KnownPeer>()
        override suspend fun all() = peers.values.toList()
        override suspend fun find(deviceId: String) = peers[deviceId]
        override suspend fun save(peer: KnownPeer) { peers[peer.deviceId] = peer }
        override suspend fun markReceived(deviceId: String, upTo: Long, at: Long) {
            peers[deviceId]?.let { peers[deviceId] = it.copy(receivedUpTo = upTo) }
        }
    }

    private class Phone(val id: String, val fingerprint: String) {
        val store = InMemorySyncStore()
        val peers = MemoryPeers()
        val session = SyncSession(SyncEngine(store, HybridClock(id) { 1_000L }) {}, peers, id, "Telefoon $id") { 1_000L }
    }

    private val a = Phone("a", "fp-a") // maakt verbinding
    private val b = Phone("b", "fp-b") // neemt aan

    /** Verbindt [a] met [b]; geeft beide uitkomsten (of de fout) terug. */
    private fun exchange(
        clientFingerprint: String = a.fingerprint,
        pending: PendingPairing? = null,
        pairing: PairingCode? = null,
    ): Pair<Result<SyncResult>, Result<SyncResult>> = runBlocking(Dispatchers.IO) {
        val toB = PipedOutputStream(); val bIn = PipedInputStream(toB, 1 shl 20)
        val toA = PipedOutputStream(); val aIn = PipedInputStream(toA, 1 shl 20)
        val server = async {
            runCatching { b.session.accept(bIn.bufferedReader(), toA.bufferedWriter(), clientFingerprint, pending) }
                .also { toA.close() }
        }
        val client = async {
            runCatching { a.session.connect(aIn.bufferedReader(), toB.bufferedWriter(), b.id, pairing?.let { it to a.fingerprint }) }
                .also { toB.close() }
        }
        client.await() to server.await()
    }

    private fun pairAB() {
        val secret = PairingCode.newSecret()
        val code = PairingCode(b.id, "Telefoon b", b.fingerprint, secret)
        val (client, server) = exchange(pending = PendingPairing(secret, 2_000L), pairing = PairingCode.decode(code.encode()))
        client.getOrThrow(); server.getOrThrow()
    }

    @Test
    fun `pairing code survives encoding`() {
        val code = PairingCode("id-1", "Willems telefoon", "AB:CD", PairingCode.newSecret())
        assertEquals(code, PairingCode.decode(code.encode()))
        assertNull(PairingCode.decode("https://example.org"))
        assertNull(PairingCode.decode("BIB1:rommel"))
    }

    @Test
    fun `pairing then syncing books both ways`() {
        a.store.edit("book", "b1", "title" to "Dictator")
        b.store.edit("book", "b2", "title" to "Het smelt")
        pairAB()
        assertEquals("fp-a", b.peers.peers["a"]?.fingerprint)
        assertEquals("fp-b", a.peers.peers["b"]?.fingerprint)
        assertEquals("Dictator", b.store.field("book", "b1", "title"))
        assertEquals("Het smelt", a.store.field("book", "b2", "title"))

        b.store.edit("book", "b2", "title" to "Het smelt (roman)")
        val (client, server) = exchange()
        assertEquals(1, client.getOrThrow().received)
        assertEquals(0, server.getOrThrow().received)
        assertEquals("Het smelt (roman)", a.store.field("book", "b2", "title"))
    }

    @Test
    fun `nothing is sent twice`() {
        a.store.edit("book", "b1", "title" to "Dictator")
        pairAB()
        val (client, server) = exchange()
        assertEquals(0, client.getOrThrow().received)
        assertEquals(0, server.getOrThrow().received)
    }

    @Test
    fun `unknown phone is refused and gets nothing`() {
        b.store.edit("book", "b2", "title" to "Het smelt")
        val (client, server) = exchange()
        assertThrows(SyncRefused::class.java) { client.getOrThrow() }
        assertThrows(SyncRefused::class.java) { server.getOrThrow() }
        assertNull(a.store.field("book", "b2", "title"))
    }

    @Test
    fun `wrong pairing secret or expired code is refused`() {
        val code = PairingCode(b.id, "b", b.fingerprint, "verkeerd")
        val wrong = exchange(pending = PendingPairing(PairingCode.newSecret(), 2_000L), pairing = code)
        assertThrows(SyncRefused::class.java) { wrong.second.getOrThrow() }
        val expired = exchange(pending = PendingPairing("verkeerd", 500L), pairing = code)
        assertThrows(SyncRefused::class.java) { expired.second.getOrThrow() }
        assertNull(b.peers.peers["a"])
    }

    @Test
    fun `paired phone with another certificate is refused`() {
        pairAB()
        val (_, server) = exchange(clientFingerprint = "fp-indringer")
        assertThrows(SyncRefused::class.java) { server.getOrThrow() }
    }

    @Test
    fun `snapshot for the first merge does not pair yet`() {
        b.store.edit("book", "b2", "title" to "Het smelt")
        val secret = PairingCode.newSecret()
        val code = PairingCode(b.id, "Telefoon b", b.fingerprint, secret)
        val snapshot = runBlocking(Dispatchers.IO) {
            val toB = PipedOutputStream(); val bIn = PipedInputStream(toB, 1 shl 20)
            val toA = PipedOutputStream(); val aIn = PipedInputStream(toA, 1 shl 20)
            val server = async {
                runCatching { b.session.accept(bIn.bufferedReader(), toA.bufferedWriter(), a.fingerprint, PendingPairing(secret, 2_000L)) }
                    .also { toA.close() }
            }
            val result = a.session.fetchSnapshot(aIn.bufferedReader(), toB.bufferedWriter(), code to a.fingerprint)
            toB.close()
            assertEquals(true, server.await().getOrThrow().snapshot)
            result
        }
        assertEquals(listOf("b2"), snapshot.records.map { it.uuid })
        assertEquals(false, snapshot.hasOtherPeers)
        assertNull(b.peers.peers["a"])
        // Daarna koppelen met hetzelfde bewijs; de wijziging van b komt nog steeds mee.
        val (client, server) = exchange(pending = PendingPairing(secret, 2_000L), pairing = code)
        assertEquals(1, client.getOrThrow().received)
        assertEquals(true, server.getOrThrow().paired)
    }
}
