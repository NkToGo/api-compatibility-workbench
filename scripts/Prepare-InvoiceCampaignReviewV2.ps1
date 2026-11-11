#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('prepare','read')][string]$Action='prepare',
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [ValidatePattern('^reviewpacket-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPacketSha256,
    [Parameter(Mandatory)][string]$ReviewEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StateDirectory,
    [string]$RuntimeDirectory,
    [string]$InspectionRuntimeDirectory
)
$ErrorActionPreference='Stop'
$icr2Root=[IO.Path]::GetFullPath($ReviewEvidenceDirectory)
$EvidenceDirectory=[IO.Path]::GetFullPath($EvidenceDirectory)
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
$icr2Driver=$PSCommandPath
$icr2Repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not $RuntimeDirectory) { $RuntimeDirectory=Join-Path $icr2Repository 'backend/target' }
if(-not $InspectionRuntimeDirectory) { $InspectionRuntimeDirectory=Join-Path $icr2Repository 'backend/target' }
$RuntimeDirectory=[IO.Path]::GetFullPath($RuntimeDirectory)
$InspectionRuntimeDirectory=[IO.Path]::GetFullPath($InspectionRuntimeDirectory)
function ICR2-Require([bool]$Condition,[string]$Message) { if(-not $Condition) { throw $Message } }
function ICR2-Safe([string]$Path) {
    for($cursor=[IO.Path]::GetFullPath($Path);$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        try { $attributes=[IO.File]::GetAttributes($cursor) }
        catch [IO.FileNotFoundException] { continue }
        catch [IO.DirectoryNotFoundException] { continue }
        ICR2-Require (-not ($attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked invoice review paths are unsupported.'
    }
}
function ICR2-Relative([string]$Absolute) {
    ICR2-Safe $Absolute
    $ref=[IO.Path]::GetRelativePath($icr2Root,[IO.Path]::GetFullPath($Absolute)).Replace('\','/')
    ICR2-Require ($ref -cmatch '^[A-Za-z0-9_./-]+$' -and -not [IO.Path]::IsPathRooted($ref) -and @($ref.Split('/') | Where-Object {$_ -in @('','.','..')}).Count -eq 0) 'Review sources must be descendants of the common evidence root.'
    return $ref
}
function ICR2-Path([string]$Reference) {
    ICR2-Require ($Reference -cmatch '^[A-Za-z0-9_./-]+$' -and -not [IO.Path]::IsPathRooted($Reference) -and @($Reference.Split('/') | Where-Object {$_ -in @('','.','..')}).Count -eq 0) 'Invalid invoice review reference.'
    $path=[IO.Path]::GetFullPath((Join-Path $icr2Root $Reference)); $null=ICR2-Relative $path; return $path
}
function ICR2-Bytes([string]$Path,[int]$Limit=8388608) {
    ICR2-Safe $Path; $file=Get-Item -LiteralPath $Path -Force
    ICR2-Require (-not $file.PSIsContainer -and $file.Length -le $Limit) 'Missing or oversized invoice review artifact.'
    return ,[IO.File]::ReadAllBytes($Path)
}
function ICR2-Hash([string]$Path) { [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((ICR2-Bytes $Path))).ToLowerInvariant() }
function ICR2-Element($Element,[int]$Depth=0) {
    ICR2-Require ($Depth -le 64) 'Invoice review JSON nesting exceeded.'
    if($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $keys=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($p in $Element.EnumerateObject()) { ICR2-Require ($keys.Count -lt 5000 -and $keys.Add($p.Name)) 'Duplicate or excessive invoice review fields.'; ICR2-Element $p.Value ($Depth+1) }
    } elseif($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        ICR2-Require ($Element.GetArrayLength() -le 5000) 'Excessive invoice review array.'
        foreach($v in $Element.EnumerateArray()) { ICR2-Element $v ($Depth+1) }
    }
}
function ICR2-Parse([string]$Text) {
    ICR2-Require ([Text.Encoding]::UTF8.GetByteCount($Text) -le 8388608) 'Invoice review JSON exceeds 8 MiB.'
    $document=[Text.Json.JsonDocument]::Parse([string]$Text)
    try { ICR2-Element $document.RootElement } finally { $document.Dispose() }
    $Text | ConvertFrom-Json -AsHashtable -Depth 80
}
function ICR2-Json([string]$Path,[int]$Limit=8388608) { ICR2-Parse ([Text.UTF8Encoding]::new($false,$true).GetString((ICR2-Bytes $Path $Limit))) }
function ICR2-Canonical($Value) {
    if($Value -is [Collections.IDictionary]) { $result=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal); foreach($k in $Value.Keys) { $result[$k]=ICR2-Canonical $Value[$k] }; return $result }
    if($Value -is [Collections.IList]) { $result=[Collections.Generic.List[object]]::new(); foreach($v in $Value) { $result.Add((ICR2-Canonical $v)) }; return ,$result.ToArray() }
    return $Value
}
function ICR2-Same($Left,$Right) { (ConvertTo-Json -InputObject (ICR2-Canonical $Left) -Depth 80 -Compress) -ceq (ConvertTo-Json -InputObject (ICR2-Canonical $Right) -Depth 80 -Compress) }
function ICR2-Shape($Value,[string[]]$Keys) { ICR2-Require ($Value -is [Collections.IDictionary] -and [Collections.Generic.HashSet[string]]::new([string[]]@($Value.Keys),[StringComparer]::Ordinal).SetEquals($Keys)) 'Unexpected invoice review fields.' }
function ICR2-Publish([string]$Path,$Value) {
    ICR2-Safe $Path; ICR2-Require (-not (Test-Path -LiteralPath $Path)) 'Invoice review publications are create-only.'
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject (ICR2-Canonical $Value) -Depth 80)+"`n")
    ICR2-Require ($bytes.Length -le 2097152) 'Invoice campaign review packet exceeds 2 MiB.'
    $pending=$Path+'.pending'; ICR2-Safe $pending
    if(Test-Path -LiteralPath $pending) { ICR2-Require ([Convert]::ToBase64String((ICR2-Bytes $pending 2097152)) -ceq [Convert]::ToBase64String($bytes)) 'Changed interrupted invoice review publication.' }
    else { $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None); try { $stream.Write($bytes); $stream.Flush($true) } finally { $stream.Dispose() } }
    [IO.File]::Move($pending,$Path)
}
function ICR2-Ensure([string]$Path,$Value) {
    if(Test-Path -LiteralPath $Path) { ICR2-Require ((ICR2-Same (ICR2-Json $Path 2097152) $Value) -and -not (Test-Path -LiteralPath ($Path+'.pending'))) 'Changed invoice review publication.' }
    else { ICR2-Require ($Action -ceq 'prepare') 'Incomplete invoice review publication; resume prepare with the same ID.'; ICR2-Publish $Path $Value }
}
function ICR2-Inventory([string[]]$Roots) {
    $files=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal); $queue=[Collections.Generic.Queue[string]]::new(); $directories=0; $bytes=[long]0
    foreach($ref in $Roots) { $queue.Enqueue($ref) }
    while($queue.Count) {
        $ref=$queue.Dequeue(); $path=ICR2-Path $ref; $item=Get-Item -LiteralPath $path -Force
        if($item.PSIsContainer) {
            $directories++; ICR2-Require ($directories -le 5000) 'Invoice review directory limit exceeded.'
            foreach($child in (Get-ChildItem -LiteralPath $path -Force)) { ICR2-Safe $child.FullName; $queue.Enqueue($ref+'/'+$child.Name) }
        } elseif($item.Name -notlike '*.lock') { $bytes+=$item.Length; ICR2-Require ($bytes -le 67108864) 'Invoice review metadata exceeds 64 MiB.'; $files[$ref]=ICR2-Hash $path; ICR2-Require ($files.Count -le 5000) 'Invoice review source inventory exceeded.' }
    }
    return $files
}
function ICR2-Runtime([string]$Directory) {
    ICR2-Safe $Directory
    $jar='api-compatibility-backend-0.1.0-SNAPSHOT.jar'; $artifacts=[ordered]@{}; $artifacts[$jar]=ICR2-Hash (Join-Path $Directory $jar)
    $dependency=Join-Path $Directory 'dependency'; ICR2-Safe $dependency
    $entries=@(Get-ChildItem -LiteralPath $dependency -Force)
    ICR2-Require ($entries.Count -ge 1 -and $entries.Count -le 32) 'Missing or excessive inspection runtime dependencies.'
    foreach($entry in ($entries | Sort-Object Name -CaseSensitive)) {
        ICR2-Safe $entry.FullName
        ICR2-Require (-not $entry.PSIsContainer -and $entry.Name -cmatch '^[A-Za-z0-9._-]+\.jar$') 'Unexpected inspection runtime dependency.'
        $artifacts['dependency/'+$entry.Name]=ICR2-Hash $entry.FullName
    }
    return $artifacts
}
function ICR2-ValidateCampaign {
    # Retained read only; never invoke the participant execution action.
    ICR2-Parse ((& (Join-Path $PSScriptRoot 'Run-InvoiceCampaignV2.ps1') -Action read -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -EvidenceDirectory $EvidenceDirectory -StateDirectory $StateDirectory -RuntimeDirectory $RuntimeDirectory | Out-String))
}
function ICR2-Inspect([string]$SelectionPath) {
    ICR2-Require (ICR2-Same (ICR2-Runtime $InspectionRuntimeDirectory) $script:icr2InspectionArtifacts) 'Inspection runtime changed during review binding.'
    $classpath=(Join-Path $InspectionRuntimeDirectory 'api-compatibility-backend-0.1.0-SNAPSHOT.jar')+[IO.Path]::PathSeparator+(Join-Path $InspectionRuntimeDirectory 'dependency/*')
    $lines=@(& java -cp $classpath io.github.nktogo.apicompatibility.runner.InvoiceReviewInputsV2 inspect $icr2Root $SelectionPath 2>&1)
    ICR2-Require ($LASTEXITCODE -eq 0) 'Retained v2 invoice review inventory validation failed.'
    ICR2-Parse ($lines -join "`n")
}
function ICR2-Derive([string]$Directory,$Validated) {
    $campaignRef=ICR2-Relative (Join-Path $StateDirectory ('invoice-campaigns-v2/'+$CampaignId))
    $executionRef=ICR2-Relative (Join-Path $StateDirectory ('invoice-campaign-v2-executions/'+$CampaignId))
    $frozen=ICR2-Json (ICR2-Path ($campaignRef+'/frozen.json')); $registration=ICR2-Json (ICR2-Path ($campaignRef+'/registration.json'))
    $comparisonRef=ICR2-Relative (Join-Path $StateDirectory ('coordination-comparisons/'+$registration.comparisonId))
    $roots=@($campaignRef,$executionRef,($comparisonRef+'/plan.json'),($comparisonRef+'/registration.json'))
    $sources=ICR2-Inventory $roots
    $records=ICR2-Json (ICR2-Path ($executionRef+'/records.json'))
    ICR2-Require ($frozen.formatVersion -eq 2 -and $frozen.campaignId -ceq $CampaignId -and $frozen.publicTask.sha256 -cmatch '^[0-9a-f]{64}$' -and $Validated.comparisonId -ceq $registration.comparisonId) 'Changed v2 frozen scope or comparison binding.'
    ICR2-Require ($records.trials.Count -eq 12 -and $frozen.order.Count -eq 12 -and (ICR2-Hash (ICR2-Path ($campaignRef+'/receipt.json'))) -ceq $ExpectedReceiptSha256) 'Changed campaign denominator or independently expected receipt.'
    $runs=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal); $rows=New-Object object[] 12; $selections=@{}
    $stateRef=ICR2-Relative $StateDirectory; $base=$frozen.selection
    $selection=[ordered]@{formatVersion=2;inputDirectory=(ICR2-Relative (Join-Path $EvidenceDirectory $base.inputDirectory));inputIdentity=$base.inputIdentity;contractDirectory=(ICR2-Relative (Join-Path $EvidenceDirectory $base.contractDirectory));contractIdentity=$base.contractIdentity;rubricArtifact=(ICR2-Relative (Join-Path $EvidenceDirectory $base.rubricArtifact));rubricIdentity=$base.rubricIdentity;publicTaskSha256=$frozen.publicTask.sha256}
    $stores=@{DETERMINISTIC='invoice-diagnostics-v2';SINGLE_AGENT='invoice-single-agent-v2';INDEPENDENT_WORKERS='invoice-independent-workers-v2';COORDINATED_TEAM='invoice-team-comparisons-v2'}
    for($repetition=1;$repetition -le 3;$repetition++) {
        $selected=@(); $positions=@{}
        foreach($arm in @('DETERMINISTIC','SINGLE_AGENT','INDEPENDENT_WORKERS','COORDINATED_TEAM')) {
            $matches=@(0..11 | Where-Object {$frozen.order[$_].repetition -eq $repetition -and $frozen.order[$_].arm -ceq $arm})
            ICR2-Require ($matches.Count -eq 1) 'Incomplete repeated arm coverage.'
            $index=$matches[0]; $slot=$frozen.order[$index]; $trial=$records.trials[$index]
            ICR2-Require ((ICR2-Same $trial.slot $slot) -and $trial.runId -ceq $Validated.slots[$index].runId -and $runs.Add($trial.runId)) 'Changed or reused campaign trial.'
            $outcome=ICR2-Json (ICR2-Path ($executionRef+'/slots/'+('{0:00}' -f ($index+1))+'-outcome.json'))
            ICR2-Require ((ICR2-Same $outcome.trial $trial) -and [DateTimeOffset]$frozen.frozenAt -le [DateTimeOffset]$outcome.startedAt) 'Changed measured trial or criteria chronology.'
            $resultRef=$stateRef+'/'+$stores[$arm]+'/'+$trial.runId+'/result.json'
            ICR2-Require ((ICR2-Hash (ICR2-Path $resultRef)) -ceq $outcome.adapterResultSha256) 'Changed measured adapter result.'
            $selected+=@{arm=$arm;stateDirectory=$stateRef;runId=$trial.runId;resultIdentity=@{sha256=$outcome.adapterResultSha256}}; $positions[$arm]=$index
        }
        $selection.sources=$selected; $file=Join-Path $Directory ('selection-'+$repetition+'.json'); ICR2-Ensure $file $selection
        $selections[[IO.Path]::GetFileName($file)]=ICR2-Hash $file
        $inventory=ICR2-Inspect $file
        ICR2-Require ($inventory.formatVersion -eq 2 -and $inventory.publicTaskSha256 -ceq $frozen.publicTask.sha256 -and $inventory.qualityReview -ceq 'NOT_PERFORMED' -and $inventory.campaignAcceptance -ceq 'NOT_ESTABLISHED' -and $inventory.contractDirectory -ceq $selection.contractDirectory -and $inventory.rubricArtifact -ceq $selection.rubricArtifact -and $inventory.criteriaTiming -ceq 'NOT_INFERRED_FROM_SELECTION' -and $inventory.trials.Count -eq 4 -and (ICR2-Same $inventory.inputIdentity $base.inputIdentity) -and (ICR2-Same $inventory.contractIdentity $base.contractIdentity) -and (ICR2-Same $inventory.rubricIdentity $base.rubricIdentity)) 'Changed selected invoice review scope.'
        foreach($row in $inventory.trials) {
            $index=$positions[$row.source.arm]
            ICR2-Require ($row.position -in 1..4 -and $null -ne $index -and $null -eq $rows[$index] -and (ICR2-Same $row.source $selected[$row.position-1]) -and (ICR2-Same $row.trial $records.trials[$index]) -and $row.units.Count -gt 0 -and $row.units.Count -le 2000) 'Changed inspected trial or review units.'
            $row.position=$index+1; $rows[$index]=$row
        }
        foreach($ref in $inventory.sourceArtifacts.Keys) {
            $null=ICR2-Path $ref; ICR2-Require (-not $sources.ContainsKey($ref) -or $sources[$ref] -ceq $inventory.sourceArtifacts[$ref]) 'Review source changed across repetitions.'
            $sources[$ref]=$inventory.sourceArtifacts[$ref]
        }
    }
    ICR2-Require ($runs.Count -eq 12 -and @($rows | Where-Object {$null -eq $_}).Count -eq 0 -and $sources.Count -le 5000) 'Incomplete invoice review coverage.'
    $totalBytes=[long]0
    foreach($ref in $sources.Keys) { $sourcePath=ICR2-Path $ref; $totalBytes+=(Get-Item -LiteralPath $sourcePath).Length; ICR2-Require ($totalBytes -le 268435456 -and (ICR2-Hash $sourcePath) -ceq $sources[$ref]) 'Invoice review sources changed or exceed 256 MiB.' }
    return [ordered]@{campaignId=$CampaignId;comparisonId=$registration.comparisonId;expectedCampaignReceiptSha256=$ExpectedReceiptSha256;publicTaskSha256=$frozen.publicTask.sha256;criteriaTiming='FROZEN_BEFORE_TRIALS';campaignAcceptance='NOT_ESTABLISHED';inputIdentity=$base.inputIdentity;contractDirectory=$selection.contractDirectory;contractIdentity=$base.contractIdentity;rubricArtifact=$selection.rubricArtifact;rubricIdentity=$base.rubricIdentity;selectionArtifacts=$selections;sourceArtifacts=$sources;trials=$rows}
}
function Invoke-InvoiceCampaignReviewV2 {
    ICR2-Require ($Action -cne 'read' -or ([bool]$ReviewPacketId -and [bool]$ExpectedPacketSha256)) 'Read requires ReviewPacketId and its independently retained ExpectedPacketSha256.'
    ICR2-Safe $icr2Root; $null=ICR2-Relative $EvidenceDirectory; $null=ICR2-Relative $StateDirectory
    $separation=[IO.Path]::GetRelativePath($EvidenceDirectory,$StateDirectory).Replace('\','/')
    $reverse=[IO.Path]::GetRelativePath($StateDirectory,$EvidenceDirectory).Replace('\','/')
    ICR2-Require ($separation.StartsWith('../',[StringComparison]::Ordinal) -and $reverse.StartsWith('../',[StringComparison]::Ordinal)) 'Campaign evidence and state roots must be separate beneath the common root.'
    $script:icr2InspectionArtifacts=ICR2-Runtime $InspectionRuntimeDirectory
    $validated=ICR2-ValidateCampaign
    ICR2-Require ($validated.formatVersion -eq 2 -and $validated.campaignId -ceq $CampaignId -and $validated.status -ceq 'FINISHED' -and $validated.recordedTrials -eq 12 -and $validated.unrecordedTrials -eq 0 -and $validated.qualityReview -ceq 'NOT_PERFORMED') 'Only a sealed complete unreviewed campaign can bind review inputs.'
    if(-not $ReviewPacketId) { $ReviewPacketId='reviewpacket-v2-'+[guid]::NewGuid().ToString() }
    $directory=Join-Path $StateDirectory ('invoice-campaign-v2-review-packets/'+$ReviewPacketId); ICR2-Safe $directory
    if($Action -ceq 'prepare') { [void][IO.Directory]::CreateDirectory($directory) }
    ICR2-Require (Test-Path -LiteralPath $directory -PathType Container) 'Missing invoice campaign review packet.'
    ICR2-Safe (Join-Path $directory 'owner.lock'); $lease=[IO.File]::Open((Join-Path $directory 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $allowed=@('owner.lock','packet.json','packet.json.pending','receipt.json','receipt.json.pending','selection-1.json','selection-2.json','selection-3.json','selection-1.json.pending','selection-2.json.pending','selection-3.json.pending')
        foreach($item in (Get-ChildItem -LiteralPath $directory -Force)) { ICR2-Safe $item.FullName; ICR2-Require (-not $item.PSIsContainer -and $item.Name -cin $allowed) 'Unexpected invoice campaign review artifact.' }
        $path=Join-Path $directory 'packet.json'; $pending=$path+'.pending'
        if(Test-Path -LiteralPath $path) { $packet=ICR2-Json $path 2097152 }
        elseif(Test-Path -LiteralPath $pending) { ICR2-Require ($Action -ceq 'prepare') 'Pending invoice review publication.'; $packet=ICR2-Json $pending 2097152 }
        else { ICR2-Require ($Action -ceq 'prepare') 'Missing invoice review publication.'; $packet=$null }
        if($ExpectedPacketSha256) {
            $existing=$(if(Test-Path -LiteralPath $path) { $path } else { $pending })
            ICR2-Require ($packet -and (ICR2-Hash $existing) -ceq $ExpectedPacketSha256) 'Missing or changed independently expected packet; inspect preserved bytes before recovery.'
        }
        if($packet) {
            ICR2-Shape $packet.producer @('extractor','scriptSha256','inspectionRuntimeArtifacts')
            ICR2-Require (ICR2-Same $packet.producer.inspectionRuntimeArtifacts $script:icr2InspectionArtifacts) 'Selected inspection runtime differs from the bound packet.'
        }
        $inventory=ICR2-Derive $directory $validated
        if(-not $packet) { $packet=[ordered]@{formatVersion=2;packetId=$ReviewPacketId;createdAtUnixMillis=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();producer=@{extractor='invoice-campaign-review-v2';scriptSha256=(ICR2-Hash $icr2Driver);inspectionRuntimeArtifacts=$script:icr2InspectionArtifacts};inventory=$inventory;qualityReview='NOT_PERFORMED';authority='campaign-bound-review-inputs-not-semantic-acceptance'} }
        ICR2-Shape $packet @('formatVersion','packetId','createdAtUnixMillis','producer','inventory','qualityReview','authority'); ICR2-Shape $packet.producer @('extractor','scriptSha256','inspectionRuntimeArtifacts')
        ICR2-Require ($packet.formatVersion -eq 2 -and $packet.packetId -ceq $ReviewPacketId -and $packet.createdAtUnixMillis -is [long] -and $packet.createdAtUnixMillis -ge 0 -and $packet.producer.extractor -ceq 'invoice-campaign-review-v2' -and $packet.producer.scriptSha256 -cmatch '^[0-9a-f]{64}$' -and $packet.qualityReview -ceq 'NOT_PERFORMED' -and $packet.authority -ceq 'campaign-bound-review-inputs-not-semantic-acceptance' -and (ICR2-Same $packet.inventory $inventory)) 'Changed invoice campaign review binding.'
        ICR2-Ensure $path $packet
        ICR2-Require (-not $ExpectedPacketSha256 -or (ICR2-Hash $path) -ceq $ExpectedPacketSha256) 'Changed independently expected invoice packet bytes.'
        ICR2-Ensure (Join-Path $directory 'receipt.json') @{formatVersion=2;packetSha256=(ICR2-Hash $path)}
        ICR2-Require ((ICR2-Same (ICR2-ValidateCampaign) $validated)) 'Measured campaign changed during review binding.'
        ICR2-Require (ICR2-Same (ICR2-Runtime $InspectionRuntimeDirectory) $script:icr2InspectionArtifacts) 'Inspection runtime changed during publication.'
        foreach($ref in $inventory.sourceArtifacts.Keys) { ICR2-Require ((ICR2-Hash (ICR2-Path $ref)) -ceq $inventory.sourceArtifacts[$ref]) 'Review evidence changed during publication.' }
        [ordered]@{status='REVIEW_INPUTS_BOUND';packetId=$ReviewPacketId;campaignId=$CampaignId;trials=$inventory.trials.Count;caseOutputs=$inventory.trials.Count;reviewUnits=@($inventory.trials.units).Count;sourceArtifacts=$inventory.sourceArtifacts.Count;packetSha256=(ICR2-Hash $path);participantCallsInitiatedByThisCommand=0;qualityReview='NOT_PERFORMED';criteriaTiming=$inventory.criteriaTiming} | ConvertTo-Json -Depth 5
    } finally { $lease.Dispose() }
}
if($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignReviewV2 }
