#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start', 'resume', 'cancel', 'revoke')][string]$Action = 'start',
    [string]$CandidateId,
    [string]$VerificationId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$classpath = (Join-Path $root 'backend/target/classes') + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
$arguments = @($Action, $StateDirectory)
if ($Action -eq 'start') {
    if (-not $CandidateId) { throw 'CandidateId is required.' }
    $image = (Get-Content -LiteralPath (Join-Path $root 'backend/target/repair-worker-image.txt') -Raw).Trim()
    if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Build the repair worker first.' }
    $caseRoot = Join-Path $root 'cases/stripe-customer-sources-2020-08-27'
    $arguments += @($CandidateId, $caseRoot, (Join-Path $caseRoot 'target/dependencies'), $image)
} else {
    if (-not $VerificationId) { throw 'VerificationId is required.' }
    $arguments += $VerificationId
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.RepairVerification' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Verification did not finalize. Preserve its ID and resume it to recover without replay.' }
