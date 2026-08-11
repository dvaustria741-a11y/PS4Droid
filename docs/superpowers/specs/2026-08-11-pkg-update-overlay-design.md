# PKG Update / DLC Overlay Install Design

## Goal

Let a user add update and DLC `.pkg` files to an already-installed game from the
game options sheet. The user selects multiple `.pkg` files via the system file
picker, reorders them into the desired install sequence, and on confirm the
packages are extracted in order and **overlaid** into the existing base game
folder — later packages overwriting earlier files, matching how PS4 update PKGs
replace base-game files at `/app0`.

This builds on the existing PKG import stack (`ImportService` + `libbachata_pkg.so`
+ `ContentImporter`). It does **not** introduce a parallel installer, change the
native extractor, or change the Room schema.

## Background

Existing single-PKG path (see `docs/superpowers/specs/2026-07-23-pkg-import-design.md`
and `docs/superpowers/specs/2026-08-06-pkg-install-state-machine-design.md`):

1. Library import card → SAF picker picks **one** `.pkg` or folder.
2. `ImportService.runPkgImport` probes (`PkgExtractor.nativeProbe`), gates on
   storage/passcode, native-extracts into staging `games/.import-<jobId>/`.
3. `ContentImporter.finalizeStagingTree` verifies required files, writes
   `install.manifest`, and **atomic-renames** staging → `games/<TITLE_ID>/`.
4. `GameRepository.addImportedGame` inserts the Room row.

Two blockers prevent this path from serving updates/DLC:

- `ImportService.kt:565-571` re-installing the same TITLE_ID fails with
  `ALREADY_INSTALLED` (or deletes + reinstalls the whole tree).
- `ContentImporter.finalizeStagingTree` (`ContentImporter.kt:164`) throws
  `CONTENT_INVALID "Game already imported"` when the destination exists.

There is also no multi-file picker, no reorder UI, and no batch extract loop.

## Decisions (locked)

| Topic | Choice |
|-------|--------|
| Architecture | **Approach 1 — sheet-embedded batch overlay.** New "Add PKG" button in `GlassBottomSheet`, multi-file SAF picker, reorder dialog, batch action in `ImportService` that overlays into the existing game folder. |
| Merge behavior | **Overlay into game folder.** Each PKG extracts to its own staging dir, then files are copied into the live game folder in order, overwriting. No destination delete, no atomic swap. |
| TITLE_ID mismatch | **Fail the whole batch** before any extract/overlay write. A two-pass pre-flight probes every PKG's TITLE_ID first (pass 1); only if all match does extraction+overlay proceed (pass 2). |
| Manifest version | Batch path writes `version=2` (with `overlays`); single-PKG/folder paths keep `version=1` (no `overlays`). Reader handles both. |
| `touchGame` | `GameRepository.touchGame(gameId)` = bump `importedAtMs` to now; re-resolve title/subtitle/detail from the final `param.sfo` on dest and `updateTitle` if changed. No new row. |
| Button placement | New full-width secondary button in the sheet (Option A), keeping the existing 3-button row. |
| Reorder UI | Up/down arrows per row (gamepad-friendly). Drag-and-drop deferred. |
| Native extractor | Unchanged. Reuses `nativeProbe` + `nativeExtract` per PKG. Pass-1 pre-flight reads TITLE_ID from `probe.titleHint` (already populated; see Open question). |
| DB schema | Unchanged. Game row stays; manifest gains optional `overlays` field (manifest version → 2; old readers ignore unknown keys). |
| Install root | `context.filesDir/games/<gameId>/` only, same as base install. |

## Data flow

```
GlassBottomSheet "Add PKG" button
  └─ OpenMultipleDocuments picker (.pkg filter)
     └─ AddPkgsDialog (reorder + confirm)
        └─ startService(ACTION_IMPORT_PKGS
                        EXTRA_GAME_ID = <gameId>
                        EXTRA_URIS    = [uri1, uri2, ...] in user order)
           └─ ImportService.runPkgBatchImport(gameId, uris)
              ├─ ImportManager.tryBeginImport → claim slot (BatchSelected)
              ├─ verify base game dest exists (games/<gameId>/)
              ├─ PASS 1 — pre-flight (no extraction, no overlay):
              │     for each uri: nativeProbe(fd) → contentId/size;
              │     sum sizes for the single storage gate;
              │     (TITLE_ID is read from the PKG header's param.sfo entry
              │      via the probe result — see Open question resolution)
              │     if any TITLE_ID != gameId → abort batch, dest untouched
              ├─ single upfront storage check (sum of PKG sizes)
              ├─ PASS 2 — extract + overlay (only if pass 1 passed):
              for ((i, uri) in uris.withIndex()):
              │   extract → staging games/.import-batch-<jobId>-<i>/
              │   verify staging has eboot.bin or sce_sys/param.sfo
              │   ContentImporter.overlayStaging(gameId, staging, contentId, uri)
              │   delete staging
              ├─ rewrite install.manifest on dest (append overlays[])
              ├─ GameRepository.touchGame(gameId)
              └─ BatchInstalled(gameId, title, count)
```

## Components

### 1. `ImportManager.kt` — new action + extras + progress states

New constants:

```kotlin
const val ACTION_IMPORT_PKGS = "com.bachatas4.android.action.IMPORT_PKGS"
const val EXTRA_GAME_ID = "game_id"
const val EXTRA_URIS = "source_uris"   // String ArrayList
const val MODE_PKG_BATCH = "pkg-batch"
```

New `ImportProgress` subtypes:

```kotlin
data class BatchSelected(
    val gameId: String,
    val gameTitle: String,
    val packageCount: Int,
) : ImportProgress

data class BatchExtracting(
    val index: Int,          // 0-based current PKG
    val total: Int,
    val bytesCopied: Long,
    val totalBytes: Long,
    val currentFile: String,
    val gameTitle: String,
) : ImportProgress

data class BatchInstalled(
    val gameId: String,
    val title: String,
    val count: Int,
) : ImportProgress

data class BatchFailed(
    val code: InstallErrorCode,
    val message: String,
    val completedCount: Int,
    val totalCount: Int,
) : ImportProgress
```

`isBusy()` already treats any non-terminal state as busy; `BatchInstalled` and
`BatchFailed` are terminal (like `Installed`/`Failed`).

### 2. `ContentImporter.kt` — new `overlayStaging`

```kotlin
data class OverlayResult(
    val filesMerged: Int,
    val bytesMerged: Long,
)

/**
 * Merge [stagingDir] into the existing games/<gameId>/ folder, overwriting
 * files. Does NOT delete the destination tree and does NOT require eboot.bin
 * in staging (update/DLC PKGs may omit it). The destination must already exist
 * (base game installed).
 */
suspend fun overlayStaging(
    gameId: String,
    stagingDir: File,
    contentId: String?,
    sourceUri: String,
): OverlayResult = withContext(Dispatchers.IO) {
    validateGameId(gameId)
    val gamesDir = File(filesDir, "games").canonicalFile
    val dest = File(gamesDir, gameId).canonicalFile
    requireInside(gamesDir, dest)
    requireInside(gamesDir, stagingDir.canonicalFile)
    check(dest.isDirectory) { "Base game not installed" }

    var files = 0
    var bytes = 0L
    stagingDir.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        val rel = file.relativeTo(stagingDir).path
        val target = File(dest, rel).canonicalFile
        requireInside(dest, target)
        target.parentFile?.mkdirs()
        coroutineContext.ensureActive()
        file.copyTo(target, overwrite = true)
        files++
        bytes += file.length()
    }
    OverlayResult(files, bytes)
}
```

Differences from `finalizeStagingTree`:
- No atomic rename; destination is preserved.
- Destination is NOT deleted.
- Partial tree allowed (no `eboot.bin` requirement on staging).
- No `GameInstallVerifier.verifyTreeForRegistration` (the base game is already
  verified; an overlay must not re-fail the whole game on a partial update PKG).

### 3. `ImportService.kt` — new `runPkgBatchImport`

Dispatched from `onStartCommand` for `ACTION_IMPORT_PKGS`. The per-PKG loop
**reuses** the existing probe → extract → passcode/copy-confirm machinery,
factored out of `runPkgImport` so both paths share it:

- `probeAndExtract(uri, staging)`: probe via SAF fd, storage-gate once (caller
  sums sizes), branch seekable-vs-cache fd, run the candidate-passcode extract
  loop, block on `NeedPasscode`/`NeedCopyConfirm` exactly as today. Returns the
  staging dir populated.
- `runPkgBatchImport(gameId, uris)`:
  1. Claim slot via `tryBeginImport`; emit `BatchSelected`.
  2. Verify `games/<gameId>/` exists and `canLaunch`.
  3. One upfront storage check: open each fd, probe, sum `pfsImageSize` (fallback
     `packageSize`) + margin. Emit `CheckingStorage` once.
  - `runPkgBatchImport(gameId, uris)`:
  1. Claim slot via `tryBeginImport`; emit `BatchSelected`.
  2. Verify `games/<gameId>/` exists and `canLaunch`.
  3. **Pass 1 — pre-flight:** open each URI's SAF fd, `nativeProbe`, read
     TITLE_ID from the probe's param.sfo, collect `contentId` + size. If any
     TITLE_ID `!= gameId`, throw a typed `CONTENT_INVALID` and abort — dest
     untouched (no extraction has run).
  4. One upfront storage check: sum probed sizes + margin. Emit `CheckingStorage`.
  5. **Pass 2 — extract + overlay loop** per URI:
     - `probeAndExtract` into `games/.import-batch-<jobId>-<i>/`.
     - `contentImporter.overlayStaging(gameId, staging, contentId, uri)`.
     - Delete staging.
     - Emit `BatchExtracting(i, total, …)` for progress.
  6. Rewrite `install.manifest` on dest (version 2, append `overlays`).
  7. `gameRepository.touchGame(gameId)`.
  8. Emit `BatchInstalled`.
  5. Rewrite `install.manifest` on dest (version 2, append `overlays`).
  6. `gameRepository.touchGame(gameId)`.
  7. Emit `BatchInstalled`.

Cleanup (finally): delete any leftover staging dirs; delete per-PKG cache files;
release slot. Same `InstallCleanup` reuse.

### 4. `InstallManifest.kt` — version 2 with `overlays`

New optional field:

```kotlin
data class InstallManifest(
    val version: Int = 1,
    val status: String,
    val gameId: String,
    val contentId: String?,
    val mode: String,
    val sourceUri: String,
    val installedAtMs: Long,
    val requiredFiles: List<String>,
    val bytesTotal: Long,
    val overlays: List<OverlayRecord> = emptyList(),   // NEW
)

data class OverlayRecord(
    val contentId: String?,
    val sourceUri: String,
    val appliedAtMs: Long,
)
```

Encoding appends `overlays=<comma-escaped "contentId@ts|sourceUri">`. The decoder
already reads into a keyed map and ignores unknown keys, so version-1 manifests
read fine and `overlays` defaults to empty when absent. Writers bumped by the
batch path set `version=2`; the single-PKG path still writes `version=1` (no
overlays), which remains valid.

### 5. `LibraryScreen.kt` — UI

**5a. GlassBottomSheet** — new callback + full-width secondary button placed
above the Launch button (Option A):

```kotlin
@Composable
private fun GlassBottomSheet(
    game: Game,
    onLaunch: () -> Unit,
    onCancel: () -> Unit,
    onOpenGameSettings: () -> Unit,
    onRequestDelete: () -> Unit,
    onAddPkgs: () -> Unit,          // NEW
    ...
)
```

Button label: "Add PKG update / DLC" with a package glyph. Disabled while
`ImportManager.isBusy()`.

**5b. Multi-file picker + reorder dialog** (new state in `LibraryContent`):

```kotlin
val pkgMultiPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments()
) { uris ->
    if (uris.isEmpty()) return@...
    if (ImportManager.isBusy()) { toast; return@... }
    val pkgUris = uris.filter {
        DocumentFile.fromSingleUri(context, it)?.name
            ?.endsWith(".pkg", ignoreCase = true) == true
    }
    uris.forEach { takePersistableUriPermission(it, FLAG_GRANT_READ) }
    addPkgsState = AddPkgsState(targetGameId, pkgUris)
}

var addPkgsState: AddPkgsState? by remember { mutableStateOf(null) }
```

`AddPkgsDialog` composable (shown when `addPkgsState != null`):

- Header: game title + gameId.
- Ordered list of selected PKGs (filename + size via `DocumentFile.length()`).
- Per row: up/down arrows (move in list), remove button.
- "Add more" button re-launches `pkgMultiPicker`, appends new URIs.
- Footer: Cancel / Install `<n>` (disabled when list empty).
- Confirm → `startService(ACTION_IMPORT_PKGS, EXTRA_GAME_ID, EXTRA_URIS)` in
  list order, then `addPkgsState = null`.

### 6. Progress overlay reuse

The existing library progress overlay (`ImportManager.progress` collected at
`LibraryScreen.kt:111`) renders all `ImportProgress` states. New batch states
render in the same surface:

- `BatchSelected` → "Preparing batch install…"
- `BatchExtracting(i, total, …)` → "Installing ${i+1} of $total: $filename ($pct%)"
- `NeedPasscode` / `NeedCopyConfirm` → same dialogs, per-PKG.
- `BatchInstalled` → success snackbar "Installed $count packages for $title".
- `BatchFailed` → error dialog "Installed $completed of $total before failure: $msg".

## Edge cases

| Case | Behavior |
|------|----------|
| TITLE_ID mismatch | Fail batch in pass 1 (pre-flight), before any extraction or overlay. Dest untouched. |
| Mid-batch failure (disk full, bad passcode) | Already-overlaid PKGs stay (can't un-merge). Current staging deleted. Remaining skipped. `BatchFailed(completedCount, totalCount)`. Game remains launchable. |
| Cancel (`ACTION_CANCEL`) | `nativeCancel()` interrupts current extract; loop checks `ensureActive()` between PKGs. Partial overlays left in place. |
| Per-PKG passcode / copy-confirm | Reuses `NeedPasscode` / `NeedCopyConfirm` + existing dialogs sequentially. |
| Storage check | One upfront gate summing all PKG sizes; no per-PKG re-check dialog. |
| Duplicate URI in batch | Allowed; re-overlays identical files (idempotent). |
| Empty picker / 0 PKGs | Confirm button disabled in `AddPkgsDialog`. |
| Base game deleted between sheet open and confirm | `overlayStaging` throws `CONTENT_INVALID "Base game not installed"` at batch start. |
| Game launched during batch | Launch buttons gated by `ImportManager.isBusy()` (existing); race avoided. |
| Process killed mid-batch | `install.manifest` not rewritten until batch end; stays at old version → `syncLibrary` sees healthy base game. No corruption. |
| Update PKG without eboot.bin | `overlayStaging` does not require eboot.bin in staging; base eboot preserved. |

## Testing

Unit/instrumented tests:

1. `overlayStaging` merges a 2-file staging tree over a populated dest,
   overwriting one existing file and adding one new file; dest tree otherwise
   intact; base `eboot.bin` preserved when staging lacks one.
2. `overlayStaging` throws when dest does not exist.
3. `overlayStaging` throws when a staging file path escapes dest
   (`requireInside`).
4. `runPkgBatchImport` aborts with a typed error when the second PKG's TITLE_ID
   differs, and dest is unchanged (no overlay from the first PKG leaks — note:
   first PKG *is* overlaid before the second is probed; see Open question).
5. `InstallManifest` round-trips version 2 with `overlays`; reads version 1
   manifests with empty `overlays`.
6. `AddPkgsDialog` reorder: up/down moves change the URI order sent to the
   service.

Manual acceptance (OnePlus Pad 2): install a base game, add an update PKG +
a DLC PKG via the new flow, reorder, confirm, launch the game and verify the
update took effect (version string / DLC content present).

## Open question (probe-sfo feasibility) — RESOLVED, NO NATIVE CHANGE NEEDED

Pass-1 pre-flight needs each PKG's TITLE_ID without extracting. Investigation of
`pkg_extractor.cpp` shows the native code already derives the TITLE_ID from the
PKG header's `content_id`: the format is `XX0006-CUSA01234_00-...`, so chars
7-15 are the CUSA TITLE_ID. `bachata_pkg_probe` already writes this 9-char slice
into `title_hint` (`pkg_extractor.cpp:796`), and `bachata_pkg_extract` derives
`st.title_id` the same way (`pkg_extractor.cpp:856`: `cid + 7, 9`).

Therefore `PkgProbeResult.titleHint` **already carries the TITLE_ID** for retail
PKGs (it is the CUSA id). Pass-1 pre-flight validates it with the existing
`GameMetadataResolver.cusa` regex (`CUSA\d{5}`) and compares to `gameId`.

**Decision: use `probe.titleHint` directly. No native change. No new field.**
This is cleaner than option (a) — zero native code touched. The earlier (a)
choice is superseded by this finding (the field we were going to add already
exists under the name `titleHint`).

## Out of scope

- Full package-manager screen listing installed base/updates/DLC per game.
- Persistent package-type metadata (base/update/dlc) in the DB.
- DLC routed to a separate `/addcont` path with HLE addcont wiring ( Approach 2).
- Atomic batch swap (staging entire merged tree, then swap) — uses 2x disk.
- Drag-and-drop reorder (arrows only for v1).
- Per-game "installed packages" list view in the sheet.
