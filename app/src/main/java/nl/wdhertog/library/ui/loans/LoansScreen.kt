package nl.wdhertog.library.ui.loans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.BorrowedRow
import nl.wdhertog.library.data.db.LoanRow
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.components.rememberNotificationPermissionRequest
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.util.LoanDates
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoansScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: LoansViewModel = appViewModel { container, _ ->
        LoansViewModel(container.loanRepository, container.bookRepository)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val names by viewModel.personNames.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<BookListItem?>(null) }
    var returning by remember { mutableStateOf<BorrowedRow?>(null) }
    val requestNotifications = rememberNotificationPermissionRequest()
    val today = remember { LocalDate.now() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Uitleen") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (tab == 0) "Uitlenen" else "Geleend boek") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Uitgeleend (${state.active.size})") },
                    icon = { Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Geleend (${state.borrowed.size})") },
                    icon = { Icon(Icons.AutoMirrored.Filled.CallReceived, contentDescription = null) })
            }
            when {
                !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                tab == 0 -> LentOutList(state, today, onOpenBook, viewModel::markReturned)
                else -> BorrowedList(state.borrowed, today, onOpenBook, onReturned = { returning = it })
            }
        }
    }

    if (picking) {
        BookPickerDialog(
            title = if (tab == 0) "Welk boek leen je uit?" else "Welk boek heb je geleend?",
            books = state.available,
            onPick = { picking = false; chosen = it },
            onDismiss = { picking = false },
        )
    }
    chosen?.let { item ->
        val lending = tab == 0
        LoanDialog(
            title = if (lending) "Uitlenen" else "Geleend van iemand",
            bookTitle = item.book.displayTitle,
            personLabel = if (lending) "Aan wie?" else "Van wie?",
            personSuggestions = names,
            showNote = lending,
            onConfirm = { person, due, note ->
                if (lending) viewModel.lend(item.book.id, person, due, note) else viewModel.markBorrowed(item.book.id, person, due)
                if (due != null) requestNotifications()
                chosen = null
            },
            onDismiss = { chosen = null },
        )
    }
    returning?.let { row ->
        BorrowedActionsDialog(
            bookTitle = row.title.ifBlank { "Boek" },
            onKeep = { viewModel.markBorrowedReturned(row.bookId, keep = true); returning = null },
            onDelete = { viewModel.markBorrowedReturned(row.bookId, keep = false); returning = null },
            onOwned = { viewModel.markOwned(row.bookId); returning = null },
            onDismiss = { returning = null },
        )
    }
}

@Composable
private fun LentOutList(state: LoansUiState, today: LocalDate, onOpenBook: (Long) -> Unit, onReturned: (Long) -> Unit) {
    if (state.active.isEmpty() && state.returned.isEmpty()) {
        Empty("Je hebt nog niets uitgeleend.\nTik op Uitlenen, of leen een boek uit vanaf de boekpagina.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        items(state.active, key = { "a${it.loanId}" }) { loan ->
            ItemRow(
                title = loan.displayTitle,
                coverUrl = loan.coverUrl,
                line = listOfNotNull(loan.personName?.let { "aan $it" } ?: "aan onbekend", loan.lentAt?.let { LoanDates.since(it, today) })
                    .joinToString(" · "),
                due = loan.dueAt?.let { LoanDates.due(it, today) },
                action = "Teruggekregen",
                onAction = { onReturned(loan.loanId) },
                onClick = { onOpenBook(loan.bookId) },
            )
            HorizontalDivider(Modifier.padding(start = 68.dp))
        }
        if (state.returned.isNotEmpty()) {
            item { Header("Eerder uitgeleend") }
            items(state.returned, key = { "r${it.loanId}" }) { loan ->
                val period = listOfNotNull(loan.lentAt, loan.returnedAt).joinToString(" – ") { LoanDates.format(it, today) }
                ItemRow(
                    title = loan.displayTitle,
                    coverUrl = loan.coverUrl,
                    line = listOfNotNull(loan.personName, period.ifEmpty { null }).joinToString(" · "),
                    due = null,
                    onClick = { onOpenBook(loan.bookId) },
                )
            }
        }
    }
}

@Composable
private fun BorrowedList(rows: List<BorrowedRow>, today: LocalDate, onOpenBook: (Long) -> Unit, onReturned: (BorrowedRow) -> Unit) {
    if (rows.isEmpty()) {
        Empty("Geen geleende boeken.\nScan een geleend boek en markeer het op de boekpagina als geleend,\nof tik op Geleend boek.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        items(rows, key = { it.bookId }) { row ->
            ItemRow(
                title = row.title.ifBlank { row.isbn13?.let { "ISBN $it" } ?: "Zonder titel" },
                coverUrl = row.coverUrl,
                line = row.personName?.let { "van $it" } ?: "van onbekend",
                due = row.dueAt?.let { LoanDates.due(it, today) },
                action = "Teruggebracht",
                onAction = { onReturned(row) },
                onClick = { onOpenBook(row.bookId) },
            )
            HorizontalDivider(Modifier.padding(start = 68.dp))
        }
    }
}

private val LoanRow.displayTitle: String get() = title.ifBlank { isbn13?.let { "ISBN $it" } ?: "Zonder titel" }

@Composable
private fun ItemRow(
    title: String,
    coverUrl: String?,
    line: String,
    due: LoanDates.DueStatus?,
    onClick: () -> Unit,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(title, coverUrl, Modifier.size(width = 36.dp, height = 52.dp), showTitle = false)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            due?.let {
                Text(
                    if (it.overdue) "⚠ ${it.text}" else it.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (it.overdue || it.dueSoon) FontWeight.SemiBold else null,
                    color = when {
                        it.overdue -> MaterialTheme.colorScheme.error
                        it.dueSoon -> MaterialTheme.colorScheme.tertiary
                        else -> Color.Unspecified
                    },
                )
            }
        }
        action?.let { OutlinedButton(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
