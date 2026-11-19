#requires -Version 7.0
[CmdletBinding(DefaultParameterSetName = 'Preview')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Preview')][switch]$Preview,
    [Parameter(Mandatory, ParameterSetName = 'Run')][ValidatePattern('^[0-9a-f]{64}$')][string]$ApproveScope,
    [Parameter(Mandatory, ParameterSetName = 'Revoke')][switch]$Revoke,
    [Parameter(Mandatory)][ValidatePattern('^acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')][string]$RunId,
    [Parameter(Mandatory, ParameterSetName = 'Preview')][Parameter(Mandatory, ParameterSetName = 'Run')]
    [ValidatePattern('^[0-9a-f]{64}$')][string]$InputId,
    [Parameter(Mandatory, ParameterSetName = 'Preview')][Parameter(Mandatory, ParameterSetName = 'Run')]
    [ValidatePattern('^[0-9a-f]{64}$')][string]$CaptureId,
    [Parameter(Mandatory, ParameterSetName = 'Preview')][Parameter(Mandatory, ParameterSetName = 'Run')]
    [ValidateSet('BASELINE', 'TARGET')][string]$Phase,
    [Parameter(Mandatory, ParameterSetName = 'Preview')][Parameter(Mandatory, ParameterSetName = 'Run')]
    [ValidatePattern('^sha256:[0-9a-f]{64}$')][string]$ImageId,
    [string]$EvidenceDirectory,
    [string]$RunsDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
if (-not $RunsDirectory) { $RunsDirectory = Join-Path $root '.workbench/invoice-rollout-runs' }
$classes = Join-Path $root 'backend/target/classes'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/InvoiceRolloutDelivery.class'))) {
    throw 'Build and verify the backend before using the invoice rollout runner.'
}
$caseRoot = Join-Path $root 'cases/stripe-invoice-subscription-2025-03-31'
$arguments = @($PSCmdlet.ParameterSetName.ToLowerInvariant(),
    [IO.Path]::GetFullPath($EvidenceDirectory), $caseRoot, [IO.Path]::GetFullPath($RunsDirectory))
if ($Revoke) {
    $arguments += $RunId
} else {
    $arguments += @($InputId, $CaptureId, $RunId, $Phase.ToUpperInvariant(), $ImageId)
    if ($ApproveScope) { $arguments += $ApproveScope }
}
$classpath = $classes + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvoiceRolloutDelivery' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Invoice rollout operation needs attention. Review the result or attempt record.' }
