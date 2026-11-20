#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory, ParameterSetName = 'Describe')][switch]$Describe,
    [Parameter(Mandatory, ParameterSetName = 'Lookup')][switch]$Lookup,
    [Parameter(Mandatory)][string]$Intent
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/GitHubPullRequestLookup.class'))) {
    throw 'Build the backend with Maven clean verify before inspecting a pull request.'
}
$file = (Resolve-Path -LiteralPath $Intent).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
$mode = $PSCmdlet.ParameterSetName.ToLowerInvariant()
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath `
    'io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup' $mode $file
if ($LASTEXITCODE -eq 3) { Write-Warning 'No exact pull request was confirmed. Review the lookup result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Pull request lookup could not start.' }
