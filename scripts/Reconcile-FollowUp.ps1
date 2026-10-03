#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('register','run','revoke')][string]$Action = 'run',
    [string]$FollowUpId,
    [string]$ReviewId,
    [string]$Model = 'gpt-5.6-terra',
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'register') {
    if (-not $FollowUpId -or $ReviewId) { throw 'Supply a completed FollowUpId and omit ReviewId for registration.' }
    $arguments = @('reconcile', $StateDirectory, $FollowUpId, $Model)
} else {
    if (-not $ReviewId -or $FollowUpId -or $PSBoundParameters.ContainsKey('Model')) { throw 'Supply ReviewId and omit FollowUpId and Model for run or revoke.' }
    $arguments = @($Action, $StateDirectory, $ReviewId)
}
$previousKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Process')
try {
    if ($Action -eq 'run' -and -not $previousKey) {
        $keyFile = Join-Path $root '.env.local'
        if (Test-Path -LiteralPath $keyFile) {
            if ((Get-Item -LiteralPath $keyFile).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked credential files are not supported.' }
            $entries = @([IO.File]::ReadAllLines($keyFile) | Where-Object { $_ -match '^OPENAI_API_KEY=' })
            if ($entries.Count -ne 1) { throw 'Configure exactly one OPENAI_API_KEY entry.' }
            $value = $entries[0].Substring('OPENAI_API_KEY='.Length).Trim().Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $value, 'Process')
            Remove-Variable value,entries
        }
    }
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.CoordinationReview' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Reconciliation did not complete. Inspect retained records before replacement work.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
