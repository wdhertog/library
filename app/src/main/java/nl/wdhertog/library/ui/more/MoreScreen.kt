package nl.wdhertog.library.ui.more

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import nl.wdhertog.library.BuildConfig
import nl.wdhertog.library.ui.components.AppCard

/** "Meer" in de onderbalk: alles wat geen eigen tab heeft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onLocations: () -> Unit,
    onAuthors: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Meer") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GroupTitle("Collectie")
            AppCard(Modifier.fillMaxWidth()) {
                Item(Icons.Default.Place, "Locaties", "Kamers, kasten en planken", onLocations)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Item(Icons.Default.Person, "Auteurs", "Per auteur je boeken, series en gegevens", onAuthors)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Item(Icons.Default.FileOpen, "Importeren", "Have I Read That, Excel of CSV", onImport)
            }
            GroupTitle("App")
            AppCard(Modifier.fillMaxWidth()) {
                Item(Icons.Default.Backup, "Back-up en export", "Back-up maken, terugzetten en exporteren", onBackup)
                if (BuildConfig.SYNC) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Item(Icons.Default.Sync, "Synchroniseren", "Telefoons koppelen en synchroniseren", onSync)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Item(Icons.Default.Settings, "Instellingen", "Opzoeken, bronnen, scannen en uitlenen", onSettings)
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Item(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
