package nl.wdhertog.library.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookStatus
import nl.wdhertog.library.data.repository.cover
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.theme.Literata

/**
 * Eén boek in de rasterweergave: cover 2:3 met badges (gelezen, uitgeleend) en een voortgangsbalkje,
 * daaronder titel en auteur.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridItem(
    item: BookListItem,
    status: BookStatus?,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            CoverImage(item.book.title, item.book.cover, Modifier.fillMaxSize(), showTitle = true, author = item.authors.firstOrNull())
            when (status) {
                is BookStatus.Read -> Badge(Modifier.align(Alignment.TopEnd)) {
                    // Vast donkergroen op wit: goed zichtbaar op elke cover, licht en donker.
                    Icon(Icons.Default.CheckCircle, contentDescription = "Gelezen", tint = Color(0xFF2E4A3F), modifier = Modifier.size(18.dp))
                }
                is BookStatus.Lent -> Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = "Uitgeleend", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(13.dp))
                }
                is BookStatus.Reading -> status.percent?.let { percent ->
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(5.dp).fillMaxWidth().height(3.dp)
                            .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                    ) {
                        Box(Modifier.fillMaxWidth(percent / 100f).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
                    }
                }
                null -> Unit
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Geselecteerd",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).size(32.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Text(
            item.book.displayTitle,
            Modifier.padding(top = 4.dp),
            fontFamily = Literata,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.authors.isNotEmpty()) {
            Text(
                item.authorLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(4.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) { content() }
}
