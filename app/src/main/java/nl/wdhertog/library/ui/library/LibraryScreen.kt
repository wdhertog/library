package nl.wdhertog.library.ui.library

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import nl.wdhertog.library.ui.components.BookRow
import nl.wdhertog.library.ui.locations.LocationPickerDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.util.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onAddBook: () -> Unit,
    onScan: () -> Unit,
    viewModel: LibraryViewModel = appViewModel { container, _ ->
        LibraryViewModel(container.bookRepository, container.locationRepository, container.enrichScheduler)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val awaitingLookup by viewModel.awaitingLookup.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickingLocation by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bibliotheek") },
                actions = {
                    SortMenu(state.sort, viewModel::setSort)
                    OverflowMenu(onAddBook = onAddBook, onUpdateLibrary = viewModel::updateLibrary)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                text = { Text("Scannen") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Zoek titel, auteur, serie, ISBN") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Zoekopdracht wissen")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageFilter(state.language, state.availableLanguages, viewModel::setLanguage)
                if (state.locationTree.isNotEmpty()) {
                    FilterChip(
                        selected = state.location != null,
                        onClick = { pickingLocation = true },
                        label = {
                            Text(
                                state.location?.location?.name ?: "Locatie",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = countLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (awaitingLookup > 0) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Gegevens ophalen voor $awaitingLookup ${if (awaitingLookup == 1) "boek" else "boeken"}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.totalCount == 0 -> EmptyMessage("Nog geen boeken.\nTik op Scannen om te beginnen.")
                state.items.isEmpty() -> EmptyMessage("Geen boeken gevonden.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.items, key = { it.book.id }) { item ->
                        BookRow(
                            item,
                            onClick = { onBookClick(item.book.id) },
                            locationLabel = item.book.locationId?.let(state.locationLabels::get),
                        )
                        HorizontalDivider(Modifier.padding(start = 84.dp))
                    }
                }
            }
        }
    }

    if (pickingLocation) LocationFilterDialog(state, viewModel, onDismiss = { pickingLocation = false })
}

@Composable
private fun LocationFilterDialog(state: LibraryUiState, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    LocationPickerDialog(
        title = "Toon boeken in",
        tree = state.locationTree,
        selectedId = state.location?.id,
        noneLabel = "Alle locaties",
        onSelect = {
            viewModel.setLocation(it)
            onDismiss()
        },
        onCreate = viewModel::createLocation,
        onDismiss = onDismiss,
    )
}

private fun countLabel(state: LibraryUiState): String {
    val books = if (state.totalCount == 1) "boek" else "boeken"
    return if (state.items.size == state.totalCount) "${state.totalCount} $books"
    else "${state.items.size} van ${state.totalCount} $books"
}

@Composable
private fun OverflowMenu(onAddBook: () -> Unit, onUpdateLibrary: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Meer") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Handmatig toevoegen") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { open = false; onAddBook() },
            )
            DropdownMenuItem(
                text = { Text("Bibliotheek bijwerken") },
                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                onClick = { open = false; onUpdateLibrary() },
            )
        }
    }
}

@Composable
private fun SortMenu(current: SortOrder, onSelect: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sorteren")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    leadingIcon = { if (order == current) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        onSelect(order)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageFilter(current: String?, available: List<String>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(current?.let(Languages::displayName) ?: "Taal") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Alle talen") }, onClick = { onSelect(null); open = false })
            available.forEach { code ->
                DropdownMenuItem(
                    text = { Text(Languages.displayName(code)) },
                    leadingIcon = { if (code == current) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onSelect(code); open = false },
                )
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(64.dp))
        }
    }
}
