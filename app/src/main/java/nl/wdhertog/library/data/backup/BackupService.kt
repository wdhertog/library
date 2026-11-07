package nl.wdhertog.library.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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


    /** Alles als JSON (zie JsonExport): de gedeelde records zoals de sync ze kent, plus wat persoonlijk is. */
    suspend fun exportJson(out: OutputStream, books: nl.wdhertog.library.data.repository.BookRepository): Int {
        val db = database()
        val records = nl.wdhertog.library.data.sync.RoomSyncStore(db, books).snapshot()
        val allBooks = db.bookDao().getBooks()
        val text = JsonExport.write(
            records = records,
            books = allBooks,
            authors = db.authorDao().all(),
            sessions = db.readingDao().allSessions(),
            goals = db.readingDao().allGoals(),
            readingDays = db.readingDao().allDays(),
            schemaVersion = schemaVersion(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        withContext(Dispatchers.IO) { out.write(text.toByteArray()) }
        return allBooks.size
    }

    suspend fun createBackup(out: OutputStream): BackupManifest {
        // Eerst in de app: de database staat dan maar kort op slot, ook als [out] naar een trage (cloud)map gaat.
        val temp = File(context.cacheDir, "back-up-${System.nanoTime()}.zip")
        try {
            val manifest = temp.outputStream().use { writeBackup(it) }
            withContext(Dispatchers.IO) { temp.inputStream().use { it.copyTo(out) } }
            return manifest
        } finally {
            temp.delete()
        }
    }

    private suspend fun writeBackup(out: OutputStream): BackupManifest {
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
        // Eerst alles uitpakken naast de huidige bibliotheek en controleren. Gaat daarbij iets mis (kapotte
        // ZIP, vol geheugen, geen geldige database), dan blijft de huidige bibliotheek onaangeroerd.
        val staging = File(context.filesDir, STAGING_DIR)
        val maxVersion = schemaVersion()
        withContext(Dispatchers.IO) {
            staging.deleteRecursively()
            File(staging, COVERS).mkdirs()
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().filter { BackupFormat.isKnownEntry(it.name) }.forEach { entry ->
                    val target = when (entry.name) {
                        BackupFormat.MANIFEST -> return@forEach
                        BackupFormat.DATABASE -> File(staging, DB_NAME)
                        BackupFormat.DATABASE_WAL -> File(staging, "$DB_NAME-wal")
                        else -> File(staging, COVERS + "/" + BackupFormat.coverFileName(entry.name)!!)
                    }
                    zip.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                }
            }
            try {
                checkDatabase(File(staging, DB_NAME), maxVersion)
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw e
            }
        }
        safetyCopy.parentFile?.mkdirs()
        safetyCopy.outputStream().use { createBackup(it) }
        withContext(Dispatchers.IO) {
            closeDatabase()
            listOf(dbFile, walFile, shmFile).forEach { it.delete() }
            coverDir.deleteRecursively()
            // Hernoemen binnen de app-opslag: snel, en geen half gekopieerde bestanden.
            moveOrCopy(File(staging, DB_NAME), dbFile)
            File(staging, "$DB_NAME-wal").takeIf(File::exists)?.let { moveOrCopy(it, walFile) }
            moveOrCopy(File(staging, COVERS), coverDir)
            coverDir.mkdirs()
            staging.deleteRecursively()
            zipFile.delete()
            // De teruggezette bibliotheek kan van een andere telefoon komen: synchronisatie begint opnieuw.
            syncResetMarker(context).createNewFile()
            // En van een oudere versie: eenmalige onderhoudstaken (bijv. talen uit het ISBN) opnieuw doen.
            context.getSharedPreferences("onderhoud", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * Tags per boek-uuid uit een back-up, zonder iets terug te zetten (voor "Genres herstellen"). Pakt de
     * database uit naar een tijdelijke map en leest alleen.
     */
    suspend fun readTags(zipFile: File): Map<String, String?> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "back-up-lezen").apply { deleteRecursively(); mkdirs() }
        try {
            ZipFile(zipFile).use { zip ->
                for (name in listOf(BackupFormat.DATABASE, BackupFormat.DATABASE_WAL)) {
                    val entry = zip.getEntry(name) ?: continue
                    val target = File(dir, if (name == BackupFormat.DATABASE) DB_NAME else "$DB_NAME-wal")
                    zip.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                }
            }
            val file = File(dir, DB_NAME)
            if (!file.isFile) throw IllegalStateException("Geen database in de back-up")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.rawQuery("SELECT uuid, tags FROM book", null).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Een echte SQLite-database, heel, en niet van een nieuwere app-versie dan deze. */
    private fun checkDatabase(file: File, maxVersion: Int) {
        if (!file.isFile) throw IllegalStateException("Geen database in de back-up")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            val version = db.version
            if (version > maxVersion) throw IllegalStateException("Deze back-up komt van een nieuwere versie van de app")
            db.rawQuery("PRAGMA quick_check", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw IllegalStateException("De database in de back-up is beschadigd")
            }
        }
        // Openen kan een -shm achterlaten; die hoort niet mee.
        File(file.path + "-shm").delete()
    }

    private fun moveOrCopy(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
    }

    internal suspend fun schemaVersion(): Int = withContext(Dispatchers.IO) { database().openHelper.readableDatabase.version }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        const val DB_NAME = "library.db"
        const val BEFORE_RESTORE = "backups/voor-terugzetten.zip"
        const val BEFORE_MERGE = "backups/voor-samenvoegen.zip"
        private const val STAGING_DIR = "terugzetten"
        private const val COVERS = "covers"

        /** Bestaat dit bestand, dan wordt bij het openen van de database de synchronisatiestatus gewist. */
        fun syncResetMarker(context: Context) = File(context.filesDir, "sync-opnieuw")
    }
}
