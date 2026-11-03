package nl.wdhertog.library.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import nl.wdhertog.library.importer.ColumnTarget
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.clickable
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.ImportRun
import nl.wdhertog.library.ui.navigation.appViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val runDate = DateTimeFormatter.ofPattern("d-M-yyyy HH:mm")

/** Excel, CSV en TSV; sommige bestandsbeheerders geven CSV als text/plain of octet-stream. */
private val tableTypes = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv",
    "text/comma-separated-values",
    "text/tab-separated-values",
    "text/plain",
    "application/octet-stream",
)

/** Keuzes voor boeken die zeker al in je bibliotheek staan. */
private val mergeChoices = listOf(MergeChoice.FILL, MergeChoice.OVERWRITE, MergeChoice.SKIP)

/** Importeren uit Have I Read That (later ook CSV/Excel), met controle vooraf en ongedaan maken. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    viewModel: ImportViewModel = appViewModel { container, _ ->
        ImportViewModel(container.importService, container.importProfiles, container.enrichScheduler, container.appScope)
    },
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val hirtIncludeAll by viewModel.hirtIncludeAll.collectAsStateWithLifecycle()
    var runToUndo by remember { mutableStateOf<ImportRun?>(null) }
    val pickHirt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.openHirt(context.contentResolver, it) }
    }
    val pickTable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.openTable(context.contentResolver, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importeren") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ImportUiState.Start, is ImportUiState.Error -> Start(
                    error = (s as? ImportUiState.Error)?.message,
                    runs = runs,
                    onPickHirt = { pickHirt.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    hirtIncludeAll = hirtIncludeAll,
                    onHirtIncludeAll = { viewModel.hirtIncludeAll.value = it },
                    onPickTable = { pickTable.launch(tableTypes) },
                    onUndo = { runToUndo = it },
                )
                ImportUiState.Reading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ImportUiState.Mapping -> Mapping(
                    s,
                    onSheet = viewModel::selectSheet,
                    onTarget = viewModel::setTarget,
                    onCancel = viewModel::reset,
                    onNext = viewModel::confirmMapping,
                )
                is ImportUiState.Review -> Review(
                    s,
                    onDefaultChoice = viewModel::setDefaultChoice,
                    onChoice = viewModel::setChoice,
                    onCancel = viewModel::reset,
                    onImport = viewModel::startImport,
                )
                is ImportUiState.Running -> Column(Modifier.align(Alignment.Center).padding(32.dp)) {
                    Text("Importeren… ${s.done} van ${s.total}")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.done.toFloat() / s.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                }
                is ImportUiState.Done -> Column(Modifier.align(Alignment.Center).padding(32.dp)) {
                    Text("Klaar", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("${s.result.added} toegevoegd, ${s.result.updated} bijgewerkt, ${s.result.skipped} overgeslagen.")
                    Text(
                        "Nieuwe boeken met een ISBN worden op de achtergrond aangevuld uit de open bronnen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onBack) { Text("Naar de bibliotheek") }
                        OutlinedButton(onClick = { runs.firstOrNull { it.id == s.result.runId }?.let { runToUndo = it } }) {
                            Text("Ongedaan maken")
                        }
                    }
                }
            }
        }
    }

    runToUndo?.let { run ->
        AlertDialog(
            onDismissRequest = { runToUndo = null },
            title = { Text("Import ongedaan maken?") },
            text = {
                Text(
                    "De ${run.added} boeken die deze import heeft toegevoegd, worden verwijderd. " +
                        "Aanvullingen op boeken die er al stonden (${run.updated}) blijven staan.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.undo(run.id); runToUndo = null }) { Text("Ongedaan maken") } },
            dismissButton = { TextButton(onClick = { runToUndo = null }) { Text("Annuleren") } },
        )
    }
}

@Composable
private fun Start(
    error: String?,
    runs: List<ImportRun>,
    onPickHirt: () -> Unit,
    hirtIncludeAll: Boolean,
    onHirtIncludeAll: (Boolean) -> Unit,
    onPickTable: () -> Unit,
    onUndo: (ImportRun) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            SourceCard(
                title = "Have I Read That",
                text = "JSON-export met leesgeschiedenis, beoordelingen en leesdoel. Opnieuw importeren werkt bij " +
                    "zonder dubbelen.",
                action = {
                    Row(Modifier.fillMaxWidth().clickable { onHirtIncludeAll(!hirtIncludeAll) }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ook boeken die je niet (meer) hebt", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Gelezen, geleend of weggedaan, en e-books en luisterboeken. Ze komen in je leesgeschiedenis, " +
                                    "niet in je boekenkast.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = hirtIncludeAll, onCheckedChange = onHirtIncludeAll)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onPickHirt) { Text("Bestand kiezen…") }
                },
            )
        }
        item {
            SourceCard(
                title = "CSV / Excel",
                text = "Een lijst uit Excel of een andere app (.xlsx, .csv, .tsv). Je kiest zelf per kolom waar " +
                    "hij terechtkomt; de app onthoudt dat voor een volgende keer.",
                action = { Button(onClick = onPickTable) { Text("Bestand kiezen…") } },
            )
        }
        if (runs.isNotEmpty()) {
            item {
                Text("Eerdere imports", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
            items(runs, key = { it.id }) { run ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(run.fileName ?: run.source, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${runDate.format(run.importedAt.atZone(ZoneId.systemDefault()))} · ${run.added} toegevoegd, ${run.updated} bijgewerkt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onUndo(run) }) { Text("Ongedaan maken") }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, text: String, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Review(
    review: ImportUiState.Review,
    onDefaultChoice: (MergeChoice) -> Unit,
    onChoice: (Int, MergeChoice) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Text(review.fileName ?: review.source, style = MaterialTheme.typography.titleMedium)
                Text(review.summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nieuw: ${review.newCount}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { showNew = !showNew }) { Text(if (showNew) "Verbergen" else "Toon") }
                }
            }
            if (showNew) {
                items(review.items.filter { it.match == null }) { item ->
                    Text(
                        listOfNotNull(item.book.title, item.book.authors.firstOrNull()).joinToString(" — "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            if (review.possibleCount > 0) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Mogelijk hetzelfde boek: ${review.possibleCount}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Zelfde titel en auteur, maar een ander ISBN: een andere editie, of hetzelfde exemplaar waarbij " +
                            "het bestand een andere uitgave noemt. Standaard wordt het een apart boek; kies " +
                            "\"Aanvullen\" als het om hetzelfde boek gaat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                itemsIndexed(review.items) { index, item ->
                    val match = item.match?.takeIf { !it.certain } ?: return@itemsIndexed
                    MatchRow(
                        title = match.existing.title.ifBlank { item.book.title },
                        detail = listOfNotNull(match.existing.authors.firstOrNull(), "ISBN ${match.existing.isbn13} ↔ bestand ${item.book.isbn13 ?: "zonder ISBN"}")
                            .joinToString(" · "),
                        choice = review.choiceFor(index),
                        isOverride = index in review.overrides,
                        options = MergeChoice.entries,
                        onChoice = { onChoice(index, it) },
                    )
                }
            }
            if (review.matchedCount > 0) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Al in je bibliotheek: ${review.matchedCount}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Leesgeschiedenis, beoordelingen en tags komen er altijd bij. Wat je zelf hebt ingevuld, blijft staan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        mergeChoices.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = review.defaultChoice == choice,
                                onClick = { onDefaultChoice(choice) },
                                shape = SegmentedButtonDefaults.itemShape(index, mergeChoices.size),
                            ) { Text(choice.label, maxLines = 1) }
                        }
                    }
                    Text(
                        when (review.defaultChoice) {
                            MergeChoice.FILL -> "Alleen lege velden (bijv. een ontbrekend deelnummer) worden aangevuld."
                            MergeChoice.OVERWRITE -> "Titel, auteur, serie, uitgever enz. uit het bestand vervangen wat de bronnen vonden."
                            MergeChoice.SKIP, MergeChoice.ADD -> "Deze boeken blijven helemaal zoals ze zijn."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Text("Per boek aanpassen:", style = MaterialTheme.typography.labelLarge)
                }
                itemsIndexed(review.items) { index, item ->
                    val match = item.match?.takeIf { it.certain } ?: return@itemsIndexed
                    MatchRow(
                        title = match.existing.title.ifBlank { item.book.title },
                        detail = listOfNotNull(match.existing.authors.firstOrNull(), match.reason.label).joinToString(" · "),
                        choice = review.choiceFor(index),
                        isOverride = index in review.overrides,
                        options = mergeChoices,
                        onChoice = { onChoice(index, it) },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Annuleren") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onImport) { Text("Importeren") }
        }
    }
}

@Composable
private fun MatchRow(
    title: String,
    detail: String,
    choice: MergeChoice,
    isOverride: Boolean,
    options: List<MergeChoice>,
    onChoice: (MergeChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Box {
            TextButton(onClick = { open = true }) {
                Text(
                    choice.label,
                    color = if (isOverride) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { onChoice(option); open = false })
                }
            }
        }
    }
}

/** Per kolom: kop, een paar voorbeeldwaarden en waar hij terechtkomt. */
@Composable
private fun Mapping(
    mapping: ImportUiState.Mapping,
    onSheet: (Int) -> Unit,
    onTarget: (Int, ColumnTarget) -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
) {
    val table = mapping.table
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Text(mapping.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("${table.rows.size} rijen", style = MaterialTheme.typography.bodyMedium)
                if (mapping.sheets.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        mapping.sheets.forEachIndexed { index, sheet ->
                            FilterChip(selected = index == mapping.sheetIndex, onClick = { onSheet(index) }, label = { Text(sheet.name) })
                        }
                    }
                }
                Text(
                    "Kies per kolom waar hij terechtkomt. Bij een kolom met 1/0 of ja/nee wordt de kolomnaam " +
                        "de tag of de eigenaar; een notitie wordt \"Kolom: waarde\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (!mapping.hasTitle) {
                    Text("Kies welke kolom de titel bevat.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            itemsIndexed(table.header) { index, header ->
                val samples = table.column(index).filter(String::isNotBlank).distinct().take(3)
                ColumnRow(
                    header = header.ifBlank { "(kolom zonder naam)" },
                    samples = samples.joinToString(" · ").ifEmpty { "leeg" },
                    target = mapping.targets[index],
                    onTarget = { onTarget(index, it) },
                )
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Annuleren") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onNext, enabled = mapping.hasTitle) { Text("Verder") }
        }
    }
}

@Composable
private fun ColumnRow(header: String, samples: String, target: ColumnTarget, onTarget: (ColumnTarget) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(header, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(samples, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }) {
                Text(
                    target.label,
                    color = if (target == ColumnTarget.IGNORE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ColumnTarget.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { onTarget(option); open = false })
                }
            }
        }
    }
}
