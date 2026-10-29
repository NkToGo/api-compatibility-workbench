#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../backend/target/invoice-campaign-comparison-tests/'+[guid]::NewGuid().ToString('N'))))
$testState=Join-Path $testRoot 'state';$testEvidence=Join-Path $testRoot 'evidence';$testReviews=Join-Path $testRoot 'reviews'
foreach($path in @($testState,$testEvidence,$testReviews)){[void][IO.Directory]::CreateDirectory($path)}
if($IsWindows){[IO.File]::SetAttributes($testState,([IO.File]::GetAttributes($testState) -bor [IO.FileAttributes]::Hidden))}
function Test-Write([string]$Path,$Value){[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path));[IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 80)+"`n",[Text.UTF8Encoding]::new($false))}
function Test-Hash([string]$Path){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($Path))).ToLowerInvariant()}
$testCampaign='invoicecampaign-'+[guid]::NewGuid().ToString();$testPacket='reviewpacket-'+[guid]::NewGuid().ToString();$testComparison='comparison-'+[guid]::NewGuid().ToString()
$testOriginal=Join-Path $testState ('invoice-campaign-executions/'+$testCampaign+'/records.json');$testPlan=Join-Path $testState ('coordination-comparisons/'+$testComparison+'/plan.json')
$testPacketFile=Join-Path $testState ('invoice-campaign-review-packets/'+$testPacket+'/packet.json');$testReviewFile=Join-Path $testReviews 'inspection.json'
$testCriteria=@('versioned-association','independent-version-boundaries','application-policy-gates','joint-ledger-behavior','execution-and-causal-scope','evidence-and-uncertainty')
$testArms=@('DETERMINISTIC','SINGLE_AGENT','INDEPENDENT_WORKERS','COORDINATED_TEAM','SINGLE_AGENT','INDEPENDENT_WORKERS','COORDINATED_TEAM','DETERMINISTIC','INDEPENDENT_WORKERS','COORDINATED_TEAM','DETERMINISTIC','SINGLE_AGENT')
$original=@{formatVersion=1;trials=@()};$sources=@();$reviewRows=@();$verifiedRows=@()
for($i=1;$i -le 12;$i++){
    $missing=$i -in @(2,5,9,12);$arm=$testArms[$i-1];$run='trial-'+[guid]::NewGuid().ToString()
    $output=@{caseId='stripe-invoice-subscription-2025-03-31';inputIdentity=@{sha256='3'*64};response=$(if($missing){'NOT_PROVIDED'}elseif($arm -ceq 'COORDINATED_TEAM'){'ABSTAINED'}else{'ANSWERED'});outputIdentity=$(if($missing){$null}else{@{sha256='4'*64}});review=$null}
    $trial=@{slot=@{workloadId='synthetic-invoice';repetition=[int][Math]::Ceiling($i/4.0);arm=$arm};runId=$run;definitionIdentity=@{sha256='5'*64};implementationIdentity=@{sha256='6'*64};model=$(if($arm -ceq 'DETERMINISTIC'){$null}else{'synthetic-model'});status=$(if($i -eq 12){'UNKNOWN_REMOTE_OUTCOME'}elseif($missing -or $arm -ceq 'COORDINATED_TEAM'){'FAILED'}else{'COMPLETED'});traceIdentity=@{sha256='7'*64};usage=@{reservedCalls=2;registeredCalls=2;completedResponses=$(if($i -eq 12){1}else{2});notDispatchedSlots=0;unresolvedUsageSlots=$(if($i -eq 12){1}else{0});inputTokens=13;outputTokens=2;toolCalls=3;caseExecutions=0};elapsedMillis=17;cases=@($output)}
    $source=@{arm=$arm;runId=$run;stateDirectory='state';resultIdentity=@{sha256='8'*64}}
    $original.trials+=,$trial;$sources+=,@{position=$i;source=$source;trial=$trial}
    $reviewRows+=,@{position=$i;source=$source;output=$output;coverageComplete=$true;criteria=@($testCriteria|ForEach-Object{@{criterionId=$_;judgment='NOT_SATISFIED'}})}
    $verifiedRows+=,@{position=$i;arm=$arm;coverageAttestedComplete=$true;pendingUnits=0;pendingCriteria=0;endorsedMaterialUnsupported=$(if($i -eq 3){3}else{0});endorsedMaterialUnresolved=$(if($i -eq 3){3}else{0});satisfiesDeclaredReview=$false}
}
Test-Write $testOriginal $original;Test-Write $testPlan @{fixture='synthetic evaluator boundary; not a real comparison plan'}
$sourceHashes=@{};$sourceHashes['state/invoice-campaign-executions/'+$testCampaign+'/records.json']=Test-Hash $testOriginal;$sourceHashes['state/coordination-comparisons/'+$testComparison+'/plan.json']=Test-Hash $testPlan
Test-Write $testPacketFile @{fixture='synthetic packet-reader boundary';inventory=@{comparisonId=$testComparison;sourceArtifacts=$sourceHashes;trials=$sources}}
Test-Write $testReviewFile @{fixture='synthetic reviewed coverage; not semantic adjudication';reviewer=@{method='model-assisted-offline-inspection';newProviderCalls=0};trials=$reviewRows}
Test-Write (Join-Path $testRoot 'fixture.json') @{fixture='synthetic verification/evaluator boundary and published-record replay; no new semantic judgments'}
$testPacketSha=Test-Hash $testPacketFile;$testReviewSha=Test-Hash $testReviewFile;$testReceiptSha='9'*64;$testReport=Join-Path $testRoot 'report'
. (Join-Path $PSScriptRoot 'Compare-InvoiceCampaign.ps1') -ReportAction publish -ReportDirectory $testReport -ReviewPath $testReviewFile -ReviewSha256 $testReviewSha -CampaignId $testCampaign -ExpectedReceiptSha256 $testReceiptSha -ReviewPacketId $testPacket -ExpectedPacketSha256 $testPacketSha -ReviewEvidenceDirectory $testRoot -EvidenceDirectory $testEvidence -StateDirectory $testState
$script:checks=0;$script:verifications=0;$script:evaluations=0;$script:fault='NONE'
function ICP-Verify {
    $script:verifications++
    ICR-Parse (ConvertTo-Json -Depth 80 -InputObject @{status='INVOICE_REVIEW_BINDINGS_MATCHED';packetId=$testPacket;packetSha256=$testPacketSha;reviewId='semanticreview-'+[guid]::Empty.ToString();reviewSha256=$testReviewSha;campaignId=$testCampaign;expectedCampaignReceiptSha256=$testReceiptSha;criteriaTiming='FROZEN_BEFORE_TRIALS';campaignAcceptance='NOT_ESTABLISHED';reviewedTrials=12;pendingUnits=$(if($script:fault -ceq 'PENDING'){1}else{0});pendingCriteria=0;trials=$verifiedRows;historicalMaterialUnsupported=4;historicalMaterialUnresolved=4})
}
function ICP-Evaluate([string]$Plan,[string]$Records) {
    $script:evaluations++
    if($script:fault -ceq 'PLAN_CHANGED'){[IO.File]::AppendAllText($testPlan,' ')}
    if($script:fault -ceq 'REVIEW_CHANGED'){[IO.File]::AppendAllText($testReviewFile,' ')}
    if($script:fault -ceq 'CORE_FAILED'){throw 'Synthetic evaluator refusal'}
    return @{formatVersion=2;planSha256=Test-Hash $Plan;recordsSha256=$(if($script:fault -ceq 'BAD_RESULT'){'a'*64}else{Test-Hash $Records});assessment=@{conclusion='UNRESOLVED';routingDecision=$(if($script:fault -ceq 'PROMOTION'){'ESTABLISHED'}else{'NOT_ESTABLISHED'});rows=@(1..12|ForEach-Object{@{fixture='synthetic row'}});pairs=@(1..6|ForEach-Object{@{fixture='synthetic pair'}})}}
}
function Test-Pass([string]$Label,[bool]$Value){if(-not $Value){throw ('FAIL: '+$Label)};$script:checks++;Write-Output ('PASS: '+$Label)}
function Test-Reject([string]$Label,[scriptblock]$Operation){$caught=$false;try{& $Operation > $null}catch{$caught=$true};Test-Pass $Label $caught}
function Test-Invoke{(Invoke-InvoiceCampaignComparison|Out-String)|ConvertFrom-Json -AsHashtable -Depth 80}
function Test-Copy($Value){ICR-Parse (ConvertTo-Json -InputObject $Value -Depth 80)}
$result=Test-Invoke;$recordsFile=Join-Path $testReport 'records.json';$assessmentFile=Join-Path $testReport 'assessment.json';$receiptFile=Join-Path $testReport 'receipt.json';$records=ICR-Json $recordsFile;$receipt=ICR-Json $receiptFile
Test-Pass 'all twelve slots and four absent outputs are retained' ($result.plannedSlots -eq 12 -and $result.providedOutputsBound -eq 8 -and $result.absentOutputsPreserved -eq 4)
Test-Pass 'missing outputs acquire neither identities nor reviews' (@($records.trials|Where-Object{$_.cases[0].response -ceq 'NOT_PROVIDED' -and ($null -ne $_.cases[0].review -or $null -ne $_.cases[0].outputIdentity)}).Count -eq 0)
$projection=Test-Copy $records;$projection.formatVersion=1;foreach($t in $projection.trials){$t.cases[0].review=$null}
Test-Pass 'original operational facts are unchanged' (ICR-Same $projection (ICR-Json $testOriginal))
Test-Pass 'unknown remote outcome usage and measured costs remain visible' ($records.trials[11].status -ceq 'UNKNOWN_REMOTE_OUTCOME' -and $records.trials[11].usage.unresolvedUsageSlots -eq 1 -and $records.trials[11].usage.inputTokens -eq 13)
Test-Pass 'explicit endorsed unsupported and unresolved counts are exported' ($records.trials[2].cases[0].review.materialUnsupportedClaims -eq 3 -and $records.trials[2].cases[0].review.materialUnresolvedClaims -eq 3)
Test-Pass 'the complete inspection and historical errors remain in the receipt' ($receipt.inspection.reviewedTrials -eq 12 -and $receipt.inspection.historicalMaterialUnsupported -eq 4 -and $receipt.inspection.historicalMaterialUnresolved -eq 4)
Test-Pass 'verification precedes exactly one evaluator call without participant calls' ($script:verifications -eq 1 -and $script:evaluations -eq 1 -and $result.newModelCalls -eq 0 -and $result.newConsumerExecutions -eq 0)
$pins=@{};foreach($p in @($recordsFile,$assessmentFile,$receiptFile)){$pins[$p]=Test-Hash $p}
Test-Pass 'repeated publication matches existing bytes' ((Test-Invoke).receiptSha256 -ceq $result.receiptSha256)
$ReportAction='read';Test-Pass 'read matches and changes no published artifact' ((Test-Invoke).assessmentSha256 -ceq $result.assessmentSha256)
ICP-CheckPins $pins
[IO.File]::Delete($receiptFile);Test-Reject 'missing final receipt is incomplete in read mode' {Test-Invoke}
$ReportAction='publish';Test-Pass 'publication recovers a missing final receipt without replacing records' ((Test-Invoke).receiptSha256 -ceq $result.receiptSha256);ICP-CheckPins $pins
$ReviewSha256='a'*64;Test-Reject 'wrong independent review identity is refused' {Test-Invoke};$ReviewSha256=$testReviewSha
$ExpectedPacketSha256='a'*64;Test-Reject 'wrong independent packet identity is refused' {Test-Invoke};$ExpectedPacketSha256=$testPacketSha
$ExpectedReceiptSha256='a'*64;Test-Reject 'wrong independent campaign receipt is refused' {Test-Invoke};$ExpectedReceiptSha256=$testReceiptSha
$script:fault='PENDING';Test-Reject 'pending coverage cannot be exported as complete' {Test-Invoke};$script:fault='NONE'
foreach($fault in @('PLAN_CHANGED','REVIEW_CHANGED','CORE_FAILED','BAD_RESULT','PROMOTION')){
    $script:fault=$fault;$ReportDirectory=Join-Path $testRoot ('fault-'+$fault.ToLowerInvariant());$beforePlan=[IO.File]::ReadAllBytes($testPlan);$beforeReview=[IO.File]::ReadAllBytes($testReviewFile)
    try{Test-Reject ('evaluator boundary refuses '+$fault) {Test-Invoke}}finally{[IO.File]::WriteAllBytes($testPlan,$beforePlan);[IO.File]::WriteAllBytes($testReviewFile,$beforeReview);$script:fault='NONE';$ReportDirectory=$testReport}
}
foreach($p in @($recordsFile,$assessmentFile,$receiptFile)){$saved=[IO.File]::ReadAllBytes($p);[IO.File]::AppendAllText($p,' ');try{Test-Reject ('changed published '+[IO.Path]::GetFileName($p)+' is preserved and refused') {Test-Invoke}}finally{[IO.File]::WriteAllBytes($p,$saved)}}
foreach($p in @($testState,$testEvidence,$testRoot,$testReviews)){$ReportDirectory=$p;Test-Reject 'report/source or selected-review overlap is refused' {Test-Invoke}};$ReportDirectory=$testReport
$link=Join-Path $testRoot 'linked-report';if($IsWindows){$null=New-Item -ItemType Junction -Path $link -Target $testReport}else{$null=New-Item -ItemType SymbolicLink -Path $link -Target $testReport}
try{$ReportDirectory=$link;Test-Reject 'linked report paths are refused' {Test-Invoke}}finally{$ReportDirectory=$testReport;Remove-Item -LiteralPath $link -Force}
$o=ICR-Json $testOriginal;$p=ICR-Json $testPacketFile;$r=ICR-Json $testReviewFile;$v=ICP-Verify
$r.trials[0].position=2;Test-Reject 'ordered review identity cannot drift' {ICP-Derive $o $p $r $v};$r=ICR-Json $testReviewFile
$o.trials[1].cases[0].outputIdentity=@{sha256='a'*64};$p.inventory.trials[1].trial=Test-Copy $o.trials[1];$r.trials[1].output=Test-Copy $o.trials[1].cases[0]
Test-Reject 'absent output cannot gain a fabricated identity' {ICP-Derive $o $p $r $v}
Test-Pass 'original plan records review and packet bytes remain unchanged' ((Test-Hash $testOriginal) -ceq $sourceHashes['state/invoice-campaign-executions/'+$testCampaign+'/records.json'] -and (Test-Hash $testPlan) -ceq $sourceHashes['state/coordination-comparisons/'+$testComparison+'/plan.json'] -and (Test-Hash $testReviewFile) -ceq $testReviewSha -and (Test-Hash $testPacketFile) -ceq $testPacketSha)
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$publicRoot=Join-Path $repository 'docs/evaluations'
$publicRecords=Join-Path $publicRoot 'invoice-reviewed-records-2026-10-29.json';$publicResult=Join-Path $publicRoot 'invoice-results-2026-10-29.json';$publicBinding=Join-Path $publicRoot 'invoice-results-binding-2026-10-29.json'
$measurement=ICR-Json (Join-Path $publicRoot 'invoice-campaign-2026-10-26.json')
$publicPlan=Join-Path $testRoot 'public-record-replay/plan.json';$publicReplay=Join-Path $testRoot 'public-record-replay/assessment.json'
Test-Write $publicPlan @{formatVersion=1;definition=$measurement.frozenRegistration.definition}
$classpath=(Join-Path $repository 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar')+[IO.Path]::PathSeparator+(Join-Path $repository 'backend/target/dependency/*')
& java -cp $classpath io.github.nktogo.apicompatibility.runner.CoordinationComparisonReport evaluate $publicPlan $publicRecords > $publicReplay
if($LASTEXITCODE -ne 0){throw 'Published supplied-record comparison replay failed.'}
$replay=ICR-Json $publicReplay;$published=ICR-Json $publicResult;$binding=ICR-Json $publicBinding
Test-Pass 'published supplied-record assessment replays with the existing evaluator' (ICR-Same $replay.assessment $published.assessment)
Test-Pass 'published record and supplied review identities match their binding receipt' ($replay.recordsSha256 -ceq $binding.recordsSha256 -and (Test-Hash (Join-Path $publicRoot 'invoice-campaign-review-2026-10-27.json')) -ceq $binding.reviewSha256)
Write-Output ('Invoice campaign comparison controls passed: '+$script:checks+'; synthetic boundaries and published-record replay, no model calls or new semantic judgments.')
