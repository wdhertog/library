package nl.wdhertog.library.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import nl.wdhertog.library.AppContainer
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.ui.bookdetail.BookDetailScreen
import nl.wdhertog.library.ui.bookedit.BookEditScreen
import nl.wdhertog.library.ui.library.LibraryScreen

@Serializable
object LibraryRoute

@Serializable
data class BookDetailRoute(val bookId: Long)

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

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onBookClick = { navController.navigate(BookDetailRoute(it)) },
                onAddBook = { navController.navigate(BookEditRoute()) },
            )
        }
        composable<BookDetailRoute> {
            BookDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(BookEditRoute(it)) },
                onOpenBook = { navController.navigate(BookDetailRoute(it)) },
            )
        }
        composable<BookEditRoute> {
            BookEditScreen(
                onClose = { navController.popBackStack() },
                onSaved = { bookId, isNew ->
                    if (isNew) {
                        navController.navigate(BookDetailRoute(bookId)) {
                            popUpTo<BookEditRoute> { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
    }
}
