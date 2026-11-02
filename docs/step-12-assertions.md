# Step 12 Declarative response assertions

The Tests editor now configures ordered response checks and Test Results shows actual outcomes after Send. The next planned increment is step 13, response extraction into temporary run variables. The user manages commits; no commit or push is performed.

## Delivered behavior

- Stable UUID-based definitions with editable names, types, target/expected values and enabled state. Add, remove and reorder are supported, with a maximum of 100 checks per request. Invalid/disabled drafts can be saved without sending them.
- Seven check types: exact status, header existence, header containing text, JSONPath existence, JSONPath equality, maximum time and maximum body size.
- Enabled definitions are validated before network traffic; invalid configurations identify their editor row. The ordered immutable definitions captured at Send are used even if the user edits the tab while the request is active.
- Application-layer evaluation runs on the existing bounded background worker against an immutable ExecutionResult. JavaFX only receives display results. No scripts or user code are executed.
- Results distinguish PASS, FAIL, NOT RUN and DISABLED, with expected/actual fields, reasons and counts. HTTP error responses remain eligible for checks. Network failures, timeout and cancellation produce NOT RUN. Cancelling check evaluation keeps the response available.
- JSONPath uses the project's pinned Jayway 3.0.0 dependency and an explicit Jackson provider. Only definite property/bracket/index paths are accepted; filters, wildcards, functions, recursive descent and scripts are rejected. Paths are limited to 64 segments. JSON parsing has a 2 MiB body budget, depth/number/string limits and duplicate-key detection.
- Equality compares JSON types and structure, with scale-independent numeric comparison using arbitrary-precision numeric nodes. Null is a present value. Missing paths fail; invalid/empty/oversized/truncated JSON cannot run. Header names are case-insensitive and content comparisons are literal/case-sensitive. Time/size limits are inclusive and byte counts refer to raw downloaded bytes.
- A truncated response can still support status/header checks and conclusive limit failures, but cannot prove that an incomplete measurement satisfies an upper bound. Those cases are NOT RUN with an explanation.
- Display strings are redacted before clipping to 512 characters. Credential headers are masked even when the expected/actual values were used for comparison. Tooltips expose complete display strings when a column is narrow. JSONPath debug logging is disabled by the packaged logging configuration.
- Definitions persist in order inside the existing encrypted request JSON and recovery draft payload. Old payloads default to an empty assertion list; no schema migration or plaintext assertion copy is introduced. Existing normalized assertion tables remain prepared for later run/report persistence.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 145 tests passed with zero failures, errors or skipped tests: domain 5, application 38, HTTP client 32, SQLite persistence 11, Windows security 2, import/export 2, UI 21 and app 34. `git diff --check` passed. No JSONPath debug/evaluation-path messages remained in the final application test output after the packaged logging configuration was added. Reactor artifacts were installed locally for the next JavaFX launch.

Unit tests cover all check types, order/identity, missing/null fields, bracket paths and indices, structural/numeric equality including precision, malformed/empty/duplicate/deep/oversized JSON, status 500 responses, DNS/TLS/timeout/cancellation, inclusive byte/time boundaries, truncation, unsupported path operators, invalid configurations, disabled drafts and credential masking. View-model tests verify save/load identity/order, dirty tracking and immutable submission snapshots.

Real JavaFX/local-server integration tests create a check through the editor, display mixed passing/failing/disabled outcomes, edit definitions while a delayed response is active, save/restart/recover encrypted definitions, block invalid checks before HTTP traffic and show NOT RUN after timeout or cancellation. Existing response viewer, HTTP, authentication, environment, storage, recovery and appearance checks are part of the full build. Editor/result screenshots were inspected in graphite and light themes.

## Manual check

1. Restart after installing the reactor artifacts. Open a request returning JSON, then open Tests.
2. Add a Status equals check with Expected `200`.
3. Add a JSONPath value equals check with Header / JSONPath `$.ok` and Expected `true` if the response contains `"ok": true`.
4. Send and open Response → Test Results. Both checks should pass for a matching response.
5. Change the expected status to `201` and send again. That check must fail with expected 201 and actual 200.
6. Disable the failing check and send again; its result must be DISABLED. Try an invalid JSONPath and verify that sending is blocked with a row error.
7. Save, close and reopen/restart. Definitions and order must remain intact; response results are recomputed on the next Send.

## Boundaries

No response extraction, run-scoped variable mutation, collection execution, saved result history, report export or portable assertion import/export is added. Expected values are literal JSON/text/numbers, with no variable interpolation in assertion settings. Current-response results do not overwrite the saved definitions or body. The interactive response viewer and explicit Save Response continue to show/save actual server data for debugging.

JSONPath reference: [Jayway JsonPath](https://github.com/json-path/JsonPath).
