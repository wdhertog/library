package nl.wdhertog.library.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Waar een telefoon te bereiken is. */
data class Endpoint(val host: String, val port: Int)

/**
 * Aanmelden en zoeken op het thuisnetwerk via NSD (mDNS), als `_bibliotheek._tcp` met het
 * telefoon-id in de TXT-gegevens. Alleen het id en de poort zijn zichtbaar; de rest gaat versleuteld.
 */
class Discovery(context: Context, private val scope: CoroutineScope) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    private val _found = MutableStateFlow<Map<String, Endpoint>>(emptyMap())

    /** Gevonden telefoons op id. */
    val found: StateFlow<Map<String, Endpoint>> = _found

    init {
        // Oudere Android-versies lossen maar één dienst tegelijk op.
        scope.launch { for (info in toResolve) resolve(info) }
    }

    fun register(port: Int, deviceId: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "Bibliotheek ${deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ID, deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "Aanmelden mislukt: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) { Log.w(TAG, "Zoeken mislukt: $error") }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    /** Wacht tot [deviceId] gevonden is, hooguit [timeoutMillis]. */
    suspend fun await(deviceId: String, timeoutMillis: Long): Endpoint? =
        withTimeoutOrNull(timeoutMillis) { _found.first { deviceId in it }[deviceId] }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo) {
        val resolved = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) { if (continuation.isActive) continuation.resume(null) }
                    override fun onServiceResolved(info: NsdServiceInfo) { if (continuation.isActive) continuation.resume(info) }
                })
            }
        } ?: return
        val id = resolved.attributes[ID]?.toString(Charsets.UTF_8) ?: return
        val host = resolved.host?.hostAddress ?: return
        _found.update { it + (id to Endpoint(host, resolved.port)) }
    }

    private companion object {
        const val SERVICE_TYPE = "_bibliotheek._tcp"
        const val ID = "id"
        const val TAG = "Discovery"
    }
}
