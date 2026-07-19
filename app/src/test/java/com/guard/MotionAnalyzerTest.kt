package com.guard

import com.guard.MotionAnalyzer.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection behaviour, driven by synthetic accelerometer samples. The phone is
 * modelled lying flat: gravity along +Z.
 */
class MotionAnalyzerTest {

    private val g = MotionAnalyzer.GRAVITY_EARTH
    private val sens = 70 // the default slider position
    private val tiltThresh = GuardPrefs.sensitivityToTiltDeg(sens)
    private val joltThresh = GuardPrefs.sensitivityToJoltMs2(sens)

    private val analyzer = MotionAnalyzer()

    /** One sample at [t] ms, using the default thresholds. */
    private fun sample(x: Float, y: Float, z: Float, t: Long): Outcome =
        analyzer.onSample(x, y, z, t, tiltThresh, joltThresh)

    /** Feed still samples until the resting orientation is captured; returns the time. */
    private fun settle(startMs: Long = 1000L, stepMs: Long = 100L): Long {
        var t = startMs
        while (!analyzer.baselineFrozen) {
            val outcome = sample(0f, 0f, g, t)
            if (analyzer.baselineFrozen) {
                assertEquals("baseline must be locked by stillness, not by timeout",
                    Outcome.BASELINE_LOCKED, outcome)
                return t
            }
            t += stepMs
            assertTrue("baseline never locked", t < startMs + 20_000L)
        }
        return t
    }

    // ---- Phase 1: baselining ---------------------------------------------

    @Test
    fun `a still phone locks its resting orientation`() {
        analyzer.reset(0L)
        assertFalse(analyzer.baselineFrozen)
        settle()
        assertTrue(analyzer.baselineFrozen)
    }

    @Test
    fun `stillness must be sustained, a single still sample is not enough`() {
        analyzer.reset(0L)
        assertEquals(Outcome.NONE, sample(0f, 0f, g, 1000L))
        assertFalse(analyzer.baselineFrozen)
    }

    @Test
    fun `movement restarts the stillness timer`() {
        analyzer.reset(0L)
        sample(0f, 0f, g, 1000L)          // still: timer starts
        sample(0f, 0f, g + 3f, 1600L)     // jolted: timer resets
        // Without the reset, this sample would be 700ms after the first one and
        // would lock the baseline while the phone is still being handled.
        assertEquals(Outcome.NONE, sample(0f, 0f, g, 1700L))
        assertFalse(analyzer.baselineFrozen)
    }

    @Test
    fun `never waits forever for stillness`() {
        // A phone on a vibrating surface never goes still; the guard must start
        // watching anyway rather than stay blind indefinitely.
        analyzer.reset(0L)
        var t = 0L
        var outcome = Outcome.NONE
        while (t <= 11_000L && outcome != Outcome.BASELINE_TIMEOUT) {
            t += 100L
            outcome = sample(0f, 0f, g + 3f, t)
        }
        assertEquals(Outcome.BASELINE_TIMEOUT, outcome)
        assertTrue(analyzer.baselineFrozen)
    }

    @Test
    fun `no motion is reported before the baseline exists`() {
        // Phase 1 must never alarm — the phone is still in the owner's hand.
        analyzer.reset(0L)
        for (t in 100L..600L step 100L) {
            assertNotEquals(Outcome.THRESHOLD_HIT, sample(g, 0f, 0f, t))
        }
    }

    // ---- Phase 2: watching -----------------------------------------------

    @Test
    fun `a resting phone is not a pickup`() {
        analyzer.reset(0L)
        var t = settle()
        repeat(20) {
            t += 100L
            assertEquals(Outcome.NONE, sample(0f, 0f, g, t))
        }
    }

    @Test
    fun `small noise on a resting surface does not trigger`() {
        // A passing truck / a tap on the table: well under both thresholds.
        analyzer.reset(0L)
        var t = settle()
        repeat(20) { i ->
            t += 100L
            val wobble = if (i % 2 == 0) 0.2f else -0.2f
            assertEquals(Outcome.NONE, sample(wobble, 0f, g + wobble, t))
        }
    }

    @Test
    fun `tilting the phone off its resting orientation triggers`() {
        analyzer.reset(0L)
        var t = settle()
        // Turned on its side: same magnitude (so jolt stays 0) but a large tilt —
        // this isolates the tilt path from the jolt path.
        var outcome = Outcome.NONE
        repeat(5) {
            t += 100L
            if (outcome != Outcome.THRESHOLD_HIT) outcome = sample(g, 0f, 0f, t)
        }
        assertEquals(Outcome.THRESHOLD_HIT, outcome)
        assertTrue("tilt should be reported for the log", analyzer.tiltDeg > tiltThresh)
    }

    @Test
    fun `a snatch triggers on the jolt alone`() {
        analyzer.reset(0L)
        var t = settle()
        // Yanked upwards: direction barely changes, but the magnitude spikes.
        t += 100L
        assertEquals(Outcome.NONE, sample(0f, 0f, g + 6f, t)) // debounced: one hit
        t += 100L
        assertEquals(Outcome.THRESHOLD_HIT, sample(0f, 0f, g + 6f, t))
    }

    @Test
    fun `a single stray sample is debounced away`() {
        analyzer.reset(0L)
        var t = settle()
        t += 100L
        assertEquals(Outcome.NONE, sample(0f, 0f, g + 6f, t)) // one spike
        t += 100L
        assertEquals(Outcome.NONE, sample(0f, 0f, g, t))      // back to rest
        t += 100L
        assertEquals(Outcome.NONE, sample(0f, 0f, g, t))      // still quiet
    }

    @Test
    fun `sensitivity changes what counts as a pickup`() {
        // The same gentle nudge: ignored at the lowest sensitivity, caught at the
        // highest. This is the guarantee the slider makes to the user.
        val nudge = 1.0f // m/s^2 above gravity

        val insensitive = MotionAnalyzer()
        val sensitive = MotionAnalyzer()
        for (a in listOf(insensitive, sensitive)) {
            a.reset(0L)
            var t = 1000L
            while (!a.baselineFrozen) {
                a.onSample(0f, 0f, g, t, 1f, 1f)
                t += 100L
            }
        }

        var lowOutcome = Outcome.NONE
        var highOutcome = Outcome.NONE
        var t = 5000L
        repeat(4) {
            t += 100L
            if (lowOutcome != Outcome.THRESHOLD_HIT) {
                lowOutcome = insensitive.onSample(
                    0f, 0f, g + nudge, t,
                    GuardPrefs.sensitivityToTiltDeg(0), GuardPrefs.sensitivityToJoltMs2(0)
                )
            }
            if (highOutcome != Outcome.THRESHOLD_HIT) {
                highOutcome = sensitive.onSample(
                    0f, 0f, g + nudge, t,
                    GuardPrefs.sensitivityToTiltDeg(100), GuardPrefs.sensitivityToJoltMs2(100)
                )
            }
        }
        assertEquals(Outcome.NONE, lowOutcome)
        assertEquals(Outcome.THRESHOLD_HIT, highOutcome)
    }

    // ---- Re-baselining ----------------------------------------------------

    @Test
    fun `rebaseline returns to phase 1 and cannot trigger until settled again`() {
        // This is what happens when the phone is repositioned on its charger while
        // the alarm is paused: it must adopt the new resting position, not alarm.
        analyzer.reset(0L)
        var t = settle()
        t += 100L
        analyzer.rebaseline(t)
        assertFalse(analyzer.baselineFrozen)
        repeat(3) {
            t += 100L
            assertNotEquals(Outcome.THRESHOLD_HIT, sample(g, 0f, 0f, t))
        }
    }

    @Test
    fun `after rebaselining, the new resting orientation is the reference`() {
        analyzer.reset(0L)
        var t = settle()
        // Phone stood on its side and left there.
        t += 100L
        analyzer.rebaseline(t)
        while (!analyzer.baselineFrozen) {
            t += 100L
            sample(g, 0f, 0f, t)
        }
        // Resting in the NEW orientation is now quiet...
        repeat(5) {
            t += 100L
            assertEquals(Outcome.NONE, sample(g, 0f, 0f, t))
        }
        // ...while returning to the OLD one is a pickup.
        var outcome = Outcome.NONE
        repeat(5) {
            t += 100L
            if (outcome != Outcome.THRESHOLD_HIT) outcome = sample(0f, 0f, g, t)
        }
        assertEquals(Outcome.THRESHOLD_HIT, outcome)
    }

    // ---- The angle helper -------------------------------------------------

    @Test
    fun `angleBetweenDeg covers the basic cases`() {
        val eps = 0.01f
        assertEquals(0f, MotionAnalyzer.angleBetweenDeg(0f, 0f, 9.8f, 0f, 0f, 9.8f), eps)
        assertEquals(90f, MotionAnalyzer.angleBetweenDeg(0f, 0f, 9.8f, 9.8f, 0f, 0f), eps)
        assertEquals(180f, MotionAnalyzer.angleBetweenDeg(0f, 0f, 9.8f, 0f, 0f, -9.8f), eps)
        // Magnitude must not matter, only direction.
        assertEquals(0f, MotionAnalyzer.angleBetweenDeg(0f, 0f, 1f, 0f, 0f, 9.8f), eps)
        // A degenerate vector yields 0 rather than NaN, so it can never trigger.
        assertEquals(0f, MotionAnalyzer.angleBetweenDeg(0f, 0f, 0f, 0f, 0f, 9.8f), eps)
    }
}
