#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('policy','check','run','read')][string]$Action = 'check',
    [string]$InputBundleId,
    [string]$TrialId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
switch ($Action) {
    'policy' {
        if ($InputBundleId -or $TrialId) { throw 'Policy accepts only StateDirectory.' }
        $arguments = @('policy',$StateDirectory)
    }
    'check' {
        if (-not $InputBundleId -or $TrialId) { throw 'Check requires only InputBundleId.' }
        $arguments = @('check',$StateDirectory,$InputBundleId)
    }

    default {
        if (-not $TrialId -or $InputBundleId) { throw 'Run/read require only TrialId.' }
        $arguments = @($Action,$StateDirectory,$TrialId)
    }
}
$previousKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Process')
try {
    # Read-only inspection never loads a credential.
    if ($Action -in @('check','run') -and -not $previousKey) {
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
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Team-stage run failed validation. Inspect retained state before resuming the same trial.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
