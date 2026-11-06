package nl.wdhertog.library.ui.authors

import android.widget.Toast
import nl.wdhertog.library.data.repository.AuthorDuplicate
import nl.wdhertog.library.data.db.Author
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.AuthorSummary
import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.data.repository.ManualParts
import nl.wdhertog.library.ui.components.BookRow
import nl.wdhertog.library.ui.navigation.appViewModel
import java.util.Locale

/** Alle auteurs van wie je een boek hebt, op achternaam. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorListScreen(
    onBack: () -> Unit,
    onOpenAuthor: (Long) -> Unit,
    viewModel: AuthorListViewModel = appViewModel { container, _ ->
        AuthorListViewModel(container.authorRepository, container.app.getSharedPreferences("authors", android.content.Context.MODE_PRIVATE))
    },
) {
    val authors by viewModel.authors.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auteurs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                placeholder = { Text("Zoek een auteur") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = authors
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    if (query.isBlank()) "Nog geen auteurs." else "Geen auteur gevonden.",
                    Modifier.padding(32.dp),
                )
                else -> LazyColumn {
                    if (query.isBlank()) {
                        items(duplicates, key = { "dubbel-" + it.key }) { duplicate ->
                            DuplicateCard(duplicate, onMerge = { viewModel.merge(duplicate) }, onIgnore = { viewModel.ignore(duplicate) })
                        }
                    }
                    items(list, key = { it.id }) { author ->
                        AuthorRow(author) { onOpenAuthor(author.id) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateCard(duplicate: AuthorDuplicate, onMerge: () -> Unit, onIgnore: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Mogelijk dezelfde auteur", style = MaterialTheme.typography.labelLarge)
            Text("${duplicate.keep.name} (${books(duplicate.keep.books)})", style = MaterialTheme.typography.bodyMedium)
            Text("${duplicate.other.name} (${books(duplicate.other.books)})", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onIgnore) { Text("Negeren") }
                TextButton(onClick = onMerge) { Text("Samenvoegen als „${duplicate.keep.name}”") }
            }
        }
    }
}

private fun books(count: Int) = if (count == 1) "1 boek" else "$count boeken"

/** Naam wijzigen; bestaat de naam al, dan worden de auteurs samengevoegd. */
@Composable
private fun RenameAuthorDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Naam wijzigen") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Bestaat deze naam al, dan worden de twee auteurs samengevoegd. Verrijking zet de oude spelling niet terug.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Een andere auteur kiezen om deze mee samen te voegen. */
@Composable
private fun MergeAuthorDialog(current: Author, authors: List<AuthorSummary>, onPick: (AuthorSummary) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<AuthorSummary?>(null) }
    val needle = nl.wdhertog.library.util.TextNormalizer.normalize(query)
    val options = authors.filter { it.id != current.id && (needle.isBlank() || nl.wdhertog.library.util.TextNormalizer.normalize(it.name).contains(needle)) }
    val choice = picked
    if (choice != null) {
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Samenvoegen?") },
            text = { Text("De boeken van „${current.name}” komen bij „${choice.name}”; „${current.name}” verdwijnt.") },
            confirmButton = { TextButton(onClick = { onPick(choice) }) { Text("Samenvoegen") } },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Annuleren") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Samenvoegen met…") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Zoek een auteur") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(options, key = { it.id }) { author ->
                        Text(
                            "${author.name} · ${books(author.books)}",
                            Modifier.fillMaxWidth().clickable { picked = author }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

@Composable
private fun AuthorRow(author: AuthorSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(author.name, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                plural(author.books, "boek", "boeken"),
                plural(author.works, "werk", "werken").takeIf { author.works != author.books },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Auteurspagina: wie het is (Wikidata), je boeken per werk, en de series. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    viewModel: AuthorViewModel = appViewModel { container, handle ->
        AuthorViewModel(
            handle,
            container.authorRepository,
            container.bookRepository,
            container.readingRepository,
            container.seriesRepository,
            container.settings::mayLookUpNow,
        )
    },
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val notFound by viewModel.notFound.collectAsStateWithLifecycle()
    val lookingUp by viewModel.lookingUp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val allAuthors by viewModel.allAuthors.collectAsStateWithLifecycle()
    page?.author?.let { author ->
        when (dialog) {
            "rename" -> RenameAuthorDialog(author.name, onRename = { viewModel.rename(it); dialog = null }, onDismiss = { dialog = null })
            "merge" -> MergeAuthorDialog(author, allAuthors, onPick = { viewModel.mergeInto(it.id); dialog = null }, onDismiss = { dialog = null })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.author?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Meer") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Naam wijzigen…") },
                                enabled = page != null,
                                onClick = { menuOpen = false; dialog = "rename" },
                            )
                            DropdownMenuItem(
                                text = { Text("Samenvoegen met…") },
                                enabled = page != null,
                                onClick = { menuOpen = false; dialog = "merge" },
                            )
                            DropdownMenuItem(
                                text = { Text("Opnieuw opzoeken op Wikidata") },
                                enabled = !lookingUp,
                                onClick = {
                                    menuOpen = false
                                    viewModel.lookUp { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val p = page
        if (p == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (notFound) Text("Deze auteur staat niet (meer) in je bibliotheek.", Modifier.padding(32.dp)) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { AboutCard(p) }
            if (p.series.isNotEmpty()) {
                item { SectionTitle("Series") }
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        p.series.forEachIndexed { index, series ->
                            if (index > 0) HorizontalDivider()
                            SeriesLine(series) { onOpenSeries(series.id) }
                        }
                    }
                }
            }
            item { SectionTitle("Boeken") }
            p.works.forEach { work ->
                if (work.books.size > 1) {
                    item(key = "werk-${work.workId}") {
                        Text(
                            "${work.title} · ${work.books.size} edities",
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(work.books, key = { "boek-${it.book.id}" }) { item ->
                    BookRow(
                        item = item,
                        onClick = { onOpenBook(item.book.id) },
                        trailing = {
                            if (work.read) Icon(Icons.Default.CheckCircle, contentDescription = "Gelezen", tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard(page: AuthorPage) {
    val author = page.author
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(author.name, style = MaterialTheme.typography.headlineSmall)
            val years = when {
                author.birthYear != null && author.deathYear != null -> "${author.birthYear}–${author.deathYear}"
                author.birthYear != null -> "geboren ${author.birthYear}"
                else -> null
            }
            listOfNotNull(years, author.country).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            author.description?.let {
                Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    plural(page.books, "boek", "boeken"),
                    plural(page.works.size, "werk", "werken").takeIf { page.works.size != page.books },
                    "${page.readWorks} gelezen",
                    page.averageRating?.let { "gemiddeld ★ " + String.format(Locale.forLanguageTag("nl"), "%.1f", it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SeriesLine(series: SeriesSummary, onClick: () -> Unit) {
    val total = listOfNotNull(series.totalVolumes, series.maxIndex, ManualParts.highest(series.manualParts)).maxOrNull()
        ?.takeIf { it >= series.works }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(series.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (total != null) "${series.works} van $total delen" else plural(series.works, "deel", "delen"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
