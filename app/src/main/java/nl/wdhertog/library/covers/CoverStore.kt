package nl.wdhertog.library.covers

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zelf gefotografeerde covers, als JPEG in de app-opslag. In de database staat alleen de
 * bestandsnaam (Book.coverFile); elke nieuwe foto krijgt een nieuwe naam, zodat de
 * beeldcache nooit een oude versie laat zien.
 */
class CoverStore(context: Context) {
    private val dir = coverDir(context)

    suspend fun save(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.jpg"
        val scaled = scaleDown(bitmap)
        File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== bitmap) scaled.recycle()
        name
    }

    suspend fun delete(name: String?) {
        if (name == null) return
        withContext(Dispatchers.IO) { File(dir, name).delete() }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val factor = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt(), (bitmap.height * factor).roundToInt(), true)
    }

    companion object {
        private const val MAX_SIDE = 1200
        private const val JPEG_QUALITY = 85

        fun coverDir(context: Context) = File(context.filesDir, "covers")

        /** Wat de beeldlader nodig heeft: een URL van een bron, of een eigen foto (bestandsnaam). */
        fun model(context: Context, cover: String): Any =
            if (cover.contains("://")) cover else File(coverDir(context), cover)
    }
}
