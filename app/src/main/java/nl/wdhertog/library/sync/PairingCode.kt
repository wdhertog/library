package nl.wdhertog.library.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wat de ene telefoon de andere laat scannen (QR-code) of plakken: wie hij is, de vingerafdruk van
 * zijn certificaat (zodat de ander alleen met hem praat) en een eenmalig geheim waarmee de ander
 * bewijst dat hij de code echt gezien heeft.
 */
@Serializable
data class PairingCode(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
    val secret: String,
    /** Adressen van de telefoon die de code toont, zodat koppelen niet op het zoeken hoeft te wachten. */
    val hosts: List<String> = emptyList(),
    val port: Int = 0,
) {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(serializer(), this).toByteArray())

    companion object {
        private const val PREFIX = "BIB1:"

        fun decode(text: String): PairingCode? = runCatching {
            val payload = text.trim().removePrefix(PREFIX).takeIf { text.trim().startsWith(PREFIX) } ?: return null
            Json.decodeFromString(serializer(), String(Base64.getUrlDecoder().decode(payload)))
        }.getOrNull()

        fun newSecret(): String = ByteArray(16).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        /** Bewijs dat de koppelende telefoon het geheim kent, gebonden aan zijn eigen id en certificaat. */
        fun proof(secret: String, deviceId: String, fingerprint: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$deviceId|$fingerprint".toByteArray()))
        }
    }
}
