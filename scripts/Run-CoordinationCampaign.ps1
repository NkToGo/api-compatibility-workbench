#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('run','read')][string]$Action = 'run',
    [Parameter(Mandatory)][ValidatePattern('^campaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [ValidateRange(1,12)][int]$MaximumTrials = 12,
    [string]$StateDirectory
)
$ErrorActionPreference='Stop'
$ceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $StateDirectory) { $StateDirectory=Join-Path $ceRoot '.workbench' }
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
$ceDriver=$PSCommandPath
$ceAdapters=[ordered]@{
    DETERMINISTIC=@{entry='CoordinationDiagnostics';store='coordination-diagnostics';script='Run-CoordinationDiagnostics.ps1';policy='deterministic.json'}
    SINGLE_AGENT=@{entry='CoordinationSingleAgent';store='coordination-single-agent';script='Run-CoordinationSingleAgent.ps1';policy='single-agent.json'}
    INDEPENDENT_WORKERS=@{entry='CoordinationIndependentWorkers';store='coordination-independent-workers';script='Run-CoordinationIndependentWorkers.ps1';policy='independent-workers.json'}
    COORDINATED_TEAM=@{entry='CoordinationTeamComparison';store='coordination-team-comparisons';script='Run-CoordinationTeamComparison.ps1';policy='coordinated-team.json'}
}
function CE-Require([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function CE-Safe([string]$Path) {
    for ($cursor=[IO.Path]::GetFullPath($Path);$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if (Test-Path -LiteralPath $cursor) { CE-Require (-not ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked campaign execution paths are unsupported.' }
    }
}
function CE-Bytes([string]$Path) {
    CE-Safe $Path; $item=Get-Item -LiteralPath $Path -Force
    CE-Require (-not $item.PSIsContainer -and $item.Length -le 8388608) 'Missing or oversized execution artifact.'
    return ,[IO.File]::ReadAllBytes($Path)
}
function CE-Hash([string]$Path) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((CE-Bytes $Path))).ToLowerInvariant() }
function CE-Json([string]$Path) { return [Text.Encoding]::UTF8.GetString((CE-Bytes $Path)) | ConvertFrom-Json -AsHashtable -Depth 80 }
function CE-Canonical($Value) {
    if ($Value -is [Collections.IDictionary]) { $r=[ordered]@{}; foreach ($k in ($Value.Keys | Sort-Object -CaseSensitive)) { $r[$k]=CE-Canonical $Value[$k] }; return $r }
    if ($Value -is [Collections.IList]) { $r=[Collections.Generic.List[object]]::new(); foreach ($v in $Value) { $r.Add((CE-Canonical $v)) }; return ,$r.ToArray() }
    return $Value
}
function CE-Same($Left,$Right) { return (ConvertTo-Json -InputObject (CE-Canonical $Left) -Depth 80 -Compress) -ceq (ConvertTo-Json -InputObject (CE-Canonical $Right) -Depth 80 -Compress) }
function CE-Shape($Value,[string[]]$Keys) {
    CE-Require ($Value -is [Collections.IDictionary] -and [Collections.Generic.HashSet[string]]::new([string[]]$Keys,[StringComparer]::Ordinal).SetEquals([string[]]@($Value.Keys))) 'Unexpected execution record fields.'
}
function CE-Publish([string]$Path,$Value) {
    CE-Safe $Path; CE-Require (-not (Test-Path -LiteralPath $Path)) 'Execution publication already exists.'
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Value -Depth 80)+"`n")
    CE-Require ($bytes.Length -le 8388608) 'Oversized execution publication.'
    $stream=[IO.File]::Open(($Path+'.pending'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($bytes); $stream.Flush($true) } finally { $stream.Dispose() }
    [IO.File]::Move(($Path+'.pending'),$Path)
}
function CE-Time($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset]$Value }
    return [DateTimeOffset]::Parse([string]$Value,[Globalization.CultureInfo]::InvariantCulture)
}
function CE-Adapter([string]$Arm,[string[]]$Arguments) {
    CE-Require ($Arm -cin @($ceAdapters.Keys)) 'Unsupported campaign arm.'
    if ($Arguments[0] -ceq 'run') {
        try { $lines=@(& (Join-Path $ceRoot ('scripts/'+$ceAdapters[$Arm].script)) -Action run -TrialId $Arguments[2] -StateDirectory $StateDirectory 2>&1) }
        catch { throw 'Trial dispatch stopped. Preserve the mapped trial and inspect it before resuming.' }
    } else {
        $classpath=(Join-Path $ceRoot 'backend/target/classes')+[IO.Path]::PathSeparator+(Join-Path $ceRoot 'backend/target/dependency/*')
        $lines=@(& java '-cp' $classpath ('io.github.nktogo.apicompatibility.runner.'+$ceAdapters[$Arm].entry) @Arguments 2>&1)
        CE-Require ($LASTEXITCODE -eq 0) 'Adapter metadata or retained evidence validation failed.'
    }
    return ($lines -join "`n")
}
function CE-Policies {
    foreach ($arm in $ceAdapters.Keys) {
        $actual=(CE-Adapter $arm @('policy',$StateDirectory)) | ConvertFrom-Json -AsHashtable -Depth 80
        CE-Require (CE-Same $actual (CE-Json (Join-Path $ceCampaign ('policies/'+$ceAdapters[$arm].policy)))) 'Compiled policy changed. Preserve this campaign; do not change its conditions.'
    }
    CE-Require ((CE-Hash $ceDriver) -ceq $ceRegistration.driverSha256) 'Campaign driver changed after execution registration.'
    foreach ($arm in $ceAdapters.Keys) { CE-Require ((CE-Hash (Join-Path $ceRoot ('scripts/'+$ceAdapters[$arm].script))) -ceq $ceRegistration.wrapperSha256[$arm]) 'Adapter wrapper changed after execution registration.' }
}
function CE-Slot($Slot) { return @{workloadId=$Slot.workloadId;repetition=$Slot.repetition;arm=$Slot.arm} }
function CE-Trials {
    $root=Join-Path $StateDirectory 'coordination-trials'; CE-Safe $root
    $result=@(); if (-not (Test-Path -LiteralPath $root)) { return $result }
    $entries=@(Get-ChildItem -LiteralPath $root -Force)
    CE-Require ($entries.Count -le 10000) 'Trial inventory exceeds inspection limit.'
    foreach ($entry in $entries) {
        CE-Safe $entry.FullName
        CE-Require ($entry.PSIsContainer -and $entry.Name -cmatch '^trial-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and (Test-Path -LiteralPath (Join-Path $entry.FullName 'spec.json'))) 'Incomplete or unexpected trial registration; reconcile it before admitting another trial.'
        $spec=CE-Json (Join-Path $entry.FullName 'spec.json')
        if ($spec.definition.experimentId -ceq $cePlan.definition.experimentId) {
            CE-Require (@($ceOrder.slots | Where-Object { CE-Same (CE-Slot $_) $spec.slot }).Count -eq 1) 'Unexpected campaign trial slot.'
            $result+=@{id=$entry.Name;spec=$spec}
        }
    }
    return $result
}
function CE-Candidates($Slot) { return @(CE-Trials | Where-Object { CE-Same $_.spec.slot (CE-Slot $Slot) }) }
function CE-Mapping($Slot,[string]$Id,[string]$IntentPath) {
    CE-Require ($Id -cmatch '^trial-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid mapped trial reference.'
    $runtime=Join-Path $StateDirectory ('coordination-trials/'+$Id)
    $adapter=Join-Path $StateDirectory ($ceAdapters[$Slot.arm].store+'/'+$Id)
    $spec=CE-Json (Join-Path $runtime 'spec.json'); $intent=CE-Json $IntentPath
    CE-Require ($spec.formatVersion -eq 1 -and $spec.runId -ceq $Id -and $spec.bundleId -ceq $ceFrozen.bundleId -and $spec.inputReceiptSha256 -ceq (CE-Hash (Join-Path $ceCampaign 'inputs-receipt.json')) -and (CE-Same $spec.definition $cePlan.definition) -and (CE-Same $spec.slot (CE-Slot $Slot)) -and (CE-Time $spec.registeredAt) -ge (CE-Time $intent.createdAt)) 'Trial registration differs from its fresh campaign slot.'
    CE-Require (CE-Same $spec.policy (CE-Json (Join-Path $ceCampaign ('policies/'+$ceAdapters[$Slot.arm].policy))).policy) 'Mapped trial policy differs from registration.'
    CE-Require (CE-Same (CE-Json (Join-Path $runtime 'grant.json')) @{permission='registered-coordination-trial';specSha256=(CE-Hash (Join-Path $runtime 'spec.json'))}) 'Changed mapped trial grant.'
    $adapterSpec=CE-Json (Join-Path $adapter 'spec.json')
    CE-Require ($adapterSpec.runId -ceq $Id -and $adapterSpec.runtimeSpecSha256 -ceq (CE-Hash (Join-Path $runtime 'spec.json'))) 'Incomplete or conflicting adapter registration.'
    CE-Require (CE-Same (CE-Json (Join-Path $adapter 'registration.json')) @{specSha256=(CE-Hash (Join-Path $adapter 'spec.json'))}) 'Changed adapter registration.'
    return @{formatVersion=1;position=$Slot.position;runId=$Id;intentSha256=(CE-Hash $IntentPath);runtimeSpecSha256=(CE-Hash (Join-Path $runtime 'spec.json'));adapterSpecSha256=(CE-Hash (Join-Path $adapter 'spec.json'));adapterRegistrationSha256=(CE-Hash (Join-Path $adapter 'registration.json'))}
}
function CE-Accounting($Trace,[string]$Runtime,$Policy) {
    $fields=@('reservedCalls','registeredCalls','completedResponses','notDispatchedSlots','unresolvedUsageSlots','inputTokens','outputTokens','toolCalls','caseExecutions')
    $rows=[ordered]@{}; foreach ($p in $Policy.participants) { $r=[ordered]@{}; foreach ($f in $fields) { $r[$f]=[long]0 }; $rows[$p.id]=$r }
    foreach ($path in $Trace.artifactSha256.Keys) {
        if ($path -cmatch '^calls/[0-9a-f]{64}\.json$') {
            $reservation=CE-Json (Join-Path $Runtime $path); CE-Require ($rows.Contains($reservation.participant)) 'Unknown accounted participant.'
            $row=$rows[$reservation.participant]; $row.reservedCalls++
            $mappingPath=$path.Substring(0,$path.Length-5)+'-mapping.json'
            if (-not $Trace.artifactSha256.ContainsKey($mappingPath)) { $row.unresolvedUsageSlots++; continue }
            $mapping=CE-Json (Join-Path $Runtime $mappingPath); $row.registeredCalls++
            CE-Require ($mapping.callId -cmatch '^model-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid accounted broker reference.'
            $resultPath='broker/'+$mapping.callId+'/result.json'
            if (-not $Trace.artifactSha256.ContainsKey($resultPath)) { $row.unresolvedUsageSlots++; continue }
            $response=CE-Json (Join-Path $Runtime $resultPath)
            if ($response.status -ceq 'COMPLETED') { $row.completedResponses++; $row.inputTokens+=[long]$response.inputTokens; $row.outputTokens+=[long]$response.outputTokens }
            elseif ($response.remoteOutcome -ceq 'NOT_DISPATCHED') { $row.notDispatchedSlots++ }
            else { $row.unresolvedUsageSlots++ }
        } elseif ($path -cmatch '^reads/[0-9a-f]{64}\.json$') {
            $read=CE-Json (Join-Path $Runtime $path); CE-Require ($rows.Contains($read.participant)) 'Unknown read participant.'; $rows[$read.participant].toolCalls++
        }
    }
    foreach ($field in $fields) {
        $sum=[long]0; foreach ($row in $rows.Values) { $sum+=$row[$field] }
        CE-Require ($sum -eq $Trace.usage[$field]) 'Participant accounting differs from inclusive retained trace.'
    }
    return $rows
}
function CE-Outcome($Slot,$Mapping) {
    $null=CE-Adapter $Slot.arm @('read',$StateDirectory,$Mapping.runId)
    $runtime=Join-Path $StateDirectory ('coordination-trials/'+$Mapping.runId)
    $adapter=Join-Path $StateDirectory ($ceAdapters[$Slot.arm].store+'/'+$Mapping.runId)
    $result=CE-Json (Join-Path $adapter 'result.json'); $trace=CE-Json (Join-Path $runtime 'result.json'); $trial=$result.trial
    CE-Require ($trial.runId -ceq $Mapping.runId -and (CE-Same $trial.slot (CE-Slot $Slot)) -and (CE-Same $trial.usage $trace.usage) -and $trial.traceIdentity.sha256 -ceq (CE-Hash (Join-Path $runtime 'result.json')) -and $trial.status -ceq $trace.status -and $trial.status -cin @('COMPLETED','FAILED','CANCELLED','UNKNOWN_REMOTE_OUTCOME') -and $trial.elapsedMillis -eq $trace.elapsedMillis) 'Changed terminal trial or trace binding.'
    CE-Require ($trial.implementationIdentity.sha256 -ceq $cePlan.definition.implementations[$Slot.arm].sha256 -and $trial.usage.reservedCalls -le $cePlan.definition.limits.modelCalls -and $trial.usage.caseExecutions -eq 0 -and $result.qualityReview -ceq 'NOT_PERFORMED') 'Changed measured policy, limits or review boundary.'
    CE-Require (($Slot.arm -ceq 'DETERMINISTIC' -and $null -eq $trial.model -and $trial.usage.reservedCalls -eq 0) -or ($Slot.arm -cne 'DETERMINISTIC' -and $trial.model -ceq $cePlan.definition.model)) 'Changed measured model.'
    CE-Require ($trial.cases.Count -eq 2) 'Changed case denominator.'
    for ($i=0;$i -lt 2;$i++) { $expected=$cePlan.definition.workloads[0].cases[$i]; $actual=$trial.cases[$i]; CE-Require ($actual.caseId -ceq $expected.caseId -and (CE-Same $actual.inputIdentity $expected.inputIdentity) -and $null -eq $actual.review) 'Changed case input or supplied quality review.' }
    $started=CE-Json (Join-Path $runtime 'started.json'); $intent=CE-Json (Join-Path $ceExecution ('slots/'+('{0:00}' -f $Slot.position)+'-intent.json'))
    CE-Require ((CE-Time $started.startedAt) -ge (CE-Time $intent.createdAt) -and (CE-Time $trace.finishedAt) -ge (CE-Time $started.startedAt) -and (CE-Time $started.deadline) -eq (CE-Time $started.startedAt).AddMilliseconds($cePlan.definition.limits.elapsedMillis)) 'Changed campaign timing or renewed deadline.'
    $participants=CE-Accounting $trace $runtime (CE-Json (Join-Path $runtime 'spec.json')).policy
    return @{formatVersion=1;position=$Slot.position;runId=$Mapping.runId;mappingSha256=(CE-Hash (Join-Path $ceExecution ('slots/'+('{0:00}' -f $Slot.position)+'-mapping.json')));adapterResultSha256=(CE-Hash (Join-Path $adapter 'result.json'));adapterReceiptSha256=(CE-Hash (Join-Path $adapter 'receipt.json'));traceReceiptSha256=(CE-Hash (Join-Path $runtime 'receipt.json'));startedAt=$started.startedAt;deadline=$started.deadline;finishedAt=$trace.finishedAt;trial=$trial;participants=$participants;resourceIssues=$trace.resourceIssues;qualityReview='NOT_PERFORMED'}
}
function CE-Inventory {
    foreach ($entry in (Get-ChildItem -LiteralPath $ceExecution -Force)) {
        CE-Safe $entry.FullName
        if ($entry.PSIsContainer) {
            CE-Require ($entry.Name -ceq 'slots') 'Unexpected campaign execution directory.'
            foreach ($child in (Get-ChildItem -LiteralPath $entry.FullName -Force)) { CE-Safe $child.FullName; CE-Require (-not $child.PSIsContainer -and $child.Name -cmatch '^(0[1-9]|1[0-2])-(intent|mapping|outcome|receipt)\.json$') 'Unexpected or incomplete slot publication.' }
        } else { CE-Require ($entry.Name -cin @('owner.lock','registration.json','registration-receipt.json','records.json','result.json','receipt.json')) 'Unexpected or incomplete execution publication.' }
    }
}
function Invoke-CampaignExecution {
    $script:ceCampaign=Join-Path $StateDirectory ('coordination-campaigns/'+$CampaignId)
    $script:ceExecution=Join-Path $StateDirectory ('coordination-campaign-executions/'+$CampaignId)
    CE-Safe $ceCampaign; CE-Safe $ceExecution
    $validated=(& (Join-Path $ceRoot 'scripts/Coordination-Campaign.ps1') -Action read -CampaignId $CampaignId -StateDirectory $StateDirectory | Out-String) | ConvertFrom-Json -AsHashtable
    $script:cePlan=CE-Json (Join-Path $ceCampaign 'plan.json'); $script:ceOrder=CE-Json (Join-Path $ceCampaign 'order.json'); $script:ceFrozen=CE-Json (Join-Path $ceCampaign 'frozen.json')
    CE-Safe (Join-Path $ceExecution 'slots')
    if ($Action -ceq 'run') { [void][IO.Directory]::CreateDirectory((Join-Path $ceExecution 'slots')) }
    CE-Safe (Join-Path $ceExecution 'owner.lock')
    $lease=[IO.File]::Open((Join-Path $ceExecution 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        CE-Inventory
        $registrationPath=Join-Path $ceExecution 'registration.json'
        if (-not (Test-Path -LiteralPath $registrationPath)) {
            CE-Require ($Action -ceq 'run' -and @(Get-ChildItem -LiteralPath (Join-Path $ceExecution 'slots') -Force).Count -eq 0) 'Missing execution registration.'
            $wrappers=[ordered]@{}; foreach ($arm in $ceAdapters.Keys) { $wrappers[$arm]=CE-Hash (Join-Path $ceRoot ('scripts/'+$ceAdapters[$arm].script)) }
            CE-Publish $registrationPath @{formatVersion=1;campaignId=$CampaignId;comparisonId=$validated.comparisonId;campaignReceiptSha256=$validated.receiptSha256;driverSha256=(CE-Hash $ceDriver);wrapperSha256=$wrappers;createdAt=[DateTimeOffset]::UtcNow.ToString('O');authority='trusted-host-execution-not-semantic-review'}
        }
        $script:ceRegistration=CE-Json $registrationPath
        CE-Shape $ceRegistration @('formatVersion','campaignId','comparisonId','campaignReceiptSha256','driverSha256','wrapperSha256','createdAt','authority')
        CE-Shape $ceRegistration.wrapperSha256 @($ceAdapters.Keys)
        foreach ($sha in $ceRegistration.wrapperSha256.Values) { CE-Require ($sha -cmatch '^[0-9a-f]{64}$') 'Invalid wrapper identity.' }
        CE-Require ($ceRegistration.formatVersion -eq 1 -and $ceRegistration.campaignId -ceq $CampaignId -and $ceRegistration.comparisonId -ceq $validated.comparisonId -and $ceRegistration.campaignReceiptSha256 -ceq $validated.receiptSha256 -and $ceRegistration.driverSha256 -cmatch '^[0-9a-f]{64}$' -and $ceRegistration.authority -ceq 'trusted-host-execution-not-semantic-review') 'Changed execution registration.'
        $registrationReceipt=@{registrationSha256=(CE-Hash $registrationPath)}
        $receiptPath=Join-Path $ceExecution 'registration-receipt.json'
        if ($Action -ceq 'run' -and -not (Test-Path -LiteralPath $receiptPath)) { CE-Publish $receiptPath $registrationReceipt }
        CE-Require (CE-Same (CE-Json $receiptPath) $registrationReceipt) 'Changed execution registration receipt.'
        $outcomes=@(); $incomplete=@(); $dispatched=0; $previousAt=CE-Time $ceRegistration.createdAt; $gap=$false
        foreach ($slot in $ceOrder.slots) {
            $prefix=Join-Path $ceExecution ('slots/'+('{0:00}' -f $slot.position))
            $intentPath=$prefix+'-intent.json'; $mappingPath=$prefix+'-mapping.json'; $outcomePath=$prefix+'-outcome.json'; $slotReceiptPath=$prefix+'-receipt.json'
            $any=(Test-Path -LiteralPath $intentPath) -or (Test-Path -LiteralPath $mappingPath) -or (Test-Path -LiteralPath $outcomePath) -or (Test-Path -LiteralPath $slotReceiptPath)
            CE-Require (-not ($gap -and $any)) 'A later slot was admitted before the previous slot was sealed.'
            if ($Action -ceq 'run' -and $dispatched -lt $MaximumTrials -and -not (Test-Path -LiteralPath $outcomePath)) {
                CE-Policies
                if (-not (Test-Path -LiteralPath $intentPath)) {
                    CE-Require (@(CE-Candidates $slot).Count -eq 0) 'An unmapped trial already occupies this fresh campaign slot.'
                    CE-Publish $intentPath @{formatVersion=1;position=$slot.position;slot=(CE-Slot $slot);registrationSha256=(CE-Hash $registrationPath);createdAt=[DateTimeOffset]::UtcNow.ToString('O')}
                }
            }
            if (-not $any -and -not (Test-Path -LiteralPath $intentPath)) { $gap=$true; continue }
            $intent=CE-Json $intentPath; CE-Shape $intent @('formatVersion','position','slot','registrationSha256','createdAt')
            CE-Require ($intent.formatVersion -eq 1 -and $intent.position -eq $slot.position -and (CE-Same $intent.slot (CE-Slot $slot)) -and $intent.registrationSha256 -ceq (CE-Hash $registrationPath) -and (CE-Time $intent.createdAt) -ge $previousAt) 'Changed slot intent or recorded order.'
            $candidates=@(CE-Candidates $slot); CE-Require ($candidates.Count -le 1) 'Ambiguous slot registration; do not select a retry.'
            if ($Action -ceq 'run' -and $dispatched -lt $MaximumTrials -and -not (Test-Path -LiteralPath $mappingPath)) {
                if ($candidates.Count -eq 0) { $id=(CE-Adapter $slot.arm @('register',$StateDirectory,$ceFrozen.bundleId,(Join-Path $ceCampaign 'plan.json'),$slot.workloadId,[string]$slot.repetition)).Trim() }
                else { $id=$candidates[0].id }
                CE-Require (-not (Test-Path -LiteralPath (Join-Path $StateDirectory ('coordination-trials/'+$id+'/started.json')))) 'Unmapped trial already started; preserve its uncertain lineage.'
                CE-Publish $mappingPath (CE-Mapping $slot $id $intentPath)
                $candidates=@(CE-Candidates $slot)
            }
            if (-not (Test-Path -LiteralPath $mappingPath)) { $incomplete+=@{position=$slot.position;state='INTENT_WITHOUT_MAPPING';resources='NOT_INCLUDED_IN_SEALED_TOTALS'}; $gap=$true; continue }
            $mapping=CE-Json $mappingPath
            CE-Require ($candidates.Count -eq 1 -and $candidates[0].id -ceq $mapping.runId -and (CE-Same $mapping (CE-Mapping $slot $mapping.runId $intentPath))) 'Changed or duplicate mapped trial.'
            if ($Action -ceq 'run' -and $dispatched -lt $MaximumTrials -and -not (Test-Path -LiteralPath $outcomePath)) {
                $adapterResult=Join-Path $StateDirectory ($ceAdapters[$slot.arm].store+'/'+$mapping.runId+'/receipt.json')
                if (-not (Test-Path -LiteralPath $adapterResult)) { $null=CE-Adapter $slot.arm @('run',$StateDirectory,$mapping.runId) }
                CE-Publish $outcomePath (CE-Outcome $slot $mapping)
                $dispatched++
                Write-Information ('Recorded slot '+$slot.position+'/12: '+$slot.arm+'; trial '+$mapping.runId) -InformationAction Continue
            }
            if (-not (Test-Path -LiteralPath $outcomePath)) { $incomplete+=@{position=$slot.position;runId=$mapping.runId;state='MAPPED_UNSEALED';resources='NOT_INCLUDED_IN_SEALED_TOTALS'}; $gap=$true; continue }
            $outcome=CE-Outcome $slot $mapping
            CE-Require (CE-Same (CE-Json $outcomePath) $outcome) 'Changed sealed slot outcome.'
            $slotReceipt=@{outcomeSha256=(CE-Hash $outcomePath)}
            if ($Action -ceq 'run' -and -not (Test-Path -LiteralPath $slotReceiptPath)) { CE-Publish $slotReceiptPath $slotReceipt }
            CE-Require (CE-Same (CE-Json $slotReceiptPath) $slotReceipt) 'Missing or changed slot receipt.'
            $previousAt=CE-Time $outcome.finishedAt; $outcomes+=$outcome
        }
        $records=@{formatVersion=1;trials=@($outcomes | ForEach-Object { $_.trial })}
        $usage=[ordered]@{}; foreach ($f in @('reservedCalls','registeredCalls','completedResponses','notDispatchedSlots','unresolvedUsageSlots','inputTokens','outputTokens','toolCalls','caseExecutions')) { $usage[$f]=[long]0; foreach ($o in $outcomes) { $usage[$f]+=[long]$o.trial.usage[$f] } }
        $summary=@{formatVersion=1;campaignId=$CampaignId;comparisonId=$validated.comparisonId;registrationReceiptSha256=(CE-Hash $receiptPath);plannedTrials=12;plannedCaseOutputs=24;recordedTrials=$outcomes.Count;unrecordedTrials=12-$outcomes.Count;status=$(if ($outcomes.Count -eq 12) {'FINISHED'} else {'PENDING'});usage=$usage;usageScope='sealed-terminal-trials-only';incompleteSlots=$incomplete;trialElapsedMillis=[long](@($outcomes | ForEach-Object { $_.trial.elapsedMillis }) | Measure-Object -Sum).Sum;slots=@($outcomes | ForEach-Object { @{position=$_.position;runId=$_.runId;status=$_.trial.status;outcomeSha256=(CE-Hash (Join-Path $ceExecution ('slots/'+('{0:00}' -f $_.position)+'-outcome.json')));receiptSha256=(CE-Hash (Join-Path $ceExecution ('slots/'+('{0:00}' -f $_.position)+'-receipt.json')))} });qualityReview='NOT_PERFORMED';authority='known-case-operational-measurement-not-quality-or-routing'}
        if ($Action -ceq 'run' -and $outcomes.Count -eq 12) {
            foreach ($pair in @(@{file='records.json';value=$records},@{file='result.json';value=$summary})) {
                $path=Join-Path $ceExecution $pair.file
                if (Test-Path -LiteralPath $path) { CE-Require (CE-Same (CE-Json $path) $pair.value) 'Changed incomplete campaign result.' } else { CE-Publish $path $pair.value }
            }
            $terminalReceipt=@{registrationReceiptSha256=(CE-Hash $receiptPath);recordsSha256=(CE-Hash (Join-Path $ceExecution 'records.json'));resultSha256=(CE-Hash (Join-Path $ceExecution 'result.json'))}
            $path=Join-Path $ceExecution 'receipt.json'
            if (-not (Test-Path -LiteralPath $path)) { CE-Publish $path $terminalReceipt }
        }
        CE-Require ($Action -ceq 'run' -or $outcomes.Count -ne 12 -or (Test-Path -LiteralPath (Join-Path $ceExecution 'receipt.json'))) 'Terminal campaign seal is incomplete; resume the same campaign to reconcile metadata.'
        if (Test-Path -LiteralPath (Join-Path $ceExecution 'receipt.json')) {
            CE-Require ($outcomes.Count -eq 12 -and (CE-Same (CE-Json (Join-Path $ceExecution 'records.json')) $records) -and (CE-Same (CE-Json (Join-Path $ceExecution 'result.json')) $summary) -and (CE-Same (CE-Json (Join-Path $ceExecution 'receipt.json')) @{registrationReceiptSha256=(CE-Hash $receiptPath);recordsSha256=(CE-Hash (Join-Path $ceExecution 'records.json'));resultSha256=(CE-Hash (Join-Path $ceExecution 'result.json'))})) 'Changed terminal campaign receipt.'
        }
        CE-Inventory
        $summary | ConvertTo-Json -Depth 30
    } finally { $lease.Dispose() }
}
# Dot sourcing exposes the controller functions to isolated, credential-free tests.
if ($MyInvocation.InvocationName -ne '.') { Invoke-CampaignExecution }
