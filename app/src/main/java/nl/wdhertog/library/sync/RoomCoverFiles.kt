package nl.wdhertog.library.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.sync.SyncDao
import java.io.File

/** Eigen cover-foto's in de covermap van de app, voor [SyncSession]. */
class RoomCoverFiles(private val context: Context, private val dao: SyncDao) : CoverFiles {
    private val dir get() = CoverStore.coverDir(context)

    override suspend fun missing(): List<String> = dao.coverFiles().let { names ->
        withContext(Dispatchers.IO) { names.filter { CoverFiles.isValidName(it) && !File(dir, it).exists() } }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!CoverFiles.isValidName(name)) return@withContext null
        File(dir, name).takeIf(File::isFile)?.readBytes()
    }

    override suspend fun write(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        if (!CoverFiles.isValidName(name)) return@withContext
        dir.mkdirs()
        // Eerst een tijdelijk bestand: een half overgekomen foto komt nooit onder de echte naam te staan.
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(File(dir, name))) temp.delete()
    }
}
