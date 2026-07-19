package com.guard

import kotlin.math.min

/**
 * "Pocket mode": detects the phone being pulled out of a pocket or bag, using the
 * proximity sensor. Pure logic, no Android types, so it is unit-tested on the JVM
 * (see [MotionAnalyzer] for the same split).
 *
 * A pocket keeps the sensor covered continuously, so the signature of a removal is
 * a **near → far** transition after a sustained period of "near". Requiring the
 * dwell is what separates a real removal from a hand or a sleeve brushing the
 * sensor for an instant. A phone lying face-up on a table reads "far" the whole
 * time and therefore never triggers this path — that is what the accelerometer is
 * for.
 */
class PocketDetector {

    private var near = false
    private var nearSinceMs = 0L

    /** True while the sensor is covered and has been for long enough to count. */
    fun isSettledNear(nowMs: Long): Boolean =
        near && nowMs - nearSinceMs >= NEAR_DWELL_MS

    fun reset() {
        near = false
        nearSinceMs = 0L
    }

    /**
     * Feed one proximity reading. Returns true exactly once, on the transition that
     * means "taken out of the pocket".
     */
    fun onSample(isNear: Boolean, nowMs: Long): Boolean {
        if (isNear) {
            if (!near) {
                near = true
                nearSinceMs = nowMs
            }
            return false
        }
        val removed = isSettledNear(nowMs)
        near = false
        nearSinceMs = 0L
        return removed
    }

    companion object {
        /** How long the sensor must stay covered before uncovering counts as a removal. */
        const val NEAR_DWELL_MS = 1500L

        /** Anything closer than this counts as covered. */
        private const val NEAR_MAX_CM = 5f

        /**
         * Interpret a raw `TYPE_PROXIMITY` value. Most phones ship a binary sensor
         * that reports either 0 or its maximum range; a few report real centimetres,
         * hence the [NEAR_MAX_CM] cap.
         */
        fun isNear(value: Float, maxRangeCm: Float): Boolean =
            value < min(if (maxRangeCm > 0f) maxRangeCm else NEAR_MAX_CM, NEAR_MAX_CM)
    }
}
