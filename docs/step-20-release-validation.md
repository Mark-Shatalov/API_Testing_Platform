# Step 20 — release validation

## How validation is run

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\Invoke-ReleaseValidation.ps1
```

1. Checks JDK 25 and WiX (installs WiX 5.0.2 when the .NET SDK is present).
2. `Build-Installer.ps1`: `mvnw.cmd clean verify` (every automated test), `jlink`, app image, smoke test without system Java, MSI, checksums. Surefire results are summarised per module.
3. Builds a next-patch MSI from the same code to test upgrading in place.
4. Elevated `Test-Installer.ps1` with a temporary `LOCALAPPDATA`: install, launch, upgrade, launch, downgrade refusal, uninstall with data preserved.

Results land in `packaging\target\release-validation` (`summary.md`, `summary.json`, build and msiexec logs, `installer-validation.md`). The manual part of the journey is the checklist in [release-checklist.md](release-checklist.md).

## Required journey and where it is covered

| Requirement | Automated coverage |
| --- | --- |
| Install on clean Windows, upgrade, uninstall | `Test-Installer.ps1` (local run, Windows Sandbox or a clean VM) |
| Launch without a Java installation | `Test-AppImage.ps1` removes `JAVA_HOME` and Java from `PATH`; checks the bundled Java 25 runtime |
| Create, send, save, reopen after restart | `LibraryIntegrationTest.sendsSavesClosesAndReopensAllSettingsAfterRestart` |
| Switch environments, encrypted secrets, authentication | `EnvironmentIntegrationTest`, `AuthenticationIntegrationTest`, `DpapiPayloadProtectorTest` |
| Assertions, run and cancel a collection | `AssertionIntegrationTest`, `CollectionRunIntegrationTest.cancellingOrClosingStopsActiveHttpAndPreventsLaterRequests` |
| Saved report after restart, export | `HistoryTransferIntegrationTest.manualSendReopensSanitizedHistoryAfterRestartAndExportsReadableReports` |
| Export and import; malformed imports | `HistoryTransferIntegrationTest.workspaceFileRoundTrip…`, `PortableWorkspaceFormatTest.rejectsMalformedReferencesTypesVersionsAndUnsupportedFields`, `…rejectsDuplicateJsonPropertiesDeepInputAndOversizedFiles` |
| Draft recovery | `LibraryIntegrationTest.recoversUnfinishedTabWithoutOverwritingSavedRequestAndNormalCloseClearsSnapshots` |
| HTTP 200, 204, 301, 400, 500 | `JavaHttpExecutionEngineTest` (`returnsHttpErrorStatusAsACompletedResponse`, `handlesRedirectsAndNeverPolicy`, `detectsRedirectLoopsAndRedirectLimit`), `ResponseDebuggingIntegrationTest` |
| Non-ASCII text | `JavaHttpExecutionEngineTest`, `ResponsePresentationTest` |
| DNS, TLS, connection, timeout errors | `reportsConnectionRefusedAndDnsFailures`, `negotiatesHttpsHttp2AndReportsUntrustedCertificates`, `enforcesTimeoutAcrossBodyReadingAndReleasesCapacity` |
| Large and chunked responses | `capsChunkedResponsesAndRequestBodies`, `configuredCaptureLimitBoundsLargeResponse`, `cancelsDuringChunkedBodyDownload…` |
| Multipart uploads | `streamsMultipartFilesAndReplaysTheExactBodyOn307` |
| Logs and exports reveal no secrets | `OperationsServiceTest.diagnosticArchiveWhitelistsEvents…`, `actualHttpLogsOnlyNumericOutcome…`, `PortableWorkspaceFormatTest.defaultExportOmitsCredentials…`, `RedactedRequestSummaryTest`, `SecretRedactorTest` |
| Explicit data removal | `UserDataRemovalTest` |

## Results — 2026-11-04, version 0.2.0

Run on Windows 11 (NT 10.0.26300) x64, Intel Core i7-13620H, 16 GB RAM, Eclipse Temurin JDK 25.0.4.1+1, WiX 5.0.2, Maven Wrapper (Maven 3.9.16). Command: `Invoke-ReleaseValidation.ps1` (final run 18:04 UTC). Version 0.2.0 adds screen-fitted window placement, F11 full screen, window icons and the Start-menu uninstaller to the first validated build (0.1.0, 280 tests, 27/27 installer checks).

| Step | Result | Detail |
| --- | --- | --- |
| JDK / WiX | PASS | Temurin 25.0.4.1+1-LTS, WiX 5.0.2 |
| Automated tests (`mvnw.cmd clean verify`) | **PASS** | 287 tests, 0 failures, 0 errors, 2 skipped |
| Build, jlink, app image, smoke test, MSI | **PASS** | `dist\API Testing Platform-0.2.0.msi`, SHA-256 `a5c5f85d1e4d8105bfa368a97c605b9a35deaf0083a88e824b1d4676632e9ef4` |
| Upgrade-test MSI | PASS | 0.2.1 built from the same code |
| Install / upgrade / downgrade / uninstall | **PASS** | 29 of 29 checks |

Tests per module: domain 5, application 80, http-client 36, persistence-sqlite 29, security-windows 2, import-export 28, ui 22, app 85 (2 skipped). The skips are intentional: `PerformanceRecoveryTest` runs only with `-Dplatform.performance=true`, and `UserDataRemovalTest.removesLinksWithoutVisitingTheirTargets` needs symbolic-link rights (Developer Mode or elevation) on Windows.

Packaged application (no `JAVA_HOME`, no Java on `PATH`, temporary `LOCALAPPDATA`): window ready 3.0 s after launch from the app image and 3.3–3.6 s from Program Files; bundled Java 25.0.4.1; 23 MiB used heap at ready; database, logs and the JavaFX native cache (`cache\0.2.0\openjfx`) created under `LOCALAPPDATA`; no file in the installation directory created or changed.

Installer checks (`installer-validation.md`): one Apps & Features entry with the right version after install and after upgrade; upgrade code `{74C383B2-1E8B-4A5E-8D88-E2EAAE645A1B}`; launcher, data-removal launcher, uninstall launcher, 29 jars in `app\mods`, all three Start-menu shortcuts; no database in Program Files; upgrade 0.2.0 → 0.2.1 in place with the marker file and the original database file preserved; previous native cache removed at next start; installing 0.2.0 over 0.2.1 refused (`JpDisallowDowngrade`, msiexec 1603, 0.2.1 still installed); uninstall removes the registration, program files and shortcuts and keeps user data.

### Defects found and fixed during validation

| Defect | Fix |
| --- | --- |
| Packaged app did not start: `Module java.sql.rowset not found, required by org.xerial.sqlitejdbc` | Added `java.sql.rowset`; runtime sizing now reads every jar's module descriptor (including multi-release jars) and analyses automatic modules with jdeps, failing instead of skipping silently |
| jpackage MSIs allow downgrades by default; 0.1.0 silently replaced 0.1.1, whose migrated database 0.1.0 would refuse | `packaging/windows/resources/overrides.wxi` undefines `JpAllowDowngrades` |
| Response placeholder overflowed the workbench by 1.6 px at the 1050×700 minimum window (from the UI redesign commit) | Tighter `.response-panel .empty-state` padding in `main.css` |
| Window opened at a fixed 1400×900 with a 1050×700 minimum, larger than the screen on high-scaling laptops, hiding the title-bar buttons | `WindowPlacement` fits the window to the screen under the pointer (maximized on small screens); F11 toggles full screen |
| No uninstaller other than Windows Settings | *Uninstall API Testing Platform* Start-menu shortcut with an optional data-removal checkbox |
| Intermittent UI-test failures when a lookup ran before a new tab's `SplitPane` was laid out | `FxTestSupport.waitFor` treats a not-yet-rendered node as "not ready"; explicit wait in `EnvironmentIntegrationTest` |
| Validation tooling issues (trailing `\` in `JAVA_HOME`, stale surefire reports counted, launcher stdout not shown, locked previous app image, PowerShell 5.1 empty-array `.Count`) | Fixed in the packaging scripts |

### Not verified

- **Clean Windows machine.** Validation ran on the development PC (a JDK is installed there; the test removes it from the launcher's environment). Windows Sandbox cannot run on it (hardware virtualization is disabled in firmware). Run `Test-Installer.ps1` on another Windows PC without Java, a fresh VM, or `Start-SandboxValidation.ps1` on a machine with virtualization enabled.
- **Manual checklist** in [release-checklist.md](release-checklist.md): interactive setup dialogs, standard (non-admin) user, real HTTPS/proxy endpoints, the data-removal confirmation window, SmartScreen behaviour.
- The automated suite covers the journey items in the table above with local test servers; they were not repeated by hand in the installed build.

## Release readiness

Automated release validation passes with no open defects. Before publishing 0.2.0: run the clean-machine validation and the manual checklist, build from a clean commit (`build-info.json` currently records `gitDirty: true`), and decide on code signing (the MSI is unsigned, so SmartScreen may warn).
