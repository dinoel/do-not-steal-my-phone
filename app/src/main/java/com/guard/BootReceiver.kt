package com.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Optional "survive a reboot while armed" feature (spec §6.3), default off.
 * On boot, only re-arms if the user enabled boot survival AND the guard was
 * armed when the device shut down.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = GuardPrefs(context)
        if (prefs.bootSurvival && prefs.state.isActive) {
            Log.i(TAG, "Boot survival on and was armed — re-arming")
            GuardService.send(context, GuardService.ACTION_ARM)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
