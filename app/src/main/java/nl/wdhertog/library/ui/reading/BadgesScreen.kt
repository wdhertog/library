package nl.wdhertog.library.ui.reading

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.AppContainer
import nl.wdhertog.library.data.repository.Achievements
import nl.wdhertog.library.data.repository.Badge
import nl.wdhertog.library.data.repository.BadgeKind
import nl.wdhertog.library.ui.components.AppCard
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.ui.theme.Gold
import java.time.format.DateTimeFormatter
import java.util.Locale

private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("nl"))

internal fun readingViewModel(container: AppContainer) =
    ReadingViewModel(container.bookRepository, container.readingRepository, container.badgeService)

private val BadgeKind.icon: ImageVector
    get() = when (this) {
        BadgeKind.BOOKS -> Icons.AutoMirrored.Filled.MenuBook
        BadgeKind.PAGES -> Icons.Default.AutoStories
        BadgeKind.THICK_BOOK -> Icons.Default.Weekend
        BadgeKind.GOAL -> Icons.Default.EmojiEvents
        BadgeKind.LANGUAGES -> Icons.Default.Translate
        BadgeKind.SERIES -> Icons.Default.CollectionsBookmark
        BadgeKind.AUTHOR -> Icons.Default.Favorite
        BadgeKind.REREAD -> Icons.Default.Replay
        BadgeKind.STREAK -> Icons.Default.LocalFireDepartment
        BadgeKind.FAST -> Icons.Default.Speed
        BadgeKind.YEARS -> Icons.Default.CalendarMonth
        BadgeKind.LENDER -> Icons.Default.VolunteerActivism
        BadgeKind.COLLECTOR -> Icons.Default.Inventory2
        BadgeKind.COUNTRIES -> Icons.Default.Public
    }

/** "73 van 100", "14 van 16 dit jaar" of "60% van je verste serie". */
private fun Badge.progressText(): String = when (kind) {
    BadgeKind.SERIES -> "$progress% van je verste serie"
    else -> "$progress van $target" + (progressNote?.let { " $it" } ?: "")
}

/** Details van een badge: wanneer en waarmee behaald, of hoe ver je bent. */
@Composable
private fun BadgeDialog(badge: Badge, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { BadgeIcon(badge, 56.dp) },
        title = { Text(if (badge.times > 1) "${badge.title} ×${badge.times}" else badge.title, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(badge.description, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                if (badge.achieved) {
                    Text(badge.whenText()?.let { "Behaald $it" } ?: "Behaald (datum onbekend)", fontWeight = FontWeight.Medium)
                    badge.achievedWith?.let { Text("Met: $it", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (badge.times > 1) Text("${badge.times} keer gehaald", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    badge.details.forEach {
                        Spacer(Modifier.height(8.dp))
                        Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (badge.target > 1) {
                    LinearProgressIndicator(
                        progress = { badge.progress.toFloat() / badge.target },
                        Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(badge.progressText(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    badge.details.forEach {
                        Spacer(Modifier.height(8.dp))
                        Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("Nog niet behaald", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Sluiten") } },
    )
}

/** Wanneer behaald, of null als dat niet bekend is. */
private fun Badge.whenText(): String? = achievedOn?.let { if (yearOnly) "in ${it.year}" else "op ${longDate.format(it)}" }

/** Pictogram in een gouden cirkel; grijs als je hem nog niet hebt. */
@Composable
fun BadgeIcon(badge: Badge, size: Dp = 48.dp) {
    val background = if (badge.achieved) Gold.container else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (badge.achieved) Gold.onContainer else MaterialTheme.colorScheme.outline
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(badge.kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Kaart op Lezen: de laatst behaalde badges en hoeveel je er hebt. */
@Composable
internal fun BadgesCard(badges: List<Badge>, onClick: () -> Unit) {
    val recent = Achievements.recent(badges)
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Badges · ${badges.count { it.achieved }} van ${badges.size}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    recent.forEach { badge ->
                        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BadgeIcon(badge, 40.dp)
                            Text(
                                badge.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Alle badges", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Alle badges: behaald (met datum) en nog niet behaald (grijs, met voortgang). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesScreen(
    onBack: () -> Unit,
    viewModel: ReadingViewModel = appViewModel { container, _ -> readingViewModel(container) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    state?.badges?.firstOrNull { it.id == opened }?.let { BadgeDialog(it, onDismiss = { opened = null }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Badges") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val achieved = s.badges.filter { it.achieved }
            .sortedWith(compareByDescending<Badge, java.time.LocalDate?>(nullsFirst()) { it.achievedOn })
        val open = s.badges.filter { !it.achieved }.sortedByDescending { it.progress.toFloat() / it.target }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (achieved.isNotEmpty()) {
                item { SectionHeader("Behaald · ${achieved.size}") }
                items(achieved, key = { it.id }) { BadgeRow(it, onClick = { opened = it.id }) }
            }
            if (open.isNotEmpty()) {
                item { SectionHeader("Nog te halen") }
                items(open, key = { it.id }) { BadgeRow(it, onClick = { opened = it.id }) }
            }
            item {
                Text(
                    "Badges komen uit je eigen leessessies en tellen met terugwerkende kracht. " +
                        "Ze worden niet gesynchroniseerd.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun BadgeRow(badge: Badge, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BadgeIcon(badge)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val title = if (badge.times > 1) "${badge.title} ×${badge.times}" else badge.title
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(badge.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (badge.achieved) {
                    badge.whenText()?.let {
                        Text("Behaald $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (badge.target > 1) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { badge.progress.toFloat() / badge.target },
                        Modifier.fillMaxWidth().height(4.dp),
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        drawStopIndicator = {},
                    )
                    Text(badge.progressText(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 80.dp))
    }
}
