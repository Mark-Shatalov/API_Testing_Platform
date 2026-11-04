# API Testing Platform

API Testing Platform is a local Windows desktop application for creating, organizing, and testing HTTP APIs. The application is designed for Windows 10 and Windows 11 and keeps its application data on the local computer.

Steps 4–18 are implemented: the JavaFX workspace includes a complete request editor, asynchronous HTTP execution, a response viewer, SQLite-backed workspaces/collections/requests, protected crash-recovery drafts, environments, variable resolution, authentication helpers, declarative assertions, response extraction, sequential collection runs, persistent history with sanitized reports, portable workspace import/export, operational settings, database backup/restore and safe diagnostics. Performance and failure recovery have measured workloads. Step 19 packages the app as a Windows MSI with a bundled Java runtime; step 20 provides the release validation run.

## Module structure

| Module | Responsibility |
| --- | --- |
| `app` | JavaFX entry point, startup configuration, and dependency wiring |
| `ui` | JavaFX screens, controllers, view models, and CSS |
| `application` | Business use cases and infrastructure-facing service interfaces |
| `domain` | HTTP request, collection, assertion, environment, and execution models |
| `http-client` | Java `HttpClient` infrastructure |
| `persistence-sqlite` | SQLite repositories and migrations |
| `security-windows` | Windows DPAPI secret encryption |
| `import-export` | Portable JSON import and export |
| `packaging` | Windows runtime image and installer configuration |

## Development requirements

- Windows 10 or Windows 11 x64
- Java 25 JDK

A global Maven installation is not required. The Maven Wrapper downloads the configured Maven version automatically.

## Verify the complete project

From PowerShell in the repository root:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"
.\mvnw.cmd clean verify
```

## Run the JavaFX application

Install the reactor artifacts locally, then launch the `app` module:

```powershell
.\mvnw.cmd install
.\mvnw.cmd -f app\pom.xml javafx:run
```

The development build requires Java 25. `javafx:run` uses the same entry point as the installed launcher (`dev.apitestingplatform.app.Main`).

## Install on Windows

The MSI from `packaging\windows\Build-Installer.ps1` installs into `C:\Program Files\API Testing Platform` with its own Java 25 runtime; no JDK, JRE, server or service is needed on the user's computer. Data, backups, logs, settings and native-library caches stay in `%LOCALAPPDATA%\API Testing Platform`, outside the installation folder. Installing a newer version upgrades in place and keeps that data; uninstalling also keeps it. To delete it, run **Start → API Testing Platform → Remove API Testing Platform Data** and confirm.

```powershell
.\packaging\windows\Install-WixToolset.ps1      # once per build machine (.NET 8 SDK required)
.\packaging\windows\Build-Installer.ps1         # dist\API Testing Platform-<version>.msi
```

Building, release steps and the installer validation checklist: [packaging/README.md](packaging/README.md), [release checklist](docs/release-checklist.md), [Step 19 packaging](docs/step-19-packaging.md) and [Step 20 release validation](docs/step-20-release-validation.md).

## Finding your way around

Start with **New request**, enter an HTTP(S) URL and click **Send**. A blank request offers **Use a sample GET request**, which fills a public example URL without sending it. Request options appear after you start editing; hover over a tab to see what it does. The response appears below, with search and save tools available once a response is ready. Validation errors open the section that needs attention.

To keep a request, create a **New collection** in the sidebar and click **Save** (Ctrl+S). **Find saved requests…** searches collections, folders and requests by name while keeping their hierarchy visible; Escape clears the search. Use **Add** for saved requests and folders, and **Organize** for renaming, deleting and ordering the selected item.

**Workspace** contains workspace creation, management, import and export. **Tools** contains Save as, refresh, recovery and temporary-variable actions. The left navigation rail contains **Library**, **History** and **Settings**. Settings contains appearance and network, backup and diagnostic settings. The environment selector remains visible; expand **Environment options** to create or edit environments and workspace variables.

## Appearance

The workbench uses a near-black canvas, a framed editing surface, rounded controls and a cyan default accent, inspired by Dotify and Liner. Geist is bundled with its open font license and works offline; code uses Consolas. The light theme follows the same layout, with darker focus indicators for contrast. Existing saved theme and accent preferences are preserved. See [the visual design notes](docs/interface-redesign.md).

Use **Settings → Appearance…** to select **Dark graphite** (the default) or **Light** and choose an accent color. Changes preview immediately across the workspace, response viewer and application dialogs. **Save** keeps the selection for future launches; **Cancel** restores the previous appearance. **Reset to defaults** previews the original graphite palette, which can then be saved.

Preferences are stored separately from requests in `%LOCALAPPDATA%\API Testing Platform\appearance.properties`. Saving runs in the background; failures stay visible in the dialog and can be retried. Missing or invalid preferences fall back to the default theme. The Windows title bar and native file chooser follow Windows settings.

## Local storage and saved requests

The app opens a SQLite database at `%LOCALAPPDATA%\API Testing Platform\platform.sqlite`, outside the project and installation directories. Startup migrations and every read/write run on a bounded serialized background queue. SQLite uses WAL, foreign keys, a busy timeout and short write transactions. A separate process lock prevents two app instances from editing the same data directory simultaneously.

Use **Workspace → New workspace…**, **New collection** and **Add → Saved request… / Folder…** to build the navigation tree. Newly created items are selected automatically; creating a workspace switches to it. If Folder/Request is chosen without a selected parent, a dialog asks for the destination in the current workspace. If no collection exists, an explanation offers to create one. Folders are exactly one level deep inside a collection; requests belong to a collection, directly or through a folder. Select an item and use **Organize** to Rename, Delete, Move up or Move down. Current workspace management is available through **Workspace → Manage current workspace…**. Deleting a parent asks for confirmation and deletes its descendants.

Double-click a request (or press Enter / Open) to open a tab. **New tab** creates a scratch request. **Save** / Ctrl+S saves an existing request; a new request asks for its collection/folder and name. **Save As…** creates an independent saved request. All editor settings and inactive body-mode drafts survive saving and restart. Saving incomplete input is allowed; Send still validates before network traffic. Files are referenced by path, not embedded. Edits made while saving remain dirty if they were not part of the saved snapshot. Version checks prevent stale tabs from silently overwriting newer data.

Unsaved tabs have an asterisk. Closing a dirty tab offers Save / Discard / Cancel. Closing the window asks before discarding unsaved work; choose Cancel and Save first to keep edits permanently. Database errors leave dirty editor content intact.

Recovery snapshots are debounced for 750 ms after the last edit, not written on every keystroke. The recovery indicator confirms successful snapshots; failures are visible and **Retry recovery save** retries dirty tabs. An abnormal exit leaves the latest committed snapshots available at the next startup. **Recover tabs…** can reopen the recovery prompt later. Recovery creates dirty tabs without overwriting saved requests and is idempotent. Normal discard/close removes the corresponding temporary snapshots only after successful storage operations. Changes made within the debounce window before a crash may not have reached disk.

Complete request and recovery payloads are versioned JSON envelopes in TEXT columns, protected with Windows DPAPI for the current user. This covers Authorization, cookies, URL/query secrets and arbitrary body values without attempting to guess which values are secret. There is no plaintext fallback. Names, hierarchy and timestamps are ordinary database metadata, not encrypted. Protected payloads depend on the Windows user profile and are not a portable export format. Transient byte buffers are wiped where feasible; Java strings cannot be reliably erased.

`schema_version` records ordered SHA-256-checksummed migrations. Changed checksums or unknown schema versions are rejected without replacing data. Before an incompatible migration, `VACUUM INTO` creates a consistent backup under `migration-backups` next to the database; migration SQL and version registration are transactional and roll back on failure. Do not manually copy a live database without its WAL; close the app before file-level backup. Manual backup/restore UI belongs to a later phase.

Environments and variables are implemented in step 10. Steps 12–13 save ordered assertion and extraction definitions inside the encrypted request payload alongside editor settings. Step 15 adds encrypted, bounded execution history through an additive V003 migration; the older normalized run/result tables remain reserved. See [Phase 2 implementation and verification](docs/phase-2-implementation.md), [Step 10 implementation](docs/step-10-environments.md), [Step 12 assertions](docs/step-12-assertions.md) and [Step 13 extraction](docs/step-13-extraction.md).

## Environments and variables

The sidebar's **Environment** selector belongs to the selected workspace. Expand **Environment options** to find **New environment**, **Edit** and **Delete**. Select **No environment** to use workspace, temporary session and request values. **Workspace variables…** in the same section edits defaults shared by requests in that workspace. Switching workspaces selects a request tab for that workspace, and selecting an existing tab restores its workspace. Unsaved tabs from other workspaces remain open. The selected environment is saved per workspace.

In an environment, add `baseUrl = http://localhost:8080` and save. Select that environment, enter `{{baseUrl}}/posts/2` in the request URL, and use **Preview URL** to inspect the resolved address without sending traffic. **Send** resolves and validates a fresh immutable snapshot again. The saved URL remains `{{baseUrl}}/posts/2`, so selecting another environment changes the destination without editing the saved template.

The request editor's **Variables** tab contains request-local values, which are saved with that request and included in encrypted recovery drafts. Precedence is **request > temporary run/session > selected environment > workspace**. Names are case-sensitive, start with a letter or underscore, and can contain letters, digits, dots, underscores and hyphens. Duplicate names within one scope are rejected. Each scope supports up to 256 variables and 1,048,576 combined name/value characters; individual values are limited to 65,536 characters. A workspace supports up to 100 environments.

Placeholders resolve in the URL, enabled query/header/cookie rows, JSON/text bodies, URL-encoded fields and multipart text/metadata. File paths are kept as literal external-file references. Variable values can reference other variables; nested references follow the same precedence. Unknown required variables, cycles, malformed placeholders, excessive nesting/expansion and invalid resolved URLs or bodies stop the request before network traffic and mark the relevant fields. Use `\{{name}}` to send literal `{{name}}` text. Substitution is textual: JSON values must still form valid JSON after substitution, and URL path values must already use appropriate URL encoding. Structured query/form values are encoded by the existing HTTP engine.

Mark confidential variables **Secret**. Their values are hidden in the editor unless **Show secret values** is selected. Preview masks secret substitutions as `[secret]` (URL-encoded in structured query values), including values derived from secret variables. A preview is a snapshot: prepare it again after changing inputs or environments. Every Send prepares its own target. Environment/workspace values, including ordinary values, are protected with current-user Windows DPAPI on disk. Names, secret flags and environment metadata are not encrypted. No resolved values are written back into saved templates or logged. Existing response viewing remains a debugging view and can display data echoed by the server.

## Authentication and secret handling

The request **Auth** tab supports **No auth**, **Bearer Token**, **Basic Auth**, and **API Key** in a header or query parameter. Enter literal values or templates such as `{{token}}`; the same request > temporary run/session > environment > workspace precedence applies. Tokens, passwords and key values are password fields by default. **Show credentials** reveals them explicitly. Switching modes retains inactive drafts, but only the selected mode is resolved or sent. Save and crash recovery protect all authentication settings inside the existing current-user DPAPI payload, with no plaintext fallback. Old saved requests default to No auth.

Bearer fields accept the token only, without the `Bearer` prefix. Basic Auth uses UTF-8 before Base64 encoding; usernames cannot contain a colon and neither username nor password can contain control characters. API Key requires a name and non-empty value; transport-managed/invalid header names and unsupported header characters are rejected. An enabled manual header/query parameter conflicting with the Auth helper is rejected rather than overwritten. In No auth mode, explicit Authorization headers in Headers still work.

Preview and Send resolve authentication on a background worker without changing saved templates. The target preview hides literal API keys as well as secret variables and secret-derived values. Missing variables and invalid/conflicting auth mark the Auth tab before any traffic. Header credentials are applied only to the immutable execution snapshot. API key values in structured query parameters use the existing URL encoding.

On a cross-origin redirect, generated custom API key headers and headers containing secret variables are stripped alongside Authorization/Proxy-Authorization/Cookie. Query parameters identified as credentials are removed from a cross-origin redirect target, and remain stripped on subsequent redirects. Same-origin redirects retain header credentials and follow the server's query string. TLS certificate and hostname verification remain enabled; no bypass setting is provided.

The shared `SecretRedactor` masks Authorization, Proxy-Authorization, Cookie, Set-Cookie, custom authentication fields and known secret values at text/header/URL boundaries. `RedactedRequestSummary` provides a non-portable diagnostic JSON codec that omits bodies and cookie/query values and redacts credential headers/URLs. Its tests verify that ordinary summaries contain no credential plaintext. History and portable export now apply additional omission boundaries; logging/diagnostic archives remain later work. No imported scripts are executed. The interactive response viewer and explicit Save Response still show/save actual server data, which can include echoed credentials.

See [Step 11 implementation and verification](docs/step-11-authentication.md).

## Response checks

In the request **Tests** tab, use **Add check** to configure up to 100 named checks in a specific order. Use the arrows to reorder, **On** to enable/disable, and **Remove** to delete a check. Configurations, including incomplete or disabled drafts, are saved/recovered with the encrypted request. Old requests start with no checks.

Supported checks are exact HTTP status, header presence, header content containing literal text, JSONPath presence, JSONPath value equality, maximum duration in milliseconds, and maximum response size in bytes. Header names are case-insensitive; content matching is case-sensitive and succeeds when any individual value contains the expected text. Time/size bounds are inclusive. JSON equality takes a literal JSON value: `2`, `true`, `null`, `"hello"`, arrays or objects. Types and structure must match; numeric scale and object-member order are ignored. JSONPath presence treats an explicitly null field as present. Assertion expected values are literals, not variable templates.

JSONPath uses Jayway 3.0.0 with a bounded Jackson provider. Supported paths select one value, for example `$.id`, `$.users[0].name`, or `$['key.with.dots']`. Filters, wildcards, recursive descent, slices, functions and scripts are rejected. Paths are limited to 64 segments. JSON response checks require a complete body no larger than 2 MiB, with depth/number/string parsing limits and unique object keys. These checks never execute JavaScript.

**Send** validates enabled checks before network traffic, then runs them against the immutable submitted check list and immutable response on a bounded worker. Edits during the request affect the next Send. **Preview URL** does not run checks or send traffic. In **Response → Test Results**, each row shows **PASS**, **FAIL**, **NOT RUN**, or **DISABLED**, expected/actual values and a reason. Hover over a truncated cell for its complete display text; display values are capped at 512 characters after redaction. The summary counts each outcome separately. HTTP 4xx/5xx responses can pass checks that expect those statuses; transport errors/timeout/cancellation give NOT RUN, never a fake pass.

Malformed/empty/oversized/truncated JSON gives NOT RUN for JSON checks while independent status/header checks can still run. For truncated downloads, a size/time already above the maximum conclusively fails; a captured measurement below the maximum is NOT RUN because the complete response is unknown. Cancel during check evaluation preserves the received response and marks unfinished checks NOT RUN. Known secrets and credential headers are masked in assertion results; raw response debugging remains unchanged. JSONPath's own debug logging is disabled to keep user paths out of diagnostics.

Complete masked check values are available in the current response; History persists check outcomes/counts with compared values omitted. Check definitions currently live in the encrypted request JSON rather than a second normalized copy. See [Step 12 implementation and verification](docs/step-12-assertions.md).

## Response extraction

In the upper request editor, open **Extract** (after **Tests**) and choose **Add extraction**. Set **Variable name** to `token` and **Response JSONPath** to `$.access_token` for a response such as `{"access_token":"example-token"}`. Send, then open **Response → Extraction Results** to see EXTRACTED, FAILED, NOT RUN or DISABLED with a reason. In a later request, enter `{{token}}` in Auth → Bearer Token, or another supported template field.

Saved requests in the same collection share a temporary manual session, including requests inside folders. Save both requests in that collection before sending the first one. Unsaved tabs have isolated sessions. Different collections/workspaces do not share extracted values. Use **Clear temporary variables** beside Preview URL when the Variables or Extract tab is selected to clear the current session, or **Tools → Clear temporary variables** to clear every session. Restart also discards all values. Manual sessions are separate from the collection runner; automated iterations use fresh temporary variables. Sessions are retained across environment changes until explicitly cleared; clear them when switching test targets.

Rules have stable IDs and saved order, can be disabled/reordered/removed, and are validated before HTTP traffic (maximum 100). Definitions persist in the encrypted request and recovery payload; old requests default to no rules. Extracted values stay in memory and never enter saved environments or templates. All extracted values are treated as secrets, including values derived from secret inputs. Result summaries and previews mask them, as do assertions on the response that produced them. The raw response viewer and explicit Save Response remain debugging tools showing actual server data.

Only properties, quoted properties, non-negative array indices and array wildcards `[*]` are accepted (64 path segments maximum); filters, recursive descent, unions, slices, functions and scripts are rejected. Missing paths or zero matches fail; multiple wildcard matches fail with their count, and a single match succeeds. A definite path selecting an array/object counts as one JSON value. Strings become their unquoted text; numbers, booleans, null, arrays and objects become JSON text. Empty strings and null are valid present values. Extracted values are literal data: response text containing `{{other}}` is not interpreted as a second variable template.

Extraction requires a complete JSON response within 2 MiB, with bounded depth and duplicate-key detection. Network errors, cancellation, malformed/empty/oversized/truncated JSON yield NOT RUN. HTTP error status responses can still contain valid extractable JSON. Each variable is limited to 65,536 characters; a temporary scope has the same 256-variable / 1,048,576-character budget as saved scopes. Failed refreshes remove their previous target instead of reusing a stale token. Independent successful rules still publish together. Session capacity failure publishes no new values and removes the attempted targets. Clearing or updating a session while a request is active prevents its late completion from overwriting newer values.

See [Step 13 implementation and verification](docs/step-13-extraction.md).

## Collection runner

Select a collection (or one of its folders/requests) in the sidebar and click **Run collection…**, beside the library actions. The separate, non-modal window keeps the main editor available. Choose **Iterations** and **Delay (ms)**, then **Start**. Save edits first: only saved request definitions are executed.

Start captures the saved collection order and the workspace's selected environment together on the SQLite worker. Requests follow the tree order: a folder's children run in their saved order at the folder's position among direct collection requests. Later saves, reordering, deletion, environment edits or environment selection changes do not alter the active snapshot. Uploads remain external file references and their bytes are not copied at Start.

Execution is sequential and runs off JavaFX: resolve variables/authentication, validate, send HTTP, evaluate assertions, extract response data, then advance. Each iteration starts with fresh temporary variables; they are shared between requests within that iteration and discarded afterwards. These values never mix with manual Send sessions or saved environments. Precedence stays request > run > selected environment > workspace.

The results table updates as requests complete and shows iteration, request outcome, HTTP status, duration, captured bytes, check counts and extraction counts. Select a row for masked expected/actual check values, extraction outcomes and failure reasons. Request outcomes (SUCCESS, HTTP ERROR, NETWORK ERROR, INVALID REQUEST, CANCELLED, NOT RUN) are separate from assertion and extraction outcomes. A valid response can fail a check while the request remains SUCCESS; HTTP 4xx/5xx responses are HTTP ERROR even when an assertion intentionally expects that status. Ordinary failures are recorded and later requests continue. Overall completion distinguishes COMPLETED, COMPLETED WITH FAILURES, CANCELLED and unexpected FAILED runs.

**Cancel run** cancels active HTTP or interrupts the delay and prevents subsequent requests from starting. Completed rows remain; the interrupted step is CANCELLED and later steps are NOT RUN. Closing the runner or application also cancels the run. Cancellation before snapshot loading finishes sends no requests. A finished/cancelled run can be started again from a fresh snapshot.

Limits: one active collection run, 1–1,000 iterations, 0–60,000 ms between request steps, at most 1,000 total executions and a 32 MiB protected saved-request snapshot budget. There is no delay before the first request or after the last. Live results stay in the runner window, while History preserves sanitized reports across closing/restart. Bodies, headers and runtime variable values are not retained in runner result rows.

See [Step 14 implementation and verification](docs/step-14-collection-runner.md).

## Execution history and reports

Use **History** in the left navigation after Send or Run collection. Request attempts include validation/network failures and cancellation; collection records include per-step outcomes and partial/cancelled progress. Select a record to reopen its report, then select a request row to inspect check/extraction outcomes. **Refresh** reloads the latest records, including completed runs whose runner window is closed.

History keeps request identity/name, HTTP status, duration, captured size, classified network errors and assertion/extraction outcomes. Headers, bodies, URLs, runtime variables and compared expected/actual values are omitted. Rule details have a global 2 MiB admission budget per report; counts remain complete when details are omitted. Payloads use current-user DPAPI; history IDs/times/kinds/status/counts remain ordinary SQLite metadata. The normal response viewer and Save Response continue to show/save actual server data.

Defaults retain the most recent 100 finished records for 30 days. In the History window, change the record/day limits and **Save retention**; cleanup applies immediately and on startup/save. Limits are 1–1,000 records and 1–365 days, with pages of 100 records. Active entries are preserved. **Clear finished history…** asks before removing finished records and keeps saved requests/environments. History survives deleting source requests/collections.

Runs save an initial entry, the first step and checkpoints at most once per second, followed by a final report. Startup marks unfinished entries INTERRUPTED; such reports can contain only their last committed checkpoint. Normal window closing cancels active work and waits for the runner's final history save before closing storage. History failure keeps the response/run result available and displays a notice. **Retry pending saves** retries up to 16 buffered records in this app session; the buffer itself does not survive a process exit.

**Export report…** writes a readable standalone HTML report or versioned JSON report from the selected record. Both formats omit compared values and payloads; HTML escapes user markup and has no scripts/external resources. Report export is capped at 8 MiB and replaces a destination atomically. See [Step 15 history](docs/step-15-history.md).

## Portable workspace import and export

**Export workspace…** exports the selected saved workspace: collections, folders, request drafts, environments, workspace/request variables, authentication settings, assertions and extraction rules, in saved order. Save open edits first. This is API Testing Platform's own JSON format (`api-testing-platform-workspace`, version 1), independent of SQLite/DPAPI. It does not import Postman or OpenAPI files, file contents, history, recovery tabs or temporary run variables.

Secret inclusion is off by default. Default export replaces secret/sensitive-name variables, credentials, literal header/query/form/cookie values, body drafts and sensitive expected values with omission markers, and removes upload paths/fragments. Full variable placeholders in structured inputs are retained, and known credential values are masked in other data fields, including URL paths. Names and non-secret configuration/variables remain. Keep confidential variables marked Secret; exported names are public metadata. Fill `{{REDACTED}}` inputs and review omitted check expectations before sending imported requests.

The explicit **Include secret values and request payloads in plain text** checkbox exports full saved values, including inactive drafts and upload path references. Files are never embedded. This choice applies only to that export and is not remembered. An exported file with secrets is ordinary plaintext; imported saved payloads are encrypted again for the receiving Windows user.

**Import workspace…** validates the entire file before changing the database, then creates a separate workspace named `… (imported)` with fresh hierarchy/environment IDs and remapped references. Existing data and unsaved tabs are kept; nothing is executed. Failed parsing, reference validation, encryption or SQL rolls back without a partial workspace. Unknown fields/versions are rejected with an explanation.

Limits: 32 MiB file, 10,000 hierarchy items, 100 environments, existing per-draft/per-variable budgets, JSON nesting depth 100 and duplicate-key detection. Export snapshot admission also caps protected source payloads at 48 MiB; default secret masking caps 4,096 distinct known credential values / 2 MiB credential characters. Oversized workspaces must be split. File parsing/writing and all SQLite/encryption work stay off JavaFX. Failed/unsupported atomic replacement leaves the previous destination intact. See [Step 16 portable transfer](docs/step-16-portable-transfer.md).

## Settings, backups and diagnostics

Open **Settings → Network, backups and diagnostics…**. Choose Direct, System or Manual proxy, an optional additional CA certificate bundle and a response capture/preview limit of 64–10,240 KiB (default 10 MiB). **Save network settings**, then restart to apply them. Certificate and hostname validation stay enabled; extra CA certificates add trust to the JVM defaults. An invalid/missing CA at startup falls back to JVM trust, preserving proxy/capture settings and displaying a notice in Settings. Proxy authentication is not provided by this settings screen. Preferences live in `operations.properties` beside the database.

The window also shows data/database/log paths and the existing history retention settings. Retention changes apply immediately. **Open Logs Folder** opens the bounded structured event logs. **Create diagnostic archive…** exports a ZIP containing only a version/settings summary and whitelisted log events. Database files, request/response payloads, URLs, credentials, variable values, paths, certificate files and proxy addresses are excluded. Logs have timestamps, levels, component names and correlation IDs; they retain at most four 512 KiB files.

**Back up database…** creates and verifies a consistent SQLite snapshot while the app is running. It includes saved workspaces, requests, environments, history and committed recovery drafts; unsaved edits still in the autosave debounce window and the separate appearance/network preference files are excluded. Database payload encryption remains tied to the current Windows profile. Use the existing **Export workspace…** for a portable JSON copy.

**Restore after restart…** verifies a completed backup, then stages it without changing the active database. **Cancel pending restore** cancels it. Close normally and restart to apply: the app keeps the current database and any WAL/SHM sidecars in `recovery-backups` before replacing them. Restore replaces the database with the snapshot, so changes made after the snapshot will be in the automatic pre-restore copy. Interrupted activation rolls back from verified safety copies before reopening SQLite. Settings/diagnostics and restore staging remain accessible when database startup fails. Backups with incompatible schemas, extra triggers/views, invalid references, corrupt data or payloads unreadable by this Windows profile are rejected. Manual backups are limited to 1 GiB; automatic safety copies remain until you remove them. Exports cannot replace managed data, settings, logs or automatic recovery copies.

See [Step 17 settings and recovery](docs/step-17-operations.md) for exact behavior and checks.

## Performance and failure recovery

Navigation indexes tree items and children once, and uses fixed-height virtualized rows. Request tabs create controls when first selected; the response code editor is created when a response arrives. Draft values remain in the view model, including inactive tabs. Network, storage, encryption, backup/restore and diagnostics run on bounded background workers. Storage lock/access/full/corruption errors show safe retry/recovery guidance while preserving editor changes.

Measured startup, navigation for 10,000 saved requests, opening eight distinct requests, a 500-request loopback run and memory use are documented with their method and limits in [Step 18 performance report](docs/step-18-performance.md). The optional workload runs with `-Dplatform.performance=true`; normal test runs skip that workload. Timing goals are measured, not imposed as hardware-dependent unit-test thresholds.

## HTTP engine

`JavaHttpExecutionEngine` implements the application module's `HttpExecutionEngine` port and uses Java 25 `HttpClient.sendAsync`. Construct one engine during background startup, reuse it, and close it on shutdown. Calls to `execute` return immediately with a `RequestExecution` handle; its `result()` stage completes with an immutable `ExecutionResult`. Use `cancel()` on the handle to cancel transport work. Cancelling a copy returned by `result().toCompletableFuture()` does not cancel transport work.

Supported methods are GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS over HTTP or HTTPS. Configuration includes ordered, enabled query parameters and headers (including duplicate names), explicit request cookies, UTF-8 JSON/text, URL-encoded forms, multipart text/file parts, timeout, redirect policy, and HTTP protocol preference. Disabled rows are neither validated nor sent. An explicit Content-Type overrides JSON/text/form defaults; multipart Content-Type and boundary are generated automatically and cannot be overridden. Cookies are request-local; the engine does not keep a shared cookie jar.

The timeout covers the complete operation, including dispatch, redirects, and response body consumption. Redirect policies are NEVER, NORMAL (prevents HTTPS-to-HTTP downgrade), and ALWAYS. Redirects are limited and cycles are detected. POST becomes GET for 301/302; 303 becomes GET except for HEAD; 307/308 preserve the method and body. Authorization, Proxy-Authorization, and Cookie credentials are removed when a redirect changes scheme, hostname, or effective port.

Defaults allow 16 concurrent requests, 4 dispatch workers, 4 HTTP workers, a 10 MiB response body, an 8 MiB request body, and 10 redirects. Executor queues and response buffering are bounded; excess requests fail with RESOURCE_LIMIT. `EngineLimits` configures those limits. HTTP/2 is a preference and can negotiate down to HTTP/1.1; the result records the negotiated protocol. Standard certificate and hostname validation remain enabled.

Results contain status, immutable headers, defensively copied raw body bytes, body size, elapsed milliseconds, start timestamp, and an optional typed error. HTTP 4xx/5xx are ordinary HTTP responses; network failures have status 0 and an error. Error messages omit raw exception messages and request secrets. Oversized responses stop downloading at the byte limit and retain the prefix, status, and headers with a RESPONSE_TOO_LARGE error. `bodyTruncated()` identifies this case; its size is the captured size, not the full server body size. Other failed operations do not expose partial content. A read-only `bodyBuffer()` supports decoding and saving without another full body copy.

Completion handlers run on background threads (or on the caller for immediate admission failures/cancellation). Keep handlers short and use `Platform.runLater` when updating JavaFX controls. Do not block the JavaFX thread waiting for a result.

MockWebServer integration tests cover request construction, every method, HTTP/1.1, TLS and HTTP/2, redirects, deadlines, cancellation, bounded admission and body sizes, malformed input, DNS and connection failures, and engine shutdown. HTTPS tests generate local certificates. The reserved `.invalid` domain is used for the DNS failure test; all HTTP traffic in the tests targets local servers.

## Request editor

Enter an absolute HTTP(S) URL, select the method, configure the editor tabs, and press Send:

- Params / Headers / Cookies: Add row, edit name/value, uncheck rows to exclude them, or Remove selected. Text fields update the draft immediately, without requiring a separate cell commit. Each table supports duplicate names and up to 1000 rows.
- Body: None, Raw JSON, Text, x-www-form-urlencoded, or multipart/form-data. Forms use UTF-8 URL encoding. Multipart supports Add text part and Add file, editable field names and file media types. Files stream from disk off the JavaFX thread; the request limit includes multipart framing. File paths and content must remain accessible until sending finishes, including redirects.
- Format JSON validates and formats on a bounded worker. Syntax errors identify line and column. Formatting has depth and output limits; stale formatting never overwrites newer edits.
- Settings: timeout in seconds (including fractional seconds, positive and at most 86400), redirect policy, and preferred HTTP version.
- Variables: request-local ordinary or secret values used by template resolution.
- Auth: No auth, Bearer Token, Basic Auth and API Key helpers with optional variables and hidden credentials.
- Tests: ordered declarative response checks and field-level configuration errors; no JavaScript is executed.

Validation runs before network traffic. Invalid URLs, JSON, HEAD bodies, cookie/header injection, transport-managed headers, and unsafe multipart metadata produce field/row-level errors. Invalid upload files are rejected by infrastructure before HTTP dispatch. Validation messages do not echo request secrets. A HEAD request must use None; GET bodies are allowed but may be rejected by individual servers.

The mutable draft is separate from the immutable submission snapshot. Sending, errors and cancellation never reset it; edits made during an operation affect the next Send only. Switching body modes retains raw/form/multipart drafts. An asterisk identifies unsaved edits. Phase 2 adds explicit saving and encrypted temporary recovery snapshots as described above.

## Response viewer

The response panel has Body, Headers, Cookies, and Test Results tabs, plus status code/text, full-download duration, captured size, and content-type metadata. Status text uses conventional HTTP labels (HTTP/2 does not carry a reason phrase). Cancel remains available throughout the download and propagates to the HTTP future.

The Body tab automatically detects valid JSON and provides Pretty and Raw modes. JSON formatting uses a streaming parser with a nesting limit and a 512 KiB input/output budget. Larger, deeply nested, malformed, or incomplete JSON remains available in Raw mode. RichTextFX 0.11.7 provides selectable text and JSON token highlighting. No response HTML or scripts are executed.

Bodies are rendered in 64 KiB character pages. Find next/previous searches the entire captured text in the selected mode and moves to a matching page; searches are case-sensitive and wrap. Ctrl+C or Copy selection copies the selected visible text. Charset decoding uses Content-Type with UTF-8 as the fallback. Unsupported charset names and truncation are identified in the viewer.

Save Response opens a Windows file dialog and writes the original captured bytes on a background thread using a temporary file and atomic replacement where supported. Pretty formatting and text decoding do not alter saved bytes. Oversized responses clearly show that search and saving cover only the retained prefix. The viewer also enforces its own 10 MiB limit when receiving results from another engine implementation.

Loading, success, HTTP error, DNS, TLS, connection refusal, redirect failure, other network errors, timeout, neutral cancellation, and response-too-large states are distinct. Duplicate response headers are retained. Cookies show their name, value, domain, path, expiry, Max-Age, Secure, HttpOnly, SameSite, and original Set-Cookie text. `ResponsePanel.showTests` displays the step 12 assertion results with separate outcomes, expected/actual values and reasons. An unconfigured request shows an explicit empty state.

Formatting, searches, and saves use bounded background executors; stale preparation/search results are ignored when a new response arrives. Viewer work is cancelled when the window closes. Tests include actual JavaFX windows and a local MockWebServer-to-viewer debugging flow; `clean verify` therefore requires access to a Windows desktop and local loopback sockets.

