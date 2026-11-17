#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('create', 'status', 'cancel', 'recover', 'run', 'list', 'work')][string]$Action,
    [string]$InputId,
    [string]$JobId,
    [string]$Selection,
    [ValidateRange(1, 10)][int]$MaximumJobs = 1,
    [string]$JournalDirectory,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $JournalDirectory) { $JournalDirectory = Join-Path $root '.workbench/maintainer-jobs' }
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$classes = Join-Path $root 'backend/target/classes'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/github/MaintainerJobs.class'))) { throw 'Build the backend with Maven clean verify before using maintainer jobs.' }
$arguments = @($Action, [IO.Path]::GetFullPath($JournalDirectory), [IO.Path]::GetFullPath($EvidenceDirectory))
switch ($Action) {
    'create' {
        if ($InputId -cnotmatch '^[0-9a-f]{64}$' -or $JobId -or $Selection) { throw 'Create requires one retained InputId only.' }
        $arguments += $InputId
    }
    'list' { if ($InputId -or $JobId -or $Selection) { throw 'List does not accept input, job or selection arguments.' } }
    'work' {
        if (-not $Selection -or $InputId -or $JobId) { throw 'Work requires a host Selection and optional MaximumJobs.' }
        $arguments += @((Resolve-Path -LiteralPath $Selection).Path, [string]$MaximumJobs)
    }
    default {
        if ($JobId -cnotmatch '^planjob-[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$' -or $InputId) { throw 'This action requires one complete JobId.' }
        if ($Action -ne 'run' -and $Selection) { throw 'Offline job actions do not accept repository selection.' }
        $arguments += $JobId
        if ($Action -eq 'run' -and $Selection) { $arguments += (Resolve-Path -LiteralPath $Selection).Path }
    }
}
$classpath = $classes + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' '-Djdk.httpclient.HttpClient.log=' `
    '-cp' $classpath 'io.github.nktogo.apicompatibility.github.MaintainerJobs' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Planning job needs attention. Inspect the sanitized status or failure above.' }
