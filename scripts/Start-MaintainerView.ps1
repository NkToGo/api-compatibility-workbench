#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Report,
    [ValidateRange(0,65535)][int]$Port = 8765
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path (Join-Path $classes 'io/github/nktogo/apicompatibility/maintainer/MaintainerView.class'))) {
    throw 'Build the backend with Maven clean verify before starting the view.'
}
$selected = (Resolve-Path -LiteralPath $Report).Path
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.maintainer.MaintainerView' $selected $Port
if ($LASTEXITCODE -ne 0) { throw 'Local maintainer view failed to start.' }
