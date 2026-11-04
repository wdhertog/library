package nl.wdhertog.library.util

import android.content.Context
import android.content.Intent
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.repository.displayTitle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Teksten om te delen via het deelmenu van Android (WhatsApp, Signal, mail, …); de app stuurt zelf niets. */
object Share {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("nl"))

    /** "Dictator — Robert Harris (2015)", met ISBN en een link naar Open Library (open data). */
    fun book(book: Book, authors: List<String>): String = buildString {
        append(book.displayTitle)
        book.subtitle?.let { append(": ").append(it) }
        val byline = listOfNotNull(authors.takeIf { it.isNotEmpty() }?.joinToString(", "), book.publishedYear?.let { "($it)" })
        if (byline.isNotEmpty()) append(" — ").append(byline.joinToString(" "))
        book.isbn13?.let {
            append("\nISBN ").append(it)
            append("\nhttps://openlibrary.org/isbn/").append(it)
        }
    }

    /** Vriendelijk navragen of iemand een uitgeleend boek nog heeft. */
    fun askAboutLoan(title: String, authors: List<String>, person: String?, lentAt: LocalDate?, dueAt: LocalDate?, today: LocalDate = LocalDate.now()): String {
        val greeting = person?.let { "Hoi ${it.substringBefore(' ')}," } ?: "Hoi,"
        val book = "\u2018$title\u2019" + (authors.firstOrNull()?.let { " van $it" } ?: "")
        val since = lentAt?.let { " Je hebt het sinds ${dateFormat.format(it)}${if (it.year != today.year) " " + it.year else ""}." } ?: ""
        val due = dueAt?.takeIf { !it.isAfter(today) }?.let { " Het zou ${dateFormat.format(it)} terug zijn." } ?: ""
        return "$greeting heb je $book nog?$since$due"
    }

    /** Een afbeelding delen (via de cache en een FileProvider; alleen leesrecht voor de ontvanger). */
    fun image(context: Context, bitmap: android.graphics.Bitmap, name: String) {
        val dir = java.io.File(context.cacheDir, "gedeeld").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Delen via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun open(context: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(send, "Delen via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
