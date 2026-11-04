package nl.wdhertog.library.util

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.flow.MutableStateFlow
import nl.wdhertog.library.lookup.LookupSource
import kotlinx.coroutines.flow.StateFlow

/** Plank-modus: wat er gebeurt met een gescand boek dat al op een andere plek staat. */
enum class ShelfExisting(val label: String, val description: String) {
    MOVE("Verplaatsen", "Meteen naar deze plank, met de mogelijkheid om het ongedaan te maken."),
    ASK("Eerst vragen", "Laat zien waar het staat en vraag of het hierheen moet."),
    KEEP("Laten staan", "Niet verplaatsen; in de lijst kun je het alsnog hierheen zetten."),
}

/** Instellingen (ontwerp §6.12), bewaard in SharedPreferences en als flow te volgen. */
class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _wifiOnly = flag(KEY_WIFI_ONLY, false)
    private val _autoLookup = flag(KEY_AUTO_LOOKUP, true)
    private val _libraryGrid = flag(KEY_LIBRARY_GRID, false)
    private val _scanSound = flag(KEY_SCAN_SOUND, true)
    private val _scanVibrate = flag(KEY_SCAN_VIBRATE, true)
    private val _loanReminders = flag(KEY_LOAN_REMINDERS, true)
    private val _syncInBackground = flag(KEY_SYNC_BACKGROUND, true)
    private val _shelfExisting = MutableStateFlow(
        prefs.getString(KEY_SHELF_EXISTING, null)?.let { runCatching { ShelfExisting.valueOf(it) }.getOrNull() } ?: ShelfExisting.MOVE,
    )
    private val _disabledSources = MutableStateFlow(prefs.getStringSet(KEY_DISABLED_SOURCES, emptySet()).orEmpty())

    /** Gegevens opzoeken alleen via wifi (niet via mobiele data). */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    /** Gegevens vanzelf opzoeken; uit = alleen met "Bibliotheek bijwerken" of "Gegevens opnieuw ophalen". */
    val autoLookup: StateFlow<Boolean> = _autoLookup
    val scanSound: StateFlow<Boolean> = _scanSound
    val scanVibrate: StateFlow<Boolean> = _scanVibrate
    val loanReminders: StateFlow<Boolean> = _loanReminders

    val shelfExisting: StateFlow<ShelfExisting> = _shelfExisting

    /** Bronnen die uit staan (op sleutel, zie LookupSource). */
    val disabledSources: StateFlow<Set<String>> = _disabledSources

    fun setShelfExisting(value: ShelfExisting) {
        prefs.edit().putString(KEY_SHELF_EXISTING, value.name).apply()
        _shelfExisting.value = value
    }

    fun isSourceEnabled(source: LookupSource) = source.key !in _disabledSources.value

    fun setSourceEnabled(source: LookupSource, enabled: Boolean) {
        val updated = if (enabled) _disabledSources.value - source.key else _disabledSources.value + source.key
        prefs.edit().putStringSet(KEY_DISABLED_SOURCES, updated).apply()
        _disabledSources.value = updated
    }

    /** Bereikbaar blijven voor gekoppelde telefoons als de app niet in beeld is (met vaste melding). */
    val syncInBackground: StateFlow<Boolean> = _syncInBackground

    fun setWifiOnly(value: Boolean) = set(KEY_WIFI_ONLY, _wifiOnly, value)
    fun setAutoLookup(value: Boolean) = set(KEY_AUTO_LOOKUP, _autoLookup, value)

    /** Bibliotheek als raster van covers in plaats van een lijst. */
    val libraryGrid: StateFlow<Boolean> = _libraryGrid
    fun setLibraryGrid(value: Boolean) = set(KEY_LIBRARY_GRID, _libraryGrid, value)
    fun setScanSound(value: Boolean) = set(KEY_SCAN_SOUND, _scanSound, value)
    fun setScanVibrate(value: Boolean) = set(KEY_SCAN_VIBRATE, _scanVibrate, value)
    fun setLoanReminders(value: Boolean) = set(KEY_LOAN_REMINDERS, _loanReminders, value)
    fun setSyncInBackground(value: Boolean) = set(KEY_SYNC_BACKGROUND, _syncInBackground, value)

    /** Mag er nu meteen worden opgezocht (bijv. de snelle lookup na een scan)? */
    fun mayLookUpNow(): Boolean {
        if (!_autoLookup.value) return false
        if (!_wifiOnly.value) return true
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return !connectivity.isActiveNetworkMetered
    }

    private fun set(key: String, flow: MutableStateFlow<Boolean>, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        flow.value = value
    }

    private companion object {
        const val KEY_WIFI_ONLY = "lookup-wifi-only"
        const val KEY_AUTO_LOOKUP = "lookup-auto"
        const val KEY_LIBRARY_GRID = "library-grid"
        const val KEY_SCAN_SOUND = "scan-sound"
        const val KEY_SCAN_VIBRATE = "scan-vibrate"
        const val KEY_LOAN_REMINDERS = "loan-reminders"
        const val KEY_SHELF_EXISTING = "shelf-existing"
        const val KEY_DISABLED_SOURCES = "disabled-sources"
        const val KEY_SYNC_BACKGROUND = "sync-background"
    }
}
