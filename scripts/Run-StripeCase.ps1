#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/StripeCaseRunner.class'))) {
    throw 'Build the backend with Maven clean verify before running the case.'
}
$caseRoot = Join-Path $root 'cases/stripe-customer-sources-2020-08-27'
& (Join-Path $PSScriptRoot 'Prepare-StripeCase.ps1')
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.StripeCaseRunner' $caseRoot `
    (Join-Path $caseRoot 'target/dependencies') (Join-Path $root 'backend/target/case-runs')
if ($LASTEXITCODE -ne 0) { throw "Case runner failed (exit $LASTEXITCODE)." }
