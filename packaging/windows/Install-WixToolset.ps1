<#
.SYNOPSIS
    Installs the pinned WiX Toolset used by jpackage to build the MSI/EXE.

.DESCRIPTION
    Installs WiX as a global .NET tool (requires the .NET SDK 8 or newer) and adds the Util and UI
    extensions that jpackage 25 needs. Re-running is safe. Open a new PowerShell window afterwards,
    or keep using this one: %USERPROFILE%\.dotnet\tools is added to PATH for the current session.
#>
[CmdletBinding()]
param([string]$Version = '5.0.2')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

if (-not (Get-Command 'dotnet.exe' -ErrorAction SilentlyContinue)) {
    throw 'The .NET SDK is required to install WiX. Install the .NET 8 SDK from https://dotnet.microsoft.com/download, then run this script again.'
}
$installed = (Invoke-Captured 'dotnet.exe' @('tool', 'list', '--global')).Output | Where-Object { $_ -match '^\s*wix\s' }
if ($installed) { Invoke-Checked 'dotnet.exe' @('tool', 'update', '--global', 'wix', '--version', $Version) "Update WiX to $Version" }
else { Invoke-Checked 'dotnet.exe' @('tool', 'install', '--global', 'wix', '--version', $Version) "Install WiX $Version" }

$tools = Join-Path $env:USERPROFILE '.dotnet\tools'
if (($env:PATH -split ';') -notcontains $tools) { $env:PATH = "$tools;$env:PATH" }
foreach ($extension in 'WixToolset.Util.wixext', 'WixToolset.UI.wixext') {
    Invoke-Checked (Join-Path $tools 'wix.exe') @('extension', 'add', '--global', "$extension/$Version") "Add $extension $Version"
}
Write-Host "Ready: $(Get-WixToolset)" -ForegroundColor Green
