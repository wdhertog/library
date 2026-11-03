package nl.wdhertog.library.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import nl.wdhertog.library.BuildConfig
import nl.wdhertog.library.LibraryApp

/** Na het opstarten van de telefoon of een update van de app: de achtergronddienst weer aanzetten. */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.SYNC) return
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as LibraryApp).container
        if (!container.settings.syncInBackground.value) return
        val result = goAsync()
        container.appScope.launch {
            try {
                if (container.syncRepository.peers().isNotEmpty()) SyncService.start(context)
            } finally {
                result.finish()
            }
        }
    }
}
