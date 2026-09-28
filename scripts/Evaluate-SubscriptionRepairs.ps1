#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start','resume')][string]$Action = 'start',
    [string]$VerificationId,
    [string]$EvaluationId,
    [ValidateRange(1,5)][int]$Repetitions = 3,
    [string]$Model = 'gpt-5.6-terra',
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
if ($Action -eq 'start' -and -not $VerificationId) { throw 'Supply a completed subscription VerificationId.' }
if ($Action -eq 'resume' -and -not $EvaluationId) { throw 'Supply the existing EvaluationId to resume.' }
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
    if ($Action -eq 'start') {
        $image = (Get-Content -LiteralPath (Join-Path $target 'repair-worker-image.txt') -Raw).Trim()
        if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Build the repair worker first.' }
        $arguments = @('start', $StateDirectory, $VerificationId, $Model, $image, $Repetitions)
    } else { $arguments = @('resume', $StateDirectory, $EvaluationId) }
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.SubscriptionRepairEvaluation' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation did not finish. Preserve its ID and child records; resume the same evaluation.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
