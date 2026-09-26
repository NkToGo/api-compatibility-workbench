#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classpath = (Join-Path $root 'backend/target/classes') + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
if (-not (Test-Path (Join-Path $root 'backend/target/dependency')) -or
    -not (Test-Path (Join-Path $root 'backend/target/classes/io/github/nktogo/apicompatibility/runner/SubscriptionPeriodCase.class'))) {
    throw 'Build the backend with Maven clean verify before running this case.'
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.SubscriptionPeriodCase' `
    (Join-Path $root 'cases/stripe-subscription-periods-2025-07-30') (Join-Path $root '.workbench/period-cases')
if ($LASTEXITCODE -ne 0) { throw 'Subscription period case failed.' }
