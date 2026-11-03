# Measures only fresh benchmark instances with temporary LOCALAPPDATA, never your real database.
# Run the performance test once to generate its production module-path report.
param([ValidateRange(1,5)][int]$Samples = 3)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$folder = Join-Path $workspace '.tools\performance'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
[xml]$report = Get-Content (Join-Path $workspace 'app\target\surefire-reports\TEST-dev.apitestingplatform.app.PerformanceRecoveryTest.xml')
$modulePath = ($report.testsuite.properties.property | Where-Object name -eq 'jdk.module.path').value
$originalLocal = $env:LOCALAPPDATA
$javaBinary = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
try {
    $measurements = @()
    foreach ($index in 1..$Samples) {
        $probeData = Join-Path $folder ('fresh-start-' + [guid]::NewGuid())
        New-Item -ItemType Directory -Path $probeData | Out-Null
        $env:LOCALAPPDATA = $probeData
        $measurement = Join-Path $folder ("startup-$index-" + [guid]::NewGuid() + ".properties")
        $argumentFile = Join-Path $folder "startup-$index.args"
        @('--enable-native-access=javafx.graphics,org.xerial.sqlitejdbc,com.sun.jna', '--module-path', ('"' + $modulePath.Replace('\','/') + '"'), ('"-Dplatform.startup.measurement=' + $measurement.Replace('\','/') + '"'), '-Dplatform.startup.autoExit=true', '--module', 'dev.apitestingplatform.app/dev.apitestingplatform.app.ApiTestingPlatformApplication') | Set-Content -LiteralPath $argumentFile
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $process = Start-Process -FilePath $javaBinary -ArgumentList ('@"' + $argumentFile + '"') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $folder "startup-$index.out.log") -RedirectStandardError (Join-Path $folder "startup-$index.err.log")
        $peak = 0L
        while (!(Test-Path -LiteralPath $measurement) -and !$process.HasExited -and $timer.ElapsedMilliseconds -lt 20000) {
            $process.Refresh(); $peak = [Math]::Max($peak, $process.WorkingSet64); Start-Sleep -Milliseconds 50
        }
        if (!(Test-Path -LiteralPath $measurement)) { throw "Startup measurement $index did not complete; inspect its stderr." }
        $wall = $timer.ElapsedMilliseconds
        $process.Refresh(); $peak = [Math]::Max($peak, $process.WorkingSet64)
        $measurements += [pscustomobject]@{Sample=$index; WallToMeasurementMs=$wall; WorkingSetMiB=[Math]::Round($process.WorkingSet64/1MB,1); PeakWorkingSetMiB=[Math]::Round($peak/1MB,1); Properties=(Get-Content $measurement | Where-Object {$_ -notmatch '^#'} ) -join ';'}
        if (!$process.WaitForExit(12000)) { throw "Benchmark app did not close normally." }
        if ($process.ExitCode -ne 0) { throw "Benchmark app returned $($process.ExitCode)." }
    }
    $measurements | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'startup-samples.json')
    $measurements | Format-List
} finally { $env:LOCALAPPDATA = $originalLocal }
