# API Testing Platform

API Testing Platform is a local Windows desktop application for creating, organizing, and testing HTTP APIs. The application is designed for Windows 10 and Windows 11 and keeps its application data on the local computer.

This repository contains the multi-module project foundation, the JavaFX workspace, an asynchronous HTTP engine, and a functional response viewer. Send and Cancel are connected to the engine through its application interface. Database functionality and collection execution are subsequent increments.

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

## HTTP engine

`JavaHttpExecutionEngine` implements the application module's `HttpExecutionEngine` port and uses Java 25 `HttpClient.sendAsync`. Construct one engine during background startup, reuse it, and close it on shutdown. Calls to `execute` return immediately with a `RequestExecution` handle; its `result()` stage completes with an immutable `ExecutionResult`. Use `cancel()` on the handle to cancel transport work. Cancelling a copy returned by `result().toCompletableFuture()` does not cancel transport work.

Supported methods are GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS over HTTP or HTTPS. Configuration includes ordered query parameters and headers (including duplicate names), explicit request cookies, UTF-8 JSON or text bodies, timeout, redirect policy, and HTTP protocol preference. An explicit Content-Type header overrides the body type's default. Cookies are request-local; the engine does not keep a shared cookie jar.

The timeout covers the complete operation, including dispatch, redirects, and response body consumption. Redirect policies are NEVER, NORMAL (prevents HTTPS-to-HTTP downgrade), and ALWAYS. Redirects are limited and cycles are detected. POST becomes GET for 301/302; 303 becomes GET except for HEAD; 307/308 preserve the method and body. Authorization, Proxy-Authorization, and Cookie credentials are removed when a redirect changes scheme, hostname, or effective port.

Defaults allow 16 concurrent requests, 4 dispatch workers, 4 HTTP workers, a 10 MiB response body, an 8 MiB request body, and 10 redirects. Executor queues and response buffering are bounded; excess requests fail with RESOURCE_LIMIT. `EngineLimits` configures those limits. HTTP/2 is a preference and can negotiate down to HTTP/1.1; the result records the negotiated protocol. Standard certificate and hostname validation remain enabled.

Results contain status, immutable headers, defensively copied raw body bytes, body size, elapsed milliseconds, start timestamp, and an optional typed error. HTTP 4xx/5xx are ordinary HTTP responses; network failures have status 0 and an error. Error messages omit raw exception messages and request secrets. Oversized responses stop downloading at the byte limit and retain the prefix, status, and headers with a RESPONSE_TOO_LARGE error. `bodyTruncated()` identifies this case; its size is the captured size, not the full server body size. Other failed operations do not expose partial content. A read-only `bodyBuffer()` supports decoding and saving without another full body copy.

Completion handlers run on background threads (or on the caller for immediate admission failures/cancellation). Keep handlers short and use `Platform.runLater` when updating JavaFX controls. Do not block the JavaFX thread waiting for a result.

MockWebServer integration tests cover request construction, every method, HTTP/1.1, TLS and HTTP/2, redirects, deadlines, cancellation, bounded admission and body sizes, malformed input, DNS and connection failures, and engine shutdown. HTTPS tests generate local certificates. The reserved `.invalid` domain is used for the DNS failure test; all HTTP traffic in the tests targets local servers.

## Response viewer

The response panel has Body, Headers, Cookies, and Test Results tabs, plus status, duration, captured size, and content-type metadata. Enter a URL and press Send to inspect a real response; Cancel stops the active request. The initial request editor still uses an empty body and no additional parameters or headers.

The Body tab automatically detects valid JSON and provides Pretty and Raw modes. JSON formatting uses a streaming parser with a nesting limit and a 512 KiB input/output budget. Larger, deeply nested, malformed, or incomplete JSON remains available in Raw mode. RichTextFX 0.11.7 provides selectable text and JSON token highlighting. No response HTML or scripts are executed.

Bodies are rendered in 64 KiB character pages. Find next/previous searches the entire captured text in the selected mode and moves to a matching page; searches are case-sensitive and wrap. Ctrl+C or Copy selection copies the selected visible text. Charset decoding uses Content-Type with UTF-8 as the fallback. Unsupported charset names and truncation are identified in the viewer.

Save body opens a Windows file dialog and writes the original captured bytes on a background thread using a temporary file and atomic replacement where supported. Pretty formatting and text decoding do not alter saved bytes. Oversized responses clearly show that search and saving cover only the retained prefix. The viewer also enforces its own 10 MiB limit when receiving results from another engine implementation.

Loading, success, HTTP error, network error, timeout, cancelled, and response-too-large states are distinct. Duplicate response headers are retained. Cookies show their name, value, domain, path, expiry, Max-Age, Secure, HttpOnly, SameSite, and original Set-Cookie text. `ResponsePanel.showTests` accepts assertion results; this step displays them and does not implement an assertion runner. Before results are supplied, the tab explicitly says that no assertions are configured.

Formatting, searches, and saves use bounded background executors; stale preparation/search results are ignored when a new response arrives. Viewer work is cancelled when the window closes. Tests include actual JavaFX windows and a local MockWebServer-to-viewer debugging flow; `clean verify` therefore requires access to a Windows desktop and local loopback sockets.

