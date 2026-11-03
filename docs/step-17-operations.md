# Step 17 — settings, backups and diagnostics

Implemented with step 18. Commits remain the user's responsibility; nothing was committed or pushed.

## Settings

The toolbar's **Settings…** opens a non-modal window that follows the active graphite/light theme. File work stays on bounded workers; status remains visible below the scrollable settings.

| Setting | Behavior |
| --- | --- |
| Proxy | Direct explicitly bypasses the JVM/OS proxy. System uses the default selector with OS proxy discovery enabled at startup. Manual takes a hostname/IP and a separate port, 1–65,535. No proxy credential storage/helper is added. |
| Additional CA | Optional PEM/DER certificate bundle, at most 1 MiB / 100 certificates. Private-key bundles, invalid and expired certificates are rejected. Trust extends JVM defaults; HTTPS certificate and hostname verification remain enabled. |
| Capture / preview limit | 64–10,240 KiB, default 10 MiB. Reaching the limit cancels the download and retains the prefix with RESPONSE_TOO_LARGE. Checks/extraction retain their existing stricter JSON limits. |
| History retention | Existing 1–1,000 finished records / 1–365 days; applies immediately and preserves active records and saved requests. |
| Local paths | Read-only data directory, database and logs paths, plus startup/logging notices. |

Network settings save atomically to `operations.properties` beside the database and apply on the next launch. Failure preserves the previous file. Missing preferences use defaults; malformed/unsupported preferences use defaults with a notice and keep the original file. If a saved CA file becomes unreadable/invalid, startup retains proxy/capture settings, uses JVM trust and exposes a notice in Settings. The default does not disable TLS verification.

## Database backup and restore

**Back up database…** runs SQLite `VACUUM INTO` on the repository's serialized worker, outside a transaction. The completed standalone snapshot is verified, flushed and atomically installed at the selected destination. It includes the database contents: hierarchy, saved drafts, environments/variables, history/settings and committed recovery snapshots. Appearance/network preference files and edits still waiting for autosave are separate. A failed backup leaves an existing destination intact. The file limit is 1 GiB.

Payloads stay encrypted using the current Windows user's DPAPI. The same profile must decrypt them during validation; copying the database to another Windows profile is not the portable-transfer mechanism. **Export workspace…** remains available for that purpose and uses its existing default-off plaintext-secrets option.

**Restore after restart…** copies the selected completed backup into a bounded staging file and verifies:

- Known contiguous migration versions and checksums, with the exact expected tables/indexes/schema; extra triggers, views and other schema changes are rejected.
- SQLite integrity and foreign-key references.
- Protected active request, recovery, variable and history payloads, including decryption by this profile. Oversized/non-text envelopes are rejected before materializing them in Java.

Staging never replaces the active database. A SHA-256 receipt identifies the staged bytes. **Cancel pending restore** removes the staged request and keeps current data.

On restart, after acquiring the exclusive data-directory lock and before opening SQLite, the app rechecks the receipt and snapshot. It copies the current database plus present WAL/SHM sidecars into a unique `recovery-backups/before-restore-…` directory, with per-file checksums and presence metadata. It writes a recovery journal, removes old sidecars, and installs the verified standalone database. The APPLIED receipt is the commit point; cleanup follows it. The previous files remain as automatic recovery copies. These copies are not automatically pruned.

An interrupted pre-commit activation first rolls back from the verified safety copies, then revalidates any pending request. A committed receipt prevents replaying a stale backup after an interrupted cleanup. Failed rollback leaves storage closed and keeps the journal and files for recovery. No new backup can be staged while that journal remains. Existing incompatible-migration backups still happen before schema changes.

Database startup failure keeps the instance lock so Settings can stage a valid restore even when the database is corrupt. Another instance cannot stage or cancel recovery against that directory. Settings and non-secret diagnostics still work; normal failed-storage closing releases the lock. Restoring replaces the entire database, so later changes are available in the automatic pre-restore copy rather than in the restored snapshot.

Workspace/report/response/diagnostic exports and manual backups cannot target the active database, sidecars, lock, settings, restore metadata, logs or automatic recovery directories. Parent paths are canonicalized to catch directory aliases; existing symbolic links and aliases to the active database are rejected.

## Logs and diagnostics

`logs/operations.log` rotates through three archives, 512 KiB per file (2 MiB total during ordinary operation). A bounded 256-event queue protects callers; overflow drops events and increments a counter. Logging failure sets a visible Settings notice and does not discard editor data or fail HTTP/storage actions.

Every event has an ISO timestamp, level, component, correlation UUID, enumerated event code and optional bounded numeric metrics. Request start/completion share an ID; collection runs use their history ID. Startup, HTTP, storage, runner, workspace transfer, settings, backup/restore and unexpected UI failures have structured events. The log API accepts no arbitrary message, URL, exception, header, body, variable or credential strings. It omits sensitive inputs entirely rather than attempting to identify every possible password in arbitrary text. Logging is best effort, including during abrupt process exit.

**Open Logs Folder** opens Explorer on the operations worker. **Create diagnostic archive…** writes an atomic ZIP with exactly `diagnostics.properties` and `events.log`. The summary contains application/Java/OS/architecture versions, proxy mode, an extra-CA-configured boolean, capture and retention limits, and dropped-event count. It contains no host addresses or file paths. Only the four known bounded log files are considered, and their lines are parsed against the event whitelist again. Other files and arbitrary injected log lines are excluded. The archive excludes the database, settings files, certificates, paths, request/response data and secrets; maximum ZIP output is 5 MiB. Diagnostics can still export system information when logging/storage is unavailable.

## Verification and manual check

Windows 11, Temurin 25.0.5: `mvnw.cmd --offline --no-transfer-progress -Dplatform.performance=true clean install` passed all ten reactor modules, **267 tests**, zero failures/errors/skips. Tests use temporary databases and local HTTP/HTTPS servers; real Windows DPAPI is exercised in app integration tests. Coverage includes proxy routing/bypass, capture limits, added CA trust and wrong-host/untrusted TLS rejection; preference persistence/corruption and failed saves; bounded rotation, log access failure, HTTP secret omission and correlation IDs; diagnostic ZIP whitelisting; consistent encrypted backup, staged/cancelled restore, modified/corrupt/foreign-profile/schema-invalid backups, corrupt-database recovery, second-instance refusal and simulated interrupted activation/cleanup. Settings were inspected in both themes. Response export to the active database is rejected. Existing migration rollback/backup, editor persistence, cancellation, timeout, import/export and history tests also pass. Disk-full/access codes have classified-message tests; no real system disk was filled.

1. Restart the app and click **Settings…** in the top toolbar.
2. Change the capture limit, **Save network settings**, close/reopen Settings to check persistence, then restart to apply it.
3. **Back up database…** to a separate `.sqlite` file. For a restore check, use a disposable workspace: back it up, rename a saved request, select the backup with **Restore after restart…**, then close normally and reopen. The old name should return; the newer database should remain in `recovery-backups`.
4. Check **Cancel pending restore** separately before restarting; the current names should remain.
5. Scroll to **Create diagnostic archive…**, create a ZIP and inspect its two entries. **Open Logs Folder** should open the directory shown above it.

Installer/release work remains steps 19–20.
