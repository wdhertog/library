package nl.wdhertog.library.ui.locations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.ui.navigation.appViewModel

val LocationType.icon: ImageVector
    get() = when (this) {
        LocationType.ROOM -> Icons.Default.MeetingRoom
        LocationType.BOOKCASE -> Icons.Default.GridView
        LocationType.SHELF -> Icons.Default.TableRows
        LocationType.BOX -> Icons.Default.Inventory2
        LocationType.OTHER -> Icons.Default.Place
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    onOpenLocation: (Long) -> Unit,
    viewModel: LocationsViewModel = appViewModel { container, _ -> LocationsViewModel(container.locationRepository) },
) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    // Standaard is alles uitgeklapt; ingeklapte knopen worden onthouden.
    var collapsed by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locaties") },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = "Locatie toevoegen") }
                },
            )
        },
    ) { padding ->
        val roots = tree
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                roots == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                roots.isEmpty() -> Text(
                    "Nog geen locaties.\nTik op + om een kamer of plek toe te voegen,\nbijvoorbeeld \"Woonkamer\".",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val all = LocationTree.flatten(roots)
                    val expanded = all.map { it.id }.toSet() - collapsed
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(LocationTree.flatten(roots, expanded), key = { it.id }) { node ->
                            LocationRow(
                                node = node,
                                expanded = node.id in expanded,
                                onToggle = { collapsed = if (node.id in collapsed) collapsed - node.id else collapsed + node.id },
                                onClick = { onOpenLocation(node.id) },
                            )
                            if (node.depth == 0) HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        LocationEditDialog(
            title = "Nieuwe locatie",
            initialName = "",
            initialType = LocationTree.defaultChildType(null),
            onConfirm = { name, type ->
                adding = false
                viewModel.add(name, type, parentId = null)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun LocationRow(node: LocationNode, expanded: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (8 + node.depth * 24).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.children.isNotEmpty()) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Inklappen" else "Uitklappen",
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Icon(node.location.type.icon, contentDescription = node.location.type.label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(node.location.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            "${node.totalCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
