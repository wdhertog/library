package nl.wdhertog.library

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import nl.wdhertog.library.ui.navigation.AppNavHost
import nl.wdhertog.library.ui.theme.LibraryTheme

class MainActivity : ComponentActivity() {

    /** Gezet als de app via een uitleenherinnering wordt geopend. */
    private val openLoans = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openLoans.value = intent.getBooleanExtra(EXTRA_OPEN_LOANS, false)
        setContent {
            LibraryTheme {
                AppNavHost(openLoans = openLoans.value, onOpenLoansHandled = { openLoans.value = false })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.SYNC) (application as LibraryApp).container.syncManager.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (BuildConfig.SYNC) (application as LibraryApp).container.syncManager.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_LOANS, false)) openLoans.value = true
    }

    companion object {
        const val EXTRA_OPEN_LOANS = "open_loans"
    }
}
