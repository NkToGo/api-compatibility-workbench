#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Intent,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/PublishedRepairStatus.class'))) {
    throw 'Build the backend before checking a published repair.'
}
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
$file = (Resolve-Path -LiteralPath $Intent).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath 'io.github.nktogo.apicompatibility.github.PublishedRepairStatus' `
    ([IO.Path]::GetFullPath($StateDirectory)) $file
if ($LASTEXITCODE -eq 3) { Write-Warning 'Current repair status could not be confirmed. Review the result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Published repair status could not be checked.' }
