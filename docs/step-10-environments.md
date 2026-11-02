# Step 10 Environments and variable resolution

The application now supports workspace defaults, named environments and request-local variables. The next planned increment is step 11, authentication helpers and broader redaction. No commit or push is performed; the user manages commits.

## Delivered behavior

- Workspace-scoped environment create, rename/edit, delete and selection, with a persistent No environment option.
- A workspace variable editor and a request Variables tab. Secret rows use password fields by default and require an explicit reveal action.
- A pure bounded resolver: request values override environment values, which override workspace defaults. Recursive references, repeated placeholders and escaped literal placeholders are supported.
- Resolution in the URL, query parameters, headers, cookies, raw JSON/text, form fields and multipart text/metadata. External file paths remain literal.
- Preview target resolves and validates without network traffic. Send captures editor values and workspace environment state, resolves off the JavaFX thread, validates the actual request and displays a masked target before execution.
- Unknown variables, cycles, invalid placeholders, invalid resolved URLs/JSON and size/depth violations prevent HTTP execution. Error messages omit variable values. Secret-derived substitutions remain masked in the target preview.
- Saved requests retain templates and local variables; resolving and switching environments never overwrite them. Workspace/tab navigation keeps environment controls aligned with the active request's workspace while preserving unsaved tabs.
- New compatible V002 migration extends the existing environment/variable tables and adds encrypted workspace defaults and selected environment state. Environment updates use optimistic versions, preserve IDs for unchanged variable names, and run transactionally. Foreign keys clean up workspace data and clear a deleted active environment.
- Environment/workspace values and request-local draft values are protected by the existing current-user DPAPI adapter. No plaintext storage fallback is added.

## Verification

Verified on Windows with Temurin JDK 25.0.5 using `mvnw.cmd --offline --no-transfer-progress clean install`. All 10 reactor projects succeeded; 107 tests passed with zero failures, errors or skipped tests: domain 5, application 15, HTTP client 30, SQLite persistence 11, Windows security 2, UI 19 and app 25. `git diff --check` passed. The local reactor artifacts were installed so the next JavaFX launch uses this implementation. Screenshots of the new controls and masked variable editor were inspected, together with the existing light/graphite appearance checks.

Automated checks use temporary databases, current-user DPAPI and local MockWebServer instances. Resolver tests cover precedence, nesting, repetition, unknown values, cycles, escaping, disabled rows, Unicode, secret propagation, invalid resolved URLs/JSON and bounded expansion. Persistence tests cover CRUD, variable order, restart, stale writes, duplicate environment names, workspace isolation, encrypted payloads, selected environment deletion and upgrade from V001 with an old request JSON payload.

JavaFX integration tests create/edit masked variables through real dialogs, restore environment selection after restart, preview without sending, switch between two local servers, inspect actual resolved URLs/headers/bodies, preserve saved templates, reject unresolved requests without HTTP traffic, and retain unsaved tabs while switching workspaces. Existing HTTP, response, storage, recovery and appearance checks are included in the final full build.

## Manual check

1. Restart the application after installing the reactor artifacts.
2. Under ENVIRONMENT, choose New, name it Local, and add `baseUrl` with the address of your API. Save, then select Local in the environment selector.
3. Enter `{{baseUrl}}/posts/2`, select GET and press Preview target. Verify the address, then Send.
4. Add another environment with a different baseUrl and select it. Preview/send the same template; the destination should change.
5. Use the request Variables tab to override a value from the environment. Mark a value Secret and verify that the variable editor and target preview hide it by default.
6. Try `{{missing}}`; the request must remain unsent and show a field error.
7. Save the request, restart and verify that its template/local variables, environments, workspace defaults and selected environment are retained.

## Boundaries

This increment does not implement Bearer/Basic/API Key helper controls, assertions, response extraction, collection runs, history or import/export. Secret masking applies to variable editors and target previews; explicit literal credentials in the original request fields and server response bodies remain visible in the debugging UI. Request-local variables are saved as part of the existing encrypted request/draft payload format, with missing old fields defaulting to an empty list.
