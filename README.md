# Guard — Anti-Theft Alarm (Android)

A personal anti-theft "guard" for a phone left unattended in public. Arm it, put
the phone down (screen off, locked by the normal keyguard). If the phone is moved
and **not unlocked by the owner within a short grace period**, it blares a loud
looping siren, vibrates, strobes the flashlight, and flashes the screen — until
someone who can actually unlock the phone silences it.

Kotlin, plain framework Views, ordinary APK — **no root, no device owner, no MDM,
no network/cloud, and zero third-party dependencies** (only the Android framework
and the Kotlin stdlib; JUnit is test-only and never packaged). The release APK is
**~95 KB**.

## Build

```bash
export ANDROID_HOME=$HOME/android-sdk   # SDK location
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Install on a connected
device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

- `compileSdk`/`targetSdk` = 34 (Android 14), `minSdk` = 28. Target device: Galaxy S23 (One UI).
- Toolchain: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, JDK 17+.

### Tests

```bash
./gradlew :app:testDebugUnitTest        # report: app/build/reports/tests/testDebugUnitTest/
```

Plain JVM JUnit — no emulator, no device, no Robolectric. This is possible
because the logic worth testing was deliberately kept free of Android types:

- **`WatchGate`** — every "should we watch / may we alarm / may we disarm" rule as
  a pure function. These are the decisions where a mistake is a security hole
  rather than a bug: the disarm-while-locked refusal, the power-bank rule (a
  charger connected after the phone was locked never pauses the alarm), and the
  call / already-unlocked suppressions.
- **`MotionAnalyzer`** — baselining and tilt/jolt detection, driven by synthetic
  accelerometer samples: a resting phone and small noise stay quiet, a tilt or a
  snatch fires, a single stray sample is debounced away, and the sensitivity
  slider provably changes what counts as a pickup.
- **`GuardState`** / the sensitivity mappings — the persisted-name round-trip with
  its fallback, and the slider-to-threshold curves.

JUnit is a `testImplementation` dependency: it runs on the JVM and is never
packaged into the APK, which still ships zero third-party code.

### Release signing

Release builds are signed from `keystore.properties` + a keystore file, both of
which are **gitignored and not in this repo**. A fresh clone therefore builds
*debug* out of the box; producing a signed release requires your own keystore
(see `app/build.gradle.kts`). Debug and release use different keys, so switching
between them on a device requires an uninstall.

## How it works

### State machine (`GuardService`)
`UNARMED → ARMED → TRIGGERED → ALARM`. `ARMED` has three sub-modes:

- **waiting** — just armed; does nothing until the phone is locked (screen off),
  so placing and locking the phone never self-triggers;
- **watching** — actively monitoring the accelerometer;
- **paused (charging)** — see *Pause while charging* below.

State is the single source of truth, persisted in `SharedPreferences`, so the
tile, notification and in-app screen always agree and it survives a restart.

By default an owner unlock **keeps the guard armed** (stops any alarm, returns to
the waiting phase, resumes watching when the phone is next locked). A setting can
switch this to "unlock fully disarms".

### Motion detection
Default is the reliable path: hold a `PARTIAL_WAKE_LOCK` and run a **wake-up
accelerometer**. Once the phone has been set down and still for ~0.7 s, its
resting orientation is locked in as a baseline; it then fires the instant it
detects a **tilt** off that baseline (lifted) or a **jolt** (grabbed) — and keeps
working with the screen off. An optional **battery-saver mode** uses the one-shot
`TYPE_SIGNIFICANT_MOTION` sensor with no wake lock (slower, less reliable).

The maths itself lives in `MotionAnalyzer` — a pure class with no Android types,
so it is directly unit-tested (see [Tests](#tests)); `GuardService` only owns the
sensor registration and decides what a detection *means*.

A single **sensitivity** slider (0–100) tunes the tilt/jolt thresholds
(`GuardPrefs.sensitivityToTiltDeg` / `sensitivityToJoltMs2`). The settings screen
shows a **live motion meter** that fills as you move the phone and turns red past
the current trigger threshold, so you can calibrate by feel.

### Pause while charging (default on)
While armed and plugged in, the motion alarm is paused; **unplugging instantly
activates watching** (the café scenario: charging in public, a thief unplugs and
walks off). This is tamper-proof: only a charger connected while the owner was
present (before the phone was locked) may pause — a charger connected *after* the
phone is locked (a thief's power bank) never pauses. The accelerometer keeps
running through the pause so unplugging starts detection with no blind window.

### Alarm output
`SirenPlayer` **synthesizes the siren at runtime** with `AudioTrack` (no audio
asset ships): a seamless loop of five cycling segments (wail, hi-lo two-tone,
whoop, screech, and a harsh police "yelp"), soft-clipped for loudness, played on
`STREAM_ALARM`/`USAGE_ALARM` so it sounds through silent/vibrate. On alarm it
forces the alarm volume to max (saved/restored, and persisted so a crash can't
leave it pinned). Alongside the siren: continuous max vibration, a **flashlight
strobe**, and a full-screen white/black **`FlashActivity`** shown over the lock
screen via a full-screen intent. The flash screen has *no* silence button — only a
real keyguard unlock stops the alarm.

### Voice announcement
Optionally, `Announcer` speaks a warning over the siren using the **on-device
TextToSpeech engine** — no audio asset, no network, no permission (only a
`<queries>` entry so the app may bind the TTS service on Android 11+). The phrase
and the language are configurable in the app; picking a language fills in a
sensible default sentence for it unless a custom phrase was typed. The phrase
repeats with a short gap, is spoken slightly faster than normal for urgency, and
the siren ducks while it plays so it stays intelligible. If no engine or voice
data is available the app stays silent and the siren plays alone.

### Silencing is unlock-gated (anti-theft guarantee)
Disarming always requires unlocking the phone, so a thief can't silence it:

- the notification's Disarm action uses `setAuthenticationRequired(true)` (API 31+);
- the Quick Settings tile disarms via `unlockAndRun`;
- the service refuses an external disarm while the keyguard is securely locked
  (defense-in-depth for older versions / stray intents).

### False-alarm guards
The alarm is suppressed, without any extra permission, when it would clearly be a
false positive:

- **Phone calls** — if the phone is ringing or in a call (checked via the global
  audio mode), grabbing it to answer never sounds the alarm.
- **Already unlocked** — the live keyguard state is the source of truth for "owner
  is present": if the phone is actually unlocked, motion is ignored. This is a
  backstop for the rare case where `ACTION_USER_PRESENT` is not delivered, which
  could otherwise leave the guard watching during normal use.

### Controls & UI
- **Quick Settings tile** "Guard" — primary arm/disarm, reflects state instantly.
- **Foreground-service notification** — ongoing status with a Disarm action.
- **In-app screen** — plain framework Views (no Compose), styled from a design
  system with dark/light palettes (auto by system theme), per-state status colors,
  and pulse animations. Big ARM/DISARM, behavior/effects toggles, sensitivity (with
  the live meter) and grace sliders, guided setup, and an event log. The **Test
  siren** button toggles: tap once to preview, tap again to stop.

### Resilience
`START_STICKY`, plus an inexact `AlarmManager` watchdog (`WatchdogReceiver`) that
pokes the service ~every 15 min while armed. Optional boot survival
(`BootReceiver`, default **off**) re-arms after a reboot only if it was armed. The
service only runs while active — on reaching UNARMED it stops itself, so there is
zero cost while off. Uses `foregroundServiceType="specialUse"` (there is no
built-in FGS type for an anti-theft alarm).

## Required setup (guided in-app)
1. Battery optimization exemption (system dialog).
2. Notifications permission (Android 13+).
3. Exclude from Samsung "Sleeping apps" (manual — cannot be set programmatically;
   the app deep-links to its settings with instructions).

## Non-goals
No lock-screen/AOD replacement, no custom PIN, no network/cloud/tracking, no
defense against power-off or SIM removal. This is a deterrent, not a hardened
security product.
