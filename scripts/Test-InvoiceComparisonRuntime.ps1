#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputDirectory,
    [Parameter(Mandatory)][string]$InputIdentity,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$jar = Join-Path $root 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend before checking invoice comparison tools.' }
$newestSource = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild before checking invoice comparison tools.' }
$classPath = $jar + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java -cp $classPath io.github.nktogo.apicompatibility.runner.InvoiceComparisonRuntimeMain check ([IO.Path]::GetFullPath($StateDirectory)) ([IO.Path]::GetFullPath($InputDirectory)) $InputIdentity
if ($LASTEXITCODE -ne 0) { throw 'Invoice comparison runtime check failed.' }
