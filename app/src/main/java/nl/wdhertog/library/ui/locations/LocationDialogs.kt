package nl.wdhertog.library.ui.locations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationTree

/** Naam en soort van een locatie invoeren (nieuw of hernoemen). */
@Composable
fun LocationEditDialog(
    title: String,
    initialName: String,
    initialType: LocationType,
    onConfirm: (name: String, type: LocationType) -> Unit,
    onDismiss: () -> Unit,
    parentLabel: String? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val confirm = { if (name.isNotBlank()) onConfirm(name, type) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Zelf de insets afhandelen, zodat de dialoog boven het toetsenbord blijft en Opslaan zichtbaar is.
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                parentLabel?.let {
                    Text("In: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Naam") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocationType.entries.forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = name.isNotBlank()) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/**
 * Kies een locatie uit de boom. Met de + achter een locatie maak je er direct een
 * sublocatie in aan (bijv. een nieuwe plank tijdens het scannen); die wordt dan gekozen.
 */
@Composable
fun LocationPickerDialog(
    title: String,
    tree: List<LocationNode>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: suspend (name: String, type: LocationType, parentId: Long?) -> Long,
    onDismiss: () -> Unit,
    noneLabel: String? = null,
) {
    var creatingIn by remember { mutableStateOf<LocationNode?>(null) }
    var creatingRoot by remember { mutableStateOf(false) }
    var pendingCreate by remember { mutableStateOf<Triple<String, LocationType, Long?>?>(null) }

    pendingCreate?.let { (name, type, parentId) ->
        LaunchedEffect(pendingCreate) {
            val id = onCreate(name, type, parentId)
            pendingCreate = null
            onSelect(id)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (noneLabel != null) {
                    item {
                        PickerRow(depth = 0, label = noneLabel, count = null, selected = selectedId == null, onClick = { onSelect(null) }) {
                            Icon(Icons.Default.LocationOff, contentDescription = null, Modifier.size(20.dp))
                        }
                        HorizontalDivider()
                    }
                }
                items(LocationTree.flatten(tree), key = { it.id }) { node ->
                    PickerRow(
                        depth = node.depth,
                        label = node.location.name,
                        count = node.totalCount,
                        selected = node.id == selectedId,
                        onClick = { onSelect(node.id) },
                        trailing = {
                            IconButton(onClick = { creatingIn = node }) {
                                Icon(Icons.Default.Add, contentDescription = "Locatie toevoegen in ${node.location.name}")
                            }
                        },
                    )
                }
                item {
                    TextButton(onClick = { creatingRoot = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (tree.isEmpty()) "Eerste locatie toevoegen" else "Nieuwe kamer of plek")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sluiten") } },
    )

    creatingIn?.let { parent ->
        LocationEditDialog(
            title = "Nieuwe locatie",
            parentLabel = parent.pathLabel,
            initialName = "",
            initialType = LocationTree.defaultChildType(parent.location.type),
            onConfirm = { name, type ->
                creatingIn = null
                pendingCreate = Triple(name, type, parent.id)
            },
            onDismiss = { creatingIn = null },
        )
    }
    if (creatingRoot) {
        LocationEditDialog(
            title = "Nieuwe locatie",
            initialName = "",
            initialType = LocationTree.defaultChildType(null),
            onConfirm = { name, type ->
                creatingRoot = false
                pendingCreate = Triple(name, type, null)
            },
            onDismiss = { creatingRoot = false },
        )
    }
}

@Composable
private fun PickerRow(
    depth: Int,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
    leading: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 20).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Gekozen", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        count?.let { Text("$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        trailing()
    }
}
