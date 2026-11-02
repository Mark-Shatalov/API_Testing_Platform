# Step 11 Authentication and secret protection

The request Auth tab now configures No auth, Bearer Token, Basic Auth and API Key credentials in a header or query. The next planned increment is step 12, declarative assertions. The user manages commits; no commit or push is performed.

## Delivered behavior

- Auth settings are immutable editable templates inside RequestDraft. Old encrypted payloads with no authentication field load as No auth. Inactive mode values survive switching, saving and recovery but are not applied to a request.
- Token/password/key controls are hidden by default with an explicit reveal checkbox. Auth fields accept the same bounded variable resolution as the URL and body. Missing/cyclic references and credential validation failures are field errors in Auth, with no network traffic or secret values in errors.
- Bearer and Basic create Authorization headers; Basic uses UTF-8, rejects colons in usernames and rejects control characters. API Key supports an arbitrary valid non-managed header or an encoded query parameter. Conflicting enabled manual headers or query keys, including keys already present in the raw URL, are rejected before sending.
- Credentials are applied to an immutable execution snapshot. Neither environment secrets nor generated headers are written back into the saved template. All saved auth fields and recovery payloads use the existing current-user DPAPI protection. There is no plaintext fallback.
- Custom authentication headers and variable-derived secret headers are annotated for cross-origin stripping. Secret query keys are removed from cross-origin redirect targets and subsequent hops. Standard Authorization/Proxy-Authorization/Cookie stripping, TLS verification, cancellation and deadlines are preserved.
- A shared redactor masks standard credential headers and known secret values in plain text, encoded URLs and escaped text. Secret-derived variables remain masked. The redacted diagnostic summary codec omits bodies/cookies/query values and masks headers/URLs. It refuses invalid request preparation. This is not the portable collection format planned for step 16.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 125 tests passed with zero failures, errors or skipped tests: domain 5, application 23, HTTP client 32, SQLite persistence 11, Windows security 2, import/export 2, UI 20 and app 30. `git diff --check` passed. Reactor artifacts were installed locally so the next launch uses the new Auth implementation.

Automated checks exercise successful auth requests to local MockWebServer instances, real JavaFX auth fields and current-user Windows DPAPI. The UI integration cases cover all four helper placements (Bearer, Basic, API Key header, API Key query), preview without network traffic, variable-backed tokens, immutable templates, save/restart/recovery and absence of plaintext markers on disk. Conflict validation is checked before traffic. Existing V001 migration coverage also verifies old payloads with neither variables nor authentication fields.

Unit tests cover UTF-8 Basic encoding, invalid credentials, disabled/manual conflicts, unknown/inactive variables, API key masking, standard header redaction, encoded/escaped values and redacted summary contents. Transport tests verify same-origin credential preservation and removal of custom header/query credentials across origins; existing tests cover TLS rejection, cancellation, redirects and network failures. Auth screenshots were inspected in light and graphite themes.

## Manual check

1. Restart after installing the reactor artifacts, then open a request's Auth tab.
2. Choose Bearer Token. Enter a test token or `{{token}}` backed by a secret variable, without the Bearer prefix. Confirm the field is hidden; Show credentials reveals the entered template/value.
3. Use Preview target, then Send to an API you are authorized to test. The helper adds Authorization to the sent snapshot; the manual Headers table remains unchanged.
4. Try Basic Auth with a test username/password, then API Key with HEADER and QUERY placements. Query keys are hidden in the target preview.
5. Add a conflicting enabled manual Authorization header while using Bearer/Basic, or a matching manual API key field. Sending must stop with an Auth error. Disable/remove the conflicting row and retry.
6. Save and reopen after restarting. Settings and inactive mode values should remain available; reveal is off by default.

## Boundaries and protocol choices

No OAuth sign-in/refresh, challenge negotiation, TLS bypass, scripting or secret-inclusive export option is introduced. Basic encoding is UTF-8; compatibility with servers expecting other legacy encodings is outside this increment. Credentials typed in explicit manual request fields remain visible there; generated secret values are never inserted into those editors. The response viewer and explicit Save Response preserve actual server data for debugging.

Temporary Basic encoding byte buffers are wiped, as are the existing persistence encryption buffers where feasible. Java String values used by editors and immutable snapshots cannot be reliably erased and remain subject to garbage collection.

There are no history, diagnostic archive or general logging user flows yet. The redactor and diagnostic summary codec provide the tested masking boundary for those later increments. Portable collection import/export UI, schema validation and atomic imports remain step 16; this increment does not claim those features.

Protocol references: [Bearer token usage RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html), [Basic authentication RFC 7617](https://www.rfc-editor.org/rfc/rfc7617.html).
