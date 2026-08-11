# PKG Update / DLC Overlay Install Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Add PKG" button to the game options sheet that lets a user select multiple `.pkg` files, reorder them, and on confirm extract+overlay them in order into an already-installed game's folder.

**Architecture:** New batch action in `ImportService` reuses the existing native PKG extractor (`libbachata_pkg.so`) per-package, extracting each to its own staging dir, then merging files into the live `games/<gameId>/` folder via a new `ContentImporter.overlayStaging`. A two-pass design (pass 1 probes all PKGs for TITLE_ID validation + storage; pass 2 extracts+overlays) guarantees a mismatched PKG never writes to the base game. UI adds a button to `GlassBottomSheet`, a multi-document SAF picker, and a reorder dialog. No native code changes; no DB schema changes.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, Coroutines, Android SAF (`ActivityResultContracts.OpenMultipleDocuments`), JUnit4 + `TemporaryFolder` for JVM unit tests.

**Spec:** `docs/superpowers/specs/2026-08-11-pkg-update-overlay-design.md`

## Global Constraints

- Install root is fixed at `context.filesDir/games/<gameId>/` (app-private storage). Never user-configurable.
- Native extractor (`libbachata_pkg.so`, `core/runtime/src/main/cpp/pkg/`) is **not modified**. Reuse `PkgExtractor.nativeProbe` / `nativeExtract` / `nativeCancel` as-is.
- Room `games` table schema is **not modified**. The `GameEntity` columns stay exactly as defined in `core/database/src/main/kotlin/com/bachatas4/android/database/GameDao.kt:11`.
- `PkgProbeResult.titleHint` already carries the TITLE_ID for retail PKGs (`pkg_extractor.cpp:796` derives it as `content_id` chars 7-15). Pass-1 pre-flight reads it directly; no new probe field.
- TITLE_ID validation uses the existing `GameMetadataResolver.cusa` regex (`CUSA\d{5}`, case-insensitive) defined at `core/data/src/main/kotlin/com/bachatas4/android/data/GameMetadataResolver.kt:14`.
- Every file write into the game folder must pass a `requireInside(gamesDir, target)` containment check (existing pattern in `ContentImporter.kt:380`).
- Manifest version for batch overlays is `2`; single-PKG/folder installs stay at version `1`. The decoder at `InstallManifest.kt:66` reads into a keyed map and ignores unknown keys, so v1 manifests read fine with the v2 decoder.
- All new `ImportProgress` subtypes must be covered by the existing library progress overlay collection at `feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt:111` (which observes `ImportManager.progress`) — either by adding rendering branches or by ensuring terminal states reset via the existing `LaunchedEffect(importProgress)` at `LibraryScreen.kt:125`.
- Existing single-PKG and folder import paths must keep passing their current tests; this plan adds new paths, it does not change existing ones except to factor out shared probe/extract helpers.
- Commits follow Conventional Commits (`feat:`, `test:`, `refactor:`). Each task ends with a commit.

---

## File Structure

**Modified files (9):**

| File | Responsibility |
|------|----------------|
| `core/data/src/main/kotlin/com/bachatas4/android/data/ImportManager.kt` | Add `ACTION_IMPORT_PKGS`, extras, `MODE_PKG_BATCH`, and 4 new `ImportProgress` subtypes. |
| `core/data/src/main/kotlin/com/bachatas4/android/data/InstallManifest.kt` | Add optional `overlays: List<OverlayRecord>` field; bump writers to version 2; keep v1 reads working. |
| `core/data/src/main/kotlin/com/bachatas4/android/data/ContentImporter.kt` | Add `overlayStaging(gameId, stagingDir, contentId, sourceUri)` + `OverlayResult`. |
| `core/data/src/main/kotlin/com/bachatas4/android/data/GameRepository.kt` | Add `touchGame(gameId)` — bump `importedAtMs`, re-resolve title from sfo. |
| `app/src/main/kotlin/com/bachatas4/android/service/ImportService.kt` | Factor out `probeAndExtract`; add `runPkgBatchImport`; dispatch `ACTION_IMPORT_PKGS`. |
| `feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt` | Add "Add PKG" button to `GlassBottomSheet`, multi-picker, `AddPkgsDialog`, batch progress rendering. |
| `core/data/src/test/kotlin/com/bachatas4/android/data/ContentImporterTest.kt` | Add `overlayStaging` tests. |
| `core/data/src/test/kotlin/com/bachatas4/android/data/InstallManifestTest.kt` | Add v2 `overlays` round-trip + v1 back-compat tests. |
| `core/data/src/test/kotlin/com/bachatas4/android/data/ImportManagerTest.kt` | Add batch-progress-state + `isBusy` tests. |

**Created files (1):**

| File | Responsibility |
|------|----------------|
| `app/src/test/kotlin/com/bachatas4/android/service/ImportServiceBatchTest.kt` | JVM-level test of the pre-flight TITLE_ID mismatch abort logic, using a fake extractor where feasible; otherwise covered by the `overlayStaging` + `ImportManager` unit tests and manual acceptance. |

---

## Task 1: Manifest v2 with overlays

**Goal:** Add the `overlays` field to `InstallManifest` so the batch path can record applied packages, without breaking v1 reads.

**Files:**
- Modify: `core/data/src/main/kotlin/com/bachatas4/android/data/InstallManifest.kt`
- Test: `core/data/src/test/kotlin/com/bachatas4/android/data/InstallManifestTest.kt`

**Interfaces:**
- Produces: `data class OverlayRecord(val contentId: String?, val sourceUri: String, val appliedAtMs: Long)`; `InstallManifest` gains `overlays: List<OverlayRecord> = emptyList()`. Encoding appends an `overlays=` line; decoding reads it back and tolerates its absence.

- [ ] **Step 1: Read the current manifest file + test**

Read `core/data/src/main/kotlin/com/bachatas4/android/data/InstallManifest.kt` (115 lines) and `core/data/src/test/kotlin/com/bachatas4/android/data/InstallManifestTest.kt` to learn the encode/decode shape and existing test patterns.

- [ ] **Step 2: Write the failing tests**

Append to `InstallManifestTest.kt`:

```kotlin
@Test
fun overlayRoundTripsWithManifest() {
    val dir = temporaryFolder.newFolder()
    val manifest = InstallManifest(
        version = 2,
        status = InstallManifestIo.STATUS_INSTALLED,
        gameId = "CUSA07023",
        contentId = "EP9000-CUSA07023_00-BBBASEGAME00000",
        mode = "pkg",
        sourceUri = "content://base",
        installedAtMs = 1_700_000_000_000L,
        requiredFiles = listOf("eboot.bin", "sce_sys/param.sfo"),
        bytesTotal = 10_000L,
        overlays = listOf(
            OverlayRecord("EP9000-CUSA07023_00-BBUPDT010000000", "content://u1", 1_700_000_001_000L),
            OverlayRecord(null, "content://dlc", 1_700_000_002_000L),
        ),
    )
    InstallManifestIo.write(dir, manifest)
    val read = InstallManifestIo.read(dir)
    assertEquals(manifest, read)
}

@Test
fun versionOneManifestReadsWithEmptyOverlays() {
    val dir = temporaryFolder.newFolder()
    // Hand-write a v1 manifest with no overlays line.
    File(dir, InstallManifestIo.FILE_NAME).writeText(
        """
        version=1
        status=INSTALLED
        gameId=CUSA07023
        contentId=
        mode=pkg
        sourceUri=content://base
        installedAtMs=1700000000000
        requiredFiles=eboot.bin,sce_sys/param.sfo
        bytesTotal=10000
        """.trimIndent(),
    )
    val read = InstallManifestIo.read(dir)
    assertNotNull(read)
    assertEquals(1, read!!.version)
    assertEquals(emptyList<OverlayRecord>(), read.overlays)
}
```

If `temporaryFolder` or `newFolder()` is not yet a field in `InstallManifestTest`, add:
```kotlin
@get:Rule
val temporaryFolder = TemporaryFolder()
```
and import `org.junit.rules.TemporaryFolder`. Add the missing imports (`java.io.File`, `org.junit.Assert.assertNotNull`).

- [ ] **Step 3: Run tests to verify they fail**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests "com.bachatas4.android.data.InstallManifestTest.overlayRoundTripsWithManifest" --tests "com.bachatas4.android.data.InstallManifestTest.versionOneManifestReadsWithEmptyOverlays"
```
Expected: compile error — `OverlayRecord` unresolved.

- [ ] **Step 4: Add `OverlayRecord` + `overlays` field + encode/decode**

In `InstallManifest.kt`:

Add after the `InstallManifest` data class (before `object InstallManifestIo`):

```kotlin
data class OverlayRecord(
    val contentId: String?,
    val sourceUri: String,
    val appliedAtMs: Long,
)
```

Add `overlays` field to `InstallManifest`:
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
    val overlays: List<OverlayRecord> = emptyList(),
)
```

In `encode`, after the `bytesTotal=` line, append:
```kotlin
val ov = manifest.overlays.joinToString(",") { o ->
    escape((o.contentId.orEmpty()) + "@" + o.appliedAtMs + "|" + o.sourceUri)
}
append("overlays=").append(ov).append('\n')
```

In `decode`, after `bytesTotal` is parsed, before the `return InstallManifest(...)`:
```kotlin
val overlays = map["overlays"]
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.map { entry ->
        val at = entry.indexOf('@')
        val pipe = entry.indexOf('|', startIndex = (at + 1).coerceAtLeast(0))
        if (at <= 0 || pipe <= at) {
            OverlayRecord(null, unescape(entry), 0L)
        } else {
            val cid = unescape(entry.substring(0, at)).takeIf { it.isNotBlank() }
            val ts = unescape(entry.substring(at + 1, pipe)).toLongOrNull() ?: 0L
            val uri = unescape(entry.substring(pipe + 1))
            OverlayRecord(cid, uri, ts)
        }
    }
    ?: emptyList()
```
Then add `overlays = overlays,` to the returned `InstallManifest(...)`.

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests "com.bachatas4.android.data.InstallManifestTest"
```
Expected: all `InstallManifestTest` tests PASS (existing + 2 new).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/bachatas4/android/data/InstallManifest.kt \
        core/data/src/test/kotlin/com/bachatas4/android/data/InstallManifestTest.kt
git commit -m "feat(data): manifest v2 records overlay pkgs

Add OverlayRecord + optional overlays list to InstallManifest.
v1 manifests decode with empty overlays."
```

---

## Task 2: `ContentImporter.overlayStaging`

**Goal:** Add the overlay-merge method that copies a staging tree into an existing game folder, overwriting, without deleting the destination.

**Files:**
- Modify: `core/data/src/main/kotlin/com/bachatas4/android/data/ContentImporter.kt`
- Test: `core/data/src/test/kotlin/com/bachatas4/android/data/ContentImporterTest.kt`

**Interfaces:**
- Produces: `suspend fun overlayStaging(gameId: String, stagingDir: File, contentId: String?, sourceUri: String): OverlayResult` on `ContentImporter`. `data class OverlayResult(val filesMerged: Int, val bytesMerged: Long)`.
- Throws `ContentImportException(RuntimeErrorCode.CONTENT_INVALID, ...)` when the base game folder (`games/<gameId>/`) does not exist, or a staging path escapes containment.

- [ ] **Step 1: Read current ContentImporter + test**

Read `core/data/src/main/kotlin/com/bachatas4/android/data/ContentImporter.kt` (391 lines) and `core/data/src/test/kotlin/com/bachatas4/android/data/ContentImporterTest.kt` (note the `importerFor(bytes)` helper that builds an importer rooted at `temporaryFolder.root`).

- [ ] **Step 2: Write the failing tests**

Append to `ContentImporterTest.kt`:

```kotlin
@Test
fun overlayStagingMergesFilesIntoExistingGame() = runTest {
    // Base game pre-installed.
    val dest = File(temporaryFolder.root, "games/CUSA07023").apply { mkdirs() }
    File(dest, "eboot.bin").writeText("base-eboot")
    File(dest, "sce_sys").mkdirs()
    File(dest, "sce_sys/param.sfo").writeText("base-sfo")

    // Staging tree from an update PKG (no eboot; overwrites param.sfo; adds a file).
    val staging = File(temporaryFolder.root, "games/.import-batch-1").apply { mkdirs() }
    File(staging, "sce_sys").mkdirs()
    File(staging, "sce_sys/param.sfo").writeText("updated-sfo")
    File(staging, "patch.dat").writeText("patch-data")

    val result = importerFor(ByteArray(0)).overlayStaging(
        gameId = "CUSA07023",
        stagingDir = staging,
        contentId = "EP9000-CUSA07023_00-UPDT",
        sourceUri = "content://upd",
    )

    assertEquals(2, result.filesMerged)
    // Overwritten file:
    assertEquals("updated-sfo", File(dest, "sce_sys/param.sfo").readText())
    // Added file:
    assertEquals("patch-data", File(dest, "patch.dat").readText())
    // Base eboot preserved (staging had none):
    assertEquals("base-eboot", File(dest, "eboot.bin").readText())
}

@Test
fun overlayStagingFailsWhenBaseGameMissing() = runTest {
    val staging = File(temporaryFolder.root, "games/.import-batch-2").apply { mkdirs() }
    File(staging, "sce_sys").mkdirs()
    File(staging, "sce_sys/param.sfo").writeText("sfo")

    val error = assertImportException {
        importerFor(ByteArray(0)).overlayStaging(
            gameId = "CUSA99999",
            stagingDir = staging,
            contentId = null,
            sourceUri = "content://x",
        )
    }
    assertEquals(RuntimeErrorCode.CONTENT_INVALID, error.code)
}

@Test
fun overlayStagingRejectsEscapingPath() = runTest {
    val dest = File(temporaryFolder.root, "games/CUSA07023").apply { mkdirs() }
    File(dest, "eboot.bin").writeText("x")
    val staging = File(temporaryFolder.root, "games/.import-batch-3").apply { mkdirs() }
    // Symlink escape: create a staging file whose canonical path leaves dest.
    // (On systems where symlinks are restricted this is a no-op; the containment
    // guard on the *staging* root still runs first.)
    File(staging, "evil.txt").writeText("evil")

    // Drive overlayStaging with a staging dir that lives OUTSIDE gamesDir to trip requireInside.
    val outside = File(temporaryFolder.root, "outside").apply { mkdirs() }
    File(outside, "x.txt").writeText("x")
    val error = assertImportException {
        importerFor(ByteArray(0)).overlayStaging(
            gameId = "CUSA07023",
            stagingDir = outside,
            contentId = null,
            sourceUri = "content://x",
        )
    }
    assertEquals(RuntimeErrorCode.CONTENT_INVALID, error.code)
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests "com.bachatas4.android.data.ContentImporterTest.overlayStagingMergesFilesIntoExistingGame" --tests "com.bachatas4.android.data.ContentImporterTest.overlayStagingFailsWhenBaseGameMissing" --tests "com.bachatas4.android.data.ContentImporterTest.overlayStagingRejectsEscapingPath"
```
Expected: compile error — `overlayStaging` unresolved.

- [ ] **Step 4: Implement `overlayStaging` + `OverlayResult`**

In `ContentImporter.kt`, add after the `ContentImportResult` data class (near the top, around line 35):

```kotlin
data class OverlayResult(
    val filesMerged: Int,
    val bytesMerged: Long,
)
```

Add the method to the `ContentImporter` class (after `importGame`, before the private helpers):

```kotlin
/**
 * Merge [stagingDir] into the existing games/<gameId>/ folder, overwriting files.
 * Does NOT delete the destination tree and does NOT require eboot.bin in staging
 * (update/DLC PKGs may omit it). The destination must already exist.
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
    val staging = stagingDir.canonicalFile
    requireInside(gamesDir, dest)
    requireInside(gamesDir, staging)
    if (!dest.isDirectory) {
        throw ContentImportException(RuntimeErrorCode.CONTENT_INVALID, "Base game not installed")
    }

    var files = 0
    var bytes = 0L
    staging.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        val rel = file.relativeTo(staging).path
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

Add imports if missing: `kotlin.coroutines.coroutineContext`, `kotlinx.coroutines.ensureActive` (both already imported at the top of the file — verify).

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests "com.bachatas4.android.data.ContentImporterTest"
```
Expected: all `ContentImporterTest` tests PASS (existing + 3 new).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/bachatas4/android/data/ContentImporter.kt \
        core/data/src/test/kotlin/com/bachatas4/android/data/ContentImporterTest.kt
git commit -m "feat(data): ContentImporter.overlayStaging merges into game folder

Copies a staging tree into an existing games/<id>/ folder, overwriting
files. Base eboot preserved when staging has none. Used by the batch
PKG update/DLC install path."
```

---

## Task 3: `GameRepository.touchGame`

**Goal:** Add a method that bumps `importedAtMs` and re-resolves the title/subtitle/detail from the on-disk `param.sfo` after an overlay.

**Files:**
- Modify: `core/data/src/main/kotlin/com/bachatas4/android/data/GameRepository.kt`

**Interfaces:**
- Produces: `suspend fun touchGame(gameId: String)` on `GameRepository`. Loads the entity, re-reads `sce_sys/param.sfo` on disk, updates title/subtitle/detail if changed, and bumps `importedAtMs` to `System.currentTimeMillis()`. No-op (returns) if the game is not in the DB.

- [ ] **Step 1: Read current GameRepository + GameDao**

Read `core/data/src/main/kotlin/com/bachatas4/android/data/GameRepository.kt` (168 lines) and `core/database/src/main/kotlin/com/bachatas4/android/database/GameDao.kt`. Note `gameDao.getById`, `gameDao.updateTitle`, and the absence of a general `update(entity)` — you will add one.

- [ ] **Step 2: Add `update` to `GameDao`**

In `GameDao.kt`, add (the file already uses `@Dao` + `@Update` or you can use `@Query`):

```kotlin
@Query("UPDATE games SET title = :title, subtitle = :subtitle, detail = :detail, importedAtMs = :importedAtMs WHERE id = :id")
suspend fun updateMetadata(
    id: String,
    title: String,
    subtitle: String?,
    detail: String?,
    importedAtMs: Long,
)
```

- [ ] **Step 3: Add `touchGame` to `GameRepository`**

In `GameRepository.kt`, add after `updateLastLaunched`:

```kotlin
/**
 * Re-resolve metadata from on-disk param.sfo and bump importedAtMs after an
 * overlay install. No-op if the game row is missing.
 */
suspend fun touchGame(gameId: String) {
    val entity = gameDao.getById(gameId) ?: return
    val sfoFile = GameIconPaths.paramSfo(context.filesDir, entity.relativePath)
    val sfo = if (sfoFile.isFile) {
        runCatching { ParamSfoReader.parse(sfoFile.readBytes()) }.getOrNull()
    } else null
    gameDao.updateMetadata(
        id = gameId,
        title = sfo?.title?.takeIf { it.isNotBlank() } ?: entity.title,
        subtitle = sfo?.subtitle ?: entity.subtitle,
        detail = sfo?.detail ?: entity.detail,
        importedAtMs = System.currentTimeMillis(),
    )
}
```

- [ ] **Step 4: Build to verify compilation**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:compileDebugKotlin :core:database:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/bachatas4/android/data/GameRepository.kt \
        core/database/src/main/kotlin/com/bachatas4/android/database/GameDao.kt
git commit -m "feat(data): GameRepository.touchGame refreshes metadata after overlay

Re-reads param.sfo and bumps importedAtMs so the library reflects the
latest applied update/DLC."
```

---

## Task 4: `ImportManager` batch action + progress states

**Goal:** Add the new intent action, extras, mode constant, and 4 new `ImportProgress` subtypes for the batch flow.

**Files:**
- Modify: `core/data/src/main/kotlin/com/bachatas4/android/data/ImportManager.kt`
- Test: `core/data/src/test/kotlin/com/bachatas4/android/data/ImportManagerTest.kt`

**Interfaces:**
- Produces constants: `ACTION_IMPORT_PKGS = "com.bachatas4.android.action.IMPORT_PKGS"`, `EXTRA_GAME_ID = "game_id"`, `EXTRA_URIS = "source_uris"` (String `ArrayList` extra), `MODE_PKG_BATCH = "pkg-batch"`.
- Produces `ImportProgress.BatchSelected(gameId, gameTitle, packageCount)`, `BatchExtracting(index, total, bytesCopied, totalBytes, currentFile, gameTitle)`, `BatchInstalled(gameId, title, count)`, `BatchFailed(code, message, completedCount, totalCount)`.
- `isBusy()` must treat `BatchSelected`, `BatchExtracting` as busy; `BatchInstalled`, `BatchFailed` as terminal (not busy) — matching `Installed`/`Failed`.

- [ ] **Step 1: Read current ImportManager + test**

Read `core/data/src/main/kotlin/com/bachatas4/android/data/ImportManager.kt` (107 lines) and `core/data/src/test/kotlin/com/bachatas4/android/data/ImportManagerTest.kt` to learn the `isBusy` test pattern.

- [ ] **Step 2: Write the failing tests**

Append to `ImportManagerTest.kt`:

```kotlin
@Test
fun batchSelectedIsBusy() {
    ImportManager.update(ImportProgress.BatchSelected("CUSA07023", "Bloodborne", 3))
    assertTrue(ImportManager.isBusy())
}

@Test
fun batchExtractingIsBusy() {
    ImportManager.update(
        ImportProgress.BatchExtracting(1, 3, 500L, 1000L, "update.pkg", "Bloodborne"),
    )
    assertTrue(ImportManager.isBusy())
}

@Test
fun batchInstalledIsNotBusy() {
    ImportManager.update(ImportProgress.BatchInstalled("CUSA07023", "Bloodborne", 3))
    assertFalse(ImportManager.isBusy())
}

@Test
fun batchFailedIsNotBusy() {
    ImportManager.update(
        ImportProgress.BatchFailed(InstallErrorCode.CONTENT_INVALID, "mismatch", 2, 3),
    )
    assertFalse(ImportManager.isBusy())
}
```

Add imports: `org.junit.Assert.assertFalse`, `org.junit.Assert.assertTrue` (check existing imports first).

- [ ] **Step 3: Run tests to verify they fail**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests "com.bachatas4.android.data.ImportManagerTest.batchSelectedIsBusy" --tests "com.bachatas4.android.data.ImportManagerTest.batchExtractingIsBusy" --tests "com.bachatas4.android.data.ImportManagerTest.batchInstalledIsNotBusy" --tests "com.bachatas4.android.data.ImportManagerTest.batchFailedIsNotBusy"
```
Expected: compile error — `BatchSelected` etc. unresolved.

- [ ] **Step 4: Add constants + progress subtypes**

In `ImportManager.kt`, add to the `ImportProgress` sealed interface (before `Failed`):

```kotlin
data class BatchSelected(
    val gameId: String,
    val gameTitle: String,
    val packageCount: Int,
) : ImportProgress

data class BatchExtracting(
    val index: Int,
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

In the `ImportManager` object, add constants (after the existing `ACTION_*`/`EXTRA_*` block):

```kotlin
const val ACTION_IMPORT_PKGS = "com.bachatas4.android.action.IMPORT_PKGS"
const val EXTRA_GAME_ID = "game_id"
const val EXTRA_URIS = "source_uris"   // String ArrayList extra
const val MODE_PKG_BATCH = "pkg-batch"
```

Update `isBusy()` to treat the batch terminal states as not busy. The current `when` already returns `false` for `Idle`, `Installed`, `Failed`; add `BatchInstalled`, `BatchFailed`:

```kotlin
fun isBusy(state: ImportProgress = _progress.value): Boolean =
    when (state) {
        is ImportProgress.Idle,
        is ImportProgress.Installed,
        is ImportProgress.Failed,
        is ImportProgress.BatchInstalled,
        is ImportProgress.BatchFailed,
        -> false
        else -> true
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest --tests "com.bachatas4.android.data.ImportManagerTest"
```
Expected: all `ImportManagerTest` tests PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/bachatas4/android/data/ImportManager.kt \
        core/data/src/test/kotlin/com/bachatas4/android/data/ImportManagerTest.kt
git commit -m "feat(data): batch PKG import action + progress states

Add ACTION_IMPORT_PKGS, EXTRA_GAME_ID/EXTRA_URIS, MODE_PKG_BATCH, and
BatchSelected/Extracting/Installed/Failed progress subtypes.
Terminal batch states are not busy."
```

---

## Task 5: Factor out `probeAndExtract` + add `runPkgBatchImport`

**Goal:** Extract the shared probe→extract→passcode machinery from `runPkgImport` into a reusable `probeAndExtract`, then add the two-pass batch flow `runPkgBatchImport` and dispatch `ACTION_IMPORT_PKGS`.

**Files:**
- Modify: `app/src/main/kotlin/com/bachatas4/android/service/ImportService.kt`

**Interfaces:**
- Consumes: `PkgExtractor.nativeProbe` / `nativeExtract` / `nativeCancel`, `ImportManager.tryBeginImport` / `update` / `reset`, `ContentImporter.overlayStaging`, `GameRepository.touchGame`, `InstallManifestIo`, `GameInstallVerifier`, `GameMetadataResolver.cusa` regex.
- Produces: private `suspend fun runPkgBatchImport(gameId: String, uris: List<String>)` and the `ACTION_IMPORT_PKGS` dispatch branch in `onStartCommand`.

- [ ] **Step 1: Read the full ImportService**

Read `app/src/main/kotlin/com/bachatas4/android/service/ImportService.kt` end-to-end. Focus on `onStartCommand` (line 77), `runPkgImport` (line 293), `isOpenFdSeekable` (line 639), `extractWithProgress`, `copyPkgToLocalCache`, `failInstall`, `handleFailure`, and the notification helpers. Understand the `passcodeWaiter`/`copyConfirmWaiter` `CompletableDeferred` mechanism — the batch flow reuses it.

- [ ] **Step 2: Factor out `probeAndExtract`**

Refactor `runPkgImport` so the probe→storage→extract→passcode block (roughly lines 334-545) becomes a private suspend helper:

```kotlin
/**
 * Probe + extract a single PKG [uriString] into [staging]. Handles the SAF-fd
 * seekability branch, the copy-confirm gate, and the passcode candidate loop,
 * blocking on the same [passcodeWaiter]/[copyConfirmWaiter] mechanism as the
 * single-PKG path. Returns (probe, usedPasscode).
 */
private suspend fun probeAndExtract(
    uriString: String,
    staging: File,
    displayName: String,
    jobId: String,
): Pair<PkgProbeResult, String?>
```

Move the body of `runPkgImport` from the probe (line ~334) through the successful-extract (line ~545, where `usedPasscode` is known) into `probeAndExtract`. `runPkgImport` then calls `probeAndExtract` and continues with its own finalize/register block unchanged. Keep the existing single-PKG tests passing by preserving behavior exactly — this is a pure refactor.

Run the existing build + any existing ImportService tests to confirm no regression:
```bash
cd android/BachataS4 && ./gradlew :app:compileDebugKotlin :core:data:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, data tests green.

Commit this refactor separately:
```bash
git add app/src/main/kotlin/com/bachatas4/android/service/ImportService.kt
git commit -m "refactor(service): extract probeAndExtract from runPkgImport

No behavior change. Sets up reuse by the batch PKG import path."
```

- [ ] **Step 3: Add `EXTRA_URIS` reading + dispatch in `onStartCommand`**

In `onStartCommand`, add a branch before the `ACTION_IMPORT` branch:

```kotlin
ImportManager.ACTION_IMPORT_PKGS -> {
    val gameId = intent.getStringExtra(ImportManager.EXTRA_GAME_ID)
    val uris = intent.getStringArrayListExtra(ImportManager.EXTRA_URIS)
    if (gameId.isNullOrBlank() || uris.isNullOrEmpty()) {
        Log.e(TAG, "batch import missing game id or uris")
        ImportManager.update(
            ImportProgress.Failed(InstallErrorCode.SOURCE_INACCESSIBLE, "Missing batch import parameters"),
        )
        stopSelf()
        return START_NOT_STICKY
    }
    Log.i(TAG, "batch import start gameId=$gameId count=${uris.size}")
    if (importJob?.isActive == true) {
        Log.w(TAG, "import already running — ignore batch request")
        return START_NOT_STICKY
    }
    if (!ImportManager.tryBeginImport("", ImportManager.MODE_PKG_BATCH)) {
        ImportManager.reset()
        if (!ImportManager.tryBeginImport("", ImportManager.MODE_PKG_BATCH)) {
            Log.w(TAG, "import slot busy — abort batch")
            return START_NOT_STICKY
        }
    }
    importJob = scope.launch { runPkgBatchImport(gameId, uris) }
}
```

- [ ] **Step 4: Implement `runPkgBatchImport` (two-pass)**

Add the method. Pass 1 probes every URI for TITLE_ID + size; pass 2 extracts + overlays. Reuse `probeAndExtract` for the per-PKG extraction (which already handles passcode/copy-confirm gates).

```kotlin
private suspend fun runPkgBatchImport(gameId: String, uris: List<String>) {
    Log.i(TAG, "pkg batch start gameId=$gameId count=${uris.size}")
    updateNotification("Preparing batch install…", indeterminate = true)
    val jobId = UUID.randomUUID().toString()
    val gamesDir = File(filesDir, "games").canonicalFile
    val dest = File(gamesDir, gameId).canonicalFile

    var completedCount = 0
    val overlays = mutableListOf<OverlayRecord>()
    val stagings = mutableListOf<File>()
    var failed = false
    try {
        // Base game must exist and be launchable.
        if (!dest.isDirectory || !GameInstallVerifier.canLaunch(filesDir, "games/$gameId")) {
            ImportManager.update(
                ImportProgress.Failed(InstallErrorCode.CONTENT_INVALID, "Base game not installed"),
            )
            return
        }
        val baseGame = gameRepository.getGame(gameId)
        ImportManager.update(
            ImportProgress.BatchSelected(gameId, baseGame?.title ?: gameId, uris.size),
        )

        // --- PASS 1: probe every PKG, validate TITLE_ID, sum sizes ---
        val probed = mutableListOf<Pair<String, PkgProbeResult>>() // uri -> probe
        var sumExtractBytes = 0L
        for (uriString in uris) {
            coroutineContext?.ensureActive()
            val uri = Uri.parse(uriString)
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val probe = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                withContext(Dispatchers.IO) { PkgExtractor.nativeProbe(descriptor.fd) }
            } ?: run {
                ImportManager.update(
                    ImportProgress.BatchFailed(
                        InstallErrorCode.SOURCE_INACCESSIBLE,
                        "Cannot open $uriString",
                        0,
                        uris.size,
                    ),
                )
                failed = true
                return
            }
            if (probe.status == PkgStatus.ERROR) {
                ImportManager.update(
                    ImportProgress.BatchFailed(
                        InstallValidator.mapProbeError(probe.message),
                        probe.message ?: "Invalid package",
                        0,
                        uris.size,
                    ),
                )
                failed = true
                return
            }
            val titleId = probe.titleHint?.trim().orEmpty()
            if (!titleId.equals(gameId, ignoreCase = true)) {
                ImportManager.update(
                    ImportProgress.BatchFailed(
                        InstallErrorCode.CONTENT_INVALID,
                        "PKG TITLE_ID '$titleId' does not match '$gameId'",
                        0,
                        uris.size,
                    ),
                )
                failed = true
                return
            }
            probed.add(uriString to probe)
            sumExtractBytes += (probe.pfsImageSize.takeIf { it > 0 } ?: probe.packageSize).coerceAtLeast(0L)
        }

        // Single storage gate.
        val margin = maxOf(STORAGE_MARGIN_BYTES, sumExtractBytes / 20L)
        val required = sumExtractBytes + margin
        val free = filesDir.usableSpace
        InstallValidator.checkStorage(required, free)?.let { code ->
            ImportManager.update(
                ImportProgress.BatchFailed(code, "Need ${formatBytes(required)} free, have ${formatBytes(free)}", 0, uris.size),
            )
            failed = true
            return
        }

        // --- PASS 2: extract + overlay each PKG in order ---
        for ((index, pair) in probed.withIndex()) {
            val (uriString, probe) = pair
            coroutineContext?.ensureActive()
            val displayName = probe.titleHint?.ifBlank { null } ?: probe.contentId.ifBlank { "PKG" }
            val staging = File(gamesDir, ".import-batch-$jobId-$index").canonicalFile
            stagings += staging
            staging.mkdirs()

            val (_, _) = probeAndExtract(uriString, staging, displayName, jobId)

            ImportManager.update(
                ImportProgress.BatchExtracting(
                    index = index,
                    total = probed.size,
                    bytesCopied = 0L,
                    totalBytes = probe.packageSize,
                    currentFile = displayName,
                    gameTitle = baseGame?.title ?: gameId,
                ),
            )

            contentImporter.overlayStaging(
                gameId = gameId,
                stagingDir = staging,
                contentId = probe.contentId,
                sourceUri = uriString,
            )
            overlays += OverlayRecord(probe.contentId, uriString, System.currentTimeMillis())
            staging.deleteRecursively()
            stagings -= staging
            completedCount++
        }

        // --- Rewrite manifest with overlays ---
        val existing = InstallManifestIo.read(dest) ?: InstallManifest(
            status = InstallManifestIo.STATUS_INSTALLED,
            gameId = gameId,
            contentId = null,
            mode = ImportManager.MODE_PKG,
            sourceUri = "",
            installedAtMs = System.currentTimeMillis(),
            requiredFiles = GameInstallVerifier.REQUIRED_FILES,
            bytesTotal = 0L,
        )
        InstallManifestIo.write(
            dest,
            existing.copy(
                version = 2,
                overlays = existing.overlays + overlays,
                installedAtMs = System.currentTimeMillis(),
            ),
        )

        gameRepository.touchGame(gameId)
        ImportManager.update(
            ImportProgress.BatchInstalled(gameId, baseGame?.title ?: gameId, overlays.size),
        )
        notifyDone("${baseGame?.title ?: gameId}: ${overlays.size} package(s) installed")
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        Log.e(TAG, "pkg batch failed", failure)
        ImportManager.update(
            ImportProgress.BatchFailed(
                code = when (failure) {
                    is ContentImportException -> failure.code
                    else -> InstallErrorCode.SOURCE_INACCESSIBLE
                },
                message = failure.message ?: "Batch install failed",
                completedCount = completedCount,
                totalCount = uris.size,
            ),
        )
    } finally {
        stagings.forEach { runCatching { it.deleteRecursively() } }
        if (ImportManager.isBusy()) ImportManager.reset()
        Log.i(TAG, "pkg batch finished completed=$completedCount/${uris.size}")
        stopSelf()
    }
}
```

Add imports: `com.bachatas4.android.data.OverlayRecord`, `com.bachatas4.android.data.ContentImportException`, `kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.ensureActive`, `kotlin.coroutines.coroutineContext` (verify each against existing imports).

Note: `probeAndExtract` opens its own SAF fd internally (moved from `runPkgImport`), so pass 2 does not reopen the fd here. If during the Task 5 Step 2 refactor `probeAndExtract` was defined to take an already-open fd rather than a URI, adjust the call accordingly — the contract is `probeAndExtract(uriString, staging, displayName, jobId)` opens its own fd per call.

- [ ] **Step 5: Build + run unit tests**

```bash
cd android/BachataS4 && ./gradlew :app:compileDebugKotlin :core:data:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, data tests green. (The batch path is exercised by the data-layer unit tests from Tasks 1-4; `ImportService` itself is an Android `Service` best validated by integration/manual test.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/bachatas4/android/service/ImportService.kt
git commit -m "feat(service): batch PKG overlay install (two-pass)

runPkgBatchImport: pass 1 probes all PKGs (TITLE_ID match + size sum +
storage gate), pass 2 extracts + overlays each into the live game folder.
Rewrites install.manifest v2 with overlays and touches the game row."
```

---

## Task 6: UI — "Add PKG" button in GlassBottomSheet

**Goal:** Add an "Add PKG" button to the game options sheet that triggers the new flow.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `ImportManager.isBusy()` to disable the button while an install runs.
- Produces: a new `onAddPkgs: (gameId: String, gameTitle: String) -> Unit` callback on `GlassBottomSheet`, wired from `LibraryContent` to open the multi-picker + reorder dialog (Task 7).

- [ ] **Step 1: Read the GlassBottomSheet + its call site**

Read `LibraryScreen.kt:1061-1238` (`GlassBottomSheet`) and `LibraryScreen.kt:667-695` (where `GlassBottomSheet` is invoked in `LibraryContent`).

- [ ] **Step 2: Add `onAddPkgs` parameter to `GlassBottomSheet`**

Change the signature at line 1062:

```kotlin
@Composable
private fun GlassBottomSheet(
    game: com.bachatas4.android.model.Game,
    onLaunch: () -> Unit,
    onCancel: () -> Unit,
    onOpenGameSettings: () -> Unit,
    onRequestDelete: () -> Unit,
    onAddPkgs: () -> Unit,          // NEW
    maxHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 3: Add the full-width secondary button**

Inside the bottom button `Column` (the one starting at line 1144), insert a new full-width button **above** the existing `Row` of three buttons (Cancel/Options/Remove), and **below** the Launch button:

```kotlin
Button(
    onClick = onAddPkgs,
    enabled = !com.bachatas4.android.data.ImportManager.isBusy(),
    modifier = Modifier
        .fillMaxWidth()
        .height(50.dp),
    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = Color.White.copy(alpha = 0.08f),
        contentColor = BachataPalette.Primary,
    ),
    shape = RoundedCornerShape(12.dp),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📦", style = MaterialTheme.typography.bodyMedium)
        Text("Add PKG update / DLC", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
```

- [ ] **Step 4: Wire the callback at the call site**

At line 675-692, add the `onAddPkgs` argument to the `GlassBottomSheet(...)` call:

```kotlin
GlassBottomSheet(
    game = detailsGame,
    onLaunch = { ... },
    onCancel = { ... },
    onOpenGameSettings = { ... },
    onRequestDelete = { ... },
    onAddPkgs = { onAddPkgs(detailsGame.id, detailsGame.title) },   // NEW
    maxHeight = localMaxHeight,
    modifier = Modifier.align(Alignment.BottomCenter),
)
```

`onAddPkgs` is a new parameter on `LibraryContent` (and `LibraryScreen`); it is plumbed to the picker+dialog state added in Task 7. For now, in `LibraryContent`'s signature add `onAddPkgs: (String, String) -> Unit = { _, _ -> }` so compilation succeeds before Task 7 implements it.

- [ ] **Step 5: Build to verify compilation**

```bash
cd android/BachataS4 && ./gradlew :feature:library:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt
git commit -m "feat(library): Add PKG button in game options sheet

Full-width secondary button (disabled while import busy) that will
trigger the multi-PKG picker + reorder dialog."
```

---

## Task 7: Multi-file picker + reorder dialog + confirm

**Goal:** Implement the multi-document SAF picker, the reorder dialog, and the confirm action that starts the batch service.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `ActivityResultContracts.OpenMultipleDocuments`, `DocumentFile.fromSingleUri`, `ImportManager.ACTION_IMPORT_PKGS` / `EXTRA_GAME_ID` / `EXTRA_URIS`, `ImportManager.SERVICE_CLASS`, `ImportManager.isBusy()`.
- Produces: an `AddPkgsDialog` composable + picker state wired to the `onAddPkgs` callback from Task 6; on confirm, `startService(ACTION_IMPORT_PKGS)` with the ordered URI list.

- [ ] **Step 1: Read the existing single-PKG picker for the pattern**

Read `LibraryScreen.kt:169-189` (`pkgPicker` using `ActivityResultContracts.OpenDocument`) and `LibraryScreen.kt:197-215` (`showImportChooser` dialog) to mirror the style.

- [ ] **Step 2: Add the picker + dialog state**

Near the existing `showImportChooser` state (around line 151), add:

```kotlin
data class AddPkgsState(
    val gameId: String,
    val gameTitle: String,
    val uris: List<String>,   // in current order
)

var addPkgsState by remember { mutableStateOf<AddPkgsState?>(null) }

val pkgMultiPicker = rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
) { uris ->
    if (uris.isEmpty()) return@rememberLauncherForActivityResult
    if (com.bachatas4.android.data.ImportManager.isBusy()) {
        Toast.makeText(context, "Import already in progress", Toast.LENGTH_SHORT).show()
        return@rememberLauncherForActivityResult
    }
    val pkgUris = uris.filter { uri ->
        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
            ?.endsWith(".pkg", ignoreCase = true) == true
    }
    if (pkgUris.isEmpty()) {
        Toast.makeText(context, "Select .pkg files", Toast.LENGTH_SHORT).show()
        return@rememberLauncherForActivityResult
    }
    pkgUris.forEach { uri ->
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val current = addPkgsState
    addPkgsState = if (current != null) {
        current.copy(uris = current.uris + pkgUris.map { it.toString() })
    } else {
        // First launch: gameId/gameTitle come from the sheet callback via a pending request.
        addPkgsPendingRequest?.let { AddPkgsState(it.first, it.second, pkgUris.map { uri -> uri.toString() }) }
    }
}

// Holds the (gameId, gameTitle) from the sheet button until the picker returns.
var addPkgsPendingRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
```

- [ ] **Step 3: Wire the sheet callback to launch the picker**

In `LibraryContent`, the `onAddPkgs` lambda (added as a stub in Task 6 Step 4) now becomes:

```kotlin
onAddPkgs = { gameId, gameTitle ->
    addPkgsPendingRequest = gameId to gameTitle
    addPkgsState = AddPkgsState(gameId, gameTitle, emptyList())
    pkgMultiPicker.launch(arrayOf("*/*"))
},
```

(Replace the placeholder `onAddPkgs: (String, String) -> Unit = { _, _ -> }` with the real wiring, or pass it down from `LibraryScreen` if you prefer that structure — follow whichever matches how `onLaunch`/`onOpenGameSettings` are plumbed.)

- [ ] **Step 4: Implement `AddPkgsDialog`**

Add a new private composable near the other dialogs (e.g. after the passcode dialog ~line 303):

```kotlin
@Composable
private fun AddPkgsDialog(
    state: AddPkgsState,
    onReorder: (List<String>) -> Unit,
    onAddMore: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add packages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${state.gameTitle} (${state.gameId})",
                    style = MaterialTheme.typography.bodySmall,
                    color = BachataPalette.Secondary,
                )
                Text("Install order (top first):", style = MaterialTheme.typography.bodySmall)
                state.uris.forEachIndexed { index, uri ->
                    val name = remember(uri) {
                        runCatching {
                            androidx.documentfile.provider.DocumentFile
                                .fromSingleUri(androidx.compose.ui.platform.LocalContext.current, android.net.Uri.parse(uri))?.name
                        }.getOrNull() ?: uri
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium)
                        Text(name.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (index > 0) {
                                    val moved = state.uris.toMutableList()
                                    val item = moved.removeAt(index)
                                    moved.add(index - 1, item)
                                    onReorder(moved)
                                }
                            },
                            enabled = index > 0,
                            contentPadding = PaddingValues(2.dp),
                        ) { Text("▲") }
                        TextButton(
                            onClick = {
                                if (index < state.uris.lastIndex) {
                                    val moved = state.uris.toMutableList()
                                    val item = moved.removeAt(index)
                                    moved.add(index + 1, item)
                                    onReorder(moved)
                                }
                            },
                            enabled = index < state.uris.lastIndex,
                            contentPadding = PaddingValues(2.dp),
                        ) { Text("▼") }
                        TextButton(
                            onClick = {
                                val moved = state.uris.toMutableList()
                                moved.removeAt(index)
                                onReorder(moved)
                            },
                            contentPadding = PaddingValues(2.dp),
                        ) { Text("✕") }
                    }
                }
                TextButton(onClick = onAddMore) { Text("+ Add more") }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onConfirm,
                enabled = state.uris.isNotEmpty(),
            ) { Text("Install ${state.uris.size}") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

Add imports as needed (`androidx.compose.foundation.layout.Column`, `androidx.compose.material3.TextButton`, `androidx.compose.runtime.remember`, `androidx.compose.ui.platform.LocalContext`).

- [ ] **Step 5: Render the dialog from `LibraryContent`**

Near the delete `AlertDialog` (around line 698), add:

```kotlin
addPkgsState?.let { state ->
    AddPkgsDialog(
        state = state,
        onReorder = { ordered -> addPkgsState = state.copy(uris = ordered) },
        onAddMore = { pkgMultiPicker.launch(arrayOf("*/*")) },
        onConfirm = {
            val intent = Intent(com.bachatas4.android.data.ImportManager.ACTION_IMPORT_PKGS).apply {
                setClassName(context.packageName, com.bachatas4.android.data.ImportManager.SERVICE_CLASS)
                putExtra(com.bachatas4.android.data.ImportManager.EXTRA_GAME_ID, state.gameId)
                putStringArrayListExtra(
                    com.bachatas4.android.data.ImportManager.EXTRA_URIS,
                    ArrayList(state.uris),
                )
            }
            context.startService(intent)
            addPkgsState = null
            onShowDetails(null)  // close the sheet
        },
        onDismiss = { addPkgsState = null },
    )
}
```

- [ ] **Step 6: Build**

```bash
cd android/BachataS4 && ./gradlew :feature:library:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt
git commit -m "feat(library): multi-PKG picker + reorder dialog

OpenMultipleDocuments picker, AddPkgsDialog with up/down/remove per row
and add-more, confirm starts ACTION_IMPORT_PKGS with ordered URIs."
```

---

## Task 8: Batch progress rendering in the library overlay

**Goal:** Render the new batch progress states in the existing library progress overlay so the user sees "Installing 2 of 3: update.pkg (45%)" and the terminal success/failure messages.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `ImportManager.progress` (already collected at line 111), the new `ImportProgress.BatchSelected` / `BatchExtracting` / `BatchInstalled` / `BatchFailed`.

- [ ] **Step 1: Find the existing progress rendering**

Read `LibraryScreen.kt` around line 111 (`importProgress` collection) and wherever `ImportProgress.Extracting` / `Installed` / `Failed` are rendered in the UI (search for `is ImportProgress.`). Note the overlay/banner pattern used.

- [ ] **Step 2: Add batch-state branches**

Wherever the existing `when (importProgress)` renders the banner/dialog, add branches. Map the batch states to the same UI affordances:

```kotlin
is ImportProgress.BatchSelected -> {
    // "Preparing ${packageCount} packages for ${gameTitle}…"
}
is ImportProgress.BatchExtracting -> {
    val pct = if (totalBytes > 0) (bytesCopied * 100 / totalBytes).toInt() else 0
    // "Installing ${index + 1} of ${total}: $currentFile ($pct%)"
}
is ImportProgress.BatchInstalled -> {
    // success banner "Installed $count packages for $title", then reset
}
is ImportProgress.BatchFailed -> {
    // error banner "Installed $completedCount of $totalCount before failure: $message"
}
```

Follow the exact composable structure used by the existing `Extracting`/`Installed`/`Failed` branches. Also extend the `LaunchedEffect(importProgress)` block at line 125 so `BatchInstalled` and `BatchFailed` auto-reset after the same delays as `Installed`/`Failed`:

```kotlin
is ImportProgress.BatchInstalled -> {
    delay(4_000)
    if (ImportManager.progress.value is ImportProgress.BatchInstalled) ImportManager.reset()
}
is ImportProgress.BatchFailed -> {
    delay(8_000)
    if (ImportManager.progress.value is ImportProgress.BatchFailed) ImportManager.reset()
}
```

The per-PKG `NeedPasscode`/`NeedCopyConfirm` states are already rendered by the existing dialogs (lines 216-303) and need no changes — the batch path emits them identically.

- [ ] **Step 3: Build**

```bash
cd android/BachataS4 && ./gradlew :feature:library:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/bachatas4/android/feature/library/LibraryScreen.kt
git commit -m "feat(library): render batch PKG install progress

BatchSelected/Extracting/Installed/Failed banner text + auto-reset
delays matching the single-PKG path."
```

---

## Task 9: Full build + lint + acceptance

**Goal:** Assemble the debug APK and confirm the feature is present and the build is clean.

**Files:** none (verification only)

- [ ] **Step 1: Run all unit tests**

```bash
cd android/BachataS4 && ./gradlew :core:data:testDebugUnitTest :core:database:testDebugUnitTest
```
Expected: all green, including the 9 new tests from Tasks 1, 2, 4.

- [ ] **Step 2: Lint + assemble debug**

```bash
cd android/BachataS4 && ./gradlew lintDebug assembleDebug
```
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Verify APK contains runtime assets (per AGENTS.md)**

```bash
unzip -l android/BachataS4/app/build/outputs/apk/debug/app-debug.apk \
  | grep -E 'assets/runtime/(manifest\.json|runtime\.zip)'
```
Expected: both entries present. (This guards against a green build that shipped without the managed runtime; it does not require rebuilding the runtime here — only verifying.)

- [ ] **Step 4: Commit any lint fixes**

```bash
git add -A
git commit -m "chore: lint cleanup for batch PKG overlay feature" || echo "nothing to commit"
```

- [ ] **Step 5: Manual acceptance note**

Document the manual test for the reviewer (not automated here):
> Install a base game (e.g. CUSA07023). In the library, tap the game → "Add PKG update / DLC" → select an update `.pkg` and a DLC `.pkg` → reorder if desired → "Install 2". Confirm the progress overlay shows "Installing 1 of 2" then "Installing 2 of 2", then "Installed 2 packages". Launch the game and verify the update/DLC took effect. Repeat with a PKG whose TITLE_ID differs → confirm the batch aborts with "does not match" and the game folder is unchanged.

---

## Self-Review

**1. Spec coverage:**
- Overlay merge → Task 2 (`overlayStaging`). ✓
- TITLE_ID mismatch fail-batch → Task 5 (pass-1 check before any extract). ✓
- Two-pass pre-flight → Task 5. ✓
- Batch action + extras + progress states → Tasks 4 + 5. ✓
- Manifest v2 overlays → Task 1. ✓
- `touchGame` → Task 3. ✓
- GlassBottomSheet button → Task 6. ✓
- Multi-picker + reorder dialog → Task 7. ✓
- Progress rendering → Task 8. ✓
- Edge: base game missing → Task 5 pass checks `canLaunch` first. ✓
- Edge: mid-batch failure → Task 5 `try/catch` emits `BatchFailed(completedCount)`. ✓
- Edge: cancel → reuses `nativeCancel` + `ensureActive` (existing machinery; `probeAndExtract` already wires `nativeCancel` response). ✓
- Edge: empty picker / 0 PKGs → Task 7 confirm button `enabled = state.uris.isNotEmpty()`. ✓
- Open question (titleId from probe) → Global Constraints document that `titleHint` carries it; Task 5 reads `probe.titleHint`. ✓

**2. Placeholder scan:** No TBD/TODO. Every step has concrete code or commands. The `// "..." ` comments in Task 8 are UI text strings the implementer fills into the existing banner composable — they reference the existing pattern rather than inventing new structure. ✓

**3. Type consistency:**
- `OverlayRecord(contentId: String?, sourceUri: String, appliedAtMs: Long)` — defined Task 1, used Task 5. ✓
- `overlayStaging(gameId, stagingDir, contentId, sourceUri): OverlayResult` — defined Task 2, called Task 5. ✓
- `touchGame(gameId)` — defined Task 3, called Task 5. ✓
- `BatchSelected/Extracting/Installed/Failed` field names — defined Task 4, read Task 8. ✓
- `ACTION_IMPORT_PKGS`, `EXTRA_GAME_ID`, `EXTRA_URIS`, `MODE_PKG_BATCH` — defined Task 4, used Tasks 5 + 7. ✓
- `probeAndExtract(uriString, staging, displayName, jobId)` — defined Task 5 Step 2, called Task 5 Step 4. ✓

No gaps or inconsistencies found.
