package nl.wdhertog.library.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.wdhertog.library.covers.CoverStore

/**
 * Een zachte achtergrondkleur uit de cover (schetsen: "de kleur komt uit de cover zelf"): de gemiddelde
 * kleur, voor een derde gemengd met de achtergrond. Op de telefoon zelf berekend. Zonder cover de kleur
 * van de plaatshouder.
 */
@Composable
fun rememberCoverTint(title: String, cover: String?): Color {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    var base by remember(cover, title) { mutableStateOf(placeholderColor(title)) }
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(CoverStore.model(context, cover))
            .allowHardware(false)
            .size(24)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        base = withContext(Dispatchers.Default) { averageColor(result.image.toBitmap()) } ?: base
    }
    return lerp(surface, base, 0.35f)
}

private fun averageColor(bitmap: Bitmap): Color? {
    val small = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
    val pixel = small.getPixel(0, 0)
    return Color(pixel).takeIf { it.alpha > 0.5f }
}
