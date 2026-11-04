package nl.wdhertog.library.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import nl.wdhertog.library.R
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.repository.cover
import java.util.Locale

/**
 * Een cover als gewone bitmap, voor plekken buiten Compose (deelafbeelding, widget):
 * de cover zelf, bijgesneden op [width] × [height], of net als in de app een gekleurd vlak met de titel.
 */
object CoverBitmap {

    suspend fun of(context: Context, book: Book, width: Int, height: Int, textSize: Float = height / 11f): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val loaded = book.cover?.let { load(context, it, width, height) }
        if (loaded != null) {
            canvas.drawBitmap(loaded, null, centerCrop(loaded, RectF(0f, 0f, width.toFloat(), height.toFloat())), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            drawPlaceholder(context, canvas, book.title, width, height, textSize)
        }
        return result
    }

    private fun drawPlaceholder(context: Context, canvas: Canvas, title: String, width: Int, height: Int, textSize: Float) {
        canvas.drawColor(placeholderColor(title).toArgb())
        val padding = width * 0.1f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = 0xF2FFFFFF.toInt()
            typeface = ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF
            textLocale = Locale.forLanguageTag("nl")
        }
        val text = title.trim()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - 2 * padding).toInt().coerceAtLeast(1))
            .setMaxLines(5)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .build()
        canvas.save()
        canvas.translate(padding, padding)
        layout.draw(canvas)
        canvas.restore()
        // Rug: een smalle donkere rand links, zoals in de app.
        canvas.drawRect(0f, 0f, width * 0.035f, height.toFloat(), Paint().apply { color = 0x2E000000 })
    }

    private fun centerCrop(bitmap: Bitmap, target: RectF): RectF {
        val scale = maxOf(target.width() / bitmap.width, target.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        return RectF(target.centerX() - w / 2, target.centerY() - h / 2, target.centerX() + w / 2, target.centerY() + h / 2)
    }

    /** Via Coil (en dus zijn cache); alleen gelezen, want de bitmap uit de cache mag niet gewijzigd of gerecycled worden. */
    private suspend fun load(context: Context, cover: String, width: Int, height: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(CoverStore.model(context, cover))
            .size(width * 2, height * 2)
            .allowHardware(false)
            .build()
        return runCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull()
    }
}
