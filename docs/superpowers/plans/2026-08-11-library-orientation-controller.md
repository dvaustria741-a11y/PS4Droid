# Library Orientation Toggle + Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the Library portrait/landscape header toggle and wire Select/Share (`control == "share"`) so a gamepad can flip orientation without focusing header chrome.

**Architecture:** Reuse existing `UiOrientationPreference` + `MainActivity` cold-start + `SessionWindowModeEffect` session restore. Restore deleted `OrientationIcons` / header button. `LibraryViewModel` exposes a `toggleOrientation` SharedFlow and handles `share` in a testable `handleNavEvent`; `LibraryScreen` applies write + `requestedOrientation` for both touch and hotkey.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Activity, SharedPreferences (`UiOrientationPreference`), JUnit 4, `kotlinx-coroutines-test`, Hilt ViewModel, existing `GamepadInputManager` nav listener.

## Global Constraints

- Prefs file: `ui_preferences`; key: `ui_orientation`; values: `portrait` | `landscape`; default: portrait.
- Portrait → `ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT`; landscape → `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
- Hotkey logical control string: `"share"` (mapper keycode 109 / Select-Back).
- While details sheet open, `share` is absorbed and must **not** emit toggle.
- ViewModel must not touch `Context` / `Activity` / SharedPreferences for orientation.
- No Settings duplicate toggle; no focus-chrome rewrite; session stays immersive landscape.
- Spec: `docs/superpowers/specs/2026-08-11-library-orientation-controller-design.md`.
- Before any APK install or publication, follow runtime packaging and `unzip` verification in `AGENTS.md`.

## File Map

| File | Responsibility |
| --- | --- |
| `feature/library/.../OrientationIcons.kt` | Restore phone glyphs + `OrientationToggleButton`. |
| `feature/library/.../LibraryViewModel.kt` | `toggleOrientation` SharedFlow; `handleNavEvent`; `share` emit/absorb. |
| `feature/library/.../LibraryViewModelTest.kt` | Unit tests for share emit / details absorb / existing sort. |
| `feature/library/.../LibraryScreen.kt` | Header toggle UI; apply helper; collect hotkey; `findActivity`. |
| `core/data/.../UiOrientationPreference.kt` | **No change** (already complete). |
| `app/.../MainActivity.kt` | **No change**. |
| `feature/session/.../SessionWindowModeEffect.kt` | **No change**. |

---

### Task 1: ViewModel hotkey + unit tests (TDD)

**Files:**
- Modify: `android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryViewModel.kt`
- Modify: `android/BachataS4/feature/library/src/test/kotlin/com/bachatas4/android/feature/library/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: `NavControllerEvent(control: String, pressed: Boolean)` from `com.bachatas4.android.runtime.input`
- Produces:
  - `val toggleOrientation: SharedFlow<Unit>`
  - `fun handleNavEvent(event: NavControllerEvent): Boolean` (internal or public; used by `attachNavListener` and tests)
  - `attachNavListener()` registers `{ handleNavEvent(it) }` on `GamepadInputManager`

- [ ] **Step 1: Write the failing tests**

Replace / extend `LibraryViewModelTest.kt` so it includes existing tests **and** these new ones:

```kotlin
package com.bachatas4.android.feature.library

import com.bachatas4.android.model.Game
import com.bachatas4.android.runtime.input.NavControllerEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelTest {
    @Test
    fun sortsGamesByTitleThenId() {
        val viewModel = LibraryViewModel()

        viewModel.setGames(
            listOf(
                Game(id = "CUSA3", title = "zeta", relativePath = "games/CUSA3"),
                Game(id = "CUSA2", title = "Alpha", relativePath = "games/CUSA2"),
                Game(id = "CUSA1", title = "alpha", relativePath = "games/CUSA1"),
            ),
        )

        assertEquals(listOf("CUSA1", "CUSA2", "CUSA3"), viewModel.state.value.games.map { it.id })
    }

    @Test
    fun keepsSelectionWhenPresentOtherwiseSelectsFirstSortedGame() {
        val viewModel = LibraryViewModel()

        viewModel.setGames(listOf(game("B", "Beta"), game("A", "Alpha")))
        assertEquals("A", viewModel.state.value.selectedGameId)

        viewModel.selectGame("B")
        viewModel.setGames(listOf(game("B", "Beta"), game("A", "Alpha")))
        assertEquals("B", viewModel.state.value.selectedGameId)
    }

    @Test
    fun sharePressedEmitsToggleOrientationWhenNoDetails() = runTest {
        val viewModel = LibraryViewModel()
        viewModel.setGames(listOf(game("A", "Alpha")))
        val events = mutableListOf<Unit>()
        val job = backgroundScope.launch {
            viewModel.toggleOrientation.collect { events.add(it) }
        }

        val handled = viewModel.handleNavEvent(NavControllerEvent("share", pressed = true))

        assertTrue(handled)
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun sharePressedDoesNotEmitWhenDetailsOpen() = runTest {
        val viewModel = LibraryViewModel()
        viewModel.setGames(listOf(game("A", "Alpha")))
        viewModel.showDetails("A")
        val events = mutableListOf<Unit>()
        val job = backgroundScope.launch {
            viewModel.toggleOrientation.collect { events.add(it) }
        }

        val handled = viewModel.handleNavEvent(NavControllerEvent("share", pressed = true))

        assertTrue(handled)
        assertEquals(0, events.size)
        job.cancel()
    }

    @Test
    fun shareReleaseIsIgnored() = runTest {
        val viewModel = LibraryViewModel()
        viewModel.setGames(listOf(game("A", "Alpha")))
        val events = mutableListOf<Unit>()
        val job = backgroundScope.launch {
            viewModel.toggleOrientation.collect { events.add(it) }
        }

        val handled = viewModel.handleNavEvent(NavControllerEvent("share", pressed = false))

        assertFalse(handled)
        assertEquals(0, events.size)
        job.cancel()
    }

    @Test
    fun dpadRightStillNavigatesAfterShareWiring() {
        val viewModel = LibraryViewModel()
        viewModel.setGames(listOf(game("A", "Alpha"), game("B", "Beta")))
        assertEquals("A", viewModel.state.value.selectedGameId)

        val handled = viewModel.handleNavEvent(NavControllerEvent("dpad_right", pressed = true))

        assertTrue(handled)
        assertEquals("B", viewModel.state.value.selectedGameId)
    }

    private fun game(id: String, title: String) = Game(id, title, "games/$id")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd android/BachataS4 && ./gradlew :feature:library:testDebugUnitTest --tests com.bachatas4.android.feature.library.LibraryViewModelTest
```

Expected: FAIL — unresolved `toggleOrientation` and/or `handleNavEvent`.

- [ ] **Step 3: Implement ViewModel changes**

Update `LibraryViewModel.kt` to match this structure (keep existing methods; refactor nav body into `handleNavEvent`):

```kotlin
package com.bachatas4.android.feature.library

import androidx.lifecycle.ViewModel
import com.bachatas4.android.model.Game
import com.bachatas4.android.runtime.input.GamepadInputManager
import com.bachatas4.android.runtime.input.NavControllerEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class LibraryUiState(
    val games: List<Game> = emptyList(),
    val selectedGameId: String? = null,
    val showDetailsGameId: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor() : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState
    private var focusedIndex: Int = 0
    private var numColumns: Int = 1
    private val openSettingsRequest = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openSettings: SharedFlow<String> = openSettingsRequest
    private val launchRequest = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val launch: SharedFlow<String> = launchRequest
    private val toggleOrientationRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val toggleOrientation: SharedFlow<Unit> = toggleOrientationRequest

    fun setGames(games: List<Game>) {
        val sorted = sortGames(games)
        val selected = mutableState.value.selectedGameId
            ?.takeIf { id -> id == "__import_card__" || sorted.any { it.id == id } }
            ?: sorted.firstOrNull()?.id
            ?: "__import_card__"
        mutableState.value = mutableState.value.copy(games = sorted, selectedGameId = selected)
        focusedIndex = if (selected == "__import_card__") sorted.size else sorted.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    }

    fun selectGame(id: String) {
        if (id == "__import_card__") {
            focusedIndex = mutableState.value.games.size
        } else {
            val index = mutableState.value.games.indexOfFirst { it.id == id }
            if (index >= 0) focusedIndex = index
        }
        mutableState.value = mutableState.value.copy(selectedGameId = id)
    }

    fun setNumColumns(columns: Int) {
        numColumns = columns.coerceAtLeast(1)
    }

    fun navigatePrev() = navigate(-1)
    fun navigateNext() = navigate(1)
    fun navigateUp() = navigate(-numColumns)
    fun navigateDown() = navigate(numColumns)

    private fun navigate(direction: Int) {
        val games = mutableState.value.games
        val totalCount = games.size + 1
        if (totalCount <= 0) return
        val isVertical = kotlin.math.abs(direction) > 1
        if (isVertical) {
            val target = focusedIndex + direction
            if (target in 0 until totalCount) {
                focusedIndex = target
            }
        } else {
            focusedIndex = ((focusedIndex + direction) % totalCount + totalCount) % totalCount
        }
        val newSelectedId = if (focusedIndex == games.size) {
            "__import_card__"
        } else {
            games[focusedIndex].id
        }
        mutableState.value = mutableState.value.copy(selectedGameId = newSelectedId)
    }

    fun showDetails(id: String?) {
        mutableState.value = mutableState.value.copy(showDetailsGameId = id)
    }

    fun handleNavEvent(event: NavControllerEvent): Boolean {
        val currentState = mutableState.value
        val detailsId = currentState.showDetailsGameId
        if (detailsId != null) {
            return when {
                event.control == "cross" && event.pressed -> {
                    launchRequest.tryEmit(detailsId)
                    true
                }
                event.control == "circle" && event.pressed -> {
                    showDetails(null)
                    true
                }
                event.control == "square" && event.pressed -> {
                    showDetails(null)
                    openSettingsRequest.tryEmit(detailsId)
                    true
                }
                event.control == "share" && event.pressed -> true
                event.pressed -> true
                else -> false
            }
        }
        return when {
            event.control == "dpad_left" && event.pressed -> {
                navigatePrev()
                true
            }
            event.control == "dpad_right" && event.pressed -> {
                navigateNext()
                true
            }
            event.control == "dpad_up" && event.pressed -> {
                navigateUp()
                true
            }
            event.control == "dpad_down" && event.pressed -> {
                navigateDown()
                true
            }
            event.control == "cross" && event.pressed -> {
                currentState.selectedGameId?.let { launchRequest.tryEmit(it) }
                true
            }
            event.control == "square" && event.pressed -> {
                currentState.selectedGameId?.let { id ->
                    if (id != "__import_card__") {
                        showDetails(id)
                    }
                }
                true
            }
            event.control == "share" && event.pressed -> {
                toggleOrientationRequest.tryEmit(Unit)
                true
            }
            event.control == "circle" && event.pressed -> true
            else -> false
        }
    }

    fun attachNavListener() {
        GamepadInputManager.registerNavListener { event -> handleNavEvent(event) }
    }

    override fun onCleared() {
        GamepadInputManager.unregisterNavListener()
        super.onCleared()
    }

    fun sortGames(games: List<Game>): List<Game> =
        games.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Game::title).thenBy(Game::id))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
cd android/BachataS4 && ./gradlew :feature:library:testDebugUnitTest --tests com.bachatas4.android.feature.library.LibraryViewModelTest
```

Expected: BUILD SUCCESSFUL, all `LibraryViewModelTest` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryViewModel.kt \
  android/BachataS4/feature/library/src/test/kotlin/com/bachatas4/android/feature/library/LibraryViewModelTest.kt
git commit -m "feat(android): library Select/Share emits orientation toggle"
```

---

### Task 2: Restore OrientationIcons

**Files:**
- Create: `android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/OrientationIcons.kt`

**Interfaces:**
- Consumes: `UiOrientation` from `com.bachatas4.android.data`
- Produces: `OrientationIcons.Portrait`, `OrientationIcons.Landscape`, `@Composable OrientationToggleButton(orientation, onClick, modifier)`

- [ ] **Step 1: Restore file from git history**

Run:

```bash
git show 079c8728^:android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/OrientationIcons.kt \
  > android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/OrientationIcons.kt
```

Verify the file exists and contains `OrientationToggleButton` and both vector icons:

```bash
test -f android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/OrientationIcons.kt
grep -n 'OrientationToggleButton\|StayCurrentPortrait\|StayCurrentLandscape' \
  android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/OrientationIcons.kt
```

Expected: file present; all three names match.

If `git show` fails (history rewrite), recreate the exact content from the pre-drop blob: Material-style `ImageVector` paths for StayCurrentPortrait / StayCurrentLandscape and:

```kotlin
@Composable
internal fun OrientationToggleButton(
    orientation: UiOrientation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = remember(orientation) {
        if (orientation == UiOrientation.Portrait) OrientationIcons.Portrait
        else OrientationIcons.Landscape
    }
    val description = if (orientation == UiOrientation.Portrait) {
        "Switch to landscape"
    } else {
        "Switch to portrait"
    }
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = BachataPalette.Primary,
            modifier = Modifier.size(26.dp),
        )
    }
}
```

Full vector path bodies must match the deleted file (use `git show 079c8728^:...OrientationIcons.kt` as source of truth).

- [ ] **Step 2: Compile library module**

Run:

```bash
cd android/BachataS4 && ./gradlew :feature:library:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/OrientationIcons.kt
git commit -m "feat(android): restore library orientation icons"
```

---

### Task 3: Wire LibraryScreen toggle + hotkey apply

**Files:**
- Modify: `android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `UiOrientation`, `UiOrientationPreference`, `OrientationToggleButton`, `viewModel.toggleOrientation`
- Produces: header toggle; shared apply path for touch + hotkey; `Context.findActivity()`

- [ ] **Step 1: Add imports**

Near existing data imports in `LibraryScreen.kt`, add:

```kotlin
import android.app.Activity
import android.content.ContextWrapper
import com.bachatas4.android.data.UiOrientation
import com.bachatas4.android.data.UiOrientationPreference
```

(`Context` is already imported.)

- [ ] **Step 2: Collect hotkey in `LibraryScreen`**

Immediately after the existing `LaunchedEffect` that collects `viewModel.openSettings` (around lines 324–328), add:

```kotlin
    LaunchedEffect(viewModel) {
        viewModel.toggleOrientation.collect {
            val next = UiOrientationPreference.toggle(UiOrientationPreference.read(context))
            UiOrientationPreference.write(context, next)
            context.findActivity()?.requestedOrientation =
                UiOrientationPreference.toActivityOrientation(next)
        }
    }
```

Note: header local state also needs to update when hotkey fires. Prefer a single apply helper used by both paths (Step 3–4) rather than only writing prefs here without recomposing the icon.

- [ ] **Step 3: Restore orientation state + apply helper in `LibraryContent`**

In `LibraryContent`, after `val context = LocalContext.current`, restore:

```kotlin
    var uiOrientation by remember {
        mutableStateOf(UiOrientationPreference.read(context))
    }
    val applyOrientation: (UiOrientation) -> Unit = { next ->
        UiOrientationPreference.write(context, next)
        context.findActivity()?.requestedOrientation =
            UiOrientationPreference.toActivityOrientation(next)
        uiOrientation = next
    }
```

Update the `header` lambda to:

```kotlin
            val header: @Composable () -> Unit = {
                LibraryScreenHeader(
                    uiOrientation = uiOrientation,
                    onToggleOrientation = {
                        applyOrientation(UiOrientationPreference.toggle(uiOrientation))
                    },
                    onOpenSettings = onOpenSettings,
                )
            }
```

Because hotkey is collected in `LibraryScreen` (parent) while state lives in `LibraryContent` (child), **move the hotkey collect into `LibraryContent`** (or lift `uiOrientation` state to `LibraryScreen` and pass it down). Preferred minimal shape:

1. Keep `uiOrientation` + `applyOrientation` in `LibraryContent`.
2. Do **not** collect in parent alone.
3. Pass `viewModel` is not available in `LibraryContent` today — instead collect in `LibraryScreen` by applying prefs/orientation there **and** forcing `LibraryContent` to re-read on next composition is fragile.

**Chosen wiring (exact):**

- Add optional callback is worse.
- Better: collect `toggleOrientation` in `LibraryScreen` and call a shared top-level apply that only sets activity + prefs; pass `uiOrientation` from parent:

In `LibraryScreen` (where `context` and `viewModel` already exist):

```kotlin
    var uiOrientation by remember {
        mutableStateOf(UiOrientationPreference.read(context))
    }
    val applyOrientation: (UiOrientation) -> Unit = remember(context) {
        { next ->
            UiOrientationPreference.write(context, next)
            context.findActivity()?.requestedOrientation =
                UiOrientationPreference.toActivityOrientation(next)
            uiOrientation = next
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.toggleOrientation.collect {
            applyOrientation(UiOrientationPreference.toggle(UiOrientationPreference.read(context)))
        }
    }
```

Pass into `LibraryContent`:

```kotlin
        LibraryContent(
            state = state,
            // ...existing params...
            uiOrientation = uiOrientation,
            onToggleOrientation = {
                applyOrientation(UiOrientationPreference.toggle(uiOrientation))
            },
            onSetNumColumns = { viewModel.setNumColumns(it) },
        )
```

Update `LibraryContent` signature:

```kotlin
fun LibraryContent(
    state: LibraryUiState,
    importProgress: ImportProgress,
    gameToDelete: String?,
    uiOrientation: UiOrientation,
    onToggleOrientation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGameSettings: (String) -> Unit,
    onSelectGame: (String) -> Unit,
    onImport: () -> Unit,
    onLaunch: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onShowDetails: (String?) -> Unit,
    onSetNumColumns: (Int) -> Unit,
)
```

Header:

```kotlin
            val header: @Composable () -> Unit = {
                LibraryScreenHeader(
                    uiOrientation = uiOrientation,
                    onToggleOrientation = onToggleOrientation,
                    onOpenSettings = onOpenSettings,
                )
            }
```

If any other call sites invoke `LibraryContent` (tests/previews), update them the same way. Search:

```bash
rg -n 'LibraryContent\(' android/BachataS4 --glob '!**/{build,.gradle}/**'
```

- [ ] **Step 4: Restore `LibraryScreenHeader` parameters and toggle button**

Replace the private header with:

```kotlin
@Composable
private fun LibraryScreenHeader(
    uiOrientation: UiOrientation,
    onToggleOrientation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.size(32.dp),
            factory = { viewContext ->
                android.widget.ImageView(viewContext).apply {
                    setImageResource(viewContext.applicationInfo.icon)
                    contentDescription = "Bachata S4 logo"
                }
            },
        )
        Text(
            text = "Library",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = BachataPalette.Primary,
        )
        OrientationToggleButton(
            orientation = uiOrientation,
            onClick = onToggleOrientation,
        )
        TextButton(onClick = onOpenSettings) {
            Text("⚙", color = BachataPalette.Primary, style = MaterialTheme.typography.titleLarge)
        }
    }
}
```

- [ ] **Step 5: Restore `findActivity` helper**

Add at the bottom of `LibraryScreen.kt` (before or after `LibraryDependencies`):

```kotlin
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}
```

- [ ] **Step 6: Compile + unit tests**

Run:

```bash
cd android/BachataS4 && ./gradlew :feature:library:compileDebugKotlin :feature:library:testDebugUnitTest --tests com.bachatas4.android.feature.library.LibraryViewModelTest
```

Expected: BUILD SUCCESSFUL; ViewModel tests PASS.

- [ ] **Step 7: Commit**

```bash
git add android/BachataS4/feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt
git commit -m "feat(android): restore library orientation toggle with Share hotkey"
```

---

### Task 4: Verify prefs + session path still green

**Files:**
- None expected (verification only). Touch only if a test fails due to this work.

- [ ] **Step 1: Run orientation preference unit tests**

```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests com.bachatas4.android.data.UiOrientationPreferenceTest
```

Expected: PASS (unchanged helper).

- [ ] **Step 2: Run library feature unit tests**

```bash
cd android/BachataS4 && ./gradlew :feature:library:testDebugUnitTest
```

Expected: all PASS.

- [ ] **Step 3: Compile app module (no full runtime package required for compile-only check)**

```bash
cd android/BachataS4 && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual checklist (device / emulator with pad if available)**

1. Open Library → tap orientation icon → UI locks to landscape, carousel appears.
2. Tap again → portrait grid returns.
3. Kill app, reopen → last choice restored.
4. On controller: press Select/Share → same as tap; D-pad still moves selection; A launches; X details.
5. Open details sheet → Select/Share does **not** rotate.
6. Launch a game → immersive landscape; stop session → previous UI orientation restored.

- [ ] **Step 5: Commit only if verification required code fixes**

If no code changes, skip commit. If fixes landed:

```bash
git add -A android/BachataS4
git status
git commit -m "fix(android): orientation toggle verification follow-ups"
```

---

## Self-review (plan vs spec)

| Spec requirement | Task |
| --- | --- |
| Restore header toggle left of ⚙ | Task 2 + Task 3 |
| Restore OrientationIcons | Task 2 |
| Persist + apply `requestedOrientation` | Task 3 |
| Cold start / session restore unchanged | Task 4 verify only |
| `share` hotkey toggles when no details | Task 1 + Task 3 collect |
| `share` absorbed when details open | Task 1 |
| ViewModel no Context/Activity | Task 1 |
| Landscape carousel / d-pad cols | Already present; Task 4 manual |
| Unit tests for share emit | Task 1 |

No TBD/TODO placeholders. SharedFlow name `toggleOrientation` consistent across tasks. Control string `"share"` consistent with `ControllerMapper` keycode 109.
