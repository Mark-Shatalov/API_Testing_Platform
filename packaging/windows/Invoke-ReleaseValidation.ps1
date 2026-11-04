<#
.SYNOPSIS
    One-command release validation: all automated tests, installer build, app smoke test, and an elevated
    install / upgrade / downgrade-refusal / uninstall cycle. Results go to packaging\target\release-validation.

.DESCRIPTION
    Steps (each is recorded as PASS, FAIL or SKIPPED in summary.md and summary.json):
      1. Toolchain: JDK 25 (JAVA_HOME, or the newest C:\Program Files\Eclipse Adoptium\jdk-25*),
         WiX Toolset (installed with Install-WixToolset.ps1 when the .NET SDK is available).
      2. Build-Installer.ps1: mvnw.cmd clean verify (every automated test), jlink, jpackage app image,
         smoke test, MSI, checksums. Surefire results are summarised per module.
      3. A second MSI with the next patch version, used only to test upgrading in place.
      4. Test-Installer.ps1 in an elevated window (Windows asks for permission). It uses a temporary
         LOCALAPPDATA, so your real application data is not touched, and it uninstalls at the end.
    Use -SkipInstallerLifecycle to skip step 4. Use -LifecycleOnly to skip the tests and the main build,
    reuse dist\API Testing Platform-<version>.msi from an earlier run and repeat steps 3 and 4 only.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\Invoke-ReleaseValidation.ps1
#>
[CmdletBinding()]
param(
    [switch]$SkipInstallerLifecycle,
    [switch]$LifecycleOnly,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

Assert-WindowsX64
$root = Get-RepositoryRoot
$settings = Get-InstallerSettings $root
# packaging\target is removed by "mvnw clean"; collect results in a temporary folder until the end.
$staging = New-EmptyDirectory (Join-Path ([IO.Path]::GetTempPath()) ('api-testing-platform-validation-' + [guid]::NewGuid().ToString('N')))
$steps = New-Object System.Collections.Generic.List[object]
function Add-Step([string]$Name, [string]$State, [string]$Detail) {
    $steps.Add([pscustomobject]@{ Step = $Name; Result = $State; Detail = $Detail })
    $color = @{ PASS = 'Green'; FAIL = 'Red'; SKIPPED = 'Yellow' }[$State]
    Write-Host ("[{0}] {1} - {2}" -f $State, $Name, $Detail) -ForegroundColor $color
}
function Invoke-LoggedScript([string]$Script, [string[]]$Arguments, [string]$Log) {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Script @Arguments 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $Log | Out-Host
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
}

# 1. Toolchain ---------------------------------------------------------------------------------------
$javaHome = $env:JAVA_HOME
$jdk = $null
try { $jdk = Get-Jdk $javaHome } catch {
    $candidate = Get-ChildItem -Path "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $candidate) { throw "JDK 25 not found. $($_.Exception.Message)" }
    $javaHome = $candidate.FullName
    $jdk = Get-Jdk $javaHome
}
$env:JAVA_HOME = $jdk.Home
Add-Step 'JDK' 'PASS' "$($jdk.Vendor) $($jdk.Version) at $($jdk.Home)"
$wix = $null
try { $wix = Get-WixToolset } catch {
    if (Get-Command 'dotnet.exe' -ErrorAction SilentlyContinue) {
        $code = Invoke-LoggedScript (Join-Path $PSScriptRoot 'Install-WixToolset.ps1') @() (Join-Path $staging 'wix-install.log')
        if ($code -eq 0) { try { $wix = Get-WixToolset } catch { } }
    }
}
if ($wix) { Add-Step 'WiX Toolset' 'PASS' $wix }
else { Add-Step 'WiX Toolset' 'FAIL' 'Not available; only the app image can be built. Install the .NET 8 SDK, then run Install-WixToolset.ps1.' }

# 2. Tests, app image, smoke test and installer --------------------------------------------------------
$byModule = @()
if ($LifecycleOnly) {
    $code = 0
    Add-Step 'Automated tests (mvnw clean verify)' 'SKIPPED' '-LifecycleOnly reuses the previous build'
} else {
    $build = @('-JavaHome', $jdk.Home)
    if ($Offline) { $build += '-Offline' }
    if (-not $wix) { $build += '-AppImageOnly' }
    $buildStarted = Get-Date
    $code = Invoke-LoggedScript (Join-Path $PSScriptRoot 'Build-Installer.ps1') $build (Join-Path $staging 'build-installer.log')

    $modules = @()
    foreach ($report in Get-ChildItem -Path $root -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\target\\surefire-reports\\' -and $_.LastWriteTime -ge $buildStarted }) {
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        $module = ($report.FullName.Substring($root.Length + 1) -split '\\')[0]
        $suiteNode = $suite.DocumentElement
        $failed = @($suiteNode.SelectNodes('testcase') | Where-Object { $_.SelectSingleNode('failure|error') } | ForEach-Object {
                $problem = $_.SelectSingleNode('failure|error')
                $message = "$($problem.GetAttribute('message'))"
                if ($message.Length -gt 300) { $message = $message.Substring(0, 300) }
                "$($_.GetAttribute('classname')).$($_.GetAttribute('name')): $message" })
        $modules += [pscustomobject]@{ Module = $module; Tests = [int]$suiteNode.GetAttribute('tests'); Failures = [int]$suiteNode.GetAttribute('failures')
            Errors = [int]$suiteNode.GetAttribute('errors'); Skipped = [int]$suiteNode.GetAttribute('skipped'); FailedCases = $failed }
    }
    $byModule = @($modules | Group-Object Module | ForEach-Object {
            [pscustomobject]@{ Module = $_.Name; Tests = ($_.Group | Measure-Object Tests -Sum).Sum; Failures = ($_.Group | Measure-Object Failures -Sum).Sum
                Errors = ($_.Group | Measure-Object Errors -Sum).Sum; Skipped = ($_.Group | Measure-Object Skipped -Sum).Sum
                FailedCases = @($_.Group | ForEach-Object { $_.FailedCases }) } })
    $total = ($byModule | Measure-Object Tests -Sum).Sum
    $broken = ($byModule | Measure-Object Failures -Sum).Sum + ($byModule | Measure-Object Errors -Sum).Sum
    $skipped = ($byModule | Measure-Object Skipped -Sum).Sum
    if ($total -gt 0 -and $broken -eq 0) { Add-Step 'Automated tests (mvnw clean verify)' 'PASS' "$total tests, $skipped skipped, 0 failures/errors" }
    elseif (-not $total) { Add-Step 'Automated tests (mvnw clean verify)' 'FAIL' 'No test results were produced by this run; see build-installer.log' }
    else { Add-Step 'Automated tests (mvnw clean verify)' 'FAIL' "$total tests, $broken failures/errors, $skipped skipped" }
}

$installerName = "$($settings.Name)-$(ConvertTo-InstallerVersion $settings.ProjectVersion).msi"
$installer = Join-Path $root "dist\$installerName"
if ($LifecycleOnly) {
    if (-not (Test-Path -LiteralPath $installer)) { throw "dist\$installerName does not exist. Run once without -LifecycleOnly." }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'packaging\target\jpackage\modules'))) { throw 'packaging\target\jpackage\modules is missing. Run once without -LifecycleOnly.' }
    Add-Step 'Build, app smoke test and MSI' 'SKIPPED' "reusing dist\$installerName"
} elseif ($code -eq 0) {
    if ($wix) { Add-Step 'Build, app smoke test and MSI' 'PASS' "dist\$installerName" }
    else { Add-Step 'Build and app smoke test' 'PASS' 'App image only (no WiX)' }
} else { Add-Step 'Build-Installer.ps1' 'FAIL' "exit code $code; see build-installer.log" }

# 3. Next patch version for the upgrade test -----------------------------------------------------------
$upgrade = $null
if ($code -eq 0 -and $wix) {
    $parts = (ConvertTo-InstallerVersion $settings.ProjectVersion) -split '\.'
    $next = '{0}.{1}.{2}' -f $parts[0], $parts[1], ([int]$parts[2] + 1)
    $upgradeFolder = Join-Path $staging 'upgrade-test'
    $upgradeCode = Invoke-LoggedScript (Join-Path $PSScriptRoot 'Build-Installer.ps1') @('-JavaHome', $jdk.Home, '-SkipMavenBuild', '-SkipSmokeTest', '-AppVersion', $next, '-Destination', $upgradeFolder) (Join-Path $staging 'build-upgrade-test.log')
    $upgrade = Join-Path $upgradeFolder "$($settings.Name)-$next.msi"
    if ($upgradeCode -eq 0 -and (Test-Path -LiteralPath $upgrade)) { Add-Step 'Upgrade-test MSI' 'PASS' "$next (same code, higher version)" }
    else { Add-Step 'Upgrade-test MSI' 'FAIL' "exit code $upgradeCode"; $upgrade = $null }
}

# 4. Elevated install / upgrade / uninstall --------------------------------------------------------------
if ($SkipInstallerLifecycle) { Add-Step 'Installer lifecycle' 'SKIPPED' '-SkipInstallerLifecycle' }
elseif ($code -ne 0) { Add-Step 'Installer lifecycle' 'SKIPPED' 'The build failed; an older MSI in dist\ is not tested' }
elseif (-not (Test-Path -LiteralPath $installer)) { Add-Step 'Installer lifecycle' 'SKIPPED' 'No MSI was built' }
else {
    $lifecycle = Join-Path $staging 'installer-validation'
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $PSScriptRoot 'Test-Installer.ps1')`" -Msi `"$installer`" -LogDirectory `"$lifecycle`""
    if ($upgrade) { $arguments += " -UpgradeMsi `"$upgrade`"" }
    try {
        Write-Host 'Windows will ask for administrator permission to install and uninstall the MSI.' -ForegroundColor Yellow
        $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs -Wait -PassThru
        $report = Join-Path $lifecycle 'installer-validation.md'
        $passedChecks = 0; $allChecks = 0
        if (Test-Path -LiteralPath (Join-Path $lifecycle 'installer-validation.json')) {
            $json = Get-Content -LiteralPath (Join-Path $lifecycle 'installer-validation.json') -Raw | ConvertFrom-Json
            $allChecks = @($json.checks).Count; $passedChecks = @($json.checks | Where-Object { $_.Passed }).Count
        }
        if ($process.ExitCode -eq 0 -and $allChecks -gt 0) { Add-Step 'Installer lifecycle' 'PASS' "$passedChecks/$allChecks checks; see installer-validation\installer-validation.md" }
        else {
            $problems = ''
            if ($allChecks -gt 0) { $problems = ' - ' + ((@($json.checks | Where-Object { -not $_.Passed }) | ForEach-Object { "$($_.Check): $($_.Detail)" }) -join '; ') }
            elseif (Test-Path -LiteralPath (Join-Path $lifecycle 'transcript.txt')) { $problems = ' - see installer-validation\transcript.txt' }
            Add-Step 'Installer lifecycle' 'FAIL' "exit code $($process.ExitCode); $passedChecks/$allChecks checks passed$problems"
        }
    } catch { Add-Step 'Installer lifecycle' 'SKIPPED' "Not run: $($_.Exception.Message)" }
}

# Summary ----------------------------------------------------------------------------------------------
$summary = [ordered]@{
    testedAtUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    windows     = [Environment]::OSVersion.VersionString
    computer    = "$((Get-CimInstance Win32_Processor | Select-Object -First 1).Name), $([math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)) GB RAM"
    steps       = $steps
    tests       = $byModule
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $staging 'summary.json') -Encoding UTF8
$markdown = @('| Step | Result | Detail |', '| --- | --- | --- |') + @($steps | ForEach-Object { '| {0} | {1} | {2} |' -f $_.Step, $_.Result, ($_.Detail -replace '\|', '/') })
$markdown += @('', '| Module | Tests | Failures | Errors | Skipped |', '| --- | ---: | ---: | ---: | ---: |')
$markdown += @($byModule | ForEach-Object { '| {0} | {1} | {2} | {3} | {4} |' -f $_.Module, $_.Tests, $_.Failures, $_.Errors, $_.Skipped })
$failedCases = @($byModule | ForEach-Object { $_.FailedCases } | Where-Object { $_ })
if ($failedCases.Count -gt 0) { $markdown += @('', 'Failed tests:', '') + @($failedCases | ForEach-Object { "- $_" }) }
$markdown | Set-Content -LiteralPath (Join-Path $staging 'summary.md') -Encoding UTF8

$results = New-EmptyDirectory (Join-Path $root 'packaging\target\release-validation')
Copy-Item -Path (Join-Path $staging '*') -Destination $results -Recurse -Force
foreach ($extra in 'dist\SHA256SUMS.txt', "dist\$($settings.Name)-$(ConvertTo-InstallerVersion $settings.ProjectVersion).build-info.json") {
    if (Test-Path -LiteralPath (Join-Path $root $extra)) { Copy-Item -LiteralPath (Join-Path $root $extra) -Destination $results }
}
Remove-Item -LiteralPath (Join-Path $results 'upgrade-test') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''
$markdown | ForEach-Object { Write-Host $_ }
Write-Host "`nResults: $results" -ForegroundColor Cyan
if (@($steps | Where-Object { $_.Result -eq 'FAIL' }).Count -gt 0) { exit 1 }
