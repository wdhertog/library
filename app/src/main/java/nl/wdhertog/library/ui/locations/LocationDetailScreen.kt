package nl.wdhertog.library.ui.locations

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import nl.wdhertog.library.ui.components.LongSetSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.ui.components.BookRow
import nl.wdhertog.library.ui.components.SelectionTopBar
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.activity.compose.BackHandler
import nl.wdhertog.library.ui.navigation.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationDetailScreen(
    onBack: () -> Unit,
    onOpenLocation: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    onScanHere: (Long) -> Unit,
    viewModel: LocationDetailViewModel = appViewModel { container, handle ->
        LocationDetailViewModel(handle, container.locationRepository, container.bookRepository)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val node = state.node
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet<Long>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // "Alles selecteren" = alle boeken op deze plek, in plankvolgorde (bijv. om in één keer de eigenaar te zetten).
            if (selection.isNotEmpty()) {
                SelectionTopBar(
                    selected = state.books.map { it.book.id }.filter { it in selection },
                    onSelectAll = { selection = state.books.map { it.book.id }.toSet() },
                    onClear = { selection = emptySet() },
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            } else TopAppBar(
                title = {
                    Column {
                        Text(node?.location?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (node != null && node.path.size > 1) {
                            Text(
                                node.path.dropLast(1).joinToString(" › "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
                },
                actions = {
                    if (node != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Meer") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Wijzigen") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = { menuOpen = false; dialog = "edit" },
                                )
                                DropdownMenuItem(
                                    text = { Text("Verwijderen") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = { menuOpen = false; dialog = "delete" },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                node == null -> Text("Deze locatie bestaat niet meer.", Modifier.align(Alignment.Center))
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Button(
                            onClick = { onScanHere(node.id) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Boeken scannen naar deze " + if (node.location.type == LocationType.OTHER) "plek" else node.location.type.label.lowercase())
                        }
                    }
                    item { SectionHeader("Binnen ${node.location.name}") }
                    items(node.children, key = { "loc-${it.id}" }) { child ->
                        ChildRow(child, onClick = { onOpenLocation(child.id) })
                    }
                    item {
                        TextButton(onClick = { dialog = "add" }, Modifier.padding(horizontal = 8.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Locatie toevoegen in ${node.location.name}")
                        }
                        HorizontalDivider()
                    }
                    item {
                        SectionHeader(
                            if (state.books.isEmpty()) "Nog geen boeken direct op deze plek"
                            else "${state.books.size} ${if (state.books.size == 1) "boek" else "boeken"}, in volgorde",
                        )
                    }
                    itemsIndexed(state.books, key = { _, it -> "book-${it.book.id}" }) { index, item ->
                        val id = item.book.id
                        BookRow(
                            item = item,
                            onClick = {
                                if (selection.isEmpty()) onOpenBook(id)
                                else selection = if (id in selection) selection - id else selection + id
                            },
                            leadingLabel = "${index + 1}",
                            trailing = {
                                if (selection.isEmpty()) {
                                    IconButton(onClick = { viewModel.removeBook(id) }) {
                                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Van deze plek halen")
                                    }
                                }
                            },
                            selected = id in selection,
                            onLongClick = { selection = selection + id },
                        )
                    }
                }
            }
        }
    }

    if (node != null) {
        when (dialog) {
            "add" -> LocationEditDialog(
                title = "Nieuwe locatie",
                parentLabel = node.pathLabel,
                initialName = "",
                initialType = LocationTree.defaultChildType(node.location.type),
                onConfirm = { name, type -> dialog = null; viewModel.addChild(name, type) },
                onDismiss = { dialog = null },
            )
            "edit" -> LocationEditDialog(
                title = "Locatie wijzigen",
                initialName = node.location.name,
                initialType = node.location.type,
                onConfirm = { name, type -> dialog = null; viewModel.update(name, type) },
                onDismiss = { dialog = null },
            )
            "delete" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("${node.location.name} verwijderen?") },
                text = {
                    val subs = LocationTree.descendantIds(node).size - 1
                    Text(
                        buildString {
                            if (subs > 0) append("Ook de $subs locatie(s) erbinnen worden verwijderd. ")
                            if (node.totalCount > 0) append("De ${node.totalCount} boek(en) blijven in je bibliotheek, maar zonder locatie.")
                            if (isEmpty()) append("Deze locatie is leeg.")
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { dialog = null; viewModel.delete(onDeleted = onBack) }) { Text("Verwijderen") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuleren") } },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChildRow(node: LocationNode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(node.location.type.icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(node.location.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("${node.totalCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
