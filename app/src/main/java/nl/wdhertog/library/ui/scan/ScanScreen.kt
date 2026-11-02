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
import androidx.compose.material3.TextButton
import nl.wdhertog.library.ui.components.CoverImage
import nl.wdhertog.library.ui.navigation.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onManualEntry: () -> Unit,
    onPhotoCover: (Long) -> Unit,
    viewModel: ScanViewModel = appViewModel { container, handle ->
        ScanViewModel(
            handle,
            container.bookRepository,
            container.locationRepository,
            container.enrichmentService,
            container.enrichScheduler,
            container.appScope,
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

    val feedback = remember { ScanFeedback(context) }
    DisposableEffect(Unit) { onDispose { feedback.release() } }
    LaunchedEffect(viewModel) { viewModel.signals.collect(feedback::play) }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val torchOn by viewModel.torchOn.collectAsStateWithLifecycle()
    val shelfId by viewModel.shelfId.collectAsStateWithLifecycle()
    val tree by viewModel.locationTree.collectAsStateWithLifecycle()
    val shelfLabel = shelfId?.let { LocationTree.index(tree)[it]?.pathLabel }
    var pickingShelf by remember { mutableStateOf(false) }
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
                if (hasPermission) {
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
                ShelfBar(shelfLabel, onClick = { pickingShelf = true }, onClear = { viewModel.setShelf(null) })
                SessionList(session, onOpenBook, viewModel::undoMove, onPhotoCover, Modifier.weight(1f))
            }
        }

        if (landscape) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxHeight())
                list(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                camera(Modifier.fillMaxWidth().weight(0.45f))
                list(Modifier.fillMaxWidth().weight(0.55f))
            }
        }
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
    onPhotoCover: (Long) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(
            if (rows.isEmpty()) "Richt de camera op de barcode achterop het boek." else "Deze sessie (${rows.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn {
            items(rows, key = { it.entry.isbn13 }) { row ->
                SessionRowItem(
                    row,
                    onClick = { onOpenBook(row.entry.bookId) },
                    onUndo = { onUndo(row.entry.isbn13) },
                    onPhotoCover = { onPhotoCover(row.entry.bookId) },
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
        }
    }
}

@Composable
private fun SessionRowItem(row: SessionRow, onClick: () -> Unit, onUndo: () -> Unit, onPhotoCover: () -> Unit) {
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
            val highlight = row.entry.outcome != ScanOutcome.ADDED
            val status = when (row.entry.outcome) {
                ScanOutcome.ALREADY_PRESENT -> "Staat al in je collectie"
                ScanOutcome.ALREADY_HERE -> "Stond al op deze plek"
                ScanOutcome.MOVED -> row.previousLocation?.let { "Verplaatst van $it" } ?: "Op deze plek gezet (had nog geen plek)"
                ScanOutcome.UNDONE -> row.previousLocation?.let { "Teruggezet naar $it" } ?: "Teruggezet: weer zonder plek"
                ScanOutcome.ADDED -> when {
                    book == null -> ""
                    book.enrichStatus == EnrichStatus.NEW -> "Zoeken…"
                    book.enrichStatus == EnrichStatus.FAILED -> "Niet gevonden — later aanvullen"
                    else -> row.item?.authorLine.orEmpty()
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
        } else if (book?.enrichStatus == EnrichStatus.NEW && row.entry.outcome == ScanOutcome.ADDED) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (book != null && book.cover == null) {
            // Geen open bron met een cover: meteen zelf fotograferen nu het boek in je hand ligt.
            IconButton(onClick = onPhotoCover) {
                Icon(Icons.Default.AddAPhoto, contentDescription = "Cover fotograferen", tint = MaterialTheme.colorScheme.primary)
            }
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
