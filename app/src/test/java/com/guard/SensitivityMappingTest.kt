package com.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensitivity slider maps to the two detection thresholds. Getting the
 * direction wrong would invert the whole setting (slider right = less sensitive),
 * which is easy to do and hard to notice on a device, so it is pinned here.
 */
class SensitivityMappingTest {

    private val eps = 0.001f

    @Test
    fun `tilt threshold spans 30 to 6 degrees`() {
        assertEquals(30f, GuardPrefs.sensitivityToTiltDeg(0), eps)
        assertEquals(6f, GuardPrefs.sensitivityToTiltDeg(100), eps)
        assertEquals(13.2f, GuardPrefs.sensitivityToTiltDeg(70), eps) // the default
    }

    @Test
    fun `jolt threshold spans 3_5 to 0_6 m per s2`() {
        assertEquals(3.5f, GuardPrefs.sensitivityToJoltMs2(0), eps)
        assertEquals(0.6f, GuardPrefs.sensitivityToJoltMs2(100), eps)
        assertEquals(1.47f, GuardPrefs.sensitivityToJoltMs2(70), eps) // the default
    }

    @Test
    fun `higher sensitivity always means a lower threshold`() {
        for (s in 1..100) {
            assertTrue(
                "tilt threshold must decrease at sensitivity $s",
                GuardPrefs.sensitivityToTiltDeg(s) < GuardPrefs.sensitivityToTiltDeg(s - 1)
            )
            assertTrue(
                "jolt threshold must decrease at sensitivity $s",
                GuardPrefs.sensitivityToJoltMs2(s) < GuardPrefs.sensitivityToJoltMs2(s - 1)
            )
        }
    }

    @Test
    fun `out-of-range sensitivity is clamped, never extrapolated`() {
        assertEquals(GuardPrefs.sensitivityToTiltDeg(0), GuardPrefs.sensitivityToTiltDeg(-40), eps)
        assertEquals(GuardPrefs.sensitivityToTiltDeg(100), GuardPrefs.sensitivityToTiltDeg(900), eps)
        assertEquals(GuardPrefs.sensitivityToJoltMs2(0), GuardPrefs.sensitivityToJoltMs2(-40), eps)
        assertEquals(GuardPrefs.sensitivityToJoltMs2(100), GuardPrefs.sensitivityToJoltMs2(900), eps)
    }

    @Test
    fun `the default sensitivity is inside the slider range`() {
        assertTrue(GuardPrefs.DEFAULT_SENSITIVITY in 0..100)
        assertTrue(GuardPrefs.DEFAULT_GRACE_SECONDS in GuardPrefs.MIN_GRACE_SECONDS..GuardPrefs.MAX_GRACE_SECONDS)
    }
}
