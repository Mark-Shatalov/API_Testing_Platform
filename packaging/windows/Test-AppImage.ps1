<#
.SYNOPSIS
    Starts a packaged or installed API Testing Platform launcher once and checks where it writes.

.DESCRIPTION
    The launcher runs with a temporary LOCALAPPDATA, without JAVA_HOME and with Java removed from PATH,
    so it must use its bundled runtime. The built-in startup probe (platform.startup.measurement) records
    when the window is ready and closes the application normally. The check fails unless:
      - the process exits with code 0 after the window was ready;
      - the database, logs and the versioned native cache are created below LOCALAPPDATA;
      - nothing in the installation directory is created or changed.
    Returns a summary object. Your real data in %LOCALAPPDATA%\API Testing Platform is never used.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Launcher,
    [string]$ExpectedVersion,
    # Use (and keep) this LOCALAPPDATA instead of a temporary one, e.g. to test data across upgrades.
    [string]$LocalAppData,
    [int]$TimeoutSeconds = 90
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$Launcher = (Resolve-Path -LiteralPath $Launcher).Path
$installation = Split-Path -Parent $Launcher
$work = New-EmptyDirectory (Join-Path ([IO.Path]::GetTempPath()) ('api-testing-platform-smoke-' + [guid]::NewGuid().ToString('N')))
$temporaryData = [string]::IsNullOrWhiteSpace($LocalAppData)
if ($temporaryData) { $LocalAppData = Join-Path $work 'LocalAppData' }
New-Item -ItemType Directory -Path $LocalAppData -Force | Out-Null
$LocalAppData = (Resolve-Path -LiteralPath $LocalAppData).Path
$data = Join-Path $LocalAppData 'API Testing Platform'
$measurement = Join-Path $work 'startup.properties'

function Get-Snapshot([string]$Folder) {
    return @(Get-ChildItem -LiteralPath $Folder -Recurse -Force -File | ForEach-Object {
            '{0}|{1}|{2}' -f $_.FullName.Substring($Folder.Length), $_.Length, $_.LastWriteTimeUtc.Ticks
        } | Sort-Object)
}
$before = @(Get-Snapshot $installation)

$saved = @{ LOCALAPPDATA = $env:LOCALAPPDATA; JAVA_TOOL_OPTIONS = $env:JAVA_TOOL_OPTIONS; JAVA_HOME = $env:JAVA_HOME; PATH = $env:PATH }
$process = $null
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    $env:LOCALAPPDATA = $LocalAppData
    $env:JAVA_TOOL_OPTIONS = '"-Dplatform.startup.measurement=' + $measurement.Replace('\', '/') + '" -Dplatform.startup.autoExit=true'
    Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
    $env:PATH = (($env:PATH -split ';') | Where-Object { $_ -and $_ -notmatch '(?i)(java|jdk|jre|adoptium|zulu|corretto)' }) -join ';'
    $process = Start-Process -FilePath $Launcher -PassThru -WorkingDirectory $work `
        -RedirectStandardOutput (Join-Path $work 'stdout.log') -RedirectStandardError (Join-Path $work 'stderr.log')
    $null = $process.Handle   # caches the handle so ExitCode is available after the process ends
    while (-not (Test-Path -LiteralPath $measurement) -and -not $process.HasExited -and $timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Milliseconds 200
    }
    $readyMs = $timer.ElapsedMilliseconds
    if (-not $process.WaitForExit(30000)) {
        Stop-Process -Id $process.Id -Force
        throw 'The application did not close within 30 seconds after the startup probe.'
    }
    $exitCode = $process.ExitCode
} finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) { Remove-Item "Env:\$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:\$name" $saved[$name] }
    }
}

$failures = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $measurement)) { $failures.Add("The window did not become ready within $TimeoutSeconds seconds.") }
if ($exitCode -ne 0) { $failures.Add("The application exited with code $exitCode.") }
if (-not (Test-Path -LiteralPath (Join-Path $data 'platform.sqlite'))) { $failures.Add("No database was created in $data.") }
if (-not (Get-ChildItem -LiteralPath (Join-Path $data 'logs') -File -ErrorAction SilentlyContinue)) { $failures.Add('No structured log was written below LOCALAPPDATA.') }
$cacheVersion = $ExpectedVersion
if ([string]::IsNullOrWhiteSpace($cacheVersion)) { $cacheVersion = '*' }
$javafxCache = @(Get-ChildItem -Path (Join-Path $data "cache\$cacheVersion\openjfx") -Recurse -File -Filter '*.dll' -ErrorAction SilentlyContinue)
if ($javafxCache.Count -eq 0) { $failures.Add("JavaFX native libraries were not cached below $data\cache\$cacheVersion.") }
$after = @(Get-Snapshot $installation)
$changed = @(Compare-Object $before $after | ForEach-Object { ($_.InputObject -split '\|')[0] })
if ($changed.Count -gt 0) { $failures.Add("Files changed in the installation directory: $($changed -join ', ')") }
$runtimeRelease = Join-Path $installation 'runtime\release'
$bundledJava = ''
if (Test-Path -LiteralPath $runtimeRelease) {
    $bundledJava = ((Get-Content -LiteralPath $runtimeRelease | Where-Object { $_ -match '^JAVA_VERSION=' }) -replace '^JAVA_VERSION="?|"$', '')
}
if ($bundledJava -notmatch '^25(\.|$)') { $failures.Add("The bundled runtime does not report Java 25 ($runtimeRelease).") }

$probe = @{}
if (Test-Path -LiteralPath $measurement) {
    Get-Content -LiteralPath $measurement | Where-Object { $_ -match '^[a-zA-Z]+=' } | ForEach-Object {
        $pair = $_ -split '=', 2; $probe[$pair[0]] = $pair[1]
    }
}
$result = [ordered]@{
    launcher            = $Launcher
    passed              = ($failures.Count -eq 0)
    exitCode            = $exitCode
    launchToReadyMs     = $readyMs
    jvmToReadyMs        = $probe['jvmToReadyMs']
    usedHeapMiB         = $probe['usedHeapMiB']
    bundledJava         = $bundledJava
    javafxCachedLibrary = ($javafxCache | Select-Object -First 1 | ForEach-Object { $_.FullName.Substring($LocalAppData.Length) })
    failures            = @($failures)
}
if ($failures.Count -gt 0) {
    # The launcher prints JVM start-up errors (for example a missing runtime module) to either stream.
    $tail = ''
    foreach ($stream in 'stdout.log', 'stderr.log') {
        $file = Join-Path $work $stream
        if (Test-Path -LiteralPath $file) { $tail += "`n[$stream]`n" + ((Get-Content -LiteralPath $file -Tail 40) -join "`n") }
    }
    throw ("Smoke test failed for $Launcher`n - " + ($failures -join "`n - ") + "`nLauncher output (last lines):$tail`nWork folder kept: $work")
}
Write-Host "Smoke test passed: ready after $readyMs ms, Java $bundledJava, data and caches below LOCALAPPDATA only." -ForegroundColor Green
# A caller-supplied LOCALAPPDATA lives outside the work folder and is kept.
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
return [pscustomobject]$result
