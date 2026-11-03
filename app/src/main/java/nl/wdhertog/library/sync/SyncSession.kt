package nl.wdhertog.library.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import nl.wdhertog.library.data.sync.HybridClock
import nl.wdhertog.library.data.sync.SyncChange
import nl.wdhertog.library.data.sync.SyncEngine
import nl.wdhertog.library.data.sync.SnapshotRecord
import java.io.BufferedReader
import java.io.BufferedWriter
import java.security.MessageDigest
import java.util.Base64

/** Een gekoppelde telefoon zoals de sessie die nodig heeft. */
data class KnownPeer(val deviceId: String, val name: String, val fingerprint: String, val receivedUpTo: Long)

/** Opslag van gekoppelde telefoons (sync_peer in de app, in het geheugen in tests). */
interface PeerStore {
    suspend fun all(): List<KnownPeer>
    suspend fun find(deviceId: String): KnownPeer?
    suspend fun save(peer: KnownPeer)
    suspend fun markReceived(deviceId: String, upTo: Long, at: Long)
}

/**
 * Eigen cover-foto's: de naam staat in het boek (en gaat met de gewone uitwisseling mee), het bestand
 * gaat aan het eind van een sessie mee als de ander het nog niet heeft.
 */
interface CoverFiles {
    /** Namen die in boeken staan, maar waarvan het bestand hier ontbreekt. */
    suspend fun missing(): List<String>
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, data: ByteArray)

    companion object {
        private val validName = Regex("[A-Za-z0-9-]{1,64}\\.jpg")

        /** Alleen gewone namen: nooit een pad, zodat de ander niet buiten de covermap kan schrijven. */
        fun isValidName(name: String) = validName.matches(name)

        const val MAX_BYTES = 8 shl 20

        val NONE = object : CoverFiles {
            override suspend fun missing() = emptyList<String>()
            override suspend fun read(name: String): ByteArray? = null
            override suspend fun write(name: String, data: ByteArray) = Unit
        }
    }
}

/** Een lopende koppeling op deze telefoon: welk geheim de ander moet bewijzen, en hoe lang nog. */
data class PendingPairing(val secret: String, val expiresAt: Long)

class SyncRefused(message: String) : Exception(message)

data class SyncResult(
    val peerName: String,
    val received: Int,
    val sent: Int,
    /** Deze sessie was de koppeling zelf. */
    val paired: Boolean = false,
    /** Alleen het overzicht voor het eerste samenvoegen is verstuurd. */
    val snapshot: Boolean = false,
    val coversReceived: Int = 0,
)

/** De bibliotheek van de andere telefoon, voor het eerste samenvoegen (FirstMerge). */
@Serializable
data class RemoteSnapshot(
    val deviceId: String,
    val name: String,
    /** Klokstand na het klaarzetten van al zijn wijzigingen. */
    val clock: String,
    /** Is de ander al met andere telefoons gekoppeld (dan hoort hij bij een groep)? */
    val hasOtherPeers: Boolean,
    val records: List<SnapshotRecord>,
)

/**
 * Het gesprek tussen twee telefoons over één (al versleutelde) verbinding, als JSON-regels:
 *
 * 1. de verbinder stuurt `hello` (wie hij is, tot waar hij de ander al heeft, eventueel een koppelbewijs);
 * 2. de ontvanger antwoordt `hello` (of `error`) en stuurt zijn wijzigingen;
 * 3. de verbinder stuurt zijn wijzigingen; de ontvanger past ze toe en sluit af met `done`.
 *
 * De ontvanger controleert dat het certificaat van de verbinder bij een gekoppelde telefoon hoort,
 * of dat er een geldig koppelbewijs bij zit. De verbinder heeft het certificaat van de ontvanger
 * al op TLS-niveau vastgepind.
 */
class SyncSession(
    private val engine: SyncEngine,
    private val peers: PeerStore,
    private val deviceId: String,
    private val deviceName: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val covers: CoverFiles = CoverFiles.NONE,
) {
    @Serializable
    private data class Message(
        val type: String,
        val deviceId: String? = null,
        val name: String? = null,
        /** Tot welk volgnummer uit de outbox van de ander ik alles heb. */
        val since: Long? = null,
        val proof: String? = null,
        val items: List<Item>? = null,
        val message: String? = null,
        /** "snapshot": alleen het overzicht ophalen, voor het eerste samenvoegen. */
        val mode: String? = null,
        val snapshot: RemoteSnapshot? = null,
        /** Covers: gevraagde namen, of één bestand (naam en Base64). */
        val files: List<String>? = null,
        val file: String? = null,
        val data: String? = null,
    )

    @Serializable
    private data class Item(val seq: Long, val entity: String, val uuid: String, val fields: JsonObject, val hlc: String, val deleted: Boolean)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private companion object {
        const val SNAPSHOT = "snapshot"
    }

    /**
     * Kant van de telefoon die verbinding maakt met [peerId]. [pairing] alleen bij de eerste keer: de
     * gescande code en de vingerafdruk van het eigen certificaat.
     */
    suspend fun connect(
        reader: BufferedReader,
        writer: BufferedWriter,
        peerId: String,
        pairing: Pair<PairingCode, String>? = null,
    ): SyncResult {
        engine.prepareOutgoing()
        val known = pairing?.let { KnownPeer(it.first.deviceId, it.first.name, it.first.fingerprint, 0) }
        val since = peers.find(peerId)?.receivedUpTo ?: 0
        send(
            writer,
            Message(
                "hello", deviceId, deviceName, since,
                proof = pairing?.let { (code, ownFingerprint) -> PairingCode.proof(code.secret, deviceId, ownFingerprint) },
            ),
        )
        val hello = receive(reader)
        if (hello.type == "error") throw SyncRefused(hello.message ?: "Geweigerd")
        if (hello.deviceId != peerId) throw SyncRefused("Andere telefoon dan verwacht")
        val peer = known ?: peers.find(peerId) ?: throw SyncRefused("Niet gekoppeld")
        if (known != null) peers.save(peer.copy(name = hello.name ?: peer.name))

        // Vóór het toepassen bepalen, anders gaat wat de ander net stuurde meteen terug.
        val outgoing = changesFor(peerId, hello.since ?: 0)
        val incoming = receive(reader).items.orEmpty().map { it.toChange() }
        engine.applyIncoming(incoming)
        peers.markReceived(peerId, incoming.maxOfOrNull { it.seq } ?: since, now())

        send(writer, Message("changes", items = outgoing.map { it.toItem() }))
        val done = receive(reader)
        if (done.type != "done") throw SyncRefused(done.message ?: "Onverwacht antwoord")
        val coversReceived = requestCovers(reader, writer)
        serveCovers(reader, writer)
        return SyncResult(hello.name ?: peer.name, received = incoming.size, sent = outgoing.size, paired = known != null, coversReceived = coversReceived)
    }

    /**
     * Het overzicht van de ander ophalen (eerste samenvoegen). De ander slaat deze telefoon dan nog niet
     * op als gekoppeld; dat gebeurt bij de uitwisseling daarna, met hetzelfde koppelbewijs.
     */
    suspend fun fetchSnapshot(reader: BufferedReader, writer: BufferedWriter, pairing: Pair<PairingCode, String>): RemoteSnapshot {
        val (code, ownFingerprint) = pairing
        send(writer, Message("hello", deviceId, deviceName, 0, proof = PairingCode.proof(code.secret, deviceId, ownFingerprint), mode = SNAPSHOT))
        val hello = receive(reader)
        if (hello.type == "error") throw SyncRefused(hello.message ?: "Geweigerd")
        if (hello.deviceId != code.deviceId) throw SyncRefused("Andere telefoon dan verwacht")
        return receive(reader).snapshot ?: throw SyncRefused("Geen overzicht ontvangen")
    }

    /**
     * Kant van de telefoon die de verbinding aanneemt. [clientFingerprint] komt uit de TLS-verbinding;
     * [pending] is er alleen zolang deze telefoon een koppelcode laat zien.
     */
    suspend fun accept(reader: BufferedReader, writer: BufferedWriter, clientFingerprint: String, pending: PendingPairing?): SyncResult {
        val hello = receive(reader)
        val clientId = hello.deviceId ?: return refuse(writer, "Onbekende telefoon")
        val existing = peers.find(clientId)
        var paired = false
        val snapshotOnly = hello.mode == SNAPSHOT
        val peer = when {
            existing != null && existing.fingerprint == clientFingerprint -> existing
            hello.proof != null && pending != null && now() < pending.expiresAt &&
                constantTimeEquals(hello.proof, PairingCode.proof(pending.secret, clientId, clientFingerprint)) ->
                KnownPeer(clientId, hello.name ?: "Telefoon", clientFingerprint, 0).also {
                    if (!snapshotOnly) { peers.save(it); paired = true }
                }
            else -> return refuse(writer, "Deze telefoon is niet gekoppeld")
        }

        if (snapshotOnly) {
            // Eerst alles klaarzetten: wat de ander bij het samenvoegen laat winnen, krijgt dan een latere tijd.
            engine.prepareOutgoing()
            send(writer, Message("hello", deviceId, deviceName))
            val records = engine.snapshot()
            val others = peers.all().any { it.deviceId != clientId }
            send(writer, Message("snapshot", snapshot = RemoteSnapshot(deviceId, deviceName, engine.clockNow(), others, records)))
            return SyncResult(hello.name ?: peer.name, received = 0, sent = records.size, snapshot = true)
        }

        engine.prepareOutgoing()
        send(writer, Message("hello", deviceId, deviceName, since = peer.receivedUpTo))
        val outgoing = changesFor(clientId, hello.since ?: 0)
        send(writer, Message("changes", items = outgoing.map { it.toItem() }))

        val incoming = receive(reader).items.orEmpty().map { it.toChange() }
        engine.applyIncoming(incoming)
        peers.markReceived(clientId, incoming.maxOfOrNull { it.seq } ?: peer.receivedUpTo, now())
        send(writer, Message("done"))
        serveCovers(reader, writer)
        val coversReceived = requestCovers(reader, writer)
        return SyncResult(hello.name ?: peer.name, received = incoming.size, sent = outgoing.size, paired = paired, coversReceived = coversReceived)
    }

    /** Wat de ander nog niet heeft; wat van hemzelf komt (doorgegeven via een derde) hoeft niet terug. */
    private suspend fun changesFor(peerId: String, since: Long) =
        engine.changesAfter(since).filter { HybridClock.Timestamp.parse(it.hlc).deviceId != peerId }

    /** Vraagt de ontbrekende covers; geeft het aantal ontvangen bestanden. */
    private suspend fun requestCovers(reader: BufferedReader, writer: BufferedWriter): Int {
        val wanted = covers.missing().filter(CoverFiles::isValidName).toSet()
        send(writer, Message("need", files = wanted.toList()))
        var count = 0
        while (true) {
            val message = receive(reader)
            if (message.type == "end") return count
            val name = message.file ?: continue
            if (message.type != "file" || name !in wanted) continue
            val bytes = runCatching { Base64.getDecoder().decode(message.data.orEmpty()) }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > CoverFiles.MAX_BYTES) continue
            covers.write(name, bytes)
            count++
        }
    }

    /** Stuurt de covers die de ander vraagt en die hier zijn. */
    private suspend fun serveCovers(reader: BufferedReader, writer: BufferedWriter) {
        val need = receive(reader)
        need.files.orEmpty().filter(CoverFiles::isValidName).distinct().forEach { name ->
            covers.read(name)?.takeIf { it.size <= CoverFiles.MAX_BYTES }?.let {
                send(writer, Message("file", file = name, data = Base64.getEncoder().encodeToString(it)))
            }
        }
        send(writer, Message("end"))
    }

    /** Deze telefoon is al met een andere bezig; de verbinder probeert het later opnieuw. */
    fun refuseBusy(writer: BufferedWriter) = send(writer, Message("error", message = "Even bezig met een andere telefoon, probeer het zo opnieuw"))

    private fun refuse(writer: BufferedWriter, message: String): Nothing {
        send(writer, Message("error", message = message))
        throw SyncRefused(message)
    }

    private fun send(writer: BufferedWriter, message: Message) {
        writer.write(json.encodeToString(Message.serializer(), message))
        writer.newLine()
        writer.flush()
    }

    private fun receive(reader: BufferedReader): Message =
        json.decodeFromString(Message.serializer(), reader.readLine() ?: throw SyncRefused("Verbinding verbroken"))

    private fun SyncChange.toItem() = Item(seq, entity, uuid, fields, hlc, deleted)
    private fun Item.toChange() = SyncChange(seq, entity, uuid, fields, hlc, deleted)

    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
