package nl.wdhertog.library.ui.reading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.wdhertog.library.R
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.repository.ReadEntry
import nl.wdhertog.library.data.repository.YearOverview
import nl.wdhertog.library.data.repository.cover
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.components.formatRating
import nl.wdhertog.library.ui.components.placeholderColor
import nl.wdhertog.library.util.Languages

/**
 * Het jaaroverzicht als afbeelding om te delen (1080 × 1350, staand 4:5): jaartal, aantal,
 * de covers van wat je las en de hoogtepunten. Altijd in de lichte kleuren van de app.
 */
object YearImage {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 72f

    private const val BACKGROUND = 0xFFFBF8F2.toInt()
    private const val INK = 0xFF1D1B17.toInt()
    private const val MUTED = 0xFF5E5A52.toInt()
    private const val GREEN = 0xFF2E4A3F.toInt()
    private const val LINE = 0xFFE7E1D6.toInt()

    suspend fun render(context: Context, overview: YearOverview, books: List<ReadEntry>): Bitmap {
        val serif = ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF
        val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val sansBold = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // Covers: oudste eerst, hooguit twee rijen van zes.
        val columns = 6
        val shown = books.sortedBy { it.session.finishedAt }.take(columns * 2)
        val gap = 16f
        val coverWidth = (WIDTH - 2 * MARGIN - (columns - 1) * gap) / columns
        val coverHeight = coverWidth * 1.5f
        val covers = shown.map { loadCover(context, it, coverWidth.toInt(), coverHeight.toInt()) }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        fun paint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = face
        }
        val width = WIDTH - 2 * MARGIN
        fun line(text: String, p: TextPaint, y: Float, x: Float = MARGIN, max: Float = width) =
            canvas.drawText(TextUtils.ellipsize(text, p, max, TextUtils.TruncateAt.END).toString(), x, y, p)

        var y = MARGIN + 36f
        line("MIJN JAAR IN BOEKEN", paint(34f, MUTED, sansBold).apply { letterSpacing = 0.08f }, y)
        y += 130f
        line("${overview.year}", paint(140f, GREEN, serif), y)
        y += 72f
        val counts = listOfNotNull(
            "${overview.count} ${if (overview.count == 1) "boek" else "boeken"}",
            overview.pages.takeIf { it > 0 }?.let { YearOverview.number(it) + " pagina's" },
            overview.averageRating?.let { "gem. ★ " + formatRating((it * 10).toInt() / 10.0) },
        )
        line(counts.joinToString("  ·  "), paint(46f, INK, sans), y)
        overview.goal?.let { goal ->
            y += 52f
            line(if (overview.count >= goal) "Leesdoel van $goal gehaald" else "Leesdoel: $goal", paint(34f, MUTED, sans), y)
        }

        // Covers
        y += 48f
        val placeholderText = paint(22f, 0xF2FFFFFF.toInt(), serif).apply { textLocale = java.util.Locale.forLanguageTag("nl") }
        covers.forEachIndexed { i, cover ->
            val x = MARGIN + (i % columns) * (coverWidth + gap)
            val top = y + (i / columns) * (coverHeight + gap)
            val rect = RectF(x, top, x + coverWidth, top + coverHeight)
            val path = android.graphics.Path().apply { addRoundRect(rect, 8f, 8f, android.graphics.Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            if (cover != null) {
                canvas.drawBitmap(cover, null, centerCrop(cover, rect), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val title = shown[i].item.book.title
                canvas.drawColor(placeholderColor(title).toArgb())
                val text = android.text.StaticLayout.Builder
                    .obtain(title.trim(), 0, title.trim().length, placeholderText, (coverWidth - 24f).toInt())
                    .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL).setLineSpacing(0f, 1.05f).build()
                canvas.save()
                canvas.translate(x + 14f, top + 14f)
                text.draw(canvas)
                canvas.restore()
                canvas.drawRect(x, top, x + 5f, top + coverHeight, Paint().apply { color = 0x2E000000 })
            }
            canvas.restore()
        }
        val rows = (covers.size + columns - 1) / columns
        y += rows * coverHeight + (rows - 1).coerceAtLeast(0) * gap
        if (books.size > shown.size) {
            y += 44f
            line("en nog ${books.size - shown.size}", paint(32f, MUTED, sans), y)
        }

        // Hoogtepunten, zolang ze passen.
        val label = paint(28f, MUTED, sansBold).apply { letterSpacing = 0.08f }
        val value = paint(40f, INK, serif)
        fun short(entry: ReadEntry) = entry.item.book.displayTitle.substringBefore(": ")
        val highlights = buildList {
            overview.thickest?.let { add("DIKSTE BOEK" to "${short(it)} · ${it.item.book.pages} p.") }
            overview.bestRated.firstOrNull()?.let { add((if (overview.bestRated.size > 1) "FAVORIETEN" else "FAVORIET") to overview.bestRated.joinToString(", ") { e -> short(e) }) }
            overview.topAuthor?.let { (name, n) -> add("MEEST GELEZEN AUTEUR" to "$name · $n boeken") }
            overview.busiestMonth?.let { (m, n) -> add("DRUKSTE MAAND" to "${YearOverview.monthName(m).replaceFirstChar { it.uppercase() }} · $n ${if (n == 1) "boek" else "boeken"}") }
            if (overview.languages.size > 1) add("TALEN" to overview.languages.joinToString(" · ") { (code, n) -> "${Languages.displayName(code)} $n" })
        }
        y += 40f
        val divider = Paint().apply { color = LINE; strokeWidth = 2f }
        for ((name, text) in highlights) {
            if (y + 112f > HEIGHT - MARGIN) break
            canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, divider)
            line(name, label, y + 46f)
            line(text, value, y + 98f)
            y += 110f
        }
        covers.forEach { it?.recycle() }
        return bitmap
    }

    private fun centerCrop(bitmap: Bitmap, target: RectF): RectF {
        val scale = maxOf(target.width() / bitmap.width, target.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        return RectF(target.centerX() - w / 2, target.centerY() - h / 2, target.centerX() + w / 2, target.centerY() + h / 2)
    }

    private suspend fun loadCover(context: Context, entry: ReadEntry, width: Int, height: Int): Bitmap? {
        val cover = entry.item.book.cover ?: return null
        val request = ImageRequest.Builder(context)
            .data(CoverStore.model(context, cover))
            .size(width * 2, height * 2)
            .allowHardware(false)
            .build()
        return runCatching {
            SingletonImageLoader.get(context).execute(request).image?.toBitmap()
                ?.let { withContext(Dispatchers.Default) { it.copy(Bitmap.Config.ARGB_8888, false) } }
        }.getOrNull()
    }
}
