package nl.wdhertog.library.ui.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.ui.components.DateField
import nl.wdhertog.library.ui.components.RatingInput
import nl.wdhertog.library.ui.components.formatRating
import kotlin.math.roundToInt

/**
 * Leessessie vastleggen of wijzigen: status, datums, beoordeling en recensie.
 * Ook voor "uitgelezen", "gestopt" en een boek dat je vroeger las.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDialog(
    title: String,
    initial: ReadingSession,
    onSave: (ReadingSession) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var status by remember { mutableStateOf(initial.status) }
    var started by remember { mutableStateOf(initial.startedAt) }
    var finished by remember { mutableStateOf(initial.finishedAt) }
    var yearOnly by remember { mutableStateOf(initial.datePrecision == DatePrecision.YEAR) }
    var rating by remember { mutableStateOf(initial.rating) }
    var review by remember { mutableStateOf(initial.review.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingStatus.entries.forEach { option ->
                        FilterChip(selected = status == option, onClick = { status = option }, label = { Text(option.label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("Begonnen", started, { started = it }, yearOnly = yearOnly)
                    if (status != ReadingStatus.READING) {
                        DateField(if (status == ReadingStatus.FINISHED) "Uit" else "Gestopt", finished, { finished = it }, yearOnly = yearOnly)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = yearOnly, onCheckedChange = { yearOnly = it })
                    Text("Alleen het jaar is bekend", style = MaterialTheme.typography.bodyMedium)
                }
                if (status != ReadingStatus.READING) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RatingInput(rating, { rating = it })
                        rating?.let { Text(formatRating(it)) }
                    }
                    OutlinedTextField(
                        value = review,
                        onValueChange = { review = it },
                        label = { Text("Recensie (optioneel)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        status = status,
                        startedAt = started,
                        finishedAt = finished.takeIf { status != ReadingStatus.READING },
                        datePrecision = if (yearOnly) DatePrecision.YEAR else DatePrecision.DAY,
                        rating = rating.takeIf { status != ReadingStatus.READING },
                        review = review.takeIf { status != ReadingStatus.READING },
                    ),
                )
            }) { Text("Opslaan") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Verwijderen") }
                TextButton(onClick = onDismiss) { Text("Annuleren") }
            }
        },
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Leessessie verwijderen?") },
            text = { Text("Deze sessie verdwijnt uit je leesgeschiedenis en statistiek.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Verwijderen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
        )
    }
}

/** Hoe ver ben je? Pagina of percentage; met een bekend aantal pagina's rekent het een het ander uit. */
@Composable
fun ProgressDialog(
    session: ReadingSession,
    pages: Int?,
    onSave: (page: Int?, percent: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(session.progressPage?.toString().orEmpty()) }
    var percent by remember { mutableStateOf(session.progressPercent?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Voortgang") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = page,
                    onValueChange = { text ->
                        page = text.filter(Char::isDigit)
                        val p = page.toIntOrNull()
                        if (p != null && pages != null && pages > 0) percent = (p * 100.0 / pages).roundToInt().coerceAtMost(100).toString()
                    },
                    label = { Text(if (pages != null) "Pagina (van $pages)" else "Pagina") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = percent,
                    onValueChange = { text ->
                        percent = text.filter(Char::isDigit).take(3)
                        val p = percent.toIntOrNull()
                        if (p != null && pages != null && pages > 0) page = (pages * p / 100.0).roundToInt().toString()
                    },
                    label = { Text("Procent") },
                    suffix = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(page.toIntOrNull(), percent.toIntOrNull()?.coerceIn(0, 100)) }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Leesdoel voor een jaar; leeg = geen doel. */
@Composable
fun GoalDialog(year: Int, current: Int?, onSave: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Leesdoel $year") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                label = { Text("Aantal boeken") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull()) }) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}
