package com.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security-relevant decisions. A regression in any of these is not a cosmetic
 * bug: it either lets a thief silence the guard, or makes the siren fire during
 * normal use.
 */
class WatchGateTest {

    // ---- watchMode --------------------------------------------------------

    @Test
    fun `no watching until the phone has actually been locked`() {
        // Placing and locking the phone must not self-trigger the alarm.
        assertEquals(
            WatchMode.IDLE,
            WatchGate.watchMode(
                hasBeenLocked = false,
                pauseWhileChargingSetting = false,
                chargerPauseAllowed = false,
                pluggedIn = false,
            )
        )
    }

    @Test
    fun `locked and on battery means watching`() {
        assertEquals(
            WatchMode.WATCHING,
            WatchGate.watchMode(
                hasBeenLocked = true,
                pauseWhileChargingSetting = true,
                chargerPauseAllowed = true,
                pluggedIn = false,
            )
        )
    }

    @Test
    fun `an owner-connected charger pauses only while the setting is on`() {
        assertEquals(
            WatchMode.PAUSED_CHARGING,
            WatchGate.watchMode(
                hasBeenLocked = true,
                pauseWhileChargingSetting = true,
                chargerPauseAllowed = true,
                pluggedIn = true,
            )
        )
        assertEquals(
            WatchMode.WATCHING,
            WatchGate.watchMode(
                hasBeenLocked = true,
                pauseWhileChargingSetting = false,
                chargerPauseAllowed = true,
                pluggedIn = true,
            )
        )
    }

    @Test
    fun `a charger that was not owner-connected never pauses the alarm`() {
        // The power-bank attack: plug the locked phone in and hope the guard pauses.
        assertEquals(
            WatchMode.WATCHING,
            WatchGate.watchMode(
                hasBeenLocked = true,
                pauseWhileChargingSetting = true,
                chargerPauseAllowed = false,
                pluggedIn = true,
            )
        )
    }

    @Test
    fun `unplugging resumes watching immediately`() {
        val paused = WatchGate.watchMode(true, true, true, pluggedIn = true)
        val unplugged = WatchGate.watchMode(true, true, true, pluggedIn = false)
        assertEquals(WatchMode.PAUSED_CHARGING, paused)
        assertEquals(WatchMode.WATCHING, unplugged)
    }

    // ---- chargerMayPause --------------------------------------------------

    @Test
    fun `only a charger connected before the phone was locked may pause`() {
        assertTrue(WatchGate.chargerMayPause(hasBeenLocked = false))
        assertFalse(WatchGate.chargerMayPause(hasBeenLocked = true))
    }

    // ---- disarmAllowed ----------------------------------------------------

    @Test
    fun `disarm is refused while the phone is securely locked`() {
        assertFalse(WatchGate.disarmAllowed(keyguardLocked = true, deviceSecure = true))
    }

    @Test
    fun `disarm is allowed once unlocked, or when there is no lock at all`() {
        assertTrue(WatchGate.disarmAllowed(keyguardLocked = false, deviceSecure = true))
        // No PIN/pattern/biometric set: there is nothing to unlock, so refusing
        // would only lock the owner out of their own alarm.
        assertTrue(WatchGate.disarmAllowed(keyguardLocked = true, deviceSecure = false))
        assertTrue(WatchGate.disarmAllowed(keyguardLocked = false, deviceSecure = false))
    }

    // ---- unlockedByOwner --------------------------------------------------

    @Test
    fun `owner is present only when a secure keyguard is dismissed`() {
        assertTrue(WatchGate.unlockedByOwner(keyguardLocked = false, deviceSecure = true))
        assertFalse(WatchGate.unlockedByOwner(keyguardLocked = true, deviceSecure = true))
    }

    @Test
    fun `an insecure device never counts as proof the owner is present`() {
        // Anyone can dismiss a swipe-only keyguard, so it proves nothing — and this
        // must not become a way to suppress the alarm.
        assertFalse(WatchGate.unlockedByOwner(keyguardLocked = false, deviceSecure = false))
        assertFalse(WatchGate.unlockedByOwner(keyguardLocked = true, deviceSecure = false))
    }

    @Test
    fun `unlockedByOwner and disarmAllowed never contradict each other`() {
        // Whenever the owner is demonstrably present, disarming must be possible.
        for (locked in listOf(true, false)) {
            for (secure in listOf(true, false)) {
                if (WatchGate.unlockedByOwner(locked, secure)) {
                    assertTrue(
                        "locked=$locked secure=$secure",
                        WatchGate.disarmAllowed(locked, secure)
                    )
                }
            }
        }
    }

    // ---- phoneBusy --------------------------------------------------------

    @Test
    fun `ringing and in-call audio modes suppress the alarm`() {
        // Values are android.media.AudioManager's MODE_* constants.
        assertTrue(WatchGate.phoneBusy(1)) // MODE_RINGTONE
        assertTrue(WatchGate.phoneBusy(2)) // MODE_IN_CALL
        assertTrue(WatchGate.phoneBusy(3)) // MODE_IN_COMMUNICATION
    }

    @Test
    fun `normal and invalid audio modes do not suppress the alarm`() {
        assertFalse(WatchGate.phoneBusy(0))  // MODE_NORMAL
        assertFalse(WatchGate.phoneBusy(-1)) // MODE_INVALID
        assertFalse(WatchGate.phoneBusy(4))  // MODE_CALL_SCREENING — not a live call
    }
}
