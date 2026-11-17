#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('capture', 'inspect', 'list')][string]$Action,
    [string]$Selection,
    [string]$Plan,
    [string]$Configuration,
    [string]$InputId,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$classes = Join-Path $root 'backend/target/classes'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/github/MigrationInputCapture.class'))) {
    throw 'Build the backend with Maven clean verify before capturing migration inputs.'
}
$arguments = @($Action, [IO.Path]::GetFullPath($EvidenceDirectory))
if ($Action -eq 'capture') {
    if (-not $Selection -or -not $Plan -or -not $Configuration -or $InputId) { throw 'Capture requires Selection, Plan and Configuration only.' }
    $arguments += @((Resolve-Path -LiteralPath $Selection).Path, (Resolve-Path -LiteralPath $Plan).Path, (Resolve-Path -LiteralPath $Configuration).Path)
} else {
    if ($Selection -or $Plan -or $Configuration) { throw 'Offline inspection does not accept repository configuration.' }
    if ($Action -eq 'inspect') {
        if ($InputId -cnotmatch '^[0-9a-f]{64}$') { throw 'Inspect requires a complete input snapshot identity.' }
        $arguments += $InputId
    } elseif ($InputId) { throw 'List does not accept an input snapshot identity.' }
}
$classpath = $classes + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath `
    'io.github.nktogo.apicompatibility.github.MigrationInputCapture' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Migration input operation did not complete without gaps. See the sanitized result or failure above.' }
