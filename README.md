# API Testing Platform

API Testing Platform is a local Windows desktop application for creating, organizing, and testing HTTP APIs. The application is designed for Windows 10 and Windows 11 and keeps its application data on the local computer.

Phases 1–4 (steps 4–13) are implemented: the JavaFX workspace includes a complete request editor, asynchronous HTTP execution, a response viewer, SQLite-backed workspaces/collections/requests, protected crash-recovery drafts, environments, variable resolution, authentication helpers, declarative response assertions and response extraction into temporary variables. Sequential collection execution is the next increment (step 14).

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
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
.\mvnw.cmd clean verify
```

## Run the JavaFX application

Install the reactor artifacts locally, then launch the `app` module:

```powershell
.\mvnw.cmd install
.\mvnw.cmd -f app\pom.xml javafx:run
```

The development build requires Java 25. A future packaging increment will use `jlink` and `jpackage` so installed users do not need a separate Java installation.

## Appearance

Use **Appearance…** in the top toolbar to select **Dark graphite** (the default) or **Light** and choose an accent color. Changes preview immediately across the workspace, response viewer and application dialogs. **Save** keeps the selection for future launches; **Cancel** restores the previous appearance. **Reset to defaults** previews the original graphite palette, which can then be saved.

Preferences are stored separately from requests in `%LOCALAPPDATA%\API Testing Platform\appearance.properties`. Saving runs in the background; failures stay visible in the dialog and can be retried. Missing or invalid preferences fall back to the default theme. The Windows title bar and native file chooser follow Windows settings.

## Local storage and saved requests

The app opens a SQLite database at `%LOCALAPPDATA%\API Testing Platform\platform.sqlite`, outside the project and installation directories. Startup migrations and every read/write run on a bounded serialized background queue. SQLite uses WAL, foreign keys, a busy timeout and short write transactions. A separate process lock prevents two app instances from editing the same data directory simultaneously.

Use the workspace selector and **+ Workspace / + Collection / + Folder / + Request** actions to build the navigation tree. Newly created items are selected automatically; creating a workspace switches to it. If Folder/Request is pressed without a selected parent, a dialog asks for the destination in the current workspace. If no collection exists, an explanation offers to create one. Folders are exactly one level deep inside a collection; requests belong to a collection, directly or through a folder. Select an item to Rename, Delete, Move up or Move down. Workspace actions are also available through **Workspace…**. Deleting a parent asks for confirmation and deletes its descendants.

Double-click a request (or press Enter / Open) to open a tab. **New tab** creates a scratch request. **Save** / Ctrl+S saves an existing request; a new request asks for its collection/folder and name. **Save As…** creates an independent saved request. All editor settings and inactive body-mode drafts survive saving and restart. Saving incomplete input is allowed; Send still validates before network traffic. Files are referenced by path, not embedded. Edits made while saving remain dirty if they were not part of the saved snapshot. Version checks prevent stale tabs from silently overwriting newer data.

Unsaved tabs have an asterisk. Closing a dirty tab offers Save / Discard / Cancel. Closing the window asks before discarding unsaved work; choose Cancel and Save first to keep edits permanently. Database errors leave dirty editor content intact.

Recovery snapshots are debounced for 750 ms after the last edit, not written on every keystroke. The recovery indicator confirms successful snapshots; failures are visible and **Retry recovery save** retries dirty tabs. An abnormal exit leaves the latest committed snapshots available at the next startup. **Recover tabs…** can reopen the recovery prompt later. Recovery creates dirty tabs without overwriting saved requests and is idempotent. Normal discard/close removes the corresponding temporary snapshots only after successful storage operations. Changes made within the debounce window before a crash may not have reached disk.

Complete request and recovery payloads are versioned JSON envelopes in TEXT columns, protected with Windows DPAPI for the current user. This covers Authorization, cookies, URL/query secrets and arbitrary body values without attempting to guess which values are secret. There is no plaintext fallback. Names, hierarchy and timestamps are ordinary database metadata, not encrypted. Protected payloads depend on the Windows user profile and are not a portable export format. Transient byte buffers are wiped where feasible; Java strings cannot be reliably erased.

`schema_version` records ordered SHA-256-checksummed migrations. Changed checksums or unknown schema versions are rejected without replacing data. Before an incompatible migration, `VACUUM INTO` creates a consistent backup under `migration-backups` next to the database; migration SQL and version registration are transactional and roll back on failure. Do not manually copy a live database without its WAL; close the app before file-level backup. Manual backup/restore UI belongs to a later phase.

Environments and variables are implemented in step 10. Steps 12–13 save ordered assertion and extraction definitions inside the encrypted request payload alongside editor settings. The schema also prepares normalized assertion/run/result tables; run history persistence remains future work. See [Phase 2 implementation and verification](docs/phase-2-implementation.md), [Step 10 implementation](docs/step-10-environments.md) [Step 12 assertions](docs/step-12-assertions.md) and [Step 13 extraction](docs/step-13-extraction.md).

## Environments and variables

The sidebar's **ENVIRONMENT** selector belongs to the selected workspace. **New**, **Edit**, and **Delete** manage named environments. Select **No environment** to use workspace, temporary session and request values. **Workspace variables…** edits defaults shared by requests in that workspace. Switching workspaces selects a request tab for that workspace, and selecting an existing tab restores its workspace. Unsaved tabs from other workspaces remain open. The selected environment is saved per workspace.

In an environment, add `baseUrl = http://localhost:8080` and save. Select that environment, enter `{{baseUrl}}/posts/2` in the request URL, and use **Preview target** to inspect the resolved address without sending traffic. **Send** resolves and validates a fresh immutable snapshot again. The saved URL remains `{{baseUrl}}/posts/2`, so selecting another environment changes the destination without editing the saved template.

The request editor's **Variables** tab contains request-local values, which are saved with that request and included in encrypted recovery drafts. Precedence is **request > temporary run/session > selected environment > workspace**. Names are case-sensitive, start with a letter or underscore, and can contain letters, digits, dots, underscores and hyphens. Duplicate names within one scope are rejected. Each scope supports up to 256 variables and 1,048,576 combined name/value characters; individual values are limited to 65,536 characters. A workspace supports up to 100 environments.

Placeholders resolve in the URL, enabled query/header/cookie rows, JSON/text bodies, URL-encoded fields and multipart text/metadata. File paths are kept as literal external-file references. Variable values can reference other variables; nested references follow the same precedence. Unknown required variables, cycles, malformed placeholders, excessive nesting/expansion and invalid resolved URLs or bodies stop the request before network traffic and mark the relevant fields. Use `\{{name}}` to send literal `{{name}}` text. Substitution is textual: JSON values must still form valid JSON after substitution, and URL path values must already use appropriate URL encoding. Structured query/form values are encoded by the existing HTTP engine.

Mark confidential variables **Secret**. Their values are hidden in the editor unless **Show secret values** is selected. Preview masks secret substitutions as `[secret]` (URL-encoded in structured query values), including values derived from secret variables. A preview is a snapshot: prepare it again after changing inputs or environments. Every Send prepares its own target. Environment/workspace values, including ordinary values, are protected with current-user Windows DPAPI on disk. Names, secret flags and environment metadata are not encrypted. No resolved values are written back into saved templates or logged. Existing response viewing remains a debugging view and can display data echoed by the server.

## Authentication and secret handling

The request **Auth** tab supports **No auth**, **Bearer Token**, **Basic Auth**, and **API Key** in a header or query parameter. Enter literal values or templates such as `{{token}}`; the same request > temporary run/session > environment > workspace precedence applies. Tokens, passwords and key values are password fields by default. **Show credentials** reveals them explicitly. Switching modes retains inactive drafts, but only the selected mode is resolved or sent. Save and crash recovery protect all authentication settings inside the existing current-user DPAPI payload, with no plaintext fallback. Old saved requests default to No auth.

Bearer fields accept the token only, without the `Bearer` prefix. Basic Auth uses UTF-8 before Base64 encoding; usernames cannot contain a colon and neither username nor password can contain control characters. API Key requires a name and non-empty value; transport-managed/invalid header names and unsupported header characters are rejected. An enabled manual header/query parameter conflicting with the Auth helper is rejected rather than overwritten. In No auth mode, explicit Authorization headers in Headers still work.

Preview and Send resolve authentication on a background worker without changing saved templates. The target preview hides literal API keys as well as secret variables and secret-derived values. Missing variables and invalid/conflicting auth mark the Auth tab before any traffic. Header credentials are applied only to the immutable execution snapshot. API key values in structured query parameters use the existing URL encoding.

On a cross-origin redirect, generated custom API key headers and headers containing secret variables are stripped alongside Authorization/Proxy-Authorization/Cookie. Query parameters identified as credentials are removed from a cross-origin redirect target, and remain stripped on subsequent redirects. Same-origin redirects retain header credentials and follow the server's query string. TLS certificate and hostname verification remain enabled; no bypass setting is provided.

The shared `SecretRedactor` masks Authorization, Proxy-Authorization, Cookie, Set-Cookie, custom authentication fields and known secret values at text/header/URL boundaries. `RedactedRequestSummary` provides a non-portable diagnostic JSON codec that omits bodies and cookie/query values and redacts credential headers/URLs. Its tests verify that ordinary summaries contain no credential plaintext. History, logging/diagnostic archives and portable import/export user flows remain later steps; those flows must use this boundary. No imported scripts are executed. The interactive response viewer and explicit Save Response still show/save actual server data, which can include echoed credentials.

See [Step 11 implementation and verification](docs/step-11-authentication.md).

## Response checks

In the request **Tests** tab, use **Add check** to configure up to 100 named checks in a specific order. Use the arrows to reorder, **On** to enable/disable, and **Remove** to delete a check. Configurations, including incomplete or disabled drafts, are saved/recovered with the encrypted request. Old requests start with no checks.

Supported checks are exact HTTP status, header presence, header content containing literal text, JSONPath presence, JSONPath value equality, maximum duration in milliseconds, and maximum response size in bytes. Header names are case-insensitive; content matching is case-sensitive and succeeds when any individual value contains the expected text. Time/size bounds are inclusive. JSON equality takes a literal JSON value: `2`, `true`, `null`, `"hello"`, arrays or objects. Types and structure must match; numeric scale and object-member order are ignored. JSONPath presence treats an explicitly null field as present. Assertion expected values are literals, not variable templates.

JSONPath uses Jayway 3.0.0 with a bounded Jackson provider. Supported paths select one value, for example `$.id`, `$.users[0].name`, or `$['key.with.dots']`. Filters, wildcards, recursive descent, slices, functions and scripts are rejected. Paths are limited to 64 segments. JSON response checks require a complete body no larger than 2 MiB, with depth/number/string parsing limits and unique object keys. These checks never execute JavaScript.

**Send** validates enabled checks before network traffic, then runs them against the immutable submitted check list and immutable response on a bounded worker. Edits during the request affect the next Send. **Preview target** does not run checks or send traffic. In **Response → Test Results**, each row shows **PASS**, **FAIL**, **NOT RUN**, or **DISABLED**, expected/actual values and a reason. Hover over a truncated cell for its complete display text; display values are capped at 512 characters after redaction. The summary counts each outcome separately. HTTP 4xx/5xx responses can pass checks that expect those statuses; transport errors/timeout/cancellation give NOT RUN, never a fake pass.

Malformed/empty/oversized/truncated JSON gives NOT RUN for JSON checks while independent status/header checks can still run. For truncated downloads, a size/time already above the maximum conclusively fails; a captured measurement below the maximum is NOT RUN because the complete response is unknown. Cancel during check evaluation preserves the received response and marks unfinished checks NOT RUN. Known secrets and credential headers are masked in assertion results; raw response debugging remains unchanged. JSONPath's own debug logging is disabled to keep user paths out of diagnostics.

Check results are available for the current response; persistent execution history belongs to step 15. Check definitions currently live in the encrypted request JSON rather than a second normalized copy. See [Step 12 implementation and verification](docs/step-12-assertions.md).

## Response extraction

In the upper request editor, open **Extract** (after **Tests**) and choose **Add extraction**. Set **Variable name** to `token` and **Response JSONPath** to `$.access_token` for a response such as `{"access_token":"example-token"}`. Send, then open **Response → Extraction Results** to see EXTRACTED, FAILED, NOT RUN or DISABLED with a reason. In a later request, enter `{{token}}` in Auth → Bearer Token, or another supported template field.

Saved requests in the same collection share a temporary manual session, including requests inside folders. Save both requests in that collection before sending the first one. Unsaved tabs have isolated sessions. Different collections/workspaces do not share extracted values. Use **Clear temporary variables** beside Preview target to clear the current session, or the top toolbar action to clear every session. Restart also discards all values. These manual sessions are a way to exercise data flow before the sequential collection runner in step 14; each future automated run will own a fresh `RunVariables` instance. Sessions are retained across environment changes until explicitly cleared; clear them when switching test targets.

Rules have stable IDs and saved order, can be disabled/reordered/removed, and are validated before HTTP traffic (maximum 100). Definitions persist in the encrypted request and recovery payload; old requests default to no rules. Extracted values stay in memory and never enter saved environments or templates. All extracted values are treated as secrets, including values derived from secret inputs. Result summaries and previews mask them, as do assertions on the response that produced them. The raw response viewer and explicit Save Response remain debugging tools showing actual server data.

Only properties, quoted properties, non-negative array indices and array wildcards `[*]` are accepted (64 path segments maximum); filters, recursive descent, unions, slices, functions and scripts are rejected. Missing paths or zero matches fail; multiple wildcard matches fail with their count, and a single match succeeds. A definite path selecting an array/object counts as one JSON value. Strings become their unquoted text; numbers, booleans, null, arrays and objects become JSON text. Empty strings and null are valid present values. Extracted values are literal data: response text containing `{{other}}` is not interpreted as a second variable template.

Extraction requires a complete JSON response within 2 MiB, with bounded depth and duplicate-key detection. Network errors, cancellation, malformed/empty/oversized/truncated JSON yield NOT RUN. HTTP error status responses can still contain valid extractable JSON. Each variable is limited to 65,536 characters; a temporary scope has the same 256-variable / 1,048,576-character budget as saved scopes. Failed refreshes remove their previous target instead of reusing a stale token. Independent successful rules still publish together. Session capacity failure publishes no new values and removes the attempted targets. Clearing or updating a session while a request is active prevents its late completion from overwriting newer values.

See [Step 13 implementation and verification](docs/step-13-extraction.md).

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

