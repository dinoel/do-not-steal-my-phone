package com.guard

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — the primary arm/disarm control (spec §4.1).
 *
 * The tile is stateless itself: it reads the current [GuardState] from prefs and
 * sends the service an explicit ACTION_ARM / ACTION_DISARM (not ACTION_TOGGLE —
 * an unlock can change the state between the tap and the command arriving, and an
 * explicit action can't be misread and re-arm what the user just disarmed).
 * Disarming is gated behind [unlockAndRun] so it can't be done from the lock
 * screen. Whenever the state changes anywhere (including auto-disarm on unlock),
 * the service calls [requestUpdate] so the tile redraws.
 */
class GuardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Reflect the real persisted state whenever the panel (re)opens.
        setTile(GuardPrefs(this).state.isActive)
    }

    override fun onClick() {
        super.onClick()
        val prefs = GuardPrefs(this)
        val willBeActive = !prefs.state.isActive
        if (willBeActive) {
            // Arming from the lock screen is fine and desirable.
            toggle(willBeActive)
        } else {
            // Disarming must require an unlock, so a thief can't silence the alarm
            // from the lock-screen quick settings. unlockAndRun shows the keyguard
            // when locked and runs the toggle only after a successful unlock.
            unlockAndRun { toggle(willBeActive) }
        }
    }

    private fun toggle(willBeActive: Boolean) {
        // Log the tap directly (works even if the service fails to start).
        GuardPrefs(this).appendLog(
            if (willBeActive) "Tile tapped → arming" else "Tile tapped → disarming"
        )
        // Send the EXPLICIT action, not TOGGLE: after unlockAndRun the unlock's
        // USER_PRESENT may already have disarmed the guard ("stay armed" off), and
        // a toggle arriving then would re-arm the phone the user just disarmed.
        GuardService.send(
            this,
            if (willBeActive) GuardService.ACTION_ARM else GuardService.ACTION_DISARM
        )
        // Immediately show the NEW state — don't re-read the old saved state, which
        // the service hasn't updated yet (that made the tile snap back until reopened).
        setTile(willBeActive)
    }

    private fun setTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        tile.contentDescription =
            if (active) getString(R.string.state_armed) else getString(R.string.state_unarmed)
        tile.updateTile()
    }

    companion object {
        /** Ask the platform to call [onStartListening] so the tile can redraw. */
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, GuardTileService::class.java)
                )
            }
        }
    }
}
