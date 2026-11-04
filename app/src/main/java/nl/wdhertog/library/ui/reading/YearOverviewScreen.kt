package nl.wdhertog.library.ui.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.ReadEntry
import nl.wdhertog.library.data.repository.ReadingRepository
import nl.wdhertog.library.data.repository.ReadingStats
import nl.wdhertog.library.data.repository.YearOverview
import nl.wdhertog.library.data.repository.cover
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.components.AppCard
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.components.formatRating
import nl.wdhertog.library.ui.navigation.YearOverviewRoute
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.ui.theme.BookTitleStyle
import nl.wdhertog.library.util.Languages
import nl.wdhertog.library.util.Share

class YearOverviewViewModel(handle: SavedStateHandle, books: BookRepository, reading: ReadingRepository) : ViewModel() {
    val year = handle.toRoute<YearOverviewRoute>().year

    val overview: StateFlow<YearOverview?> =
        combine(books.observeLibrary(), reading.observeSessions(), reading.observeGoals()) { library, sessions, goals ->
            YearOverview.of(ReadingStats.forYear(year, sessions, library.associateBy { it.book.id }, goals.firstOrNull { it.year == year }?.target))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** "Je jaar in boeken": de hoogtepunten van een leesjaar, om terug te kijken of te delen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearOverviewScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: YearOverviewViewModel = appViewModel { container, handle ->
        YearOverviewViewModel(handle, container.bookRepository, container.readingRepository)
    },
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jouw ${viewModel.year} in boeken") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
                actions = {
                    overview?.takeIf { it.count > 0 }?.let { o ->
                        IconButton(onClick = { Share.open(context, o.shareText()) }) { Icon(Icons.Default.Share, contentDescription = "Delen") }
                    }
                },
            )
        },
    ) { padding ->
        val o = overview
        if (o == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (o.count == 0) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("In ${o.year} heb je (nog) geen boeken uitgelezen.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Totals(o) }
            o.thickest?.let { item { Highlight("Dikste boek", it, "${it.item.book.pages} pagina's", onOpenBook) } }
            o.topAuthor?.let { (name, n) -> item { Fact("Meest gelezen auteur", name, "$n boeken") } }
            if (o.bestRated.isNotEmpty()) {
                item {
                    Section(if (o.bestRated.size == 1) "Favoriet" else "Favorieten") {
                        o.bestRated.forEach { entry ->
                            BookLine(entry, "★ " + formatRating(entry.session.rating ?: 0.0), onOpenBook)
                        }
                    }
                }
            }
            o.busiestMonth?.let { (month, n) ->
                item { Fact("Drukste maand", YearOverview.monthName(month).replaceFirstChar { it.uppercase() }, "$n ${if (n == 1) "boek" else "boeken"}") }
            }
            o.fastest?.let { (entry, days) -> item { Highlight("Snelst uitgelezen", entry, if (days == 1L) "in één dag" else "in $days dagen", onOpenBook) } }
            if (o.first != null || o.last != null) {
                item {
                    Section("Begin en eind") {
                        o.first?.let { BookLine(it, "eerste", onOpenBook) }
                        o.last?.let { BookLine(it, "laatste", onOpenBook) }
                    }
                }
            }
            if (o.languages.size > 1) {
                item { Fact("Talen", o.languages.joinToString(" · ") { (code, n) -> "${Languages.displayName(code)} $n" }, null) }
            }
        }
    }
}

@Composable
private fun Totals(o: YearOverview) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${o.count}", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text(if (o.count == 1) "boek gelezen" else "boeken gelezen", style = MaterialTheme.typography.titleMedium)
            val parts = listOfNotNull(
                o.goal?.let {
                    when {
                        o.count >= it -> "doel van $it gehaald"
                        o.year >= java.time.LocalDate.now().year -> "doel $it"
                        else -> "doel was $it"
                    }
                },
                o.pages.takeIf { it > 0 }?.let { YearOverview.number(it) + " pagina's" },
                o.averagePages?.let { "gem. $it p." },
                o.averageRating?.let { "gem. ★ " + formatRating((it * 10).toInt() / 10.0) },
            )
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(label)
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Fact(label: String, value: String, detail: String?) {
    Section(label) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Highlight(label: String, entry: ReadEntry, detail: String, onOpenBook: (Long) -> Unit) {
    Section(label) { BookLine(entry, detail, onOpenBook) }
}

@Composable
private fun BookLine(entry: ReadEntry, detail: String, onOpenBook: (Long) -> Unit) {
    val book = entry.item.book
    Row(Modifier.fillMaxWidth().clickable { onOpenBook(book.id) }, verticalAlignment = Alignment.CenterVertically) {
        CoverImage(book.title, book.cover, Modifier.size(width = 44.dp, height = 66.dp), showTitle = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.displayTitle, style = BookTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.item.authors.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
