#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('capture', 'inspect', 'list')][string]$Action,
    [string]$InputId,
    [string]$CaptureId,
    [string]$Selection,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$classes = Join-Path $root 'backend/target/classes'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/github/ConsumerSourceCapture.class'))) {
    throw 'Build the backend with Maven clean verify before capturing consumer sources.'
}
$arguments = @($Action.ToLowerInvariant(), [IO.Path]::GetFullPath($EvidenceDirectory))
switch ($Action) {
    'capture' {
        if ($InputId -cnotmatch '^[0-9a-f]{64}$' -or -not $Selection -or $CaptureId) { throw 'Capture requires InputId and Selection.' }
        $arguments += @($InputId, (Resolve-Path -LiteralPath $Selection).Path)
    }
    'inspect' {
        if ($CaptureId -cnotmatch '^[0-9a-f]{64}$' -or $InputId -or $Selection) { throw 'Inspect requires CaptureId only.' }
        $arguments += $CaptureId
    }
    'list' {
        if ($CaptureId -or $InputId -or $Selection) { throw 'List requires no capture or selection arguments.' }
    }
}
$classpath = $classes + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath `
    'io.github.nktogo.apicompatibility.github.ConsumerSourceCapture' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Consumer source operation needs attention. See the sanitized result or failure above.' }
