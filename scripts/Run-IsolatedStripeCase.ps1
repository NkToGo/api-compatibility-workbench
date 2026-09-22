#requires -Version 7.0
[CmdletBinding()]
param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $root 'backend/target'
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$imageFile = Join-Path $target 'worker-image.txt'
if (-not (Test-Path -LiteralPath $imageFile)) { throw 'Prepare the image with Build-IsolatedWorker.ps1 first.' }
$image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.DockerCaseExecutor' $image (Join-Path $target 'isolated-case-runs') $EvidenceDirectory
if ($LASTEXITCODE -ne 0) { throw "Isolated case failed (exit $LASTEXITCODE). Read its execution receipt." }
