# Guard — Anti-Theft Alarm (Android)

A personal anti-theft "guard" for a phone left unattended in public. Arm it, put
the phone down (screen off, locked by the normal keyguard). If the phone is moved
and **not unlocked by the owner within a short grace period**, it plays a loud
looping siren on the alarm stream. Built to the spec in `requirements.md`.

Kotlin + Jetpack (Compose), plain APK — no root, no device owner, no MDM.

## Build

The Android SDK, Gradle wrapper, and a generated siren are already set up in this
repo. From the project root:

```bash
export ANDROID_HOME=$HOME/android-sdk   # SDK was installed here
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Install on a connected device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
(or open the project in Android Studio and Run).

- `compileSdk`/`targetSdk` = 34 (Android 14), `minSdk` = 28. Target device: Galaxy S23 (One UI).
- Toolchain: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, JDK 17+.

## How it works

### State machine (in `GuardService`)
`UNARMED → ARMED → TRIGGERED → ALARM`, exactly as in spec §3. A successful owner
unlock (`ACTION_USER_PRESENT`) from any active state means **disarm** (→ UNARMED);
the owner must re-arm before leaving the phone again. State is persisted in
`SharedPreferences` so it survives a service restart.

### Motion detection (the important bit, spec §5)
The spec's default was `TYPE_SIGNIFICANT_MOTION` (zero battery, no wake lock). In
practice that sensor is slow and insensitive to a quick pickup on many phones and
unreliable with the screen off, so the **default detection is now the reliable
path**:

- **Standby (ARMED):** hold a `PARTIAL_WAKE_LOCK` and run a **wake-up
  accelerometer** (`SENSOR_DELAY_UI`). Once the phone has been set down and still
  for ~0.7 s, its resting orientation is locked in as a baseline. It then triggers
  the instant it detects either a **tilt** away from that baseline (lifted off a
  surface) or a **jolt** (grabbed). This fires immediately and keeps working with
  the screen off. Cost: some battery while armed — acceptable for the target
  "minutes to tens of minutes" scenario.
- **Battery-saver mode (optional, off by default):** the original one-shot
  `TYPE_SIGNIFICANT_MOTION`, no wake lock. A settings toggle for users who want
  battery savings and whose device cooperates.
- **Active (TRIGGERED):** acquire/keep the wake lock, run the grace timer, and on
  expiry sound the alarm unless the owner unlocked. (No second-guessing — the
  earlier "re-arm if motion wasn't sustained" heuristic was removed because it
  could swallow real alarms.)

### Sensitivity
A single slider (0–100) tunes the detection thresholds: higher = a smaller tilt or
gentler grab triggers. Mappings are documented in `GuardPrefs.sensitivityToTiltDeg`
and `sensitivityToJoltMs2`. Default 70 (~13° tilt / ~1.5 m/s² jolt).

### Siren (spec §8)
`SirenPlayer` plays `res/raw/siren.wav` looped on `STREAM_ALARM` with
`USAGE_ALARM` audio attributes, so it sounds through silent/vibrate. On alarm it
saves and forces the alarm volume to max, and restores it on stop. The siren clip
is a phase-continuous "wail" generated to loop with no click at the seam.

### Controls (always consistent — single source of truth is the persisted state)
- **Quick Settings tile** "Guard" (primary): toggles arm/disarm, reflects state.
- **Foreground-service notification** (secondary): ongoing, low importance while
  armed/idle (raised for ALARM), with an ARM/DISARM action.
- **In-app screen** (Compose): big ARM/DISARM button, sensitivity + grace sliders,
  exemption status/links, boot-survival toggle, and a 3-second test-siren button.

### Resilience (spec §6.3)
`START_STICKY`, plus an AlarmManager watchdog (`WatchdogReceiver`) that pokes the
service ~every 15 min while armed. Optional boot survival (`BootReceiver`, default
**off**) re-arms after a reboot only if it was armed. If killed mid-ALARM, it
restores to ARMED rather than resurrecting a siren.

## Notable implementation decisions
- **Service only runs while active.** On reaching UNARMED the service stops itself,
  so there is no permanent notification and zero cost while off. The tile reads
  state straight from prefs, so it still works with the service stopped. (Spec
  allows the FGS to run while unarmed; we choose not to.)
- **`foregroundServiceType="specialUse"`** — there is no built-in FGS type for a
  device-monitoring/anti-theft alarm; `specialUse` is the correct catch-all on
  Android 14, declared with the required justification `<property>`.
- **Watchdog uses `setAndAllowWhileIdle` (inexact)** to avoid needing an
  exact-alarm permission; a few minutes of drift is fine for a backstop, and the
  battery-optimization exemption (which the app guides the user to grant) lets the
  broadcast start the FGS from the background.
- **Wake lock** is held while armed in the default reliable mode (and through
  TRIGGERED/ALARM), released on every exit and on destroy, with a 60-minute safety
  timeout so it can never leak. In battery-saver mode no wake lock is held while
  armed.

## Required setup (guided in-app, spec §7)
1. Battery optimization exemption (system dialog).
2. Notifications permission (Android 13+).
3. Exclude from Samsung "Sleeping apps" (manual — cannot be set programmatically;
   the app deep-links to its settings with instructions).

## Non-goals
No lock-screen/AOD replacement, no custom PIN, no network/cloud/tracking, no
defense against power-off or SIM removal. This is a deterrent, not a hardened
security product.
