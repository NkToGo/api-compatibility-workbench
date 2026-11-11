#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('run','read')][string]$Action = 'run',
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [ValidateRange(1,12)][int]$MaximumTrials = 1,
    [string]$EvidenceDirectory,
    [string]$StateDirectory,
    [string]$RuntimeDirectory
)
$ErrorActionPreference='Stop'
$ieRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EvidenceDirectory) { $EvidenceDirectory=$ieRoot }
$EvidenceDirectory=[IO.Path]::GetFullPath($EvidenceDirectory)
if (-not $StateDirectory) { $StateDirectory=Join-Path $ieRoot '.workbench' }
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
if (-not $RuntimeDirectory) { $RuntimeDirectory=Join-Path $ieRoot 'backend/target' }
$RuntimeDirectory=[IO.Path]::GetFullPath($RuntimeDirectory)
$ieDriver=$PSCommandPath
$ieAdapters=[ordered]@{
    DETERMINISTIC=@{entry='InvoiceDiagnosticsV2';store='invoice-diagnostics-v2';runtime='invoice-navigation-trials'}
    SINGLE_AGENT=@{entry='InvoiceSingleAgentV2';store='invoice-single-agent-v2';runtime='invoice-v2-trials'}
    INDEPENDENT_WORKERS=@{entry='InvoiceIndependentWorkersV2';store='invoice-independent-workers-v2';runtime='invoice-v2-trials'}
    COORDINATED_TEAM=@{entry='InvoiceTeamComparisonV2';store='invoice-team-comparisons-v2';runtime='invoice-team-reconciliation-v2-trials'}
}
function IE-Require([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function IE-Safe([string]$Path) {
    for ($cursor=[IO.Path]::GetFullPath($Path);$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if (Test-Path -LiteralPath $cursor) { IE-Require (-not ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked campaign execution paths are unsupported.' }
    }
}
function IE-Bytes([string]$Path) {
    IE-Safe $Path; $item=Get-Item -LiteralPath $Path -Force
    IE-Require (-not $item.PSIsContainer -and $item.Length -le 8388608) 'Missing or oversized execution artifact.'
    return ,[IO.File]::ReadAllBytes($Path)
}
function IE-Hash([string]$Path) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((IE-Bytes $Path))).ToLowerInvariant() }
function IE-Element($Element) {
    if ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) { IE-Require ($names.Count -lt 5000 -and $names.Add($property.Name)) 'Duplicate or excessive execution JSON fields.'; IE-Element $property.Value }
    } elseif ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        IE-Require ($Element.GetArrayLength() -le 5000) 'Excessive execution JSON array.'
        foreach ($item in $Element.EnumerateArray()) { IE-Element $item }
    }
}
function IE-Json([string]$Path) {
    $text=[Text.UTF8Encoding]::new($false,$true).GetString((IE-Bytes $Path))
    $document=[Text.Json.JsonDocument]::Parse([string]$text)
    try { IE-Element $document.RootElement } finally { $document.Dispose() }
    return $text | ConvertFrom-Json -AsHashtable -Depth 80
}
function IE-Canonical($Value) {
    if ($Value -is [Collections.IDictionary]) { $r=[ordered]@{}; foreach ($k in ($Value.Keys | Sort-Object -CaseSensitive)) { $r[$k]=IE-Canonical $Value[$k] }; return $r }
    if ($Value -is [Collections.IList]) { $r=[Collections.Generic.List[object]]::new(); foreach ($v in $Value) { $r.Add((IE-Canonical $v)) }; return ,$r.ToArray() }
    return $Value
}
function IE-Same($Left,$Right) { return (ConvertTo-Json -InputObject (IE-Canonical $Left) -Depth 80 -Compress) -ceq (ConvertTo-Json -InputObject (IE-Canonical $Right) -Depth 80 -Compress) }
function IE-Shape($Value,[string[]]$Keys) {
    IE-Require ($Value -is [Collections.IDictionary] -and [Collections.Generic.HashSet[string]]::new([string[]]$Keys,[StringComparer]::Ordinal).SetEquals([string[]]@($Value.Keys))) 'Unexpected execution record fields.'
}
function IE-Publish([string]$Path,$Value) {
    IE-Safe $Path; IE-Require (-not (Test-Path -LiteralPath $Path)) 'Execution publication already exists.'
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Value -Depth 80)+"`n")
    IE-Require ($bytes.Length -le 8388608) 'Oversized execution publication.'
    $stream=[IO.File]::Open(($Path+'.pending'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($bytes); $stream.Flush($true) } finally { $stream.Dispose() }
    [IO.File]::Move(($Path+'.pending'),$Path)
}
function IE-Time($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset]$Value }
    return [DateTimeOffset]::Parse([string]$Value,[Globalization.CultureInfo]::InvariantCulture)
}
function IE-Runtime {
    IE-Safe $RuntimeDirectory
    $jar='api-compatibility-backend-0.1.0-SNAPSHOT.jar'
    $result=[ordered]@{}; $result[$jar]=IE-Hash (Join-Path $RuntimeDirectory $jar)
    $dependencies=Join-Path $RuntimeDirectory 'dependency'; IE-Safe $dependencies
    $entries=@(Get-ChildItem -LiteralPath $dependencies -Force)
    IE-Require ($entries.Count -ge 1 -and $entries.Count -le 32) 'Missing or excessive runtime dependencies.'
    foreach ($entry in ($entries | Sort-Object Name -CaseSensitive)) {
        IE-Safe $entry.FullName
        IE-Require (-not $entry.PSIsContainer -and $entry.Name -cmatch '^[A-Za-z0-9._-]+\.jar$') 'Unexpected runtime dependency.'
        $result['dependency/'+$entry.Name]=IE-Hash $entry.FullName
    }
    return $result
}
function IE-Java([string]$Entry,[string[]]$Arguments) {
    $classpath=(Join-Path $RuntimeDirectory 'api-compatibility-backend-0.1.0-SNAPSHOT.jar')+[IO.Path]::PathSeparator+(Join-Path $RuntimeDirectory 'dependency/*')
    $lines=@(& java '-cp' $classpath ('io.github.nktogo.apicompatibility.runner.'+$Entry) @Arguments 2>&1)
    IE-Require ($LASTEXITCODE -eq 0) 'Adapter metadata or retained evidence validation failed.'
    return ($lines -join [Environment]::NewLine)
}
function IE-Campaign([string]$Mode) {
    $arguments=@($Mode,$EvidenceDirectory,$StateDirectory,$CampaignId)
    if ($Mode -ceq 'verify-current') { $arguments+= $ExpectedReceiptSha256 }
    return (IE-Java 'InvoiceCampaignV2' $arguments | ConvertFrom-Json -AsHashtable -Depth 80)
}
function IE-Adapter([string]$Arm,[string[]]$Arguments) {
    IE-Require ($Arm -cin @($ieAdapters.Keys)) 'Unsupported campaign arm.'
    if ($Arguments[0] -cne 'run') { return (IE-Java $ieAdapters[$Arm].entry $Arguments) }
    IE-Require (IE-Same (IE-Runtime) $ieRegistration.runtimeArtifacts) 'Runtime artifacts changed before dispatch.'
    if ($Arm -ceq 'DETERMINISTIC') { return (IE-Java $ieAdapters[$Arm].entry $Arguments) }
    $previousKey=[Environment]::GetEnvironmentVariable('OPENAI_API_KEY','Process')
    try {
        if (-not $previousKey) {
            $keyFile=Join-Path $ieRoot '.env.local'
            if (Test-Path -LiteralPath $keyFile) {
                IE-Safe $keyFile
                $entries=@([IO.File]::ReadAllLines($keyFile) | Where-Object { $_ -match '^OPENAI_API_KEY=' })
                IE-Require ($entries.Count -eq 1) 'Configure exactly one OPENAI_API_KEY entry.'
                $value=$entries[0].Substring('OPENAI_API_KEY='.Length).Trim().Trim('"').Trim("'")
                [Environment]::SetEnvironmentVariable('OPENAI_API_KEY',$value,'Process')
                Remove-Variable value,entries
            }
        }
        return (IE-Java $ieAdapters[$Arm].entry $Arguments)
    } catch { throw 'Trial dispatch stopped. Preserve the mapped trial and inspect it before resuming.' }
    finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY',$previousKey,'Process') }
}
function IE-Policies {
    IE-Require (IE-Same (IE-Runtime) $ieRegistration.runtimeArtifacts) 'Runtime artifacts changed after execution registration.'
    $current=IE-Campaign 'verify-current'
    IE-Require ($current.receiptSha256 -ceq $ExpectedReceiptSha256) 'Campaign receipt changed before slot admission.'
    foreach ($arm in $ieAdapters.Keys) {
        $actual=(IE-Adapter $arm @('policy',$StateDirectory)) | ConvertFrom-Json -AsHashtable -Depth 80
        IE-Require (IE-Same $actual @{policy=$ieFrozen.policies[$arm];implementationIdentity=$iePlan.definition.implementations[$arm]}) 'Compiled policy changed. Preserve this campaign; do not change its conditions.'
    }
    IE-Require ((IE-Hash $ieDriver) -ceq $ieRegistration.driverSha256) 'Campaign driver changed after execution registration.'
}
function IE-Slot($Slot) { return @{workloadId=$Slot.workloadId;repetition=$Slot.repetition;arm=$Slot.arm} }
function IE-Arguments([string]$Action,[string]$Id) { return @($Action,$StateDirectory,$ieInputDirectory,$ieFrozen.selection.inputIdentity.sha256,$Id) }
function IE-Trials {
    $result=@()
    foreach ($namespace in @($ieAdapters.Values.runtime | Sort-Object -Unique)) {
        $root=Join-Path $StateDirectory $namespace; IE-Safe $root
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $entries=@(Get-ChildItem -LiteralPath $root -Force)
        IE-Require ($entries.Count -le 10000) 'Trial inventory exceeds inspection limit.'
        foreach ($entry in $entries) {
            IE-Safe $entry.FullName
            IE-Require ($entry.PSIsContainer -and $entry.Name -cmatch '^trial-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and (Test-Path -LiteralPath (Join-Path $entry.FullName 'spec.json'))) 'Incomplete or unexpected trial registration; reconcile it before admitting another trial.'
            $spec=IE-Json (Join-Path $entry.FullName 'spec.json')
            if ($spec.definition.experimentId -ceq $iePlan.definition.experimentId) {
                IE-Require ($spec.slot.arm -cin @($ieAdapters.Keys) -and $ieAdapters[$spec.slot.arm].runtime -ceq $namespace) 'Trial occupies the wrong v2 runtime namespace.'
                IE-Require (@($ieOrder.slots | Where-Object { IE-Same (IE-Slot $_) $spec.slot }).Count -eq 1) 'Unexpected campaign trial slot.'
                $result+=@{id=$entry.Name;spec=$spec}
            }
        }
    }
    return $result
}
function IE-Candidates($Slot) { return @(IE-Trials | Where-Object { IE-Same $_.spec.slot (IE-Slot $Slot) }) }
function IE-Mapping($Slot,[string]$Id,[string]$IntentPath) {
    IE-Require ($Id -cmatch '^trial-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid mapped trial reference.'
    $runtime=Join-Path $StateDirectory ($ieAdapters[$Slot.arm].runtime+'/'+$Id)
    $adapter=Join-Path $StateDirectory ($ieAdapters[$Slot.arm].store+'/'+$Id)
    $spec=IE-Json (Join-Path $runtime 'spec.json'); $intent=IE-Json $IntentPath
    IE-Require ($spec.formatVersion -eq 1 -and $spec.runId -ceq $Id -and $spec.bundleId -ceq $ieFrozen.selection.inputIdentity.sha256 -and $spec.inputReceiptSha256 -ceq (IE-Hash (Join-Path $ieInputDirectory 'receipt.json')) -and (IE-Same $spec.definition $iePlan.definition) -and (IE-Same $spec.slot (IE-Slot $Slot)) -and (IE-Time $spec.registeredAt) -ge (IE-Time $intent.createdAt)) 'Trial registration differs from its fresh campaign slot.'
    IE-Require (IE-Same $spec.policy $ieFrozen.policies[$Slot.arm]) 'Mapped trial policy differs from registration.'
    IE-Require (IE-Same (IE-Json (Join-Path $runtime 'grant.json')) @{permission='registered-coordination-trial';specSha256=(IE-Hash (Join-Path $runtime 'spec.json'))}) 'Changed mapped trial grant.'
    $adapterSpec=IE-Json (Join-Path $adapter 'spec.json')
    IE-Require ($adapterSpec.formatVersion -eq 2 -and $adapterSpec.runId -ceq $Id -and $adapterSpec.runtimeSpecSha256 -ceq (IE-Hash (Join-Path $runtime 'spec.json')) -and $adapterSpec.bundleId -ceq $spec.bundleId -and (IE-Same $adapterSpec.definition $spec.definition) -and (IE-Same $adapterSpec.slot $spec.slot) -and (IE-Same $adapterSpec.policy $spec.policy) -and $adapterSpec.publicTaskSha256 -ceq $ieFrozen.publicTask.sha256) 'Changed v2 adapter registration or task binding.'
    IE-Require (IE-Same (IE-Json (Join-Path $adapter 'registration.json')) @{specSha256=(IE-Hash (Join-Path $adapter 'spec.json'))}) 'Changed adapter registration.'
    return @{formatVersion=2;position=$Slot.position;runId=$Id;intentSha256=(IE-Hash $IntentPath);runtimeSpecSha256=(IE-Hash (Join-Path $runtime 'spec.json'));adapterSpecSha256=(IE-Hash (Join-Path $adapter 'spec.json'));adapterRegistrationSha256=(IE-Hash (Join-Path $adapter 'registration.json'))}
}
function IE-Accounting($Trace,[string]$Runtime,$Policy) {
    $fields=@('reservedCalls','registeredCalls','completedResponses','notDispatchedSlots','unresolvedUsageSlots','inputTokens','outputTokens','toolCalls','caseExecutions')
    $rows=[ordered]@{}; foreach ($p in $Policy.participants) { $r=[ordered]@{}; foreach ($f in $fields) { $r[$f]=[long]0 }; $rows[$p.id]=$r }
    foreach ($path in $Trace.artifactSha256.Keys) {
        if ($path -cmatch '^calls/[0-9a-f]{64}\.json$') {
            $reservation=IE-Json (Join-Path $Runtime $path); IE-Require ($rows.Contains($reservation.participant)) 'Unknown accounted participant.'
            $row=$rows[$reservation.participant]; $row.reservedCalls++
            $mappingPath=$path.Substring(0,$path.Length-5)+'-mapping.json'
            if (-not $Trace.artifactSha256.ContainsKey($mappingPath)) { $row.unresolvedUsageSlots++; continue }
            $mapping=IE-Json (Join-Path $Runtime $mappingPath); $row.registeredCalls++
            IE-Require ($mapping.callId -cmatch '^model-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid accounted broker reference.'
            $resultPath='broker/'+$mapping.callId+'/result.json'
            if (-not $Trace.artifactSha256.ContainsKey($resultPath)) { $row.unresolvedUsageSlots++; continue }
            $response=IE-Json (Join-Path $Runtime $resultPath)
            if ($response.status -ceq 'COMPLETED') { $row.completedResponses++; $row.inputTokens+=[long]$response.inputTokens; $row.outputTokens+=[long]$response.outputTokens }
            elseif ($response.remoteOutcome -ceq 'NOT_DISPATCHED') { $row.notDispatchedSlots++ }
            else { $row.unresolvedUsageSlots++ }
        } elseif ($path -cmatch '^reads/[0-9a-f]{64}\.json$') {
            $read=IE-Json (Join-Path $Runtime $path); IE-Require ($rows.Contains($read.participant)) 'Unknown read participant.'; $rows[$read.participant].toolCalls++
        }
    }
    foreach ($field in $fields) {
        $sum=[long]0; foreach ($row in $rows.Values) { $sum+=$row[$field] }
        IE-Require ($sum -eq $Trace.usage[$field]) 'Participant accounting differs from inclusive retained trace.'
    }
    return $rows
}
function IE-Outcome($Slot,$Mapping) {
    $null=IE-Adapter $Slot.arm (IE-Arguments 'read' $Mapping.runId)
    $runtime=Join-Path $StateDirectory ($ieAdapters[$Slot.arm].runtime+'/'+$Mapping.runId)
    $adapter=Join-Path $StateDirectory ($ieAdapters[$Slot.arm].store+'/'+$Mapping.runId)
    $result=IE-Json (Join-Path $adapter 'result.json'); $trace=IE-Json (Join-Path $runtime 'result.json'); $trial=$result.trial
    IE-Require ($trial.runId -ceq $Mapping.runId -and (IE-Same $trial.slot (IE-Slot $Slot)) -and (IE-Same $trial.usage $trace.usage) -and $trial.traceIdentity.sha256 -ceq (IE-Hash (Join-Path $runtime 'result.json')) -and $trial.status -ceq $trace.status -and $trial.status -cin @('COMPLETED','FAILED','CANCELLED','UNKNOWN_REMOTE_OUTCOME') -and $trial.elapsedMillis -eq $trace.elapsedMillis) 'Changed terminal trial or trace binding.'
    IE-Require ($result.formatVersion -eq 2 -and $trial.implementationIdentity.sha256 -ceq $iePlan.definition.implementations[$Slot.arm].sha256 -and $trial.usage.reservedCalls -le $iePlan.definition.limits.modelCalls -and $trial.usage.toolCalls -le $ieFrozen.policies[$Slot.arm].maxReads -and $trial.usage.caseExecutions -eq 0 -and $result.qualityReview -ceq 'NOT_PERFORMED') 'Changed measured policy, limits or review boundary.'
    IE-Require (IE-Same $trial.definitionIdentity $ieDefinitionIdentity) 'Changed measured campaign definition.'
    IE-Require (($Slot.arm -ceq 'DETERMINISTIC' -and $null -eq $trial.model -and $trial.usage.reservedCalls -eq 0) -or ($Slot.arm -cne 'DETERMINISTIC' -and $trial.model -ceq $iePlan.definition.model)) 'Changed measured model.'
    IE-Require ($trial.cases.Count -eq 1) 'Changed case denominator.'
    $expected=$iePlan.definition.workloads[0].cases[0]; $actual=$trial.cases[0]; IE-Require ($actual.caseId -ceq $expected.caseId -and (IE-Same $actual.inputIdentity $expected.inputIdentity) -and $null -eq $actual.review) 'Changed case input or supplied quality review.'
    $started=IE-Json (Join-Path $runtime 'started.json'); $intent=IE-Json (Join-Path $ieExecution ('slots/'+('{0:00}' -f $Slot.position)+'-intent.json'))
    IE-Require ((IE-Time $started.startedAt) -ge (IE-Time $intent.createdAt) -and (IE-Time $trace.finishedAt) -ge (IE-Time $started.startedAt) -and (IE-Time $started.deadline) -eq (IE-Time $started.startedAt).AddMilliseconds($iePlan.definition.limits.elapsedMillis)) 'Changed campaign timing or renewed deadline.'
    $participants=IE-Accounting $trace $runtime (IE-Json (Join-Path $runtime 'spec.json')).policy
    return @{formatVersion=2;position=$Slot.position;runId=$Mapping.runId;mappingSha256=(IE-Hash (Join-Path $ieExecution ('slots/'+('{0:00}' -f $Slot.position)+'-mapping.json')));adapterResultSha256=(IE-Hash (Join-Path $adapter 'result.json'));adapterReceiptSha256=(IE-Hash (Join-Path $adapter 'receipt.json'));traceReceiptSha256=(IE-Hash (Join-Path $runtime 'receipt.json'));startedAt=$started.startedAt;deadline=$started.deadline;finishedAt=$trace.finishedAt;trial=$trial;participants=$participants;resourceIssues=$trace.resourceIssues;qualityReview='NOT_PERFORMED'}
}
function IE-Inventory {
    foreach ($entry in (Get-ChildItem -LiteralPath $ieExecution -Force)) {
        IE-Safe $entry.FullName
        if ($entry.PSIsContainer) {
            IE-Require ($entry.Name -ceq 'slots') 'Unexpected campaign execution directory.'
            foreach ($child in (Get-ChildItem -LiteralPath $entry.FullName -Force)) { IE-Safe $child.FullName; IE-Require (-not $child.PSIsContainer -and $child.Name -cmatch '^(0[1-9]|1[0-2])-(intent|mapping|outcome|receipt)\.json$') 'Unexpected or incomplete slot publication.' }
        } else { IE-Require ($entry.Name -cin @('owner.lock','registration.json','registration-receipt.json','records.json','result.json','receipt.json')) 'Unexpected or incomplete execution publication.' }
    }
}
function Invoke-InvoiceCampaignV2Execution {
    $script:ieCampaign=Join-Path $StateDirectory ('invoice-campaigns-v2/'+$CampaignId)
    $script:ieExecution=Join-Path $StateDirectory ('invoice-campaign-v2-executions/'+$CampaignId)
    IE-Safe $ieCampaign; IE-Safe $ieExecution
    $script:ieRegistration=$null
    $null=IE-Runtime
    $existingRegistration=Join-Path $ieExecution 'registration.json'
    if (Test-Path -LiteralPath $existingRegistration) {
        IE-Require (IE-Same (IE-Runtime) (IE-Json $existingRegistration).runtimeArtifacts) 'Runtime artifacts changed before retained inspection.'
    }
    $validated=IE-Campaign 'read'
    IE-Require ($validated.receiptSha256 -ceq $ExpectedReceiptSha256) 'Campaign receipt differs from the independently expected identity.'
    $script:ieDefinitionIdentity=$validated.definitionIdentity
    $script:iePlan=IE-Json (Join-Path $ieCampaign 'plan.json'); $script:ieFrozen=IE-Json (Join-Path $ieCampaign 'frozen.json')
    $script:ieOrder=@{slots=@(for ($position=0;$position -lt $ieFrozen.order.Count;$position++) { @{position=$position+1;workloadId=$ieFrozen.order[$position].workloadId;repetition=$ieFrozen.order[$position].repetition;arm=$ieFrozen.order[$position].arm} })}
    $script:ieInputDirectory=[IO.Path]::GetFullPath((Join-Path $EvidenceDirectory $ieFrozen.selection.inputDirectory))
    IE-Safe (Join-Path $ieExecution 'slots')
    if ($Action -ceq 'run') { [void][IO.Directory]::CreateDirectory((Join-Path $ieExecution 'slots')) }
    IE-Safe (Join-Path $ieExecution 'owner.lock')
    $lease=[IO.File]::Open((Join-Path $ieExecution 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        IE-Inventory
        $registrationPath=Join-Path $ieExecution 'registration.json'
        if (-not (Test-Path -LiteralPath $registrationPath)) {
            IE-Require ($Action -ceq 'run' -and @(Get-ChildItem -LiteralPath (Join-Path $ieExecution 'slots') -Force).Count -eq 0) 'Missing execution registration.'
            $null=IE-Campaign 'verify-current'
            IE-Publish $registrationPath @{formatVersion=2;campaignId=$CampaignId;comparisonId=$validated.comparisonId;campaignReceiptSha256=$validated.receiptSha256;driverSha256=(IE-Hash $ieDriver);runtimeArtifacts=(IE-Runtime);createdAt=[DateTimeOffset]::UtcNow.ToString('O');authority='trusted-host-execution-not-semantic-review'}
        }
        $script:ieRegistration=IE-Json $registrationPath
        IE-Shape $ieRegistration @('formatVersion','campaignId','comparisonId','campaignReceiptSha256','driverSha256','runtimeArtifacts','createdAt','authority')
        IE-Require (IE-Same (IE-Runtime) $ieRegistration.runtimeArtifacts) 'Runtime artifacts changed after execution registration.'
        foreach ($sha in $ieRegistration.runtimeArtifacts.Values) { IE-Require ($sha -cmatch '^[0-9a-f]{64}$') 'Invalid runtime artifact identity.' }
        IE-Require ($ieRegistration.formatVersion -eq 2 -and $ieRegistration.campaignId -ceq $CampaignId -and $ieRegistration.comparisonId -ceq $validated.comparisonId -and $ieRegistration.campaignReceiptSha256 -ceq $validated.receiptSha256 -and $ieRegistration.driverSha256 -cmatch '^[0-9a-f]{64}$' -and $ieRegistration.authority -ceq 'trusted-host-execution-not-semantic-review') 'Changed execution registration.'
        $registrationReceipt=@{registrationSha256=(IE-Hash $registrationPath)}
        $receiptPath=Join-Path $ieExecution 'registration-receipt.json'
        if ($Action -ceq 'run' -and -not (Test-Path -LiteralPath $receiptPath)) { IE-Publish $receiptPath $registrationReceipt }
        IE-Require (IE-Same (IE-Json $receiptPath) $registrationReceipt) 'Changed execution registration receipt.'
        $outcomes=@(); $incomplete=@(); $dispatched=0; $previousAt=IE-Time $ieRegistration.createdAt; $gap=$false
        foreach ($slot in $ieOrder.slots) {
            $prefix=Join-Path $ieExecution ('slots/'+('{0:00}' -f $slot.position))
            $intentPath=$prefix+'-intent.json'; $mappingPath=$prefix+'-mapping.json'; $outcomePath=$prefix+'-outcome.json'; $slotReceiptPath=$prefix+'-receipt.json'
            $any=(Test-Path -LiteralPath $intentPath) -or (Test-Path -LiteralPath $mappingPath) -or (Test-Path -LiteralPath $outcomePath) -or (Test-Path -LiteralPath $slotReceiptPath)
            IE-Require (-not ($gap -and $any)) 'A later slot was admitted before the previous slot was sealed.'
            if ($Action -ceq 'run' -and $dispatched -lt $MaximumTrials -and -not (Test-Path -LiteralPath $outcomePath)) {
                IE-Policies
                if (-not (Test-Path -LiteralPath $intentPath)) {
                    IE-Require (@(IE-Candidates $slot).Count -eq 0) 'An unmapped trial already occupies this fresh campaign slot.'
                    IE-Publish $intentPath @{formatVersion=2;position=$slot.position;slot=(IE-Slot $slot);registrationSha256=(IE-Hash $registrationPath);createdAt=[DateTimeOffset]::UtcNow.ToString('O')}
                }
            }
            if (-not $any -and -not (Test-Path -LiteralPath $intentPath)) { $gap=$true; continue }
            $intent=IE-Json $intentPath; IE-Shape $intent @('formatVersion','position','slot','registrationSha256','createdAt')
            IE-Require ($intent.formatVersion -eq 2 -and $intent.position -eq $slot.position -and (IE-Same $intent.slot (IE-Slot $slot)) -and $intent.registrationSha256 -ceq (IE-Hash $registrationPath) -and (IE-Time $intent.createdAt) -ge $previousAt) 'Changed slot intent or recorded order.'
            $candidates=@(IE-Candidates $slot); IE-Require ($candidates.Count -le 1) 'Ambiguous slot registration; do not select a retry.'
            if ($Action -ceq 'run' -and $dispatched -lt $MaximumTrials -and -not (Test-Path -LiteralPath $mappingPath)) {
                if ($candidates.Count -eq 0) { $id=(IE-Adapter $slot.arm @('register',$StateDirectory,$ieInputDirectory,$ieFrozen.selection.inputIdentity.sha256,(Join-Path $ieCampaign 'plan.json'),$slot.workloadId,[string]$slot.repetition)).Trim() }
                else { $id=$candidates[0].id }
                IE-Require (-not (Test-Path -LiteralPath (Join-Path $StateDirectory ($ieAdapters[$slot.arm].runtime+'/'+$id+'/started.json')))) 'Unmapped trial already started; preserve its uncertain lineage.'
                IE-Publish $mappingPath (IE-Mapping $slot $id $intentPath)
                $candidates=@(IE-Candidates $slot)
            }
            if (-not (Test-Path -LiteralPath $mappingPath)) { $incomplete+=@{position=$slot.position;state='INTENT_WITHOUT_MAPPING';resources='NOT_INCLUDED_IN_SEALED_TOTALS'}; $gap=$true; continue }
            $mapping=IE-Json $mappingPath
            IE-Require ($candidates.Count -eq 1 -and $candidates[0].id -ceq $mapping.runId -and (IE-Same $mapping (IE-Mapping $slot $mapping.runId $intentPath))) 'Changed or duplicate mapped trial.'
            if ($Action -ceq 'run' -and $dispatched -lt $MaximumTrials -and -not (Test-Path -LiteralPath $outcomePath)) {
                $adapterResult=Join-Path $StateDirectory ($ieAdapters[$slot.arm].store+'/'+$mapping.runId+'/receipt.json')
                if (-not (Test-Path -LiteralPath $adapterResult)) { $null=IE-Adapter $slot.arm (IE-Arguments 'run' $mapping.runId) }
                IE-Publish $outcomePath (IE-Outcome $slot $mapping)
                $dispatched++
                Write-Information ('Recorded slot '+$slot.position+'/12: '+$slot.arm+'; trial '+$mapping.runId) -InformationAction Continue
            }
            if (-not (Test-Path -LiteralPath $outcomePath)) { $incomplete+=@{position=$slot.position;runId=$mapping.runId;state='MAPPED_UNSEALED';resources='NOT_INCLUDED_IN_SEALED_TOTALS'}; $gap=$true; continue }
            $outcome=IE-Outcome $slot $mapping
            IE-Require (IE-Same (IE-Json $outcomePath) $outcome) 'Changed sealed slot outcome.'
            $slotReceipt=@{outcomeSha256=(IE-Hash $outcomePath)}
            if ($Action -ceq 'run' -and -not (Test-Path -LiteralPath $slotReceiptPath)) { IE-Publish $slotReceiptPath $slotReceipt }
            IE-Require (IE-Same (IE-Json $slotReceiptPath) $slotReceipt) 'Missing or changed slot receipt.'
            $previousAt=IE-Time $outcome.finishedAt; $outcomes+=$outcome
        }
        $records=@{formatVersion=1;trials=@($outcomes | ForEach-Object { $_.trial })}
        $usage=[ordered]@{}; foreach ($f in @('reservedCalls','registeredCalls','completedResponses','notDispatchedSlots','unresolvedUsageSlots','inputTokens','outputTokens','toolCalls','caseExecutions')) { $usage[$f]=[long]0; foreach ($o in $outcomes) { $usage[$f]+=[long]$o.trial.usage[$f] } }
        $summary=@{formatVersion=2;campaignId=$CampaignId;comparisonId=$validated.comparisonId;registrationReceiptSha256=(IE-Hash $receiptPath);plannedTrials=12;plannedCaseOutputs=12;recordedTrials=$outcomes.Count;unrecordedTrials=12-$outcomes.Count;status=$(if ($outcomes.Count -eq 12) {'FINISHED'} else {'PENDING'});usage=$usage;usageScope='sealed-terminal-trials-only';incompleteSlots=$incomplete;trialElapsedMillis=[long](@($outcomes | ForEach-Object { $_.trial.elapsedMillis }) | Measure-Object -Sum).Sum;slots=@($outcomes | ForEach-Object { @{position=$_.position;runId=$_.runId;status=$_.trial.status;outcomeSha256=(IE-Hash (Join-Path $ieExecution ('slots/'+('{0:00}' -f $_.position)+'-outcome.json')));receiptSha256=(IE-Hash (Join-Path $ieExecution ('slots/'+('{0:00}' -f $_.position)+'-receipt.json')))} });qualityReview='NOT_PERFORMED';authority='known-invoice-operational-measurement-not-quality-or-routing'}
        if ($Action -ceq 'run' -and $outcomes.Count -eq 12) {
            foreach ($pair in @(@{file='records.json';value=$records},@{file='result.json';value=$summary})) {
                $path=Join-Path $ieExecution $pair.file
                if (Test-Path -LiteralPath $path) { IE-Require (IE-Same (IE-Json $path) $pair.value) 'Changed incomplete campaign result.' } else { IE-Publish $path $pair.value }
            }
            $terminalReceipt=@{registrationReceiptSha256=(IE-Hash $receiptPath);recordsSha256=(IE-Hash (Join-Path $ieExecution 'records.json'));resultSha256=(IE-Hash (Join-Path $ieExecution 'result.json'))}
            $path=Join-Path $ieExecution 'receipt.json'
            if (-not (Test-Path -LiteralPath $path)) { IE-Publish $path $terminalReceipt }
        }
        IE-Require ($Action -ceq 'run' -or $outcomes.Count -ne 12 -or (Test-Path -LiteralPath (Join-Path $ieExecution 'receipt.json'))) 'Terminal campaign seal is incomplete; resume the same campaign to reconcile metadata.'
        if (Test-Path -LiteralPath (Join-Path $ieExecution 'receipt.json')) {
            IE-Require ($outcomes.Count -eq 12 -and (IE-Same (IE-Json (Join-Path $ieExecution 'records.json')) $records) -and (IE-Same (IE-Json (Join-Path $ieExecution 'result.json')) $summary) -and (IE-Same (IE-Json (Join-Path $ieExecution 'receipt.json')) @{registrationReceiptSha256=(IE-Hash $receiptPath);recordsSha256=(IE-Hash (Join-Path $ieExecution 'records.json'));resultSha256=(IE-Hash (Join-Path $ieExecution 'result.json'))})) 'Changed terminal campaign receipt.'
        }
        IE-Inventory
        $summary | ConvertTo-Json -Depth 30
    } finally { $lease.Dispose() }
}
# Dot sourcing exposes the controller functions to isolated, credential-free tests.
if ($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignV2Execution }
