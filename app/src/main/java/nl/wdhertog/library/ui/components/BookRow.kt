package nl.wdhertog.library.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import nl.wdhertog.library.ui.theme.BookTitleStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.data.repository.isAwaitingLookup
import nl.wdhertog.library.data.repository.cover

/** Eén boek in een lijst: cover, titel, auteurs, serie/jaar/taal en eventueel de locatie. */
@Composable
fun BookRow(
    item: BookListItem,
    onClick: () -> Unit,
    locationLabel: String? = null,
    leadingLabel: String? = null,
    trailing: @Composable () -> Unit = {},
    /** Meervoudige selectie: lang drukken begint, tikken wisselt (zie SelectionTopBar). */
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingLabel?.let {
            Text(
                it,
                Modifier.width(28.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            CoverImage(item.book.title, item.book.cover, Modifier.size(width = 52.dp, height = 76.dp), showTitle = false)
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Geselecteerd",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).size(32.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.book.displayTitle,
                    Modifier.weight(1f, fill = false),
                    style = BookTitleStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.book.favorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "Favoriet",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
            if (item.authors.isNotEmpty()) {
                Text(
                    item.authorLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOfNotNull(
                // Wat niet in de kast staat, valt zo op tussen de rest (bijv. in zoekresultaten).
                "Niet in bezit".takeIf { item.book.ownership == Ownership.NOT_OWNED },
                item.book.format?.takeIf { it == BookFormat.EBOOK || it == BookFormat.AUDIO }?.label,
                item.series?.let { s -> s.seriesName + (s.seriesIndex?.let { " #$it" } ?: "") },
                item.book.publishedYear?.toString(),
                item.book.language?.uppercase(),
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            locationLabel?.let {
                Text(
                    "📍 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.book.isAwaitingLookup) {
            Icon(
                Icons.Default.HourglassEmpty,
                contentDescription = "Gegevens worden opgehaald",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        trailing()
    }
}
