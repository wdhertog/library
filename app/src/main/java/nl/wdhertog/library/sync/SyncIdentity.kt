package nl.wdhertog.library.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * De sleutel waarmee deze telefoon zich bij een andere bekendmaakt. Het certificaat is door
 * zichzelf ondertekend; vertrouwen komt alleen van de vingerafdruk die bij het koppelen is vastgelegd.
 */
class SyncIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String = fingerprint(certificate)

    companion object {
        private const val ALIAS = "bibliotheek-sync"

        fun fingerprint(certificate: java.security.cert.Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

        /**
         * De sleutel in de Android Keystore: verlaat de telefoon nooit en zit niet in back-ups.
         * Wordt de eerste keer gemaakt.
         */
        fun androidKeyStore(): SyncIdentity {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                            .setCertificateSubject(X500Principal("CN=Bibliotheek"))
                            .build(),
                    )
                    generateKeyPair()
                }
            }
            val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return SyncIdentity(entry.privateKey, entry.certificate as X509Certificate)
        }
    }
}
