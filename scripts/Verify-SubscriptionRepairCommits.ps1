#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Intent,
    [Parameter(Mandatory)][string]$SourcePath,
    [Parameter(Mandatory)][string]$BaselineApiVersion,
    [Parameter(Mandatory)][string]$TargetApiVersion,
    [switch]$Execute,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/SubscriptionRepositoryVerification.class'))) {
    throw 'Build the backend before checking subscription repair commits.'
}
$repo = (Resolve-Path -LiteralPath $Repository).Path
$intentFile = (Resolve-Path -LiteralPath $Intent).Path
$mode = if ($Execute) { 'verify' } else { 'preview' }
$arguments = @($mode, $repo, $intentFile, $SourcePath, $BaselineApiVersion, $TargetApiVersion)
if ($Execute) {
    . (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
    $StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
    $image = (Get-Content -LiteralPath (Join-Path $root 'backend/target/repair-worker-image.txt') -Raw).Trim()
    if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Build the repair worker first.' }
    $arguments += @(([IO.Path]::GetFullPath($StateDirectory)), (Join-Path $root 'cases/stripe-subscription-periods-2025-07-30'), $image)
}
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.SubscriptionRepositoryVerification' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Subscription repository check or verification did not complete.' }
