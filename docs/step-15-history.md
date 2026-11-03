# Step 15 Persistent execution history and sanitized reports

Manual Send attempts and collection runs now produce local history. This increment is implemented together with step 16; the user manages commits and no commit/push is performed.

## Behavior

- The main toolbar's **History…** opens a non-modal, paged history window. Selecting a record loads its saved report asynchronously; selecting a step shows assertion/extraction outcomes and safe reasons. History remains available after the runner is closed, source requests are deleted or the app restarts.
- Manual request records cover HTTP success/error, validation failure, network errors, timeout and cancellation. A completed capture is idempotent, so a late callback cannot overwrite an explicitly cancelled record. Collection history records initial RUNNING metadata, the first completed step, throttled checkpoints and a final report with separate request/check/extraction counts.
- History retains IDs/names, ordered step positions/iterations, HTTP status, duration, captured bytes, classified network errors and check/extraction outcomes. It excludes headers, URLs, bodies, runtime variable values and compared expected/actual values. Raw exception messages are replaced with safe classifications. The interactive response viewer remains an actual-data debugging view.
- Detailed rule results share a 2 MiB admission budget; per-step and report-wide counters remain complete when details are omitted. Report fields explain omission. This keeps large runs bounded without inventing passing outcomes for missing details.
- History save errors do not discard the response or fail an otherwise completed HTTP run. A notice directs users to **Retry pending saves**. A shared buffer retains the latest revision of up to 16 unsaved records in the current app session. Repeated/stale retries cannot overwrite a newer persisted checkpoint/final report.
- Default retention is 100 finished records / 30 days; the History window permits 1–1,000 records and 1–365 days. Save retention prunes immediately; pruning also runs at startup and after history saves. Active RUNNING records are excluded from pruning/clear. Clear finished history asks before deletion. Pages contain at most 100 records.
- Startup changes leftover RUNNING metadata to INTERRUPTED, using the last committed checkpoint without claiming unrecorded steps completed. Normal application closing cancels manual work, waits for the collection runner's final save and then finishes the database session. A failed close leaves the editor available for retry.

## Persistence and exports

Additive migration V003 introduces `execution_history` and `history_settings` without altering V001/V002 checksums or rewriting saved requests. History payloads use the existing DPAPI codec; ID/kind/state/time/count/revision metadata is ordinary SQLite data. History intentionally has no cascading ownership relation to saved request/workspace rows, so deletion does not remove previous reports.

Sanitization runs before repository submission and again at the storage/export boundaries. Expected/actual values and unsafe raw error messages are not persisted. Logical request/check names are metadata, not a place to store credentials. Reports include no raw response object or saved request configuration.

**Export report…** supports standalone readable HTML and versioned JSON (`api-testing-platform-report`, version 1), capped at 8 MiB. HTML escapes user text and contains no scripts, remote assets or local-resource links. Both formats use the same omission policy. Background atomic file replacement either succeeds completely or leaves the previous destination unchanged. Exported reports are not an importable workspace format.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 243 tests passed with zero failures, errors or skips: domain 5, application 80, HTTP client 32, SQLite persistence 19, Windows security 2, import/export 28, UI 22 and app 55. Reactor artifacts were installed locally for the next JavaFX launch. `git diff --check` passed, and the final log contains no JSONPath debug/evaluation-path messages. History layouts were inspected in graphite/light themes and the export dialog was inspected with its secret checkbox off. Tests cover sanitizer idempotency, complete counters under detail-budget omission, retry buffering/latest revisions, manual capture idempotency, continued HTTP execution after history failure, encrypted repository restart, interrupted checkpoints, stale retries, count/age retention, pagination, clear protection and V002→V003 upgrade. JavaFX/local-server tests cover History reopening after restart, real request/run reports, graceful cancellation during application close, timeout/cancel/tab close, retry after an injected SQL failure and readable report export. Graphite/light history layouts are inspected.

## Manual check

1. Restart the updated app and send a request or run the existing login/protected collection.
2. Open **History…** in the top toolbar and select the newest record.
3. Select a step to inspect status, duration, bytes and check/extraction outcomes. Compared values are intentionally omitted.
4. Close and restart the app. Reopen History and verify the same report remains.
5. Select a record → **Export report…** → HTML, then open the file to read it independently.
6. Change retention and Save retention, or cancel an active run and verify its CANCELLED / NOT RUN outcomes in History.

## Boundaries

No full request/response body retention, secret-bearing report option, report re-execution or history import is implemented. A forced process termination can lose work since the last committed checkpoint and any unsaved retry buffer. Larger performance tuning and broader operational settings belong to later increments.
