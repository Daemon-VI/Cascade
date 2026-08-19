# Using Cascade

## The mental model

A **flow** is an ordered list of **actions**. They run top to bottom, and each one hands its
result to the next. That handoff is the whole idea:

```
Ask for Input   ->  "revision notes"
Append to Log   ->  writes it to notes.txt
Show Toast      ->  "Saved"
```

The result of the step above is always available as `{{last}}`. When you need a value from
further back, give that step a name in its **Save result as** box and refer to it by that name
anywhere: `{{note}}`, `{{battery}}`, `{{weather}}`.

That is the entire language. Everything else is detail.

## Building a flow

1. **New flow** (bottom right on the home screen). It opens empty with the action library
   already up.
2. **Add actions.** Two ways, and the difference matters:
   - **Tap** an action in the library — it is appended to the *end* of the flow.
   - **Press and hold, then drag** it onto the canvas — a purple line shows where it will land,
     so you can drop it *between* two existing steps.
3. **Configure it.** Tap a step card to expand it. Its parameters appear inline; they save as you
   type. Tap it again to collapse.
4. **Reorder.** Press and hold a step card, drag it, drop it. Dragging an `If` or a loop carries
   everything inside it along.
5. **Run it** with ▶ in the top bar.

Rename the flow by tapping its title. The ⋮ menu holds **Triggers**, **Icon and colour**,
**Rename** and **Delete flow**.

## Variables

Every text field has a **Variable** chip above it. Tap it and you get the list of variables that
exist *at that point in the flow* — outputs of earlier steps, loop counters, the caught error
inside a Try. Picking one inserts `{{name}}` at your cursor.

You can also type them by hand, and you can dig into structured values:

| You write | You get |
|---|---|
| `{{last}}` | result of the step directly above |
| `{{weather}}` | a step you named "weather" |
| `{{cfg.user.name}}` | a field inside a parsed JSON object |
| `{{items[0]}}` | first item of a list (`[-1]` is the last) |
| `{{items.count}}` | how many items |
| `{{input}}` | whatever started the flow (a trigger, or Run Flow) |

Text mixes freely with variables: `Battery is {{level}}% and it is {{weekday}}`.

## Control flow

Adding **If** drops three steps in at once — `If`, `Otherwise`, `End If` — and everything you put
between them is the branch. Same for **Repeat**, **Repeat with Each**, **While** and **Try**.
Indentation on the canvas shows you the nesting.

- **If** compares a value against another with sixteen conditions (is, contains, matches regex,
  greater than, is empty, between, is in list...). Pick `expression is true` for anything
  arithmetic: `{{battery}} < 20 && {{hour}} > 18`.
- **Repeat with Each** walks a list, giving you `{{item}}` and `{{index}}` each pass (rename those
  if you nest loops).
- **Try / On Error** keeps the flow alive when a step fails — the message lands in `{{error}}`.
  Wrap anything that touches the network in it.
- **Stop If** is the early exit: "if there is nothing to do, stop here".
- Deleting an `If` removes only the three markers and **keeps its contents**, so you can unwrap a
  branch without losing work.

## Debugging

The run sheet is the debugger. Every step logs its title and a preview of what it produced, so
you can see exactly where a value stopped being what you expected. Two actions exist purely for
this:

- **Add to Log** — prints anything you like into the run log.
- **Show Result** — stops and shows a value, then continues when you tap Continue.

Disable a step (expand it → **Disable**) to skip it without deleting it. That is the fastest way
to bisect a flow that misbehaves.

## Triggers

⋮ → **Triggers** → **Add trigger**. A flow can have several.

| Trigger | Needs |
|---|---|
| Quick Settings tile | You drag the Cascade tile into your shade once |
| Home screen icon | Tap "Add icon to home screen" |
| At a time of day / Every so often | Exact alarms + battery-optimisation exemption |
| Boot, charger in/out, low battery, headphones | Nothing |
| Screen on/off, unlocked | App process alive (grant notification access to keep it up) |
| Joins a Wi-Fi network | Location permission to read the network name |
| A notification arrives | Notification access |

Three things about background runs that will bite you otherwise:

1. **They cannot ask you anything.** An `Ask for Input` step with no default will fail. Give it a
   default and it uses that.
2. **They cannot open other apps.** Android blocks background activity starts. If your flow opens
   an app, trigger it from the **home screen icon** instead, which runs as a real activity.
3. **ColorOS freezes them.** Settings → **Ignore battery optimisation** is not optional on a
   Realme.

Background runs are recorded in Settings → **Background run history**.

## Permissions

Settings lists every permission with the action or trigger that needs it, and a live ✓/✗. Grant
only what your flows use. Nothing is requested at install time.

The ones people miss: **Modify system settings** (brightness, auto-rotate, screen timeout),
**Do Not Disturb access** (silent ringer and the DND action), **Notification access** (the
notification trigger and Now Playing).

---

# Worked example 1 — Random DSA topic (4 actions, 2 minutes)

Teaches lists and variables. Picks a random topic to drill and copies it to your clipboard.

1. **Text** — paste your topics, one per line:
   ```
   Two pointers
   Sliding window
   Binary search
   Monotonic stack
   Union find
   Topological sort
   ```
   Set **Save result as** to `topics`.
2. **Split Text** — Text: `{{topics}}`, Split by: **New line**. Save result as `list`.
3. **Get Item from List** — List: `{{list}}`, Get: **Random item**. Save result as `pick`.
4. **Set Clipboard** — Text: `{{pick}}`.
5. **Show Result** — Value: `Today: {{pick}}`.

Run it. Then ⋮ → Triggers → **Home screen icon** so it is one tap from your launcher.

# Worked example 2 — Night wind-down (trigger + condition)

Teaches triggers and branching.

1. **Get Date Part** — Part: **Hour**. Save result as `hour`.
2. **Stop If** — Value `{{hour}}`, condition **less than**, Compare with `21`.
   (Guard clause: if it somehow runs in the morning, do nothing.)
3. **Set Do Not Disturb** — Filter: **On**.
4. **Set Brightness** — Automatic brightness: **Turn off**, Level: `15`.
5. **Set Ringer Mode** — Mode: **Vibrate**.
6. **Post Notification** — Title `Wind down`, Message `DND on, screen dimmed. Sleep.`

Then ⋮ → Triggers → **At a time of day** → `22:30`. Grant **Modify system settings** and
**Do Not Disturb access** in Settings, plus battery-optimisation exemption, or it will not fire.

# Worked example 3 — anything the actions do not cover

Two escape hatches, for when there is no dedicated action:

- **Send Intent** drives any app that accepts an intent. Action `android.intent.action.VIEW`,
  Data URI `spotify:playlist:37i9dQZF1DXcBWIGoYBM5M`, and Spotify starts that playlist.
- **Run JavaScript** runs real JS with every flow variable in scope as `vars`, and `input` set to
  the previous result. `return` a value and it becomes the step's result:
  ```js
  var words = String(input).split(/\s+/);
  return words.filter(function (w) { return w.length > 6; }).length;
  ```

Between those two, plus **HTTP Request** and **Run Shell Command**, there is very little on the
phone you cannot reach.
