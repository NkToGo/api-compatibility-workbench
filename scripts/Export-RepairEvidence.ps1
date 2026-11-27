#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$VerificationId,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Intent,
    [Parameter(Mandatory)][string]$SourcePath,
    [Parameter(Mandatory)][string]$Destination,
    [string]$StateDirectory,
    [switch]$ObserveGitHub,
    [string]$Environment
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/RepairEvidenceExport.class'))) {
    throw 'Build the backend before exporting repair evidence.'
}
if ($PSBoundParameters.ContainsKey('Environment') -and (-not $ObserveGitHub -or [string]::IsNullOrWhiteSpace($Environment))) {
    throw 'Use -Environment with -ObserveGitHub and supply the deployment environment name.'
}
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
$repo = (Resolve-Path -LiteralPath $Repository).Path
$file = (Resolve-Path -LiteralPath $Intent).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
$mode = if ($ObserveGitHub) { 'observe' } else { 'offline' }
$arguments = @(([IO.Path]::GetFullPath($StateDirectory)), $VerificationId, $repo, $file, $SourcePath,
    ([IO.Path]::GetFullPath($Destination)), $mode)
if ($PSBoundParameters.ContainsKey('Environment')) { $arguments += $Environment }
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath 'io.github.nktogo.apicompatibility.github.RepairEvidenceExport' @arguments
if ($LASTEXITCODE -eq 3) { Write-Warning 'Repair evidence export interrupted.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Repair evidence export could not complete.' }
