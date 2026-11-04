package nl.wdhertog.library.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.MainActivity
import nl.wdhertog.library.R
import nl.wdhertog.library.data.repository.ReadEntry
import nl.wdhertog.library.data.repository.ReadingStats
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.components.CoverBitmap

/**
 * Widget "Nu aan het lezen" op het beginscherm: het boek waar je het langst mee bezig bent, met
 * hoe ver je bent. Tik opent de boekpagina (of Lezen als je niets leest).
 *
 * Bijgewerkt als je leessessies veranderen terwijl de app draait (ook door synchroniseren),
 * als je de app verlaat, en verder hooguit eens per drie uur door Android.
 */
class ReadingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as LibraryApp
        app.container.appScope.launch {
            try {
                update(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val lock = Mutex()

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ReadingWidget::class.java))

        /** Werk alle geplaatste widgets bij; doet niets als er geen staan. */
        suspend fun update(context: Context) = lock.withLock {
            val ids = ids(context)
            if (ids.isEmpty()) return@withLock
            val container = (context.applicationContext as LibraryApp).container
            val sessions = container.readingRepository.observeSessions().first()
            val library = container.bookRepository.observeLibrary().first().associateBy { it.book.id }
            val current = ReadingStats.current(sessions, library)
            val views = if (current.isEmpty()) empty(context) else reading(context, current.first(), current.size - 1)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        private fun empty(context: Context) = RemoteViews(context.packageName, R.layout.widget_reading).apply {
            setViewVisibility(R.id.widget_book, View.GONE)
            setViewVisibility(R.id.widget_empty, View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_root, open(context, MainActivity.EXTRA_OPEN_READING, 0L))
        }

        private suspend fun reading(context: Context, entry: ReadEntry, others: Int): RemoteViews {
            val book = entry.item.book
            val density = context.resources.displayMetrics.density
            val cover = CoverBitmap.of(context, book, (48 * density).toInt(), (72 * density).toInt(), textSize = 8 * density)
            val percent = entry.session.progressPercent
                ?: entry.session.progressPage?.let { page -> book.pages?.takeIf { it > 0 }?.let { (page * 100 / it).coerceIn(0, 100) } }
            return RemoteViews(context.packageName, R.layout.widget_reading).apply {
                setViewVisibility(R.id.widget_book, View.VISIBLE)
                setViewVisibility(R.id.widget_empty, View.GONE)
                setImageViewBitmap(R.id.widget_cover, cover)
                setContentDescription(R.id.widget_cover, "Cover van ${book.title}")
                setTextViewText(R.id.widget_title, book.displayTitle)
                setTextViewText(R.id.widget_author, entry.item.authors.joinToString(", "))
                setViewVisibility(R.id.widget_author, if (entry.item.authors.isEmpty()) View.GONE else View.VISIBLE)
                if (percent != null) {
                    setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
                    setProgressBar(R.id.widget_progress, 100, percent, false)
                    setTextViewText(R.id.widget_percent, "$percent%")
                } else {
                    setViewVisibility(R.id.widget_progress_row, View.GONE)
                }
                if (others > 0) {
                    setViewVisibility(R.id.widget_more, View.VISIBLE)
                    setTextViewText(R.id.widget_more, if (others == 1) "en nog 1 ander boek" else "en nog $others andere boeken")
                } else {
                    setViewVisibility(R.id.widget_more, View.GONE)
                }
                setOnClickPendingIntent(R.id.widget_root, open(context, MainActivity.EXTRA_OPEN_BOOK, book.id))
            }
        }

        private fun open(context: Context, extra: String, bookId: Long): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (extra == MainActivity.EXTRA_OPEN_BOOK) putExtra(extra, bookId) else putExtra(extra, true)
            }
            return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
