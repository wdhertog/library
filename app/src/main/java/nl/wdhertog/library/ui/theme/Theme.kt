package nl.wdhertog.library.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import nl.wdhertog.library.R

/*
 * Eigen groen-crème palet (schetsen van 4 november 2026), op elke telefoon hetzelfde: geen kleuren
 * van de achtergrond (Material You). Donkere modus volgt de telefoon, met dezelfde tinten donker.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E4A3F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E3D5),
    onPrimaryContainer = Color(0xFF0B2018),
    secondary = Color(0xFF7A5A12),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA1),
    onSecondaryContainer = Color(0xFF261900),
    tertiary = Color(0xFF7A5A12),
    tertiaryContainer = Color(0xFFFFDEA1),
    onTertiaryContainer = Color(0xFF261900),
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1D1B17),
    surface = Color(0xFFFBF8F2),
    onSurface = Color(0xFF1D1B17),
    surfaceVariant = Color(0xFFF2EEE6),
    onSurfaceVariant = Color(0xFF5E5A52),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EC),
    surfaceContainer = Color(0xFFF2EEE6),
    surfaceContainerHigh = Color(0xFFECE7DE),
    surfaceContainerHighest = Color(0xFFE7E1D6),
    outline = Color(0xFFCFC8BB),
    outlineVariant = Color(0xFFE7E1D6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFACCDBA),
    onPrimary = Color(0xFF17362B),
    primaryContainer = Color(0xFF2E4A3F),
    onPrimaryContainer = Color(0xFFC7E3D5),
    secondary = Color(0xFFEAC16C),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDEA1),
    tertiary = Color(0xFFEAC16C),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDEA1),
    background = Color(0xFF171613),
    onBackground = Color(0xFFEAE4D9),
    surface = Color(0xFF171613),
    onSurface = Color(0xFFEAE4D9),
    surfaceVariant = Color(0xFF2A2823),
    onSurfaceVariant = Color(0xFFC9C2B5),
    surfaceContainerLowest = Color(0xFF12110F),
    surfaceContainerLow = Color(0xFF1F1E1A),
    surfaceContainer = Color(0xFF23221E),
    surfaceContainerHigh = Color(0xFF2B2925),
    surfaceContainerHighest = Color(0xFF35332E),
    outline = Color(0xFF8F887C),
    outlineVariant = Color(0xFF3A372F),
)

/** Literata (SIL Open Font License, zie assets/licenses): serif voor titels, zoals in de schetsen. */
@OptIn(ExperimentalTextApi::class)
val Literata = FontFamily(
    Font(R.font.literata, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.literata, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.literata, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val base = Typography()

/** Schermtitels en grote koppen in Literata; de rest blijft de systeemletter (beter leesbaar klein). */
private val AppTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = Literata),
    displayMedium = base.displayMedium.copy(fontFamily = Literata),
    displaySmall = base.displaySmall.copy(fontFamily = Literata),
    headlineLarge = base.headlineLarge.copy(fontFamily = Literata, fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontFamily = Literata, fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontFamily = Literata, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = Literata, fontWeight = FontWeight.Medium),
)

/** Boektitels in lijsten: serif, iets voller. */
val BookTitleStyle get() = AppTypography.titleMedium.copy(fontFamily = Literata, fontWeight = FontWeight.Medium)

@Composable
fun LibraryTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
