#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Selection,
    [Parameter(Mandatory)][string]$Plan
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/github/GitHubSourceInventory.class'))) {
    throw 'Build the backend with Maven clean verify before inventorying repository sources.'
}
$selected = (Resolve-Path -LiteralPath $Selection).Path
$planned = (Resolve-Path -LiteralPath $Plan).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
# Credential-bearing HTTP diagnostics and implicit retries must remain disabled.
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath `
    'io.github.nktogo.apicompatibility.github.GitHubSourceInventory' $selected $planned
if ($LASTEXITCODE -ne 0) { throw 'GitHub source inventory did not complete. See the sanitized result or failure above.' }
