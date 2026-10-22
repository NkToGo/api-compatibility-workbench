#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('policy','check','register','run','read')][string]$Action = 'check',
    [string]$InputDirectory,
    [string]$InputIdentity,
    [string]$PlanPath,
    [string]$WorkloadId,
    [int]$Repetition = 1,
    [string]$TrialId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$jar = Join-Path $root 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend before running invoice team review.' }
$newestSource = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild before running invoice team review.' }
$classPath = $jar + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
if ($Action -eq 'policy') {
    if ($InputDirectory -or $InputIdentity -or $PlanPath -or $WorkloadId -or $TrialId -or $Repetition -ne 1) { throw 'Policy accepts only StateDirectory.' }
    $arguments = @('policy',[IO.Path]::GetFullPath($StateDirectory))
} else {
    if (-not $InputDirectory -or -not $InputIdentity) { throw 'An input directory and independently retained identity are required.' }
    $arguments = @($Action,[IO.Path]::GetFullPath($StateDirectory),[IO.Path]::GetFullPath($InputDirectory),$InputIdentity)
    switch ($Action) {
        'check' { if ($PlanPath -or $WorkloadId -or $TrialId -or $Repetition -ne 1) { throw 'Check requires only InputDirectory and InputIdentity.' } }
        'register' {
            if (-not $PlanPath -or -not $WorkloadId -or $TrialId) { throw 'Register requires PlanPath and WorkloadId.' }
            $arguments += @([IO.Path]::GetFullPath($PlanPath),$WorkloadId,[string]$Repetition)
        }
        default {
            if (-not $TrialId -or $PlanPath -or $WorkloadId -or $Repetition -ne 1) { throw 'Run/read require TrialId.' }
            $arguments += $TrialId
        }
    }
}
$previousKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Process')
try {
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
    & java -cp $classPath io.github.nktogo.apicompatibility.runner.InvoiceTeamRunner @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Invoice team prefix failed validation. Inspect retained state before resuming the same trial.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
