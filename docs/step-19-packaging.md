# Step 19 — Windows packaging

## Result

`packaging\windows\Build-Installer.ps1` produces `dist\API Testing Platform-<version>.msi` (or `.exe` with `-Type exe`) using the Maven Wrapper, `jlink` and `jpackage`. The installer bundles a trimmed Java 25 runtime; users need no JDK, JRE, separate server or service.

## Design

- **Runtime.** `jlink` links only JDK modules: `packaging/windows/runtime-modules.txt`, plus any JDK module that a jar's module descriptor `requires` (non-static; read with `jar --describe-module --release 25`, so multi-release jars such as sqlite-jdbc are included) or that `jdeps` finds in an automatic module. Additions are reported as warnings. Application and library jars are not linked: `json-path`, `reactfx`, `richtextfx`, `flowless` and `undofx` are automatic modules, which `jlink` rejects. `jpackage` copies the module path into `app\mods` and starts `dev.apitestingplatform.app/dev.apitestingplatform.app.Main` with `--module-path $APPDIR\mods` and `--enable-native-access=javafx.graphics,org.xerial.sqlitejdbc,com.sun.jna`. Debug data, headers, man pages and the `java.exe` launcher are stripped from the runtime.
- **Module path.** `packaging/pom.xml` depends on `app`, so it is built last, and copies the runtime dependency graph at `package`. JavaFX's empty platform-independent jars are dropped; the build fails if the Windows (`-win`) JavaFX jars are missing.
- **Entry point.** New `Main` runs before any native library loads. It sets `javafx.cachedir`, `jna.tmpdir` and `org.sqlite.tmpdir` to `%LOCALAPPDATA%\API Testing Platform\cache\<version>` (version from jpackage's `jpackage.app-version`, `dev` otherwise) and removes caches of other versions. JavaFX would otherwise extract DLLs into `%USERPROFILE%\.openjfx`. Explicit `-D` overrides are respected. `javafx:run` uses the same entry point.
- **Data locations.** Unchanged and already outside the installation folder: database, WAL, lock, migration/recovery backups, logs and settings in `%LOCALAPPDATA%\API Testing Platform`. The cache is added there. Nothing is written to the installation directory; the smoke test verifies this.
- **Install scope.** Per-machine install into Program Files. A jpackage per-user MSI installs into `%LOCALAPPDATA%\<name>`, which is exactly the data folder, so it is deliberately not used.
- **Upgrade.** A fixed upgrade code (`installer.upgradeUuid` in `packaging/pom.xml`) makes a higher version replace the installed one in place. The MSI never owns files in `%LOCALAPPDATA%`, so data survives; schema migrations with their pre-migration backup run at the next start. Lower versions are refused: jpackage allows downgrades by default, so `packaging/windows/resources/overrides.wxi` (passed with `--resource-dir`) undefines `JpAllowDowngrades`.
- **Uninstall.** Removes Program Files content and shortcuts and keeps user data. Removing data is a separate, explicit choice: the second launcher **Remove API Testing Platform Data** (`Main --remove-user-data`) shows a confirmation with Cancel as default, refuses while the app holds its instance lock, deletes only `%LOCALAPPDATA%\API Testing Platform`, and removes links/junctions without visiting their targets. An uninstall-time checkbox was not used because Windows usually uninstalls MSIs without showing dialogs, so such a choice would be silently skipped.
- **Reproducibility.** Toolchain is pinned or checked: JDK 25 (`java.specification.version`), Maven via the wrapper, `maven-dependency-plugin` 3.8.1, WiX 5.0.2 (`Install-WixToolset.ps1`). All installer metadata is read from `packaging/pom.xml`. Each build writes `SHA256SUMS.txt` and `build-info.json` with commit, dirty state, JDK, WiX, runtime modules, the SHA-256 of every bundled jar and the smoke-test result. MSI files embed timestamps, so builds are reproducible in inputs and procedure, not bit-for-bit.
- **Icon.** `packaging/windows/app.ico` (16–256 px), used for the launchers, shortcuts and Apps list.

## Files

| File | Purpose |
| --- | --- |
| `app/.../Main.java`, `RuntimeLocations.java` | Packaged entry point; native caches under LOCALAPPDATA |
| `app/.../UserDataRemoval.java`, `UserDataRemovalApplication.java` | Explicit user-data removal and its confirmation window |
| `app/.../RuntimeLocationsTest.java`, `UserDataRemovalTest.java` | Cache locations, overrides, version sanitising; removal, in-use refusal, wrong-folder refusal, links, messages |
| `packaging/pom.xml` | Installer metadata, module-path copy |
| `packaging/windows/Build-Installer.ps1` | Build: Maven Wrapper → jlink → jpackage app image → smoke test → MSI → checksums |
| `packaging/windows/Test-AppImage.ps1` | Launch without system Java, temporary LOCALAPPDATA, checks write locations |
| `packaging/windows/Test-Installer.ps1` | Install / upgrade / downgrade refusal / uninstall validation |
| `packaging/windows/Start-SandboxValidation.ps1` | The same validation in Windows Sandbox |
| `packaging/windows/Invoke-ReleaseValidation.ps1` | One-command release validation (step 20) |
| `packaging/windows/resources/overrides.wxi` | Disables MSI downgrades |
| `packaging/windows/Install-WixToolset.ps1`, `Common.ps1`, `runtime-modules.txt`, `app.ico` | Tooling and inputs |
| `.github/workflows/windows.yml` | CI: verify on every push; MSI build and lifecycle test on a clean runner for tags/manual runs |
| `docs/release-checklist.md` | Release instructions and installer validation checklist |

## Verification

Verified on Windows 11 x64 with Temurin JDK 25.0.4.1 and WiX 5.0.2; full results in [Step 20](step-20-release-validation.md): 280 automated tests pass, the packaged app starts without any system Java and writes only below `LOCALAPPDATA`, and the MSI passes 27 of 27 install / upgrade / downgrade-refusal / uninstall checks. A clean-machine run (Sandbox, VM or CI) is still outstanding.

## Limitations

- The MSI is not code-signed (no certificate available); SmartScreen may warn.
- The *Remove API Testing Platform Data* shortcut is removed with the application; after uninstalling, the folder is deleted manually.
- Windows Sandbox needs Windows Pro/Enterprise/Education and hardware virtualization enabled in firmware.
