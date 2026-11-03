# Step 13 Response extraction and temporary data flow

This increment adds ordered declarative extraction definitions, an application-layer engine, an explicitly owned in-memory variable scope and a usable manual data-flow session. Automatic sequential collection execution remains step 14. The user manages commits; no commit or push is performed.

## Behavior and ownership

- Upper request tab **Extract → Add extraction** edits the destination variable and response JSONPath, enabled state and order. **Response → Extraction Results** distinguishes EXTRACTED, FAILED, NOT RUN and DISABLED. Results contain reasons and never contain extracted values.
- Definitions have stable UUIDs, a maximum of 100 per request and row-level preflight validation. Enabled targets must have valid, unique variable names; incomplete disabled definitions can be saved. Definitions are captured at Send, so edits during the operation affect the next request.
- `ExtractionEngine` evaluates an immutable `ExecutionResult` in the existing bounded background worker. JSON parsing and path selection do not run on JavaFX. `RunVariables` holds values separately from persisted environments, request variables and recovery drafts. Callers create one instance per future automated run; no global singleton exists.
- For manual Send, saved requests share a session within their owning collection, including folders. Unsaved tabs use independent sessions. Different collections and workspaces are isolated. Save both requests before starting data flow; saving a scratch tab does not migrate its former private session into a collection. Session lookup is bounded to 100 contexts and each context to 256 variables / 1,048,576 combined name/value characters. Toolbar **Clear temporary variables** clears all contexts; the action beside Preview target clears only that request's context. Restart clears everything. Environment changes retain manual session values until cleared explicitly.
- Resolution precedence is request-local > temporary run/session > selected environment > workspace. Response-derived values are literal data, including any `{{braces}}`; saved variable templates keep their existing nested expansion behavior.
- All extracted values are secret. Preview, later request metadata and assertion results for the producing response use masking. Request execution still receives actual values. The raw response viewer and Save Response intentionally display/save actual server bytes. Extracted values never become a saved environment update, request draft field, history entry or log message.

## JSON selection and failure rules

The bounded parser and property/index grammar are shared with assertions. Extraction additionally accepts array wildcards `[*]`. Paths have at most 64 segments; recursive descent, filters, slices, unions, negative indices, functions and scripts are rejected. Rules support names/quoted property keys and non-negative indices.

Definite selection returns one JSON value, even when that value is an array, object, empty string or null. An indefinite wildcard selection must match exactly one value: zero or multiple matches fail explicitly. String values become raw string text; other types use JSON text with arbitrary-precision numbers. Individual values are capped at 65,536 characters.

Responses require complete valid JSON within 2 MiB and parser limits (depth 100, bounded strings/numbers, no duplicate properties/trailing data). A missing path is FAILED; malformed/empty JSON, network errors, timeout, cancellation and truncation are NOT RUN. HTTP 4xx/5xx responses remain eligible when complete and valid. Disabled rules do not read or alter variables.

Publication is atomic: successful independent rules replace their targets together, failed enabled targets are removed, and unrelated values remain. If scope capacity is exceeded, no new values publish and the attempted targets are removed. A revision captured at submission prevents a late response from restoring values after Clear or replacing a newer concurrent update. Cancelling response processing or closing an active tab invalidates attempted targets; stale UI callbacks cannot publish.

## Persistence

Only `RequestDraft.extractions` is persisted, inside the existing DPAPI-protected request and recovery payload. Missing fields in old payloads default to an empty list, with legacy constructor overloads retained. No schema migration, plaintext copy or persistent runtime-variable table is introduced.

## Manual check

1. Save two requests in the same collection. The first must return JSON with an `access_token` property; the second should accept Bearer authentication.
2. In the first request's upper **Extract** tab, choose **Add extraction**. Enter variable `token`, path `$.access_token`, then Send.
3. Open lower **Extraction Results**. Expect EXTRACTED with a masked-value explanation.
4. In the second request, choose Auth → Bearer Token and enter `{{token}}`. Send; the token from the first response is used without changing the saved environment.
5. Send a first response with no `access_token`. Expect FAILED. A subsequent required `{{token}}` must be unresolved unless a saved lower-priority scope explicitly supplies a fallback.
6. Try `$.items[*].id` with two matched values: expect FAILED with the match count. Try an HTML response: expect NOT RUN. Try `$..token`: Send is blocked with a row error.
7. Save/reopen/restart to verify that definitions remain and extracted values disappear. Use Clear temporary variables to reset without restarting.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 180 tests passed with zero failures, errors or skips: domain 5, application 64, HTTP client 32, SQLite persistence 11, Windows security 2, import/export 2, UI 22 and app 42. Reactor artifacts were installed locally for the next JavaFX launch. `git diff --check` passed; no JSONPath debug/evaluation-path messages appeared in the final log. Screenshots were inspected in graphite and light themes. A separate loopback-only demo server was verified to return a token, accept it in the next request (200) and reject missing authentication (401). Tests cover typed values, precise numbers, missing/multiple matches, parser/path/value limits, failure invalidation, immutable submission snapshots, scope precedence, literal response braces, session capacity and late publication after reset. JavaFX/local-server checks cover token exchange between saved requests in one collection, isolation, encrypted definition/recovery persistence, restart, clear, non-JSON/missing/multiple responses, validation before traffic, timeout and cancellation.

## Boundaries

No automatic collection runner, iteration/delay controls, saved execution history, reports or portable import/export is added. A new `RunVariables` instance is the data-flow boundary for step 14; manual interactive sessions are deliberately separate from saved environment state.
