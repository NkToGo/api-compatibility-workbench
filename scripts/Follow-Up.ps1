#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('register','run','revoke')][string]$Action = 'run',
    [string]$ReviewId,
    [string[]]$ProposalIds,
    [string]$FollowUpId,
    [ValidateRange(0,24)][int]$MaxCalls = 0,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'register') {
    if (-not $ReviewId -or -not $ProposalIds -or $FollowUpId) { throw 'Supply ReviewId and ProposalIds; omit FollowUpId for registration.' }
    $arguments = @('register', $StateDirectory, $ReviewId, "$MaxCalls") + $ProposalIds
} else {
    if (-not $FollowUpId -or $ReviewId -or $ProposalIds -or $PSBoundParameters.ContainsKey('MaxCalls')) { throw 'Supply FollowUpId and omit ReviewId, ProposalIds and MaxCalls for run or revoke.' }
    $arguments = @($Action, $StateDirectory, $FollowUpId)
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
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.FollowUpDispatch' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Follow-up command failed. Inspect retained records before registering replacement work.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
