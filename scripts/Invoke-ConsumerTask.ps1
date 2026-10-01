#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('run','revoke')][string]$Action = 'run',
    [Parameter(Mandatory)][string]$TaskId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'revoke') {
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.ConsumerTasks' revoke $StateDirectory $TaskId
    if ($LASTEXITCODE -ne 0) { throw 'Task revocation failed.' }
} else {
    # Reuse the existing credential loader; the credential remains in the trusted host process.
    $previousKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Process')
    try {
        if (-not $previousKey) {
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
        & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.ConsumerTasks' run $StateDirectory $TaskId
        if ($LASTEXITCODE -ne 0) { throw 'Task did not finish. Inspect its retained records before registering replacement work.' }
    } finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
}
