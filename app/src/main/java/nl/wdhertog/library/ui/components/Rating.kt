package nl.wdhertog.library.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** "3,25" of "4"; beoordelingen zijn decimaal (geïmporteerde waarden als 3,25 blijven staan). */
fun formatRating(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.forLanguageTag("nl"), "%.2f", value).trimEnd('0')

/** Sterren op halve sterren afgerond; wijkt de waarde daarvan af, dan staat het getal erachter. */
@Composable
fun RatingStars(value: Double, size: Dp = 16.dp) {
    val halves = (value * 2).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val icon = when {
                halves >= (i + 1) * 2 -> Icons.Default.Star
                halves == i * 2 + 1 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Default.StarBorder
            }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size))
        }
        if (halves / 2.0 != value) {
            Spacer(Modifier.width(4.dp))
            Text(formatRating(value), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Invoer in halve sterren: tik op de linkerhelft van een ster voor een halve. Nog eens op dezelfde waarde = wissen. */
@Composable
fun RatingInput(value: Double?, onChange: (Double?) -> Unit, starSize: Dp = 36.dp) {
    val halves = value?.let { (it * 2).roundToInt() } ?: 0
    Row {
        repeat(5) { i ->
            val icon = when {
                halves >= (i + 1) * 2 -> Icons.Default.Star
                halves == i * 2 + 1 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Default.StarBorder
            }
            Icon(
                icon,
                contentDescription = "${i + 1} sterren",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(starSize)
                    .pointerInput(value) {
                        detectTapGestures { offset ->
                            val picked = i + if (offset.x < size.width / 2) 0.5 else 1.0
                            onChange(if (picked == value) null else picked)
                        }
                    },
            )
        }
    }
}
