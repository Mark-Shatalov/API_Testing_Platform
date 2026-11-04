<#
.SYNOPSIS
    Runs Test-Installer.ps1 inside a fresh Windows Sandbox: a clean, disposable Windows with no Java.

.DESCRIPTION
    Requires Windows 10/11 Pro, Enterprise or Education with the "Windows Sandbox" feature enabled and
    hardware virtualization turned on in the BIOS/UEFI. The repository is mapped read-only; results are
    written to packaging\target\sandbox-validation on the host. Close the sandbox when the PowerShell
    window inside it reports the result; everything inside the sandbox is discarded.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$UpgradeMsi
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Get-RepositoryRoot
$sandbox = Join-Path $env:WINDIR 'System32\WindowsSandbox.exe'
if (-not (Test-Path -LiteralPath $sandbox)) { throw 'Windows Sandbox is not enabled. Enable it in "Turn Windows features on or off", or validate on a clean VM.' }
$results = (New-Item -ItemType Directory -Path (Join-Path $root 'packaging\target\sandbox-validation') -Force).FullName

function Get-SandboxPath([string]$Path) {
    $full = (Resolve-Path -LiteralPath $Path).Path
    if (-not $full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw "$Path must be inside the repository (for example dist\)." }
    return 'C:\Validation\repo' + $full.Substring($root.Length)
}
$arguments = "-Msi `"$(Get-SandboxPath $Msi)`""
if ($UpgradeMsi) { $arguments += " -UpgradeMsi `"$(Get-SandboxPath $UpgradeMsi)`"" }
$command = "powershell.exe -NoExit -NoProfile -ExecutionPolicy Bypass -File C:\Validation\repo\packaging\windows\Test-Installer.ps1 $arguments -LogDirectory C:\Validation\results"

$configuration = Join-Path $results 'installer-validation.wsb'
@"
<Configuration>
  <Networking>Disable</Networking>
  <MappedFolders>
    <MappedFolder><HostFolder>$([Security.SecurityElement]::Escape($root))</HostFolder><SandboxFolder>C:\Validation\repo</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$([Security.SecurityElement]::Escape($results))</HostFolder><SandboxFolder>C:\Validation\results</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand><Command>$([Security.SecurityElement]::Escape($command))</Command></LogonCommand>
</Configuration>
"@ | Set-Content -LiteralPath $configuration -Encoding UTF8
Start-Process -FilePath $configuration
Write-Host "Windows Sandbox started. Results will appear in $results"
