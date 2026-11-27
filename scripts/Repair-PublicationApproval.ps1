#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory, ParameterSetName = 'Approve')][switch]$Approve,
    [Parameter(Mandatory, ParameterSetName = 'Check')][switch]$Check,
    [Parameter(Mandatory, ParameterSetName = 'Revoke')][switch]$Revoke,
    [Parameter(Mandatory, ParameterSetName = 'Revoke')][string]$OperationId,
    [Parameter(Mandatory, ParameterSetName = 'Approve')]
    [Parameter(Mandatory, ParameterSetName = 'Check')][string]$VerificationId,
    [Parameter(Mandatory, ParameterSetName = 'Approve')]
    [Parameter(Mandatory, ParameterSetName = 'Check')][string]$Repository,
    [Parameter(Mandatory, ParameterSetName = 'Approve')]
    [Parameter(Mandatory, ParameterSetName = 'Check')][string]$Intent,
    [Parameter(Mandatory, ParameterSetName = 'Approve')]
    [Parameter(Mandatory, ParameterSetName = 'Check')][string]$SourcePath,
    [Parameter(Mandatory, ParameterSetName = 'Approve')][ValidatePattern('^[0-9a-f]{64}$')][string]$BindingIdentity,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'backend/target/classes'
$libraries = Join-Path $root 'backend/target/dependency/*'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'io/github/nktogo/apicompatibility/github/RepairPublicationApproval.class'))) {
    throw 'Build the backend before managing repair publication approvals.'
}
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$StateDirectory = Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $StateDirectory
$arguments = @($PSCmdlet.ParameterSetName.ToLowerInvariant(), [IO.Path]::GetFullPath($StateDirectory))
if ($Revoke) {
    $arguments += $OperationId
} else {
    $arguments += @($VerificationId, (Resolve-Path -LiteralPath $Repository).Path, (Resolve-Path -LiteralPath $Intent).Path, $SourcePath)
    if ($Approve) { $arguments += $BindingIdentity }
}
$classpath = $classes + [IO.Path]::PathSeparator + $libraries
& java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' `
    '-Djdk.httpclient.HttpClient.log=' '-cp' $classpath 'io.github.nktogo.apicompatibility.github.RepairPublicationApproval' @arguments
if ($LASTEXITCODE -eq 3) { Write-Warning 'The approval operation could not proceed. Review the result.' }
elseif ($LASTEXITCODE -ne 0) { throw 'Repair publication approval could not be processed.' }
