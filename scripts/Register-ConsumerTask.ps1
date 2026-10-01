#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('customer','subscription')][string]$Case,
    [string]$VerificationId,
    [string]$EvidenceStateDirectory,
    [string]$Model = 'gpt-5.6-terra',
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
if (-not $EvidenceStateDirectory) { $EvidenceStateDirectory = $StateDirectory }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Case -eq 'customer') {
    if ($VerificationId) { throw 'VerificationId applies to subscription tasks only.' }
    $imageFile = Join-Path $target 'worker-image.txt'
    if (-not (Test-Path -LiteralPath $imageFile)) { throw 'Prepare the immutable worker with Build-IsolatedWorker.ps1 first.' }
    $image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
    $caseRoot = Join-Path $root 'cases/stripe-customer-sources-2020-08-27'
    $arguments = @('customer', $StateDirectory, $caseRoot, (Join-Path $caseRoot 'target/dependencies'), $image, $Model)
} else {
    if (-not $VerificationId) { throw 'Supply a completed subscription VerificationId.' }
    $arguments = @('subscription', $StateDirectory, $EvidenceStateDirectory, $VerificationId, $Model)
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.ConsumerTasks' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Task registration failed. No investigation was dispatched.' }
