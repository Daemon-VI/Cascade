# Cascade

A native Android automation builder in the spirit of iOS Shortcuts: you drag actions onto a
canvas, wire them together with variables, and the flow runs on your phone — by tap, by a
home-screen icon, from a Quick Settings tile, or on a trigger.

Built for a Realme RMX3312 (Android 15 / API 35), no root required.
Package `com.rishi.cascade`, single activity, Jetpack Compose, zero third-party runtime deps
beyond AndroidX/Compose.

## What it does

- **154 actions** across 14 categories — control flow, interaction, text, maths, dates, data,
  device, apps and intents, network, files, media, location, communication, scripting.
- **18 trigger types** — Quick Settings tile, home-screen icon, time of day, interval, boot,
  charger in/out, low battery, headphones, screen on/off, unlock, Wi-Fi join/leave, notification
  arrival, airplane mode.
- **Real drag and drop** — long-press a step to move it (whole `If` / loop blocks travel with
  their contents), or press and drag an action straight out of the library onto the canvas, with
  a live insertion line showing where it lands.
- **Variables everywhere** — every step can name its result, and any text field can reference it
  with `{{name}}`, including paths into JSON: `{{weather.current.temp_c}}`, `{{list[0].id}}`.
- **Nested control flow** — If / Otherwise, Repeat, Repeat with Each, While, Try / On Error,
  Break, Continue, Stop, and Run Flow for calling one flow from another.
- **Escape hatches** — a full intent builder (any action, component, extras, activity/broadcast/
  service) and a real JavaScript step for whatever the action library does not cover.

## Things iOS Shortcuts cannot do that this does

| | |
|---|---|
| **Send Intent** | Fire any Android intent at any app, with typed extras. Android's automation surface is far wider than iOS's, and this exposes all of it. |
| **Run JavaScript** | Arbitrary JS with every flow variable in scope, returning a value into the flow. |
| **Run Shell Command** | `sh -c` as the app user (no root) — `getprop`, `settings get`, `ping`, `pm list`. |
| **HTTP Request** | Any method, headers, query, JSON/form/raw bodies, JSON auto-parsed into a navigable value. |
| **Triggers** | Notification arrival, Wi-Fi SSID, screen state, boot and *app launch* are ordinary triggers here. |
| **Reach** | Raw TCP/UDP sockets, wake-on-LAN, HMAC signing, zip archives, sensors, silent camera, wallpaper, ringtone and the settings tables - all without root. |

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 9.3.1, Kotlin 2.2.10 (AGP's built-in Kotlin — do **not** apply
`org.jetbrains.kotlin.android` in `app/build.gradle.kts`), Gradle 9.7, compileSdk 37,
targetSdk 35, minSdk 26, JDK 17 bytecode.

## First run

Open the app and tap **Add 6 example flows** for a working set: Morning brief, Battery rescue,
Focus mode, Where am I, Quick note, Is my site up. Then **Settings** to grant only the
permissions your flows actually need — every row says which action or trigger needs it.

On Realme/ColorOS, also grant **Ignore battery optimisation**, or background triggers get frozen.

## Where things live

Flows are one JSON file each in the app's private `files/flows/`. Nothing leaves the phone
unless one of your own steps sends it somewhere.

- `USAGE.md` - how to build flows, with three worked examples
- `ARCHITECTURE.md` - internals
- `PROJECT_STATE.md` - what is verified, what is not
- `ROADMAP.md` - what is next
