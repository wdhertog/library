package nl.wdhertog.library.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.wdhertog.library.data.db.BorrowedRow
import nl.wdhertog.library.data.db.LoanRow
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.util.LoanDates
import java.time.LocalDate

/** Balk bovenaan bij een boek dat je zelf van iemand hebt geleend. */
@Composable
fun BorrowedBanner(row: BorrowedRow, onReturned: () -> Unit) {
    val today = LocalDate.now()
    val due = row.dueAt?.let { LoanDates.due(it, today) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (due?.overdue == true) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.CallReceived, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Geleend van ${row.personName ?: "onbekend"}", style = MaterialTheme.typography.bodyLarge)
                due?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onReturned) { Text("Teruggebracht") }
        }
    }
}

/** Uitlenen: de huidige uitlening, knoppen, en eerdere uitleningen. */
@Composable
fun LoanSection(
    ownership: Ownership,
    loans: List<LoanRow>,
    onLend: () -> Unit,
    onReturned: (Long) -> Unit,
    onMarkBorrowed: () -> Unit,
    onMarkOwned: () -> Unit,
    onDispose: () -> Unit,
    onDelete: (LoanRow) -> Unit,
    disposal: String? = null,
    /** Uitlenen en teruggekregen staan al als knop bovenaan de boekpagina. */
    hidePrimary: Boolean = false,
) {
    val today = LocalDate.now()
    val active = loans.firstOrNull { it.returnedAt == null }
    Column {
        when {
            active != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Uitgeleend aan ${active.personName ?: "onbekend"}" + (active.lentAt?.let { ", ${LoanDates.since(it, today)}" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    active.dueAt?.let {
                        val due = LoanDates.due(it, today)
                        Text(
                            due.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (due.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    active.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!hidePrimary) OutlinedButton(onClick = { onReturned(active.loanId) }) { Text("Teruggekregen") }
                DeleteButton { onDelete(active) }
            }
            ownership == Ownership.OWNED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hidePrimary) OutlinedButton(onClick = onLend) { Text("Uitlenen…") }
                TextButton(onClick = onMarkBorrowed) { Text("Dit boek is geleend…") }
                TextButton(onClick = onDispose) { Text("Weggedaan…") }
            }
            ownership == Ownership.NOT_OWNED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(disposal ?: "Niet (meer) in bezit", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onMarkOwned) { Text("Toch in bezit") }
            }
        }
        val history = loans.filter { it.returnedAt != null }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Eerder uitgeleend", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.forEach { loan ->
                val period = listOfNotNull(loan.lentAt, loan.returnedAt).joinToString(" – ") { LoanDates.format(it, today) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(loan.personName ?: "onbekend", period.ifEmpty { null }).joinToString(" · "),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DeleteButton { onDelete(loan) }
                }
            }
        }
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.DeleteOutline, contentDescription = "Uitlening verwijderen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
