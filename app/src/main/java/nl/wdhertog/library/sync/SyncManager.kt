package nl.wdhertog.library.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.wdhertog.library.BuildConfig
import nl.wdhertog.library.data.sync.FirstMerge
import nl.wdhertog.library.data.sync.MergePlan
import nl.wdhertog.library.data.sync.SnapshotRecord
import nl.wdhertog.library.data.sync.SyncEngine
import nl.wdhertog.library.data.sync.SyncSchema
import nl.wdhertog.library.data.sync.SyncRepository
import nl.wdhertog.library.data.sync.SyncStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.Inet4Address
import java.security.MessageDigest
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

    /** Beide telefoons hebben boeken: kiezen welke hetzelfde zijn (alleen op de telefoon die scant). */
    data class Review(val peerName: String, val plan: MergePlan) : PairingState
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
    /** Kopie van de bibliotheek vóór het eerste samenvoegen. */
    private val backup: suspend () -> Unit,
    private val covers: () -> CoverFiles,
) {
    private val storeInstance by lazy { store() }
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var visible = false
    /** Het netwerk waarop de koppelcode getoond werd. */
    @Volatile private var pairingNetwork: String? = null
    private var networkWatched = false
    private var engine: SyncEngine? = null
    private var editJob: Job? = null
    private var periodicJob: Job? = null

    /** Wat er tussen het controlescherm en het samenvoegen bewaard moet blijven. */
    private class PendingMerge(val code: PairingCode, val remote: RemoteSnapshot, val local: List<SnapshotRecord>, val plan: MergePlan)
    @Volatile private var pendingMerge: PendingMerge? = null

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
        // Twee testapps naast elkaar op één telefoon (-PstagingVariant) uit elkaar houden.
        val variant = BuildConfig.APPLICATION_ID.removePrefix("$RELEASE_ID.").takeIf { it != RELEASE_ID && it.any(Char::isDigit) }
        if (variant == null) name else "$name ($variant)"
    }

    /** De app komt in beeld. */
    fun onForeground() {
        visible = true
        stopJob?.cancel()
        watchNetwork()
        scope.launch {
            if (repo.peers().isNotEmpty() && inBackground()) SyncService.start(context)
            evaluate()
        }
    }

    /** De app gaat uit beeld; even wachten, want draaien van het scherm telt ook als uit beeld gaan. */
    fun onBackground() {
        visible = false
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(5_000)
            evaluate()
        }
    }

    /** De achtergronddienst is (opnieuw) gestart, bijvoorbeeld na het opstarten van de telefoon. */
    fun onServiceStarted() {
        watchNetwork()
        scope.launch { evaluate() }
    }

    /** De instelling voor de achtergrond is veranderd (vanuit het scherm, dus in beeld). */
    fun backgroundSettingChanged() {
        scope.launch {
            if (inBackground() && repo.peers().isNotEmpty()) SyncService.start(context) else SyncService.stop(context)
        }
    }

    /** Na ontkoppelen: zonder gekoppelde telefoons hoeft er niet meer geluisterd te worden. */
    suspend fun unpair(deviceId: String) {
        repo.unpair(deviceId)
        if (repo.peers().isEmpty()) SyncService.stop(context)
        evaluate()
    }

    /**
     * Luisteren en aanmelden zolang er gekoppeld wordt, of er een gekoppelde telefoon is en de app in
     * beeld is of de telefoon op een thuisnetwerk zit (met de achtergronddienst).
     */
    private suspend fun evaluate() {
        val peers = repo.peers().isNotEmpty()
        val pairingBusy = _pairing.value is PairingState.Showing || pendingMerge != null
        val home = isHomeNetwork()
        val listen = pairingBusy || (peers && (visible || (home && inBackground())))
        if (listen) startListening() else stopListening()
        SyncService.update(context, home)
    }

    private fun watchNetwork() {
        if (networkWatched) return
        networkWatched = true
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { evaluate() } }
            override fun onLost(network: Network) { scope.launch { evaluate() } }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { scope.launch { evaluate() } }
        })
    }

    /**
     * Herkenning van het huidige wifi-netwerk zonder locatietoestemming (de netwerknaam vraagt die wel):
     * gateway, subnet en domein. Thuis is een netwerk waar al eens gesynchroniseerd is.
     */
    private fun networkKey(): String? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val link = connectivity.getLinkProperties(network) ?: return null
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val subnet = link.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { address ->
            val bytes = address.address.address
            val mask = if (address.prefixLength == 0) 0 else -1 shl (32 - address.prefixLength)
            val ip = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) } and mask
            "${ip ushr 24 and 0xff}.${ip ushr 16 and 0xff}.${ip ushr 8 and 0xff}.${ip and 0xff}/${address.prefixLength}"
        }
        if (gateway == null && subnet == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest("$gateway|$subnet|${link.domains.orEmpty()}".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun isHomeNetwork() = networkKey()?.let { it in prefs.getStringSet(KEY_HOME, emptySet()).orEmpty() } == true

    private fun rememberNetwork(key: String? = networkKey()) {
        key ?: return
        val known = prefs.getStringSet(KEY_HOME, emptySet()).orEmpty()
        if (key !in known) {
            prefs.edit().putStringSet(KEY_HOME, known + key).apply()
            scope.launch { evaluate() }
        }
    }

    suspend fun startPairing(): PairingCode {
        // Nu de app nog in beeld is: de koppeling komt rond als de andere telefoon scant, en dan
        // kan het zijn dat deze app net uit beeld is (en een dienst niet meer mag starten).
        if (inBackground()) SyncService.start(context)
        pairingNetwork = networkKey()
        repo.enable()
        // De andere telefoon kan hier velden laten winnen bij het samenvoegen.
        if (repo.peers().isEmpty()) runCatching { backup() }.onFailure { Log.w(TAG, "Kopie mislukt", it) }
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
        scope.launch {
            if (repo.peers().isEmpty()) SyncService.stop(context)
            evaluate()
        }
    }

    /**
     * Koppelen met de telefoon die [code] laat zien. Hebben beide telefoons boeken, dan eerst het
     * overzicht van de ander ophalen en samenvoegen (ontwerp §11, S4); met twijfel- of zekere paren
     * komt het controlescherm ([PairingState.Review]) en gaat het verder met [confirmMerge].
     * Geeft null terug zolang het controlescherm openstaat.
     */
    suspend fun pairWith(code: PairingCode): Result<SyncResult?> = runCatching {
        if (code.deviceId == repo.deviceId()) throw SyncRefused("Dit is de code van deze telefoon zelf")
        if (inBackground()) SyncService.start(context)
        repo.enable()
        startListening()
        code.hosts.forEach { lastKnown[code.deviceId] = Endpoint(it, code.port) }
        // Al eerder gekoppeld (bijv. de code opnieuw gescand): gewoon uitwisselen.
        if (repo.peerStore.find(code.deviceId) != null) return@runCatching finishPairing(code)

        val remote = sessionLock.withLock {
            exchange(code.name, code.deviceId, code.fingerprint, candidates(code)) { reader, writer ->
                session().fetchSnapshot(reader, writer, code to identity.fingerprint)
            }
        }
        val local = storeInstance.snapshot()
        val localBooks = local.any { it.entity == SyncSchema.BOOK }
        val remoteBooks = remote.records.any { it.entity == SyncSchema.BOOK }
        val localHasPeers = repo.peers().isNotEmpty()
        when {
            !localHasPeers -> Unit // deze telefoon voegt zich bij de ander
            !remoteBooks && !remote.hasOtherPeers -> return@runCatching finishPairing(code) // de ander is leeg en nieuw
            !remote.hasOtherPeers -> throw SyncRefused(
                "Deze telefoon is al gekoppeld met andere telefoons. Laat de code op deze telefoon zien en scan hem met ${code.name}.",
            )
            else -> throw SyncRefused("Beide telefoons zijn al met andere telefoons gekoppeld. Twee groepen samenvoegen kan niet.")
        }
        val plan = FirstMerge.plan(local, remote.records)
        val merge = PendingMerge(code, remote, local, plan)
        if (localBooks && remoteBooks && plan.pairs.isNotEmpty()) {
            pendingMerge = merge
            _status.value = SyncStatus.Idle
            _pairing.value = PairingState.Review(code.name, plan)
            null
        } else {
            merge(merge, accepted = emptySet())
        }
    }.also { result ->
        result.onSuccess { r -> r?.let { _pairing.value = PairingState.Paired(it.peerName) } }
    }

    /** Na het controlescherm: [accepted] zijn de lokale uuid's van de boeken die hetzelfde zijn. */
    suspend fun confirmMerge(accepted: Set<String>): Result<SyncResult> = runCatching {
        val merge = pendingMerge ?: throw SyncRefused("Er staat geen samenvoeging klaar")
        pendingMerge = null
        merge(merge, accepted)
    }.also { result ->
        result.onSuccess { _pairing.value = PairingState.Paired(it.peerName) }
        result.onFailure { _pairing.value = null }
    }

    fun cancelMerge() {
        pendingMerge = null
        _pairing.value = null
        scope.launch {
            if (repo.peers().isEmpty()) SyncService.stop(context)
            evaluate()
        }
    }

    private suspend fun merge(merge: PendingMerge, accepted: Set<String>): SyncResult {
        _status.value = SyncStatus.Busy("Samenvoegen…")
        if (merge.local.any { it.entity == SyncSchema.BOOK }) backup()
        val renames = FirstMerge.renames(merge.plan, accepted, merge.local, merge.remote.records)
        FirstMerge.apply(storeInstance, repo.clock(), renames, merge.local, merge.remote.records, merge.remote.clock)
        repo.saveClock(repo.clock().current.toString())
        return finishPairing(merge.code)
    }

    /** De eerste gewone uitwisseling; daarmee slaan beide telefoons elkaar op als gekoppeld. */
    private suspend fun finishPairing(code: PairingCode): SyncResult = sessionLock.withLock {
        exchange(code.name, code.deviceId, code.fingerprint, candidates(code)) { reader, writer ->
            session().connect(reader, writer, code.deviceId, code to identity.fingerprint)
        }
    }.also { afterPaired() }

    /**
     * Gekoppeld (aan deze of de andere kant): dit netwerk is thuis. De dienst draait al sinds het koppelen
     * begon. Is de app inmiddels uit beeld, dan geeft Android geen netwerkgegevens meer; dan geldt het
     * netwerk van toen de code werd getoond.
     */
    private fun afterPaired() = rememberNetwork(networkKey() ?: pairingNetwork)

    private suspend fun candidates(code: PairingCode) = (
        listOfNotNull(discovery.found.value[code.deviceId]) + code.hosts.map { Endpoint(it, code.port) } +
            listOfNotNull(if (code.hosts.isEmpty()) discovery.await(code.deviceId, 5_000) else null)
        ).distinct()

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

    private suspend fun <T> exchange(
        name: String,
        peerId: String,
        fingerprint: String,
        endpoints: List<Endpoint>,
        talk: suspend (BufferedReader, BufferedWriter) -> T,
    ): T {
        _status.value = SyncStatus.Busy("Synchroniseren met $name…")
        var lastError: Throwable = SyncRefused("$name niet gevonden op dit netwerk")
        for (endpoint in endpoints) {
            val socket = runCatching { withContext(Dispatchers.IO) { tls.connect(endpoint.host, endpoint.port, fingerprint) } }
                .getOrElse { lastError = it; null } ?: continue
            lastKnown[peerId] = endpoint
            return try {
                withContext(Dispatchers.IO) { socket.use { talk(it.inputStream.bufferedReader(), it.outputStream.bufferedWriter()) } }
                    .also { result ->
                        if (result is SyncResult) {
                            _status.value = SyncStatus.Done(describe(result))
                            if (!result.snapshot) rememberNetwork()
                        }
                    }
            } catch (e: Exception) {
                _status.value = SyncStatus.Failed(e.message ?: "Mislukt")
                throw e
            }
        }
        _status.value = SyncStatus.Failed(lastError.message ?: "Mislukt")
        throw lastError
    }

    private suspend fun engine(): SyncEngine =
        engine ?: SyncEngine(storeInstance, repo.clock(), repo::saveClock).also { engine = it }

    private suspend fun session(): SyncSession = session ?: SyncSession(
        engine(),
        repo.peerStore,
        repo.deviceId(),
        deviceName,
        covers = covers(),
    ).also { session = it }

    /** Luisteren, aanmelden en zoeken; geeft de poort. */
    private suspend fun startListening(): Int = stateLock.withLock {
        server?.let { return it.localPort }
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
        // Na een wijziging hier: als het even stil is doorgeven, en bij aanhoudende wijzigingen (importeren,
        // gegevens opzoeken) toch minstens elke minuut.
        editJob = scope.launch {
            var firstChangeAt = 0L
            repo.observeDirtyCount().collectLatest { count ->
                if (count == 0) return@collectLatest
                val now = System.currentTimeMillis()
                if (firstChangeAt == 0L) firstChangeAt = now
                delay(minOf(EDIT_QUIET_MILLIS, maxOf(0, firstChangeAt + EDIT_MAX_WAIT_MILLIS - now)))
                firstChangeAt = 0L
                // Los van deze flow: het klaarzetten leegt sync_dirty, en dat zou het versturen afbreken.
                scope.launch { pushChanges() }
            }
        }
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_MILLIS)
                lastAuto.clear()
                autoSync(discovery.found.value.keys)
            }
        }
        socket.localPort
    }

    private suspend fun stopListening() = stateLock.withLock {
        if (server == null) return@withLock
        editJob?.cancel()
        periodicJob?.cancel()
        autoJob?.cancel()
        discovery.stop()
        discovery.unregister()
        runCatching { server?.close() }
        serverJob?.cancel()
        server = null
    }

    /** Eigen wijzigingen klaarzetten en, als er iets is, naar de gevonden gekoppelde telefoons sturen. */
    private suspend fun pushChanges() {
        if (!sessionLock.tryLock()) return
        try {
            if (engine().prepareOutgoing() == 0) return
            val found = discovery.found.value
            for (peer in repo.peers()) {
                val endpoint = found[peer.deviceId] ?: continue
                runCatching {
                    exchange(peer.name, peer.deviceId, peer.certificateFingerprint, listOf(endpoint)) { r, w ->
                        session().connect(r, w, peer.deviceId)
                    }
                }.onFailure { Log.i(TAG, "Doorgeven mislukt: ${it.message}") }
            }
        } finally {
            sessionLock.unlock()
        }
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
                when {
                    result.snapshot -> {
                        // Het controlescherm op de andere telefoon kan even duren.
                        pending = pending?.copy(expiresAt = System.currentTimeMillis() + PAIRING_VALID_MILLIS)
                        _status.value = SyncStatus.Busy("${result.peerName} kiest welke boeken hetzelfde zijn…")
                    }
                    result.paired -> {
                        pending = null
                        _pairing.value = PairingState.Paired(result.peerName)
                        _status.value = SyncStatus.Done(describe(result))
                        afterPaired()
                    }
                    else -> {
                        _status.value = SyncStatus.Done(describe(result))
                        rememberNetwork()
                    }
                }
            } finally {
                sessionLock.unlock()
            }
        }
    }.onFailure {
        Log.i(TAG, "Inkomende verbinding: ${it.message}")
        if (it is SyncRefused) _status.value = SyncStatus.Failed(it.message ?: "Geweigerd")
    }

    private fun describe(result: SyncResult) =
        "${result.peerName}: ${result.received} ontvangen, ${result.sent} verstuurd" +
            if (result.coversReceived > 0) ", ${result.coversReceived} covers" else ""

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
        const val EDIT_QUIET_MILLIS = 5_000L
        const val EDIT_MAX_WAIT_MILLIS = 60_000L
        const val PERIODIC_MILLIS = 15 * 60_000L
        const val KEY_HOME = "home-networks"
    }
}
