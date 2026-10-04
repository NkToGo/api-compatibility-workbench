#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('capture','read')][string]$Action = 'capture',
    [string]$CustomerStateDirectory,
    [string]$CustomerContextId,
    [string]$CustomerRequestId,
    [string]$SubscriptionStateDirectory,
    [string]$SubscriptionContextId,
    [string]$InputBundleId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'capture') {
    if (-not $CustomerStateDirectory -or -not $CustomerContextId -or -not $CustomerRequestId -or -not $SubscriptionStateDirectory -or -not $SubscriptionContextId -or $InputBundleId) { throw 'Capture requires two existing contexts and the recorded customer request.' }
    $arguments = @('capture',$StateDirectory,$CustomerStateDirectory,$CustomerContextId,$CustomerRequestId,$SubscriptionStateDirectory,$SubscriptionContextId)
} else {
    if (-not $InputBundleId -or $CustomerStateDirectory -or $CustomerContextId -or $CustomerRequestId -or $SubscriptionStateDirectory -or $SubscriptionContextId) { throw 'Read requires only InputBundleId.' }
    $arguments = @('read',$StateDirectory,$InputBundleId)
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.CoordinationInputs' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Captured comparison inputs failed validation.' }
