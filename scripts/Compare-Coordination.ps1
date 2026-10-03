#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('example','register','assess','read')][string]$Action = 'example',
    [string]$PlanPath,
    [string]$RecordsPath,
    [string]$ComparisonId,
    [string]$ReportId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$target = Join-Path $root 'backend/target'
$classpath = (Join-Path $target 'classes') + [IO.Path]::PathSeparator + (Join-Path $target 'dependency/*')
switch ($Action) {
    'example' {
        if ($PlanPath -or $RecordsPath -or $ComparisonId -or $ReportId) { throw 'Example accepts only StateDirectory.' }
        $arguments = @('example',$StateDirectory)
    }
    'register' {
        if (-not $PlanPath -or $RecordsPath -or $ComparisonId -or $ReportId) { throw 'Registration requires only PlanPath.' }
        $arguments = @('register',$StateDirectory,$PlanPath)
    }
    'assess' {
        if (-not $RecordsPath -or -not $ComparisonId -or $PlanPath -or $ReportId) { throw 'Assessment requires ComparisonId and RecordsPath.' }
        $arguments = @('assess',$StateDirectory,$ComparisonId,$RecordsPath)
    }
    'read' {
        if (-not $ComparisonId -or -not $ReportId -or $PlanPath -or $RecordsPath) { throw 'Read requires ComparisonId and ReportId.' }
        $arguments = @('read',$StateDirectory,$ComparisonId,$ReportId)
    }
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.CoordinationComparisonReport' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Comparison input or retained report failed validation.' }
