package nl.wdhertog.library.ui.components

import androidx.compose.foundation.clickable
import nl.wdhertog.library.data.repository.cover
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.data.repository.isAwaitingLookup

/** Eén boek in een lijst: cover, titel, auteurs, serie/jaar/taal en eventueel de locatie. */
@Composable
fun BookRow(
    item: BookListItem,
    onClick: () -> Unit,
    locationLabel: String? = null,
    leadingLabel: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        CoverImage(item.book.title, item.book.cover, Modifier.size(width = 52.dp, height = 76.dp), showTitle = false)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.book.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
