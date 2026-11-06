package nl.wdhertog.library.ui.sync

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.data.sync.MergeBook
import nl.wdhertog.library.data.sync.MergePair
import nl.wdhertog.library.data.sync.SyncPeer
import nl.wdhertog.library.sync.PairingCode
import nl.wdhertog.library.sync.PairingState
import nl.wdhertog.library.sync.SyncStatus
import nl.wdhertog.library.ui.scan.BarcodeCamera
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class SyncDialog { SHOW_CODE, SCAN, PASTE }

/** Synchroniseren met andere telefoons op het thuisnetwerk (ontwerp §11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as LibraryApp).container
    val manager = container.syncManager
    val peers by container.syncRepository.observePeers().collectAsStateWithLifecycle(initialValue = emptyList())
    val status by manager.status.collectAsStateWithLifecycle()
    val pairing by manager.pairing.collectAsStateWithLifecycle()
    val inBackground by container.settings.syncInBackground.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SyncDialog?>(null) }
    var unpair by remember { mutableStateOf<SyncPeer?>(null) }
    val pairMessage by manager.pairMessage.collectAsStateWithLifecycle()

    val pairWith: (PairingCode) -> Unit = { code ->
        dialog = null
        manager.requestPairing(code)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synchroniseren") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Houd de collectie gelijk met andere telefoons op hetzelfde wifi-netwerk. Alles gaat rechtstreeks " +
                    "en versleuteld van telefoon naar telefoon, zonder server. Leesgeschiedenis, beoordelingen, " +
                    "favorieten en je leeslijst blijven op je eigen telefoon.",
                style = MaterialTheme.typography.bodyMedium,
            )
            // Alleen in een testversie (eigen app-id naast de gewone app).
            if (nl.wdhertog.library.BuildConfig.APPLICATION_ID != "nl.wdhertog.library") Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Testversie van synchroniseren. Dit is een aparte app: je gewone Bibliotheek doet niet mee en blijft onaangeroerd.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Deze telefoon: ${manager.deviceName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

            StatusLine(status)
            Button(
                onClick = { manager.requestSyncNow() },
                enabled = peers.isNotEmpty() && status !is SyncStatus.Busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Nu synchroniseren")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Op de achtergrond", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Ook synchroniseren als de app niet open is. Er staat dan een stille, vaste melding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Switch(checked = inBackground, onCheckedChange = {
                    container.settings.setSyncInBackground(it)
                    manager.backgroundSettingChanged()
                })
            }
            if (inBackground) BatteryHint()

            SectionTitle("Gekoppelde telefoons")
            if (peers.isEmpty()) {
                Text("Nog geen. Koppel hieronder een telefoon.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            peers.forEach { peer ->
                ListItem(
                    headlineContent = { Text(peer.name) },
                    supportingContent = { Text(peer.lastSyncAt?.let { "Laatst: ${formatTime(it)}" } ?: "Nog niet gesynchroniseerd") },
                    leadingContent = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                    trailingContent = { IconButton(onClick = { unpair = peer }) { Icon(Icons.Default.LinkOff, contentDescription = "Ontkoppelen") } },
                )
            }

            SectionTitle("Telefoon koppelen")
            Text(
                "Laat op de ene telefoon de code zien en scan die met de andere. Een nieuwe telefoon hoeft maar met " +
                    "één telefoon gekoppeld te worden; wijzigingen worden doorgegeven.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { dialog = SyncDialog.SHOW_CODE; scope.launch { manager.startPairing() } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCode2, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("Code laten zien")
            }
            OutlinedButton(onClick = { dialog = SyncDialog.SCAN }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("Code scannen")
            }
            TextButton(onClick = { dialog = SyncDialog.PASTE }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentPaste, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("Code plakken")
            }
        }
    }

    when (dialog) {
        SyncDialog.SHOW_CODE -> ShowCodeDialog(pairing) { dialog = null; manager.cancelPairing() }
        SyncDialog.SCAN -> ScanCodeDialog(onCode = pairWith, onDismiss = { dialog = null })
        SyncDialog.PASTE -> PasteCodeDialog(onCode = pairWith, onDismiss = { dialog = null })
        null -> Unit
    }

    (pairing as? PairingState.Review)?.let { review ->
        MergeReviewDialog(
            review = review,
            onConfirm = { accepted -> manager.requestMerge(accepted) },
            onCancel = manager::cancelMerge,
        )
    }

    pairMessage?.let { message ->
        AlertDialog(
            onDismissRequest = manager::clearPairMessage,
            title = { Text("Koppelen") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = manager::clearPairMessage) { Text("OK") } },
        )
    }

    unpair?.let { peer ->
        AlertDialog(
            onDismissRequest = { unpair = null },
            title = { Text("${peer.name} ontkoppelen?") },
            text = { Text("Er wordt niets meer uitgewisseld met deze telefoon. De boeken blijven op beide telefoons staan.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { manager.unpair(peer.deviceId) }; unpair = null }) { Text("Ontkoppelen") }
            },
            dismissButton = { TextButton(onClick = { unpair = null }) { Text("Annuleren") } },
        )
    }
}

/**
 * Sommige telefoons (bijv. Samsung, Xiaomi) leggen apps met batterijbesparing toch stil, ook met een
 * dienst en melding. Dan kan de gebruiker de app hier uitzonderen.
 */
@Composable
private fun BatteryHint() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    // Opnieuw kijken als je terugkomt uit de systeeminstellingen.
    LifecycleResumeEffect(Unit) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp)) {
            Text(
                "Batterijbesparing kan synchroniseren op de achtergrond vertragen of stilleggen, vooral op " +
                    "Samsung- en Xiaomi-telefoons. Gaan wijzigingen niet vanzelf over, zet de beperking dan uit.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    @SuppressLint("BatteryLife")
                    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching { context.startActivity(ask) }.recoverCatching { context.startActivity(list) }
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Niet beperken") }
        }
    }
}

@Composable
private fun StatusLine(status: SyncStatus) {
    when (status) {
        SyncStatus.Idle -> Unit
        is SyncStatus.Busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(status.text, style = MaterialTheme.typography.bodyMedium)
        }
        is SyncStatus.Done -> Text(status.text, style = MaterialTheme.typography.bodyMedium)
        is SyncStatus.Failed -> Text(status.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
    }
}

@Composable
private fun ShowCodeDialog(pairing: PairingState?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pairing is PairingState.Paired) "Gekoppeld" else "Scan deze code met de andere telefoon") },
        text = {
            when (pairing) {
                is PairingState.Showing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(pairing.code.encode(), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Op de andere telefoon: Instellingen → Synchroniseren → Code scannen. De code is 10 minuten geldig " +
                            "en werkt maar voor één telefoon.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        // Gevoelig: het toetsenbord en de klembordgeschiedenis tonen of bewaren de code dan niet.
                        val clip = ClipData.newPlainText("Koppelcode", pairing.code.encode()).apply {
                            description.extras = android.os.PersistableBundle().apply {
                                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    }) { Text("Code kopiëren") }
                }
                is PairingState.Paired -> Text("Gekoppeld met ${pairing.name}. De collecties worden nu uitgewisseld.")
                is PairingState.Review, null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (pairing is PairingState.Paired) "Klaar" else "Annuleren") } },
    )
}

@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    // Altijd zwart op wit, ook in het donkere thema: dat scant het best.
    Canvas(modifier.aspectRatio(1f).background(Color.White)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun ScanCodeDialog(onCode: (PairingCode) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }
    var handled by remember { mutableStateOf(false) }
    var wrongCode by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (hasPermission) {
                    BarcodeCamera(
                        onBarcode = { raw ->
                            if (handled) return@BarcodeCamera
                            val code = PairingCode.decode(raw)
                            if (code == null) wrongCode = true else { handled = true; onCode(code) }
                        },
                        torchOn = false,
                        format = Barcode.FORMAT_QR_CODE,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Geen toegang tot de camera.", Modifier.align(Alignment.Center), color = Color.White)
                }
            }
            Text(
                if (wrongCode) "Dit is geen koppelcode van Bibliotheek." else "Richt de camera op de code op de andere telefoon.",
                Modifier.fillMaxWidth().padding(16.dp),
                color = Color.White,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(8.dp)) { Text("Annuleren", color = Color.White) }
        }
    }
}

@Composable
private fun PasteCodeDialog(onCode: (PairingCode) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val code = PairingCode.decode(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text("Koppelcode plakken") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Code (begint met BIB1:)") },
                isError = text.isNotBlank() && code == null,
                supportingText = code?.let { { Text("Code van ${it.name}") } },
                // Eén regel: een lange code duwt de knoppen anders onder het toetsenbord.
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { code?.let(onCode) }, enabled = code != null) { Text("Koppelen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

@Composable
private fun MergeReviewDialog(review: PairingState.Review, onConfirm: (Set<String>) -> Unit, onCancel: () -> Unit) {
    val plan = review.plan
    var accepted by remember(plan) { mutableStateOf(plan.pairs.filter { it.certain }.map { it.local.uuid }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val doubts = plan.pairs.filter { !it.certain }.sortedBy { it.local.title.lowercase() }
    val certain = plan.pairs.filter { it.certain }.sortedBy { it.local.title.lowercase() }

    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Samenvoegen met ${review.peerName}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Vink aan welke boeken op beide telefoons hetzelfde zijn. Die worden één boek: lege velden worden " +
                            "aangevuld, en bij een verschil wint de laatste wijziging. De andere boeken komen er allebei in. " +
                            "Eerst wordt een kopie van deze bibliotheek gemaakt (terug te zetten via Back-up).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        listOfNotNull(
                            "${plan.pairs.size} op beide" + if (doubts.isNotEmpty()) " (${doubts.size} twijfel)" else "",
                            "${plan.onlyLocal} alleen hier",
                            "${plan.onlyRemote} alleen op ${review.peerName}",
                            plan.alreadyShared.takeIf { it > 0 }?.let { "$it al gedeeld" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (doubts.isNotEmpty()) {
                        item { ReviewHeader("Twijfelgevallen: zelfde titel, ander ISBN") }
                        items(doubts, key = { it.local.uuid }) { pair ->
                            PairRow(pair, review.peerName, pair.local.uuid in accepted) { on ->
                                accepted = if (on) accepted + pair.local.uuid else accepted - pair.local.uuid
                            }
                        }
                    }
                    if (certain.isNotEmpty()) {
                        item { ReviewHeader("Hetzelfde boek") }
                        items(certain, key = { it.local.uuid }) { pair ->
                            PairRow(pair, review.peerName, pair.local.uuid in accepted) { on ->
                                accepted = if (on) accepted + pair.local.uuid else accepted - pair.local.uuid
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Annuleren") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { busy = true; onConfirm(accepted) }, enabled = !busy) { Text("Samenvoegen") }
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun PairRow(pair: MergePair, peerName: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    fun describe(book: MergeBook) = listOfNotNull(book.authors.firstOrNull(), book.isbn13).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(pair.local.title.ifBlank { "(zonder titel)" }, style = MaterialTheme.typography.bodyLarge)
            Text("Hier: ${describe(pair.local)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val remoteTitle = if (pair.remote.title != pair.local.title) "${pair.remote.title} · " else ""
            Text("$peerName: $remoteTitle${describe(pair.remote)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("nl"))

private fun formatTime(millis: Long) = timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
