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
    viewModel: ReadingViewModel = appViewModel { container, _ -> ReadingViewModel(container.bookRepository, container.readingRepository) },
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
            if (s.current.isNotEmpty()) {
                item { Header("Nu aan het lezen (${s.current.size})") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.current, key = { it.session.id }) { entry -> CurrentBook(entry, onClick = { onOpenBook(entry.item.book.id) }) }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Header("Wil ik lezen (${s.wantToRead.size})") }
                    if (s.wantToRead.isNotEmpty()) {
                        TextButton(onClick = { showWantToRead = !showWantToRead }) { Text(if (showWantToRead) "Verbergen" else "Toon") }
                    }
                }
            }
            if (showWantToRead) {
                items(s.wantToRead, key = { "want-${it.book.id}" }) { item -> BookRow(item, onClick = { onOpenBook(item.book.id) }) }
            }

            item { Goal(s.stats, onEdit = { editingGoal = true }) }
            item { Statistics(s.stats) }
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
    Box {
        TextButton(onClick = { open = true }) {
            Text(year.toString())
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Jaar kiezen")
        }
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
            LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        Text(book.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Goal(stats: YearStats, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp)) {
        val goal = stats.goal
        if (goal == null) {
            Text("Leesdoel ${stats.year} instellen…", color = MaterialTheme.colorScheme.primary)
        } else {
            val done = stats.finished.size
            Text(
                "Leesdoel ${stats.year}: $done van $goal" + if (done >= goal) " — gehaald!" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (done.toFloat() / goal).coerceAtMost(1f) }, Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

@Composable
private fun Statistics(stats: YearStats) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Gelezen in ${stats.year}: ${stats.finished.size}", style = MaterialTheme.typography.titleMedium)
            if (stats.finished.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MonthBars(stats.perMonth)
                if (stats.yearOnly > 0) {
                    Text(
                        "+ ${stats.yearOnly} waarvan alleen het jaar bekend is",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val parts = listOfNotNull(
                    stats.pages.takeIf { it > 0 }?.let { NumberFormat.getIntegerInstance(dutch).format(it) + " pagina's" },
                    stats.averageRating?.let { "gemiddeld ★ " + formatRating((it * 10).toInt() / 10.0) },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                if (stats.languages.isNotEmpty()) {
                    Text(
                        stats.languages.joinToString(" · ") { (code, count) -> "${code.uppercase()} $count" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Staafjes per maand, met de beginletter van de maand eronder. */
@Composable
private fun MonthBars(perMonth: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = perMonth.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val slot = size.width / 12
            val barWidth = slot * 0.6f
            perMonth.forEachIndexed { i, count ->
                val height = if (count == 0) 2.dp.toPx() else size.height * count / max
                drawRoundRect(
                    color = if (count == 0) empty else color,
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
