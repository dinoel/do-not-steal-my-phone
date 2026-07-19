package com.guard

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The pickup-detection maths, extracted from the sensor listener so it can be
 * unit-tested on the JVM: it holds no Android types and does no I/O — samples in,
 * [Outcome] out. `GuardService` owns the sensor registration and decides what an
 * outcome *means* (alarm vs. re-baseline).
 *
 * Two phases:
 *  1. **Baselining** — wait until the phone has been set down and held still, then
 *     freeze its resting orientation. This is what makes "arm, then put the phone
 *     down" work without the placing motion itself triggering the alarm.
 *  2. **Watching** — flag a *tilt* away from that resting orientation (lifted off
 *     the surface) or a *jolt* (grabbed), debounced over consecutive samples.
 */
class MotionAnalyzer {

    /** What a sample means to the caller. */
    enum class Outcome {
        /** Nothing to do. */
        NONE,

        /** The phone was still long enough; resting orientation captured. */
        BASELINE_LOCKED,

        /** Gave up waiting for stillness (e.g. a vibrating surface); watching anyway. */
        BASELINE_TIMEOUT,

        /** Tilt or jolt over threshold on enough consecutive samples. */
        THRESHOLD_HIT,
    }

    /** Low-pass estimate of the gravity vector (device orientation). */
    private var gX = 0f
    private var gY = 0f
    private var gZ = 0f
    private var gravityInit = false

    /** Resting orientation, captured once the phone is still. */
    private var baseX = 0f
    private var baseY = 0f
    private var baseZ = 0f

    private var stillSinceMs = 0L
    private var armStartMs = 0L
    private var detectHits = 0

    /** True once the resting orientation is captured, i.e. phase 2. */
    var baselineFrozen = false
        private set

    /** Readings from the most recent sample, for logging the trigger reason. */
    var tiltDeg = 0f
        private set
    var jolt = 0f
        private set

    /** Full reset, including the gravity estimate. Use when (re)starting the sensor. */
    fun reset(nowMs: Long) {
        gravityInit = false
        rebaseline(nowMs)
    }

    /**
     * Drop back to phase 1 but keep the gravity estimate, so a phone that was
     * legitimately moved settles into its new resting position quickly.
     */
    fun rebaseline(nowMs: Long) {
        baselineFrozen = false
        stillSinceMs = 0L
        detectHits = 0
        armStartMs = nowMs
    }

    /**
     * Feed one accelerometer sample. [tiltThreshDeg] and [joltThreshMs2] come from
     * the sensitivity setting (see [GuardPrefs.sensitivityToTiltDeg]).
     */
    fun onSample(
        x: Float,
        y: Float,
        z: Float,
        nowMs: Long,
        tiltThreshDeg: Float,
        joltThreshMs2: Float,
    ): Outcome {
        if (!gravityInit) {
            gX = x; gY = y; gZ = z
            gravityInit = true
        } else {
            gX = GRAVITY_LP * gX + (1 - GRAVITY_LP) * x
            gY = GRAVITY_LP * gY + (1 - GRAVITY_LP) * y
            gZ = GRAVITY_LP * gZ + (1 - GRAVITY_LP) * z
        }

        jolt = abs(sqrt(x * x + y * y + z * z) - GRAVITY_EARTH)

        if (!baselineFrozen) {
            // Track the current orientation until it holds still long enough.
            baseX = gX; baseY = gY; baseZ = gZ
            if (jolt < STILL_JOLT_MS2) {
                if (stillSinceMs == 0L) {
                    stillSinceMs = nowMs
                } else if (nowMs - stillSinceMs >= STILL_MS) {
                    baselineFrozen = true
                    return Outcome.BASELINE_LOCKED
                }
            } else {
                stillSinceMs = 0L
            }
            // Safety: never wait forever to start watching.
            if (nowMs - armStartMs >= BASELINE_MAX_MS) {
                baselineFrozen = true
                return Outcome.BASELINE_TIMEOUT
            }
            return Outcome.NONE
        }

        tiltDeg = angleBetweenDeg(gX, gY, gZ, baseX, baseY, baseZ)
        if (tiltDeg > tiltThreshDeg || jolt > joltThreshMs2) {
            detectHits++
            if (detectHits >= DETECT_HITS) return Outcome.THRESHOLD_HIT
        } else {
            detectHits = 0
        }
        return Outcome.NONE
    }

    companion object {
        /** Same value as `SensorManager.GRAVITY_EARTH`, inlined to stay Android-free. */
        const val GRAVITY_EARTH = 9.80665f

        private const val GRAVITY_LP = 0.85f        // gravity low-pass smoothing
        private const val STILL_JOLT_MS2 = 0.7f     // "still enough" to lock the baseline
        private const val STILL_MS = 700L           // must be still this long first
        private const val BASELINE_MAX_MS = 10_000L // give up waiting for stillness
        private const val DETECT_HITS = 2           // consecutive samples, debounces noise

        /** Angle in degrees between two 3-vectors (0 if either is degenerate). */
        fun angleBetweenDeg(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
        ): Float {
            val dot = ax * bx + ay * by + az * bz
            val magA = sqrt(ax * ax + ay * ay + az * az)
            val magB = sqrt(bx * bx + by * by + bz * bz)
            if (magA < 1e-3f || magB < 1e-3f) return 0f
            val cos = (dot / (magA * magB)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
    }
}
