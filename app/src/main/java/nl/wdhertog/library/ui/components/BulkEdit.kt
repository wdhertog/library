package nl.wdhertog.library.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.ui.locations.LocationPickerDialog
import nl.wdhertog.library.ui.loans.OwnerDialog
import nl.wdhertog.library.ui.navigation.appViewModel

/** Acties voor meerdere boeken tegelijk (ontwerp §10, optie A). */
class BulkEditViewModel(private val books: BookRepository, private val locations: LocationRepository) : ViewModel() {

    val ownerNames: StateFlow<List<String>> = books.observeOwnerNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationTree: StateFlow<List<LocationNode>> = locations.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setOwner(ids: List<Long>, owner: String?, done: (String) -> Unit) = run(done) {
        books.setOwner(ids, owner)
        "Eigenaar " + (owner?.let { "\"$it\" ingesteld" } ?: "gewist") + " voor ${count(ids)}"
    }

    /** In de volgorde van de lijst achteraan op de gekozen plek (of van hun plek af bij null). */
    fun move(ids: List<Long>, locationId: Long?, done: (String) -> Unit) = run(done) {
        ids.forEach { locations.moveBook(it, locationId) }
        (if (locationId == null) "Van hun plek gehaald: " else "Verplaatst: ") + count(ids)
    }

    fun setFavorite(ids: List<Long>, favorite: Boolean, done: (String) -> Unit) = run(done) {
        books.setFavorite(ids, favorite)
        (if (favorite) "Favoriet: " else "Geen favoriet meer: ") + count(ids)
    }

    fun setWantToRead(ids: List<Long>, wantToRead: Boolean, done: (String) -> Unit) = run(done) {
        books.setWantToRead(ids, wantToRead)
        (if (wantToRead) "Op je leeslijst: " else "Van je leeslijst: ") + count(ids)
    }

    fun addTags(ids: List<Long>, tags: List<String>, done: (String) -> Unit) = run(done) {
        books.addTags(ids, tags)
        "Tags toegevoegd aan ${count(ids)}"
    }

    fun delete(ids: List<Long>, done: (String) -> Unit) = run(done) {
        books.delete(ids)
        "Verwijderd: ${count(ids)}"
    }

    suspend fun createLocation(name: String, type: LocationType, parentId: Long?): Long = locations.add(name, type, parentId)

    private fun count(ids: List<Long>) = if (ids.size == 1) "1 boek" else "${ids.size} boeken"

    private fun run(done: (String) -> Unit, action: suspend () -> String) {
        viewModelScope.launch { done(action()) }
    }
}

private enum class BulkDialog { OWNER, LOCATION, TAGS, DELETE }

/** Bestaat nooit als locatie-id, zodat de kiezer niets als huidige plek aanwijst. */
private const val NO_SELECTION = -1L

/**
 * Bovenbalk zolang er boeken geselecteerd zijn: aantal, alles selecteren en de acties.
 * [selected] staat in lijstvolgorde, zodat verplaatsen de volgorde van de lijst aanhoudt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selected: List<Long>,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: BulkEditViewModel = appViewModel { container, _ -> BulkEditViewModel(container.bookRepository, container.locationRepository) },
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BulkDialog?>(null) }
    val ownerNames by viewModel.ownerNames.collectAsStateWithLifecycle()
    val tree by viewModel.locationTree.collectAsStateWithLifecycle()
    val finish: (String) -> Unit = { message ->
        onClear()
        onMessage(message)
    }

    TopAppBar(
        title = { Text("${selected.size} geselecteerd") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Selectie opheffen") } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, contentDescription = "Alles selecteren") }
            IconButton(onClick = { dialog = BulkDialog.OWNER }) { Icon(Icons.Default.Person, contentDescription = "Eigenaar") }
            IconButton(onClick = { dialog = BulkDialog.LOCATION }) { Icon(Icons.Default.Place, contentDescription = "Verplaatsen") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Meer acties") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuAction("Favoriet", Icons.Default.Favorite) { menuOpen = false; viewModel.setFavorite(selected, true, finish) }
                    MenuAction("Geen favoriet", Icons.Default.FavoriteBorder) { menuOpen = false; viewModel.setFavorite(selected, false, finish) }
                    MenuAction("Op leeslijst", Icons.Default.BookmarkAdd) { menuOpen = false; viewModel.setWantToRead(selected, true, finish) }
                    MenuAction("Van leeslijst", Icons.Default.BookmarkRemove) { menuOpen = false; viewModel.setWantToRead(selected, false, finish) }
                    MenuAction("Tags toevoegen…", Icons.AutoMirrored.Filled.Label) { menuOpen = false; dialog = BulkDialog.TAGS }
                    HorizontalDivider()
                    MenuAction("Verwijderen…", Icons.Default.Delete) { menuOpen = false; dialog = BulkDialog.DELETE }
                }
            }
        },
    )

    when (dialog) {
        BulkDialog.OWNER -> OwnerDialog(
            current = null,
            suggestions = ownerNames,
            onConfirm = { owner -> dialog = null; viewModel.setOwner(selected, owner, finish) },
            onDismiss = { dialog = null },
        )
        BulkDialog.LOCATION -> LocationPickerDialog(
            title = "Waar komen deze ${selected.size} boeken?",
            tree = tree,
            // Geen vinkje: de boeken kunnen op verschillende plekken staan.
            selectedId = NO_SELECTION,
            noneLabel = "Van hun plek halen",
            onSelect = { locationId -> dialog = null; viewModel.move(selected, locationId, finish) },
            onCreate = viewModel::createLocation,
            onDismiss = { dialog = null },
        )
        BulkDialog.TAGS -> TagsDialog(
            count = selected.size,
            onConfirm = { tags -> dialog = null; viewModel.addTags(selected, tags, finish) },
            onDismiss = { dialog = null },
        )
        BulkDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("${selected.size} boeken verwijderen?") },
            text = { Text("Ze verdwijnen uit je bibliotheek, met hun leesgeschiedenis en uitleningen.") },
            confirmButton = { TextButton(onClick = { dialog = null; viewModel.delete(selected, finish) }) { Text("Verwijderen") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuleren") } },
        )
        null -> Unit
    }
}

@Composable
private fun MenuAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

@Composable
private fun TagsDialog(count: Int, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Tags toevoegen aan $count ${if (count == 1) "boek" else "boeken"}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Tags, gescheiden door komma's") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.split(',')) }, enabled = text.isNotBlank()) { Text("Toevoegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}
