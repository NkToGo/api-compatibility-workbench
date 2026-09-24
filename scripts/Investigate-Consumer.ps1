#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start','resume')][string]$Action = 'start',
    [string]$ContextId,
    [string]$AgentId,
    [string]$Model = 'gpt-5.6-terra',
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
if ($Action -eq 'start' -and -not $ContextId) { throw 'Register a case with Investigation.ps1 and supply ContextId.' }
if ($Action -eq 'resume' -and -not $AgentId) { throw 'Supply the existing AgentId to resume.' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
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
    $arguments = if ($Action -eq 'start') { @('start', $StateDirectory, $ContextId, $Model) } else { @('resume', $StateDirectory, $AgentId) }
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.ConsumerInvestigator' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Investigation did not finish. Inspect its retained result and broker records before starting another run.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
