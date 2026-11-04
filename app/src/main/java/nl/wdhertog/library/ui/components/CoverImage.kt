package nl.wdhertog.library.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import nl.wdhertog.library.ui.theme.Literata
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import nl.wdhertog.library.covers.CoverStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import kotlin.math.absoluteValue

private val placeholderColors = listOf(
    Color(0xFF2E4A3F), Color(0xFF7A5A12), Color(0xFF5B3A55), Color(0xFF2F4C6E),
    Color(0xFF7B3B2E), Color(0xFF4A5A2A), Color(0xFF3D3D5C), Color(0xFF6B4B33),
)

/** De kleur van de plaatshouder voor deze titel (ook voor het kleurvlak op de boekpagina). */
fun placeholderColor(title: String): Color =
    if (title.isBlank()) Color(0xFFD8D2C6) else placeholderColors[title.hashCode().absoluteValue % placeholderColors.size]

/**
 * Cover van het boek, of een gekleurd vlak met de titel zolang er geen cover is.
 * [cover] is een URL of de bestandsnaam van een eigen foto (zie Book.cover).
 */
@Composable
fun CoverImage(title: String, cover: String?, modifier: Modifier = Modifier, showTitle: Boolean = true, author: String? = null) {
    val shape = RoundedCornerShape(4.dp)
    if (cover != null) {
        val context = LocalContext.current
        SubcomposeAsyncImage(
            model = remember(cover) { CoverStore.model(context, cover) },
            contentDescription = "Cover van $title",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
            error = { Placeholder(title, showTitle, author) },
            loading = { Placeholder(title, showTitle, author) },
        )
    } else {
        Box(modifier.clip(shape)) { Placeholder(title, showTitle, author) }
    }
}

/**
 * Zolang er geen cover is: een gekleurd vlak met een donkere "boekrug" links, de titel in serif en
 * (als er plaats is) de achternaam van de auteur. Net gescand en nog zonder titel: een neutraal vlak.
 */
@Composable
private fun Placeholder(title: String, showTitle: Boolean, author: String?) {
    val empty = title.isBlank()
    val color = placeholderColor(title)
    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.18f), size = size.copy(width = 3.dp.toPx())) }
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            empty -> Text("…", color = Color(0xFF5E5A52), style = MaterialTheme.typography.titleMedium)
            !showTitle -> Text(
                title.trim().take(1).uppercase(),
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = Literata,
                style = MaterialTheme.typography.titleMedium,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.92f),
                    fontFamily = Literata,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                author?.substringAfterLast(' ')?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.75f),
                        fontFamily = Literata,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
