package nl.wdhertog.library.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.BuildConfig
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.reminders.LoanReminderWorker

/** Instellingen en "over de app" (ontwerp §6.12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onBackup: () -> Unit, onSync: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as LibraryApp).container
    val settings = container.settings
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val sound by settings.scanSound.collectAsStateWithLifecycle()
    val vibrate by settings.scanVibrate.collectAsStateWithLifecycle()
    val reminders by settings.loanReminders.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header("Gegevens opzoeken")
            SwitchRow(
                title = "Alleen via wifi",
                text = "Op mobiele data wacht het opzoeken van boekgegevens tot je weer wifi hebt.",
                checked = wifiOnly,
                onChange = {
                    settings.setWifiOnly(it)
                    container.enrichScheduler.schedule()
                },
            )

            Header("Scannen")
            SwitchRow("Geluid", "Piep bij een gescand boek.", sound, settings::setScanSound)
            SwitchRow("Trillen", "Korte trilling bij een gescand boek.", vibrate, settings::setScanVibrate)

            Header("Uitlenen")
            SwitchRow(
                title = "Herinneringen",
                text = "Melding als een uitgeleend boek terug moet, of een geleend boek terug moet naar de eigenaar.",
                checked = reminders,
                onChange = {
                    settings.setLoanReminders(it)
                    if (it) LoanReminderWorker.schedule(context) else LoanReminderWorker.cancel(context)
                },
            )

            Header("Back-up")
            Text(
                "Back-up maken, terugzetten en exporteren",
                Modifier.fillMaxWidth().clickable(onClick = onBackup).padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            if (BuildConfig.SYNC) {
                Header("Synchroniseren")
                Text(
                    "Telefoons koppelen en synchroniseren",
                    Modifier.fillMaxWidth().clickable(onClick = onSync).padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Header("Over de app")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Bibliotheek ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(4.dp))
                Text(
                    "Boekgegevens komen uit open bronnen, zonder account of sleutel: de Koninklijke Bibliotheek " +
                        "(Nederlandse Bibliografie), Open Library, de Deutsche Nationalbibliothek, de Bibliothèque " +
                        "nationale de France, de Biblioteca Nacional de España, het Italiaanse Servizio Bibliotecario " +
                        "Nazionale en Wikidata. Daarbij wordt alleen het ISBN of de zoektekst verstuurd.\n\n" +
                        "Barcodes en covertekst worden op de telefoon zelf herkend (ML Kit); er gaan geen foto's of " +
                        "gebruiksgegevens naar Google. Al je gegevens staan alleen op deze telefoon en in je eigen back-ups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Column {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
