package nl.wdhertog.library.sync

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.wdhertog.library.BuildConfig
import nl.wdhertog.library.data.sync.SyncEngine
import nl.wdhertog.library.data.sync.SyncRepository
import nl.wdhertog.library.data.sync.SyncStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Busy(val text: String) : SyncStatus
    data class Done(val text: String) : SyncStatus
    data class Failed(val text: String) : SyncStatus
}

sealed interface PairingState {
    /** Deze telefoon laat een code zien en wacht op de andere. */
    data class Showing(val code: PairingCode) : PairingState
    data class Paired(val name: String) : PairingState
}

/**
 * Synchroniseren op het thuisnetwerk (ontwerp §11, S3). Zolang de app in beeld is en er een gekoppelde
 * telefoon is (of er gekoppeld wordt): luisteren, zich aanmelden en zoeken. Eén sessie tegelijk.
 *
 * Automatisch synchroniseren zodra de ander gevonden is, doet alleen de telefoon met het kleinste id,
 * zodat ze niet tegelijk bij elkaar aankloppen. "Nu synchroniseren" kan altijd.
 */
class SyncManager(
    private val context: Context,
    private val repo: SyncRepository,
    private val store: () -> SyncStore,
    private val scope: CoroutineScope,
    private val inBackground: () -> Boolean,
) {
    private val identity by lazy { SyncIdentity.androidKeyStore() }
    private val tls by lazy { SyncTls(identity) }
    private val discovery by lazy { Discovery(context, scope) }
    private val sessionLock = Mutex()
    private val stateLock = Mutex()
    private var session: SyncSession? = null
    private var server: SSLServerSocket? = null
    private var serverJob: Job? = null
    private var autoJob: Job? = null
    private var stopJob: Job? = null
    @Volatile private var pending: PendingPairing? = null
    private val lastKnown = ConcurrentHashMap<String, Endpoint>()
    private val lastAuto = ConcurrentHashMap<String, Long>()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    private val _pairing = MutableStateFlow<PairingState?>(null)
    val pairing: StateFlow<PairingState?> = _pairing

    val deviceName: String by lazy {
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf(String::isNotBlank) ?: Build.MODEL
        // Testversies naast elkaar op één telefoon uit elkaar houden.
        if (BuildConfig.APPLICATION_ID == RELEASE_ID) name else "$name (${BuildConfig.APPLICATION_ID.removePrefix("$RELEASE_ID.")})"
    }

    /** De app komt in beeld. */
    fun onForeground() {
        stopJob?.cancel()
        scope.launch { if (repo.peers().isNotEmpty()) startListening() }
    }

    /**
     * De app gaat uit beeld. Met de achtergronddienst blijft hij bereikbaar; anders stoppen, na even
     * wachten (draaien van het scherm telt ook als uit beeld gaan).
     */
    fun onBackground() {
        stopJob?.cancel()
        if (inBackground() && server != null) return
        stopJob = scope.launch {
            delay(5_000)
            if (_pairing.value !is PairingState.Showing) stopListening()
        }
    }

    /** De instelling voor de achtergrond is veranderd (vanuit het scherm, dus in beeld). */
    fun backgroundSettingChanged() {
        if (!inBackground()) SyncService.stop(context) else if (server != null) SyncService.start(context)
    }

    /** Na ontkoppelen: zonder gekoppelde telefoons hoeft er niet meer geluisterd te worden. */
    suspend fun unpair(deviceId: String) {
        repo.unpair(deviceId)
        if (repo.peers().isEmpty() && _pairing.value !is PairingState.Showing) stopListening()
    }

    suspend fun startPairing(): PairingCode {
        repo.enable()
        val port = startListening()
        val secret = PairingCode.newSecret()
        pending = PendingPairing(secret, System.currentTimeMillis() + PAIRING_VALID_MILLIS)
        val code = PairingCode(repo.deviceId(), deviceName, withContext(Dispatchers.IO) { identity.fingerprint }, secret, localAddresses(), port)
        _pairing.value = PairingState.Showing(code)
        return code
    }

    fun cancelPairing() {
        pending = null
        _pairing.value = null
    }

    /** Koppelen met de telefoon die [code] laat zien; meteen de eerste uitwisseling. */
    suspend fun pairWith(code: PairingCode): Result<SyncResult> = runCatching {
        if (code.deviceId == repo.deviceId()) throw SyncRefused("Dit is de code van deze telefoon zelf")
        repo.enable()
        startListening()
        code.hosts.forEach { lastKnown[code.deviceId] = Endpoint(it, code.port) }
        val candidates = code.hosts.map { Endpoint(it, code.port) } +
            listOfNotNull(discovery.found.value[code.deviceId] ?: discovery.await(code.deviceId, 5_000))
        sessionLock.withLock {
            exchange(code.name, code.deviceId, code.fingerprint, candidates.distinct()) { reader, writer ->
                session().connect(reader, writer, code.deviceId, code to identity.fingerprint)
            }
        }
    }.also { result ->
        result.onSuccess { _pairing.value = PairingState.Paired(it.peerName) }
    }

    /** Met alle gekoppelde telefoons die thuis te vinden zijn. */
    suspend fun syncNow() {
        startListening()
        val peers = repo.peers()
        if (peers.isEmpty()) return
        val messages = sessionLock.withLock {
            peers.map { peer ->
                val endpoint = discovery.found.value[peer.deviceId] ?: discovery.await(peer.deviceId, 5_000) ?: lastKnown[peer.deviceId]
                if (endpoint == null) {
                    "${peer.name} niet gevonden op dit netwerk"
                } else {
                    runCatching {
                        exchange(peer.name, peer.deviceId, peer.certificateFingerprint, listOf(endpoint)) { reader, writer ->
                            session().connect(reader, writer, peer.deviceId)
                        }
                    }.fold(::describe) { "${peer.name}: ${it.message ?: "mislukt"}" }
                }
            }
        }
        _status.value = SyncStatus.Done(messages.joinToString("\n"))
    }

    private suspend fun exchange(
        name: String,
        peerId: String,
        fingerprint: String,
        endpoints: List<Endpoint>,
        talk: suspend (BufferedReader, BufferedWriter) -> SyncResult,
    ): SyncResult {
        _status.value = SyncStatus.Busy("Synchroniseren met $name…")
        var lastError: Throwable = SyncRefused("$name niet gevonden op dit netwerk")
        for (endpoint in endpoints) {
            val socket = runCatching { withContext(Dispatchers.IO) { tls.connect(endpoint.host, endpoint.port, fingerprint) } }
                .getOrElse { lastError = it; null } ?: continue
            lastKnown[peerId] = endpoint
            return try {
                withContext(Dispatchers.IO) { socket.use { talk(it.inputStream.bufferedReader(), it.outputStream.bufferedWriter()) } }
                    .also { _status.value = SyncStatus.Done(describe(it)) }
            } catch (e: Exception) {
                _status.value = SyncStatus.Failed(e.message ?: "Mislukt")
                throw e
            }
        }
        _status.value = SyncStatus.Failed(lastError.message ?: "Mislukt")
        throw lastError
    }

    private suspend fun session(): SyncSession = session ?: SyncSession(
        SyncEngine(store(), repo.clock(), repo::saveClock),
        repo.peerStore,
        repo.deviceId(),
        deviceName,
    ).also { session = it }

    /** Luisteren, aanmelden en zoeken; geeft de poort. */
    private suspend fun startListening(): Int = stateLock.withLock {
        server?.let { return it.localPort }
        // Starten kan alleen in beeld; daarom nu al, en niet pas bij het uit beeld gaan.
        if (inBackground()) SyncService.start(context)
        val socket = withContext(Dispatchers.IO) { tls.serverSocket() }
        server = socket
        discovery.register(socket.localPort, repo.deviceId())
        discovery.start()
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { socket.accept() as SSLSocket }.getOrNull() ?: break
                launch { serve(client) }
            }
        }
        autoJob = scope.launch { discovery.found.collect { autoSync(it.keys) } }
        socket.localPort
    }

    private suspend fun stopListening() = stateLock.withLock {
        SyncService.stop(context)
        autoJob?.cancel()
        discovery.stop()
        discovery.unregister()
        runCatching { server?.close() }
        serverJob?.cancel()
        server = null
    }

    private suspend fun autoSync(found: Set<String>) {
        val own = repo.deviceId()
        val now = System.currentTimeMillis()
        for (peer in repo.peers()) {
            if (peer.deviceId !in found || own > peer.deviceId) continue
            if (now - (lastAuto[peer.deviceId] ?: 0) < AUTO_INTERVAL_MILLIS) continue
            if (!sessionLock.tryLock()) return
            lastAuto[peer.deviceId] = now
            try {
                runCatching {
                    exchange(peer.name, peer.deviceId, peer.certificateFingerprint, listOfNotNull(discovery.found.value[peer.deviceId])) { r, w ->
                        session().connect(r, w, peer.deviceId)
                    }
                }.onFailure { Log.i(TAG, "Automatisch synchroniseren mislukt: ${it.message}") }
            } finally {
                sessionLock.unlock()
            }
        }
    }

    private suspend fun serve(socket: SSLSocket) = runCatching {
        socket.use {
            it.soTimeout = 30_000
            it.startHandshake()
            val fingerprint = SyncTls.peerFingerprint(it)
            val reader = it.inputStream.bufferedReader()
            val writer = it.outputStream.bufferedWriter()
            if (!sessionLock.tryLock()) {
                session().refuseBusy(writer)
                return@use
            }
            try {
                _status.value = SyncStatus.Busy("Synchroniseren…")
                val result = session().accept(reader, writer, fingerprint, pending)
                if (result.paired) {
                    pending = null
                    _pairing.value = PairingState.Paired(result.peerName)
                }
                _status.value = SyncStatus.Done(describe(result))
            } finally {
                sessionLock.unlock()
            }
        }
    }.onFailure {
        Log.i(TAG, "Inkomende verbinding: ${it.message}")
        if (it is SyncRefused) _status.value = SyncStatus.Failed(it.message ?: "Geweigerd")
    }

    private fun describe(result: SyncResult) =
        "${result.peerName}: ${result.received} ontvangen, ${result.sent} verstuurd"

    private fun localAddresses(): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses.orEmpty()
            .map { it.address }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    }

    private companion object {
        const val TAG = "SyncManager"
        const val RELEASE_ID = "nl.wdhertog.library"
        const val PAIRING_VALID_MILLIS = 10 * 60_000L
        const val AUTO_INTERVAL_MILLIS = 2 * 60_000L
    }
}
