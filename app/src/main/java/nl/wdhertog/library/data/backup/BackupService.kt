package nl.wdhertog.library.data.backup

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.wdhertog.library.BuildConfig
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Volledige back-up (database + eigen cover-foto's) als ZIP, en terugzetten.
 * Zie BackupFormat voor de indeling.
 */
class BackupService(
    private val context: Context,
    private val database: () -> LibraryDatabase,
    /** Sluit de database, zodat de bestanden vervangen kunnen worden. */
    private val closeDatabase: () -> Unit,
) {
    private val dbFile get() = context.getDatabasePath(DB_NAME)
    private val walFile get() = File(dbFile.path + "-wal")
    private val shmFile get() = File(dbFile.path + "-shm")
    private val coverDir get() = CoverStore.coverDir(context)

    /** Kopie van de huidige bibliotheek die bij terugzetten automatisch wordt gemaakt. */
    val safetyCopy: File get() = File(context.filesDir, BEFORE_RESTORE)

    /** Automatische kopieën in de app (vóór terugzetten en vóór samenvoegen), nieuwste eerst. */
    fun safetyCopies(): List<File> = listOf(BEFORE_RESTORE, BEFORE_MERGE).map { File(context.filesDir, it) }
        .filter(File::exists).sortedByDescending(File::lastModified)


    suspend fun createBackup(out: OutputStream): BackupManifest {
        val db = database()
        val books = db.bookDao().countBooks()
        val schemaVersion = schemaVersion()
        // Tijdens het kopiëren mag niemand schrijven: een schrijftransactie houdt verrijking en scans even tegen.
        // Binnen de transactie alleen bestanden; een databasevraag vanaf een andere thread zou blijven wachten.
        return db.withTransaction {
            withContext(Dispatchers.IO) {
                val covers = coverDir.listFiles { f -> BackupFormat.coverFileName(BackupFormat.COVERS_DIR + f.name) != null }.orEmpty()
                val manifest = BackupManifest(
                    schemaVersion = schemaVersion,
                    appVersion = BuildConfig.VERSION_NAME,
                    createdAt = Instant.now().toString(),
                    books = books,
                    covers = covers.size,
                )
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
                    zip.write(BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                    zip.closeEntry()
                    addFile(zip, BackupFormat.DATABASE, dbFile)
                    if (walFile.exists() && walFile.length() > 0) addFile(zip, BackupFormat.DATABASE_WAL, walFile)
                    covers.forEach { addFile(zip, BackupFormat.COVERS_DIR + it.name, it) }
                }
                manifest
            }
        }
    }

    /** Leest een gekozen back-up in een tijdelijk bestand en controleert of hij past. */
    suspend fun inspect(input: InputStream): Pair<File, BackupFormat.Check> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "terugzetten.zip")
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        val check = runCatching {
            ZipFile(temp).use { zip ->
                val manifest = zip.getEntry(BackupFormat.MANIFEST)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
                BackupFormat.check(manifest, zip.getEntry(BackupFormat.DATABASE) != null, schemaVersion())
            }
        }.getOrElse { BackupFormat.Check.Problem("Dit bestand is geen geldige back-up (geen ZIP).") }
        temp to check
    }

    /**
     * Vervangt de bibliotheek door de back-up in [zipFile]. Maakt eerst een veiligheidskopie.
     * Daarna moet de app opnieuw starten (zie restartApp).
     */
    suspend fun restore(zipFile: File) {
        safetyCopy.parentFile?.mkdirs()
        safetyCopy.outputStream().use { createBackup(it) }
        withContext(Dispatchers.IO) {
            closeDatabase()
            listOf(dbFile, walFile, shmFile).forEach { it.delete() }
            coverDir.listFiles()?.forEach { it.delete() }
            coverDir.mkdirs()
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().filter { BackupFormat.isKnownEntry(it.name) }.forEach { entry ->
                    val target = when (entry.name) {
                        BackupFormat.MANIFEST -> return@forEach
                        BackupFormat.DATABASE -> dbFile
                        BackupFormat.DATABASE_WAL -> walFile
                        else -> File(coverDir, BackupFormat.coverFileName(entry.name)!!)
                    }
                    zip.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                }
            }
            zipFile.delete()
            // De teruggezette bibliotheek kan van een andere telefoon komen: synchronisatie begint opnieuw.
            syncResetMarker(context).createNewFile()
            // En van een oudere versie: eenmalige onderhoudstaken (bijv. talen uit het ISBN) opnieuw doen.
            context.getSharedPreferences("onderhoud", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private suspend fun schemaVersion(): Int = withContext(Dispatchers.IO) { database().openHelper.readableDatabase.version }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        const val DB_NAME = "library.db"
        const val BEFORE_RESTORE = "backups/voor-terugzetten.zip"
        const val BEFORE_MERGE = "backups/voor-samenvoegen.zip"

        /** Bestaat dit bestand, dan wordt bij het openen van de database de synchronisatiestatus gewist. */
        fun syncResetMarker(context: Context) = File(context.filesDir, "sync-opnieuw")
    }
}
