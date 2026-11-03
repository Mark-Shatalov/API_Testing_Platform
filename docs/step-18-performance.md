# Step 18 — measured performance and failure recovery

Implemented with step 17. No commit/push was performed. The goals are about 3 seconds to startup, under 100 ms for ordinary request opening excluding external files, usable navigation with 10,000 saved requests, a 500-request run, and roughly 512 MiB for ordinary use. Measurements below are local observations, not guarantees for every device or payload.

## Changes driven by measurements

The navigation tree previously filtered the complete item list for every parent and leaf. Metadata, children and tree items are now indexed once; attaching children is linear in the number of items. Selection is restored after attachment. Fixed 28-pixel rows keep TreeView virtualization predictable.

Request tabs now create their controls on first selection. Params is initialized immediately; Auth, Body, Settings, Variables, Tests, Extract and the other field editors initialize when selected. Draft values stay in the existing view model, so inactive tabs and incomplete inputs still save/recover. A response CodeArea is created only when a response arrives; a blank tab no longer pays its initialization cost. Existing body pagination, bounded response buffers, streamed uploads, background parsing/highlighting/search and bounded queues remain in place.

The SQLite queue still serializes reads/writes with short transactions and a three-second busy timeout. Lock/full/access/corruption failures now give safe, classified retry/recovery guidance. Failed operations preserve dirty editor data; unexpected UI failures display a notice and emit a structured code without raw exception text. Step 17 adds verified restore staging when storage cannot start, pre-restore safety copies and crash rollback. Network/certificate/timeout/cancel states continue through the existing request/runner pipeline.

## Method and results

Measured 2026-11-03 on Windows 11 x64, AMD Ryzen 7 7800X3D (16 logical processors), Temurin JDK 25.0.5. SQLite and HTTP workloads use temporary data and loopback MockWebServer, with one status check per request. Fixture creation is excluded. The fixture contains 10,000 navigation requests plus a separate 500-request collection. Open measurements include asynchronous SQLite load, DPAPI decode, constructing a new tab and its visible Params controls, CSS and layout; no upload file is opened. Eight distinct saved requests are opened. Three navigation moves select/scroll to indices 9,999, 5,000 and 0. Run timing includes snapshot loading, sequential HTTP, checks and history writes. Heap is observed after GC with nine tabs open and the finished report retained.

The before/after comparison uses an isolated performance-test JVM for each version, avoiding prior app integration tests warming the UI. JavaFX toolkit startup occurs before its library-ready timer; full JVM startup is measured separately below. Medians are calculated from the eight raw samples and rounded down; the original baseline output's upper-middle statistic is superseded by that calculation.

| Workload | Before | After |
| --- | ---: | ---: |
| 10,000-request library ready, including CSS/layout | 2,273 ms | 918 ms |
| Open request, median of eight | 158 ms | 71 ms |
| Open request, slowest of eight | 249 ms | 117 ms |
| Three navigation moves | 152 ms | 79 ms |
| 500 sequential local requests + checks/history | 938 ms | 796 ms |
| Used Java heap after this workload and GC | 107 MiB | 64 MiB |

Raw opening samples: before `[249, 184, 163, 132, 127, 132, 162, 154]`; after `[117, 78, 90, 104, 65, 59, 60, 60]` ms. Typical opening meets the target here; the first opening and another sample exceed 100 ms. The target is not claimed as an every-open maximum. In the final full test suite, which had already warmed JavaFX classes, library-ready was 112 ms, opening median 26 ms/max 38 ms, navigation 42 ms and the run 618 ms. Those warmed results are not substituted for the isolated comparison.

Three fresh application JVMs were then launched directly on the production module path, each with its own empty temporary LOCALAPPDATA directory. The application loaded/migrated storage, created its first workspace/tab, applied the actual theme and laid out the window. `StartupProbe` records JVM uptime at that point; PowerShell also measures wall time from process launch through receipt of the file and samples the process working set. Maven startup is excluded. The app closes normally after each measurement.

| Sample | JVM through ready | Launch through measurement file | Working set at ready |
| --- | ---: | ---: | ---: |
| 1 | 1,549 ms | 1,659 ms | 259.9 MiB |
| 2 | 1,445 ms | 1,549 ms | 259.0 MiB |
| 3 | 1,505 ms | 1,560 ms | 262.1 MiB |

Each JVM had about 26 MiB used heap at ready. These are fresh-process starts with filesystem/OS caches already warm, not a cold Windows boot. Startup was below the approximate three-second goal. The sampled startup working set was below 512 MiB. The workload's 64 MiB heap figure excludes JVM/native/graphics memory and is not a process working-set measurement; no claim is made that every large response, many open tabs or a full stress suite stays below 512 MiB. Loopback timings do not predict remote server latency or large payload performance.

## Reproduction

Run from PowerShell in the repository root with JDK 25 in JAVA_HOME:

```powershell
.\mvnw.cmd --offline --no-transfer-progress '-Dtest=PerformanceRecoveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dplatform.performance=true' test
Get-Content app\target\performance.properties
.\scripts\Measure-Startup.ps1 -Samples 3
```

The workload writes its metrics to `app/target/performance.properties`; `-Dplatform.performance.output=...` selects a different output. The startup script reads the production module path from the generated test report, launches only temporary-data benchmark instances and writes measurements under `.tools/performance`. It does not touch the real application database. Its benchmark windows can briefly appear. The startup hook does nothing unless `platform.startup.measurement` is explicitly set. Normal tests skip the optional workload; adding `-Dplatform.performance=true` includes it.

Raw measurements and temporary snapshots from this implementation run are in ignored `.tools/step17-18`; this report retains the measured values independently of that directory.

## Verification

Final `mvnw.cmd --offline --no-transfer-progress -Dplatform.performance=true clean install`: **267 tests**, zero failures/errors/skips, all ten projects successful. Module totals: domain 5, application 80, HTTP client 36, SQLite 29, Windows security 2, import/export 28, UI 22, app 65. Existing JavaFX integration tests cover editing, switching tabs, saving/restarting, authentication, checks, extraction, cancellation and stale completions. The performance test verifies the visible Params editor exists, all 10,000 navigation children remain present, and all 500 requests complete successfully. There are no machine-specific timing assertions.

Failure coverage includes actual SQLite write-lock contention followed by successful retry, injected SQL write/migration failures, corrupt database startup with subsequent restore, tampered/invalid backup rejection, interrupted restore rollback and committed-receipt cleanup, log-directory failure, failed settings saves, safe diagnostic export with failed storage and guarded response export. Disk-full/access/corrupt SQLite driver codes are checked for safe guidance without exposing SQL/secret messages; the host disk was not filled. Earlier network tests cover local timeout, refused connection, untrusted/mismatched TLS, oversize/cancellation and bounded admission. Dirty-editor preservation and Settings theme/persistence are checked in real JavaFX windows.

Windows packaging and release delivery remain steps 19–20.
