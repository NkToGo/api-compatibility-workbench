#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('probe','recover','revoke')][string]$Action = 'probe',
    [string]$Model = 'gpt-5.6-terra',
    [string]$CallId,
    [string]$BrokerDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $BrokerDirectory) { $BrokerDirectory = Join-Path $root '.workbench/model-calls' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
$previousKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Process')
try {
    if ($Action -eq 'probe' -and -not $previousKey) {
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
    $argument = if ($Action -eq 'probe') { $Model } else { $CallId }
    if (-not $argument) { throw 'A call ID is required.' }
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.ModelCallBroker' $Action $BrokerDirectory $argument
    if ($LASTEXITCODE -ne 0) { throw 'Broker command did not complete. Inspect its sanitized result; it will not be retried automatically.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
