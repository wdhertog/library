package nl.wdhertog.library.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import nl.wdhertog.library.reminders.LoanReminderWorker

/**
 * Alleen in debug-builds: start achtergrondtaken direct, zonder op het schema te wachten.
 *   adb shell am broadcast -a nl.wdhertog.library.debug.RUN_REMINDERS -p nl.wdhertog.library
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RUN_REMINDERS) LoanReminderWorker.runNow(context)
    }

    companion object {
        const val ACTION_RUN_REMINDERS = "nl.wdhertog.library.debug.RUN_REMINDERS"
    }
}
