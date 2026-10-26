#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('prepare','read')][string]$Action='prepare',
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [ValidatePattern('^reviewpacket-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPacketSha256,
    [Parameter(Mandatory)][string]$ReviewEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StateDirectory
)
$ErrorActionPreference='Stop'
$icrRoot=[IO.Path]::GetFullPath($ReviewEvidenceDirectory)
$EvidenceDirectory=[IO.Path]::GetFullPath($EvidenceDirectory)
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
$icrDriver=$PSCommandPath
$icrRepository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function ICR-Require([bool]$Condition,[string]$Message) { if(-not $Condition) { throw $Message } }
function ICR-Safe([string]$Path) {
    for($cursor=[IO.Path]::GetFullPath($Path);$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        try { $attributes=[IO.File]::GetAttributes($cursor) }
        catch [IO.FileNotFoundException] { continue }
        catch [IO.DirectoryNotFoundException] { continue }
        ICR-Require (-not ($attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked invoice review paths are unsupported.'
    }
}
function ICR-Relative([string]$Absolute) {
    ICR-Safe $Absolute
    $ref=[IO.Path]::GetRelativePath($icrRoot,[IO.Path]::GetFullPath($Absolute)).Replace('\','/')
    ICR-Require ($ref -cmatch '^[A-Za-z0-9_./-]+$' -and -not [IO.Path]::IsPathRooted($ref) -and @($ref.Split('/') | Where-Object {$_ -in @('','.','..')}).Count -eq 0) 'Review sources must be descendants of the common evidence root.'
    return $ref
}
function ICR-Path([string]$Reference) {
    ICR-Require ($Reference -cmatch '^[A-Za-z0-9_./-]+$' -and -not [IO.Path]::IsPathRooted($Reference) -and @($Reference.Split('/') | Where-Object {$_ -in @('','.','..')}).Count -eq 0) 'Invalid invoice review reference.'
    $path=[IO.Path]::GetFullPath((Join-Path $icrRoot $Reference)); $null=ICR-Relative $path; return $path
}
function ICR-Bytes([string]$Path,[int]$Limit=8388608) {
    ICR-Safe $Path; $file=Get-Item -LiteralPath $Path -Force
    ICR-Require (-not $file.PSIsContainer -and $file.Length -le $Limit) 'Missing or oversized invoice review artifact.'
    return ,[IO.File]::ReadAllBytes($Path)
}
function ICR-Hash([string]$Path) { [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((ICR-Bytes $Path))).ToLowerInvariant() }
function ICR-Element($Element,[int]$Depth=0) {
    ICR-Require ($Depth -le 64) 'Invoice review JSON nesting exceeded.'
    if($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $keys=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($p in $Element.EnumerateObject()) { ICR-Require ($keys.Count -lt 5000 -and $keys.Add($p.Name)) 'Duplicate or excessive invoice review fields.'; ICR-Element $p.Value ($Depth+1) }
    } elseif($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        ICR-Require ($Element.GetArrayLength() -le 5000) 'Excessive invoice review array.'
        foreach($v in $Element.EnumerateArray()) { ICR-Element $v ($Depth+1) }
    }
}
function ICR-Parse([string]$Text) {
    ICR-Require ([Text.Encoding]::UTF8.GetByteCount($Text) -le 8388608) 'Invoice review JSON exceeds 8 MiB.'
    $document=[Text.Json.JsonDocument]::Parse([string]$Text)
    try { ICR-Element $document.RootElement } finally { $document.Dispose() }
    $Text | ConvertFrom-Json -AsHashtable -Depth 80
}
function ICR-Json([string]$Path,[int]$Limit=8388608) { ICR-Parse ([Text.UTF8Encoding]::new($false,$true).GetString((ICR-Bytes $Path $Limit))) }
function ICR-Canonical($Value) {
    if($Value -is [Collections.IDictionary]) { $result=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal); foreach($k in $Value.Keys) { $result[$k]=ICR-Canonical $Value[$k] }; return $result }
    if($Value -is [Collections.IList]) { $result=[Collections.Generic.List[object]]::new(); foreach($v in $Value) { $result.Add((ICR-Canonical $v)) }; return ,$result.ToArray() }
    return $Value
}
function ICR-Same($Left,$Right) { (ConvertTo-Json -InputObject (ICR-Canonical $Left) -Depth 80 -Compress) -ceq (ConvertTo-Json -InputObject (ICR-Canonical $Right) -Depth 80 -Compress) }
function ICR-Shape($Value,[string[]]$Keys) { ICR-Require ($Value -is [Collections.IDictionary] -and [Collections.Generic.HashSet[string]]::new([string[]]@($Value.Keys),[StringComparer]::Ordinal).SetEquals($Keys)) 'Unexpected invoice review fields.' }
function ICR-Publish([string]$Path,$Value) {
    ICR-Safe $Path; ICR-Require (-not (Test-Path -LiteralPath $Path)) 'Invoice review publications are create-only.'
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject (ICR-Canonical $Value) -Depth 80)+"`n")
    ICR-Require ($bytes.Length -le 2097152) 'Invoice campaign review packet exceeds 2 MiB.'
    $pending=$Path+'.pending'; ICR-Safe $pending
    if(Test-Path -LiteralPath $pending) { ICR-Require ([Convert]::ToBase64String((ICR-Bytes $pending 2097152)) -ceq [Convert]::ToBase64String($bytes)) 'Changed interrupted invoice review publication.' }
    else { $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None); try { $stream.Write($bytes); $stream.Flush($true) } finally { $stream.Dispose() } }
    [IO.File]::Move($pending,$Path)
}
function ICR-Ensure([string]$Path,$Value) {
    if(Test-Path -LiteralPath $Path) { ICR-Require ((ICR-Same (ICR-Json $Path 2097152) $Value) -and -not (Test-Path -LiteralPath ($Path+'.pending'))) 'Changed invoice review publication.' }
    else { ICR-Require ($Action -ceq 'prepare') 'Incomplete invoice review publication; resume prepare with the same ID.'; ICR-Publish $Path $Value }
}
function ICR-Inventory([string[]]$Roots) {
    $files=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal); $queue=[Collections.Generic.Queue[string]]::new(); $directories=0; $bytes=[long]0
    foreach($ref in $Roots) { $queue.Enqueue($ref) }
    while($queue.Count) {
        $ref=$queue.Dequeue(); $path=ICR-Path $ref; $item=Get-Item -LiteralPath $path -Force
        if($item.PSIsContainer) {
            $directories++; ICR-Require ($directories -le 5000) 'Invoice review directory limit exceeded.'
            foreach($child in (Get-ChildItem -LiteralPath $path -Force)) { ICR-Safe $child.FullName; $queue.Enqueue($ref+'/'+$child.Name) }
        } elseif($item.Name -notlike '*.lock') { $bytes+=$item.Length; ICR-Require ($bytes -le 67108864) 'Invoice review metadata exceeds 64 MiB.'; $files[$ref]=ICR-Hash $path; ICR-Require ($files.Count -le 5000) 'Invoice review source inventory exceeded.' }
    }
    return $files
}
function ICR-ValidateCampaign {
    # Retained read only; never invoke the participant execution action.
    (& (Join-Path $PSScriptRoot 'Run-InvoiceCampaign.ps1') -Action read -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -EvidenceDirectory $EvidenceDirectory -StateDirectory $StateDirectory | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80
}
function ICR-Inspect([string]$SelectionPath) {
    $jar=Join-Path $icrRepository 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
    $newest=Get-ChildItem -LiteralPath (Join-Path $icrRepository 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    ICR-Require ($newest.LastWriteTimeUtc -le (Get-Item -LiteralPath $jar).LastWriteTimeUtc) 'Backend JAR is stale; rebuild before inspecting review inputs.'
    $classpath=$jar+[IO.Path]::PathSeparator+(Join-Path $icrRepository 'backend/target/dependency/*')
    $lines=@(& java -cp $classpath io.github.nktogo.apicompatibility.runner.InvoiceReviewInputs inspect $icrRoot $StateDirectory $SelectionPath 2>&1)
    ICR-Require ($LASTEXITCODE -eq 0) 'Retained invoice review inventory validation failed.'
    ICR-Parse ($lines -join "`n")
}
function ICR-Derive([string]$Directory,$Validated) {
    $campaignRef=ICR-Relative (Join-Path $StateDirectory ('invoice-campaigns/'+$CampaignId))
    $executionRef=ICR-Relative (Join-Path $StateDirectory ('invoice-campaign-executions/'+$CampaignId))
    $frozen=ICR-Json (ICR-Path ($campaignRef+'/frozen.json')); $registration=ICR-Json (ICR-Path ($campaignRef+'/registration.json'))
    $comparisonRef=ICR-Relative (Join-Path $StateDirectory ('coordination-comparisons/'+$registration.comparisonId))
    $roots=@($campaignRef,$executionRef,($comparisonRef+'/plan.json'),($comparisonRef+'/registration.json'))
    $sources=ICR-Inventory $roots
    $records=ICR-Json (ICR-Path ($executionRef+'/records.json'))
    ICR-Require ($records.trials.Count -eq 12 -and $frozen.order.Count -eq 12 -and (ICR-Hash (ICR-Path ($campaignRef+'/receipt.json'))) -ceq $ExpectedReceiptSha256) 'Changed campaign denominator or independently expected receipt.'
    $runs=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal); $rows=New-Object object[] 12; $selections=@{}
    $stateRef=ICR-Relative $StateDirectory; $base=$frozen.selection
    $selection=[ordered]@{formatVersion=1;inputDirectory=(ICR-Relative (Join-Path $EvidenceDirectory $base.inputDirectory));inputIdentity=$base.inputIdentity;contractDirectory=(ICR-Relative (Join-Path $EvidenceDirectory $base.contractDirectory));contractIdentity=$base.contractIdentity;rubricArtifact=(ICR-Relative (Join-Path $EvidenceDirectory $base.rubricArtifact));rubricIdentity=$base.rubricIdentity}
    $stores=@{DETERMINISTIC='invoice-diagnostics';SINGLE_AGENT='invoice-single-agent';INDEPENDENT_WORKERS='invoice-independent-workers';COORDINATED_TEAM='invoice-team-comparisons'}
    for($repetition=1;$repetition -le 3;$repetition++) {
        $selected=@(); $positions=@{}
        foreach($arm in @('DETERMINISTIC','SINGLE_AGENT','INDEPENDENT_WORKERS','COORDINATED_TEAM')) {
            $matches=@(0..11 | Where-Object {$frozen.order[$_].repetition -eq $repetition -and $frozen.order[$_].arm -ceq $arm})
            ICR-Require ($matches.Count -eq 1) 'Incomplete repeated arm coverage.'
            $index=$matches[0]; $slot=$frozen.order[$index]; $trial=$records.trials[$index]
            ICR-Require ((ICR-Same $trial.slot $slot) -and $trial.runId -ceq $Validated.slots[$index].runId -and $runs.Add($trial.runId)) 'Changed or reused campaign trial.'
            $outcome=ICR-Json (ICR-Path ($executionRef+'/slots/'+('{0:00}' -f ($index+1))+'-outcome.json'))
            ICR-Require ((ICR-Same $outcome.trial $trial) -and [DateTimeOffset]$frozen.frozenAt -le [DateTimeOffset]$outcome.startedAt) 'Changed measured trial or criteria chronology.'
            $resultRef=$stateRef+'/'+$stores[$arm]+'/'+$trial.runId+'/result.json'
            ICR-Require ((ICR-Hash (ICR-Path $resultRef)) -ceq $outcome.adapterResultSha256) 'Changed measured adapter result.'
            $selected+=@{arm=$arm;stateDirectory=$stateRef;runId=$trial.runId;resultIdentity=@{sha256=$outcome.adapterResultSha256}}; $positions[$arm]=$index
        }
        $selection.sources=$selected; $file=Join-Path $Directory ('selection-'+$repetition+'.json'); ICR-Ensure $file $selection
        $selections[[IO.Path]::GetFileName($file)]=ICR-Hash $file
        $inventory=ICR-Inspect $file
        ICR-Require ($inventory.criteriaTiming -ceq 'NOT_INFERRED_FROM_SELECTION' -and $inventory.trials.Count -eq 4 -and (ICR-Same $inventory.inputIdentity $base.inputIdentity) -and (ICR-Same $inventory.contractIdentity $base.contractIdentity) -and (ICR-Same $inventory.rubricIdentity $base.rubricIdentity)) 'Changed selected invoice review scope.'
        foreach($row in $inventory.trials) {
            $index=$positions[$row.source.arm]
            ICR-Require ($null -ne $index -and $null -eq $rows[$index] -and (ICR-Same $row.source $selected[$row.position-1]) -and (ICR-Same $row.trial $records.trials[$index]) -and $row.units.Count -gt 0 -and $row.units.Count -le 2000) 'Changed inspected trial or review units.'
            $row.position=$index+1; $rows[$index]=$row
        }
        foreach($ref in $inventory.sourceArtifacts.Keys) {
            $null=ICR-Path $ref; ICR-Require (-not $sources.ContainsKey($ref) -or $sources[$ref] -ceq $inventory.sourceArtifacts[$ref]) 'Review source changed across repetitions.'
            $sources[$ref]=$inventory.sourceArtifacts[$ref]
        }
    }
    ICR-Require ($runs.Count -eq 12 -and @($rows | Where-Object {$null -eq $_}).Count -eq 0 -and $sources.Count -le 5000) 'Incomplete invoice review coverage.'
    $totalBytes=[long]0
    foreach($ref in $sources.Keys) { $sourcePath=ICR-Path $ref; $totalBytes+=(Get-Item -LiteralPath $sourcePath).Length; ICR-Require ($totalBytes -le 268435456 -and (ICR-Hash $sourcePath) -ceq $sources[$ref]) 'Invoice review sources changed or exceed 256 MiB.' }
    return [ordered]@{campaignId=$CampaignId;comparisonId=$registration.comparisonId;expectedCampaignReceiptSha256=$ExpectedReceiptSha256;criteriaTiming='FROZEN_BEFORE_TRIALS';campaignAcceptance='NOT_ESTABLISHED';inputIdentity=$base.inputIdentity;contractDirectory=$selection.contractDirectory;contractIdentity=$base.contractIdentity;rubricArtifact=$selection.rubricArtifact;rubricIdentity=$base.rubricIdentity;selectionArtifacts=$selections;sourceArtifacts=$sources;trials=$rows}
}
function Invoke-InvoiceCampaignReview {
    ICR-Require ($Action -cne 'read' -or ([bool]$ReviewPacketId -and [bool]$ExpectedPacketSha256)) 'Read requires ReviewPacketId and its independently retained ExpectedPacketSha256.'
    ICR-Safe $icrRoot; $null=ICR-Relative $EvidenceDirectory; $null=ICR-Relative $StateDirectory
    $separation=[IO.Path]::GetRelativePath($EvidenceDirectory,$StateDirectory).Replace('\','/')
    $reverse=[IO.Path]::GetRelativePath($StateDirectory,$EvidenceDirectory).Replace('\','/')
    ICR-Require ($separation.StartsWith('../',[StringComparison]::Ordinal) -and $reverse.StartsWith('../',[StringComparison]::Ordinal)) 'Campaign evidence and state roots must be separate beneath the common root.'
    $validated=ICR-ValidateCampaign
    ICR-Require ($validated.status -ceq 'FINISHED' -and $validated.recordedTrials -eq 12 -and $validated.unrecordedTrials -eq 0 -and $validated.qualityReview -ceq 'NOT_PERFORMED') 'Only a sealed complete unreviewed campaign can bind review inputs.'
    if(-not $ReviewPacketId) { $ReviewPacketId='reviewpacket-'+[guid]::NewGuid().ToString() }
    $directory=Join-Path $StateDirectory ('invoice-campaign-review-packets/'+$ReviewPacketId); ICR-Safe $directory
    if($Action -ceq 'prepare') { [void][IO.Directory]::CreateDirectory($directory) }
    ICR-Require (Test-Path -LiteralPath $directory -PathType Container) 'Missing invoice campaign review packet.'
    ICR-Safe (Join-Path $directory 'owner.lock'); $lease=[IO.File]::Open((Join-Path $directory 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $allowed=@('owner.lock','packet.json','packet.json.pending','receipt.json','receipt.json.pending','selection-1.json','selection-2.json','selection-3.json','selection-1.json.pending','selection-2.json.pending','selection-3.json.pending')
        foreach($item in (Get-ChildItem -LiteralPath $directory -Force)) { ICR-Safe $item.FullName; ICR-Require (-not $item.PSIsContainer -and $item.Name -cin $allowed) 'Unexpected invoice campaign review artifact.' }
        $inventory=ICR-Derive $directory $validated
        $path=Join-Path $directory 'packet.json'; $pending=$path+'.pending'
        if(Test-Path -LiteralPath $path) { $packet=ICR-Json $path 2097152 }
        elseif(Test-Path -LiteralPath $pending) { ICR-Require ($Action -ceq 'prepare') 'Pending invoice review publication.'; $packet=ICR-Json $pending 2097152 }
        else { ICR-Require ($Action -ceq 'prepare') 'Missing invoice review publication.'; $packet=[ordered]@{formatVersion=1;packetId=$ReviewPacketId;createdAtUnixMillis=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();producer=@{extractor='invoice-campaign-review-v1';scriptSha256=(ICR-Hash $icrDriver)};inventory=$inventory;qualityReview='NOT_PERFORMED';authority='campaign-bound-review-inputs-not-semantic-acceptance'} }
        ICR-Shape $packet @('formatVersion','packetId','createdAtUnixMillis','producer','inventory','qualityReview','authority'); ICR-Shape $packet.producer @('extractor','scriptSha256')
        ICR-Require ($packet.formatVersion -eq 1 -and $packet.packetId -ceq $ReviewPacketId -and $packet.createdAtUnixMillis -is [long] -and $packet.createdAtUnixMillis -ge 0 -and $packet.producer.extractor -ceq 'invoice-campaign-review-v1' -and $packet.producer.scriptSha256 -cmatch '^[0-9a-f]{64}$' -and $packet.qualityReview -ceq 'NOT_PERFORMED' -and $packet.authority -ceq 'campaign-bound-review-inputs-not-semantic-acceptance' -and (ICR-Same $packet.inventory $inventory)) 'Changed invoice campaign review binding.'
        ICR-Ensure $path $packet
        ICR-Require (-not $ExpectedPacketSha256 -or (ICR-Hash $path) -ceq $ExpectedPacketSha256) 'Changed independently expected invoice packet bytes.'
        ICR-Ensure (Join-Path $directory 'receipt.json') @{formatVersion=1;packetSha256=(ICR-Hash $path)}
        ICR-Require ((ICR-Same (ICR-ValidateCampaign) $validated)) 'Measured campaign changed during review binding.'
        foreach($ref in $inventory.sourceArtifacts.Keys) { ICR-Require ((ICR-Hash (ICR-Path $ref)) -ceq $inventory.sourceArtifacts[$ref]) 'Review evidence changed during publication.' }
        [ordered]@{status='REVIEW_INPUTS_BOUND';packetId=$ReviewPacketId;campaignId=$CampaignId;trials=$inventory.trials.Count;caseOutputs=$inventory.trials.Count;reviewUnits=@($inventory.trials.units).Count;sourceArtifacts=$inventory.sourceArtifacts.Count;packetSha256=(ICR-Hash $path);participantCallsInitiatedByThisCommand=0;qualityReview='NOT_PERFORMED';criteriaTiming=$inventory.criteriaTiming} | ConvertTo-Json -Depth 5
    } finally { $lease.Dispose() }
}
if($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignReview }
