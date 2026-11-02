package nl.wdhertog.library.ui.backup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.ui.navigation.appViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val stamp = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("nl"))

/** Back-up maken en terugzetten, en de bibliotheek als CSV exporteren (ontwerp §6.12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = appViewModel { container, _ ->
        BackupViewModel(
            container.backupService,
            container.bookRepository,
            container.locationRepository,
            container.readingRepository,
            container.loanRepository,
            container.enrichScheduler,
            container.app.getSharedPreferences(BackupViewModel.PREFS, Context.MODE_PRIVATE),
        )
    },
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastBackup by viewModel.lastBackup.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val today = LocalDate.now()
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.createBackup(context.contentResolver, it) }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.inspect(context.contentResolver, it) }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportCsv(context.contentResolver, it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Back-up en export") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val stale = lastBackup?.let { Duration.between(it, Instant.now()).toDays() > 30 } ?: true
            Card(
                colors = if (stale) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Back-up", style = MaterialTheme.typography.titleMedium)
                    Text(
                        lastBackup?.let { "Laatste back-up: " + stamp.format(it.atZone(ZoneId.systemDefault())) }
                            ?: "Je hebt nog geen back-up gemaakt.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Alles in één ZIP-bestand: boeken, locaties, uitleningen, leesgeschiedenis en je eigen cover-foto's. " +
                            "Bewaar het buiten je telefoon, bijvoorbeeld in Nextcloud of op je computer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { createBackup.launch("bibliotheek-$today.zip") }) { Text("Back-up maken…") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Terugzetten", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vervangt je hele bibliotheek door een back-up, bijvoorbeeld op een nieuwe telefoon. " +
                            "De huidige bibliotheek wordt eerst automatisch bewaard.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text("Back-up kiezen…")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Exporteren als CSV", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Een lijst van al je boeken om te openen in Excel of een andere app. Geen back-up: " +
                            "leesgeschiedenis per keer en covers zitten er niet in.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { exportCsv.launch("bibliotheek-$today.csv") }) { Text("Exporteren…") }
                }
            }
        }
    }

    when (val s = state) {
        is BackupUiState.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(s.message)
                    }
                }
            },
        )
        is BackupUiState.ConfirmRestore -> AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("Back-up terugzetten?") },
            text = {
                val created = runCatching { stamp.format(Instant.parse(s.manifest.createdAt).atZone(ZoneId.systemDefault())) }.getOrDefault(s.manifest.createdAt)
                Text(
                    "Back-up van $created (app-versie ${s.manifest.appVersion}): ${s.manifest.books} boeken, " +
                        "${s.manifest.covers} eigen covers.\n\nJe huidige bibliotheek wordt hierdoor vervangen. " +
                        "Daarna start de app opnieuw.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.restore { restartApp(context) } }) { Text("Vervangen") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text("Annuleren") } },
        )
        BackupUiState.Idle -> Unit
    }
}

/** Start de app opnieuw, zodat alles de teruggezette database gebruikt. */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
