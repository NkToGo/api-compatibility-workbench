#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('prepare','read')][string]$Action = 'prepare',
    [Parameter(Mandatory)][ValidatePattern('^campaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [ValidatePattern('^reviewpacket-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [string]$StateDirectory
)
$ErrorActionPreference='Stop'
$rpRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $StateDirectory) { $StateDirectory=Join-Path $rpRoot '.workbench' }
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
$rpDriver=$PSCommandPath
$rpStores=@{DETERMINISTIC='coordination-diagnostics';SINGLE_AGENT='coordination-single-agent';INDEPENDENT_WORKERS='coordination-independent-workers';COORDINATED_TEAM='coordination-team-comparisons'}
function RP-Require([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function RP-Safe([string]$Path) {
    for ($cursor=[IO.Path]::GetFullPath($Path);$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if (Test-Path -LiteralPath $cursor) { RP-Require (-not ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked review paths are unsupported.' }
    }
}
function RP-Path([string]$Relative) {
    RP-Require ($Relative -cmatch '^[a-zA-Z0-9_./-]+$' -and -not [IO.Path]::IsPathRooted($Relative) -and $Relative -notmatch '(^|/)\.\.?(/|$)') 'Invalid review artifact reference.'
    $path=[IO.Path]::GetFullPath((Join-Path $StateDirectory $Relative))
    RP-Require ($path.StartsWith([IO.Path]::TrimEndingDirectorySeparator($StateDirectory)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)) 'Review reference escaped its store.'
    RP-Safe $path; return $path
}
function RP-Bytes([string]$Path,[int]$Limit=8388608) {
    RP-Safe $Path; $item=Get-Item -LiteralPath $Path -Force
    RP-Require (-not $item.PSIsContainer -and $item.Length -le $Limit) 'Missing or oversized review artifact.'
    return ,[IO.File]::ReadAllBytes($Path)
}
function RP-Hash([string]$Path) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((RP-Bytes $Path))).ToLowerInvariant() }
function RP-Json([string]$Path) {
    $bytes=RP-Bytes $Path
    if ($null -ne $script:rpObserved) {
        $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
        RP-Require (-not $script:rpObserved.ContainsKey($Path) -or $script:rpObserved[$Path] -ceq $digest) 'Review source changed during derivation.'
        $script:rpObserved[$Path]=$digest
    }
    return [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -AsHashtable -Depth 80
}
function RP-Canonical($Value) {
    if ($Value -is [Collections.IDictionary]) { $r=[ordered]@{}; foreach ($k in ($Value.Keys | Sort-Object -CaseSensitive)) { $r[$k]=RP-Canonical $Value[$k] }; return $r }
    if ($Value -is [Collections.IList]) { $r=[Collections.Generic.List[object]]::new(); foreach ($v in $Value) { $r.Add((RP-Canonical $v)) }; return ,$r.ToArray() }
    return $Value
}
function RP-Same($Left,$Right) { return (ConvertTo-Json -InputObject (RP-Canonical $Left) -Depth 80 -Compress) -ceq (ConvertTo-Json -InputObject (RP-Canonical $Right) -Depth 80 -Compress) }
function RP-Shape($Value,[string[]]$Keys) {
    RP-Require ($Value -is [Collections.IDictionary] -and ([Collections.Generic.HashSet[string]]::new([string[]]@($Value.Keys),[StringComparer]::Ordinal)).SetEquals($Keys)) 'Unexpected or missing review fields.'
}
function RP-Publish([string]$Path,$Value) {
    RP-Safe $Path; RP-Require (-not (Test-Path -LiteralPath $Path)) 'Review artifacts are create-only.'
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Value -Depth 80)+"`n")
    RP-Require ($bytes.Length -le 1048576) 'Review packet exceeds 1 MiB.'
    $pending=$Path+'.pending'; RP-Safe $pending
    if (Test-Path -LiteralPath $pending) {
        RP-Require ([Convert]::ToBase64String((RP-Bytes $pending 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Interrupted review publication differs; preserve it for inspection.'
    } else {
        $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($bytes); $stream.Flush($true) } finally { $stream.Dispose() }
    }
    [IO.File]::Move($pending,$Path)
}
function RP-ValidateCampaign {
    # Only retained reads are permitted here. This wrapper does not load credentials.
    return (& (Join-Path $PSScriptRoot 'Run-CoordinationCampaign.ps1') -Action read -CampaignId $CampaignId -StateDirectory $StateDirectory | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80
}
function RP-Inventory([string[]]$Roots) {
    $files=[ordered]@{}; $queue=[Collections.Generic.Queue[string]]::new()
    foreach ($relative in $Roots) { $queue.Enqueue((RP-Path $relative)) }
    $directories=0
    while ($queue.Count) {
        $path=$queue.Dequeue(); RP-Safe $path; $item=Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer) {
            $directories++; RP-Require ($directories -le 5000) 'Review directory inventory exceeds its limit.'
            foreach ($child in (Get-ChildItem -LiteralPath $path -Force)) { RP-Safe $child.FullName; $queue.Enqueue($child.FullName) }
        } elseif ($item.Name -notlike '*.lock') {
            $relative=[IO.Path]::GetRelativePath($StateDirectory,$path).Replace('\','/')
            $files[$relative]=RP-Hash (RP-Path $relative)
            RP-Require ($files.Count -le 5000) 'Review artifact inventory exceeds its limit.'
        }
    }
    return RP-Canonical $files
}
function RP-Unit($Units,[string]$Kind,[string]$Artifact,[string]$Pointer,[string]$ReferenceId='', [string]$Relation='') {
    $Units.Add([ordered]@{unitId=('unit-'+($Units.Count+1));kind=$Kind;artifact=$Artifact;pointer=$Pointer;referenceId=$ReferenceId;finalCitationRelation=$Relation})
}
function RP-Derive {
    $script:rpObserved=@{}
    try {
    $campaign='coordination-campaigns/'+$CampaignId; $execution='coordination-campaign-executions/'+$CampaignId
    $frozen=RP-Json (RP-Path ($campaign+'/frozen.json')); $registration=RP-Json (RP-Path ($campaign+'/registration.json'))
    $order=RP-Json (RP-Path ($campaign+'/order.json')); $plan=RP-Json (RP-Path ($campaign+'/plan.json'))
    $records=RP-Json (RP-Path ($execution+'/records.json')); $tasks=$plan.definition.workloads[0].cases
    RP-Require ($frozen.bundleId -cmatch '^inputs-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and $registration.comparisonId -cmatch '^comparison-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid review input identities.'
    RP-Require ($records.trials.Count -eq 12 -and $order.slots.Count -eq 12 -and $tasks.Count -eq 2 -and @($tasks.caseId | Sort-Object -Unique).Count -eq 2) 'Incomplete review denominator.'
    $roots=[Collections.Generic.List[string]]::new()
    foreach ($r in @($campaign,$execution,('coordination-inputs/'+$frozen.bundleId),('coordination-comparisons/'+$registration.comparisonId+'/plan.json'),('coordination-comparisons/'+$registration.comparisonId+'/registration.json'))) { $roots.Add($r) }
    $cases=[Collections.Generic.List[object]]::new(); $runs=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($position=0;$position -lt 12;$position++) {
        $trial=$records.trials[$position]; $slot=$order.slots[$position]
        RP-Require ($trial.runId -cmatch '^trial-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and $runs.Add($trial.runId) -and $rpStores.ContainsKey($slot.arm) -and (RP-Same $trial.slot @{workloadId=$slot.workloadId;arm=$slot.arm;repetition=$slot.repetition}) -and $trial.cases.Count -eq 2) 'Changed review trial coverage.'
        $adapter=$rpStores[$slot.arm]+'/'+$trial.runId; $roots.Add($adapter); $roots.Add('coordination-trials/'+$trial.runId)
        if ($slot.arm -ceq 'COORDINATED_TEAM') { $roots.Add('coordination-team-runs/'+$trial.runId) }
        foreach ($task in $tasks) {
            $caseOutputs=@($trial.cases | Where-Object {$_.caseId -ceq $task.caseId})
            RP-Require ($caseOutputs.Count -eq 1 -and $task.caseId -cmatch '^[a-z0-9-]+$') 'Changed review case coverage.'
            $output=$caseOutputs[0]; $units=[Collections.Generic.List[object]]::new(); $dossierPath=$adapter+'/'+$task.caseId+'.json'
            RP-Require ((RP-Same $output.inputIdentity $task.inputIdentity) -and $null -eq $output.review) 'Changed review input or existing quality judgment.'
            if ($output.response -ceq 'NOT_PROVIDED') { RP-Require ($null -eq $output.outputIdentity -and -not (Test-Path -LiteralPath (RP-Path $dossierPath))) 'Absent output has a dossier.'; $dossierPath=$null }
            else {
                RP-Require ($output.response -cin @('ANSWERED','ABSTAINED') -and $output.outputIdentity.sha256 -ceq (RP-Hash (RP-Path $dossierPath))) 'Changed review output bytes.'
                $dossier=RP-Json (RP-Path $dossierPath)
                RP-Require ($script:rpObserved[(RP-Path $dossierPath)] -ceq $output.outputIdentity.sha256) 'Dossier bytes changed during derivation.'
                RP-Require ($dossier.caseId -ceq $task.caseId -and (RP-Same $dossier.inputIdentity $task.inputIdentity) -and $dossier.response -ceq $output.response) 'Changed dossier identity.'
                RP-Unit $units 'FINAL_DOSSIER' $dossierPath ''
                if ($slot.arm -ceq 'DETERMINISTIC') {
                    foreach ($name in @('scope','executionDiagnostics','projectionDiagnostics','unresolved')) { RP-Require ($dossier.ContainsKey($name)) 'Missing diagnostic review section.'; RP-Unit $units 'STRUCTURED_DIAGNOSTIC' $dossierPath ('/'+$name) }
                } else {
                    RP-Require ($dossier.summary -is [string] -and $dossier.findings -is [Collections.IList]) 'Missing final explanation.'
                    RP-Unit $units 'FINAL_SUMMARY' $dossierPath '/summary'
                    for ($i=0;$i -lt $dossier.findings.Count;$i++) { RP-Unit $units 'FINAL_FINDING' $dossierPath ('/findings/'+$i) }
                }
            }
            if ($slot.arm -ceq 'COORDINATED_TEAM') {
                $evidencePath=$adapter+'/evidence.json'; $evidence=RP-Json (RP-Path $evidencePath)
                $cited=if ($null -ne $dossierPath) { @($dossier.summaryCitations)+@($dossier.findings.citations) } else { @() }
                $refs=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
                foreach ($collection in @('originalClaims','freshClaims')) {
                    RP-Require ($evidence[$collection] -is [Collections.IList]) 'Missing lineage collection.'
                    for ($i=0;$i -lt $evidence[$collection].Count;$i++) {
                        $claim=$evidence[$collection][$i]
                        RP-Require ($claim.referenceId -cmatch '^(claim|freshclaim)-[1-9][0-9]*$' -and $refs.Add($claim.referenceId) -and $claim.caseId -cin @($tasks.caseId)) 'Invalid or duplicate lineage reference.'
                        if ($claim.caseId -cne $task.caseId) { continue }
                        RP-Require ((RP-Same $claim.inputIdentity $task.inputIdentity) -and $claim.statement -is [string]) 'Changed lineage input.'
                        $relation=if ($cited -ccontains $claim.referenceId) {'CITED'} else {'NOT_CITED'}
                        RP-Unit $units $(if ($collection -ceq 'originalClaims') {'ORIGINAL_CLAIM'} else {'FRESH_CLAIM'}) $evidencePath ('/'+$collection+'/'+$i) $claim.referenceId $relation
                    }
                }
                # Keep entire team decisions and conflicts, including claims not cited by final answers.
                foreach ($name in @('workers','freshWorkers','coordinatorReview','possibleConflicts')) { RP-Require ($evidence.ContainsKey($name)) 'Missing team context.'; RP-Unit $units 'TEAM_CONTEXT' $evidencePath ('/'+$name) }
                RP-Unit $units 'RECONCILIATION' ($adapter+'/reconciliation.json') ''
            }
            $cases.Add([ordered]@{position=$position+1;slot=$trial.slot;runId=$trial.runId;caseId=$task.caseId;inputIdentity=$output.inputIdentity;response=$output.response;outputIdentity=$output.outputIdentity;dossierArtifact=$dossierPath;capturedEvidenceDirectory=('coordination-inputs/'+$frozen.bundleId+'/'+$task.caseId);units=$units.ToArray()})
        }
    }
    $sources=RP-Inventory $roots.ToArray()
    foreach ($entry in $script:rpObserved.GetEnumerator()) {
        $relative=[IO.Path]::GetRelativePath($StateDirectory,$entry.Key).Replace('\','/')
        RP-Require ($sources.Contains($relative) -and $sources[$relative] -ceq $entry.Value) 'Review source changed during inventory.'
    }
    return [ordered]@{campaignId=$CampaignId;comparisonId=$registration.comparisonId;bundleId=$frozen.bundleId;rubricArtifact=$campaign+'/rubric.json';sourceArtifacts=$sources;cases=$cases.ToArray()}
    } finally { $script:rpObserved=$null }
}
function Invoke-CoordinationReviewPacket {
    RP-Require ($Action -cne 'read' -or [bool]$ReviewPacketId) 'Read requires ReviewPacketId.'
    $validated=RP-ValidateCampaign
    RP-Require ($validated.status -ceq 'FINISHED' -and $validated.recordedTrials -eq 12 -and $validated.unrecordedTrials -eq 0 -and $validated.qualityReview -ceq 'NOT_PERFORMED') 'Only a sealed complete unreviewed campaign can prepare review packets.'
    $inventory=RP-Derive
    if (-not $ReviewPacketId) { $ReviewPacketId='reviewpacket-'+[guid]::NewGuid().ToString() }
    $directory=RP-Path ('coordination-review-packets/'+$ReviewPacketId)
    if ($Action -ceq 'prepare') { [void][IO.Directory]::CreateDirectory($directory) }
    RP-Require (Test-Path -LiteralPath $directory -PathType Container) 'Missing review packet.'
    RP-Safe (Join-Path $directory 'owner.lock')
    $lease=[IO.File]::Open((Join-Path $directory 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        foreach ($item in (Get-ChildItem -LiteralPath $directory -Force)) { RP-Safe $item.FullName; RP-Require (-not $item.PSIsContainer -and $item.Name -cin @('owner.lock','packet.json','receipt.json','packet.json.pending','receipt.json.pending')) 'Unexpected review packet artifact.' }
        $path=Join-Path $directory 'packet.json'
        if (-not (Test-Path -LiteralPath $path)) {
            RP-Require ($Action -ceq 'prepare') 'Incomplete review packet publication.'
            if (Test-Path -LiteralPath ($path+'.pending')) { $packet=RP-Json ($path+'.pending') }
            else { $packet=[ordered]@{formatVersion=1;packetId=$ReviewPacketId;createdAtUnixMillis=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();producer=@{extractor='complete-dossiers-and-all-team-lineage-v1';scriptSha256=(RP-Hash $rpDriver)};inventory=$inventory;qualityReview='NOT_PERFORMED';authority='review-input-inventory-not-claim-coverage-or-semantic-acceptance'} }
        } else { $packet=RP-Json $path }
        RP-Shape $packet @('formatVersion','packetId','createdAtUnixMillis','producer','inventory','qualityReview','authority')
        RP-Shape $packet.producer @('extractor','scriptSha256')
        RP-Require ($packet.formatVersion -eq 1 -and $packet.packetId -ceq $ReviewPacketId -and $packet.createdAtUnixMillis -is [long] -and $packet.createdAtUnixMillis -ge 0 -and $packet.producer.extractor -ceq 'complete-dossiers-and-all-team-lineage-v1' -and $packet.producer.scriptSha256 -cmatch '^[0-9a-f]{64}$' -and $packet.qualityReview -ceq 'NOT_PERFORMED' -and $packet.authority -ceq 'review-input-inventory-not-claim-coverage-or-semantic-acceptance' -and (RP-Same $packet.inventory $inventory)) 'Changed review packet identity, sources or derived coverage.'
        if (-not (Test-Path -LiteralPath $path)) { RP-Publish $path $packet }
        $receipt=@{formatVersion=1;packetSha256=(RP-Hash $path)}; $receiptPath=Join-Path $directory 'receipt.json'
        if (-not (Test-Path -LiteralPath $receiptPath)) { RP-Require ($Action -ceq 'prepare') 'Incomplete review packet seal; resume prepare with the same ID.'; RP-Publish $receiptPath $receipt }
        RP-Require ((RP-Same (RP-Json $receiptPath) $receipt) -and -not (Test-Path -LiteralPath ($path+'.pending')) -and -not (Test-Path -LiteralPath ($receiptPath+'.pending'))) 'Changed or incomplete review packet receipt.'
        RP-Require (RP-Same (RP-ValidateCampaign) $validated) 'Campaign changed during review inspection.'
        # Detect evidence changes during publication without invoking participant execution.
        foreach ($entry in $inventory.sourceArtifacts.GetEnumerator()) { RP-Require ((RP-Hash (RP-Path $entry.Key)) -ceq $entry.Value) 'Review source changed during inspection.' }
        [ordered]@{status='REVIEW_INPUTS_BOUND';packetId=$ReviewPacketId;campaignId=$CampaignId;caseOutputs=$inventory.cases.Count;reviewUnits=@($inventory.cases.units).Count;sourceArtifacts=$inventory.sourceArtifacts.Count;packetSha256=(RP-Hash $path);paidCallsInitiatedByThisCommand=0;qualityReview='NOT_PERFORMED';scope='Exact review inputs only; units may contain multiple material claims and citations do not establish semantic support.'} | ConvertTo-Json -Depth 5
    } finally { $lease.Dispose() }
}
# Dot sourcing exposes the pure inventory boundary to isolated, credential-free tests.
if ($MyInvocation.InvocationName -ne '.') { Invoke-CoordinationReviewPacket }
