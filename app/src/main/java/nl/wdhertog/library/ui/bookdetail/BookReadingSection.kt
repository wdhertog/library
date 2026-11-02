package nl.wdhertog.library.ui.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.db.SessionRow
import nl.wdhertog.library.ui.components.RatingStars
import nl.wdhertog.library.ui.components.formatReadDate
import nl.wdhertog.library.util.Languages

/**
 * Lezen: bezig (met voortgang), of beginnen; daaronder alle sessies van dit werk,
 * ook die in een andere editie.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingSection(
    bookId: Long,
    pages: Int?,
    wantToRead: Boolean,
    sessions: List<SessionRow>,
    onStart: () -> Unit,
    onProgress: (ReadingSession) -> Unit,
    onFinish: (ReadingSession, ReadingStatus) -> Unit,
    onAddPast: () -> Unit,
    onEdit: (ReadingSession) -> Unit,
    onToggleWantToRead: () -> Unit,
) {
    val current = sessions.firstOrNull { it.session.status == ReadingStatus.READING && it.session.bookId == bookId }?.session
    Column {
        if (current != null) {
            Text(
                listOfNotNull(
                    "Bezig" + (current.startedAt?.let { " sinds " + formatReadDate(it, current.datePrecision == DatePrecision.YEAR) } ?: ""),
                    progressText(current, pages),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clickable { onEdit(current) },
            )
            progressFraction(current, pages)?.let {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onProgress(current) }) { Text("Voortgang…") }
                Button(onClick = { onFinish(current, ReadingStatus.FINISHED) }) { Text("Uitgelezen…") }
                TextButton(onClick = { onFinish(current, ReadingStatus.ABANDONED) }) { Text("Gestopt…") }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = onStart) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sessions.any { it.session.status == ReadingStatus.FINISHED }) "Herlezen" else "Begin met lezen")
                }
                TextButton(onClick = onAddPast) { Text("Al gelezen…") }
                FilterChip(
                    selected = wantToRead,
                    onClick = onToggleWantToRead,
                    label = { Text("Wil ik lezen") },
                    leadingIcon = {
                        Icon(if (wantToRead) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd, contentDescription = null, Modifier.size(18.dp))
                    },
                )
            }
        }

        val history = sessions.filter { it.session != current }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            history.forEach { row -> SessionLine(row, isThisBook = row.session.bookId == bookId, onClick = { onEdit(row.session) }) }
        }
    }
}

@Composable
private fun SessionLine(row: SessionRow, isThisBook: Boolean, onClick: () -> Unit) {
    val session = row.session
    val yearOnly = session.datePrecision == DatePrecision.YEAR
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            when (session.status) {
                ReadingStatus.FINISHED -> Icons.Default.CheckCircle
                ReadingStatus.ABANDONED -> Icons.Default.DoNotDisturbOn
                ReadingStatus.READING -> Icons.AutoMirrored.Filled.MenuBook
            },
            contentDescription = session.status.label,
            tint = if (session.status == ReadingStatus.FINISHED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val dates = listOfNotNull(session.startedAt, session.finishedAt).map { formatReadDate(it, yearOnly) }.distinct()
            Text(
                session.status.label + (if (dates.isEmpty()) " (datum onbekend)" else " · " + dates.joinToString(" – ")),
                style = MaterialTheme.typography.bodyMedium,
            )
            session.rating?.let { RatingStars(it) }
            if (!isThisBook) {
                Text(
                    "In een andere editie: ${row.bookTitle}" + (row.bookLanguage?.let { " (${Languages.displayName(it)})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            session.review?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun progressText(session: ReadingSession, pages: Int?): String? {
    val page = session.progressPage
    val percent = session.progressPercent ?: if (page != null && pages != null && pages > 0) page * 100 / pages else null
    return when {
        page != null && percent != null -> "pagina $page" + (pages?.let { " van $it" } ?: "") + " ($percent%)"
        page != null -> "pagina $page"
        percent != null -> "$percent%"
        else -> null
    }
}

private fun progressFraction(session: ReadingSession, pages: Int?): Float? {
    session.progressPercent?.let { return it / 100f }
    val page = session.progressPage ?: return null
    return if (pages != null && pages > 0) (page.toFloat() / pages).coerceIn(0f, 1f) else null
}
