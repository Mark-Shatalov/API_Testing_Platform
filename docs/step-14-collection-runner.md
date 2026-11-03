# Step 14 Sequential collection execution

This increment adds an asynchronous sequential collection runner, saved collection/environment snapshots and a non-modal JavaFX run window. Steps 15 (persistent history/reports) and 16 (portable import/export) are explicitly deferred. The user manages commits; no commit or push is performed.

## Delivered behavior

- **Run collection…** in the sidebar opens a runner for the selected collection, or the collection owning a selected folder/request. A collection chooser is provided when the current context does not identify a single collection. The main request editor remains available while the run window is open.
- Start captures all saved request drafts, IDs, names, tree order, workspace variables and the selected environment's identity/values together on the serialized SQLite worker in a read transaction. There is no migration or new persistent run data. Saving, renaming, reordering, deletion and environment changes after capture do not mutate the active snapshot. Unsaved editor drafts are not included.
- Folder children run in their saved sibling order at the folder's position in the collection. Direct requests and folders retain their existing mixed tree order. External upload files remain file references; their content is read when their request executes, not snapshotted into memory.
- `CollectionRunner` owns one bounded background worker and admits one HTTP request at a time. It compiles a saved draft without JavaFX, resolves variables/authentication, validates, executes HTTP, evaluates assertions, performs extraction and only then advances.
- Iterations repeat the captured order. Each iteration starts with a fresh `RunVariables` instance state, shared only among requests in that iteration. Runs do not import or mutate manual Send sessions. Precedence is request-local > run > selected environment > workspace. Saved environment values are never overwritten by extraction.
- A configurable delay applies between consecutive request steps, including the boundary between iterations, with no delay before the first or after the last request. Limits are 1–1,000 iterations, 0–60,000 ms delay, 1–1,000 total executions and a 32 MiB protected request-payload snapshot budget. Only one run is active at a time.
- Result rows arrive as requests complete. Request outcomes distinguish SUCCESS, HTTP ERROR, NETWORK ERROR, INVALID REQUEST, CANCELLED and NOT RUN. Assertion and extraction outcomes remain separate: a successful HTTP request may have failing checks; a status-500 response may have a passing status assertion and still be an HTTP ERROR. Ordinary validation, network, HTTP, assertion and extraction failures are recorded and later requests continue.
- Final status distinguishes COMPLETED, COMPLETED WITH FAILURES, CANCELLED and an unexpected FAILED run. Summary counts request failures, failed checks and extraction problems separately. Selecting a row shows masked expected/actual values and reasons. Result rows exclude raw headers, bodies, URLs and temporary variable values; the raw response viewer in the normal editor remains unchanged.
- Cancel is cooperative and linearized with HTTP admission: it cancels active HTTP or interrupts the delay, prevents later request admission and keeps completed rows. The interrupted step becomes CANCELLED; future steps are NOT RUN. Cancellation during snapshot preparation sends nothing. Closing the runner or application also cancels active work. Start after completion/cancellation takes a new snapshot.
- Known and newly extracted secrets are redacted before results reach JavaFX, including assertion values from the token-producing response. Temporary values are cleared on cancellation/completion and between iterations. Raw engine exception messages are not copied into results.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 198 tests passed with zero failures, errors or skips: domain 5, application 75, HTTP client 32, SQLite persistence 13, Windows security 2, import/export 2, UI 22 and app 47. Reactor artifacts were installed locally for the next JavaFX launch. `git diff --check` passed. No JSONPath debug/evaluation-path messages appeared in the final log. Runner screenshots were inspected in graphite and light themes. Application tests exercise ordering, authentication/extraction across steps and iterations, isolated iteration/run state, active HTTP cancellation, delay cancellation and timing, repeated runs, invalid requests, HTTP/network/engine failures, stale-token invalidation, secret masking, worker admission limits and independent display callbacks. Saved-draft compiler tests cover every body mode, exact timeout conversion, disabled file drafts and invalid enabled upload paths.

SQLite tests exercise mixed folder/direct order, selected environment capture, saved contents surviving later saves/deletion, environment deletion/selection changes, empty collections and rejection of non-collection IDs. JavaFX/local-server tests use the actual sidebar button and runner controls, repeat login → protected request → server-error sequences, mutate collection/order/environment while the first response is delayed, edit the main request editor during a run, inspect separate check/request outcomes, cancel/close active HTTP, reject empty/oversized plans and cancel before snapshot completion. Screenshots are inspected in graphite and light themes.

## Manual check

1. Save the two step-13 demo requests in one collection, in this order: Login (`/login`, no auth, extract `$.access_token` into `token`), then Protected (`/protected`, Auth → Bearer Token `{{token}}`, no extraction rules).
2. Select that collection and click **Run collection…** in the sidebar.
3. Leave Iterations at 1 and Delay at 0, then Start. Expect two SUCCESS rows with HTTP 200; Login extracts one value and Protected consumes it.
4. Set Iterations to 2 and Start again. Expect four rows in the same repeated order, with a fresh login per iteration.
5. Add a status assertion expecting 201 to the Protected request, Save and Start. Its request outcome remains SUCCESS with HTTP 200, the check fails and the run ends COMPLETED WITH FAILURES.
6. Set a delay such as 5000 ms. Start, then Cancel run during the gap: the second request must remain NOT RUN. Closing the runner also cancels it.
7. Edit/reorder saved requests while a run is active: the active run uses the old snapshot; the next Start uses the updated saved definitions. Results are available only in this window for now.

## Boundaries

No persistent execution history, report export, retention settings or portable import/export is added. Those are steps 15–16 and require a separate user instruction. The runner result model is in memory only. Performance budgets for larger workloads remain subject to the dedicated later performance increment; the limits above are explicit admission bounds, not measured performance guarantees.
