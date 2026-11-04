# Windows packaging

This module turns the application into a Windows installer that bundles its own Java runtime. Installed users need no JDK, JRE, Maven, server, service, Docker or WSL.

## What gets built

| Output | Where | Notes |
| --- | --- | --- |
| Module path | `packaging/target/jpackage/modules` | Copied by `maven-dependency-plugin` during `mvnw.cmd verify`: the `app` jar and its complete runtime dependency graph, including the Windows JavaFX jars. |
| Java runtime | `packaging/target/jpackage/runtime` | `jlink` image containing only the JDK modules in `windows/runtime-modules.txt` plus any JDK module the jars' module descriptors require or `jdeps` finds in automatic modules. Libraries are not linked because several are automatic modules; `jpackage` installs them in `app\mods`. |
| App image | `packaging/target/jpackage/image/API Testing Platform` | Smoke-tested before the installer is produced. |
| Installer | `dist/API Testing Platform-<version>.msi` | Per-machine install into `C:\Program Files\API Testing Platform`, Start menu group, optional desktop shortcut, install-folder chooser. |
| Checksums, build record | `dist/SHA256SUMS.txt`, `dist/API Testing Platform-<version>.build-info.json` | Commit, JDK, WiX, runtime modules, SHA-256 of every bundled jar and the smoke-test result. |

Installer metadata (name, vendor, description, upgrade code, main module, JVM options) lives only in `packaging/pom.xml`. `windows/resources/overrides.wxi` is passed to jpackage with `--resource-dir` and disables downgrades. **Never change `installer.upgradeUuid`**: Windows Installer uses it to find and replace older versions.

## Prerequisites (build machine only)

- Windows 10 or 11 x64
- JDK 25 (Temurin 25 is used for releases) in `JAVA_HOME`
- WiX Toolset 5.0.2 for `.msi`/`.exe`: install the .NET 8 SDK, then run `.\packaging\windows\Install-WixToolset.ps1`. WiX 3.14 (`candle`/`light` on `PATH`) also works with jpackage 25.

## Build

From PowerShell in the repository root:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
.\packaging\windows\Build-Installer.ps1
```

Useful switches: `-SkipTests` (iteration only, never for a release), `-AppImageOnly` (no WiX needed), `-Type exe`, `-AppVersion 0.1.1`, `-Offline`, `-SkipMavenBuild`.

The script fails instead of producing a partial installer when the JDK is not 25, `jlink`/`jpackage` are missing, the Windows JavaFX jars are absent, the app image is incomplete or the smoke test fails.

## Validate

| Script | Purpose |
| --- | --- |
| `Test-AppImage.ps1 -Launcher <exe>` | Starts a packaged or installed launcher with a temporary `LOCALAPPDATA`, no `JAVA_HOME` and no Java on `PATH`. Passes only if the window becomes ready, the app exits normally, the database, logs and JavaFX native cache are created under `LOCALAPPDATA`, and nothing in the installation folder changes. |
| `Test-Installer.ps1 -Msi <msi> [-UpgradeMsi <msi>]` | Elevated, on a clean machine: install, launch, upgrade in place, launch, refuse downgrade, uninstall; checks registration, shortcuts, bundled Java and that user data survives upgrade and uninstall. |
| `Start-SandboxValidation.ps1 -Msi <msi> [-UpgradeMsi <msi>]` | Runs `Test-Installer.ps1` in a disposable Windows Sandbox (clean Windows without Java). Needs Windows Pro/Enterprise and hardware virtualization. |
| `Invoke-ReleaseValidation.ps1` | One command for release validation: all tests, installer, upgrade-test MSI and the elevated lifecycle test. Summary in `packaging/target/release-validation`. `-LifecycleOnly` reuses the last MSI and repeats only the installer checks. |

The CI workflow `.github/workflows/windows.yml` runs `mvnw.cmd clean verify` on every push and, for `v*` tags or a manual run, builds the MSI and runs `Test-Installer.ps1` on a clean hosted Windows runner.

See [the release instructions and installer validation checklist](../docs/release-checklist.md).
