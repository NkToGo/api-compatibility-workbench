#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testState=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../backend/target/semantic-review-tests/'+[guid]::NewGuid().ToString('N')+'/.workbench')))
[void][IO.Directory]::CreateDirectory($testState)
if ($IsWindows) { [IO.File]::SetAttributes($testState,([IO.File]::GetAttributes($testState) -bor [IO.FileAttributes]::Hidden)) }
. (Join-Path $PSScriptRoot 'Verify-CoordinationSemanticReview.ps1') -StateDirectory $testState
function Test-Write([string]$Name,$Value) {
    $path=Join-Path $testState $Name; [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path,(ConvertTo-Json -InputObject $Value -Depth 80)+"`n",[Text.UTF8Encoding]::new($false))
}
function Test-Clone($Value) { return ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Value -Depth 80) -AsHashtable -Depth 80 }
$testSources=[ordered]@{}
Test-Write 'rubric.json' @{cases=@(@{caseId='customer';expectedResponse='ANSWER';criteria=@(@{id='outcome'})},@{caseId='subscription';expectedResponse='ANSWER';criteria=@(@{id='outcome'})})}
Test-Write 'evidence.json' @{outcome='FAILED';'escaped/key'=@{'tilde~key'='PASSED'}}
$testCases=@(); $testRows=@(); $testDossiers=@()
$testInput=@{sha256=('1'*64)}; $testRun='trial-'+[guid]::NewGuid().ToString()
Test-Write 'team.json' @{originalClaims=@(@{statement='Earlier customer claim.'},@{statement='Earlier subscription claim.'});possibleConflicts=@();coordinatorReview=@{decision=@{summary='Synthetic review summary.'}}}
foreach ($id in @('customer','subscription')) {
    $d=@{caseId=$id;inputIdentity=$testInput;response='ANSWERED';summary='Synthetic recorded failure.';summaryCitations=@('claim-1');findings=@()}
    Test-Write ($id+'.json') $d; $testDossiers+=$d
    $i=if($id -ceq 'customer'){0}else{1}
    $inventory=@(@{unitId='unit-1';kind='FINAL_DOSSIER';artifact=($id+'.json');pointer=''},@{unitId='unit-2';kind='FINAL_SUMMARY';artifact=($id+'.json');pointer='/summary'},@{unitId='unit-3';kind='ORIGINAL_CLAIM';artifact='team.json';pointer=('/originalClaims/'+$i)},@{unitId='unit-4';kind='TEAM_CONTEXT';artifact='team.json';pointer='/possibleConflicts'},@{unitId='unit-5';kind='TEAM_CONTEXT';artifact='team.json';pointer='/coordinatorReview'},@{unitId='unit-6';kind='RECONCILIATION';artifact='reconciliation.json';pointer=''})
    $output=@{sha256=(RP-Hash (Join-Path $testState ($id+'.json')))}
    $testCases+=@{position=1;runId=$testRun;slot=@{arm='COORDINATED_TEAM'};caseId=$id;inputIdentity=$testInput;outputIdentity=$output;response='ANSWERED';dossierArtifact=($id+'.json');units=$inventory}
}
Test-Write 'reconciliation.json' @{decision=@{action='finish';cases=@($testDossiers | ForEach-Object { $d=Test-Clone $_; $d.Remove('inputIdentity'); $d })}}
foreach ($file in Get-ChildItem -LiteralPath $testState -File -Force) { $testSources[$file.Name]=RP-Hash $file.FullName }
$testPacket=Test-Clone @{packetId=('reviewpacket-'+[guid]::NewGuid().ToString());inventory=@{rubricArtifact='rubric.json';sourceArtifacts=$testSources;cases=$testCases}}
$script:crCache=@{}
foreach ($source in $testPacket.inventory.cases) {
    $units=@()
    foreach ($item in $source.units) {
        $coverage=if($item.kind -cin @('FINAL_DOSSIER','RECONCILIATION')){'CONTAINER'}elseif($item.unitId -ceq 'unit-4'){'CONTEXT'}else{'CLAIMS'}
        $units+=@{unitId=$item.unitId;valueSha256=(CR-Digest (CR-Pointer (CR-Artifact $testPacket $item.artifact) $item.pointer));coverage=$coverage;coveredBy=@(if($coverage -ceq 'CONTAINER'){@('unit-2')}else{@()});notes='Synthetic boundary test; not a semantic performance measurement.';claims=@(if($coverage -ceq 'CLAIMS'){@(@{claimId='claim-1';proposition='Synthetic proposition.';verdict='SUPPORTED_WITHIN_RECORDED_SCOPE';material=$true;endorsed=$true;evidenceIds=@('captured');rationale='Synthetic recorded evidence only.'})}else{@()})}
    }
    $testRows+=@{position=$source.position;runId=$source.runId;caseId=$source.caseId;inputIdentity=$source.inputIdentity;outputIdentity=$source.outputIdentity;response=$source.response;coverageComplete=$true;criteria=@(@{criterionId='outcome';judgment='SATISFIED';rationale='Synthetic declared criterion.';unitIds=@('unit-2')});units=$units}
}
$testReview=Test-Clone @{formatVersion=1;reviewId=('semanticreview-'+[guid]::NewGuid().ToString());packetId=$testPacket.packetId;packetSha256=('2'*64);rubricSha256=$testSources['rubric.json'];reviewer=@{method='model-assisted-claim-level-inspection';label='Synthetic validator control';reviewedOn='2026-10-07';independentHumanAdjudication=$false;calibratedReviewerAccuracy=$false;newProviderCalls=0};scope='Synthetic record verification only.';evidence=@(@{evidenceId='captured';artifact='evidence.json';pointer='/outcome';sha256=$testSources['evidence.json'];valueSha256=(CR-Digest 'FAILED')});cases=$testRows}
$testPassed=0
function Test-Pass([string]$Name,[bool]$Condition) { if(-not $Condition){throw ('Failed control: '+$Name)}; $script:testPassed++; Write-Output ('PASS: '+$Name) }
function Test-Reject([string]$Name,[scriptblock]$Change,[string]$Expected) {
    $candidate=Test-Clone $testReview; & $Change $candidate
    $caught=$false; try {$null=CR-Validate $candidate $testPacket} catch {$caught=$_.Exception.Message -match $Expected}
    Test-Pass $Name $caught
}
$accepted=CR-Validate $testReview $testPacket
Test-Pass 'complete bound review retains both accepted synthetic cases' (@($accepted.cases | Where-Object {$_.acceptedUnderRecordedReview}).Count -eq 2 -and $accepted.reviewUnits -eq 12)
Test-Pass 'repeated verification preserves source bytes' ((RP-Hash (Join-Path $testState 'evidence.json')) -ceq $testSources['evidence.json'])
foreach ($verdict in @('UNSUPPORTED','UNRESOLVED')) {
    $candidate=Test-Clone $testReview; $candidate.cases[0].units[1].claims[0].verdict=$verdict
    $result=CR-Validate $candidate $testPacket
    Test-Pass ($verdict+' material final claim blocks acceptance without rewriting its judgment') (-not $result.cases[0].acceptedUnderRecordedReview -and $candidate.cases[0].units[1].claims[0].verdict -ceq $verdict)
}
$candidate=Test-Clone $testReview; $candidate.cases[0].units[2].claims[0].verdict='UNSUPPORTED'; $candidate.cases[0].units[2].claims[0].endorsed=$false; $result=CR-Validate $candidate $testPacket
Test-Pass 'rejected earlier claim remains in history without poisoning a supported final answer' ($result.historicalUnsupportedClaims -eq 1 -and $result.cases[0].acceptedUnderRecordedReview)
$candidate=Test-Clone $testReview; $candidate.cases[0].units[2].claims[0].verdict='UNSUPPORTED'; $result=CR-Validate $candidate $testPacket
Test-Pass 'endorsed earlier unsupported claim blocks acceptance' (-not $result.cases[0].acceptedUnderRecordedReview -and $result.cases[0].materialUnsupportedClaims -eq 1)
foreach($judgment in @('NOT_SATISFIED','UNRESOLVED')) {
    $candidate=Test-Clone $testReview; $candidate.cases[0].criteria[0].judgment=$judgment; $result=CR-Validate $candidate $testPacket
    Test-Pass ($judgment+' criterion is retained and blocks acceptance') (-not $result.cases[0].acceptedUnderRecordedReview)
}
Test-Reject 'missing case stays missing' {param($r) $r.cases=@($r.cases[0])} 'case denominator'
Test-Reject 'duplicate case cannot replace another case' {param($r) $r.cases[1]=Test-Clone $r.cases[0]} 'duplicate reviewed case'
Test-Reject 'changed input identity rejected' {param($r) $r.cases[0].inputIdentity.sha256='0'*64} 'Changed case binding'
Test-Reject 'changed output identity rejected' {param($r) $r.cases[0].outputIdentity.sha256='0'*64} 'Changed case binding'
Test-Reject 'changed disposition rejected' {param($r) $r.cases[0].response='ABSTAINED'} 'Changed case binding'
Test-Reject 'pending coverage rejected' {param($r) $r.cases[0].coverageComplete=$false} 'pending review coverage'
Test-Reject 'missing original claim unit rejected' {param($r) $r.cases[0].units=@($r.cases[0].units | Where-Object {$_.unitId -cne 'unit-3'})} 'unit coverage'
Test-Reject 'duplicate reviewed unit rejected' {param($r) $r.cases[0].units[2]=Test-Clone $r.cases[0].units[1]} 'Duplicate or malformed'
Test-Reject 'changed claim value rejected' {param($r) $r.cases[0].units[1].valueSha256='0'*64} 'Changed reviewed claim value'
Test-Reject 'material original claim cannot become context' {param($r) $r.cases[0].units[2].coverage='CONTEXT';$r.cases[0].units[2].claims=@()} 'cannot be excluded'
Test-Reject 'final claim cannot lose materiality' {param($r) $r.cases[0].units[1].claims[0].material=$false} 'nonmaterial metadata'
Test-Reject 'final unresolved claim cannot be unendorsed' {param($r) $r.cases[0].units[1].claims[0].verdict='UNRESOLVED';$r.cases[0].units[1].claims[0].endorsed=$false} 'excluded from acceptance'
Test-Reject 'unknown claim verdict rejected' {param($r) $r.cases[0].units[1].claims[0].verdict='VALID'} 'claim judgment'
Test-Reject 'empty rationale rejected' {param($r) $r.cases[0].units[1].claims[0].rationale=''} 'claim judgment'
Test-Reject 'invented evidence reference rejected' {param($r) $r.cases[0].units[1].claims[0].evidenceIds=@('invented')} 'evidence reference'
Test-Reject 'changed evidence pointer value rejected' {param($r) $r.evidence[0].valueSha256='0'*64} 'pointer value'
Test-Reject 'foreign evidence artifact rejected' {param($r) $r.evidence[0].artifact='unregistered.json'} 'evidence binding'
Test-Reject 'absolute evidence path rejected' {param($r) $r.evidence[0].artifact=[IO.Path]::GetFullPath((Join-Path $testState 'evidence.json'))} 'evidence binding'
Test-Reject 'missing criterion rejected' {param($r) $r.cases[0].criteria=@()} 'criterion review'
Test-Reject 'invented criterion rejected' {param($r) $r.cases[0].criteria[0].criterionId='invented'} 'criterion judgment'
Test-Reject 'foreign criterion unit rejected' {param($r) $r.cases[0].criteria[0].unitIds=@('unit-99')} 'criterion unit'
Test-Reject 'container cannot omit final claim coverage' {param($r) $r.cases[0].units[0].coveredBy=@()} 'container coverage'
Test-Reject 'container cannot double count final coverage' {param($r) $r.cases[0].units[0].coveredBy=@('unit-2','unit-2')} 'container coverage'
Test-Reject 'changed frozen rubric rejected' {param($r) $r.rubricSha256='0'*64} 'review rubric'
Test-Reject 'human adjudication cannot be invented' {param($r) $r.reviewer.independentHumanAdjudication=$true} 'reviewer provenance'
Test-Reject 'calibrated accuracy cannot be invented' {param($r) $r.reviewer.calibratedReviewerAccuracy=$true} 'reviewer provenance'
Test-Reject 'unknown review fields rejected' {param($r) $r['accepted']=$true} 'Unexpected or missing review fields'
Test-Pass 'RFC 6901 escaped members resolve exactly' ((CR-Pointer @{ 'escaped/key'=@{'tilde~key'='PASSED'} } '/escaped~1key/tilde~0key') -ceq 'PASSED')
foreach ($pointer in @('/a~2b','/00','/-')) {
    $caught=$false;try{$null=CR-Pointer @('a') $pointer}catch{$caught=$true};Test-Pass ('invalid array/escape pointer rejected: '+$pointer) $caught
}
$original=[IO.File]::ReadAllBytes((Join-Path $testState 'evidence.json'))
try { Test-Write 'evidence.json' @{outcome='PASSED'}; $caught=$false;try{$null=CR-Validate $testReview $testPacket}catch{$caught=$_.Exception.Message -match 'Changed reviewed evidence'};Test-Pass 'changed captured source bytes rejected' $caught } finally {[IO.File]::WriteAllBytes((Join-Path $testState 'evidence.json'),$original)}
$changedPacket=Test-Clone $testPacket; $original=[IO.File]::ReadAllBytes((Join-Path $testState 'reconciliation.json'))
try {
    $terminal=RP-Json (Join-Path $testState 'reconciliation.json');$terminal.decision.cases[0].summary='Different answer.';Test-Write 'reconciliation.json' $terminal
    $changedPacket.inventory.sourceArtifacts['reconciliation.json']=RP-Hash (Join-Path $testState 'reconciliation.json')
    $candidate=Test-Clone $testReview;foreach($r in $candidate.cases){$r.units[5].valueSha256=CR-Digest $terminal}
    $caught=$false;try{$null=CR-Validate $candidate $changedPacket}catch{$caught=$_.Exception.Message -match 'differs from reviewed final dossier'};Test-Pass 'reconciliation cannot hide an additional unreviewed answer' $caught
} finally {[IO.File]::WriteAllBytes((Join-Path $testState 'reconciliation.json'),$original)}
$original=[IO.File]::ReadAllBytes((Join-Path $testState 'team.json'))
try {
    $team=RP-Json (Join-Path $testState 'team.json');$team.possibleConflicts=@(@{claimIds=@('claim-1');state='UNRESOLVED'});Test-Write 'team.json' $team
    $changedPacket=Test-Clone $testPacket;$changedPacket.inventory.sourceArtifacts['team.json']=RP-Hash (Join-Path $testState 'team.json')
    $candidate=Test-Clone $testReview;foreach($r in $candidate.cases){foreach($idx in @(2,3,4)){$r.units[$idx].valueSha256=CR-Digest (CR-Pointer $team $changedPacket.inventory.cases[0].units[$idx].pointer)}}
    $caught=$false;try{$null=CR-Validate $candidate $changedPacket}catch{$caught=$_.Exception.Message -match 'cannot be excluded'};Test-Pass 'nonempty conflict cannot be excluded as context' $caught
} finally {[IO.File]::WriteAllBytes((Join-Path $testState 'team.json'),$original)}
$original=[IO.File]::ReadAllBytes((Join-Path $testState 'customer.json'))
foreach ($extra in @('newClaim','selectedDecision')) {
    try {
        $dossier=RP-Json (Join-Path $testState 'customer.json');$dossier[$extra]=@{summary='An unreviewed assertion.'};Test-Write 'customer.json' $dossier
        $changedPacket=Test-Clone $testPacket;$changedPacket.inventory.sourceArtifacts['customer.json']=RP-Hash (Join-Path $testState 'customer.json')
        $candidate=Test-Clone $testReview;$candidate.cases[0].units[0].valueSha256=CR-Digest $dossier
        $caught=$false;try{$null=CR-Validate $candidate $changedPacket}catch{$caught=$_.Exception.Message -match 'Uncovered dossier|Selection metadata'};Test-Pass ('new material cannot hide in container metadata: '+$extra) $caught
    } finally {[IO.File]::WriteAllBytes((Join-Path $testState 'customer.json'),$original)}
}
$linkedDirectory=Join-Path $testState 'linked'; $linkKind=if($IsWindows){'Junction'}else{'SymbolicLink'}
$null=New-Item -ItemType $linkKind -Path $linkedDirectory -Target $testState
try {
    $caught=$false;try{$null=RP-Path 'linked/evidence.json'}catch{$caught=$_.Exception.Message -match 'Linked review paths'};Test-Pass 'linked artifact directory rejected on this operating system' $caught
} finally { [IO.Directory]::Delete($linkedDirectory) }
$ReviewPath=Join-Path $testState 'review.json'
Test-Write ('coordination-review-packets/'+$testPacket.packetId+'/packet.json') $testPacket
Test-Write 'review.json' $testReview
$caught=$false;try{$null=Invoke-CoordinationSemanticReview}catch{$caught=$_.Exception.Message -match 'Changed reviewed packet bytes'}
Test-Pass 'command rejects a changed exact packet seal before campaign inspection' $caught
[IO.File]::WriteAllBytes($ReviewPath,[byte[]]::new(1048577))
$caught=$false;try{$null=Invoke-CoordinationSemanticReview}catch{$caught=$_.Exception.Message -match 'oversized review artifact'}
Test-Pass 'command refuses review input above 1 MiB before parsing or dispatch' $caught
[ordered]@{status='PASSED';checks=$testPassed;providerCalls=0;scope='Synthetic review-integrity and acceptance-gate controls; not semantic accuracy.'} | ConvertTo-Json
