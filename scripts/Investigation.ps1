#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('register', 'describe_case', 'read_input', 'run_case', 'read_run')][string]$Action,
    [string]$ContextId,
    [ValidateSet('case-index', 'provenance', 'fixtures', 'expectations', 'baseline-source', 'reference-source')][string]$InputId,
    [ValidateRange(0,2147483647)][int]$Offset = 0,
    [ValidateRange(1,4000)][int]$MaxCharacters = 2000,
    [string]$RequestId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $root 'backend/target'
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'register') {
    $imageFile = Join-Path $target 'worker-image.txt'
    if (-not (Test-Path -LiteralPath $imageFile)) { throw 'Prepare the image with Build-IsolatedWorker.ps1 first.' }
    $image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
    $case = Join-Path $root 'cases/stripe-customer-sources-2020-08-27'
    $arguments = @('register', $StateDirectory, $case, (Join-Path $case 'target/dependencies'), $image)
} else {
    if (-not $ContextId) { throw 'ContextId is required for tool calls.' }
    $toolArguments = switch ($Action) {
        'describe_case' { @{} }
        'read_input' {
            if (-not $InputId) { throw 'InputId is required.' }
            @{ inputId = $InputId; offset = $Offset; maxCharacters = $MaxCharacters }
        }
        default {
            if (-not $RequestId) { throw 'RequestId is required; reuse it when checking or retrying the same test request.' }
            @{ requestId = $RequestId }
        }
    }
    $arguments = @('invoke', $StateDirectory, $ContextId, $Action, (ConvertTo-Json -InputObject $toolArguments -Compress))
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvestigationTools' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Investigation tool failed. Inspect its error code; do not substitute a new request ID to retry uncertain execution.' }
