package nl.wdhertog.library.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import nl.wdhertog.library.AppContainer
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.ui.bookdetail.BookDetailScreen
import nl.wdhertog.library.ui.bookedit.BookEditScreen
import nl.wdhertog.library.ui.library.LibraryScreen
import nl.wdhertog.library.ui.scan.ScanScreen

@Serializable
object LibraryRoute

@Serializable
data class BookDetailRoute(val bookId: Long)

@Serializable
object ScanRoute

/** bookId 0 = nieuw boek. */
@Serializable
data class BookEditRoute(val bookId: Long = 0)

/** ViewModel met toegang tot de AppContainer en de navigatie-argumenten. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = this[APPLICATION_KEY] as LibraryApp
            create(app.container, createSavedStateHandle())
        }
    },
)

/**
 * Alleen terug als dit scherm nog actief is. Zonder deze controle haalt een dubbele
 * terug-actie (knop + gebaar) ook het startscherm weg en blijft er een leeg venster over.
 */
private fun NavController.popFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onBookClick = { navController.navigate(BookDetailRoute(it)) },
                onAddBook = { navController.navigate(BookEditRoute()) },
                onScan = { navController.navigate(ScanRoute) },
            )
        }
        composable<ScanRoute> { entry ->
            ScanScreen(
                onClose = { navController.popFrom(entry) },
                onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                onManualEntry = { navController.navigate(BookEditRoute()) },
            )
        }
        composable<BookDetailRoute> { entry ->
            BookDetailScreen(
                onBack = { navController.popFrom(entry) },
                onEdit = { navController.navigate(BookEditRoute(it)) },
                onOpenBook = { navController.navigate(BookDetailRoute(it)) },
            )
        }
        composable<BookEditRoute> { entry ->
            BookEditScreen(
                onClose = { navController.popFrom(entry) },
                onSaved = { bookId, isNew ->
                    if (isNew) {
                        navController.navigate(BookDetailRoute(bookId)) {
                            popUpTo<BookEditRoute> { inclusive = true }
                        }
                    } else {
                        navController.popFrom(entry)
                    }
                },
            )
        }
    }
}
