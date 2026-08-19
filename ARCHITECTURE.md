# Cascade — architecture

_Last updated: 2026-08-19 (v1.0)_

Single Gradle module (`:app`), package `com.rishi.cascade`. Everything is Kotlin + Compose with
no third-party runtime libraries: `org.json` for storage, `HttpURLConnection` for network, the
platform `WebView` as the JavaScript engine.

```
model/     what a flow is
engine/    how a flow runs
actions/   what a flow can do          (115 actions)
store/     where flows live            (+ starter templates)
trigger/   what makes a flow run       (services, receivers, alarms)
ui/        how a flow is built         (Compose, drag and drop)
```

## model — Model.kt, ActionDef.kt

`Flow` = id, name, icon, colour, an **ordered flat list of `Step`**, and a list of `Trigger`.

`Step` = an action id plus `params: MutableMap<String, String>`, an optional `outputName`, and an
enabled flag. **Every parameter is stored as a string**, whatever its declared type. That single
decision keeps serialisation, the editor and variable interpolation uniform: any field can hold
`{{a_variable}}` regardless of whether the action wants a number, a boolean or a file path.

Nesting is *not* a tree. An `If` is three steps in the flat list — `flow.if`, `flow.else`,
`flow.endif` — exactly as iOS Shortcuts models it. This makes drag-reordering trivial and
serialisation flat; the cost is that block matching happens at run time (`Blocks.pair`).

`ActionDef` is the declarative description of an action: id, title, category, icon name, a
`summary` template (`"Wait %seconds%s"`) for the collapsed card, its `ParamSpec` list, its
`BlockKind` (NONE/BEGIN/MIDDLE/END), what it outputs, which permissions it needs, and search
keywords. `ParamSpec.showIf` lets a field appear only for certain values of another field, which
is how one action covers "Add item / Sort / Shuffle / Remove duplicates" without four actions.

## engine — Runtime.kt, Interpolator.kt, Expr.kt, Engine.kt

Runtime values are plain `Any?`: `String`, `Double`, `Boolean`, `List<Any?>`, `JSONObject`,
`FileRef`, `null`. `V` coerces between them (`V.text`, `V.num`, `V.bool`, `V.list`), so a step
that wants a number can be handed the text output of the step above.

`Interpolator` resolves `{{name}}`, `{{name.key}}`, `{{name[0]}}` and combinations, against the
run's variables. `Env.value(key)` returns the *underlying object* when a parameter is exactly one
`{{ref}}` — so a list stays a list instead of collapsing to text — while `Env.str(key)` always
interpolates to text.

> Gotcha found on device: Android's ICU regex engine rejects an unescaped `}` in a pattern.
> The token regex must be `\\{\\{\\s*([^{}]+?)\\s*\\}\\}`, not `...}}`.

`Expr` is a small recursive-descent evaluator (arithmetic, comparison, logic, ~30 functions)
behind the Calculate action and `expression is true` conditions.

`Engine.run()` walks the flat step list with a **program counter** rather than recursion:

- `flow.if` evaluates its condition and jumps to `else+1` or `end+1`.
- Loops keep a `LoopFrame` keyed by their opening index; the matching `end` step jumps back.
- `flow.try` pushes a frame; any exception thrown by a step unwinds to the innermost `catch`
  with the message in `{{error}}`.
- `Run Flow` builds a nested `Engine` with `depth + 1`, capped at 8.
- A 200 000-step budget stops runaway flows.

Everything a running flow needs from a human goes through the `FlowIO` interface — `ask`,
`choose`, `alert`, `showResult`, `log`, `progress`. Two implementations: `UiFlowIO` (suspends on
a `CompletableDeferred` and shows a Compose dialog) and `HeadlessIO` (background runs: turns
prompts into notifications, and refuses to guess an answer unless the step has a default).

## actions — one file per category

Each action is an `ActionDef` plus a `suspend (Env) -> Any?` body, registered in `Registry`.
Adding an action is one entry in one list; the editor, palette, search and variable picker all
derive from `ActionDef` automatically.

Control-flow actions are declared here for the editor but never executed — `Engine` intercepts
them by id before generic dispatch.

Notable implementations:
- **Run JavaScript** creates a headless `WebView` on the main looper, injects every variable as a
  `vars` object, and marshals the return value back as JSON.
- **HTTP Request** parses a JSON response into `JSONObject`/`JSONArray` so `{{result.field}}`
  works immediately.
- **Write File / Download** use `MediaStore` for Downloads, so no storage permission is needed on
  Android 10+.
- Device actions that need special access (`WRITE_SETTINGS`, DND policy, usage stats) check first
  and throw a message telling the user which Settings row to grant.

## store

`FlowStore` is a process singleton over `filesDir/flows/<id>.json`, exposing a
`StateFlow<List<Flow>>` the home screen collects. Files are small, so everything stays in memory.
`Templates` builds the six starter flows programmatically.

## trigger

- `TriggerEngine.fire(context, type, extras)` matches every enabled trigger of that type and
  starts `RunnerService` per flow; extras (SSID, notification title/text, battery level) land in
  the run as variables.
- `RunnerService` is a foreground service (`specialUse`) running flows headlessly with
  `HeadlessIO`, recording each run in `History`.
- `TriggerReceiver` handles boot, power, headset, airplane and scheduled alarms.
  `ScreenReceiver` is registered in code because screen/unlock events are not delivered to
  manifest receivers.
- `Scheduler` arms `AlarmManager` for time and interval triggers, re-arming after each firing and
  after boot, exact when the OS allows it.
- `NotificationWatcher` (NotificationListenerService) powers the notification trigger and lets
  Now Playing read media sessions. `CascadeTileService` is the Quick Settings tile.
  `RunFlowActivity` is the invisible activity behind pinned shortcuts — an activity, not a
  service, so flows launched that way are still allowed to open other apps.

## ui

- `Drag.kt` — one shared `DragState` for the whole editor, holding the payload (a new action from
  the library, or an existing step plus the size of the block it carries), the pointer position in
  root coordinates, and every row's bounds. `dragSource()` is a `@Composable` that returns the
  modifier; the position state must be `remember`ed, because a plain local would go stale as soon
  as the row recomposes mid-drag.

  > Gotcha found on device: never collapse or otherwise unmount the palette in `onDragStart`.
  > Removing the composable that owns the `pointerInput` cancels the gesture instantly. The
  > palette now collapses in `onDrop`, after the gesture has finished.

- `EditorScreen.kt` — the canvas. Indentation comes from `Blocks.indents`. Dragging a block
  opener moves its whole block. Deleting a block opener removes only its markers and keeps the
  contents. Step params are plain maps, not snapshot state, so a `revision` counter is bumped on
  every edit to refresh the collapsed summaries.
- `ActionPalette.kt` — searchable, category-filtered library in the same coordinate space as the
  canvas, which is what makes library-to-canvas dragging possible. Block closers and
  Otherwise/On Error are hidden; they arrive with their opener.
- `ParamFields.kt` — one editor per `ParamType`, each text field carrying a **Variable** chip that
  inserts `{{name}}` at the cursor from the list of variables available at that point in the flow.
- `RunSheet.kt` — the run dialog: live step log, results, errors with the cause chain, and the
  nested dialogs for Ask / Choose / Alert / Show Result.
- `Icons.kt` — string icon names to Compose vectors via an explicit `when`, deliberately: an
  unknown name is a compile error, not a silent blank at run time.
