package nl.wdhertog.library.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.MainActivity
import nl.wdhertog.library.R

/**
 * Houdt de app bereikbaar voor gekoppelde telefoons als hij niet in beeld is (ontwerp §11). Android
 * bevriest apps op de achtergrond; een dienst met een vaste, stille melding voorkomt dat. Het luisteren
 * zelf doet [SyncManager], en alleen op een thuisnetwerk; deze dienst houdt het proces in leven.
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, home), type)
        running = true
        (application as LibraryApp).container.syncManager.onServiceStarted()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 7
        @Volatile private var running = false
        @Volatile private var home = true

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Synchroniseren", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Vaste melding zolang de app klaarstaat om met je andere telefoons te synchroniseren"
                setShowBadge(false)
            },
        )
        }

        private fun notification(context: Context, home: Boolean) = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (home) "Klaar om te synchroniseren" else "Synchroniseren wacht op het thuisnetwerk")
            .setContentText("Uit te zetten in Instellingen → Synchroniseren")
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        /** Meldingstekst bijwerken: thuis klaar, of wachten op het thuisnetwerk. */
        fun update(context: Context, home: Boolean) {
            if (this.home == home) return
            this.home = home
            if (running) runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, home)) }
        }

        /** Alleen aanroepen terwijl de app in beeld is; vanaf de achtergrond staat Android dit niet toe. */
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
