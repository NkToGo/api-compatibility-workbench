#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('status', 'cancel', 'recover', 'revoke')][string]$Action,
    [string]$RunId,
    [string]$ExecutionDirectory,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ExecutionDirectory) { $ExecutionDirectory = Join-Path $root '.workbench/executions' }
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
$arguments = @($Action, $ExecutionDirectory, $EvidenceDirectory)
if ($RunId) { $arguments += $RunId }
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.DurableExecution' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Execution management failed; preserve the journal and retry recovery after resolving the error.' }
