package nl.wdhertog.library.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.data.repository.cover
import nl.wdhertog.library.ui.locations.LocationPickerDialog
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.TextButton
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.cover.CoverCamera
import nl.wdhertog.library.lookup.search.SearchCandidate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.DialogProperties
import nl.wdhertog.library.ui.navigation.appViewModel
import nl.wdhertog.library.LibraryApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onManualEntry: () -> Unit,
    onPhotoCover: (Long) -> Unit,
    onManualFromCover: (title: String, author: String, coverFile: String?) -> Unit,
    viewModel: ScanViewModel = appViewModel { container, handle ->
        ScanViewModel(
            handle,
            container.bookRepository,
            container.locationRepository,
            container.enrichmentService,
            container.enrichScheduler,
            container.appScope,
            container.coverStore,
            container.bookSearch,
            mayLookUpNow = container.settings::mayLookUpNow,
            shelfExisting = { container.settings.shelfExisting.value },
            markOwned = container.loanRepository::markOwned,
        )
    },
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val settings = (context.applicationContext as LibraryApp).container.settings
    val feedback = remember { ScanFeedback(context, sound = { settings.scanSound.value }, vibrate = { settings.scanVibrate.value }) }
    DisposableEffect(Unit) { onDispose { feedback.release() } }
    LaunchedEffect(viewModel) { viewModel.signals.collect(feedback::play) }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val torchOn by viewModel.torchOn.collectAsStateWithLifecycle()
    val shelfId by viewModel.shelfId.collectAsStateWithLifecycle()
    val wishlist by viewModel.wishlist.collectAsStateWithLifecycle()
    val tree by viewModel.locationTree.collectAsStateWithLifecycle()
    val shelfLabel = shelfId?.let { LocationTree.index(tree)[it]?.pathLabel }
    var pickingShelf by remember { mutableStateOf(false) }
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val coverSearch by viewModel.coverSearch.collectAsStateWithLifecycle()
    val askMove by viewModel.askMove.collectAsStateWithLifecycle()
    val askReclaim by viewModel.askReclaim.collectAsStateWithLifecycle()
    askReclaim?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::keepNotOwned,
            title = { Text(row.item?.book?.displayTitle ?: "Dit boek staat als niet in bezit") },
            text = {
                Text(
                    "Staat in je bibliotheek als niet in bezit" + (row.item?.book?.let { disposalNote(it) }?.let { " ($it)" } ?: "") +
                        ". Weer in bezit nemen en op ${shelfLabel ?: "deze plank"} zetten?",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.reclaim(row.entry.isbn13) }) { Text("In bezit en hier zetten") } },
            dismissButton = { TextButton(onClick = viewModel::keepNotOwned) { Text("Niet in bezit laten") } },
        )
    }
    askMove?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::keepWhereItIs,
            title = { Text(row.item?.book?.displayTitle ?: "Dit boek staat al ergens") },
            text = {
                Text(
                    "Staat nu in ${row.previousLocation ?: "een andere plek"}. Naar ${shelfLabel ?: "deze plank"} verplaatsen?",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.moveHere(row.entry.isbn13) }) { Text("Verplaatsen") } },
            dismissButton = { TextButton(onClick = viewModel::keepWhereItIs) { Text("Laten staan") } },
        )
    }
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scannen") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Sluiten") }
                },
                actions = {
                    IconButton(onClick = onManualEntry) { Icon(Icons.Default.Edit, contentDescription = "Handmatig invoeren") }
                    if (hasPermission) {
                        IconButton(onClick = viewModel::toggleTorch) {
                            Icon(
                                if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                                contentDescription = if (torchOn) "Lamp uit" else "Lamp aan",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val camera: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                if (hasPermission && mode == ScanMode.COVER) {
                    CoverCamera(
                        torchOn = torchOn,
                        onCaptured = viewModel::onCoverCaptured,
                        onFailed = {},
                        onPick = null,
                        hint = "Fotografeer de voorkant: titel en auteur worden gelezen",
                    )
                } else if (hasPermission) {
                    BarcodeCamera(onBarcode = viewModel::onBarcode, torchOn = torchOn, modifier = Modifier.fillMaxSize())
                    ScanWindow()
                } else {
                    PermissionMessage(
                        permanentlyDenied = askedOnce,
                        onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    )
                }
            }
        }
        val list: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                ModeSwitch(mode, onSelect = { viewModel.mode.value = it })
                WishlistBar(wishlist, onToggle = { viewModel.setWishlist(!wishlist) })
                if (!wishlist) ShelfBar(shelfLabel, onClick = { pickingShelf = true }, onClear = { viewModel.setShelf(null) })
                SessionList(
                    session,
                    onOpenBook,
                    viewModel::undoMove,
                    viewModel::moveHere,
                    viewModel::reclaim,
                    onPhotoCover,
                    emptyHint = if (mode == ScanMode.COVER) "Fotografeer de voorkant van een boek zonder (leesbare) barcode."
                    else "Richt de camera op de barcode achterop het boek.",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (landscape) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxHeight())
                list(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Voor een cover is een groter beeld nodig dan voor een barcode.
            val cameraWeight = if (mode == ScanMode.COVER) 0.62f else 0.45f
            Column(Modifier.padding(padding).fillMaxSize()) {
                camera(Modifier.fillMaxWidth().weight(cameraWeight))
                list(Modifier.fillMaxWidth().weight(1f - cameraWeight))
            }
        }
    }

    var openingManual by remember { mutableStateOf(false) }
    coverSearch?.let { search ->
        CoverSearchDialog(
            state = search,
            onSearch = viewModel::searchAgain,
            onPick = viewModel::pick,
            onManual = {
                // Eén keer: dubbel tikken gaf twee keer het invoerscherm (en een losse cover-foto).
                if (!openingManual) {
                    openingManual = true
                    scope.launch {
                        try {
                            val (title, author, coverFile) = viewModel.manualGuess()
                            viewModel.closeCoverSearch()
                            onManualFromCover(title, author, coverFile)
                        } finally {
                            openingManual = false
                        }
                    }
                }
            },
            onDismiss = viewModel::closeCoverSearch,
        )
    }

    if (pickingShelf) {
        LocationPickerDialog(
            title = "Plank-modus: waar komen de boeken?",
            tree = tree,
            selectedId = shelfId,
            noneLabel = "Plank-modus uit",
            onSelect = {
                viewModel.setShelf(it)
                pickingShelf = false
            },
            onCreate = viewModel::createLocation,
            onDismiss = { pickingShelf = false },
        )
    }
}

/** Barcode (doorlopend) of cover (foto, tekst lezen, zoeken). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: ScanMode, onSelect: (ScanMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(ScanMode.BARCODE to "Barcode", ScanMode.COVER to "Cover").forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
            ) { Text(label) }
        }
    }
}

/** Welk boek is het? Herkende tekst (aan te passen) en de gevonden kandidaten (ontwerp §6.4). */
@Composable
private fun CoverSearchDialog(
    state: CoverSearchState,
    onSearch: (String) -> Unit,
    onPick: (SearchCandidate) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(state is CoverSearchState.Results) { mutableStateOf((state as? CoverSearchState.Results)?.query.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Welk boek is het?") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = remember(state.photo) { state.photo.asImageBitmap() },
                        contentDescription = "Foto van de cover",
                        modifier = Modifier.size(width = 48.dp, height = 72.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    when (state) {
                        is CoverSearchState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        }
                        is CoverSearchState.Results -> OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Herkende tekst") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { onSearch(query) }, enabled = query.isNotBlank()) {
                                    Icon(Icons.Default.Search, contentDescription = "Zoeken")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (state is CoverSearchState.Results) {
                    Spacer(Modifier.height(8.dp))
                    if (state.candidates.isEmpty()) {
                        Text(
                            if (state.query.isBlank()) "Geen tekst gevonden op de foto. Typ titel of auteur en zoek opnieuw."
                            else "Niets gevonden. Pas de tekst aan, of voer het boek zelf in.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        // Index in de sleutel: twee kandidaten zonder ISBN kunnen dezelfde titel en bron hebben.
                        itemsIndexed(state.candidates, key = { i, c -> "$i:${c.source}:${c.isbn13 ?: c.title}" }) { _, candidate ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(candidate) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverImage(candidate.title, candidate.coverUrl, Modifier.size(width = 36.dp, height = 54.dp), showTitle = false)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        candidate.title + (candidate.subtitle?.let { ": $it" } ?: ""),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(candidate.authors.joinToString(", ").ifEmpty { null }, candidate.year?.toString(), candidate.source)
                                            .joinToString(" · "),
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
            }
        },
        confirmButton = { TextButton(onClick = onManual) { Text("Zelf invoeren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Kader als richtpunt; de herkenning werkt op het hele beeld. */
@Composable
private fun ScanWindow() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * 0.75f
        val h = (w * 0.45f).coerceAtMost(size.height * 0.6f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@Composable
private fun PermissionMessage(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Voor het scannen van barcodes is toegang tot de camera nodig.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text("Open instellingen") }
        } else {
            Button(onClick = onRequest) { Text("Camera toestaan") }
        }
    }
}

@Composable
private fun SessionList(
    rows: List<SessionRow>,
    onOpenBook: (Long) -> Unit,
    onUndo: (String) -> Unit,
    onMoveHere: (String) -> Unit,
    onReclaim: (String) -> Unit,
    onPhotoCover: (Long) -> Unit,
    emptyHint: String,
    modifier: Modifier,
) {
    val autoLookup by (LocalContext.current.applicationContext as nl.wdhertog.library.LibraryApp).container.settings.autoLookup.collectAsStateWithLifecycle()
    Column(modifier) {
        Text(
            if (rows.isEmpty()) emptyHint else "Deze sessie (${rows.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn {
            items(rows, key = { it.entry.isbn13 }) { row ->
                SessionRowItem(
                    row,
                    autoLookup = autoLookup,
                    onClick = { onOpenBook(row.entry.bookId) },
                    onUndo = { onUndo(row.entry.isbn13) },
                    onMoveHere = { onMoveHere(row.entry.isbn13) },
                    onReclaim = { onReclaim(row.entry.isbn13) },
                    onPhotoCover = { onPhotoCover(row.entry.bookId) },
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
        }
    }
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    autoLookup: Boolean,
    onClick: () -> Unit,
    onUndo: () -> Unit,
    onMoveHere: () -> Unit,
    onReclaim: () -> Unit,
    onPhotoCover: () -> Unit,
) {
    val book = row.item?.book
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(book?.title.orEmpty(), book?.cover, Modifier.size(width = 36.dp, height = 52.dp), showTitle = false)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book?.displayTitle ?: "ISBN ${row.entry.isbn13}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val highlight = row.entry.outcome != ScanOutcome.ADDED && row.entry.outcome != ScanOutcome.WISHLIST_ADDED
            val status = when (row.entry.outcome) {
                ScanOutcome.ALREADY_PRESENT -> "Staat al in je collectie"
                ScanOutcome.ALREADY_HERE -> "Stond al op deze plek"
                ScanOutcome.MOVED -> row.previousLocation?.let { "Verplaatst van $it" } ?: "Op deze plek gezet (had nog geen plek)"
                ScanOutcome.UNDONE -> row.previousLocation?.let { "Teruggezet naar $it" } ?: "Teruggezet: weer zonder plek"
                ScanOutcome.KEPT -> row.previousLocation?.let { "Staat in $it — niet verplaatst" } ?: "Niet verplaatst"
                ScanOutcome.NOT_OWNED -> "Niet in bezit" + (book?.let { disposalNote(it) }?.let { " · $it" } ?: "")
                ScanOutcome.RECLAIMED -> "Weer in bezit"
                ScanOutcome.ON_WISHLIST -> "Staat al in je bibliotheek · nu op je leeslijst"
                ScanOutcome.ALREADY_ON_WISHLIST -> "Stond al op je leeslijst"
                ScanOutcome.ADDED, ScanOutcome.WISHLIST_ADDED -> when {
                    book == null -> ""
                    book.enrichStatus == EnrichStatus.NEW && !autoLookup -> "Nog niet opgezocht"
                    book.enrichStatus == EnrichStatus.NEW -> "Zoeken…"
                    book.enrichStatus == EnrichStatus.FAILED -> "Niet gevonden — later aanvullen"
                    row.entry.outcome == ScanOutcome.WISHLIST_ADDED ->
                        listOf("Op je leeslijst", row.item.authorLine).filter(String::isNotEmpty).joinToString(" · ")
                    else -> row.item.authorLine.orEmpty()
                }
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.entry.outcome == ScanOutcome.MOVED) {
            TextButton(onClick = onUndo) { Text("Ongedaan maken") }
        } else if (row.entry.outcome == ScanOutcome.KEPT) {
            TextButton(onClick = onMoveHere) { Text("Hierheen") }
        } else if (row.entry.outcome == ScanOutcome.NOT_OWNED) {
            TextButton(onClick = onReclaim) { Text("In bezit") }
        } else if (book?.enrichStatus == EnrichStatus.NEW && (row.entry.outcome == ScanOutcome.ADDED || row.entry.outcome == ScanOutcome.WISHLIST_ADDED) && autoLookup) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (book != null && book.cover == null) {
            // Geen open bron met een cover: meteen zelf fotograferen nu het boek in je hand ligt.
            IconButton(onClick = onPhotoCover) {
                Icon(Icons.Default.AddAPhoto, contentDescription = "Cover fotograferen", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Leeslijst-modus aan/uit: wat je scant, komt op je leeslijst en niet in je bezit. */
@Composable
private fun WishlistBar(on: Boolean, onToggle: () -> Unit) {
    Surface(
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(if (on) "Leeslijst-modus" else "Leeslijst-modus uit", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (on) "Op je leeslijst, niet in je bezit" else "Tik om boeken voor je leeslijst te scannen",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { onToggle() })
        }
    }
}

/** Plank-modus aan/uit: alles wat je scant komt op deze plek, in scanvolgorde. */
@Composable
private fun ShelfBar(label: String?, onClick: () -> Unit, onClear: () -> Unit) {
    Surface(
        color = if (label != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    if (label != null) "Plank-modus" else "Plank-modus uit",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    label ?: "Tik om een plank te kiezen",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (label != null) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Plank-modus uit") }
            } else {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, Modifier.padding(end = 12.dp))
            }
        }
    }
}

/** Wat er over het wegdoen bekend is: "Weggegeven aan Jan", "Geleend", … */
private fun disposalNote(book: nl.wdhertog.library.data.db.Book): String? {
    val reason = book.disposalReason ?: return book.disposedTo?.let { "aan $it" }
    // Bij geleend gelezen is dat de persoon van wie je het leende.
    val preposition = if (reason == nl.wdhertog.library.data.db.DisposalReason.BORROWED) "van" else "aan"
    return reason.verb + (book.disposedTo?.let { " $preposition $it" } ?: "")
}
