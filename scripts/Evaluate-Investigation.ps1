#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start','resume')][string]$Action = 'start',
    [string]$EvaluationId,
    [string]$Model = 'gpt-5.6-terra',
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
if ($Action -eq 'resume' -and -not $EvaluationId) { throw 'Supply the existing EvaluationId to resume.' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
if ($Action -eq 'start') {
    $imageFile = Join-Path $target 'worker-image.txt'
    if (-not (Test-Path -LiteralPath $imageFile)) { throw 'Prepare the image with Build-IsolatedWorker.ps1 first.' }
    $image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
    $case = Join-Path $root 'cases/stripe-customer-sources-2020-08-27'
    $arguments = @('start', $StateDirectory, $case, (Join-Path $case 'target/dependencies'), $image, $Model)
} else { $arguments = @('resume', $StateDirectory, $EvaluationId) }
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
    & java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.InvestigationEvaluation' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation did not finish. Resume its existing ID; inspect retained records before starting another suite.' }
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $previousKey, 'Process') }
