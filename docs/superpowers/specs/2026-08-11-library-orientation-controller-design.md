# Library Orientation Toggle + Controller Design

## Goal

Restore the Library portrait/landscape orientation toggle (GameHub-style) and make it usable with a physical controller, without requiring D-pad focus into the header chrome.

Persist the choice across restarts and after gameplay sessions. Landscape continues to use the existing console-style horizontal carousel; portrait continues to use the adaptive grid.

## Relationship to prior work

This supersedes the *toggle UI* portion of `2026-08-06-ui-orientation-toggle-design.md` and the drop in commit `079c8728` (`fix(android): drop library orientation toggle`).

**Still valid and already implemented (do not re-design):**

| Piece | Status |
| --- | --- |
| `UiOrientation` / `UiOrientationPreference` | Present in `core:data` |
| Cold-start apply in `MainActivity` | Present |
| Manifest `screenOrientation="unspecified"` | Present |
| Session dispose restore from preference | Present in `SessionWindowModeEffect` |
| Landscape `LibraryLandscapeCarousel` | Present |
| D-pad grid navigation via `LibraryViewModel` | Present (`numColumns` forced to 1 in landscape) |

**Missing (this design):**

- Header orientation toggle button + icons (deleted)
- Controller hotkey to flip orientation without focusing the header

## Non-goals

- No third “follow system sensor” mode.
- No D-pad focus traversal into header chrome (settings gear / toggle focus ring).
- No Settings-screen duplicate toggle.
- No redesign of Settings/Setup for landscape beyond natural reflow.
- No change to in-session touch-controller layout or session immersive landscape.
- No per-game UI orientation.

## Chosen approach

**Restore header toggle + Share/Select hotkey (Approach A).**

| Concern | Decision |
| --- | --- |
| Touch entry | Library header button left of ⚙ |
| Controller entry | Logical control `"share"` (Android `KEYCODE_BUTTON_SELECT` / 109, Select/Back on Xbox-style pads) |
| Storage / apply | Existing `UiOrientationPreference` + `Activity.requestedOrientation` |
| ViewModel role | Emit orientation-toggle request; no Android `Context`/`Activity` in ViewModel |
| UI role | Write prefs, set `requestedOrientation`, update local icon state |
| Details sheet | Absorb `share` while details open (no rotate mid-dialog) |

### Rejected alternatives

| Approach | Why not |
| --- | --- |
| Hotkey only | Phones / no-pad users cannot switch |
| Full focus chrome | Larger focus-order rework; user chose hotkey path |

## Behavior

### Orientation mapping (unchanged)

| Preference | `ActivityInfo` constant |
| --- | --- |
| Portrait | `SCREEN_ORIENTATION_SENSOR_PORTRAIT` |
| Landscape | `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` |

Sensor portrait/landscape allows reverse orientation on each axis while locking the axis independent of system auto-rotate.

### Toggle control (touch)

- Location: Library header row: `[logo] Library … [orientation icon] [⚙]`.
- Tap flips Portrait ↔ Landscape, writes prefs immediately, applies `requestedOrientation` immediately.
- Icon reflects **current** mode:
  - Portrait → portrait phone glyph; content description “Switch to landscape”.
  - Landscape → landscape phone glyph; content description “Switch to portrait”.
- Visual weight matches the settings control (`IconButton` / small icon).

### Controller hotkey

Logical control names come from `ControllerMapper.BUTTONS` (nav path uses mapper names, not custom profile remaps for the control string):

| Control string | Typical hardware | Library action (no details sheet) |
| --- | --- | --- |
| `dpad_*` | D-Pad / HAT | Existing selection navigation |
| `cross` | A / Cross | Launch / import (existing) |
| `square` | X / Square | Show details (existing) |
| `circle` | B / Circle | Consume / no-op (existing) |
| **`share`** | **Select / Back / Share** | **Flip orientation** |

While the game-details sheet is open, `share` is consumed and does **not** toggle orientation (same absorption pattern as other non-action buttons in the details branch of `attachNavListener`).

Hotkey does not require moving focus to the header. One press flips and persists.

### Session interaction (unchanged)

1. User may be in portrait or landscape UI.
2. Session route: force immersive landscape (`SessionWindowModeEffect`).
3. Session dispose: restore orientation from persisted preference; show system bars.

### Cold start (unchanged)

`MainActivity.onCreate` reads preference and sets `requestedOrientation` before content.

### Layout adaptation (unchanged)

- Portrait: adaptive `LazyVerticalGrid`.
- Landscape: `LibraryLandscapeCarousel` when `maxWidth > maxHeight` after the activity orientation lock takes effect.
- `LibraryViewModel.setNumColumns(1)` in landscape so vertical D-pad steps by one.

## Components

### 1. Restore `OrientationIcons.kt`

Restore the deleted Material-style phone outline vectors and `OrientationToggleButton` composable from commit parent of `079c8728` (paths match StayCurrentPortrait / StayCurrentLandscape).

### 2. `LibraryScreen` / `LibraryContent` / `LibraryScreenHeader`

- Remember current `UiOrientation` from `UiOrientationPreference.read(context)`.
- Pass into header; on toggle (or when collecting ViewModel event): `toggle` → `write` → `findActivity()?.requestedOrientation = …` → update state.
- Restore private `Context.findActivity()` helper (also removed with the drop commit).
- Wire `OrientationToggleButton` left of settings in `LibraryScreenHeader`.

### 3. `LibraryViewModel` hotkey

```text
attachNavListener (no details sheet):
  share + pressed → toggleOrientationRequest.tryEmit(Unit); true

attachNavListener (details sheet open):
  share + pressed → true  // absorb, no emit
```

Expose:

```kotlin
val toggleOrientation: SharedFlow<Unit>  // extraBufferCapacity >= 1
```

Screen collects with the same lifecycle pattern used for `launch` / `openSettings`.

Do **not** put SharedPreferences or Activity orientation APIs inside the ViewModel.

### 4. Unchanged modules

- `UiOrientationPreference` / unit tests
- `MainActivity` cold start
- `SessionWindowModeEffect` dispose restore
- `LibraryLandscapeCarousel` visual design
- `AndroidManifest` `unspecified` orientation

## Data flow

```text
Cold start
  MainActivity.onCreate
    → read ui_preferences.ui_orientation
    → requestedOrientation = mapped constant

Library header tap
  → flip Portrait ↔ Landscape
  → write SharedPreferences
  → requestedOrientation = new mapping
  → recompose (grid vs carousel via BoxWithConstraints)

Library Select/Share (nav listener)
  → ViewModel emits toggleOrientation
  → Screen applies same flip/write/apply path as header tap

Enter session
  → ImmersiveLandscape + hide system bars

Leave session
  → dispose: restore preference orientation + show bars
```

## Testing

1. **Unit — ViewModel:** pressing `share` with no details sheet emits once on `toggleOrientation`; with details sheet open does not emit; existing d-pad / cross / square behavior unchanged.
2. **Unit — prefs:** existing `UiOrientationPreferenceTest` remains green (no API change expected).
3. **Manual / APK:**
   - Tap header toggle: UI rotates; icon updates; kill/reopen restores choice.
   - Press Select/Share on pad: same as tap.
   - Launch game → landscape; stop session → previous UI orientation.
   - Device rotation lock on: app still honors forced axis.

## Failure handling

- Corrupt / missing pref → portrait default (existing).
- Activity not found from Compose context → write prefs only; next cold start applies.
- Unknown / unmapped keycodes → ignored by mapper (existing).

## File map

| File | Action |
| --- | --- |
| `feature/library/.../OrientationIcons.kt` | Restore |
| `feature/library/.../LibraryScreen.kt` | Restore header toggle + collect hotkey + `findActivity` |
| `feature/library/.../LibraryViewModel.kt` | `share` → `toggleOrientation` SharedFlow |
| `feature/library/.../LibraryViewModelTest.kt` | Cover share emit / details absorb |
| `core/data/.../UiOrientationPreference.kt` | No change |
| `app/.../MainActivity.kt` | No change |
| `feature/session/.../SessionWindowModeEffect.kt` | No change |

## Out of scope follow-ups

- D-pad focus into header chrome.
- On-screen controller hint bar (GameHub “Y Search / A Confirm” chrome).
- Remappable “orientation toggle” binding in controller settings.
- Per-game UI orientation.
