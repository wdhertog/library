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
import nl.wdhertog.library.MainActivity
import nl.wdhertog.library.R

/**
 * Houdt de app bereikbaar voor gekoppelde telefoons als hij niet in beeld is (ontwerp §11). Android
 * bevriest apps op de achtergrond; een dienst met een vaste, stille melding voorkomt dat. Het luisteren
 * zelf doet [SyncManager]; deze dienst houdt alleen het proces wakker.
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Synchroniseren", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Vaste melding zolang de app klaarstaat om met je andere telefoons te synchroniseren"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Klaar om te synchroniseren")
            .setContentText("Uit te zetten in Instellingen → Synchroniseren")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 7

        /** Alleen aanroepen terwijl de app in beeld is; vanaf de achtergrond staat Android dit niet toe. */
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
