#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$VerificationId,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Intent,
    [Parameter(Mandatory)][string]$SourcePath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$BindingIdentity,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/GitHubRepairRefs.class'))) {
    throw 'Build the backend before checking repair branches.'
}
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
$repo = (Resolve-Path -LiteralPath $Repository).Path
$inputFile = (Resolve-Path -LiteralPath $Intent).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath 'io.github.nktogo.apicompatibility.github.GitHubRepairRefs' `
    ([IO.Path]::GetFullPath($StateDirectory)) $VerificationId $repo $inputFile $SourcePath $BindingIdentity
if ($LASTEXITCODE -eq 3) { Write-Warning 'The repair branches could not be confirmed. Review the check result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Remote repair branch check could not start.' }
