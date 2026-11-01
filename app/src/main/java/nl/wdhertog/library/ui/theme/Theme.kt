package nl.wdhertog.library.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E4A3F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E3D5),
    onPrimaryContainer = Color(0xFF0B2018),
    secondary = Color(0xFF7A5A12),
    secondaryContainer = Color(0xFFFFDEA1),
    onSecondaryContainer = Color(0xFF261900),
    background = Color(0xFFFBF8F2),
    surface = Color(0xFFFBF8F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFACCDBA),
    onPrimary = Color(0xFF17362B),
    primaryContainer = Color(0xFF2E4A3F),
    onPrimaryContainer = Color(0xFFC7E3D5),
    secondary = Color(0xFFEAC16C),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDEA1),
)

@Composable
fun LibraryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
