package nl.wdhertog.library

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import nl.wdhertog.library.ui.navigation.AppNavHost
import nl.wdhertog.library.ui.navigation.Launch
import nl.wdhertog.library.widget.ReadingWidget
import nl.wdhertog.library.ui.theme.LibraryTheme

class MainActivity : ComponentActivity() {

    /** Waar de app naartoe moet als hij via een uitleenherinnering of de widget wordt geopend. */
    private val launch = mutableStateOf<Launch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Alleen bij een echte start: na draaien of herstel na procesdood is de intent al afgehandeld,
        // en zou de app anders opnieuw naar het boek uit de widget springen.
        if (savedInstanceState == null) launch.value = launchFrom(intent)
        setContent {
            LibraryTheme {
                AppNavHost(launch = launch.value, onLaunchHandled = { launch.value = null })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.SYNC) (application as LibraryApp).container.syncManager.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchFrom(intent)?.let { launch.value = it }
    }

    override fun onStop() {
        super.onStop()
        if (BuildConfig.SYNC) (application as LibraryApp).container.syncManager.onBackground()
        // Titel, cover of voortgang kan veranderd zijn: de widget bijwerken bij het verlaten van de app.
        val app = application as LibraryApp
        app.container.appScope.launch {
            // Een fout in de widget mag de app niet laten crashen.
            runCatching { ReadingWidget.update(app) }.onFailure { android.util.Log.w("MainActivity", "Widget bijwerken mislukt", it) }
        }
    }

    private fun launchFrom(intent: Intent): Launch? = when {
        intent.getBooleanExtra(EXTRA_OPEN_LOANS, false) -> Launch.Loans
        intent.getBooleanExtra(EXTRA_OPEN_READING, false) -> Launch.Reading
        intent.getLongExtra(EXTRA_OPEN_BOOK, 0L) != 0L -> Launch.Book(intent.getLongExtra(EXTRA_OPEN_BOOK, 0L))
        else -> null
    }

    companion object {
        const val EXTRA_OPEN_LOANS = "open_loans"
        const val EXTRA_OPEN_READING = "open_reading"
        const val EXTRA_OPEN_BOOK = "open_book"
    }
}
