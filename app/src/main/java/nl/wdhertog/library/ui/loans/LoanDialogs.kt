package nl.wdhertog.library.ui.loans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.data.repository.cover
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.components.SuggestionTextField
import nl.wdhertog.library.util.LoanDates
import nl.wdhertog.library.util.TextNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class DueChoice(val label: String, val pick: (LocalDate) -> LocalDate?) {
    NONE("Geen", { null }),
    TWO_WEEKS("2 weken", { it.plusWeeks(2) }),
    MONTH("1 maand", { it.plusMonths(1) }),
    THREE_MONTHS("3 maanden", { it.plusMonths(3) }),
}

/**
 * Uitlenen aan iemand, of vastleggen dat je een boek van iemand hebt geleend.
 * Dezelfde vragen: wie, en wanneer moet het terug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanDialog(
    title: String,
    bookTitle: String?,
    personLabel: String,
    personSuggestions: List<String>,
    onConfirm: (person: String, dueAt: LocalDate?, note: String?) -> Unit,
    onDismiss: () -> Unit,
    showNote: Boolean = true,
    initialPerson: String = "",
    initialDue: LocalDate? = null,
) {
    val today = remember { LocalDate.now() }
    var person by remember { mutableStateOf(initialPerson) }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(initialDue) }
    var pickingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                SuggestionTextField(personLabel, person, personSuggestions, onValueChange = { person = it }, modifier = Modifier.fillMaxWidth())
                Text("Terug vóór", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DueChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = due == choice.pick(today),
                            onClick = { due = choice.pick(today) },
                            label = { Text(choice.label) },
                        )
                    }
                    val custom = due?.takeIf { d -> DueChoice.entries.none { it.pick(today) == d } }
                    FilterChip(
                        selected = custom != null,
                        onClick = { pickingDate = true },
                        label = { Text(custom?.let { LoanDates.format(it, today) } ?: "Datum…") },
                    )
                }
                due?.let {
                    Text(
                        "Je krijgt een herinnering op ${LoanDates.format(it, today)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showNote) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Notitie (optioneel)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(person.trim(), due, note) }, enabled = person.isNotBlank()) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (due ?: today.plusWeeks(4)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Annuleren") } },
        ) {
            DatePicker(state)
        }
    }
}

/**
 * Geleend boek: teruggebracht (bewaren voor je leesgeschiedenis, of uit de bibliotheek halen),
 * of toch je eigen boek (vergissing, of gekocht).
 */
@Composable
fun BorrowedActionsDialog(
    bookTitle: String,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onOwned: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(bookTitle) },
        text = {
            Column {
                Text("Teruggebracht:", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onKeep) { Text("Bewaren als \"niet meer in bezit\"") }
                Text(
                    "Handig als je het gelezen hebt: het blijft in je leesgeschiedenis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
                TextButton(onClick = onDelete) { Text("Uit de bibliotheek verwijderen") }
                Spacer(Modifier.height(8.dp))
                Text("Of:", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onOwned) { Text("Toch mijn eigen boek") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Boek weggedaan: hoe, aan wie (optioneel) en wanneer (standaard vandaag). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DisposeDialog(
    bookTitle: String,
    personSuggestions: List<String>,
    onConfirm: (reason: DisposalReason, to: String?, date: LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var reason by remember { mutableStateOf(DisposalReason.GIVEN_AWAY) }
    var person by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(today) }
    var pickingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Weggedaan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(bookTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisposalReason.entries.forEach { option ->
                        FilterChip(selected = reason == option, onClick = { reason = option }, label = { Text(option.label) })
                    }
                }
                if (reason == DisposalReason.GIVEN_AWAY || reason == DisposalReason.SOLD) {
                    SuggestionTextField("Aan wie? (optioneel)", person, personSuggestions, onValueChange = { person = it }, modifier = Modifier.fillMaxWidth())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = date == today, onClick = { date = today }, label = { Text("Vandaag") })
                    FilterChip(
                        selected = date != null && date != today,
                        onClick = { pickingDate = true },
                        label = { Text(date?.takeIf { it != today }?.let { LoanDates.format(it, today) } ?: "Datum…") },
                    )
                    FilterChip(selected = date == null, onClick = { date = null }, label = { Text("Onbekend") })
                }
                Text(
                    "Het boek blijft in je bibliotheek als \"niet meer in bezit\", zodat je leesgeschiedenis bewaard blijft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val to = person.trim().takeIf { reason == DisposalReason.GIVEN_AWAY || reason == DisposalReason.SOLD }
                onConfirm(reason, to, date)
            }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Annuleren") } },
        ) {
            DatePicker(state)
        }
    }
}

/** Van wie het boek is; leeg laten = niet ingevuld. */
@Composable
fun OwnerDialog(current: String?, suggestions: List<String>, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var owner by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Van wie is dit boek?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionTextField("Eigenaar", owner, suggestions, onValueChange = { owner = it }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (suggestions + "Samen").distinct().forEach { name ->
                        FilterChip(selected = owner == name, onClick = { owner = name }, label = { Text(name) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(owner.trim().ifEmpty { null }) }) { Text("Opslaan") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onConfirm(null) }) { Text("Wissen") }
                TextButton(onClick = onDismiss) { Text("Annuleren") }
            }
        },
    )
}

/** Bevestiging voor het weghalen van een uitlening (ook uit de geschiedenis). */
@Composable
fun DeleteLoanDialog(personName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uitlening verwijderen?") },
        text = { Text("De uitlening aan ${personName ?: "onbekend"} wordt helemaal verwijderd, ook uit de geschiedenis. Het boek blijft gewoon in je bibliotheek.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Verwijderen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Kies een boek uit je bibliotheek (alleen boeken die nu uitgeleend kunnen worden). */
@Composable
fun BookPickerDialog(
    title: String,
    books: List<BookListItem>,
    onPick: (BookListItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val words = TextNormalizer.normalize(query).split(' ').filter(String::isNotEmpty)
    val matches = remember(books, query) {
        books.filter { item -> words.all { item.book.searchText.contains(it) } }.take(50)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Zoek titel, auteur, ISBN") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(matches, key = { it.book.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(item.book.title, item.book.cover, Modifier.size(width = 32.dp, height = 48.dp), showTitle = false)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(item.book.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.authorLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}
