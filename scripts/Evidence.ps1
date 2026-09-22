#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('list', 'import', 'verify', 'export')][string]$Action,
    [string]$EvidenceDirectory,
    [string]$RunId,
    [string]$Receipt,
    [string]$Destination
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root '.workbench/evidence' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
$arguments = @($Action, $EvidenceDirectory)
switch ($Action) {
    'import' { if (-not $Receipt) { throw '-Receipt is required.' }; $arguments += $Receipt }
    'verify' { if (-not $RunId) { throw '-RunId is required.' }; $arguments += $RunId }
    'export' {
        if (-not $RunId -or -not $Destination) { throw '-RunId and a new -Destination directory are required.' }
        $arguments += @($RunId, $Destination)
    }
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.EvidenceStore' @arguments
if ($LASTEXITCODE -ne 0) { throw "Evidence operation failed (exit $LASTEXITCODE)." }
