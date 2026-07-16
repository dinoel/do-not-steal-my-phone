package com.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager backstop (spec §6.3). Fires periodically while armed; if the
 * service was killed, this restarts it and asks it to make its live state
 * consistent (re-register standby, reschedule the next watchdog). This is a
 * safety net — START_STICKY is the primary restart path.
 *
 * Firing an alarm gives the app a short window in which starting a foreground
 * service from the background is permitted, so ACTION_SELF_CHECK is allowed here.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = GuardPrefs(context)
        if (prefs.state.isActive) {
            Log.i(TAG, "Watchdog: guard should be active, poking service")
            GuardService.send(context, GuardService.ACTION_SELF_CHECK)
        }
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
    }
}
