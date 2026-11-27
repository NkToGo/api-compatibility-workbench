#requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'WorkbenchInstallationState.ps1')
$variable = 'WORKBENCH_INSTALLATION_STATE_DIRECTORY'
$original = [Environment]::GetEnvironmentVariable($variable)
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('acw-installation-' + [Guid]::NewGuid().ToString('N'))
$temporary = [IO.Path]::GetFullPath($temporary)
$alpha = Join-Path $temporary 'alpha'
$beta = Join-Path $temporary 'beta'
$linked = Join-Path $temporary 'linked'

function Assert-Equal($Expected, $Actual) {
    if ($Actual -cne $Expected) { throw "Expected $Expected, received $Actual." }
}

function Assert-Restriction([scriptblock]$Action, [string]$Message) {
    try { & $Action | Out-Null }
    catch {
        if ($_.Exception.Message -like "$Message*") { return }
        throw
    }
    throw 'An invalid installation state selection was accepted.'
}

try {
    [void][IO.Directory]::CreateDirectory($alpha)
    [void][IO.Directory]::CreateDirectory($beta)

    [Environment]::SetEnvironmentVariable($variable, [NullString]::Value)
    Assert-Equal (Join-Path $root '.workbench') (Resolve-WorkbenchInstallationState -WorkbenchRoot $root)
    Assert-Equal './existing-state' (Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory './existing-state')

    [Environment]::SetEnvironmentVariable($variable, $alpha)
    Assert-Equal $alpha (Resolve-WorkbenchInstallationState -WorkbenchRoot $root)
    Assert-Equal $alpha (Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory ($alpha + [IO.Path]::DirectorySeparatorChar))
    Assert-Restriction { Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory $beta } 'StateDirectory conflicts'
    Assert-Restriction { Resolve-WorkbenchInstallationState -WorkbenchRoot $root -RequestedDirectory 'alpha' } 'StateDirectory conflicts'

    $file = Join-Path $temporary 'not-a-directory'
    [IO.File]::WriteAllText($file, '')
    $linkType = if ($IsWindows) { 'Junction' } else { 'SymbolicLink' }
    New-Item -ItemType $linkType -Path $linked -Target $alpha | Out-Null
    [void][IO.Directory]::CreateDirectory((Join-Path $alpha 'nested'))
    $invalid = @('relative-state', ' ', (Join-Path $temporary 'missing'), $file, $root,
        $PSScriptRoot, (Split-Path -Parent $root), [IO.Path]::GetPathRoot($alpha), $linked, (Join-Path $linked 'nested'))
    if ($IsWindows) { $invalid += '\\example.invalid\not-a-local-volume' }
    foreach ($directory in $invalid) {
        [Environment]::SetEnvironmentVariable($variable, $directory)
        Assert-Restriction { Resolve-WorkbenchInstallationState -WorkbenchRoot $root } 'WORKBENCH_INSTALLATION_STATE_DIRECTORY must name'
    }
    [Environment]::SetEnvironmentVariable($variable, $beta)
    Assert-Restriction { Resolve-WorkbenchInstallationState -WorkbenchRoot $linked } 'WORKBENCH_INSTALLATION_STATE_DIRECTORY must name'

    $id = [Guid]::NewGuid().ToString()
    $alphaOperation = Join-Path $alpha "repair-publications/$id"
    $betaOperation = Join-Path $beta "repair-publications/$id"
    foreach ($operation in @($alphaOperation, $betaOperation)) {
        [void][IO.Directory]::CreateDirectory($operation)
        [IO.File]::WriteAllText((Join-Path $operation 'approval.json'), '{}')
    }
    $approvals = Join-Path $PSScriptRoot 'Repair-PublicationApproval.ps1'
    $alphaMarker = Join-Path $alphaOperation 'approval.revoked'
    $betaMarker = Join-Path $betaOperation 'approval.revoked'

    [Environment]::SetEnvironmentVariable($variable, $alpha)
    Assert-Restriction { & $approvals -Revoke -OperationId $id -StateDirectory $beta } 'StateDirectory conflicts'
    Assert-Equal $false ([IO.File]::Exists($alphaMarker))
    Assert-Equal $false ([IO.File]::Exists($betaMarker))
    $revoked = & $approvals -Revoke -OperationId $id | Out-String | ConvertFrom-Json
    Assert-Equal 'REVOKED' $revoked.state
    Assert-Equal $true ([IO.File]::Exists($alphaMarker))
    Assert-Equal $false ([IO.File]::Exists($betaMarker))

    [Environment]::SetEnvironmentVariable($variable, $beta)
    $revoked = & $approvals -Revoke -OperationId $id -StateDirectory $beta | Out-String | ConvertFrom-Json
    Assert-Equal 'REVOKED' $revoked.state
    Assert-Equal $true ([IO.File]::Exists($betaMarker))
    Write-Output 'Installation state selection, invalid paths and separate approval journals verified.'
} finally {
    if ($null -eq $original) { [Environment]::SetEnvironmentVariable($variable, [NullString]::Value) }
    else { [Environment]::SetEnvironmentVariable($variable, $original) }
    if (Test-Path -LiteralPath $temporary) {
        if ((Resolve-Path -LiteralPath $temporary).Path -cne $temporary -or
            (Get-Item -LiteralPath $temporary).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Unexpected test directory identity.'
        }
        if (Test-Path -LiteralPath $linked) { [IO.Directory]::Delete($linked) }
        Remove-Item -LiteralPath $temporary -Recurse -Force
    }
}
