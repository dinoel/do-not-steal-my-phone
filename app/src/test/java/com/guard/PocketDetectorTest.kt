package com.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pocket mode. The failure that matters is a *false* alarm: this path fires the
 * siren directly, with no tilt to corroborate it, so anything short of a real
 * "covered for a while, then uncovered" must stay silent.
 */
class PocketDetectorTest {

    private val dwell = PocketDetector.NEAR_DWELL_MS
    private val detector = PocketDetector()

    @Test
    fun `taking the phone out of a pocket is detected`() {
        var t = 1000L
        assertFalse(detector.onSample(isNear = true, nowMs = t))
        t += dwell + 100
        assertTrue(detector.onSample(isNear = false, nowMs = t))
    }

    @Test
    fun `a brief brush against the sensor is not a removal`() {
        // A hand or a sleeve passing over the sensor: covered, but not for long.
        var t = 1000L
        assertFalse(detector.onSample(isNear = true, nowMs = t))
        t += dwell - 200
        assertFalse(detector.onSample(isNear = false, nowMs = t))
    }

    @Test
    fun `a phone lying face-up never triggers`() {
        // Uncovered the whole time — this is the accelerometer's job, not ours.
        var t = 1000L
        repeat(50) {
            assertFalse(detector.onSample(isNear = false, nowMs = t))
            t += 500
        }
    }

    @Test
    fun `staying in the pocket does not trigger`() {
        var t = 1000L
        repeat(50) {
            assertFalse(detector.onSample(isNear = true, nowMs = t))
            t += 500
        }
    }

    @Test
    fun `removal fires once, not repeatedly`() {
        var t = 1000L
        detector.onSample(isNear = true, nowMs = t)
        t += dwell + 100
        assertTrue(detector.onSample(isNear = false, nowMs = t))
        repeat(10) {
            t += 500
            assertFalse(detector.onSample(isNear = false, nowMs = t))
        }
    }

    @Test
    fun `the phone can be put back and taken out again`() {
        var t = 1000L
        detector.onSample(isNear = true, nowMs = t)
        t += dwell + 100
        assertTrue(detector.onSample(isNear = false, nowMs = t))

        t += 5000
        detector.onSample(isNear = true, nowMs = t) // back in the pocket
        t += dwell + 100
        assertTrue(detector.onSample(isNear = false, nowMs = t))
    }

    @Test
    fun `the dwell timer starts at the first covered reading, not the latest`() {
        // Proximity is event-driven, but a sensor that re-reports "near" must not
        // keep pushing the deadline out and suppress a real removal.
        var t = 1000L
        repeat(5) {
            assertFalse(detector.onSample(isNear = true, nowMs = t))
            t += dwell / 4
        }
        assertTrue(detector.onSample(isNear = false, nowMs = t))
    }

    @Test
    fun `reset forgets that the phone was covered`() {
        var t = 1000L
        detector.onSample(isNear = true, nowMs = t)
        t += dwell + 100
        detector.reset() // e.g. the sensor was unregistered and re-registered
        assertFalse(detector.onSample(isNear = false, nowMs = t))
    }

    @Test
    fun `raw sensor values are classified for both binary and ranged sensors`() {
        // The common case: a binary sensor reporting 0 (covered) or its max range.
        assertTrue(PocketDetector.isNear(0f, maxRangeCm = 5f))
        assertFalse(PocketDetector.isNear(5f, maxRangeCm = 5f))
        // A sensor reporting real centimetres over a longer range.
        assertTrue(PocketDetector.isNear(1.5f, maxRangeCm = 100f))
        assertFalse(PocketDetector.isNear(40f, maxRangeCm = 100f))
        // A nonsensical max range must not classify everything as covered.
        assertFalse(PocketDetector.isNear(9f, maxRangeCm = 0f))
    }
}
