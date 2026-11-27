#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$VerificationId,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Intent,
    [Parameter(Mandatory)][string]$SourcePath,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/runner/RepairCommitBinding.class'))) {
    throw 'Build the backend before checking a repair commit.'
}
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
$repo = (Resolve-Path -LiteralPath $Repository).Path
$inputFile = (Resolve-Path -LiteralPath $Intent).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.RepairCommitBinding' `
    ([IO.Path]::GetFullPath($StateDirectory)) $VerificationId $repo $inputFile $SourcePath
if ($LASTEXITCODE -eq 3) { Write-Warning 'No repair commit binding was established. Review the check result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Repair commit check could not start.' }
