#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('policy','check','register','run','read')][string]$Action = 'check',
    [string]$InputBundleId,
    [string]$PlanPath,
    [string]$WorkloadId,
    [int]$Repetition = 1,
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
        if ($InputBundleId -or $PlanPath -or $WorkloadId -or $TrialId -or $Repetition -ne 1) { throw 'Policy accepts only StateDirectory.' }
        $arguments = @('policy',$StateDirectory)
    }
    'check' {
        if (-not $InputBundleId -or $PlanPath -or $WorkloadId -or $TrialId -or $Repetition -ne 1) { throw 'Check requires only InputBundleId.' }
        $arguments = @('check',$StateDirectory,$InputBundleId)
    }
    'register' {
        if (-not $InputBundleId -or -not $PlanPath -or -not $WorkloadId -or $TrialId) { throw 'Register requires InputBundleId, PlanPath and WorkloadId.' }
        $arguments = @('register',$StateDirectory,$InputBundleId,$PlanPath,$WorkloadId,[string]$Repetition)
    }
    default {
        if (-not $TrialId -or $InputBundleId -or $PlanPath -or $WorkloadId -or $Repetition -ne 1) { throw 'Run/read require only TrialId.' }
        $arguments = @($Action,$StateDirectory,$TrialId)
    }
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.CoordinationDiagnostics' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic trial or retained report failed validation.' }
