#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory = $true)][ValidatePattern('^sha256:[0-9a-f]{64}$')][string]$ImageId)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/InvoiceCaseCoverageMain.class'))) {
    throw 'Build the backend with Maven clean verify before capturing invoice case coverage.'
}
$runs = Join-Path $root 'backend/target/invoice-case-runs'
New-Item -ItemType Directory -Force -Path $runs | Out-Null
$output = Join-Path $runs ([Guid]::NewGuid())
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvoiceCaseCoverageMain' `
    (Join-Path $root 'cases/stripe-invoice-subscription-2025-03-31') $ImageId $output
if ($LASTEXITCODE -ne 0) { throw "Invoice case capture incomplete (exit $LASTEXITCODE). Retain the output for investigation." }
