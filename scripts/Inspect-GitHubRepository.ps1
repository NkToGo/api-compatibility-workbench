#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Selection)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/github/GitHubRepositoryConnection.class'))) {
    throw 'Build the backend with Maven clean verify before inspecting a repository.'
}
$selected = (Resolve-Path -LiteralPath $Selection).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
# Pin low-level HTTP behavior at JVM startup; credential-bearing headers must not be logged.
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath `
    'io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection' $selected
if ($LASTEXITCODE -ne 0) { throw 'GitHub repository inspection did not complete. See the sanitized failure above.' }
