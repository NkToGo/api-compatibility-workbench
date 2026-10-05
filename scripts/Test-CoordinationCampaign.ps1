#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskTestRoot=Join-Path $taskRoot ('backend/target/coordination-campaign-tests/'+[guid]::NewGuid().ToString('N'))
$taskState=Join-Path $taskTestRoot 'state'
$taskCommand=Join-Path $PSScriptRoot 'Coordination-Campaign.ps1'
[void][IO.Directory]::CreateDirectory($taskState)
function Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Write-Json([string]$Path,$Value) { [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 60).Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false)) }
function Fixture {
    $bundle='inputs-'+[guid]::NewGuid().ToString(); $directory=Join-Path $taskState ('coordination-inputs/'+$bundle)
    [void][IO.Directory]::CreateDirectory($directory)
    $cases=[ordered]@{}
    foreach ($case in @(
        @{id='stripe-customer-sources-2020-08-27'; aliases=@('baseline-source','case-index','description','execution','expectations','fixtures','provenance','reference-source')},
        @{id='stripe-subscription-periods-2025-07-30'; aliases=@('baseline-source','description','execution','expectations','fixtures','provenance')}
    )) {
        $path=Join-Path $directory $case.id; [void][IO.Directory]::CreateDirectory($path); $hashes=[ordered]@{}
        foreach ($alias in ($case.aliases | Sort-Object -CaseSensitive)) {
            [IO.File]::WriteAllText((Join-Path $path $alias),'Synthetic metadata fixture; no measured model findings.',[Text.UTF8Encoding]::new($false))
            $hashes[$alias]=Hash (Join-Path $path $alias)
        }
        # The fixture uses the documented pretty JSON input identity encoding, then
        # exercises the Java validator independently through the real command.
        $identityJson=(ConvertTo-Json -InputObject ([ordered]@{artifacts=$hashes;caseId=$case.id;formatVersion=1}) -Depth 20).Replace("`r`n","`n")
        $identity=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($identityJson))).ToLowerInvariant()
        $cases[$case.id]=[ordered]@{artifacts=$hashes;inputIdentity=@{sha256=$identity}}
    }
    Write-Json (Join-Path $directory 'manifest.json') @{formatVersion=1;bundleId=$bundle;cases=$cases;authority='synthetic-registration-test-not-measured-evidence'}
    Write-Json (Join-Path $directory 'receipt.json') @{manifestSha256=(Hash (Join-Path $directory 'manifest.json'))}
    return $bundle
}
function Inventory([string]$Path) {
    $result=[ordered]@{}; foreach ($file in (Get-ChildItem -LiteralPath $Path -Recurse -File | Sort-Object FullName)) {
        if ($file.Name -ne 'owner.lock') { $result[[IO.Path]::GetRelativePath($Path,$file.FullName)]=Hash $file.FullName }
    }; return ConvertTo-Json -InputObject $result -Depth 10 -Compress
}
$taskPassed=0
$taskBundle=Fixture
$taskRegistration=(& $taskCommand -InputBundleId $taskBundle -StateDirectory $taskState | Out-String) | ConvertFrom-Json -AsHashtable
if ($taskRegistration.plannedTrials -ne 12 -or $taskRegistration.plannedCaseOutputs -ne 24 -or $taskRegistration.paidCallsInitiatedByThisCommand -ne 0) { throw 'Registration denominator or no-dispatch boundary failed.' }
$taskCampaign=Join-Path $taskState ('coordination-campaigns/'+$taskRegistration.campaignId)
$taskComparison=Join-Path $taskState ('coordination-comparisons/'+$taskRegistration.comparisonId)
$taskBefore=Inventory $taskState
$null=& $taskCommand -Action read -CampaignId $taskRegistration.campaignId -StateDirectory $taskState
$again=(& $taskCommand -CampaignId $taskRegistration.campaignId -StateDirectory $taskState | Out-String) | ConvertFrom-Json -AsHashtable
if ($again.receiptSha256 -cne $taskRegistration.receiptSha256 -or (Inventory $taskState) -cne $taskBefore) { throw 'Sealed inspection or repeat freeze changed evidence.' }
if (Test-Path -LiteralPath (Join-Path $taskState 'coordination-trials')) { throw 'Registration created trial permissions or dispatched work.' }
$taskPassed++; Write-Output 'PASS: real Java registration, complete denominator and immutable no-dispatch replay'
$taskFiles=[ordered]@{}
foreach ($file in (Get-ChildItem -LiteralPath $taskCampaign -Recurse -File)) { if ($file.Name -ne 'owner.lock') { $taskFiles[$file.FullName]=[IO.File]::ReadAllBytes($file.FullName) } }
$taskParentPlan=[IO.File]::ReadAllBytes((Join-Path $taskComparison 'plan.json'))
function Reset {
    foreach ($entry in $taskFiles.GetEnumerator()) { [IO.File]::WriteAllBytes($entry.Key,$entry.Value) }
    [IO.File]::WriteAllBytes((Join-Path $taskComparison 'plan.json'),$taskParentPlan)
}
function Reseal {
    $path=Join-Path $taskCampaign 'frozen.json'; $frozen=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    foreach ($name in @($frozen.artifacts.Keys)) { $frozen.artifacts[$name]=Hash (Join-Path $taskCampaign $name) }; Write-Json $path $frozen
    $receipt=Get-Content -LiteralPath (Join-Path $taskCampaign 'receipt.json') -Raw | ConvertFrom-Json -AsHashtable
    $receipt.frozenSha256=Hash $path; Write-Json (Join-Path $taskCampaign 'receipt.json') $receipt
}
function Reject([string]$Name,[scriptblock]$Change) {
    Reset; & $Change; $rejected=$false
    try { $null=& $taskCommand -Action read -CampaignId $taskRegistration.campaignId -StateDirectory $taskState } catch { $rejected=$true }
    if (-not $rejected) { throw ('Missed negative control: '+$Name) }; $script:taskPassed++; Write-Output ('PASS: '+$Name)
}
Reject 'changed rubric bytes' { [IO.File]::AppendAllText((Join-Path $taskCampaign 'rubric.json'),' ') }
Reject 'changed compiled policy snapshot' { [IO.File]::AppendAllText((Join-Path $taskCampaign 'policies/single-agent.json'),' ') }
Reject 'changed case input identity' {
    $path=Join-Path $taskCampaign 'plan.json'; $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    $value.definition.workloads[0].cases[0].inputIdentity.sha256='0'*64; Write-Json $path $value; Reseal
}
Reject 'duplicate paired slot despite resealed file hashes' {
    $path=Join-Path $taskCampaign 'order.json'; $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    $value.slots[1].arm=$value.slots[0].arm; Write-Json $path $value; Reseal
}
Reject 'changed model despite resealed file hashes' {
    $path=Join-Path $taskCampaign 'plan.json'; $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    $value.definition.model='gpt-other'; Write-Json $path $value; Reseal
}
Reject 'lower single-run budget despite resealed file hashes' {
    $path=Join-Path $taskCampaign 'plan.json'; $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    $value.definition.limits.modelCalls=12; Write-Json $path $value; Reseal
}
Reject 'missing paired slot' {
    $path=Join-Path $taskCampaign 'order.json'; $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    $value.slots=@($value.slots[0..10]); Write-Json $path $value; Reseal
}
Reject 'unexpected escaped snapshot reference' {
    $path=Join-Path $taskCampaign 'frozen.json'; $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    $value.artifacts['../../outside.json']='0'*64; Write-Json $path $value
}
Reject 'changed registered comparison plan' { [IO.File]::AppendAllText((Join-Path $taskComparison 'plan.json'),' ') }
Reject 'missing terminal registration receipt' { Remove-Item -LiteralPath (Join-Path $taskCampaign 'receipt.json') }
Reject 'missing frozen snapshot manifest' { Remove-Item -LiteralPath (Join-Path $taskCampaign 'frozen.json') }
Reject 'unexpected campaign artifact' { [IO.File]::WriteAllText((Join-Path $taskCampaign 'unexpected.json'),'{}') }
Remove-Item -LiteralPath (Join-Path $taskCampaign 'unexpected.json'); Reset
Reject 'unexpected hidden campaign artifact' {
    $path=Join-Path $taskCampaign '.unexpected.json'; [IO.File]::WriteAllText($path,'{}')
    if ($IsWindows) { [IO.File]::SetAttributes($path,([IO.File]::GetAttributes($path) -bor [IO.FileAttributes]::Hidden)) }
}
Remove-Item -LiteralPath (Join-Path $taskCampaign '.unexpected.json') -Force; Reset
Reset
$taskInputPath=Join-Path $taskState ('coordination-inputs/'+$taskBundle+'/stripe-customer-sources-2020-08-27/baseline-source')
$taskInputBytes=[IO.File]::ReadAllBytes($taskInputPath); [IO.File]::AppendAllText($taskInputPath,' ')
try {
    $rejected=$false; try { $null=& $taskCommand -Action read -CampaignId $taskRegistration.campaignId -StateDirectory $taskState } catch { $rejected=$true }
    if (-not $rejected) { throw 'Changed captured input was accepted.' }
    $taskPassed++; Write-Output 'PASS: changed captured input content'
} finally { [IO.File]::WriteAllBytes($taskInputPath,$taskInputBytes) }
Reset
foreach ($name in @('registration.json','receipt.json')) { Remove-Item -LiteralPath (Join-Path $taskCampaign $name) }
$taskParentBefore=Inventory $taskComparison
$recovered=(& $taskCommand -CampaignId $taskRegistration.campaignId -StateDirectory $taskState | Out-String) | ConvertFrom-Json -AsHashtable
if ($recovered.comparisonId -cne $taskRegistration.comparisonId -or (Inventory $taskComparison) -cne $taskParentBefore) { throw 'Completed orphan comparison was duplicated or changed.' }
$taskPassed++; Write-Output 'PASS: interrupted mapping reuses the one existing comparison'
Reset
Remove-Item -LiteralPath (Join-Path $taskCampaign 'receipt.json')
$taskParentBefore=Inventory $taskComparison
$recovered=(& $taskCommand -CampaignId $taskRegistration.campaignId -StateDirectory $taskState | Out-String) | ConvertFrom-Json -AsHashtable
if ($recovered.comparisonId -cne $taskRegistration.comparisonId -or (Inventory $taskComparison) -cne $taskParentBefore) { throw 'Receipt recovery duplicated its comparison.' }
$taskPassed++; Write-Output 'PASS: missing receipt recovery keeps its registered comparison'
Reset
$duplicate=Join-Path $taskState ('coordination-comparisons/comparison-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($duplicate)
[IO.File]::WriteAllBytes((Join-Path $duplicate 'plan.json'),$taskParentPlan)
foreach ($name in @('registration.json','receipt.json')) { Remove-Item -LiteralPath (Join-Path $taskCampaign $name) }
$rejected=$false; try { $null=& $taskCommand -CampaignId $taskRegistration.campaignId -StateDirectory $taskState } catch { $rejected=$true }
if (-not $rejected) { throw 'Ambiguous comparison recovery selected a retry.' }
$taskPassed++; Write-Output 'PASS: ambiguous comparison recovery does not choose a retry'
Reset
$link=Join-Path $taskTestRoot 'linked-state'; $linkAvailable=$false
try { $null=New-Item -ItemType SymbolicLink -Path $link -Target $taskState; $linkAvailable=$true } catch { }
if (-not $linkAvailable -and $IsWindows) {
    $link=Join-Path $taskTestRoot 'junction-state'
    try { $null=New-Item -ItemType Junction -Path $link -Target $taskState; $linkAvailable=$true } catch { }
}
if ($linkAvailable) {
    $rejected=$false; try { $null=& $taskCommand -Action read -CampaignId $taskRegistration.campaignId -StateDirectory $link } catch { $rejected=$true }
    if (-not $rejected) { throw 'Linked state path accepted.' }
    $taskPassed++; Write-Output 'PASS: linked state is rejected'
} else { Write-Output 'SKIP: linked-state control unavailable on this host' }
Write-Output ('Campaign registration checks passed: '+$taskPassed+'. No OpenAI/provider calls or consumer executions.')
