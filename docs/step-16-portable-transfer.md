# Step 16 Portable workspace JSON import/export

The toolbar now exports a saved workspace and imports it as a separate copy. The format transfers collections, folders, requests, environments, scoped variables, authentication settings, assertions and extraction rules. This increment is implemented with step 15; no commit or push is performed.

## Format and validation

The top-level document contains `format: api-testing-platform-workspace`, integer `version: 1`, the informational `secretsIncluded` flag and a `workspace` value. A workspace contains its logical UUID, ordered hierarchy nodes (ID, kind, parent reference, name, sibling position and request draft where applicable), workspace variables, named environments and selected-environment reference. It contains no SQL row versions, timestamps, encrypted payload envelopes, migration data or SQLite-specific column representations.

Only this application-owned format is accepted; no Postman/OpenAPI compatibility is promised. Strict parsing rejects duplicate JSON keys, trailing data, unknown fields, incompatible formats/versions, invalid scalar types, excessive nesting/strings/numbers and oversized files. Model validation checks unique hierarchy/environment IDs, root identity, hierarchy kinds, references, one folder level, contiguous/unique sibling positions, per-request rule IDs, environment name uniqueness and selected-environment ownership.

Incomplete saved request drafts are transferable; the normal Send/runner preflight still decides whether they can execute. Import never executes requests or opens referenced upload files.

## Secret policy

The export dialog starts with **Include secret values and request payloads in plain text** unchecked, and does not remember the choice. Default export omits credentials, secret/sensitive-name variable values, literal header/query/form/cookie values, all body drafts (including inactive drafts), upload paths, URL fragments and sensitive assertion expected values. It preserves IDs/names/order/configuration, safe numeric status/limit expectations and non-secret variable values. Whole variable placeholders in structured inputs are preserved. Missing inputs generally use `{{REDACTED}}`; JSON equality uses a valid explicit omitted-value marker and upload paths become empty.

Known credentials are removed from other data fields as well, including plain/encoded URL paths, URI user information, sensitive query parameters and copies stored in other variable values. This includes literal Bearer/Basic authorization values as well as the Auth helper. Confidential variables should remain marked Secret; arbitrary unmarked public values and item/variable/check names remain public metadata.

Opting in transfers full saved values and path references in plaintext, including inactive settings. File contents are never embedded. The receiving app protects imported payloads using its own current-user DPAPI key; the file does not depend on the source Windows profile. Import treats the flag as informational, validates the data regardless of its value and never automatically runs the imported content.

## Atomic copy semantics

Export captures only the selected saved workspace and selected environment, on the serialized repository worker. Unsaved tabs, recovery drafts, runtime variables, appearance preferences and history are excluded. Import creates a new `… (imported)` workspace with fresh workspace/collection/folder/request/environment IDs and remapped parent/selection references. Rule identity/order inside each copied request is retained. Existing data and open drafts are preserved.

All parsing/reference validation and DPAPI protection finish before the write transaction. The hierarchy, requests, environments, variables and selection then insert in one short transaction; any SQL error rolls back every inserted row. Errors explicitly report unsupported fields or invalid format/structure without printing file values. File reading, parsing, encryption, SQLite access and atomic export replacement stay off JavaFX.

Limits are 32 MiB per file, 10,000 hierarchy nodes, 100 environments, existing request/variable limits, nesting depth 100, field names up to 256 characters and numbers up to 30 digits. Snapshot admission caps combined protected source payloads at 48 MiB. Default redaction caps 4,096 distinct known credential values and 2 MiB of credential characters; large workspaces must be split. Export failure or unsupported atomic replacement leaves the previous destination untouched and removes the temporary file.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 243 tests passed with zero failures, errors or skips: domain 5, application 80, HTTP client 32, SQLite persistence 19, Windows security 2, import/export 28, UI 22 and app 55. Reactor artifacts were installed locally for the next JavaFX launch. `git diff --check` passed, and the final log contains no JSONPath debug/evaluation-path messages. History layouts were inspected in graphite/light themes and the export dialog was inspected with its secret checkbox off. Format tests cover complete round trips including inactive drafts, default omission across credential/body/file/query/cookie fields, manual Bearer/Basic credentials copied into other fields, unsupported fields/versions, malformed references, duplicate IDs/keys, scalar coercion/null rejection, nesting limits, oversized input/output and atomic replacement/cleanup. SQLite tests verify reference remapping, preserved source data/order, receiving-user encryption, restart persistence, rollback after an injected SQL trigger failure and no writes after encryption failure. JavaFX tests verify real export/import files, source/open-draft preservation after invalid imports, no network execution during import and a default-off export checkbox. Export dialog layout is inspected.

## Manual check

1. Save request edits and select the workspace to transfer.
2. **Export workspace…** → keep secret inclusion off → Choose file → save a JSON file.
3. **Import workspace…** → choose that file. A new workspace ending in `(imported)` appears; the original remains.
4. Open copied requests/environments and check order and rules. Fill `{{REDACTED}}`, omitted expectations and upload paths before sending.
5. For a private full-value transfer, explicitly enable secret inclusion during a new export. The receiving copy retains full saved values, protected again on disk.
6. Try an incompatible version or invalid reference. The import must fail without adding a partial workspace or changing original data.

## Boundaries

This is a single saved-workspace transfer, not a SQLite backup, history archive, file-content bundle, collection-format adapter or merge into existing items. Import always copies. Operational backup/restore and diagnostics remain later work.
