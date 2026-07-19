package com.guard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mode is persisted by name, and an unreadable value must fall back to the
 * *stronger* protection rather than the weaker one.
 */
class GuardModeTest {

    @Test
    fun `every mode round-trips through its name`() {
        for (mode in GuardMode.entries) {
            assertEquals(mode, GuardMode.fromName(mode.name))
        }
    }

    @Test
    fun `an unreadable mode falls back to RESTING`() {
        // RESTING is the safe default: it guards on movement, so a parse failure
        // can never silently leave the phone unprotected.
        assertEquals(GuardMode.RESTING, GuardMode.fromName(null))
        assertEquals(GuardMode.RESTING, GuardMode.fromName(""))
        assertEquals(GuardMode.RESTING, GuardMode.fromName("carried"))
        assertEquals(GuardMode.RESTING, GuardMode.fromName("NOT_A_MODE"))
    }
}
