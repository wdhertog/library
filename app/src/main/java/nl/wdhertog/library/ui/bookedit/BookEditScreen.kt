package nl.wdhertog.library.ui.bookedit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import nl.wdhertog.library.ui.components.AppCard
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.ui.components.SuggestionTextField
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.util.Languages
import nl.wdhertog.library.util.TextNormalizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookEditScreen(
    onClose: () -> Unit,
    onSaved: (bookId: Long, isNew: Boolean) -> Unit,
    viewModel: BookEditViewModel = appViewModel { container, handle ->
        BookEditViewModel(handle, container.bookRepository, container.enrichScheduler)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authorSuggestions by viewModel.authorSuggestions.collectAsStateWithLifecycle()
    val seriesSuggestions by viewModel.seriesSuggestions.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedBookId) {
        state.savedBookId?.let { onSaved(it, viewModel.isNew) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "Boek toevoegen" else "Boek bewerken") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Sluiten") }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.loading && !state.saving) {
                        Text("Opslaan")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val draft = state.draft
        val errors = state.showErrors
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditCard("Titel en auteurs") {
                TextInput("Titel *", draft.title, { v -> viewModel.update { it.copy(title = v) } },
                    error = state.titleError.takeIf { errors })
                TextInput("Ondertitel", draft.subtitle, { v -> viewModel.update { it.copy(subtitle = v) } })

                draft.authors.forEachIndexed { index, author ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuggestionTextField(
                            label = if (index == 0) "Auteur" else "Auteur ${index + 1}",
                            value = author,
                            suggestions = authorSuggestions,
                            onValueChange = { v ->
                                viewModel.update { d -> d.copy(authors = d.authors.toMutableList().also { it[index] = v }) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (draft.authors.size > 1) {
                            IconButton(onClick = {
                                viewModel.update { d -> d.copy(authors = d.authors.toMutableList().also { it.removeAt(index) }) }
                            }) { Icon(Icons.Default.Close, contentDescription = "Auteur verwijderen") }
                        }
                    }
                }
                TextButton(onClick = { viewModel.update { it.copy(authors = it.authors + "") } }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Auteur toevoegen")
                }
                TextInput("Oorspronkelijke titel", draft.originalTitle, { v -> viewModel.update { it.copy(originalTitle = v) } })
            }
            EditCard("Uitgave") {
                TextInput("ISBN", draft.isbn, { v -> viewModel.update { it.copy(isbn = v) } },
                    keyboardType = KeyboardType.Number,
                    error = state.isbnError.takeIf { errors },
                    supporting = if (state.duplicateBookId != null) "Dit ISBN staat al in je collectie" else null)
                LanguageInput(draft.language) { v -> viewModel.update { it.copy(language = v) } }
                TextInput("Uitgever", draft.publisher, { v -> viewModel.update { it.copy(publisher = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("Jaar", draft.publishedYear, { v -> viewModel.update { it.copy(publishedYear = v) } },
                        Modifier.weight(1f), keyboardType = KeyboardType.Number, error = state.yearError.takeIf { errors })
                    TextInput("Pagina's", draft.pages, { v -> viewModel.update { it.copy(pages = v) } },
                        Modifier.weight(1f), keyboardType = KeyboardType.Number, error = state.pagesError.takeIf { errors })
                }

                Text("Formaat", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookFormat.entries.forEach { format ->
                        FilterChip(
                            selected = draft.format == format,
                            onClick = { viewModel.update { it.copy(format = if (it.format == format) null else format) } },
                            label = { Text(format.label) },
                        )
                    }
                }
            }
            EditCard("Serie") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SuggestionTextField("Serie", draft.seriesName, seriesSuggestions,
                        { v -> viewModel.update { it.copy(seriesName = v) } }, Modifier.weight(2f))
                    TextInput("Deel", draft.seriesIndex, { v -> viewModel.update { it.copy(seriesIndex = v) } },
                        Modifier.weight(1f))
                }
            }
            EditCard("Collectie") {
                TextInput("Tags (gescheiden door komma's)", draft.tags, { v -> viewModel.update { it.copy(tags = v) } })
                TextInput("Beschrijving", draft.description, { v -> viewModel.update { it.copy(description = v) } },
                    singleLine = false)
                TextInput("Notities", draft.notes, { v -> viewModel.update { it.copy(notes = v) } }, singleLine = false)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Op mijn leeslijst", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = draft.wantToRead, onCheckedChange = { v -> viewModel.update { it.copy(wantToRead = v) } })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Een groep velden in een kaart, zoals op de boekpagina. */
@Composable
private fun EditCard(title: String, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    error: String? = null,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageInput(code: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = if (code.isEmpty()) "Onbekend" else Languages.displayName(code),
            onValueChange = {},
            readOnly = true,
            label = { Text("Taal") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Onbekend") }, onClick = { onSelect(""); open = false })
            Languages.primary.forEach { lang ->
                DropdownMenuItem(text = { Text(Languages.displayName(lang)) }, onClick = { onSelect(lang); open = false })
            }
            HorizontalDivider()
            Languages.others.forEach { lang ->
                DropdownMenuItem(text = { Text(Languages.displayName(lang)) }, onClick = { onSelect(lang); open = false })
            }
        }
    }
}
