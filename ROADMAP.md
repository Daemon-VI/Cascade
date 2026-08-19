# Cascade — roadmap

## v1.1 — prove the background half

The engine and editor are done; what is unproven is everything that happens while you are not
looking.

- Sit on a **time trigger** across a real day and confirm it fires on ColorOS with and without
  battery-optimisation exemption. Add a "test this trigger now" button next to each trigger.
- Same for **charger**, **Wi-Fi SSID** and **notification** triggers; the notification trigger in
  particular needs a debounce so a chatty app cannot start fifty runs a minute.
- Surface the run **History** more prominently (currently buried in Settings) with per-run logs,
  not just one line per run.
- **Quick Settings tile**: today the tile binds to the first flow that has the tile trigger.
  Let the user choose, and support several tiles.

## v1.2 — editing comfort

- **Undo** in the editor, and a confirmation when deleting a block with contents.
- **Copy / paste steps** between flows, and duplicate a whole block.
- **Import / export** a flow as JSON — `FlowStore.exportJson` / `importJson` already exist, they
  just need a share sheet and a file picker in front of them.
- **Search inside a flow** and jump to a step; useful once flows pass thirty steps.
- Warn when a `{{variable}}` refers to something no earlier step produces.

## v1.3 — more reach

- **Widget** — a home-screen grid of flow buttons, not just single pinned shortcuts.
- **Accessibility service** (opt-in) to unlock what Android otherwise refuses: tap a UI element,
  read the screen, an "app opened" trigger that does not rely on polling usage stats.
- **Camera capture** and **screenshot** actions (Camera2 and MediaProjection; the Camera2 silent
  capture path already exists in AegisToolkit and can be lifted).
- **Calendar** actions (read events, add an event) and **alarm/timer** actions.
- **Geofence** trigger built on the location plumbing already in place.

## v1.4 — sharper editing model

- Optional **grid/graph view** for branching flows, alongside the list.
- **Reusable sub-flows with typed inputs** — `Run Flow` exists but has no declared parameters.
- **Per-flow variables panel** showing every variable in scope and its last value after a run.

## Deliberately out of scope

- A cloud account, sync, or any server. Flows are files on the phone; export is a JSON share.
- Root-only actions. Everything must work on a stock, unrooted Realme.
- Play Store distribution: `QUERY_ALL_PACKAGES`, `SEND_SMS` and exact alarms together make policy
  review a fight that this app does not need to have.
