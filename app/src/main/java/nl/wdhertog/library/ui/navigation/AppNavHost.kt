package nl.wdhertog.library.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import nl.wdhertog.library.data.repository.LoanRepository
import nl.wdhertog.library.ui.loans.LoansScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import nl.wdhertog.library.AppContainer
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.ui.bookdetail.BookDetailScreen
import nl.wdhertog.library.ui.backup.BackupScreen
import nl.wdhertog.library.ui.bookedit.BookEditScreen
import nl.wdhertog.library.ui.cover.CoverScreen
import nl.wdhertog.library.ui.importer.ImportScreen
import nl.wdhertog.library.ui.reading.ReadingScreen
import nl.wdhertog.library.ui.series.SeriesDetailScreen
import nl.wdhertog.library.ui.series.SeriesListScreen
import nl.wdhertog.library.ui.settings.SettingsScreen
import nl.wdhertog.library.ui.library.LibraryScreen
import nl.wdhertog.library.ui.locations.LocationDetailScreen
import nl.wdhertog.library.ui.locations.LocationsScreen
import nl.wdhertog.library.ui.scan.ScanScreen
import kotlin.reflect.KClass

@Serializable
object LibraryRoute

@Serializable
object LocationsRoute

@Serializable
object LoansRoute

@Serializable
data class LocationDetailRoute(val locationId: Long)

@Serializable
data class BookDetailRoute(val bookId: Long)

/** locationId 0 = plank-modus uit. */
@Serializable
data class ScanRoute(val locationId: Long = 0)

/** bookId 0 = nieuw boek; titel en auteur kunnen alvast ingevuld worden (bijv. na cover-herkenning). */
@Serializable
data class BookEditRoute(val bookId: Long = 0, val title: String = "", val author: String = "", val coverFile: String = "")

@Serializable
data class CoverRoute(val bookId: Long)

@Serializable
object ImportRoute

@Serializable
object ReadingRoute

@Serializable
object BackupRoute

@Serializable
object SettingsRoute

@Serializable
object SyncRoute

@Serializable
object SeriesListRoute

@Serializable
data class SeriesDetailRoute(val seriesId: Long)

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(LibraryRoute, LibraryRoute::class, "Bibliotheek", Icons.AutoMirrored.Filled.LibraryBooks),
    TopLevel(ReadingRoute, ReadingRoute::class, "Lezen", Icons.AutoMirrored.Filled.MenuBook),
    TopLevel(LocationsRoute, LocationsRoute::class, "Locaties", Icons.Default.Place),
    TopLevel(LoansRoute, LoansRoute::class, "Uitleen", Icons.Default.SwapHoriz),
)

/** Gegevens voor de menubalk zelf: het aantal te late uitleningen als badge. */
class NavBarViewModel(loans: LoanRepository) : ViewModel() {
    val overdue: StateFlow<Int> = loans.observeOverdueCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

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
fun AppNavHost(openLoans: Boolean = false, onOpenLoansHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    LaunchedEffect(openLoans) {
        if (openLoans) {
            navController.navigate(LoansRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
            onOpenLoansHandled()
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = topLevel.any { tab -> destination?.hasRoute(tab.routeClass) == true }
    val navBar = appViewModel { container, _ -> NavBarViewModel(container.loanRepository) }
    val overdue by navBar.overdue.collectAsStateWithLifecycle()

    // Alleen de menubalk krijgt hier ruimte; de schermen regelen hun eigen statusbalk.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == LoansRoute && overdue > 0) {
                                    BadgedBox(badge = { Badge { Text("$overdue") } }) { Icon(tab.icon, contentDescription = null) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = LibraryRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<LibraryRoute> {
                LibraryScreen(
                    onBookClick = { navController.navigate(BookDetailRoute(it)) },
                    onAddBook = { navController.navigate(BookEditRoute()) },
                    onScan = { navController.navigate(ScanRoute()) },
                    onImport = { navController.navigate(ImportRoute) },
                    onSeries = { navController.navigate(SeriesListRoute) },
                    onBackup = { navController.navigate(BackupRoute) },
                    onSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<SettingsRoute> { entry ->
                SettingsScreen(
                    onBack = { navController.popFrom(entry) },
                    onBackup = { navController.navigate(BackupRoute) },
                    onSync = { navController.navigate(SyncRoute) },
                )
            }
            composable<SyncRoute> { entry ->
                nl.wdhertog.library.ui.sync.SyncScreen(onBack = { navController.popFrom(entry) })
            }
            composable<BackupRoute> { entry ->
                BackupScreen(onBack = { navController.popFrom(entry) })
            }
            composable<ReadingRoute> {
                ReadingScreen(onOpenBook = { navController.navigate(BookDetailRoute(it)) })
            }
            composable<SeriesListRoute> { entry ->
                SeriesListScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenSeries = { navController.navigate(SeriesDetailRoute(it)) },
                )
            }
            composable<SeriesDetailRoute> { entry ->
                SeriesDetailScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                )
            }
            composable<LocationsRoute> {
                LocationsScreen(onOpenLocation = { navController.navigate(LocationDetailRoute(it)) })
            }
            composable<LoansRoute> {
                LoansScreen(onOpenBook = { navController.navigate(BookDetailRoute(it)) })
            }
            composable<LocationDetailRoute> { entry ->
                LocationDetailScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenLocation = { navController.navigate(LocationDetailRoute(it)) },
                    onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                    onScanHere = { navController.navigate(ScanRoute(it)) },
                )
            }
            composable<ScanRoute> { entry ->
                ScanScreen(
                    onClose = { navController.popFrom(entry) },
                    onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                    onManualEntry = { navController.navigate(BookEditRoute()) },
                    onPhotoCover = { navController.navigate(CoverRoute(it)) },
                    onManualFromCover = { title, author, coverFile ->
                        navController.navigate(BookEditRoute(title = title, author = author, coverFile = coverFile.orEmpty()))
                    },
                )
            }
            composable<BookDetailRoute> { entry ->
                BookDetailScreen(
                    onBack = { navController.popFrom(entry) },
                    onEdit = { navController.navigate(BookEditRoute(it)) },
                    onOpenBook = { navController.navigate(BookDetailRoute(it)) },
                    onOpenLocation = { navController.navigate(LocationDetailRoute(it)) },
                    onPhotoCover = { navController.navigate(CoverRoute(it)) },
                    onOpenSeries = { navController.navigate(SeriesDetailRoute(it)) },
                )
            }
            composable<ImportRoute> { entry ->
                ImportScreen(onBack = { navController.popFrom(entry) })
            }
            composable<CoverRoute> { entry ->
                CoverScreen(onClose = { navController.popFrom(entry) })
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
}
