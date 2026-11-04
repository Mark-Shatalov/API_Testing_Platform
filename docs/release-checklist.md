# Release instructions and installer validation checklist

## Where installed files and user data live

| What | Location | Install | Upgrade | Uninstall |
| --- | --- | --- | --- | --- |
| Application, bundled Java runtime, libraries | `C:\Program Files\API Testing Platform` (or the folder chosen during setup) | created | replaced | removed |
| Start menu: *API Testing Platform*, *Remove API Testing Platform Data*; optional desktop shortcut | Start menu group *API Testing Platform* | created | replaced | removed |
| SQLite database, WAL/SHM, instance lock | `%LOCALAPPDATA%\API Testing Platform\platform.sqlite*` | created at first start | kept, migrated at next start | **kept** |
| Migration, restore-safety and pre-restore backups | `%LOCALAPPDATA%\API Testing Platform\migration-backups`, `recovery-backups` | as needed | kept | **kept** |
| Logs | `%LOCALAPPDATA%\API Testing Platform\logs` | created | kept | **kept** |
| Settings | `%LOCALAPPDATA%\API Testing Platform\appearance.properties`, `operations.properties` | created | kept | **kept** |
| Native-library cache (JavaFX, JNA, SQLite) | `%LOCALAPPDATA%\API Testing Platform\cache\<version>` | created at first start | previous version's cache removed at next start | **kept** |

The installer never writes to `%LOCALAPPDATA%`, so upgrading or uninstalling cannot delete user data. User data is removed only when the user runs **Start → API Testing Platform → Remove API Testing Platform Data** and confirms (Cancel is the default button), or deletes the folder manually. That tool refuses while the application is running, never follows links or junctions out of the folder, and does not touch exports or manual backups saved elsewhere. Run it before uninstalling if you want a complete removal; after uninstalling, delete `%LOCALAPPDATA%\API Testing Platform` in File Explorer instead.

Encrypted request payloads and secrets are bound to the Windows user profile (DPAPI). Moving the data folder to another user or computer does not make them readable; use **Workspace → Export workspace…** for a portable copy.

## Release steps

1. **Choose the version.** Windows Installer compares `MAJOR.MINOR.PATCH` (maximum 255.255.65535); every release must be higher than the previous one or the upgrade is refused. Set it in all POMs:
   ```powershell
   .\mvnw.cmd -B org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
   ```
   Release builds use a version without `-SNAPSHOT`. (A `-SNAPSHOT` suffix is removed for the installer version.)
2. **Prepare the build machine:** Windows 10/11 x64, Temurin JDK 25 in `JAVA_HOME`, WiX 5.0.2 (`.\packaging\windows\Install-WixToolset.ps1`). Start from a clean checkout of the release commit (`git status` empty), so `build-info.json` records `gitDirty: false`.
3. **Build and validate:**
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\Invoke-ReleaseValidation.ps1
   ```
   It runs every automated test (`mvnw.cmd clean verify`), builds and smoke-tests the app image, builds the MSI and a next-patch MSI, and runs the elevated install/upgrade/downgrade/uninstall test with a temporary `LOCALAPPDATA`. Every step must be PASS. Results: `packaging\target\release-validation\summary.md`.
4. **Validate on clean Windows** — a Windows Sandbox (`Start-SandboxValidation.ps1`), a fresh VM, or the CI `installer` job (push a `v*` tag or run the workflow manually). Then complete the manual checklist below on that clean machine.
5. **Publish** `dist\API Testing Platform-<version>.msi` together with `SHA256SUMS.txt`. Keep `build-info.json` with the release notes. The MSI is not code-signed; Windows SmartScreen may warn until a signing certificate is used (`signtool sign` on the MSI before computing checksums).
6. **Tag** the release commit `v<version>` and bump the development version to the next `-SNAPSHOT`.

## Installer validation checklist

Automated checks (`Test-Installer.ps1`, `Test-AppImage.ps1`) cover the items marked **A**; complete the rest by hand on a clean Windows 10 or 11 x64 machine without Java installed (`where java` finds nothing).

### Install

- [ ] **A** `msiexec` installs silently with exit code 0; interactive setup shows the folder chooser and desktop-shortcut prompt.
- [ ] **A** One *API Testing Platform* entry in **Settings → Apps** with the expected version; upgrade code `74c383b2-1e8b-4a5e-8d88-e2eaae645a1b`.
- [ ] **A** `C:\Program Files\API Testing Platform` contains the launcher, `runtime` (Java 25) and `app\mods`.
- [ ] **A** Start menu contains *API Testing Platform* and *Remove API Testing Platform Data*.
- [ ] Desktop shortcut exists only when chosen; the icon is shown in the Start menu, taskbar and Apps list.
- [ ] Windows Defender/SmartScreen behaviour is noted (unsigned MSI).

### First start

- [ ] **A** Starts from the Start menu with no JDK on the machine; the window is ready in about three seconds on typical hardware.
- [ ] **A** `%LOCALAPPDATA%\API Testing Platform` contains `platform.sqlite`, `logs` and `cache\<version>`; nothing is written to Program Files or `%USERPROFILE%\.openjfx`.
- [ ] A standard user (not administrator) can start and use the installed application.
- [ ] Network access works through the system proxy and HTTPS certificate validation works (send a request to a public HTTPS API).

### Upgrade

- [ ] **A** Installing a higher version replaces the old one in place (single Apps entry, new version).
- [ ] **A** Saved workspaces, requests, environments, history, settings and logs are still present; the database is the same file and migrates at start.
- [ ] **A** The previous version's native cache is removed at the next start.
- [ ] **A** Installing an older version over a newer one is refused.
- [ ] Encrypted secrets still decrypt after the upgrade (open a request using a secret variable and send it).

### Uninstall

- [ ] **A** Uninstalling from **Settings → Apps** or `msiexec /x` removes Program Files content and shortcuts.
- [ ] **A** `%LOCALAPPDATA%\API Testing Platform` is preserved; reinstalling shows the existing data.
- [ ] *Remove API Testing Platform Data* while the app is open reports that it is running and removes nothing.
- [ ] *Remove API Testing Platform Data* → Cancel removes nothing; → **Remove data** deletes only `%LOCALAPPDATA%\API Testing Platform`.

### Complete user journey (step 20)

- [ ] Create and send a request; save it, close, reopen the app and reopen the request with settings intact.
- [ ] Switch environments; final URL preview masks secret values.
- [ ] Encrypted secret variable and Bearer/Basic/API-key authentication against a real endpoint.
- [ ] Add assertions; run a collection, cancel a run; open the saved report after restart and export it.
- [ ] Export a workspace and import it; a malformed import is rejected without changing data.
- [ ] Recover a draft after ending the process from Task Manager.
- [ ] Responses: HTTP 200, 204, 301, 400, 500; non-ASCII text; DNS failure, TLS failure, connection refused, timeout; a large and a chunked response; multipart upload.
- [ ] Logs folder and diagnostic archive contain no tokens, cookies, passwords or bodies.
