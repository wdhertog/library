package nl.wdhertog.library.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nl.wdhertog.library.LibraryApp
import nl.wdhertog.library.MainActivity
import nl.wdhertog.library.R
import nl.wdhertog.library.util.LoanDates
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Dagelijkse controle op uitleningen die terug moeten. */
class LoanReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val loans = (applicationContext as LibraryApp).container.loanRepository
        val today = LocalDate.now()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reminders = mutableListOf<Reminder>()

        loans.loansDueBy(today).forEach { loan ->
            val due = loan.dueAt ?: return@forEach
            reminders += Reminder(
                key = "loan-${loan.loanId}-$due",
                remindOn = due,
                title = "${loan.title.ifBlank { "Uitgeleend boek" }} terugvragen",
                text = "Uitgeleend aan ${loan.personName ?: "onbekend"} · ${LoanDates.due(due, today).text}",
            )
        }
        // Zelf geleende boeken: een dag van tevoren, zodat je het nog kunt terugbrengen.
        loans.borrowedDueBy(today.plusDays(1)).forEach { row ->
            val due = row.dueAt ?: return@forEach
            reminders += Reminder(
                key = "borrowed-${row.bookId}-$due",
                remindOn = due.minusDays(1),
                title = "${row.title.ifBlank { "Geleend boek" }} terugbrengen",
                text = "Geleend van ${row.personName ?: "onbekend"} · ${LoanDates.due(due, today).text}",
            )
        }

        if (reminders.isEmpty() || !canNotify()) return Result.success()
        ensureChannel()
        val manager = NotificationManagerCompat.from(applicationContext)
        reminders.forEach { reminder ->
            val last = prefs.getString(reminder.key, null)?.let(LocalDate::parse)
            if (!ReminderPolicy.shouldNotify(reminder.remindOn, today, last)) return@forEach
            manager.notify(reminder.key.hashCode(), notification(reminder))
            prefs.edit { putString(reminder.key, today.toString()) }
        }
        return Result.success()
    }

    private data class Reminder(val key: String, val remindOn: LocalDate, val title: String, val text: String)

    private fun canNotify(): Boolean =
        NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun notification(reminder: Reminder) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    Intent(applicationContext, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_LOANS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, "Uitleen-herinneringen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Wanneer een uitgeleend of geleend boek terug moet"
        }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "loans"
        private const val PREFS = "loan-reminders"
        private const val WORK_NAME = "loan-reminders"

        /** Eens per dag, rond 9 uur 's ochtends. Bestaand schema blijft staan. */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(9, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<LoanReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
