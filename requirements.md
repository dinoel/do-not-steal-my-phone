# Technical Specification — "Guard" Anti-Theft Alarm (Android)

## 0. Instructions for the coding agent

You are building a complete, installable Android app from this spec. Use **Kotlin** and **Jetpack**. Target a modern Samsung device (Galaxy S23, One UI) but rely only on the **standard Android SDK** — no root, no device owner, no MDM provisioning, no factory reset. The app must install and run as an **ordinary APK**.

Where this spec leaves a small choice open, pick the simplest option that satisfies the acceptance criteria and note your decision in a comment. Do not add features beyond this spec. Build incrementally and keep the code readable; this is a personal single-user app, not a Play Store release.

---

## 1. Product summary

A personal anti-theft "guard" for a phone left unattended in public (e.g. on a beach towel). The owner arms the guard and puts the phone down, screen off, locked by the normal system keyguard. If the phone is physically moved (picked up / carried away) and is **not unlocked by the owner within a short grace period**, the app plays a loud looping siren to attract attention and deter the thief.

The app does **not** replace the lock screen, does **not** implement its own PIN, and does **not** try to physically prevent theft. Its entire value is: *detect motion → give owner a few seconds to unlock → otherwise make noise*. This is a deterrent, not a hardened security product.

---

## 2. Core design decisions (already made — do not deviate)

1. **No custom PIN.** Disarming / silencing is done by the owner unlocking the phone with the **native** Samsung keyguard (PIN/fingerprint/face). The app detects a successful unlock via the `ACTION_USER_PRESENT` system broadcast. This means the app "borrows" the industrial-strength system lock instead of building a weak one.

2. **No lock-screen or AOD replacement.** These are closed on One UI and cannot be replaced by a third-party APK. The app never draws over or weakens the keyguard. The siren simply plays on top of whatever screen state exists.

3. **State machine drives everything** (see §4).

4. **Two-stage sensor strategy** to survive Doze and save battery (see §5). This is the most important technical detail — implement it carefully.

5. **Arming via a Quick Settings Tile** as the primary control, plus an ARM/DISARM action on the foreground-service notification. No need to open the app to arm/disarm during normal use.

---

## 3. State machine

Implement an explicit state machine inside the foreground service. States:

- **UNARMED** — guard off. No sensor monitoring. Foreground service may still run (to host the tile/notification) but is not watching for motion.
- **ARMED** — guard on. Registered for significant-motion detection (standby stage, see §5.1). Phone is expected to be lying still, screen off, locked by keyguard.
- **TRIGGERED** — significant motion detected. Woke up, acquired wake lock, switched to the full accelerometer, and started a **grace timer** (default 5 seconds). Optionally emit a short warning vibration/beep here.
- **ALARM** — grace timer expired with no owner unlock. Siren is playing.

### Transitions

| From | Event | To | Action |
|------|-------|-----|--------|
| UNARMED | User taps tile / notification ARM | ARMED | Register significant-motion, show armed state in tile & notification |
| ARMED | User taps tile / notification DISARM | UNARMED | Unregister sensors, release wake lock |
| ARMED | Significant motion fires | TRIGGERED | Acquire `PARTIAL_WAKE_LOCK`, register full accelerometer, start grace timer |
| TRIGGERED | `ACTION_USER_PRESENT` received (owner unlocked) | UNARMED | Cancel timer, release wake lock, unregister accelerometer. **Unlock = disarm.** |
| TRIGGERED | Grace timer expires | ALARM | Start siren |
| ALARM | `ACTION_USER_PRESENT` received | UNARMED | Stop siren, release wake lock, unregister sensors |
| ALARM | User taps tile / notification DISARM (edge case) | UNARMED | Stop siren, release everything |

**Important semantics:** a successful owner unlock (`ACTION_USER_PRESENT`) always means "the owner is here" → go to **UNARMED**, not back to ARMED. The owner must re-arm (tap the tile) before leaving the phone again. This avoids the phone re-arming while in active use.

Persist the current state so it survives a service restart (see §6.3).

---

## 4. Arming controls

### 4.1 Quick Settings Tile (primary)
- Implement a `TileService`.
- Tile label: "Guard". Two visual states: inactive (UNARMED) and active (ARMED/TRIGGERED/ALARM).
- Tapping the tile toggles ARM ↔ DISARM by sending a command to the foreground service (start service + action, or bound service — your choice).
- Tile must reflect the real current state, including when state changes from elsewhere (e.g. auto-disarm on unlock). Update the tile whenever state changes.

### 4.2 Foreground-service notification (secondary)
- The mandatory FGS notification includes an **ARM/DISARM** action button and shows current state text (e.g. "Guard armed", "ALARM", "Guard off").
- Use a low-importance, ongoing, non-dismissable notification for the armed/idle state. It's fine for the ALARM state to raise importance.

### 4.3 In-app screen (settings + status)
A single simple Activity:
- Large ARM/DISARM button reflecting state.
- **Sensitivity** control (see §5.3) — a slider.
- **Grace period** control — slider or number field, default 5 s, range ~2–15 s.
- Buttons/links to the two required system exemptions (see §7), each showing whether it's currently granted.
- Optional siren volume note / test-siren button (plays siren for 3 s so the owner can verify loudness; must be clearly a test and auto-stop).

---

## 5. Motion detection (critical section)

The phone's own scenario — lying still with screen off — is exactly what triggers Android **Doze**, which throttles background work and can withhold ordinary sensor delivery. Solve this with a two-stage approach that leans on a sensor designed for Doze rather than fighting Doze.

### 5.1 Standby stage (state = ARMED)
- Register a **one-shot** listener on `Sensor.TYPE_SIGNIFICANT_MOTION` via `TriggerEventListener` / `requestTriggerSensor`.
- Do **not** hold a CPU wake lock in this stage. Let the CPU sleep. `TYPE_SIGNIFICANT_MOTION` runs on the sensor hub and **wakes the device itself** when meaningful movement (translation, not jitter) occurs. This gives near-zero battery drain while armed and lying still.
- Because it is one-shot, you must **re-register** it every time it fires (or when returning to ARMED).
- If the device reports no `TYPE_SIGNIFICANT_MOTION` sensor (defensive check), fall back to a low-rate wake-up accelerometer with a wake lock, and log a warning.

### 5.2 Active stage (state = TRIGGERED)
- On significant-motion trigger: acquire a `PARTIAL_WAKE_LOCK`, transition to TRIGGERED, register the full accelerometer.
- Prefer `TYPE_LINEAR_ACCELERATION` (gravity already removed). If unavailable, use `TYPE_ACCELEROMETER` and subtract gravity (compute magnitude, subtract ~9.81, or high-pass filter).
- Use this stage to run the grace-timer window. You do **not** strictly need a second threshold check here (significant motion already fired), but you may use continued high acceleration to distinguish "picked up and carried" from a single bump if you want to reduce false alarms — keep this optional and simple.
- Sampling rate: `SENSOR_DELAY_GAME` or a fixed rate ~50 Hz is plenty. Consider requesting a small hardware **batch/maxReportLatency** where helpful, but real-time delivery during TRIGGERED is fine since the CPU is awake.

### 5.3 Sensitivity
- Expose a single **sensitivity** slider in settings mapping to detection strength. Since `TYPE_SIGNIFICANT_MOTION` isn't tunable, sensitivity should primarily tune the **active-stage** confirmation logic and the warning/grace behavior. Document clearly in code how the slider value maps to thresholds.
- Provide sane defaults so it works out of the box for the beach scenario ("grabbed and walked away" = strong, sustained motion).

---

## 6. Foreground service & lifecycle

### 6.1 Service
- A single **foreground service** hosts the state machine, sensor registration, wake lock, siren, and the USER_PRESENT receiver.
- Declare a `foregroundServiceType`. Choose the most appropriate type available for a device-monitoring/alarm use case on the target SDK and justify it in a comment; the app must still start the FGS correctly on Android 14+ (which requires a declared type and matching permission).

### 6.2 Wake lock
- Hold `PARTIAL_WAKE_LOCK` **only** during TRIGGERED and ALARM. Release it in ARMED (standby relies on the sensor hub) and in UNARMED.
- Always release wake locks on state exit and on service destroy. Never leak a wake lock.

### 6.3 Restart resilience
- Return `START_STICKY` from `onStartCommand`.
- Persist current state (e.g. DataStore/SharedPreferences). On service (re)creation, restore state: if it was ARMED, re-register significant motion; if it was ALARM… prefer restoring to ARMED rather than resuming a siren after a kill (choose and comment).
- As a safety net, schedule a periodic self-check with `AlarmManager.setExactAndAllowWhileIdle()` (or `WorkManager`) that restarts the service if it was killed while armed. Keep this lightweight; it is a backstop, not the primary mechanism.
- Register a `BOOT_COMPLETED` receiver so the guard can optionally survive a reboot **only if it was armed** (design choice — implement, default off).

---

## 7. Required system exemptions (onboarding)

The app must guide the user (the owner, on their own device) through granting these, and show current status for each in the in-app screen. All are grantable on a normal APK; none require root or reset.

1. **Battery optimization exemption** — request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` via the standard system dialog. Needed to reduce Doze/App-Standby throttling of the service.
2. **Samsung "Sleeping apps" exclusion** — cannot be set programmatically. Provide clear on-screen instructions and, if possible, an intent that deep-links toward Battery / Background usage settings, telling the user to ensure the app is **not** in "Sleeping" or "Deep sleeping" apps.
3. **Notifications permission** (Android 13+ `POST_NOTIFICATIONS`) — required for the FGS notification.
4. Any permission implied by the chosen `foregroundServiceType`.

Do **not** require `SYSTEM_ALERT_WINDOW`, device admin, or accessibility — the chosen design (variant A) does not need them.

---

## 8. Siren

- Play via `MediaPlayer` (or `SoundPool`) on **`AudioManager.STREAM_ALARM`**, looping. The alarm stream ignores silent/ring-mute.
- On entering ALARM: save current alarm-stream volume, force it to max with `setStreamVolume(STREAM_ALARM, max, 0)`, then start playback. Restore the saved volume when the siren stops.
- Request/handle audio focus appropriately for an alarm (transient exclusive is fine; the siren should keep playing regardless).
- Hold the wake lock for the whole ALARM duration so playback isn't suspended.
- Bundle a loud, attention-grabbing siren sound as a raw resource. Keep it loopable with no click at the seam.
- Stopping the siren (on USER_PRESENT or DISARM) must be immediate and must restore prior alarm volume.

---

## 9. Permissions (manifest, expected set)

- `FOREGROUND_SERVICE` and the specific `FOREGROUND_SERVICE_<TYPE>` permission for your chosen type.
- `POST_NOTIFICATIONS`
- `WAKE_LOCK`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `RECEIVE_BOOT_COMPLETED` (for the optional boot-survival feature)
- `HIGH_SAMPLING_RATE_SENSORS` only if you actually request >200 Hz (you shouldn't).

Add only what is used. Comment each non-obvious permission.

---

## 10. Acceptance criteria

The build is done when, on a Galaxy S23 (One UI), with the two exemptions granted:

1. Tapping the **Guard** Quick Settings tile arms the guard; the tile shows an active state; the FGS notification shows "armed".
2. With the phone armed, screen off, locked, and lying still on a table, battery drain over 20+ minutes is negligible (standby stage holds no wake lock).
3. Picking the phone up and walking a few steps triggers detection; if the phone is **not** unlocked within the grace period, a **loud looping siren** plays on the alarm stream at max volume, even if the phone was on silent/vibrate.
4. Unlocking the phone with the normal keyguard at any point during TRIGGERED or ALARM **immediately silences** the siren (if any) and returns the app to **UNARMED**; prior alarm volume is restored.
5. Tapping the tile or notification DISARM turns the guard off from any state.
6. The state shown by the tile, the notification, and the in-app screen are always consistent.
7. If the OS kills the service while armed, the `START_STICKY` + AlarmManager backstop restarts it and it returns to the armed standby stage within a reasonable time.
8. No leaked wake locks (verify wake lock is not held in UNARMED/ARMED).

---

## 11. Explicit non-goals

- Not preventing power-off, SIM removal, or forced shutdown.
- Not replacing the lock screen or AOD.
- No remote tracking, cloud, accounts, or network features (may be a future phase; out of scope now).
- No custom PIN entry.
- No guarantee of surviving multi-hour/day standby under aggressive OEM killing — the target scenario is "owner steps away for minutes to tens of minutes".

---

## 12. Suggested build order

1. Foreground service skeleton + state machine + persisted state.
2. Quick Settings tile + notification with ARM/DISARM, wired to the service.
3. `ACTION_USER_PRESENT` receiver → auto-disarm.
4. Two-stage sensor: significant-motion standby → accelerometer active stage → grace timer.
5. Siren on alarm stream with volume forcing/restoring.
6. In-app settings screen (sensitivity, grace period, exemption status/links).
7. Restart resilience (START_STICKY, AlarmManager backstop, optional boot survival).
8. Test-siren button and final tuning of defaults.