#requires -Version 7.0
[CmdletBinding(DefaultParameterSetName = 'Preview')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Preview')][switch]$Preview,
    [Parameter(Mandatory, ParameterSetName = 'Record')][ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewedIdentity,
    [Parameter(Mandatory, ParameterSetName = 'Record')][ValidateSet('ACCEPT_NO_REGRESSION', 'HOLD')][string]$Disposition,
    [Parameter(Mandatory, ParameterSetName = 'Check')][ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewId,
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
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/InvoiceRolloutReview.class'))) {
    throw 'Build and verify the backend before reviewing invoice rollout evidence.'
}
$caseRoot = Join-Path $root 'cases/stripe-invoice-subscription-2025-03-31'
$arguments = @($PSCmdlet.ParameterSetName.ToLowerInvariant(), $caseRoot,
    [IO.Path]::GetFullPath($EvidenceDirectory), [IO.Path]::GetFullPath($RunsDirectory),
    $InputId, $CaptureId, $BaselineRunId, $TargetRunId)
if ($ReviewedIdentity) { $arguments += @($ReviewedIdentity, $Disposition.ToUpperInvariant()) }
if ($ReviewId) { $arguments += $ReviewId }
$classpath = $classes + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvoiceRolloutReview' @arguments
if ($LASTEXITCODE -eq 3) { Write-Warning 'The historical decision does not apply to the supplied evidence. Review the JSON result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Invoice review could not be completed.' }
