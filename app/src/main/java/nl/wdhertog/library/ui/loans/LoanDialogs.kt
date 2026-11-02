package nl.wdhertog.library.ui.loans

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
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.displayTitle
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

/** Geleend boek teruggebracht: bewaren (voor je leesgeschiedenis) of uit de bibliotheek halen. */
@Composable
fun BorrowedReturnDialog(bookTitle: String, onKeep: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\"$bookTitle\" teruggebracht") },
        text = {
            Text("Wil je het boek in je bibliotheek houden als \"niet meer in bezit\", bijvoorbeeld omdat je het gelezen hebt? Of helemaal verwijderen?")
        },
        confirmButton = { TextButton(onClick = onKeep) { Text("Bewaren") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Verwijderen") } },
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
                            CoverImage(item.book.title, item.book.coverUrl, Modifier.size(width = 32.dp, height = 48.dp), showTitle = false)
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
