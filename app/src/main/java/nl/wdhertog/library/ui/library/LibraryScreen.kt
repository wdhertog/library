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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import nl.wdhertog.library.ui.components.SelectionTopBar
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
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
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
    onImport: () -> Unit,
    onSeries: () -> Unit,
    onAuthors: () -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
    viewModel: LibraryViewModel = appViewModel { container, _ ->
        LibraryViewModel(container.bookRepository, container.locationRepository, container.enrichScheduler, container.readingRepository)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val awaitingLookup by viewModel.awaitingLookup.collectAsStateWithLifecycle()
    val autoLookup by (LocalContext.current.applicationContext as nl.wdhertog.library.LibraryApp).container.settings.autoLookup.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickingLocation by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    // Meervoudige selectie: ids in lijstvolgorde worden bij een actie afgeleid uit de zichtbare lijst.
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionTopBar(
                    selected = state.items.map { it.book.id }.filter { it in selection },
                    onSelectAll = { selection = state.items.map { it.book.id }.toSet() },
                    onClear = { selection = emptySet() },
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            } else TopAppBar(
                title = { Text("Bibliotheek") },
                actions = {
                    SortMenu(state.sort, viewModel::setSort)
                    OverflowMenu(onAddBook = onAddBook, onUpdateLibrary = viewModel::updateLibrary, onImport = onImport, onSeries = onSeries, onAuthors = onAuthors, onBackup = onBackup, onSettings = onSettings)
                },
            )
        },
        floatingActionButton = {
            if (selection.isEmpty()) ExtendedFloatingActionButton(
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
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.anyFilter) {
                    AssistChip(
                        onClick = viewModel::clearFilters,
                        label = { Text("Wissen") },
                        leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
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
                if (state.availableOwners.isNotEmpty()) {
                    ChoiceChip(
                        label = state.owner ?: "Eigenaar",
                        selected = state.owner != null,
                        options = listOf<String?>(null) + state.availableOwners,
                        optionLabel = { it ?: "Iedereen" },
                        current = state.owner,
                        onSelect = viewModel::setOwner,
                    )
                }
                if (state.hasNotOwned) {
                    ChoiceChip(
                        label = if (state.ownership == OwnershipFilter.IN_HOUSE) "Bezit" else state.ownership.label,
                        selected = state.ownership != OwnershipFilter.IN_HOUSE,
                        options = OwnershipFilter.entries,
                        optionLabel = { it.label },
                        current = state.ownership,
                        onSelect = viewModel::setOwnership,
                    )
                }
                ChoiceChip(
                    label = state.read.label,
                    selected = state.read != ReadFilter.ALL,
                    options = ReadFilter.entries,
                    optionLabel = { if (it == ReadFilter.ALL) "Alles" else it.label },
                    current = state.read,
                    onSelect = viewModel::setRead,
                )
                FilterChip(
                    selected = state.multipleEditions,
                    onClick = { viewModel.setMultipleEditions(!state.multipleEditions) },
                    label = { Text("Meerdere edities") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = state.favoritesOnly,
                    onClick = { viewModel.setFavoritesOnly(!state.favoritesOnly) },
                    label = { Text("Favorieten") },
                    leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            // Andere filters of sortering: begin weer bovenaan de lijst.
            val listState = rememberLazyListState()
            LaunchedEffect(state.language, state.location, state.owner, state.favoritesOnly, state.read, state.ownership, state.multipleEditions, state.sort, state.query) {
                listState.scrollToItem(0)
            }
            // Eigen regel: in de schuifbare rij met filters zou het aantal buiten beeld kunnen vallen.
            Text(
                text = countLabel(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            if (awaitingLookup > 0 && autoLookup) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Gegevens ophalen voor $awaitingLookup ${if (awaitingLookup == 1) "boek" else "boeken"}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (awaitingLookup > 0) {
                // Automatisch opzoeken staat uit: niets draait vanzelf; hier kun je het starten.
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$awaitingLookup ${if (awaitingLookup == 1) "boek" else "boeken"} nog niet opgezocht",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::updateLibrary) { Text("Nu opzoeken") }
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.totalCount == 0 -> EmptyMessage("Nog geen boeken.\nTik op Scannen om te beginnen.")
                state.items.isEmpty() -> EmptyMessage("Geen boeken gevonden.")
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.items, key = { it.book.id }) { item ->
                        val id = item.book.id
                        BookRow(
                            item,
                            onClick = {
                                if (selection.isEmpty()) onBookClick(id)
                                else selection = if (id in selection) selection - id else selection + id
                            },
                            locationLabel = item.book.locationId?.let(state.locationLabels::get),
                            selected = id in selection,
                            onLongClick = { selection = selection + id },
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
private fun OverflowMenu(
    onAddBook: () -> Unit,
    onUpdateLibrary: () -> Unit,
    onImport: () -> Unit,
    onSeries: () -> Unit,
    onAuthors: () -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
) {
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
            DropdownMenuItem(
                text = { Text("Series") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                onClick = { open = false; onSeries() },
            )
            DropdownMenuItem(
                text = { Text("Auteurs") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                onClick = { open = false; onAuthors() },
            )
            DropdownMenuItem(
                text = { Text("Importeren…") },
                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                onClick = { open = false; onImport() },
            )
            DropdownMenuItem(
                text = { Text("Back-up en export…") },
                leadingIcon = { Icon(Icons.Default.Backup, contentDescription = null) },
                onClick = { open = false; onBackup() },
            )
            DropdownMenuItem(
                text = { Text("Instellingen") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { open = false; onSettings() },
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

/** Filterchip met een keuzemenu. */
@Composable
private fun <T> ChoiceChip(
    label: String,
    selected: Boolean,
    options: List<T>,
    optionLabel: (T) -> String,
    current: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    leadingIcon = { if (option == current) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onSelect(option); open = false },
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
