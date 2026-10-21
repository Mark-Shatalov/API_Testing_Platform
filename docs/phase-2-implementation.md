# Phase 2 — local storage (steps 7–9)

Requirements source: Phase 2 of `API_Testing_Platform_Development_Prompts.docx`. Existing Phase 1 work and the user's earlier report were preserved. No commit or push was requested or performed.

## Delivered

### Step 7: SQLite schema and migrations

- Stable UUIDs and shared modification/version metadata for workspace, collection, one-level folder and request records. Foreign keys and cascading deletion maintain references.
- Schema foundation for environments, variables, assertions, runs, request results and assertion results, each with modification timestamps.
- Versioned JSON TEXT request/draft payloads. Complete payload encryption via a minimal current-user DPAPI adapter is included now because recovery snapshots must not record secrets in plaintext. Authentication helpers and environment variable resolution remain later-phase work.
- WAL, foreign keys, full synchronization, busy timeout, one bounded serialized database queue and short transactions. JSON encoding and encryption happen before write transactions.
- Ordered, line-ending-normalized, SHA-256-checksummed migrations and schema version validation. Consistent `VACUUM INTO` backups before incompatible migrations; transactional rollback on failure.
- LOCALAPPDATA data directory and an exclusive application lock. No database work on the JavaFX thread.

### Step 8: navigation and explicit saving

- Workspace selector and populated collection/folder/request tree; create, rename, sibling reorder and confirmed recursive deletion.
- Saved requests open in independent closable tabs; scratch tabs, Save, Save As and Ctrl+S.
- Immutable editable snapshots retain every setting, disabled row, incomplete input, raw content and inactive body-mode drafts. Multipart stores paths, not file contents.
- Stable request identity and collection ancestry, optimistic save versions, dirty markers and preservation of newer edits during a save.
- Storage failures leave editor content dirty and available. Per-tab close offers Save / Discard / Cancel; window close asks before discarding dirty tabs.

### Step 9: autosave and crash recovery

- 750 ms per-tab debounce and bounded scheduling; no write on every keystroke. The latest immutable draft is stored, not just its first edit.
- Current-user encrypted snapshots, visible recovery-save state and an explicit retry action after failed writes.
- Startup recovery prompt plus Recover tabs action, stable recovered tab identities, idempotent session claims and no automatic overwrite of saved requests.
- Pending debounce timers are cancelled before cleanup. Successful explicit saves/normal close remove temporary snapshots; failures keep content available and allow retry. Unclaimed recovery snapshots remain on disk.
- Crash recovery preserves the latest committed snapshot. Edits in the final debounce window can be absent after an abrupt exit.

## Verification

Verified on this Windows desktop with Java 25.0.4 and the repository Maven Wrapper:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
.\mvnw.cmd --offline --no-transfer-progress clean verify
```

The automated checks exercise temporary SQLite databases, real Windows DPAPI and real JavaFX windows. Coverage includes:

- CRUD, hierarchy constraints, sibling ordering, cascade deletion, concurrent queued writes and stale-save rejection.
- Every editor setting across close/restart, plus encrypted-at-rest payloads.
- Migration success, consistent pre-migration backup, simulated SQL failure/rollback, checksum drift and concurrent-instance refusal.
- Rapid-edit debounce, failed autosave/retry, cancellation of pending snapshots, pause/resume and cleanup ordering.
- Real HTTP Send from a stored request, explicit save, tab close and reopen after restart.
- Simulated abnormal exit, recovery of the final rapid edit and invalid timeout input, unchanged saved request, idempotent recovery, normal-close Cancel and Discard behavior.
- Preservation of dirty UI content after a failed save.
- Regression checks for all Phase 1 HTTP and response-viewer functionality.

Result: **BUILD SUCCESS** across all 10 reactor projects; **81 tests passed**, zero failures, errors or skipped tests. Counts: domain 5, application 7, http-client 30, persistence-sqlite 8, security-windows 2, ui 19, app 10. `git diff --check` also passed.

Process termination/power loss was simulated by closing without normal session cleanup; a physical power-cut test was not performed. Cross-account DPAPI recovery and installer packaging were not tested. Normal DPAPI round trip and rejection of damaged ciphertext were tested on Windows.

## Manual check

1. Create a collection, optionally a folder, and a request. Double-click the request.
2. Edit URL, method, rows, Body and Settings. Send it, then Save.
3. Close its tab, reopen it, then close/restart the app and reopen it again. Settings should match.
4. Make an unsaved change and wait for `Recovery snapshot saved`. After an abnormal exit, choose Recover at next startup. The unsaved draft should reappear without changing the saved version until Save is explicitly pressed.
5. For a normal close, Cancel must retain edits; choosing Discard must remove that session's temporary snapshots. Do not terminate the process while editing anything you cannot afford to lose.

## Changed files for this phase

Build and wiring:

- `domain/src/main/java/module-info.java`
- `application/src/main/java/module-info.java`
- `persistence-sqlite/pom.xml`
- `persistence-sqlite/src/main/java/module-info.java`
- `security-windows/pom.xml`
- `security-windows/src/main/java/module-info.java`
- `app/pom.xml`
- `app/src/main/java/dev/apitestingplatform/app/ApiTestingPlatformApplication.java`

Domain/application:

- `domain/src/main/java/dev/apitestingplatform/domain/storage/LibraryItem.java`
- `domain/src/main/java/dev/apitestingplatform/domain/storage/RequestDraft.java`
- `domain/src/main/java/dev/apitestingplatform/domain/storage/SavedRequest.java`
- `domain/src/main/java/dev/apitestingplatform/domain/storage/RecoveryDraft.java`
- `domain/src/test/java/dev/apitestingplatform/domain/storage/StorageModelsTest.java`
- `application/src/main/java/dev/apitestingplatform/application/storage/LibraryRepository.java`
- `application/src/main/java/dev/apitestingplatform/application/storage/PayloadProtector.java`
- `application/src/main/java/dev/apitestingplatform/application/storage/StorageException.java`
- `application/src/main/java/dev/apitestingplatform/application/storage/DraftAutosave.java`
- `application/src/test/java/dev/apitestingplatform/application/storage/DraftAutosaveTest.java`

Infrastructure:

- `persistence-sqlite/src/main/resources/dev/apitestingplatform/persistence/sqlite/V001__local_storage.sql`
- `persistence-sqlite/src/main/java/dev/apitestingplatform/persistence/sqlite/Migration.java`
- `persistence-sqlite/src/main/java/dev/apitestingplatform/persistence/sqlite/Migrations.java`
- `persistence-sqlite/src/main/java/dev/apitestingplatform/persistence/sqlite/ProtectedDraftCodec.java`
- `persistence-sqlite/src/main/java/dev/apitestingplatform/persistence/sqlite/SqliteLibraryRepository.java`
- `persistence-sqlite/src/main/java/dev/apitestingplatform/persistence/sqlite/DataDirectory.java`
- `persistence-sqlite/src/test/java/dev/apitestingplatform/persistence/sqlite/SqliteLibraryRepositoryTest.java`
- `security-windows/src/main/java/dev/apitestingplatform/security/windows/DpapiPayloadProtector.java`
- `security-windows/src/test/java/dev/apitestingplatform/security/windows/DpapiPayloadProtectorTest.java`

UI/tests/documentation:

- `ui/src/main/java/dev/apitestingplatform/ui/controller/LibraryWindowController.java`
- `ui/src/main/java/dev/apitestingplatform/ui/controller/MainWindowController.java`
- `ui/src/main/java/dev/apitestingplatform/ui/viewmodel/RequestEditorViewModel.java`
- `ui/src/main/resources/dev/apitestingplatform/ui/main.css`
- `ui/src/test/java/dev/apitestingplatform/ui/viewmodel/RequestEditorViewModelTest.java`
- `app/src/test/java/dev/apitestingplatform/app/FxTestSupport.java`
- `app/src/test/java/dev/apitestingplatform/app/LibraryIntegrationTest.java`
- `app/src/test/java/dev/apitestingplatform/app/ResponseDebuggingIntegrationTest.java`
- `README.md`
- `docs/phase-2-implementation.md`

## Boundaries

Environment/variable editors, authentication helpers, assertion execution, collection runs, history UI, portable import/export and installer creation are not implemented here. Request payload protection is not a whole-database encryption feature: ordinary names, ordering and timestamps remain readable metadata. Existing Phase 1 files remain uncommitted alongside these changes; no reset or unrelated cleanup was performed.

## Follow-up: Folder / Request creation buttons

Reproduced the missing-selection scenario through a real JavaFX regression test: creating a collection left it unselected, and child creation without a selected collection returned with only a status message. Newly created workspaces/items now become active/selected. Folder and Request show a destination chooser when no parent is selected, scoped to the current workspace; an empty workspace gets an explicit explanation and a Create collection action. Cancelling does not create records. Request opening happens after the refreshed hierarchy is available.

Changed files for this fix: `ui/src/main/java/dev/apitestingplatform/ui/controller/LibraryWindowController.java`, `app/src/test/java/dev/apitestingplatform/app/LibraryIntegrationTest.java`, `README.md`, and this report. Six added regression cases cover the creation chain, parent selection, empty-workspace cancellation, and workspace activation. The initial regression failed before the fix and passed afterward. Full `clean verify`: **87 tests passed**, zero failures/errors/skips; all 10 reactor projects succeeded.
