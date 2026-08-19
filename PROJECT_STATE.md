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

115 actions across 14 categories, 17 trigger types. Verified by ADB screenshots and an injected
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
