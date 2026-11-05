package nl.wdhertog.library.ui.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import java.time.LocalDate
import nl.wdhertog.library.ui.components.AppCard
import androidx.compose.material3.AssistChip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.repository.ReadEntry
import nl.wdhertog.library.data.repository.ReadingStreaks
import nl.wdhertog.library.data.repository.YearStats
import nl.wdhertog.library.data.repository.cover
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.components.BookRow
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.components.RatingStars
import nl.wdhertog.library.ui.components.formatRating
import nl.wdhertog.library.ui.navigation.appViewModel
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dutch = Locale.forLanguageTag("nl")
private val shortDate = DateTimeFormatter.ofPattern("d MMM", dutch)
private val monthLetters = listOf("j", "f", "m", "a", "m", "j", "j", "a", "s", "o", "n", "d")

/** Lezen: nu bezig, leeslijst, leesdoel en wat je per jaar las (ontwerp §6.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    onOpenBook: (Long) -> Unit,
    onYearOverview: (Int) -> Unit,
    onBadges: () -> Unit,
    viewModel: ReadingViewModel = appViewModel { container, _ -> readingViewModel(container) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val year by viewModel.year.collectAsStateWithLifecycle()
    var editingGoal by remember { mutableStateOf(false) }
    var showWantToRead by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lezen") },
                actions = { state?.let { YearPicker(year, it.years, onSelect = { viewModel.year.value = it }) } },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // Schets 4: leesdoel als ring bovenaan, dan wat je leest, je leeslijst als covers, en de maanden.
            item { GoalCard(s.stats, s.streaks.takeIf { year == LocalDate.now().year }, isCurrentYear = year == LocalDate.now().year, onEdit = { editingGoal = true }) }
            if (s.stats.finished.isNotEmpty()) {
                item { YearOverviewLink(s.stats.year, onClick = { onYearOverview(s.stats.year) }) }
            }
            if (s.badges.any { it.achieved }) {
                item { BadgesCard(s.badges, onClick = onBadges) }
            }

            if (s.current.isNotEmpty()) {
                item { Header("Nu aan het lezen") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.current, key = { it.session.id }) { entry -> CurrentBook(entry, onClick = { onOpenBook(entry.item.book.id) }) }
                    }
                }
            }

            if (s.wantToRead.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Header("Wil ik lezen · ${s.wantToRead.size}") }
                        TextButton(onClick = { showWantToRead = !showWantToRead }) { Text(if (showWantToRead) "Minder" else "Alles") }
                    }
                }
                if (showWantToRead) {
                    items(s.wantToRead, key = { "want-${it.book.id}" }) { item -> BookRow(item, onClick = { onOpenBook(item.book.id) }) }
                } else {
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(s.wantToRead, key = { "want-row-${it.book.id}" }) { item ->
                                CoverImage(
                                    item.book.title, item.book.cover,
                                    Modifier.size(width = 56.dp, height = 84.dp).clickable { onOpenBook(item.book.id) },
                                    showTitle = false,
                                )
                            }
                        }
                    }
                }
            }

            item { Statistics(s.stats, highlightMonth = if (year == LocalDate.now().year) LocalDate.now().monthValue - 1 else null) }
            if (s.stats.finished.isNotEmpty()) item { Header("Gelezen in ${s.stats.year} · ${s.stats.finished.size}") }
            items(s.stats.finished, key = { "read-${it.session.id}" }) { entry ->
                FinishedRow(entry, onClick = { onOpenBook(entry.item.book.id) })
            }
            if (s.undated > 0 && year == s.years.first()) {
                item {
                    Text(
                        "Daarnaast ${s.undated} gelezen boeken zonder datum; die tellen in geen enkel jaar mee.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (editingGoal) {
        GoalDialog(
            year = year,
            current = state?.stats?.goal,
            onSave = { viewModel.setGoal(year, it); editingGoal = false },
            onDismiss = { editingGoal = false },
        )
    }
}

@Composable
private fun YearPicker(year: Int, years: List<Int>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 12.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(year.toString()) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Jaar kiezen", Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            years.forEach { y -> DropdownMenuItem(text = { Text(y.toString()) }, onClick = { onSelect(y); open = false }) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun CurrentBook(entry: ReadEntry, onClick: () -> Unit) {
    val book = entry.item.book
    val fraction = entry.session.progressPercent?.let { it / 100f }
        ?: entry.session.progressPage?.let { page -> book.pages?.takeIf { it > 0 }?.let { (page.toFloat() / it).coerceIn(0f, 1f) } }
    Column(Modifier.width(96.dp).clickable(onClick = onClick)) {
        CoverImage(book.title, book.cover, Modifier.size(width = 96.dp, height = 144.dp))
        Spacer(Modifier.height(6.dp))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                Modifier.fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
            )
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(book.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Leesdoel als ring, met hoe je ervoor staat, en de pagina's en je gemiddelde beoordeling. */
@Composable
private fun GoalCard(stats: YearStats, streaks: ReadingStreaks?, isCurrentYear: Boolean, onEdit: () -> Unit) {
    val done = stats.finished.size
    val goal = stats.goal
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = onEdit) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GoalRing(done, goal)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (goal != null) "Leesdoel ${stats.year}" else "Gelezen in ${stats.year}", style = MaterialTheme.typography.titleMedium)
                val status = when {
                    goal == null -> "Tik om een leesdoel in te stellen"
                    done >= goal -> "Gehaald!"
                    !isCurrentYear -> "${goal - done} te weinig"
                    else -> {
                        val today = LocalDate.now()
                        val expected = goal * today.dayOfYear / today.lengthOfYear()
                        "Nog ${goal - done} ${if (goal - done == 1) "boek" else "boeken"} · " +
                            if (done >= expected) "je ligt op schema" else "je loopt wat achter"
                    }
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val parts = listOfNotNull(
                    stats.pages.takeIf { it > 0 }?.let { NumberFormat.getIntegerInstance(dutch).format(it) + " pagina's" },
                    stats.averageRating?.let { "gem. ★ " + formatRating((it * 10).toInt() / 10.0) },
                )
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString(" · "), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                streaks?.let { streakLine(it) }?.let {
                    Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Ingang naar het jaaroverzicht: dikste boek, favorieten, drukste maand, om te delen. */
@Composable
private fun YearOverviewLink(year: Int, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Jouw $year in boeken", style = MaterialTheme.typography.titleSmall)
                Text("Hoogtepunten bekijken en delen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Leesstreak in één regel, bijv. "🔥 7 maanden op rij · langste 11". Telt deze maand nog niet mee,
 * dan staat erbij dat je hem deze maand kunt voortzetten.
 */
internal fun streakLine(streaks: ReadingStreaks): String? {
    val months = streaks.months
    if (months.longest < 2) return null
    val parts = mutableListOf<String>()
    if (months.current >= 2) {
        parts += "🔥 ${months.current} maanden op rij" + if (months.thisPeriod) "" else ", nog open deze maand"
        if (months.longest > months.current) parts += "langste ${months.longest}"
    } else {
        parts += "Langste reeks: ${months.longest} maanden op rij"
    }
    if (streaks.weeks.current >= 2) parts += "${streaks.weeks.current} weken"
    return parts.joinToString(" · ")
}

@Composable
private fun GoalRing(done: Int, goal: Int?) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val arc = MaterialTheme.colorScheme.primary
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
            val fraction = if (goal != null && goal > 0) (done.toFloat() / goal).coerceIn(0f, 1f) else 0f
            if (fraction > 0f) drawArc(arc, -90f, 360f * fraction, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$done", style = MaterialTheme.typography.titleLarge)
            goal?.let { Text("van $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Per maand in een kaart, met de talen eronder. */
@Composable
private fun Statistics(stats: YearStats, highlightMonth: Int?) {
    if (stats.finished.isEmpty()) return
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Per maand", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            MonthBars(stats.perMonth, highlightMonth)
            if (stats.yearOnly > 0) {
                Text(
                    "+ ${stats.yearOnly} waarvan alleen het jaar bekend is",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.languages.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stats.languages.joinToString(" · ") { (code, count) -> "${code.uppercase()} $count" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Staafjes per maand, met de beginletter van de maand eronder. */
@Composable
private fun MonthBars(perMonth: List<Int>, highlightMonth: Int?) {
    // Schets: lichtgroen, de huidige maand donkergroen.
    val strong = MaterialTheme.colorScheme.primary
    val color = MaterialTheme.colorScheme.secondaryContainer
    val empty = MaterialTheme.colorScheme.outlineVariant
    val max = perMonth.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val slot = size.width / 12
            val barWidth = slot * 0.6f
            perMonth.forEachIndexed { i, count ->
                val height = if (count == 0) 2.dp.toPx() else size.height * count / max
                drawRoundRect(
                    color = when {
                        count == 0 -> empty
                        i == highlightMonth -> strong
                        else -> color
                    },
                    topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            monthLetters.forEachIndexed { i, letter ->
                Text(
                    if (perMonth[i] > 0) "$letter\n${perMonth[i]}" else letter,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FinishedRow(entry: ReadEntry, onClick: () -> Unit) {
    val book = entry.item.book
    val session = entry.session
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(book.title, book.cover, Modifier.size(width = 40.dp, height = 60.dp), showTitle = false)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.item.authors.isNotEmpty()) {
                    Text(entry.item.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val dates = if (session.datePrecision == DatePrecision.YEAR) {
                    "datum onbekend"
                } else {
                    listOfNotNull(session.startedAt, session.finishedAt).map(shortDate::format).distinct().joinToString(" → ")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dates, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    session.rating?.let {
                        Spacer(Modifier.width(8.dp))
                        RatingStars(it, size = 14.dp)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 72.dp))
    }
}
