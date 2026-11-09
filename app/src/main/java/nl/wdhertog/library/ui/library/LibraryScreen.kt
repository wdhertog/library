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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import nl.wdhertog.library.ui.theme.Gold
import nl.wdhertog.library.ui.components.BookGridItem
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import nl.wdhertog.library.ui.components.LongSetSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.util.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onAddBook: () -> Unit,
    onScan: () -> Unit,
    viewModel: LibraryViewModel = appViewModel { container, _ ->
        LibraryViewModel(container.bookRepository, container.locationRepository, container.enrichScheduler, container.readingRepository, container.loanRepository)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val awaitingLookup by viewModel.awaitingLookup.collectAsStateWithLifecycle()
    val settings = (LocalContext.current.applicationContext as nl.wdhertog.library.LibraryApp).container.settings
    val autoLookup by settings.autoLookup.collectAsStateWithLifecycle()
    val grid by settings.libraryGrid.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var pickingLocation by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    // Meervoudige selectie: ids in lijstvolgorde worden bij een actie afgeleid uit de zichtbare lijst.
    var selection by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet<Long>()) }
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
            } else {
                // Schetsen: geen titelbalk, maar een afgeronde zoekbalk met de knoppen erin.
                Row(
                    Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                        decorationBox = { field ->
                            if (query.isEmpty()) {
                                Text(
                                    "Zoek in ${state.totalCount} ${if (state.totalCount == 1) "boek" else "boeken"}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            field()
                        },
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "Zoekopdracht wissen") }
                    }
                    IconButton(onClick = { settings.setLibraryGrid(!grid) }) {
                        Icon(
                            if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (grid) "Als lijst" else "Als raster",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SortMenu(state.sort, viewModel::setSort)
                    OverflowMenu(onAddBook = onAddBook, onUpdateLibrary = viewModel::updateLibrary)
                }
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) ExtendedFloatingActionButton(
                onClick = onScan,
                // Goud, zoals in de schetsen: de belangrijkste actie springt eruit.
                containerColor = Gold.container,
                contentColor = Gold.onContainer,
                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                text = { Text("Scannen") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterRow(state, viewModel, onOpenFilters = { showFilters = true })
            // Andere filters of sortering: begin weer bovenaan de lijst.
            val listState = rememberLazyListState()
            val gridState = rememberLazyGridState()
            LaunchedEffect(Unit) {
                viewModel.scrollToTop.collect {
                    listState.scrollToItem(0)
                    gridState.scrollToItem(0)
                }
            }
            // Aantal, en wat er nog wordt opgezocht (schetsen: "312 van 1.284 boeken · 37 worden aangevuld").
            Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append(countLabel(state))
                        if (awaitingLookup > 0) {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append(" · $awaitingLookup ")
                                append(if (autoLookup) "worden aangevuld" else "nog niet opgezocht")
                            }
                        }
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (awaitingLookup > 0 && !autoLookup) TextButton(onClick = viewModel::updateLibrary) { Text("Nu opzoeken") }
            }
            if (awaitingLookup > 0 && autoLookup) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), trackColor = MaterialTheme.colorScheme.outlineVariant)
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.totalCount == 0 -> EmptyMessage("Nog geen boeken.\nTik op Scannen om te beginnen.")
                state.items.isEmpty() -> EmptyMessage("Geen boeken gevonden.")
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp), // drie kolommen op een gewone telefoon, zoals de schetsen
                    state = gridState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
                ) {
                    items(state.items, key = { it.book.id }) { item ->
                        val id = item.book.id
                        BookGridItem(
                            item,
                            status = state.statuses[id],
                            onClick = {
                                if (selection.isEmpty()) onBookClick(id)
                                else selection = if (id in selection) selection - id else selection + id
                            },
                            selected = id in selection,
                            onLongClick = { selection = selection + id },
                            hasLocation = item.book.locationId != null,
                        )
                    }
                }
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
                            status = state.statuses[id],
                            selected = id in selection,
                            onLongClick = { selection = selection + id },
                        )
                        HorizontalDivider(Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (showFilters) FilterSheet(state, viewModel, onPickLocation = { pickingLocation = true }, onDismiss = { showFilters = false })
    if (pickingLocation) LocationFilterDialog(state, viewModel, onDismiss = { pickingLocation = false })
}

/** "Filters (n)", daarna de actieve filters als chips met ✕; zonder filters twee snelkeuzes. */
@Composable
private fun FilterRow(state: LibraryUiState, viewModel: LibraryViewModel, onOpenFilters: () -> Unit) {
    val active = buildList<Pair<String, () -> Unit>> {
        state.language?.let { add(Languages.displayName(it).replaceFirstChar(Char::uppercase) to { viewModel.setLanguage(null) }) }
        state.genre?.let { add(it.replaceFirstChar(Char::uppercase) to { viewModel.setGenre(null) }) }
        state.location?.let { add(it.location.name to { viewModel.setLocation(null) }) }
        if (state.withoutLocation) add("Zonder locatie" to { viewModel.setWithoutLocation(false) })
        state.owner?.let { add(it to { viewModel.setOwner(null) }) }
        if (state.ownership != OwnershipFilter.IN_HOUSE) add(state.ownership.label to { viewModel.setOwnership(OwnershipFilter.IN_HOUSE) })
        if (state.read != ReadFilter.ALL) add(state.read.label to { viewModel.setRead(ReadFilter.ALL) })
        if (state.multipleEditions) add("Meerdere edities" to { viewModel.setMultipleEditions(false) })
        if (state.favoritesOnly) add("Favorieten" to { viewModel.setFavoritesOnly(false) })
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = false,
            onClick = onOpenFilters,
            label = { Text("Filters") },
            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, Modifier.size(18.dp)) },
            trailingIcon = if (active.isNotEmpty()) {
                { Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("${active.size}") } }
            } else {
                null
            },
        )
        active.forEach { (label, clear) ->
            FilterChip(
                selected = true,
                onClick = clear,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "$label uitzetten", Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
        if (active.isEmpty()) {
            FilterChip(selected = false, onClick = { viewModel.setRead(ReadFilter.READ) }, label = { Text("Gelezen") })
            FilterChip(selected = false, onClick = { viewModel.setFavoritesOnly(true) }, label = { Text("Favorieten") })
        }
    }
}

/** Alle filters bij elkaar, van onderaf. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(state: LibraryUiState, viewModel: LibraryViewModel, onPickLocation: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (state.anyFilter) TextButton(onClick = viewModel::clearFilters) { Text("Alles wissen") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageFilter(state.language, state.availableLanguages, viewModel::setLanguage)
                if (state.availableGenres.isNotEmpty()) GenreFilter(state.genre, state.availableGenres, viewModel::setGenre)
                if (state.locationTree.isNotEmpty()) {
                    FilterChip(
                        selected = state.location != null,
                        onClick = onPickLocation,
                        label = { Text(state.location?.location?.name ?: "Locatie", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    )
                }
                FilterChip(
                    selected = state.withoutLocation,
                    onClick = { viewModel.setWithoutLocation(!state.withoutLocation) },
                    label = { Text("Zonder locatie") },
                )
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
            Text(
                "${state.items.size} van ${state.totalCount} boeken",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
    // Series, Auteurs, Importeren, Back-up en Instellingen staan onder "Meer" in de onderbalk.
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
private fun GenreFilter(current: String?, available: List<Pair<String, Int>>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(current ?: "Genre") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Alle genres") }, onClick = { onSelect(null); open = false })
            available.forEach { (genre, count) ->
                DropdownMenuItem(
                    text = { Text("$genre · $count") },
                    leadingIcon = { if (genre == current) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { onSelect(genre); open = false },
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
