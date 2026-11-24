#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory, ParameterSetName = 'Publish')][switch]$Publish,
    [Parameter(Mandatory, ParameterSetName = 'Reconcile')][switch]$Reconcile,
    [Parameter(Mandatory)][string]$Intent,
    [Parameter(Mandatory, ParameterSetName = 'Publish')][string]$VerificationId,
    [Parameter(Mandatory, ParameterSetName = 'Publish')][string]$Repository,
    [Parameter(Mandatory, ParameterSetName = 'Publish')][string]$SourcePath,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/DraftRepairPublication.class'))) {
    throw 'Build the backend before publishing a repair draft.'
}
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$arguments = @($PSCmdlet.ParameterSetName.ToLowerInvariant(), [IO.Path]::GetFullPath($StateDirectory), (Resolve-Path -LiteralPath $Intent).Path)
if ($Publish) {
    $arguments += @($VerificationId, (Resolve-Path -LiteralPath $Repository).Path, $SourcePath)
}
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath 'io.github.nktogo.apicompatibility.github.DraftRepairPublication' @arguments
if ($LASTEXITCODE -eq 3) { Write-Warning 'Draft publication was not confirmed. Review the saved operation and result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Draft repair publication could not complete.' }
