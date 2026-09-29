#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$VerificationId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.SubscriptionInvestigationTools' $StateDirectory $VerificationId
if ($LASTEXITCODE -ne 0) { throw 'Could not register retained subscription evidence.' }
