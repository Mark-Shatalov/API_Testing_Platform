# Interface usability

## Design direction

A Windows API workbench with one starting point: enter a URL and send a request.
Keep the existing graphite and light themes, Segoe UI for navigation and Consolas for response data.
Base colours: graphite `#1b1d21`, surface `#22252a`, text `#e8eaf0`, muted text `#abb2c0`, accent `#818cf8`.
Use the accent for Send and keyboard focus, rather than decorative elements.

```text
API Testing Platform       New request  Save  History  Workspace  Tools  Settings
Workspace selector         Request tabs
Environment selector       Method  URL                                  Send
Collections   New / Add     Request configuration
Find saved requests        --------------------------------------------------
Collection tree            Response body and results
Open / Run / Organize
Status and draft recovery feedback
```

## Interaction decisions

- Keep request creation, saving, sending and history directly accessible.
- Put workspace transfer, recovery and preferences in named menus.
- Keep environment selection visible; expand environment management only when needed.
- Explain empty states and offer a sample URL without sending automatically.
- Search saved item names while retaining their collection and folder context.
- Explain optional editor tabs with tooltips; keep existing configuration available.
- Open the relevant section when validation fails and show row error columns only when needed.
- Show cancellation only during an operation and response tools after a response arrives.
- Preserve shortcuts, themes, unsaved drafts and execution and storage behaviour.

This direction follows the existing developer tool rather than applying a landing-page design.
Clarity comes from grouping actions and reducing simultaneous choices.

## Verification

Verified on 4 November 2026 with JDK 25 on Windows. The full offline Maven install,
including the optional performance workload, passed 271 tests with no failures,
errors or skips. The workload includes 10,000 saved requests and 500 local requests.

Four new integration tests cover the sample request without automatic network
execution, opening the invalid editor section, searching without losing hierarchy
or draft edits, and command availability and layout at the minimum window size.
Graphite and light screenshots were inspected for the starter and populated editor
at 1050 by 700, as well as the normal response view. Existing integration tests
still exercise save/reopen, environment editing, authentication, extraction,
collection runs, history, workspace transfer, appearance and recovery.
