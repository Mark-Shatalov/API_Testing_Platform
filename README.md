# API Testing Platform

API Testing Platform is a local Windows desktop application for creating, organizing, and testing HTTP APIs. The application is designed for Windows 10 and Windows 11 and keeps its application data on the local computer.

Phases 1–2 (steps 4–9) are implemented: the JavaFX workspace includes a complete request editor, asynchronous HTTP execution, a response viewer, SQLite-backed workspaces/collections/requests, and protected crash-recovery drafts. Environment resolution, assertions and collection execution are subsequent increments.

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

## Local storage and saved requests

The app opens a SQLite database at `%LOCALAPPDATA%\API Testing Platform\platform.sqlite`, outside the project and installation directories. Startup migrations and every read/write run on a bounded serialized background queue. SQLite uses WAL, foreign keys, a busy timeout and short write transactions. A separate process lock prevents two app instances from editing the same data directory simultaneously.

Use the workspace selector and **+ Workspace / + Collection / + Folder / + Request** actions to build the navigation tree. Newly created items are selected automatically; creating a workspace switches to it. If Folder/Request is pressed without a selected parent, a dialog asks for the destination in the current workspace. If no collection exists, an explanation offers to create one. Folders are exactly one level deep inside a collection; requests belong to a collection, directly or through a folder. Select an item to Rename, Delete, Move up or Move down. Workspace actions are also available through **Workspace…**. Deleting a parent asks for confirmation and deletes its descendants.

Double-click a request (or press Enter / Open) to open a tab. **New tab** creates a scratch request. **Save** / Ctrl+S saves an existing request; a new request asks for its collection/folder and name. **Save As…** creates an independent saved request. All editor settings and inactive body-mode drafts survive saving and restart. Saving incomplete input is allowed; Send still validates before network traffic. Files are referenced by path, not embedded. Edits made while saving remain dirty if they were not part of the saved snapshot. Version checks prevent stale tabs from silently overwriting newer data.

Unsaved tabs have an asterisk. Closing a dirty tab offers Save / Discard / Cancel. Closing the window asks before discarding unsaved work; choose Cancel and Save first to keep edits permanently. Database errors leave dirty editor content intact.

Recovery snapshots are debounced for 750 ms after the last edit, not written on every keystroke. The recovery indicator confirms successful snapshots; failures are visible and **Retry recovery save** retries dirty tabs. An abnormal exit leaves the latest committed snapshots available at the next startup. **Recover tabs…** can reopen the recovery prompt later. Recovery creates dirty tabs without overwriting saved requests and is idempotent. Normal discard/close removes the corresponding temporary snapshots only after successful storage operations. Changes made within the debounce window before a crash may not have reached disk.

Complete request and recovery payloads are versioned JSON envelopes in TEXT columns, protected with Windows DPAPI for the current user. This covers Authorization, cookies, URL/query secrets and arbitrary body values without attempting to guess which values are secret. There is no plaintext fallback. Names, hierarchy and timestamps are ordinary database metadata, not encrypted. Protected payloads depend on the Windows user profile and are not a portable export format. Transient byte buffers are wiped where feasible; Java strings cannot be reliably erased.

`schema_version` records ordered SHA-256-checksummed migrations. Changed checksums or unknown schema versions are rejected without replacing data. Before an incompatible migration, `VACUUM INTO` creates a consistent backup under `migration-backups` next to the database; migration SQL and version registration are transactional and roll back on failure. Do not manually copy a live database without its WAL; close the app before file-level backup. Manual backup/restore UI belongs to a later phase.

The schema also prepares environments, variables, assertions, runs, request results and assertion results. Their editors, execution and history are not implemented by Phase 2. See [Phase 2 implementation and verification](docs/phase-2-implementation.md).

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
- Auth and Tests explicitly identify future-phase functionality. Explicit Authorization headers already work via Headers; no JavaScript is executed.

Validation runs before network traffic. Invalid URLs, JSON, HEAD bodies, cookie/header injection, transport-managed headers, and unsafe multipart metadata produce field/row-level errors. Invalid upload files are rejected by infrastructure before HTTP dispatch. Validation messages do not echo request secrets. A HEAD request must use None; GET bodies are allowed but may be rejected by individual servers.

The mutable draft is separate from the immutable submission snapshot. Sending, errors and cancellation never reset it; edits made during an operation affect the next Send only. Switching body modes retains raw/form/multipart drafts. An asterisk identifies unsaved edits. Phase 2 adds explicit saving and encrypted temporary recovery snapshots as described above.

## Response viewer

The response panel has Body, Headers, Cookies, and Test Results tabs, plus status code/text, full-download duration, captured size, and content-type metadata. Status text uses conventional HTTP labels (HTTP/2 does not carry a reason phrase). Cancel remains available throughout the download and propagates to the HTTP future.

The Body tab automatically detects valid JSON and provides Pretty and Raw modes. JSON formatting uses a streaming parser with a nesting limit and a 512 KiB input/output budget. Larger, deeply nested, malformed, or incomplete JSON remains available in Raw mode. RichTextFX 0.11.7 provides selectable text and JSON token highlighting. No response HTML or scripts are executed.

Bodies are rendered in 64 KiB character pages. Find next/previous searches the entire captured text in the selected mode and moves to a matching page; searches are case-sensitive and wrap. Ctrl+C or Copy selection copies the selected visible text. Charset decoding uses Content-Type with UTF-8 as the fallback. Unsupported charset names and truncation are identified in the viewer.

Save Response opens a Windows file dialog and writes the original captured bytes on a background thread using a temporary file and atomic replacement where supported. Pretty formatting and text decoding do not alter saved bytes. Oversized responses clearly show that search and saving cover only the retained prefix. The viewer also enforces its own 10 MiB limit when receiving results from another engine implementation.

Loading, success, HTTP error, DNS, TLS, connection refusal, redirect failure, other network errors, timeout, neutral cancellation, and response-too-large states are distinct. Duplicate response headers are retained. Cookies show their name, value, domain, path, expiry, Max-Age, Secure, HttpOnly, SameSite, and original Set-Cookie text. `ResponsePanel.showTests` accepts assertion results; this step displays them and does not implement an assertion runner. Before results are supplied, the tab explicitly says that no assertions are configured.

Formatting, searches, and saves use bounded background executors; stale preparation/search results are ignored when a new response arrives. Viewer work is cancelled when the window closes. Tests include actual JavaFX windows and a local MockWebServer-to-viewer debugging flow; `clean verify` therefore requires access to a Windows desktop and local loopback sockets.

