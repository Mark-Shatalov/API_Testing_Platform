# Shared helpers for the Windows packaging scripts. Compatible with Windows PowerShell 5.1 and PowerShell 7.
Set-StrictMode -Version 3.0

function Get-RepositoryRoot {
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
}

function Assert-WindowsX64 {
    if ($env:OS -ne 'Windows_NT') { throw 'Windows packaging must run on Windows 10 or Windows 11.' }
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'A 64-bit Windows installation is required.' }
}

function Get-XmlText([System.Xml.XmlElement]$Parent, [string]$Name) {
    $node = $Parent.SelectSingleNode("*[local-name()='$Name']")
    if ($null -eq $node -or [string]::IsNullOrWhiteSpace($node.InnerText)) { throw "pom.xml is missing <$Name>." }
    return $node.InnerText.Trim()
}

# packaging/pom.xml is the single source of installer metadata.
function Get-InstallerSettings([string]$Root) {
    [xml]$packaging = Get-Content -LiteralPath (Join-Path $Root 'packaging\pom.xml') -Raw
    [xml]$parent = Get-Content -LiteralPath (Join-Path $Root 'pom.xml') -Raw
    $properties = $packaging.DocumentElement.SelectSingleNode("*[local-name()='properties']")
    return [pscustomobject]@{
        Name           = Get-XmlText $properties 'installer.name'
        Vendor         = Get-XmlText $properties 'installer.vendor'
        Description    = Get-XmlText $properties 'installer.description'
        Copyright      = Get-XmlText $properties 'installer.copyright'
        UpgradeUuid    = Get-XmlText $properties 'installer.upgradeUuid'
        MainModule     = Get-XmlText $properties 'installer.mainModule'
        JavaOptions    = Get-XmlText $properties 'installer.javaOptions'
        ProjectVersion = Get-XmlText $parent.DocumentElement 'version'
        RemoveLauncher = 'Remove API Testing Platform Data'
        UninstallLauncher = 'Uninstall API Testing Platform'
    }
}

# Windows Installer compares major.minor.build only, with limits 255.255.65535.
function ConvertTo-InstallerVersion([string]$Version) {
    $candidate = $Version -replace '-SNAPSHOT$', ''
    if ($candidate -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
        throw "Installer version '$Version' must look like MAJOR.MINOR.PATCH (an optional -SNAPSHOT suffix is removed)."
    }
    if ([int]$Matches[1] -gt 255 -or [int]$Matches[2] -gt 255 -or [int]$Matches[3] -gt 65535) {
        throw "Installer version '$candidate' exceeds the Windows Installer limit 255.255.65535."
    }
    return $candidate
}

function Invoke-Checked([string]$FilePath, [string[]]$Arguments, [string]$Activity) {
    Write-Host "> $Activity" -ForegroundColor Cyan
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Activity failed with exit code $LASTEXITCODE." }
}

# Captures stdout and stderr as text. Windows PowerShell 5.1 would otherwise turn redirected stderr into errors.
function Invoke-Captured([string]$FilePath, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $FilePath @Arguments 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    return [pscustomobject]@{ ExitCode = $code; Output = $output }
}

function Get-Jdk([string]$JavaHome) {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        throw 'Set JAVA_HOME (or pass -JavaHome) to a JDK 25 installation, for example C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot.'
    }
    # A trailing backslash (C:\...\jdk\) would escape the closing quote when passed to another process.
    $JavaHome = $JavaHome.Trim().Trim('"').TrimEnd('\', '/')
    $tools = [ordered]@{}
    foreach ($tool in 'java', 'jar', 'jimage', 'jlink', 'jpackage', 'jdeps') {
        $path = Join-Path $JavaHome "bin\$tool.exe"
        if (-not (Test-Path -LiteralPath $path)) { throw "$path is missing. JAVA_HOME must point to a full JDK 25, not a JRE." }
        $tools[$tool] = $path
    }
    $settings = Invoke-Captured $tools.java @('-XshowSettings:properties', '-version')
    $read = {
        param($key)
        $line = $settings.Output | Where-Object { $_ -match "^\s*$([regex]::Escape($key)) = " } | Select-Object -First 1
        if ($null -eq $line) { return '' }
        return ($line -replace "^\s*$([regex]::Escape($key)) = ", '').Trim()
    }
    $specification = & $read 'java.specification.version'
    if ($specification -ne '25') { throw "JDK 25 is required; $JavaHome reports Java '$specification'." }
    return [pscustomobject]@{
        Home     = $JavaHome
        Version  = & $read 'java.runtime.version'
        Vendor   = & $read 'java.vendor'
        Java     = $tools.java
        Jlink    = $tools.jlink
        Jpackage = $tools.jpackage
        Jdeps    = $tools.jdeps
        Jar      = $tools.jar
        Jimage   = $tools.jimage
    }
}

# jpackage 25 supports WiX Toolset v3 (candle/light) and v4/v5 (wix.exe with the Util and UI extensions).
function Get-WixToolset {
    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    if ((Test-Path -LiteralPath (Join-Path $dotnetTools 'wix.exe')) -and (($env:PATH -split ';') -notcontains $dotnetTools)) {
        $env:PATH = "$dotnetTools;$env:PATH"
    }
    $wix = Get-Command 'wix.exe' -ErrorAction SilentlyContinue
    if ($null -ne $wix) {
        $version = ((Invoke-Captured $wix.Source @('--version')).Output | Select-Object -First 1) -replace '\+.*$', ''
        $major = 0
        [void][int]::TryParse(($version -split '\.')[0], [ref]$major)
        if ($major -ge 6) { Write-Warning "WiX $version is newer than the WiX 5 release this build is validated with." }
        if ($major -ge 4) {
            $extensions = (Invoke-Captured $wix.Source @('extension', 'list', '-g')).Output -join "`n"
            foreach ($extension in 'WixToolset.Util.wixext', 'WixToolset.UI.wixext') {
                if ($extensions -notmatch [regex]::Escape($extension)) {
                    throw "WiX $version is missing the global $extension extension. Run packaging\windows\Install-WixToolset.ps1."
                }
            }
            return "WiX $version"
        }
    }
    $candle = Get-Command 'candle.exe' -ErrorAction SilentlyContinue
    $light = Get-Command 'light.exe' -ErrorAction SilentlyContinue
    if ($null -ne $candle -and $null -ne $light) {
        return "WiX $((Get-Item -LiteralPath $candle.Source).VersionInfo.ProductVersion)"
    }
    throw 'WiX Toolset was not found. Run packaging\windows\Install-WixToolset.ps1 (installs WiX 5.0.2 as a .NET tool), then open a new PowerShell window.'
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

# Deletes a build folder. Freshly written launcher .exe files are often held briefly by antivirus
# scanners, so retry for about 30 seconds; a still-running packaged app is reported by name and PID.
function Remove-BuildDirectory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $full = (Resolve-Path -LiteralPath $Path).Path.TrimEnd('\')
    $running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
            $executable = $null
            try { $executable = $_.Path } catch { }
            $executable -and $executable.StartsWith($full + '\', [StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count -gt 0) {
        throw ("Close the packaged application before rebuilding: " + (($running | ForEach-Object { "$($_.ProcessName) (PID $($_.Id))" }) -join ', '))
    }
    for ($attempt = 1; $attempt -le 15; $attempt++) {
        try { Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop; return }
        catch {
            if ($attempt -eq 15) {
                throw "Could not delete $full after 30 seconds: $($_.Exception.Message) Another program (often an antivirus scan or an open File Explorer window) is holding a file there. Wait a minute, or delete the folder manually, and run the build again."
            }
            Start-Sleep -Seconds 2
        }
    }
}

function New-EmptyDirectory([string]$Path) {
    Remove-BuildDirectory $Path
    return (New-Item -ItemType Directory -Path $Path -Force).FullName
}
