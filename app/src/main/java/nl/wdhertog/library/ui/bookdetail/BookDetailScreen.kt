package nl.wdhertog.library.ui.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import nl.wdhertog.library.data.repository.editionFacts
import nl.wdhertog.library.data.repository.Editions
import nl.wdhertog.library.util.Share
import androidx.compose.material.icons.filled.Share
import nl.wdhertog.library.ui.theme.Literata
import nl.wdhertog.library.ui.components.rememberCoverTint
import nl.wdhertog.library.ui.components.AppCard
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.repository.BookDetail
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.ui.locations.LocationPickerDialog
import nl.wdhertog.library.ui.loans.BorrowedActionsDialog
import nl.wdhertog.library.ui.loans.DeleteLoanDialog
import nl.wdhertog.library.ui.loans.DisposeDialog
import nl.wdhertog.library.ui.loans.BookPickerDialog
import nl.wdhertog.library.ui.loans.OwnerDialog
import nl.wdhertog.library.ui.reading.ProgressDialog
import nl.wdhertog.library.ui.reading.SessionDialog
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.util.LoanDates
import java.time.LocalDate
import nl.wdhertog.library.ui.loans.LoanDialog
import nl.wdhertog.library.ui.components.rememberNotificationPermissionRequest
import androidx.compose.material.icons.filled.Place
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.data.repository.label
import nl.wdhertog.library.data.repository.cover
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.util.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenLocation: (Long) -> Unit,
    onPhotoCover: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    viewModel: BookDetailViewModel = appViewModel { container, handle ->
        BookDetailViewModel(
            handle,
            container.bookRepository,
            container.enrichmentService,
            container.locationRepository,
            container.loanRepository,
            container.readingRepository,
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tree by viewModel.locationTree.collectAsStateWithLifecycle()
    val locationIndex = remember(tree) { LocationTree.index(tree) }
    var pickingLocation by rememberSaveable { mutableStateOf(false) }
    val bookLoans by viewModel.bookLoans.collectAsStateWithLifecycle()
    val borrowed by viewModel.borrowed.collectAsStateWithLifecycle()
    val personNames by viewModel.personNames.collectAsStateWithLifecycle()
    val ownerNames by viewModel.ownerNames.collectAsStateWithLifecycle()
    var editingOwner by rememberSaveable { mutableStateOf(false) }
    var editionDialog by rememberSaveable { mutableStateOf<String?>(null) }
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    var sessionDialog by remember { mutableStateOf<Pair<String, ReadingSession>?>(null) }
    var progressFor by remember { mutableStateOf<ReadingSession?>(null) }
    var loanDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var loanToDelete by remember { mutableStateOf<nl.wdhertog.library.data.db.LoanRow?>(null) }
    val requestNotifications = rememberNotificationPermissionRequest()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val loadedBook = (state as? BookDetailUiState.Loaded)?.detail?.book
    // Kleurvlak uit de cover, doorlopend achter de titelbalk (schetsen).
    val tint = rememberCoverTint(loadedBook?.title.orEmpty(), loadedBook?.cover)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = if (loadedBook != null) tint else MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                    }
                },
                actions = {
                    (state as? BookDetailUiState.Loaded)?.detail?.book?.let { book ->
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                if (book.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (book.favorite) "Geen favoriet meer" else "Favoriet",
                                tint = if (book.favorite) MaterialTheme.colorScheme.secondary else LocalContentColor.current,
                            )
                        }
                    }
                    if (state is BookDetailUiState.Loaded) {
                        IconButton(onClick = { onEdit(viewModel.bookId) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Bewerken")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Meer")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delen…") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        (state as? BookDetailUiState.Loaded)?.detail?.let { Share.open(context, Share.book(it.book, it.authors)) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cover fotograferen") },
                                    leadingIcon = { Icon(Icons.Default.AddAPhoto, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onPhotoCover(viewModel.bookId)
                                    },
                                )
                                if ((state as? BookDetailUiState.Loaded)?.detail?.book?.coverFile != null) {
                                    DropdownMenuItem(
                                        text = { Text("Eigen cover-foto weghalen") },
                                        leadingIcon = { Icon(Icons.Default.HideImage, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.removeCoverPhoto()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Koppelen aan andere editie…") },
                                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        editionDialog = "link"
                                    },
                                )
                                if ((state as? BookDetailUiState.Loaded)?.detail?.otherEditions?.isNotEmpty() == true) {
                                    DropdownMenuItem(
                                        text = { Text("Loskoppelen van andere edities") },
                                        leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            editionDialog = "split"
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Verwijderen") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                BookDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                BookDetailUiState.NotFound -> Text("Dit boek bestaat niet meer.", Modifier.align(Alignment.Center))
                is BookDetailUiState.Loaded -> DetailContent(
                    detail = s.detail,
                    tint = tint,
                    primaryAction = run {
                        // Eén hoofdknop die meebeweegt met waar je bent met dit boek.
                        val current = sessions.firstOrNull { it.session.status == ReadingStatus.READING && it.session.bookId == s.detail.book.id }?.session
                        when {
                            current != null -> "Voortgang bijwerken" to { progressFor = current }
                            sessions.any { it.session.status == ReadingStatus.FINISHED } -> "Opnieuw lezen" to viewModel::startReading
                            else -> "Beginnen met lezen" to viewModel::startReading
                        }
                    },
                    secondaryAction = run {
                        val active = bookLoans.firstOrNull { it.returnedAt == null }
                        when (s.detail.book.ownership) {
                            Ownership.OWNED -> if (active != null) "Teruggekregen" to { viewModel.markReturned(active.loanId) } else "Uitlenen" to { loanDialog = "lend" }
                            Ownership.BORROWED -> "Teruggebracht…" to { loanDialog = "returned" }
                            Ownership.NOT_OWNED -> null
                        }
                    },
                    locationLabel = { id -> id?.let { locationIndex[it]?.pathLabel } },
                    onOpenBook = onOpenBook,
                    onSetPrimary = viewModel::setPrimaryEdition,
                    onOpenLocation = onOpenLocation,
                    onPickLocation = { pickingLocation = true },
                    onEditOwner = { editingOwner = true },
                    onOpenSeries = onOpenSeries,
                    onOpenAuthor = onOpenAuthor,
                    onPhotoCover = { onPhotoCover(viewModel.bookId) },
                    refreshing = refreshing,
                    onRefresh = viewModel::refresh,
                    banner = { borrowed?.let { BorrowedBanner(it, onReturned = { loanDialog = "returned" }) } },
                    readingSection = {
                        ReadingSection(
                            bookId = s.detail.book.id,
                            pages = s.detail.book.pages,
                            wantToRead = s.detail.book.wantToRead,
                            sessions = sessions,
                            onStart = viewModel::startReading,
                            onProgress = { progressFor = it },
                            onFinish = { session, status ->
                                sessionDialog = (if (status == ReadingStatus.FINISHED) "Uitgelezen" else "Gestopt") to
                                    session.copy(status = status, finishedAt = LocalDate.now())
                            },
                            onAddPast = {
                                sessionDialog = "Al gelezen" to
                                    ReadingSession(bookId = s.detail.book.id, status = ReadingStatus.FINISHED, finishedAt = LocalDate.now())
                            },
                            onEdit = { sessionDialog = "Leessessie" to it },
                            onToggleWantToRead = viewModel::toggleWantToRead,
                            hidePrimary = true,
                        )
                    },
                    // Een geleend boek leen je niet uit; zonder geschiedenis valt er dan niets te tonen.
                    loanSection = if (s.detail.book.ownership == Ownership.BORROWED && bookLoans.isEmpty()) {
                        null
                    } else {
                        {
                            LoanSection(
                                ownership = s.detail.book.ownership,
                                loans = bookLoans,
                                onLend = { loanDialog = "lend" },
                                onReturned = viewModel::markReturned,
                                onMarkBorrowed = { loanDialog = "borrow" },
                                onMarkOwned = viewModel::markOwned,
                                onDispose = { loanDialog = "dispose" },
                                onDelete = { loanToDelete = it },
                                disposal = disposalText(s.detail.book),
                                hidePrimary = true,
                                onAsk = { loan ->
                                    Share.open(
                                        context,
                                        Share.askAboutLoan(s.detail.book.displayTitle, s.detail.authors, loan.personName, loan.lentAt, loan.dueAt),
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    val bookTitle = (state as? BookDetailUiState.Loaded)?.detail?.book?.displayTitle.orEmpty()
    when (loanDialog) {
        "lend", "borrow" -> {
            val lending = loanDialog == "lend"
            LoanDialog(
                title = if (lending) "Uitlenen" else "Geleend van iemand",
                bookTitle = bookTitle,
                personLabel = if (lending) "Aan wie?" else "Van wie?",
                personSuggestions = personNames,
                showNote = lending,
                onConfirm = { person, due, note ->
                    if (lending) viewModel.lend(person, due, note) else viewModel.markBorrowed(person, due)
                    if (due != null) requestNotifications()
                    loanDialog = null
                },
                onDismiss = { loanDialog = null },
            )
        }
        "dispose" -> DisposeDialog(
            bookTitle = bookTitle,
            personSuggestions = personNames,
            onConfirm = { reason, to, date -> viewModel.markDisposed(reason, to, date); loanDialog = null },
            onDismiss = { loanDialog = null },
        )
        "returned" -> BorrowedActionsDialog(
            bookTitle = bookTitle,
            onKeep = { viewModel.markBorrowedReturned(keep = true, onDeleted = onBack); loanDialog = null },
            onDelete = { viewModel.markBorrowedReturned(keep = false, onDeleted = onBack); loanDialog = null },
            onOwned = { viewModel.markOwned(); loanDialog = null },
            onDismiss = { loanDialog = null },
        )
    }
    loanToDelete?.let { loan ->
        DeleteLoanDialog(
            personName = loan.personName,
            onConfirm = { viewModel.deleteLoan(loan.loanId); loanToDelete = null },
            onDismiss = { loanToDelete = null },
        )
    }

    sessionDialog?.let { (title, session) ->
        SessionDialog(
            title = title,
            initial = session,
            onSave = { viewModel.saveSession(it); sessionDialog = null },
            onDismiss = { sessionDialog = null },
            onDelete = if (session.id != 0L) ({ viewModel.deleteSession(session.id); sessionDialog = null }) else null,
        )
    }
    progressFor?.let { session ->
        ProgressDialog(
            session = session,
            pages = (state as? BookDetailUiState.Loaded)?.detail?.book?.pages,
            onSave = { page, percent -> viewModel.updateProgress(session.id, page, percent); progressFor = null },
            onDismiss = { progressFor = null },
        )
    }

    val loadedDetail = (state as? BookDetailUiState.Loaded)?.detail
    when (editionDialog) {
        "link" -> {
            // Alleen zolang de dialoog open is: de hele bibliotheek is hier anders voor niets actief.
            val otherBooks by viewModel.otherBooks.collectAsStateWithLifecycle()
            val sameWork = loadedDetail?.otherEditions?.map { it.id }.orEmpty().toSet()
            BookPickerDialog(
                title = "Editie of vertaling van welk boek?",
                books = otherBooks.filter { it.book.id !in sameWork },
                onPick = { other -> editionDialog = null; viewModel.linkAsSameWork(other.book.id) },
                onDismiss = { editionDialog = null },
            )
        }
        "split" -> AlertDialog(
            onDismissRequest = { editionDialog = null },
            title = { Text("Loskoppelen?") },
            text = {
                Text(
                    "Dit boek hoort dan niet meer bij ${loadedDetail?.otherEditions?.size ?: 0} andere " +
                        "${if (loadedDetail?.otherEditions?.size == 1) "editie" else "edities"} en wordt een eigen werk. " +
                        "Het wordt daarna niet meer automatisch gekoppeld.",
                )
            },
            confirmButton = { TextButton(onClick = { editionDialog = null; viewModel.splitFromWork() }) { Text("Loskoppelen") } },
            dismissButton = { TextButton(onClick = { editionDialog = null }) { Text("Annuleren") } },
        )
    }

    if (editingOwner) {
        OwnerDialog(
            current = (state as? BookDetailUiState.Loaded)?.detail?.book?.owner,
            suggestions = ownerNames,
            onConfirm = { viewModel.setOwner(it); editingOwner = false },
            onDismiss = { editingOwner = false },
        )
    }

    if (pickingLocation) {
        LocationPickerDialog(
            title = "Waar staat dit boek?",
            tree = tree,
            selectedId = (state as? BookDetailUiState.Loaded)?.detail?.book?.locationId,
            noneLabel = "Geen locatie",
            onSelect = {
                viewModel.moveTo(it)
                pickingLocation = false
            },
            onCreate = viewModel::createLocation,
            onDismiss = { pickingLocation = false },
        )
    }

    if (confirmDelete) {
        val title = (state as? BookDetailUiState.Loaded)?.detail?.book?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Boek verwijderen?") },
            text = { Text("\"$title\" wordt uit je bibliotheek verwijderd.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text("Verwijderen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
        )
    }
}

@Composable
private fun DetailContent(
    detail: BookDetail,
    tint: Color,
    primaryAction: Pair<String, () -> Unit>,
    secondaryAction: Pair<String, () -> Unit>?,
    locationLabel: (Long?) -> String?,
    onOpenBook: (Long) -> Unit,
    onSetPrimary: () -> Unit,
    onOpenLocation: (Long) -> Unit,
    onPickLocation: () -> Unit,
    onEditOwner: () -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onPhotoCover: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    banner: @Composable () -> Unit,
    readingSection: @Composable () -> Unit,
    loanSection: (@Composable () -> Unit)?,
) {
    val book = detail.book
    var descriptionOpen by remember(book.id) { mutableStateOf(false) }
    var dataOpen by remember(book.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Kleurvlak met de cover er half overheen.
        Box(Modifier.fillMaxWidth().height(232.dp)) {
            Box(Modifier.fillMaxWidth().height(96.dp).background(tint))
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .shadow(8.dp, RoundedCornerShape(4.dp))
                    .clickable(onClick = onPhotoCover), // tik op de cover om (opnieuw) te fotograferen
            ) {
                CoverImage(book.title, book.cover, Modifier.size(width = 136.dp, height = 204.dp), author = detail.authors.firstOrNull())
                if (book.cover == null) {
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = "Cover fotograferen",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp),
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(book.displayTitle, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            val seriesLine = detail.series.firstOrNull()?.let { it.seriesName + (it.seriesIndex?.let { i -> ", deel $i" } ?: "") }
            listOfNotNull(book.subtitle, seriesLine).forEach { line ->
                Text(
                    line,
                    Modifier.then(
                        if (line == seriesLine) Modifier.clickable { detail.series.firstOrNull()?.let { onOpenSeries(it.seriesId) } } else Modifier,
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = Literata),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (detail.authors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                // Elke naam opent de auteurspagina.
                FlowRow(horizontalArrangement = Arrangement.Center) {
                    detail.authors.forEachIndexed { index, name ->
                        Text(
                            name + if (index < detail.authors.lastIndex) ", " else "",
                            Modifier.clickable { onOpenAuthor(name) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            MetaLine(
                listOfNotNull(
                    book.publisher, book.publishedYear?.toString(), book.pages?.let { "$it p." },
                    book.language?.let(Languages::displayName), book.format?.label,
                ),
                center = true,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = primaryAction.second) { Text(primaryAction.first) }
                secondaryAction?.let { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            banner()
            DetailCard("Lezen") { readingSection() }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LocationLine(
                        label = locationLabel(book.locationId),
                        onOpen = book.locationId?.let { id -> { onOpenLocation(id) } },
                        onChange = onPickLocation,
                    )
                    OwnerLine(book.owner, onEdit = onEditOwner)
                }
            }
            loanSection?.let { DetailCard("Bezit en uitlenen") { it() } }

            val original = detail.works.firstOrNull()?.originalTitle?.takeIf { it != book.title }
            val tags = book.tags?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            if (book.description != null || original != null || tags.isNotEmpty() || book.notes != null) {
                DetailCard("Beschrijving") {
                    book.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descriptionOpen) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (it.length > 220) {
                            Text(
                                if (descriptionOpen) "Minder" else "Meer lezen",
                                Modifier.clickable { descriptionOpen = !descriptionOpen }.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    original?.let {
                        Text("Oorspronkelijke titel: $it", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    book.notes?.let {
                        Text("Notities: $it", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (tags.isNotEmpty()) {
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(tag) }) }
                        }
                    }
                }
            }

            if (detail.otherEditions.isNotEmpty()) {
                val primaryUuid = detail.works.firstOrNull()?.primaryBookUuid
                val all = listOf(detail.book) + detail.otherEditions
                val primary = Editions.primaryOf(all, primaryUuid) { it.editionFacts }
                val chosen = Editions.isChosen(all, primaryUuid) { it.editionFacts }
                DetailCard("Andere edities in je collectie") {
                    detail.otherEditions.forEach {
                        EditionRow(it, locationLabel(it.locationId), isPrimary = it.id == primary?.id, onClick = { onOpenBook(it.id) })
                    }
                    if (primary?.id == detail.book.id) {
                        Text(
                            if (chosen) "★ Dit is de hoofdeditie (zelf gekozen)" else "★ Dit is nu de hoofdeditie",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!(chosen && primary?.id == detail.book.id)) {
                        TextButton(onClick = onSetPrimary) { Text("Als hoofdeditie instellen") }
                    }
                }
            }

            // Gegevens en bronnen: standaard dicht.
            AppCard(Modifier.fillMaxWidth(), onClick = { dataOpen = !dataOpen }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gegevens en bronnen", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(
                            if (dataOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (dataOpen) "Dichtklappen" else "Openklappen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dataOpen) {
                        Spacer(Modifier.height(8.dp))
                        DetailRow("ISBN", book.isbn13 ?: book.isbn10)
                        DetailRow("Vertaling", book.translators)
                        DetailRow("Origineel", detail.works.firstOrNull()?.originalLanguage?.let(Languages::displayName))
                        if (book.isbn13 != null) {
                            DetailRow("Status", book.enrichStatus.label)
                            DetailRow("Bronnen", book.enrichedSources)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                                if (refreshing) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Gegevens opnieuw ophalen")
                            }
                            Text(
                                "Wat je zelf hebt ingevuld, blijft staan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Een kaart met een kleine kop, zoals in de schetsen. */
@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun MetaLine(parts: List<String>, center: Boolean = false) {
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else null,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    content()
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun EditionRow(book: Book, location: String?, isPrimary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(book.title, book.cover, Modifier.size(width = 32.dp, height = 48.dp), showTitle = false)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(book.title, style = MaterialTheme.typography.bodyLarge)
            MetaLine(listOfNotNull("★ hoofdeditie".takeIf { isPrimary }, book.language?.let(Languages::displayName), book.publishedYear?.toString(), location?.let { "📍 $it" }))
        }
    }
}

/** "Weggegeven aan Wilfred · 3 nov. 2026"; null als er niets over bekend is. */
private fun disposalText(book: Book): String? {
    if (book.disposalReason == null && book.disposedTo == null && book.disposedAt == null) return null
    val preposition = if (book.disposalReason == DisposalReason.BORROWED) "van" else "aan"
    val what = (book.disposalReason?.verb ?: "Weg") + (book.disposedTo?.let { " $preposition $it" } ?: "")
    return listOfNotNull(what, book.disposedAt?.let { LoanDates.format(it, LocalDate.now()) }).joinToString(" · ")
}

/** Van wie het boek is (gedeelde collectie). */
@Composable
private fun OwnerLine(owner: String?, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Person, contentDescription = "Eigenaar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            owner ?: "Eigenaar niet ingevuld",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (owner != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onEdit) { Text(if (owner != null) "Wijzigen" else "Kiezen") }
    }
}

/** Waar het boek staat; tik op de plek om erheen te gaan, op Wijzig om te verplaatsen. */
@Composable
private fun LocationLine(label: String?, onOpen: (() -> Unit)?, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Place, contentDescription = "Locatie", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label ?: "Geen locatie",
            Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            style = MaterialTheme.typography.bodyLarge,
            color = if (label != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onChange) { Text(if (label != null) "Verplaatsen" else "Plek kiezen") }
    }
}
