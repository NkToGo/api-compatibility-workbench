#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Intent,
    [Parameter(Mandatory)][string]$Environment,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/RepairDeploymentStatus.class'))) {
    throw 'Build the backend before checking a repair deployment.'
}
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
$file = (Resolve-Path -LiteralPath $Intent).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath 'io.github.nktogo.apicompatibility.github.RepairDeploymentStatus' `
    ([IO.Path]::GetFullPath($StateDirectory)) $file $Environment
if ($LASTEXITCODE -eq 3) { Write-Warning 'Repair deployment observation is incomplete or unavailable. Review the result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Repair deployment status could not be checked.' }
