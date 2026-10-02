#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('register','run','revoke')][string]$Action = 'run',
    [string[]]$TaskIds,
    [string]$BatchId,
    [ValidateRange(1,2)][int]$Parallelism = 2,
    [ValidateRange(1,48)][int]$MaxCalls = 24,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'register') {
    if ($BatchId -or $TaskIds.Count -lt 2 -or $TaskIds.Count -gt 4) { throw 'Register two to four fresh tasks; omit BatchId.' }
    $arguments = @('register', $StateDirectory, "$Parallelism", "$MaxCalls") + $TaskIds
} else {
    if (-not $BatchId -or $TaskIds.Count -gt 0) { throw 'Supply BatchId and omit TaskIds for run or revoke.' }
    $arguments = @($Action, $StateDirectory, $BatchId)
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
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.ParallelInvestigations' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Batch command failed. Inspect retained records before registering replacement work.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
