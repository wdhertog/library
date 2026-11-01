package nl.wdhertog.library.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Cover van het boek, of een gekleurd vlak met de titel zolang er geen cover is. */
@Composable
fun CoverImage(title: String, coverUrl: String?, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val shape = RoundedCornerShape(4.dp)
    if (coverUrl != null) {
        SubcomposeAsyncImage(
            model = coverUrl,
            contentDescription = "Cover van $title",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
            error = { Placeholder(title, showTitle) },
            loading = { Placeholder(title, showTitle) },
        )
    } else {
        Box(modifier.clip(shape)) { Placeholder(title, showTitle) }
    }
}

@Composable
private fun Placeholder(title: String, showTitle: Boolean) {
    val color = placeholderColors[title.hashCode().absoluteValue % placeholderColors.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (showTitle) title else title.take(1),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 4,
        )
    }
}
