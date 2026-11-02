package nl.wdhertog.library.ui.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("nl"))

/** Datum als chip; tik om te kiezen. Met [yearOnly] toont hij alleen het jaar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, yearOnly: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { picking = true },
        label = { Text("$label: " + (date?.let { if (yearOnly) it.year.toString() else dayFormat.format(it) } ?: "onbekend")) },
    )
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onChange(null)
                    picking = false
                }) { Text("Onbekend") }
            },
        ) {
            DatePicker(state)
        }
    }
}

fun formatReadDate(date: LocalDate, yearOnly: Boolean): String = if (yearOnly) date.year.toString() else dayFormat.format(date)
