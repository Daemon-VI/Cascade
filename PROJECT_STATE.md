# Cascade — project state

_Last updated: 2026-08-19_

## What this is

**Cascade** is a native Android drag-and-drop automation builder — the iOS Shortcuts idea, done
for Android and pushed further where Android allows more. Built for the owner's phone
(Realme RMX3312, Android 15 / API 35, arm64). No root. Package `com.rishi.cascade`.

Toolchain matches AegisToolkit: AGP 9.3.1, Kotlin 2.2.10, Gradle 9.7, compileSdk 37,
targetSdk 35, minSdk 26. **AGP 9 ships Kotlin built in — applying `org.jetbrains.kotlin.android`
in `app/build.gradle.kts` breaks the build.**

## Status: v1.0 — built, installed and verified on device

154 actions across 14 categories, 18 trigger types. Verified by ADB screenshots and an injected
self-test flow on 2026-08-19.

### Verified on the phone

- **Editor and drag-and-drop** ✅ — reorder by long-press with a ghost card, dimmed source row and
  a live insertion line; drop persists. Dragging an action out of the library onto the canvas
  inserts it at the drop point (an `If` arrives as If / Otherwise / End If, auto-expanded).
- **Engine control flow** ✅ — Battery rescue read battery 94, evaluated `If 94 < 25` as false,
  jumped to Otherwise, skipped the true branch, finished in 3 executed steps.
- **Variables and templating** ✅ — Morning brief chained Current Date → HTTP (real wttr.in call,
  "Hyderabad, Telangana, IN: +25°C") → Battery → a Text step interpolating all three → spoken
  aloud by TTS → shown as a result.
- **Self-test flow (29 executed steps)** ✅ —
  - Run JavaScript returned a real JS array `[2,4,6,8]`; `{{doubled[2]}}` resolved to 6.
  - `Calculate` on `{{doubled[2]}} + max(3, 7) * 2` = 20.
  - Parse JSON + nested paths: `{{cfg.user.name}}` = Rithik, `{{cfg.user.tags[1]}}` = android.
  - Repeat ×3 with `{{i}}`; Repeat with Each over a sorted list with `{{n}}` / `{{f}}`.
  - Try / On Error caught `1 / 0` and continued.
  - SHA-256 of "cascade" matched `sha256sum` exactly; 42 km → 26.097590073968025 miles matched
    Python; weekday name correct.
- **Triggers UI** ✅ — trigger picker, per-trigger config and enable switches; the home-screen
  icon trigger produced the real system "Add to Home screen" dialog with the Cascade icon
  (cancelled deliberately rather than pinning uninvited).
- **Settings** ✅ — live permission status per row, each explaining which action needs it.

### v1.1 additions - "when an app opens" (2026-08-19 evening)

Three new pieces, built for the "open Instagram, take a selfie, set it as wallpaper" ask:

- **Take Photo** (`camera.photo`) - headless Camera2 stills, front or back, no preview. Adapted
  from AegisToolkit's intruder-capture path.
- **Set Wallpaper** (`dev.wallpaper`) - home, lock or both, with EXIF rotation applied.
- **When an app opens** trigger - an `AccessibilityService` (`AppWatcherService`) that receives
  nothing but window-state changes, fires only on a transition *into* the chosen package, and
  holds a 20 s per-package cooldown.

Verified: the flow ran from the app and did the whole chain - a 2.6 MB front-camera JPEG was
written and the home wallpaper actually changed. ✅

**The trigger now fires — verified end to end on 2026-08-19 at 21:00.** Opening a watched app
produced a fresh front-camera JPEG and a changed wallpaper, entirely in the background, with
`history.log` recording `ok  Instagram selfie  3 steps`. Two findings got it there:

- **ColorOS will not let a sideloaded app hold an accessibility service.** The toggle silently
  fails: `appops` showed `ACCESS_RESTRICTED_SETTINGS: rejectTime=+3m40s ago` and
  `dumpsys accessibility` kept reporting `Bound services:{}`. App info has no "Allow restricted
  settings" entry on this build, and `appops set` is blocked from ADB (no MANAGE_APP_OPS_MODES),
  exactly like `pm grant`. This is not fixable from the app side.
- So the trigger gained a **second backend**: `AppWatchService` polls `UsageStatsManager`
  event stream every 1.5 s while the screen is on, behind a minimum-importance notification, and
  only runs when a flow actually wants it and accessibility is not already doing the job.
  Usage access is *not* subject to the restricted-setting block, so it can be granted normally.
- **Camera from a background-started foreground service is allowed here.** This was the open
  question; `RunnerService` declaring `camera` in its foreground-service type and claiming it only
  when the permission is held turned out to be enough on Android 15 / ColorOS.

Testing note: Instagram itself sits behind ColorOS app lock, so the automated test used YouTube as
the watched package and was then set back. The package is only a filter - the mechanism is identical.

**Earlier blockers, now resolved:**
1. ColorOS ignores `settings put secure enabled_accessibility_services` written over ADB -
   `dumpsys accessibility` still reported `Bound services:{}`. The switch has to be flipped by
   hand in Settings → Accessibility → Installed services. The Settings screen now reports this
   honestly: it checks whether the service is actually *connected*, not whether the OS lists it
   as enabled.
2. Instagram sits behind ColorOS app lock on this phone, so launching it lands on
   `AppUnlockPasswordActivity` rather than Instagram itself.

Still genuinely open once accessibility is on: whether Android grants the **camera to a
background-started foreground service**. `RunnerService` now declares
`specialUse|camera|microphone` and claims the camera type only when the permission is held, which
is the documented route, but Android 11+ while-in-use rules may still refuse it. If they do, the
fallback is the home-screen-icon trigger, which runs as a real activity.

### Not yet exercised on device

Background trigger *firing* (time / boot / charger / Wi-Fi / notification) is implemented and
wired, but has not been observed firing in the wild — it needs elapsed time and, on ColorOS,
battery-optimisation exemption. Same for the Quick Settings tile, which needs the user to drag
the tile into the shade. Media Record Audio, SMS/call/contacts and Location paths are written but
gated behind permissions that were not granted during testing.

## Bugs found and fixed during device testing

1. **`ExceptionInInitializerError` on every run.** Android's ICU regex engine rejects the
   unescaped `}}` in `\{\{\s*([^{}]+?)\s*}}`. Interpolation compiled fine on the JVM and blew up
   on the phone. Now `\\}\\}`.
2. **Library-to-canvas drag never inserted.** `onDragStart` collapsed the palette, which unmounted
   the row owning the `pointerInput` and cancelled the gesture. The palette now collapses on drop.
3. Palette listed `End If` / `Otherwise` / `End Repeat` as if they were addable on their own.
4. Collapsed step summaries went stale after editing a param (params are plain maps, not snapshot
   state) — fixed with a revision counter.

### v1.2 - catalogue expansion (2026-08-19)

37 actions added to close gaps, taking the library from 117 to **154**. Verified in one run on
device, each returning a correct value (HMAC-SHA256 matched Python byte for byte, the light sensor
read 35.6 lux, the carrier came back as JIO):

- **Text**: Extract from Text (emails, links, OTP codes, hashtags, currency), Sort Lines,
  Strip HTML.
- **Maths and dates**: Percentage, Convert Number Base, Format Duration, Is Time Between (handles
  windows crossing midnight), Wait Until Time.
- **Data**: Parse CSV, Number Range, Set Dictionary Value, Merge Dictionaries, Sort List by Field,
  Sign with HMAC, Decode JWT.
- **Device**: Read Sensor (light, proximity, pressure, humidity, acceleration, steps), Mobile
  Network Info, Screen Orientation, Keep Screen Awake, Lock the Screen (device admin), Read System
  Setting.
- **Apps**: Close Background App, App Permissions.
- **Files**: Create Zip, Extract Zip, Hash a File, Find Files, Share a File (via FileProvider).
- **Media**: Save to Gallery, Edit Image (resize/rotate/flip/grayscale/compress), Set Ringtone.
- **Network**: Upload File (multipart), Send to Socket (TCP/UDP), Wake a Computer (wake-on-LAN).
- **Location**: Open Map.
- **Comms**: Read Messages (including "latest code" for OTP flows), Recent Calls.

New plumbing: a `FileProvider` so files can be shared out, and `LockAdmin`, a device-admin receiver
that asks for `force-lock` and nothing else.

## Known rough edges

- A flow open in the editor writes on every keystroke; fine at this size, but there is no undo.
- Dropping a step *inside* a block it does not belong to is allowed; the engine tolerates
  unbalanced blocks (an unmatched opener runs to the end of the flow) but the editor does not warn.
- `Get Clipboard` only works while Cascade is on screen — an Android 10+ restriction, documented
  in the action description.
- Flows started from a background trigger cannot launch other apps (Android background-activity
  limits). Use the home-screen icon trigger for flows that open apps.

## Files

```
C:\Users\Rishi\Cascade
  app/src/main/java/com/rishi/cascade/{model,engine,actions,store,trigger,ui}
  README.md ARCHITECTURE.md PROJECT_STATE.md ROADMAP.md
  shots/            device screenshots from the 2026-08-19 verification pass
```

Git repository initialised 2026-08-19; single commit on `main`, authored as
Rithik Krishna <317035893+Daemon-VI@users.noreply.github.com>. No remote yet.
