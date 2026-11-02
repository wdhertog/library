package nl.wdhertog.library.util

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Instellingen (ontwerp §6.12), bewaard in SharedPreferences en als flow te volgen. */
class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _wifiOnly = flag(KEY_WIFI_ONLY, false)
    private val _scanSound = flag(KEY_SCAN_SOUND, true)
    private val _scanVibrate = flag(KEY_SCAN_VIBRATE, true)
    private val _loanReminders = flag(KEY_LOAN_REMINDERS, true)

    /** Gegevens opzoeken alleen via wifi (niet via mobiele data). */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly
    val scanSound: StateFlow<Boolean> = _scanSound
    val scanVibrate: StateFlow<Boolean> = _scanVibrate
    val loanReminders: StateFlow<Boolean> = _loanReminders

    fun setWifiOnly(value: Boolean) = set(KEY_WIFI_ONLY, _wifiOnly, value)
    fun setScanSound(value: Boolean) = set(KEY_SCAN_SOUND, _scanSound, value)
    fun setScanVibrate(value: Boolean) = set(KEY_SCAN_VIBRATE, _scanVibrate, value)
    fun setLoanReminders(value: Boolean) = set(KEY_LOAN_REMINDERS, _loanReminders, value)

    /** Mag er nu meteen worden opgezocht (bijv. de snelle lookup na een scan)? */
    fun mayLookUpNow(): Boolean {
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
        const val KEY_SCAN_SOUND = "scan-sound"
        const val KEY_SCAN_VIBRATE = "scan-vibrate"
        const val KEY_LOAN_REMINDERS = "loan-reminders"
    }
}
