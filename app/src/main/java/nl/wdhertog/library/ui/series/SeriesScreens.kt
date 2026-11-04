package nl.wdhertog.library.ui.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.BookmarkAdd
import nl.wdhertog.library.data.repository.ManualParts
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import nl.wdhertog.library.data.repository.SeriesDuplicate
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.data.repository.SeriesOrder
import nl.wdhertog.library.data.repository.SeriesVolume
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.navigation.appViewModel

/** Alle series, met hoeveel delen je hebt en hebt gelezen (ontwerp §6.8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesListScreen(
    /** Null als Series een tab in de onderbalk is. */
    onBack: (() -> Unit)?,
    onOpenSeries: (Long) -> Unit,
    viewModel: SeriesListViewModel = appViewModel { container, _ ->
        SeriesListViewModel(
            container.seriesRepository,
            container.app.getSharedPreferences("series", android.content.Context.MODE_PRIVATE),
            container.settings::mayLookUpNow,
        )
    },
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Series") },
                navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } } }
            )
        },
    ) { padding ->
        val list = summaries
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nog geen series. Ze verschijnen vanzelf zodra boeken bij een serie horen.")
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(duplicates, key = { "dubbel-" + it.key }) { duplicate ->
                    DuplicateCard(duplicate, onMerge = { viewModel.merge(duplicate) }, onIgnore = { viewModel.ignore(duplicate) })
                }
                items(list, key = { it.id }) { summary ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenSeries(summary.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(summary.name, style = MaterialTheme.typography.titleMedium)
                        Text(summaryLine(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DuplicateCard(duplicate: SeriesDuplicate, onMerge: () -> Unit, onIgnore: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Mogelijk dezelfde serie", style = MaterialTheme.typography.labelLarge)
            Text("${duplicate.keep.name} (${volumes(duplicate.keep.works)})", style = MaterialTheme.typography.bodyMedium)
            Text("${duplicate.other.name} (${volumes(duplicate.other.works)})", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onIgnore) { Text("Negeren") }
                TextButton(onClick = onMerge) { Text("Samenvoegen als „${duplicate.keep.name}”") }
            }
        }
    }
}

private fun volumes(count: Int) = if (count == 1) "1 deel" else "$count delen"

private fun summaryLine(summary: SeriesSummary): String {
    val total = listOfNotNull(
        summary.totalVolumes,
        summary.maxIndex?.takeIf { it <= SeriesOrder.MAX_PLAUSIBLE_INDEX },
        ManualParts.highest(summary.manualParts),
    )
        .maxOrNull()?.takeIf { it >= summary.works }
    val have = if (total != null) "${summary.works} van $total delen" else "${summary.works} " + if (summary.works == 1) "deel" else "delen"
    val notOwned = summary.works - summary.owned
    return listOfNotNull(
        have,
        notOwned.takeIf { it > 0 }?.let { "$it niet in bezit" },
        "${summary.read} gelezen",
    ).joinToString(" · ")
}

/** Delen op volgorde, per deel je exemplaren; ontbrekende nummers grijs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: SeriesDetailViewModel = appViewModel { container, handle ->
        SeriesDetailViewModel(handle, container.seriesRepository, container.settings::mayLookUpNow)
    },
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val others by viewModel.others.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingTotal by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var editingVolume by remember { mutableStateOf<SeriesVolume.Present?>(null) }
    val lookingUp by viewModel.lookingUp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingMissing by remember { mutableStateOf<SeriesVolume.Missing?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.series?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Naam wijzigen") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Meer") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Aantal delen…") }, onClick = { menuOpen = false; editingTotal = true })
                            DropdownMenuItem(text = { Text("Samenvoegen met…") }, onClick = { menuOpen = false; merging = true })
                            DropdownMenuItem(
                                text = { Text("Ontbrekend deel toevoegen…") },
                                onClick = { menuOpen = false; editingMissing = SeriesVolume.Missing(index = "", manual = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delen opzoeken op Wikidata") },
                                enabled = !lookingUp,
                                onClick = {
                                    menuOpen = false
                                    viewModel.lookUpParts { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val d = detail
        if (d == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(d.volumes, key = { volume -> (volume as? SeriesVolume.Present)?.workId?.toString() ?: "missing-${volume.index}" }) { volume ->
                when (volume) {
                    is SeriesVolume.Present -> PresentVolume(volume, onOpenBook = onOpenBook, onEdit = { editingVolume = volume })
                    is SeriesVolume.Missing -> MissingVolume(volume, onClick = { editingMissing = volume })
                }
                HorizontalDivider(Modifier.padding(start = 64.dp))
            }
        }
    }

    val d = detail ?: return
    editingMissing?.let { volume ->
        MissingPartDialog(
            volume = volume,
            onSave = { ordinal, title -> viewModel.setManualPart(ordinal, title, previousOrdinal = volume.index.ifEmpty { null }); editingMissing = null },
            onRemove = { viewModel.setManualPart(volume.index, ""); editingMissing = null },
            onWishlist = { ordinal, title ->
                viewModel.addToWishlist(ordinal, title) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                editingMissing = null
            },
            onDismiss = { editingMissing = null },
        )
    }
    if (renaming) {
        TextDialog(
            title = "Naam van de serie",
            initial = d.series.name,
            hint = "Bestaat de naam al, dan worden de series samengevoegd.",
            onSave = { viewModel.rename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (editingTotal) {
        TextDialog(
            title = "Aantal delen",
            initial = d.series.totalVolumes?.toString().orEmpty(),
            hint = "Zo zie je welke delen je nog mist. Leeg = onbekend.",
            numeric = true,
            onSave = { viewModel.setTotal(it.toIntOrNull()); editingTotal = false },
            onDismiss = { editingTotal = false },
        )
    }
    if (merging) {
        val candidates = others.filter { it.id != d.series.id }
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("Samenvoegen met…") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(candidates, key = { it.id }) { other ->
                        Text(
                            other.name,
                            Modifier.fillMaxWidth().clickable { viewModel.mergeInto(other.id); merging = false }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = false }) { Text("Annuleren") } },
        )
    }
    editingVolume?.let { volume ->
        TextDialog(
            title = "Deelnummer",
            initial = volume.index.orEmpty(),
            hint = "Bijv. 3, 0.5 of II. Leeg = zonder nummer.",
            onSave = { viewModel.setIndex(volume.workId, it); editingVolume = null },
            onDismiss = { editingVolume = null },
            extraAction = "Uit serie halen" to { viewModel.removeFromSeries(volume.workId); editingVolume = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresentVolume(volume: SeriesVolume.Present, onOpenBook: (Long) -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Text(
            volume.index ?: "–",
            Modifier.width(40.dp).padding(top = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val first = volume.books.first()
        CoverImage(first.bookTitle, first.coverFile ?: first.coverUrl, Modifier.size(width = 40.dp, height = 60.dp), showTitle = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(volume.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (volume.read) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.CheckCircle, contentDescription = "Gelezen", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            volume.authors?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                volume.books.forEach { book ->
                    val label = listOfNotNull(
                        book.language?.uppercase(),
                        book.bookTitle.takeIf { it != volume.title || volume.books.size > 1 },
                        when (book.ownership) {
                            Ownership.BORROWED -> "geleend"
                            Ownership.NOT_OWNED -> "niet in bezit"
                            Ownership.OWNED -> null
                        },
                    ).joinToString(" · ")
                    AssistChip(onClick = { onOpenBook(book.bookId) }, label = { Text(label.ifEmpty { "Openen" }, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Deelnummer wijzigen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun MissingVolume(volume: SeriesVolume.Missing, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(volume.index, Modifier.width(40.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        Column {
            if (volume.title != null) {
                Text(volume.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                listOfNotNull(volume.year?.toString(), "ontbreekt in je collectie", "zelf toegevoegd".takeIf { volume.manual })
                    .joinToString(" · ").replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Titel van een ontbrekend deel invullen; bij een nieuw deel ook het nummer. */
@Composable
private fun MissingPartDialog(
    volume: SeriesVolume.Missing,
    onSave: (String, String) -> Unit,
    onRemove: () -> Unit,
    onWishlist: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = volume.index.isEmpty()
    var ordinal by remember { mutableStateOf(volume.index) }
    var title by remember { mutableStateOf(volume.title.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(if (isNew) "Ontbrekend deel toevoegen" else "Deel ${volume.index}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) {
                    OutlinedTextField(
                        value = ordinal,
                        onValueChange = { ordinal = it },
                        label = { Text("Deelnummer") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Wordt gedeeld met je gekoppelde telefoons. Heb je het deel later, dan verdwijnt het hier vanzelf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Nog niet in huis, wel willen lezen: komt in Lezen onder "Wil ik lezen".
                TextButton(
                    onClick = { onWishlist(ordinal.trim(), title.trim()) },
                    enabled = ordinal.isNotBlank() && title.isNotBlank(),
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Op mijn leeslijst")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ordinal.trim(), title.trim()) }, enabled = ordinal.isNotBlank() && title.isNotBlank()) { Text("Opslaan") }
        },
        dismissButton = {
            Row {
                if (volume.manual && !isNew) TextButton(onClick = onRemove) { Text("Weghalen") }
                TextButton(onClick = onDismiss) { Text("Annuleren") }
            }
        },
    )
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    hint: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    numeric: Boolean = false,
    extraAction: Pair<String, () -> Unit>? = null,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = if (numeric) it.filter(Char::isDigit).take(4) else it },
                    singleLine = true,
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Opslaan") } },
        dismissButton = {
            Row {
                extraAction?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                TextButton(onClick = onDismiss) { Text("Annuleren") }
            }
        },
    )
}
