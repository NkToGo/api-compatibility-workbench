#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('freeze','read')][string]$Action = 'freeze',
    [ValidatePattern('^inputs-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$InputBundleId,
    [ValidatePattern('^campaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $StateDirectory) { $StateDirectory = Join-Path $taskRoot '.workbench' }
$StateDirectory = [IO.Path]::GetFullPath($StateDirectory)
$taskStore = Join-Path $StateDirectory 'coordination-campaigns'
$taskAdapters = [ordered]@{
    DETERMINISTIC = @{ entry='CoordinationDiagnostics'; file='deterministic.json' }
    SINGLE_AGENT = @{ entry='CoordinationSingleAgent'; file='single-agent.json' }
    INDEPENDENT_WORKERS = @{ entry='CoordinationIndependentWorkers'; file='independent-workers.json' }
    COORDINATED_TEAM = @{ entry='CoordinationTeamComparison'; file='coordinated-team.json' }
}
$taskArtifacts = @('plan.json','rubric.json','order.json','inputs-manifest.json','inputs-receipt.json') + @($taskAdapters.Values | ForEach-Object { 'policies/' + $_.file })
function Require([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Safe-Path([string]$Path) {
    for ($cursor=[IO.Path]::GetFullPath($Path); $cursor; $cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if (Test-Path -LiteralPath $cursor) { Require (-not ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked campaign paths are unsupported.' }
    }
}
function Bytes([string]$Path) {
    Safe-Path $Path
    $item=Get-Item -LiteralPath $Path
    Require (-not $item.PSIsContainer -and $item.Length -le 1048576) 'Missing or oversized campaign artifact.'
    return ,[IO.File]::ReadAllBytes($item.FullName)
}
function Read-Json([string]$Path) { return [Text.Encoding]::UTF8.GetString((Bytes $Path)) | ConvertFrom-Json -AsHashtable -Depth 60 }
function Hash([string]$Path) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((Bytes $Path))).ToLowerInvariant() }
function Shape($Value,[string[]]$Keys) {
    Require ($Value -is [Collections.IDictionary]) 'Expected an object.'
    $expected=[Collections.Generic.HashSet[string]]::new([string[]]$Keys,[StringComparer]::Ordinal)
    Require ($expected.SetEquals([string[]]@($Value.Keys))) 'Unexpected or missing campaign fields.'
}
function Publish-Bytes([string]$Path,[byte[]]$Content) {
    Safe-Path $Path
    Require ($Content.Length -le 1048576 -and -not (Test-Path -LiteralPath $Path)) 'Existing or oversized campaign publication.'
    $pending=$Path+'.pending'
    $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($Content); $stream.Flush($true) } finally { $stream.Dispose() }
    [IO.File]::Move($pending,$Path)
}
function Publish-Json([string]$Path,$Value) { Publish-Bytes $Path ([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Value -Depth 60)+"`n")) }
function Canonical($Value) {
    if ($Value -is [Collections.IDictionary]) {
        $result=[ordered]@{}; foreach ($key in ($Value.Keys | Sort-Object -CaseSensitive)) { $result[$key]=Canonical $Value[$key] }; return $result
    }
    if ($Value -is [Collections.IList]) { $result=[Collections.Generic.List[object]]::new(); foreach ($item in $Value) { $result.Add((Canonical $item)) }; return ,$result.ToArray() }
    return $Value
}
function Same($Left,$Right) { return (ConvertTo-Json -InputObject (Canonical $Left) -Depth 60 -Compress) -ceq (ConvertTo-Json -InputObject (Canonical $Right) -Depth 60 -Compress) }
function Inventory([string]$Directory) {
    $allowed=@($taskArtifacts)+@('frozen.json','registration.json','receipt.json','owner.lock')
    foreach ($entry in (Get-ChildItem -LiteralPath $Directory -Force)) {
        Safe-Path $entry.FullName
        $relative=[IO.Path]::GetRelativePath($Directory,$entry.FullName).Replace('\','/')
        if ($entry.PSIsContainer) {
            Require ($relative -ceq 'policies') 'Unexpected campaign directory.'
            foreach ($child in (Get-ChildItem -LiteralPath $entry.FullName -Force)) {
                Safe-Path $child.FullName
                $childRelative=[IO.Path]::GetRelativePath($Directory,$child.FullName).Replace('\','/')
                Require (-not $child.PSIsContainer -and $childRelative -cin $allowed) 'Unexpected campaign artifact.'
            }
        } else { Require ($relative -cin $allowed) 'Unexpected campaign artifact.' }
    }
}
function Workbench([string]$Entry,[string[]]$Arguments) {
    Require ($Entry -cin @('CoordinationInputs','CoordinationComparisonReport','CoordinationDiagnostics','CoordinationSingleAgent','CoordinationIndependentWorkers','CoordinationTeamComparison')) 'Unsupported metadata command.'
    $classpath=(Join-Path $taskRoot 'backend/target/classes')+[IO.Path]::PathSeparator+(Join-Path $taskRoot 'backend/target/dependency/*')
    $lines=@(& java '-cp' $classpath ('io.github.nktogo.apicompatibility.runner.'+$Entry) @Arguments 2>&1)
    Require ($LASTEXITCODE -eq 0) 'Workbench metadata validation failed; check the build and captured input bindings.'
    return ($lines -join "`n")
}
function Frozen([string]$Directory) {
    $frozen=Read-Json (Join-Path $Directory 'frozen.json')
    Shape $frozen @('formatVersion','campaignId','bundleId','frozenAt','scope','teamAllocation','artifacts')
    Require ($frozen.formatVersion -eq 1 -and $frozen.campaignId -ceq $CampaignId -and $frozen.bundleId -cmatch '^inputs-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid frozen campaign identity.'
    Shape $frozen.artifacts $taskArtifacts
    foreach ($name in $taskArtifacts) { Require ($frozen.artifacts[$name] -cmatch '^[0-9a-f]{64}$' -and (Hash (Join-Path $Directory $name)) -ceq $frozen.artifacts[$name]) 'Frozen campaign artifact changed.' }
    Require (Same $frozen.teamAllocation @{ nominal=@(24,6,24,6); cumulative=@(24,30,54,60); carryUnusedForward=$true }) 'Unexpected team allocation.'
    $plan=Read-Json (Join-Path $Directory 'plan.json'); Shape $plan @('formatVersion','definition')
    $definition=$plan.definition
    Shape $definition @('experimentId','origin','model','rubricIdentity','implementations','workloads','repetitions','limits')
    Require ($plan.formatVersion -eq 1 -and $definition.experimentId -ceq ('coordination-'+$CampaignId) -and $definition.origin -ceq 'SUPPLIED_RECORDS' -and $definition.model -ceq 'gpt-5.6-terra' -and $definition.repetitions -eq 3) 'Changed declared campaign conditions.'
    Require (Same $definition.limits @{ modelCalls=60; elapsedMillis=1200000; inputBytesPerCall=64000; outputTokensPerCall=2048 }) 'Changed common limits.'
    Shape $definition.implementations @($taskAdapters.Keys)
    Require ($definition.rubricIdentity.sha256 -ceq (Hash (Join-Path $Directory 'rubric.json'))) 'Changed rubric binding.'
    $rubric=Read-Json (Join-Path $Directory 'rubric.json')
    Require ($rubric.formatVersion -eq 1 -and $rubric.rubricId -ceq 'coordination-known-cases-v1' -and $rubric.cases.Count -eq 2) 'Unsupported rubric.'
    $manifest=Read-Json (Join-Path $Directory 'inputs-manifest.json'); $inputReceipt=Read-Json (Join-Path $Directory 'inputs-receipt.json')
    Require ($manifest.bundleId -ceq $frozen.bundleId -and $inputReceipt.manifestSha256 -ceq (Hash (Join-Path $Directory 'inputs-manifest.json'))) 'Changed captured input metadata.'
    Require ($definition.workloads.Count -eq 1 -and $definition.workloads[0].workloadId -ceq 'two-case' -and $definition.workloads[0].cases.Count -eq 2) 'Changed workload denominator.'
    for ($i=0;$i -lt 2;$i++) {
        $expected=$rubric.cases[$i]; $actual=$definition.workloads[0].cases[$i]
        Require ($actual.caseId -ceq $expected.caseId -and $actual.expectedResponse -ceq 'ANSWER' -and $actual.inputIdentity.sha256 -ceq $manifest.cases[$actual.caseId].inputIdentity.sha256 -and (Same $actual.criteria @($expected.criteria.id))) 'Changed task input or criteria.'
    }
    $adapters=@(); foreach ($arm in $taskAdapters.Keys) {
        $snapshot=Read-Json (Join-Path $Directory ('policies/'+$taskAdapters[$arm].file)); Shape $snapshot @('policy','implementationIdentity')
        Require ($snapshot.implementationIdentity.sha256 -cmatch '^[0-9a-f]{64}$' -and $snapshot.implementationIdentity.sha256 -ceq $definition.implementations[$arm].sha256) 'Changed arm implementation binding.'
        $adapters += $snapshot.policy.adapterIdentity.sha256
    }
    Require (@($adapters | Sort-Object -Unique).Count -eq 1) 'Mixed compiled implementations.'
    $order=Read-Json (Join-Path $Directory 'order.json'); Shape $order @('formatVersion','slots')
    Require ($order.formatVersion -eq 1 -and $order.slots.Count -eq 12) 'Changed planned trial denominator.'
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($i=0;$i -lt 12;$i++) {
        $slot=$order.slots[$i]; Shape $slot @('position','workloadId','repetition','arm')
        Require ($slot.position -eq ($i+1) -and $slot.workloadId -ceq 'two-case' -and $slot.repetition -eq (1+[int][Math]::Floor($i/4)) -and $slot.arm -cin @($taskAdapters.Keys) -and $seen.Add("$($slot.repetition):$($slot.arm)")) 'Changed execution order or duplicate planned slot.'
    }
    $null=Workbench 'CoordinationInputs' @('read',$StateDirectory,$frozen.bundleId)
    $current=Join-Path $StateDirectory ('coordination-inputs/'+$frozen.bundleId)
    Require ((Hash (Join-Path $current 'manifest.json')) -ceq (Hash (Join-Path $Directory 'inputs-manifest.json')) -and (Hash (Join-Path $current 'receipt.json')) -ceq (Hash (Join-Path $Directory 'inputs-receipt.json'))) 'Captured bundle differs from its frozen registration.'
    return $frozen
}
function Comparison([string]$Id,[string]$Directory) {
    Require ($Id -cmatch '^comparison-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid comparison reference.'
    $parent=Join-Path $StateDirectory ('coordination-comparisons/'+$Id)
    $registration=Read-Json (Join-Path $parent 'registration.json')
    Require ($registration.comparisonId -ceq $Id -and $registration.planSha256 -ceq (Hash (Join-Path $parent 'plan.json')) -and (Same (Read-Json (Join-Path $parent 'plan.json')) (Read-Json (Join-Path $Directory 'plan.json')))) 'Comparison registration differs from the frozen plan.'
    return @{ formatVersion=1; comparisonId=$Id; comparisonRegistrationSha256=(Hash (Join-Path $parent 'registration.json')); comparisonPlanSha256=(Hash (Join-Path $parent 'plan.json')) }
}
Require (-not ($CampaignId -and $InputBundleId)) 'Use an existing campaign ID or a new input bundle, not both.'
Require ($Action -ne 'read' -or [bool]$CampaignId) 'Read requires CampaignId.'
Require ($CampaignId -or $InputBundleId) 'Freeze requires InputBundleId or an existing CampaignId.'
Safe-Path $StateDirectory; Safe-Path $taskStore
[void][IO.Directory]::CreateDirectory($taskStore)
if (-not $CampaignId) {
    $null=Workbench 'CoordinationInputs' @('read',$StateDirectory,$InputBundleId)
    $rubricPath=Join-Path $taskRoot 'docs/evaluations/coordination-rubric-v1.json'
    $rubric=Read-Json $rubricPath
    $snapshots=[ordered]@{}; foreach ($arm in $taskAdapters.Keys) { $snapshots[$arm]=(Workbench $taskAdapters[$arm].entry @('policy',$StateDirectory)) | ConvertFrom-Json -AsHashtable -Depth 60 }
    Require (@($snapshots.Values.policy.adapterIdentity.sha256 | Sort-Object -Unique).Count -eq 1) 'Compiled code changed during policy capture.'
    $CampaignId='campaign-'+[guid]::NewGuid().ToString(); $directory=Join-Path $taskStore $CampaignId
    [void][IO.Directory]::CreateDirectory((Join-Path $directory 'policies'))
    $inputPath=Join-Path $StateDirectory ('coordination-inputs/'+$InputBundleId); $manifest=Read-Json (Join-Path $inputPath 'manifest.json')
    $implementations=[ordered]@{}; foreach ($arm in $taskAdapters.Keys) { $implementations[$arm]=$snapshots[$arm].implementationIdentity; Publish-Json (Join-Path $directory ('policies/'+$taskAdapters[$arm].file)) $snapshots[$arm] }
    $cases=@($rubric.cases | ForEach-Object { [ordered]@{ caseId=$_.caseId; inputIdentity=$manifest.cases[$_.caseId].inputIdentity; expectedResponse=$_.expectedResponse; criteria=@($_.criteria.id) } })
    $plan=[ordered]@{ formatVersion=1; definition=[ordered]@{ experimentId=('coordination-'+$CampaignId); origin='SUPPLIED_RECORDS'; model='gpt-5.6-terra'; rubricIdentity=@{sha256=(Hash $rubricPath)}; implementations=$implementations; workloads=@(@{workloadId='two-case';cases=$cases}); repetitions=3; limits=@{modelCalls=60;elapsedMillis=1200000;inputBytesPerCall=64000;outputTokensPerCall=2048} } }
    Publish-Json (Join-Path $directory 'plan.json') $plan
    Publish-Bytes (Join-Path $directory 'rubric.json') (Bytes $rubricPath)
    foreach ($name in @('manifest','receipt')) { Publish-Bytes (Join-Path $directory ('inputs-'+$name+'.json')) (Bytes (Join-Path $inputPath ($name+'.json'))) }
    $slots=@(); $arms=@($taskAdapters.Keys)
    for ($repetition=1;$repetition -le 3;$repetition++) { for ($offset=0;$offset -lt 4;$offset++) { $slots += [ordered]@{ position=$slots.Count+1; workloadId='two-case'; repetition=$repetition; arm=$arms[($offset+$repetition-1)%4] } } }
    Publish-Json (Join-Path $directory 'order.json') @{formatVersion=1;slots=$slots}
    $hashes=[ordered]@{}; foreach ($name in $taskArtifacts) { $hashes[$name]=Hash (Join-Path $directory $name) }
    Publish-Json (Join-Path $directory 'frozen.json') @{formatVersion=1;campaignId=$CampaignId;bundleId=$InputBundleId;frozenAt=[DateTimeOffset]::UtcNow.ToString('O');scope='Known synthetic cases; no held-out accuracy or general superiority.';teamAllocation=@{nominal=@(24,6,24,6);cumulative=@(24,30,54,60);carryUnusedForward=$true};artifacts=$hashes}
} else { $directory=Join-Path $taskStore $CampaignId }
Safe-Path $directory
Safe-Path (Join-Path $directory 'owner.lock')
$lease=[IO.File]::Open((Join-Path $directory 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    Inventory $directory
    $frozen=Frozen $directory
    if ($Action -eq 'freeze' -and -not (Test-Path -LiteralPath (Join-Path $directory 'registration.json'))) {
        $candidateIds=@(); $comparisonRoot=Join-Path $StateDirectory 'coordination-comparisons'; Safe-Path $comparisonRoot
        if (Test-Path -LiteralPath $comparisonRoot) { foreach ($candidate in (Get-ChildItem -LiteralPath $comparisonRoot -Directory -Force)) {
            $candidatePlan=Join-Path $candidate.FullName 'plan.json'
            if (Test-Path -LiteralPath $candidatePlan) { $value=Read-Json $candidatePlan; if ($value.definition.experimentId -ceq ('coordination-'+$CampaignId)) { $candidateIds += $candidate.Name } }
        } }
        Require ($candidateIds.Count -le 1) 'Ambiguous comparison registrations; do not choose a retry.'
        $id=if ($candidateIds.Count -eq 1) { $candidateIds[0] } else { (Workbench 'CoordinationComparisonReport' @('register',$StateDirectory,(Join-Path $directory 'plan.json'))).Trim() }
        Publish-Json (Join-Path $directory 'registration.json') (Comparison $id $directory)
    }
    $mapping=Read-Json (Join-Path $directory 'registration.json'); Shape $mapping @('formatVersion','comparisonId','comparisonRegistrationSha256','comparisonPlanSha256')
    Require ($mapping.formatVersion -eq 1 -and (Same $mapping (Comparison $mapping.comparisonId $directory))) 'Changed campaign registration mapping.'
    $expectedReceipt=@{ formatVersion=1; campaignId=$CampaignId; frozenSha256=(Hash (Join-Path $directory 'frozen.json')); registrationSha256=(Hash (Join-Path $directory 'registration.json')) }
    if ($Action -eq 'freeze' -and -not (Test-Path -LiteralPath (Join-Path $directory 'receipt.json'))) { Publish-Json (Join-Path $directory 'receipt.json') $expectedReceipt }
    Require (Same (Read-Json (Join-Path $directory 'receipt.json')) $expectedReceipt) 'Incomplete or changed campaign receipt.'
    Inventory $directory
    [ordered]@{ status='REGISTRATION_VALIDATED'; campaignId=$CampaignId; comparisonId=$mapping.comparisonId; plannedTrials=12; plannedCaseOutputs=24; model='gpt-5.6-terra'; paidCallsInitiatedByThisCommand=0; qualityReview='NOT_ESTABLISHED_BY_REGISTRATION'; receiptSha256=(Hash (Join-Path $directory 'receipt.json')); planPath=(Join-Path $directory 'plan.json'); orderPath=(Join-Path $directory 'order.json') } | ConvertTo-Json
} finally { $lease.Dispose() }
