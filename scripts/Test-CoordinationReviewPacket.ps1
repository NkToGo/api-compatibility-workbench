#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../backend/target/review-packet-tests'))
$testState=Join-Path $testRoot ([guid]::NewGuid().ToString('N')+'/.workbench')
[void][IO.Directory]::CreateDirectory($testState)
if ($IsWindows) { [IO.File]::SetAttributes($testState,([IO.File]::GetAttributes($testState) -bor [IO.FileAttributes]::Hidden)) }
function Test-Write([string]$Relative,$Value) {
    $path=Join-Path $testState $Relative; [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path,(ConvertTo-Json -InputObject $Value -Depth 80)+"`n",[Text.UTF8Encoding]::new($false))
}
function Test-Hash([string]$Relative) { return (Get-FileHash -LiteralPath (Join-Path $testState $Relative) -Algorithm SHA256).Hash.ToLowerInvariant() }
$testCampaign='campaign-'+[guid]::NewGuid().ToString(); $testBundle='inputs-'+[guid]::NewGuid().ToString(); $testComparison='comparison-'+[guid]::NewGuid().ToString()
$testArms=@('DETERMINISTIC','SINGLE_AGENT','INDEPENDENT_WORKERS','COORDINATED_TEAM')
$testStores=@('coordination-diagnostics','coordination-single-agent','coordination-independent-workers','coordination-team-comparisons')
$testCases=@(@{caseId='stripe-customer-sources-2020-08-27';inputIdentity=@{sha256=('1'*64)}},@{caseId='stripe-subscription-periods-2025-07-30';inputIdentity=@{sha256=('2'*64)}})
$testSlots=@(); $testTrials=@()
for ($i=0;$i -lt 12;$i++) {
    $arm=$testArms[$i%4]; $id='trial-'+[guid]::NewGuid().ToString(); $adapter=$testStores[$i%4]+'/'+$id
    $slot=@{workloadId='two-case';arm=$arm;repetition=1+[int][Math]::Floor($i/4)}; $testSlots+=@{position=$i+1;workloadId=$slot.workloadId;arm=$arm;repetition=$slot.repetition}; $outputs=@()
    foreach ($c in $testCases) {
        if ($arm -ceq 'DETERMINISTIC') { $d=@{caseId=$c.caseId;inputIdentity=$c.inputIdentity;response='ANSWERED';scope='synthetic test only';executionDiagnostics=@{outcome='FAILED';scope='declared check'};projectionDiagnostics=@{complete=$false};unresolved=@('Unknown cause.')} }
        else {
            $cites=if ($arm -ceq 'COORDINATED_TEAM') {if ($c.caseId -ceq $testCases[0].caseId) {@('claim-1','freshclaim-1')} else {@('claim-2')}} else {@('read-1')}
            $d=@{caseId=$c.caseId;inputIdentity=$c.inputIdentity;response='ANSWERED';summary='Synthetic explanation, not a measured finding.';summaryCitations=$cites;findings=@(@{kind='LIMITATION';statement='Synthetic limitation.';citations=$cites})}
        }
        $file=$adapter+'/'+$c.caseId+'.json'; Test-Write $file $d
        $outputs+=@{caseId=$c.caseId;inputIdentity=$c.inputIdentity;response='ANSWERED';outputIdentity=@{sha256=(Test-Hash $file)};review=$null}
    }
    Test-Write ($adapter+'/result.json') @{qualityReview='NOT_PERFORMED';authority='synthetic-controller-test'}
    Test-Write ('coordination-trials/'+$id+'/result.json') @{status='COMPLETED';authority='synthetic-controller-test'}
    Test-Write ('coordination-trials/'+$id+'/broker/model-test/request.json') @{purpose='Synthetic evidence binding only, no dispatch.'}
    if ($arm -ceq 'COORDINATED_TEAM') {
        Test-Write ($adapter+'/evidence.json') @{
            originalClaims=@(@{referenceId='claim-1';caseId=$testCases[0].caseId;inputIdentity=$testCases[0].inputIdentity;statement='Earlier cited explanation.'},@{referenceId='claim-2';caseId=$testCases[1].caseId;inputIdentity=$testCases[1].inputIdentity;statement='Other case explanation.'},@{referenceId='claim-3';caseId=$testCases[0].caseId;inputIdentity=$testCases[0].inputIdentity;statement='Earlier uncited claim requiring separate review.'})
            freshClaims=@(@{referenceId='freshclaim-1';caseId=$testCases[0].caseId;inputIdentity=$testCases[0].inputIdentity;statement='Fresh follow-up explanation.'})
            workers=@(@{referenceId='worker-1';response='ANSWERED'});freshWorkers=@(@{referenceId='freshworker-1';response='ANSWERED'})
            coordinatorReview=@{decision=@{summary='Unresolved issue remains.'}};possibleConflicts=@(@{state='UNRESOLVED';claimIds=@('claim-1','claim-3')})
        }
        Test-Write ($adapter+'/reconciliation.json') @{status='COMPLETED';decision=@{action='finish';rejectedClaim='claim-3'}}
        Test-Write ('coordination-team-runs/'+$id+'/originals.json') @{authority='synthetic-original-worker-binding'}
    }
    $testTrials+=@{slot=$slot;runId=$id;cases=$outputs;status='COMPLETED'}
}
Test-Write ('coordination-campaigns/'+$testCampaign+'/frozen.json') @{bundleId=$testBundle}
Test-Write ('coordination-campaigns/'+$testCampaign+'/registration.json') @{comparisonId=$testComparison}
Test-Write ('coordination-campaigns/'+$testCampaign+'/order.json') @{slots=$testSlots}
Test-Write ('coordination-campaigns/'+$testCampaign+'/plan.json') @{definition=@{workloads=@(@{cases=$testCases})}}
Test-Write ('coordination-campaigns/'+$testCampaign+'/rubric.json') @{authority='synthetic-unchanged-rubric'}
Test-Write ('coordination-campaign-executions/'+$testCampaign+'/records.json') @{trials=$testTrials}
Test-Write ('coordination-campaign-executions/'+$testCampaign+'/receipt.json') @{authority='synthetic-seal'}
foreach ($name in @('plan','registration')) { Test-Write ('coordination-comparisons/'+$testComparison+'/'+$name+'.json') @{authority='synthetic-comparison-binding'} }
foreach ($c in $testCases) { Test-Write ('coordination-inputs/'+$testBundle+'/'+$c.caseId+'/execution') @{authority='synthetic-evidence'} }
. (Join-Path $PSScriptRoot 'Prepare-CoordinationReview.ps1') -CampaignId $testCampaign -StateDirectory $testState
$testPassed=0; $testReads=0
$testValidation=@{status='FINISHED';recordedTrials=12;unrecordedTrials=0;qualityReview='NOT_PERFORMED'}
function RP-ValidateCampaign { $script:testReads++; return $script:testValidation }
function Test-Run { return (Invoke-CoordinationReviewPacket | Out-String) | ConvertFrom-Json -AsHashtable }
function Test-Pass([string]$Name,[bool]$Condition) { if (-not $Condition) {throw ('Failed control: '+$Name)}; $script:testPassed++; Write-Output ('PASS: '+$Name) }
function Test-Reject([string]$Name,[scriptblock]$Operation,[string]$Message='.') {
    $caught=$false; try { $null=& $Operation } catch { $caught=$_.Exception.Message -match $Message }
    Test-Pass $Name $caught
}
function Test-Changed([string]$Relative,[scriptblock]$Mutate,[scriptblock]$Check) {
    $path=Join-Path $testState $Relative; $bytes=[IO.File]::ReadAllBytes($path)
    try { & $Mutate $path; & $Check } finally { [IO.File]::WriteAllBytes($path,$bytes) }
}
function Test-Inventory {
    $r=[ordered]@{}; foreach ($f in (Get-ChildItem -LiteralPath $testState -Recurse -File -Force | Sort-Object FullName)) { if ($f.Name -notlike '*.lock') { $r[[IO.Path]::GetRelativePath($testState,$f.FullName)]=(Get-FileHash -LiteralPath $f.FullName).Hash } }
    return ConvertTo-Json -InputObject $r -Compress
}
$oldKey=[Environment]::GetEnvironmentVariable('OPENAI_API_KEY','Process'); [Environment]::SetEnvironmentVariable('OPENAI_API_KEY',$null,'Process')
try {
    $initial=Test-Inventory; $first=Test-Run; $ReviewPacketId=$first.packetId; $packetPrefix='coordination-review-packets/'+$ReviewPacketId
    $packet=RP-Json (RP-Path ($packetPrefix+'/packet.json'))
    $beforeFiles=$initial | ConvertFrom-Json -AsHashtable; $preserved=$true
    foreach ($entry in $beforeFiles.GetEnumerator()) { if ((Test-Hash $entry.Key).ToUpperInvariant() -cne $entry.Value) { $preserved=$false } }
    Test-Pass 'preparation preserves every previous source byte' $preserved
    Test-Pass 'all 24 exact final outputs retained without semantic approval' ($first.caseOutputs -eq 24 -and $first.qualityReview -ceq 'NOT_PERFORMED' -and @($packet.inventory.cases.units | Where-Object kind -ceq 'FINAL_DOSSIER').Count -eq 24)
    Test-Pass 'structured diagnostic scope and unresolved sections remain reviewable' (@($packet.inventory.cases.units | Where-Object kind -ceq 'STRUCTURED_DIAGNOSTIC').Count -eq 24)
    $lineage=@($packet.inventory.cases.units | Where-Object {$_.kind -cin @('ORIGINAL_CLAIM','FRESH_CLAIM')})
    Test-Pass 'original, fresh and uncited claims all retained' ($lineage.Count -eq 12 -and @($lineage|Where-Object finalCitationRelation -ceq 'NOT_CITED').Count -eq 3 -and @($lineage|Where-Object kind -ceq 'FRESH_CLAIM').Count -eq 3)
    Test-Pass 'coordinator decisions and unresolved conflicts are not discarded' (@($packet.inventory.cases.units | Where-Object {$_.kind -ceq 'TEAM_CONTEXT' -and $_.pointer -ceq '/possibleConflicts'}).Count -eq 6 -and @($packet.inventory.cases.units|Where-Object kind -ceq 'RECONCILIATION').Count -eq 6)
    Test-Pass 'captured evidence, runtime ledgers and original worker records bound' (@($packet.inventory.sourceArtifacts.Keys|Where-Object {$_ -like '*broker/*/request.json'}).Count -eq 12 -and @($packet.inventory.sourceArtifacts.Keys|Where-Object {$_ -like 'coordination-team-runs/*/originals.json'}).Count -eq 3)
    $after=Test-Inventory; $Action='read'; $read=Test-Run; $Action='prepare'; $resume=Test-Run
    Test-Pass 'credential-free read and completed prepare preserve every non-lock byte' ((Test-Inventory) -ceq $after -and $read.packetSha256 -ceq $first.packetSha256 -and $resume.packetSha256 -ceq $first.packetSha256 -and $testReads -eq 6)
    $Action='read'
    Test-Changed ($packetPrefix+'/packet.json') {[IO.File]::AppendAllText($args[0],' ')} {Test-Reject 'changed packet bytes rejected by seal' {Test-Run} 'receipt'}
    Test-Changed ($packetPrefix+'/receipt.json') {Remove-Item -LiteralPath $args[0] -Force} {Test-Reject 'incomplete seal cannot pass read' {Test-Run} 'seal'; $script:Action='prepare'; $null=Test-Run; $script:Action='read'; Test-Pass 'same packet seal recovers without replacing packet' ((Test-Run).packetSha256 -ceq $first.packetSha256)}
    foreach ($relative in @(('coordination-campaigns/'+$testCampaign+'/rubric.json'),('coordination-inputs/'+$testBundle+'/'+$testCases[0].caseId+'/execution'),('coordination-trials/'+$testTrials[0].runId+'/broker/model-test/request.json'),('coordination-team-runs/'+$testTrials[3].runId+'/originals.json'))) {
        Test-Changed $relative {[IO.File]::AppendAllText($args[0],' ')} {Test-Reject 'changed captured rubric or historical evidence rejected' {Test-Run} 'coverage'}
    }
    $firstDossier=$testStores[0]+'/'+$testTrials[0].runId+'/'+$testCases[0].caseId+'.json'
    Test-Changed $firstDossier {[IO.File]::AppendAllText($args[0],' ')} {Test-Reject 'changed final dossier bytes rejected' {Test-Run} 'output bytes'}
    $teamEvidence=$testStores[3]+'/'+$testTrials[3].runId+'/evidence.json'
    Test-Changed $teamEvidence { $e=RP-Json $args[0]; $e.originalClaims[0].inputIdentity=@{sha256=('3'*64)}; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $e -Depth 80)) } {Test-Reject 'foreign lineage input rejected' {Test-Run} 'lineage input'}
    Test-Changed $teamEvidence { $e=RP-Json $args[0]; $e.freshClaims[0].referenceId='claim-1'; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $e -Depth 80)) } {Test-Reject 'duplicate lineage identity rejected' {Test-Run} 'lineage reference'}
    Test-Changed ($packetPrefix+'/packet.json') {
        $p=RP-Json $args[0]; $p.inventory.cases[6].units=@($p.inventory.cases[6].units|Where-Object kind -cne 'ORIGINAL_CLAIM'); [IO.File]::WriteAllText($args[0],(ConvertTo-Json $p -Depth 80))
    } {Test-Reject 'removed claim units rejected even before seal comparison' {Test-Run} 'coverage'}
    $recordsPath='coordination-campaign-executions/'+$testCampaign+'/records.json'
    Test-Changed $recordsPath { $r=RP-Json $args[0]; $r.trials=$r.trials[0..10]; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $r -Depth 80)) } {Test-Reject 'missing trial cannot shrink denominator' {Test-Run} 'denominator'}
    Test-Changed $recordsPath { $r=RP-Json $args[0]; $r.trials[1].runId=$r.trials[0].runId; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $r -Depth 80)) } {Test-Reject 'reused trial identity rejected' {Test-Run} 'trial coverage'}
    Test-Changed $recordsPath { $r=RP-Json $args[0]; $r.trials[0].cases[1]=$r.trials[0].cases[0]; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $r -Depth 80)) } {Test-Reject 'duplicate cases cannot substitute for complete coverage' {Test-Run} 'case coverage'}
    Test-Changed $recordsPath {
        $r=RP-Json $args[0]; $r.trials[0].status='FAILED'; $r.trials[0].cases[0].outputIdentity=$null; $r.trials[0].cases[0].response='NOT_PROVIDED'; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $r -Depth 80))
    } {
        $path=Join-Path $testState $firstDossier; $bytes=[IO.File]::ReadAllBytes($path); Remove-Item -LiteralPath $path -Force
        try { $derived=RP-Derive; $case=$derived.cases[0]; Test-Pass 'missing output in failed trial remains an explicit ungraded case' ($derived.cases.Count -eq 24 -and $case.response -ceq 'NOT_PROVIDED' -and $case.units.Count -eq 0 -and $null -eq $case.dossierArtifact) } finally { [IO.File]::WriteAllBytes($path,$bytes) }
    }
    $abstention=$testStores[1]+'/'+$testTrials[1].runId+'/'+$testCases[0].caseId+'.json'
    Test-Changed $abstention { $d=RP-Json $args[0]; $d.response='ABSTAINED'; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $d -Depth 80)) } {
        Test-Changed $recordsPath { $r=RP-Json $args[0]; $r.trials[1].cases[0].response='ABSTAINED'; $r.trials[1].cases[0].outputIdentity.sha256=Test-Hash $abstention; [IO.File]::WriteAllText($args[0],(ConvertTo-Json $r -Depth 80)) } {
            $derived=RP-Derive; Test-Pass 'abstention explanation remains in the full review denominator' ($derived.cases.Count -eq 24 -and $derived.cases[2].response -ceq 'ABSTAINED' -and $derived.cases[2].units.Count -eq 3)
        }
    }
    $testValidation.status='PENDING'; Test-Reject 'incomplete campaign rejected before publication' {Test-Run} 'sealed complete'; $testValidation.status='FINISHED'
    $unexpected=Join-Path $testState ($packetPrefix+'/unexpected.json'); [IO.File]::WriteAllText($unexpected,'{}')
    try {Test-Reject 'unexpected packet artifacts rejected' {Test-Run} 'Unexpected'} finally {Remove-Item -LiteralPath $unexpected -Force}
    Test-Reject 'escaped source references rejected' {RP-Path '../outside.json'} 'reference'
    Test-Reject 'absolute source references rejected' {RP-Path '/outside.json'} 'reference'
    $linked=Join-Path $testState 'linked'; $linkType=if ($IsWindows) {'Junction'} else {'SymbolicLink'}
    New-Item -ItemType $linkType -Path $linked -Target (Join-Path $testState ('coordination-inputs/'+$testBundle)) | Out-Null
    try {Test-Reject 'linked evidence rejected without following it' {RP-Inventory @('linked')} 'Linked'} finally {Remove-Item -LiteralPath $linked -Force}
    $oversized=Join-Path $testState 'oversized.json'; [IO.File]::WriteAllBytes($oversized,[byte[]]::new(8388609))
    try {Test-Reject 'oversized source refused' {RP-Inventory @('oversized.json')} 'oversized'} finally {Remove-Item -LiteralPath $oversized -Force}
    $pending=Join-Path $testState ($packetPrefix+'/packet.json.pending'); [IO.File]::WriteAllText($pending,'{}')
    try {Test-Reject 'stale pending publication cannot appear complete' {Test-Run} 'incomplete'} finally {Remove-Item -LiteralPath $pending -Force}
    $script:Action='prepare'; $recoveryId='reviewpacket-'+[guid]::NewGuid().ToString(); $script:ReviewPacketId=$recoveryId; $null=Test-Run
    $recovery=RP-Path ('coordination-review-packets/'+$recoveryId+'/packet.json'); [IO.File]::Move($recovery,$recovery+'.pending')
    $recoveryReceipt=RP-Path ('coordination-review-packets/'+$recoveryId+'/receipt.json'); Remove-Item -LiteralPath $recoveryReceipt -Force
    Test-Pass 'interrupted packet publication resumes with original ID and bytes' ((Test-Run).packetId -ceq $recoveryId -and -not (Test-Path -LiteralPath ($recovery+'.pending')))
    Write-Output ("Review packet controls passed: $testPassed; all fixtures synthetic, campaign validation stubbed, no model calls.")
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY',$oldKey,'Process') }
