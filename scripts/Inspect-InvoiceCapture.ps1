#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$InputId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$CaptureId,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$classes = Join-Path $root 'backend/target/classes'
$dependencies = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/InvoiceCapturePreflight.class'))) {
    throw 'Build and verify the backend before inspecting an invoice source capture.'
}
$caseRoot = Join-Path $root 'cases/stripe-invoice-subscription-2025-03-31'
$classpath = $classes + [IO.Path]::PathSeparator + $dependencies
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvoiceCapturePreflight' `
    $caseRoot ([IO.Path]::GetFullPath($EvidenceDirectory)) $InputId $CaptureId
if ($LASTEXITCODE -ne 0) { throw 'Invoice capture preflight needs attention. See the result or error above.' }
