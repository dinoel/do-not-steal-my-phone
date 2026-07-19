package com.guard

/**
 * What the phone is being guarded *against*, which the sensors cannot infer on
 * their own.
 *
 * The two situations demand opposite responses to the same reading. A phone that
 * is covered and moving is either riding in your pocket (must stay silent) or
 * inside a bag being carried off (must scream). Nothing in the sensor data
 * separates those cases — only you know which one you are in, so this is an
 * explicit choice rather than a heuristic.
 */
enum class GuardMode {
    /** Phone or bag left somewhere. Any movement is theft. */
    RESTING,

    /**
     * Phone on your person. Movement is ignored while the proximity sensor is
     * covered; taking it out is what starts the countdown — and since a thief
     * cannot unlock, the grace period sorts out "you took it out" from "someone
     * else did". While uncovered this behaves exactly like [RESTING], so putting
     * the phone down on a table keeps guarding it without touching a setting.
     */
    CARRIED;

    companion object {
        fun fromName(name: String?): GuardMode =
            entries.firstOrNull { it.name == name } ?: RESTING
    }
}
