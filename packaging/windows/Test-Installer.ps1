<#
.SYNOPSIS
    Validates install, upgrade, downgrade protection and uninstall of the MSI on a clean Windows machine.

.DESCRIPTION
    Run elevated on a clean Windows 10/11 x64 machine, VM or Windows Sandbox where API Testing Platform
    is not installed (see Start-SandboxValidation.ps1). Uses a dedicated temporary LOCALAPPDATA for the
    application data, so an existing user's data is never touched. Steps:
      1. Silent install of -Msi; checks registration, upgrade code, files, shortcuts and bundled Java.
      2. Launches the installed app without any Java on PATH (Test-AppImage.ps1) and adds a data marker.
      3. Optional: installs -UpgradeMsi over it; checks one registration, new version, data preserved,
         launches again; then verifies the older MSI is refused (no downgrade).
      4. Silent uninstall; checks program files and shortcuts are removed and the user data is preserved.
    Writes installer-validation.json and installer-validation.md plus verbose msiexec logs to -LogDirectory.
    Exit code 0 only when every check passes.

.EXAMPLE
    .\packaging\windows\Test-Installer.ps1 -Msi "dist\API Testing Platform-0.1.0.msi" -UpgradeMsi "dist\API Testing Platform-0.1.1.msi"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$UpgradeMsi,
    [string]$LogDirectory,
    [switch]$KeepInstalled
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

Assert-WindowsX64
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this validation from an elevated PowerShell: the MSI installs per machine into Program Files.'
}
$settings = Get-InstallerSettings (Get-RepositoryRoot)
$Msi = (Resolve-Path -LiteralPath $Msi).Path
if ($UpgradeMsi) { $UpgradeMsi = (Resolve-Path -LiteralPath $UpgradeMsi).Path }
if ([string]::IsNullOrWhiteSpace($LogDirectory)) { $LogDirectory = Join-Path (Get-RepositoryRoot) 'packaging\target\installer-validation' }
$LogDirectory = (New-Item -ItemType Directory -Path $LogDirectory -Force).FullName
# The elevated window closes when this script ends; keep everything it printed.
Start-Transcript -LiteralPath (Join-Path $LogDirectory 'transcript.txt') -Force | Out-Null
$localAppData = New-EmptyDirectory (Join-Path ([IO.Path]::GetTempPath()) ('api-testing-platform-installer-' + [guid]::NewGuid().ToString('N')))
$data = Join-Path $localAppData 'API Testing Platform'
$installDirectory = Join-Path $env:ProgramFiles $settings.Name
$launcher = Join-Path $installDirectory "$($settings.Name).exe"
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check([string]$Name, [bool]$Passed, [string]$Detail) {
    $checks.Add([pscustomobject]@{ Check = $Name; Passed = $Passed; Detail = $Detail })
    $color = 'Red'; $label = 'FAIL'
    if ($Passed) { $color = 'Green'; $label = 'PASS' }
    Write-Host ("[{0}] {1} - {2}" -f $label, $Name, $Detail) -ForegroundColor $color
}

function Get-MsiProperty([string]$Path, [string]$Property) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($Path, 0))
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @("SELECT Value FROM Property WHERE Property = '$Property'"))
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
    $value = $null
    if ($null -ne $record) { $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1) }
    $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) | Out-Null
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
    return $value
}

function Get-Registrations {
    $keys = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
    return @(Get-ItemProperty -Path $keys -ErrorAction SilentlyContinue | Where-Object {
            $_.PSObject.Properties['DisplayName'] -and $_.DisplayName -eq $settings.Name })
}

function Invoke-Msiexec([string[]]$Arguments, [string]$Log) {
    $quoted = @($Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $quoted += @('/qn', '/norestart', '/l*v', ('"' + $Log + '"'))
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList ($quoted -join ' ') -Wait -PassThru
    return $process.ExitCode
}

function Get-Shortcuts {
    $menus = @((Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$($settings.Name)"),
        (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$($settings.Name)"))
    return @($menus | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter '*.lnk' } | ForEach-Object { $_.BaseName } | Sort-Object)
}

function Test-InstalledVersion([string]$Label, [string]$Version) {
    $registrations = @(Get-Registrations)
    Add-Check "$Label registration" ($registrations.Count -eq 1) "$($registrations.Count) Apps & Features entry/entries"
    if ($registrations.Count -ge 1) {
        Add-Check "$Label version" ($registrations[0].DisplayVersion -eq $Version) "registered $($registrations[0].DisplayVersion), expected $Version"
    }
    Add-Check "$Label launcher" (Test-Path -LiteralPath $launcher) $launcher
    Add-Check "$Label data-removal launcher" (Test-Path -LiteralPath (Join-Path $installDirectory "$($settings.RemoveLauncher).exe")) 'explicit user-data removal is available'
    Add-Check "$Label uninstall launcher" (Test-Path -LiteralPath (Join-Path $installDirectory "$($settings.UninstallLauncher).exe")) 'Start-menu uninstaller is available'
    $mods = @(Get-ChildItem -LiteralPath (Join-Path $installDirectory 'app\mods') -Filter '*.jar' -ErrorAction SilentlyContinue)
    Add-Check "$Label module path" ($mods.Count -gt 10) "$($mods.Count) jars in app\mods"
    $shortcuts = @(Get-Shortcuts)
    Add-Check "$Label Start menu" (($shortcuts -contains $settings.Name) -and ($shortcuts -contains $settings.RemoveLauncher) -and ($shortcuts -contains $settings.UninstallLauncher)) ("shortcuts: " + ($shortcuts -join ', '))
    Add-Check "$Label data outside install directory" (-not (Test-Path -LiteralPath (Join-Path $installDirectory 'platform.sqlite'))) 'no database in Program Files'
}

function Invoke-Smoke([string]$Label, [string]$Version) {
    try {
        $smoke = & (Join-Path $PSScriptRoot 'Test-AppImage.ps1') -Launcher $launcher -ExpectedVersion $Version -LocalAppData $localAppData
        Add-Check "$Label launch without system Java" $true ("ready after {0} ms, bundled Java {1}" -f $smoke.launchToReadyMs, $smoke.bundledJava)
    } catch { Add-Check "$Label launch without system Java" $false $_.Exception.Message }
}

function Get-InstallerVersion([string]$Path) {
    try { return Get-MsiProperty $Path 'ProductVersion' }
    catch {
        if ((Split-Path -Leaf $Path) -match '-(\d+\.\d+\.\d+)\.msi$') { return $Matches[1] }
        throw
    }
}

$marker = Join-Path $data 'installer-validation-marker.txt'
$database = Join-Path $data 'platform.sqlite'
$installAttempted = $false

try {
$version = Get-InstallerVersion $Msi
try {
    $upgradeCode = "$(Get-MsiProperty $Msi 'UpgradeCode')"
    Add-Check 'Upgrade code' ($upgradeCode.Trim('{}') -eq $settings.UpgradeUuid) $upgradeCode
} catch { Add-Check 'Upgrade code' $false "Could not read the MSI Property table: $($_.Exception.Message)" }
$existing = @(Get-Registrations)
if ($existing.Count -gt 0) { throw "$($settings.Name) is already installed ($($existing[0].DisplayVersion)). Use a clean machine, VM or Windows Sandbox." }

# 1-2. Install and launch.
$installAttempted = $true
$code = Invoke-Msiexec @('/i', $Msi) (Join-Path $LogDirectory '1-install.log')
Add-Check 'Install' ($code -in 0, 3010) "msiexec exit code $code"
Test-InstalledVersion 'Install' $version
Invoke-Smoke 'Install' $version
$marker = Join-Path $data 'installer-validation-marker.txt'
$token = [guid]::NewGuid().ToString()
if (Test-Path -LiteralPath $data) { Set-Content -LiteralPath $marker -Value $token -Encoding ASCII }
$database = Join-Path $data 'platform.sqlite'
$created = $null
if (Test-Path -LiteralPath $database) { $created = (Get-Item -LiteralPath $database).CreationTimeUtc }

# 3. Upgrade in place and refuse downgrade.
if ($UpgradeMsi) {
    $newVersion = Get-InstallerVersion $UpgradeMsi
    $code = Invoke-Msiexec @('/i', $UpgradeMsi) (Join-Path $LogDirectory '2-upgrade.log')
    Add-Check 'Upgrade' ($code -in 0, 3010) "msiexec exit code $code ($version -> $newVersion)"
    Test-InstalledVersion 'Upgrade' $newVersion
    $kept = (Test-Path -LiteralPath $marker) -and ((Get-Content -LiteralPath $marker -Raw).Trim() -eq $token)
    $sameDatabase = ($null -ne $created) -and (Test-Path -LiteralPath $database) -and ((Get-Item -LiteralPath $database).CreationTimeUtc -eq $created)
    Add-Check 'Upgrade keeps user data' ($kept -and $sameDatabase) 'marker and original database file still present'
    Invoke-Smoke 'Upgrade' $newVersion
    Add-Check 'Upgrade clears old native cache' (-not (Test-Path -LiteralPath (Join-Path $data "cache\$version"))) "cache\$version removed at next start"
    $code = Invoke-Msiexec @('/i', $Msi) (Join-Path $LogDirectory '3-downgrade.log')
    $registrations = @(Get-Registrations)
    $installedNow = (@($registrations | ForEach-Object { $_.DisplayVersion }) -join ', ')
    $stillNew = ($registrations.Count -eq 1) -and ($registrations[0].DisplayVersion -eq $newVersion)
    Add-Check 'Downgrade refused' (($code -ne 0) -and $stillNew) "msiexec exit code $code; installed after the attempt: $installedNow (expected $newVersion)"
}

} catch {
    # Record the failure, then fall through so an installed copy is still removed.
    Add-Check 'Validation script' $false ("$($_.Exception.Message) (Test-Installer.ps1 line $($_.InvocationInfo.ScriptLineNumber))")
}

# 4. Uninstall, keeping user data.
if ($installAttempted -and -not $KeepInstalled) {
    $registrations = @(Get-Registrations)
    if ($registrations.Count -ge 1) {
        $code = Invoke-Msiexec @('/x', $registrations[0].PSChildName) (Join-Path $LogDirectory '4-uninstall.log')
        Add-Check 'Uninstall' ($code -in 0, 3010) "msiexec exit code $code"
    }
    Add-Check 'Uninstall registration removed' ((@(Get-Registrations)).Count -eq 0) 'no Apps & Features entry'
    Add-Check 'Uninstall removes program files' (-not (Test-Path -LiteralPath $launcher)) $installDirectory
    Add-Check 'Uninstall removes shortcuts' ((@(Get-Shortcuts)).Count -eq 0) 'Start menu folder empty or removed'
    $kept = (Test-Path -LiteralPath $marker) -and (Test-Path -LiteralPath $database)
    Add-Check 'Uninstall preserves user data' $kept $data
}

$failed = @($checks | Where-Object { -not $_.Passed })
$report = [ordered]@{
    application = $settings.Name; installer = (Split-Path -Leaf $Msi); upgradeInstaller = $UpgradeMsi
    windows = [Environment]::OSVersion.VersionString; testedAtUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    passed = ($failed.Count -eq 0); checks = $checks
}
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $LogDirectory 'installer-validation.json') -Encoding UTF8
$markdown = @('| Check | Result | Detail |', '| --- | --- | --- |') + @($checks | ForEach-Object {
        $state = 'FAIL'; if ($_.Passed) { $state = 'PASS' }
        '| {0} | {1} | {2} |' -f $_.Check, $state, ($_.Detail -replace '\|', '/') })
$markdown | Set-Content -LiteralPath (Join-Path $LogDirectory 'installer-validation.md') -Encoding UTF8
Remove-Item -LiteralPath $localAppData -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ("{0} of {1} checks passed. Logs: {2}" -f ($checks.Count - $failed.Count), $checks.Count, $LogDirectory)
Stop-Transcript | Out-Null
if ($failed.Count -gt 0) { exit 1 }
