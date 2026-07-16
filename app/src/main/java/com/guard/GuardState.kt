package com.guard

/**
 * The four states of the guard state machine (see spec §3).
 *
 *  - [UNARMED]   guard off, no sensor monitoring.
 *  - [ARMED]     guard on. Sub-modes (see GuardService.updateWatchState): waiting
 *                for the phone to be locked, paused on an owner-connected charger,
 *                or actively watching the wake-up accelerometer for tilt/jolt.
 *  - [TRIGGERED] motion fired; wake lock held, grace timer counting down.
 *  - [ALARM]     grace expired without an owner unlock; siren playing.
 */
enum class GuardState {
    UNARMED,
    ARMED,
    TRIGGERED,
    ALARM;

    /** True for any state where the guard is actively engaged (tile shows "active"). */
    val isActive: Boolean
        get() = this != UNARMED

    companion object {
        fun fromName(name: String?): GuardState =
            entries.firstOrNull { it.name == name } ?: UNARMED
    }
}
