#requires -Version 7.0
[CmdletBinding()]
param([string]$EvidenceDirectory, [string]$ExecutionDirectory, [ValidateRange(1,3600)][int]$AuthorizationSeconds = 300)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $root 'backend/target'
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
if (-not $ExecutionDirectory) { $ExecutionDirectory = Join-Path $root '.workbench/executions' }
$imageFile = Join-Path $target 'worker-image.txt'
if (-not (Test-Path -LiteralPath $imageFile)) { throw 'Prepare the image with Build-IsolatedWorker.ps1 first.' }
$image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.DurableExecution' 'run' $ExecutionDirectory $EvidenceDirectory $image $AuthorizationSeconds
if ($LASTEXITCODE -ne 0) { throw "Isolated case failed (exit $LASTEXITCODE). Inspect the registered run with Execution.ps1; recover it if required." }
