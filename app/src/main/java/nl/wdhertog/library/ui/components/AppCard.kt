package nl.wdhertog.library.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Kaart zoals in de schetsen: licht vlak met een dunne rand, in plaats van kopjes met lijnen. */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    // Licht: wit op crème. Donker: iets lichter dan de achtergrond, zodat de kaart er ook dan uitspringt.
    val container = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    val colors = CardDefaults.cardColors(containerColor = container)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val shape = RoundedCornerShape(12.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}
