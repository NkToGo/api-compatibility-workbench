#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Selection,
    [Parameter(Mandatory)][string]$Plan,
    [Parameter(Mandatory)][string]$Configuration
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/github/MigrationConfiguration.class'))) {
    throw 'Build the backend with Maven clean verify before inspecting migration configuration.'
}
$selected = (Resolve-Path -LiteralPath $Selection).Path
$planned = (Resolve-Path -LiteralPath $Plan).Path
$configurationFile = (Resolve-Path -LiteralPath $Configuration).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath `
    'io.github.nktogo.apicompatibility.github.MigrationConfiguration' $selected $planned $configurationFile
if ($LASTEXITCODE -ne 0) { throw 'Migration configuration inspection did not complete. See the sanitized result or failure above.' }
