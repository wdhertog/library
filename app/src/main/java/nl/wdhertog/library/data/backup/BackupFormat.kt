package nl.wdhertog.library.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Beschrijving in elke back-up (manifest.json). */
@Serializable
data class BackupManifest(
    val app: String = APP_ID,
    val format: Int = FORMAT,
    /** Databaseversie (Room); een oudere wordt bij het openen bijgewerkt, een nieuwere niet. */
    val schemaVersion: Int,
    val appVersion: String,
    /** ISO-8601. */
    val createdAt: String,
    val books: Int,
    val covers: Int,
) {
    companion object {
        const val APP_ID = "nl.wdhertog.library"
        const val FORMAT = 1
    }
}

/**
 * Indeling van het ZIP-bestand. Alleen deze namen worden bij terugzetten gelezen; zo kan een
 * (kwaadwillend of beschadigd) bestand nooit buiten de eigen mappen schrijven.
 */
object BackupFormat {
    const val MANIFEST = "manifest.json"
    const val DATABASE = "database/library.db"
    const val DATABASE_WAL = "database/library.db-wal"
    const val COVERS_DIR = "covers/"

    private val coverName = Regex("""[A-Za-z0-9-]{1,64}\.jpg""")
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Bestandsnaam van een cover-item, of null als het geen geldige cover is. */
    fun coverFileName(entryName: String): String? =
        entryName.removePrefix(COVERS_DIR).takeIf { entryName.startsWith(COVERS_DIR) && coverName.matches(it) }

    fun isKnownEntry(entryName: String): Boolean =
        entryName == MANIFEST || entryName == DATABASE || entryName == DATABASE_WAL || coverFileName(entryName) != null

    sealed interface Check {
        data class Ok(val manifest: BackupManifest) : Check
        data class Problem(val message: String) : Check
    }

    /** Kan deze back-up in deze app worden teruggezet? */
    fun check(manifestJson: String?, hasDatabase: Boolean, currentSchema: Int): Check {
        val manifest = manifestJson?.let { runCatching { json.decodeFromString<BackupManifest>(it) }.getOrNull() }
            ?: return Check.Problem("Dit is geen back-up van de Bibliotheek-app.")
        return when {
            manifest.app != BackupManifest.APP_ID -> Check.Problem("Dit is geen back-up van de Bibliotheek-app.")
            !hasDatabase -> Check.Problem("De back-up is onvolledig: de database ontbreekt.")
            manifest.format > BackupManifest.FORMAT || manifest.schemaVersion > currentSchema ->
                Check.Problem("Deze back-up komt uit een nieuwere versie van de app (${manifest.appVersion}). Werk de app eerst bij.")
            else -> Check.Ok(manifest)
        }
    }
}
