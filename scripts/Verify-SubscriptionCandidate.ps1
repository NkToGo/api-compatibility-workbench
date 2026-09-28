#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start', 'resume', 'cancel', 'revoke')][string]$Action = 'start',
    [string]$BaselineSource,
    [string]$CandidateSource,
    [string]$VerificationId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$classpath = (Join-Path $root 'backend/target/classes') + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
if ($Action -eq 'start') {
    if (-not $BaselineSource -or -not $CandidateSource) { throw 'BaselineSource and CandidateSource are required.' }
    $image = (Get-Content -LiteralPath (Join-Path $root 'backend/target/repair-worker-image.txt') -Raw).Trim()
    if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Build the repair worker first.' }
    $arguments = @('subscription', $StateDirectory, $BaselineSource, $CandidateSource,
        (Join-Path $root 'cases/stripe-subscription-periods-2025-07-30'), $image)
} else {
    if (-not $VerificationId) { throw 'VerificationId is required.' }
    $arguments = @($Action, $StateDirectory, $VerificationId)
}
& java '-cp' $classpath 'io.github.nktogo.apicompatibility.runner.RepairVerification' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Verification did not finalize. Preserve its ID and resume it to recover without replay.' }
