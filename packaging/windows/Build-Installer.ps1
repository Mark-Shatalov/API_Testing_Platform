<#
.SYNOPSIS
    Builds the Windows installer for API Testing Platform with a bundled Java runtime.

.DESCRIPTION
    1. Checks Windows x64, JDK 25 (java, jlink, jpackage, jdeps) and WiX Toolset.
    2. Runs the Maven Wrapper build (clean verify), which also copies the runtime module path
       to packaging\target\jpackage\modules.
    3. Links a minimal Java runtime with jlink (JDK modules only).
    4. Creates an app image with jpackage and smoke-tests it with temporary user data.
    5. Creates the MSI (or EXE) with jpackage, plus SHA-256 checksums and build information in dist\.

    Users of the installer need no JDK, JRE, server or service.

.EXAMPLE
    .\packaging\windows\Build-Installer.ps1
.EXAMPLE
    .\packaging\windows\Build-Installer.ps1 -AppVersion 0.1.1 -SkipTests
#>
[CmdletBinding()]
param(
    [ValidateSet('msi', 'exe')][string]$Type = 'msi',
    # Defaults to the Maven project version without -SNAPSHOT.
    [string]$AppVersion,
    [string]$Destination,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipTests,
    # Reuse an existing packaging\target\jpackage\modules from a previous Maven build.
    [switch]$SkipMavenBuild,
    [switch]$Offline,
    # Stop after the tested app image; no WiX Toolset is required.
    [switch]$AppImageOnly,
    [switch]$SkipSmokeTest
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

Assert-WindowsX64
$root = Get-RepositoryRoot
$settings = Get-InstallerSettings $root
if ([string]::IsNullOrWhiteSpace($AppVersion)) { $AppVersion = $settings.ProjectVersion }
$AppVersion = ConvertTo-InstallerVersion $AppVersion
if ([string]::IsNullOrWhiteSpace($Destination)) { $Destination = Join-Path $root 'dist' }
$jdk = Get-Jdk $JavaHome
$env:JAVA_HOME = $jdk.Home
$wix = 'not used (app image only)'
if (-not $AppImageOnly) { $wix = Get-WixToolset }
Write-Host "Packaging $($settings.Name) $AppVersion with $($jdk.Vendor) $($jdk.Version) and $wix"

# --- Maven Wrapper build ---------------------------------------------------------------------------
if (-not $SkipMavenBuild) {
    # Remove the previous app image first, with retries; "mvnw clean" fails at once on a locked launcher.
    Remove-BuildDirectory (Join-Path $root 'packaging\target\jpackage')
    $maven = @('-B', '--no-transfer-progress')
    if ($Offline) { $maven += '--offline' }
    if ($SkipTests) { $maven += '-DskipTests' }
    $maven += @('clean', 'verify')
    Push-Location $root
    try { Invoke-Checked (Join-Path $root 'mvnw.cmd') $maven 'Maven Wrapper build (mvnw.cmd clean verify)' }
    finally { Pop-Location }
}

$work = Join-Path $root 'packaging\target\jpackage'
$copied = Join-Path $work 'modules'
if (-not (Test-Path -LiteralPath $copied)) { throw "$copied does not exist. Run without -SkipMavenBuild." }

# Copy the module path, dropping JavaFX's empty platform-independent jars (the -win jars hold the code).
$modulePath = New-EmptyDirectory (Join-Path $work 'mods')
$jars = @(Get-ChildItem -LiteralPath $copied -Filter '*.jar' | Where-Object { $_.Name -notmatch '^javafx-[a-z]+-[0-9.]+\.jar$' })
foreach ($jar in $jars) { Copy-Item -LiteralPath $jar.FullName -Destination $modulePath }
if (-not ($jars | Where-Object { $_.Name -match '^app-.+\.jar$' })) { throw 'The app module jar is missing from the copied module path.' }
if (-not ($jars | Where-Object { $_.Name -match '^javafx-graphics-.+-win\.jar$' })) {
    throw 'The Windows JavaFX jars are missing. Build on Windows so Maven selects the "win" JavaFX classifier.'
}

# --- Bundled Java runtime --------------------------------------------------------------------------
$listed = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime-modules.txt') |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
# The boot layer fails to start if a named module's non-static "requires" is missing (for example
# sqlite-jdbc requires java.sql.rowset), so read every jar's module descriptor. Automatic modules have
# no descriptor; jdeps analyses their bytecode instead.
$jdkModules = @((Invoke-Captured $jdk.Java @('--list-modules')).Output | Where-Object { $_ -match '@' } | ForEach-Object { ($_ -split '@')[0].Trim() })
$declared = @(); $automatic = @()
foreach ($jar in $jars) {
    # --release 25 reads META-INF/versions/N/module-info.class of multi-release jars such as sqlite-jdbc.
    $descriptor = Invoke-Captured $jdk.Jar @('--file', (Join-Path $modulePath $jar.Name), '--describe-module', '--release', '25')
    if ($descriptor.ExitCode -ne 0) { throw "Could not read the module descriptor of $($jar.Name).`n$($descriptor.Output -join "`n")" }
    $text = $descriptor.Output -join "`n"
    if ($text -match 'No root module descriptor') { throw "$($jar.Name) has no module descriptor for Java 25." }
    if ($text -match '(?m)automatic\s*$') { $automatic += (Join-Path $modulePath $jar.Name); continue }
    foreach ($line in $descriptor.Output) {
        if ($line -notmatch '^requires\s+(\S+)(.*)$') { continue }
        $name = $Matches[1]; $modifiers = $Matches[2]
        if ($modifiers -notmatch '\bstatic\b' -and $jdkModules -contains $name) { $declared += $name }
    }
}
$detected = @()
if ($automatic.Count -gt 0) {
    $jdeps = Invoke-Captured $jdk.Jdeps (@('--multi-release', '25', '--ignore-missing-deps', '--print-module-deps') + $automatic)
    $printed = $jdeps.Output | Where-Object { $_ -match '^[a-z][a-z0-9_.]*(,[a-z][a-z0-9_.]*)*$' } | Select-Object -Last 1
    if ($jdeps.ExitCode -ne 0 -or -not $printed) { throw "jdeps could not analyse the automatic modules.`n$($jdeps.Output -join "`n")" }
    $detected = @($printed -split ',' | Where-Object { $jdkModules -contains $_ })
}
$required = @($declared + $detected | Sort-Object -Unique)
$missing = @($required | Where-Object { $listed -notcontains $_ })
if ($missing.Count -gt 0) { Write-Warning "Adding JDK modules required by the module path but not listed in runtime-modules.txt: $($missing -join ', ')" }
$runtimeModules = @($listed + $missing | Sort-Object -Unique)
$detected = $required

$runtime = Join-Path $work 'runtime'
Remove-BuildDirectory $runtime
Invoke-Checked $jdk.Jlink @('--add-modules', ($runtimeModules -join ','), '--strip-debug', '--no-header-files',
    '--no-man-pages', '--strip-native-commands', '--compress', 'zip-6', '--output', $runtime) 'jlink (bundled Java runtime)'

# --- jpackage ----------------------------------------------------------------------------------------
$icon = Join-Path $PSScriptRoot 'app.ico'
$removeProperties = Join-Path $work 'remove-user-data.properties'
# Properties files treat backslashes as escapes; forward slashes are valid Windows paths.
@(
    'arguments=--remove-user-data'
    "description=Permanently removes this Windows user's $($settings.Name) data after confirmation"
    "icon=$($icon.Replace('\', '/'))"
    'win-menu=true'
    'win-shortcut=false'
) | Set-Content -LiteralPath $removeProperties -Encoding ASCII
$uninstallProperties = Join-Path $work 'uninstall.properties'
@(
    'arguments=--uninstall'
    "description=Uninstalls $($settings.Name) after confirmation; optionally deletes this user's data"
    "icon=$($icon.Replace('\', '/'))"
    'win-menu=true'
    'win-shortcut=false'
) | Set-Content -LiteralPath $uninstallProperties -Encoding ASCII

$common = @(
    '--name', $settings.Name,
    '--app-version', $AppVersion,
    '--vendor', $settings.Vendor,
    '--description', $settings.Description,
    '--copyright', $settings.Copyright,
    '--icon', $icon,
    '--runtime-image', $runtime,
    '--module-path', $modulePath,
    '--module', $settings.MainModule,
    '--java-options', $settings.JavaOptions,
    '--add-launcher', "$($settings.RemoveLauncher)=$removeProperties",
    '--add-launcher', "$($settings.UninstallLauncher)=$uninstallProperties"
)

$images = New-EmptyDirectory (Join-Path $work 'image')
Invoke-Checked $jdk.Jpackage (@('--type', 'app-image', '--dest', $images) + $common) 'jpackage app image'
$image = Join-Path $images $settings.Name
$launcher = Join-Path $image "$($settings.Name).exe"
foreach ($required in $launcher, (Join-Path $image "$($settings.RemoveLauncher).exe"), (Join-Path $image "$($settings.UninstallLauncher).exe"), (Join-Path $image 'runtime\release')) {
    if (-not (Test-Path -LiteralPath $required)) { throw "The app image is incomplete: $required is missing." }
}
$smoke = $null
if (-not $SkipSmokeTest) {
    $smoke = & (Join-Path $PSScriptRoot 'Test-AppImage.ps1') -Launcher $launcher -ExpectedVersion $AppVersion
}
if ($AppImageOnly) {
    Write-Host "App image ready: $image" -ForegroundColor Green
    return
}

# jpackage defines JpAllowDowngrades for applications, so an older MSI would silently replace a newer
# installation and the older app would then refuse the database the newer one migrated.
# resources\overrides.wxi undefines it (main.wxs includes the file before evaluating the variable).
$resources = Join-Path $PSScriptRoot 'resources'
if (-not (Test-Path -LiteralPath (Join-Path $resources 'overrides.wxi'))) { throw "$resources\overrides.wxi is missing." }

New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$installer = Join-Path $Destination "$($settings.Name)-$AppVersion.$Type"
if (Test-Path -LiteralPath $installer) { Remove-Item -LiteralPath $installer -Force }
# Per-machine install into Program Files: user data in %LOCALAPPDATA% stays separate from the install
# directory (a per-user jpackage install would use %LOCALAPPDATA%\<name> itself).
Invoke-Checked $jdk.Jpackage (@('--type', $Type, '--dest', $Destination) + $common + @(
        '--win-upgrade-uuid', $settings.UpgradeUuid,
        '--win-menu', '--win-menu-group', $settings.Name,
        '--win-shortcut', '--win-shortcut-prompt',
        '--win-dir-chooser',
        '--resource-dir', $resources)) "jpackage $Type installer"
if (-not (Test-Path -LiteralPath $installer)) { throw "jpackage did not create $installer." }

# --- Checksums and build record ----------------------------------------------------------------------
$hash = Get-FileSha256 $installer
$fileName = Split-Path -Leaf $installer
$sums = Join-Path $Destination 'SHA256SUMS.txt'
$lines = @()
if (Test-Path -LiteralPath $sums) { $lines = @(Get-Content -LiteralPath $sums | Where-Object { $_ -notmatch "\*$([regex]::Escape($fileName))$" }) }
@($lines + "$hash *$fileName") | Set-Content -LiteralPath $sums -Encoding ASCII

$commit = 'unknown'; $dirty = $null
if (Get-Command 'git.exe' -ErrorAction SilentlyContinue) {
    $head = Invoke-Captured 'git.exe' @('-C', $root, 'rev-parse', 'HEAD')
    if ($head.ExitCode -eq 0) {
        $commit = $head.Output[0]
        $dirty = ((Invoke-Captured 'git.exe' @('-C', $root, 'status', '--porcelain')).Output.Count -gt 0)
    }
}
$jars = @($jars | Sort-Object Name | ForEach-Object { [ordered]@{ file = $_.Name; sha256 = Get-FileSha256 $_.FullName } })
[ordered]@{
    application     = $settings.Name
    version         = $AppVersion
    projectVersion  = $settings.ProjectVersion
    installer       = $fileName
    sha256          = $hash
    upgradeUuid     = $settings.UpgradeUuid
    gitCommit       = $commit
    gitDirty        = $dirty
    builtAtUtc      = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    jdk             = "$($jdk.Vendor) $($jdk.Version)"
    wix             = $wix
    testsSkipped    = [bool]$SkipTests
    runtimeModules  = $runtimeModules
    jdepsDetected   = $detected
    smokeTest       = $smoke
    modulePath      = $jars
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Destination "$($settings.Name)-$AppVersion.build-info.json") -Encoding UTF8

Write-Host "Installer: $installer" -ForegroundColor Green
Write-Host "SHA-256:   $hash"
