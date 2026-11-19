#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$InputId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$CaptureId,
    [ValidatePattern('^acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')][string]$BaselineRunId = '-',
    [ValidatePattern('^acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')][string]$TargetRunId = '-',
    [string]$EvidenceDirectory,
    [string]$RunsDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
if (-not $RunsDirectory) { $RunsDirectory = Join-Path $root '.workbench/invoice-rollout-runs' }
$classes = Join-Path $root 'backend/target/classes'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/InvoiceRolloutComparison.class'))) {
    throw 'Build and verify the backend before comparing invoice rollout evidence.'
}
$caseRoot = Join-Path $root 'cases/stripe-invoice-subscription-2025-03-31'
$classpath = $classes + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvoiceRolloutComparison' `
    $caseRoot ([IO.Path]::GetFullPath($EvidenceDirectory)) ([IO.Path]::GetFullPath($RunsDirectory)) `
    $InputId $CaptureId $BaselineRunId $TargetRunId
if ($LASTEXITCODE -eq 3) { Write-Warning 'Invoice rollout comparison is unresolved. Review the JSON report.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Invoice rollout comparison could not be read.' }
