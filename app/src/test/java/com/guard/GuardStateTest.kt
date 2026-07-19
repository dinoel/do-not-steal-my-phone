package com.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuardState] is persisted by name in SharedPreferences and read back by the
 * service, the tile and the UI, so the name round-trip and the
 * unknown-value fallback are load-bearing: a parse failure must degrade to
 * UNARMED rather than throw inside a tile/notification callback.
 */
class GuardStateTest {

    @Test
    fun `every state round-trips through its name`() {
        for (state in GuardState.entries) {
            assertEquals(state, GuardState.fromName(state.name))
        }
    }

    @Test
    fun `unknown or missing names fall back to UNARMED`() {
        assertEquals(GuardState.UNARMED, GuardState.fromName(null))
        assertEquals(GuardState.UNARMED, GuardState.fromName(""))
        assertEquals(GuardState.UNARMED, GuardState.fromName("NOT_A_STATE"))
        // Matching is exact, so a differently-cased value is not a state either.
        assertEquals(GuardState.UNARMED, GuardState.fromName("armed"))
    }

    @Test
    fun `isActive is true for every state except UNARMED`() {
        assertFalse(GuardState.UNARMED.isActive)
        assertTrue(GuardState.ARMED.isActive)
        assertTrue(GuardState.TRIGGERED.isActive)
        assertTrue(GuardState.ALARM.isActive)
    }
}
