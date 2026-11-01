package nl.wdhertog.library.ui.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.repository.BookDetail
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.data.repository.label
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.util.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: BookDetailViewModel = appViewModel { container, handle ->
        BookDetailViewModel(handle, container.bookRepository, container.enrichmentService)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                    }
                },
                actions = {
                    if (state is BookDetailUiState.Loaded) {
                        IconButton(onClick = { onEdit(viewModel.bookId) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Bewerken")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Meer")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Verwijderen") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                BookDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                BookDetailUiState.NotFound -> Text("Dit boek bestaat niet meer.", Modifier.align(Alignment.Center))
                is BookDetailUiState.Loaded -> DetailContent(s.detail, onOpenBook, refreshing, viewModel::refresh)
            }
        }
    }

    if (confirmDelete) {
        val title = (state as? BookDetailUiState.Loaded)?.detail?.book?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Boek verwijderen?") },
            text = { Text("\"$title\" wordt uit je bibliotheek verwijderd.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text("Verwijderen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
        )
    }
}

@Composable
private fun DetailContent(detail: BookDetail, onOpenBook: (Long) -> Unit, refreshing: Boolean, onRefresh: () -> Unit) {
    val book = detail.book
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row {
            CoverImage(book.title, book.coverUrl, Modifier.size(width = 104.dp, height = 156.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.displayTitle, style = MaterialTheme.typography.headlineSmall)
                book.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (detail.authors.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail.authors.joinToString(", "), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                MetaLine(listOfNotNull(book.publisher, book.publishedYear?.toString(), book.pages?.let { "$it p." }))
                MetaLine(listOfNotNull(book.language?.let(Languages::displayName), book.format?.label))
            }
        }

        detail.series.forEach { s ->
            Spacer(Modifier.height(16.dp))
            AssistChip(
                onClick = {},
                label = { Text("Serie: ${s.seriesName}" + (s.seriesIndex?.let { " · deel $it" } ?: "")) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
            )
        }

        if (book.wantToRead) {
            Spacer(Modifier.height(8.dp))
            Text("Op je leeslijst", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }

        detail.works.firstOrNull()?.originalTitle?.takeIf { it != book.title }?.let {
            Section("Oorspronkelijke titel") { Text(it) }
        }
        book.description?.let { Section("Beschrijving") { Text(it, style = MaterialTheme.typography.bodyMedium) } }

        if (detail.otherEditions.isNotEmpty()) {
            Section("Andere edities in je collectie") {
                detail.otherEditions.forEach { EditionRow(it, onClick = { onOpenBook(it.id) }) }
            }
        }

        Section("Details") {
            DetailRow("ISBN", book.isbn13 ?: book.isbn10)
            DetailRow("Vertaling", book.translators)
            DetailRow("Origineel", detail.works.firstOrNull()?.originalLanguage?.let(Languages::displayName))
            DetailRow("Tags", book.tags)
        }
        book.notes?.let { Section("Notities") { Text(it, style = MaterialTheme.typography.bodyMedium) } }

        if (book.isbn13 != null) {
            Section("Gegevens") {
                Text(book.enrichStatus.label, style = MaterialTheme.typography.bodyMedium)
                book.enrichedSources?.let {
                    Text("Bronnen: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Gegevens opnieuw ophalen")
                }
                Text(
                    "Wat je zelf hebt ingevuld, blijft staan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MetaLine(parts: List<String>) {
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    content()
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun EditionRow(book: Book, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(book.title, book.coverUrl, Modifier.size(width = 32.dp, height = 48.dp), showTitle = false)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(book.title, style = MaterialTheme.typography.bodyLarge)
            MetaLine(listOfNotNull(book.language?.let(Languages::displayName), book.publishedYear?.toString()))
        }
    }
}
