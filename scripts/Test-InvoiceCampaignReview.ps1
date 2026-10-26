#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../backend/target/invoice-campaign-review-tests/'+[guid]::NewGuid().ToString('N'))))
$testEvidence=Join-Path $testRoot 'evidence'; $testState=Join-Path $testRoot 'state'
[void][IO.Directory]::CreateDirectory($testEvidence); [void][IO.Directory]::CreateDirectory($testState)
if($IsWindows) { [IO.File]::SetAttributes($testState,([IO.File]::GetAttributes($testState) -bor [IO.FileAttributes]::Hidden)) }
$testCampaign='invoicecampaign-'+[guid]::NewGuid().ToString(); $testComparison='comparison-'+[guid]::NewGuid().ToString()
function Test-Write([string]$Relative,$Value) {
    $file=Join-Path $testRoot $Relative; [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($file))
    [IO.File]::WriteAllText($file,(ConvertTo-Json -InputObject $Value -Depth 80)+"`n",[Text.UTF8Encoding]::new($false))
}
function Test-Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
$arms=@('DETERMINISTIC','SINGLE_AGENT','INDEPENDENT_WORKERS','COORDINATED_TEAM')
$stores=@{DETERMINISTIC='invoice-diagnostics';SINGLE_AGENT='invoice-single-agent';INDEPENDENT_WORKERS='invoice-independent-workers';COORDINATED_TEAM='invoice-team-comparisons'}
$testTrials=@(); $testOrder=@(); $testSourceRefs=@('evidence',('state/invoice-campaigns/'+$testCampaign),('state/invoice-campaign-executions/'+$testCampaign),('state/coordination-comparisons/'+$testComparison))
for($index=0;$index -lt 12;$index++) {
    $rep=1+[int][Math]::Floor($index/4); $arm=$arms[($index%4+$rep-1)%4]; $id='trial-'+[guid]::NewGuid().ToString()
    $slot=@{workloadId='invoice-joint';repetition=$rep;arm=$arm}; $testOrder+=$slot
    $trial=@{runId=$id;slot=$slot;status=$(if($arm -ceq 'SINGLE_AGENT'){'UNKNOWN_REMOTE_OUTCOME'}elseif($arm -ceq 'DETERMINISTIC'){'COMPLETED'}else{'FAILED'});cases=@(@{caseId='stripe-invoice-subscription-2025-03-31';inputIdentity=@{sha256=('1'*64)};response=$(if($arm -ceq 'SINGLE_AGENT' -or $arm -ceq 'INDEPENDENT_WORKERS'){'NOT_PROVIDED'}else{'ABSTAINED'});review=$null})}
    $testTrials+=$trial; $adapter='state/'+$stores[$arm]+'/'+$id; $testSourceRefs+=$adapter
    Test-Write ($adapter+'/result.json') @{trial=$trial;qualityReview='NOT_PERFORMED'}
    Test-Write ($adapter+'/response.json') @{statement='Synthetic retained response; not a measured claim.'}
    Test-Write ('state/invoice-campaign-executions/'+$testCampaign+'/slots/'+('{0:00}' -f ($index+1))+'-outcome.json') @{trial=$trial;startedAt=[DateTimeOffset]::UtcNow.ToString('o');adapterResultSha256=(Test-Hash (Join-Path $testRoot ($adapter+'/result.json')))}
}
$campaign='state/invoice-campaigns/'+$testCampaign; $execution='state/invoice-campaign-executions/'+$testCampaign
Test-Write ($campaign+'/frozen.json') @{frozenAt=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o');order=$testOrder;selection=@{inputDirectory='inputs';inputIdentity=@{sha256=('1'*64)};contractDirectory='contract';contractIdentity=@{sha256=('2'*64)};rubricArtifact='rubric.json';rubricIdentity=@{sha256=('3'*64)}}}
Test-Write ($campaign+'/registration.json') @{comparisonId=$testComparison}
Test-Write ($campaign+'/receipt.json') @{authority='synthetic independently selected receipt'}
Test-Write ($execution+'/records.json') @{trials=$testTrials}
foreach($file in @('plan.json','registration.json')) { Test-Write ('state/coordination-comparisons/'+$testComparison+'/'+$file) @{authority='synthetic comparison metadata'} }
Test-Write 'evidence/inputs/manifest.json' @{authority='synthetic evidence'}
Test-Write 'evidence/contract/provenance.json' @{authority='synthetic contract'}
Test-Write 'evidence/rubric.json' @{authority='synthetic rubric'}
$expected=Test-Hash (Join-Path $testRoot ($campaign+'/receipt.json'))
. (Join-Path $PSScriptRoot 'Prepare-InvoiceCampaignReview.ps1') -CampaignId $testCampaign -ExpectedReceiptSha256 $expected -ReviewEvidenceDirectory $testRoot -EvidenceDirectory $testEvidence -StateDirectory $testState
$script:testPhase='FINISHED'; $script:testInspectionFault=$false; $script:testMutation=$null; $script:testChecks=0
function ICR-ValidateCampaign {
    if($script:testMutation) { & $script:testMutation; $script:testMutation=$null }
    return @{status=$script:testPhase;recordedTrials=12;unrecordedTrials=0;qualityReview='NOT_PERFORMED';slots=@($testTrials | ForEach-Object { @{runId=$_.runId} })}
}
function ICR-Inspect([string]$SelectionPath) {
    $s=ICR-Json $SelectionPath; $rows=@(); $roots=@($s.inputDirectory,$s.contractDirectory,$s.rubricArtifact)
    foreach($source in $s.sources) {
        $ref=$source.stateDirectory+'/'+$stores[$source.arm]+'/'+$source.runId; $roots+=$ref
        $rows+=@{position=$rows.Count+1;source=$source;trial=(ICR-Json (ICR-Path ($ref+'/result.json'))).trial;units=@(@{unitId='unit-1';kind='TRIAL_OUTCOME';artifact=$ref+'/result.json';pointer='/trial';valueIdentity=@{sha256=('4'*64)}})}
    }
    if($script:testInspectionFault) { $rows=$rows[0..2] }
    return @{criteriaTiming='NOT_INFERRED_FROM_SELECTION';inputIdentity=$s.inputIdentity;contractIdentity=$s.contractIdentity;rubricIdentity=$s.rubricIdentity;trials=$rows;sourceArtifacts=(ICR-Inventory $roots)}
}
function Test-Pass([string]$Label,[bool]$Value) { if(-not $Value) { throw ('FAIL: '+$Label) }; $script:testChecks++; Write-Output ('PASS: '+$Label) }
function Test-Reject([string]$Label,[scriptblock]$Operation) { $caught=$false; try { & $Operation > $null } catch { $caught=$true }; Test-Pass $Label $caught }
function Test-Invoke { (Invoke-InvoiceCampaignReview | Out-String) | ConvertFrom-Json -AsHashtable }
function Test-Altered([string]$Path,[scriptblock]$Operation) { $bytes=[IO.File]::ReadAllBytes($Path); try { & $Operation } finally { [IO.File]::WriteAllBytes($Path,$bytes) } }
$before=ICR-Inventory $testSourceRefs
Test-Pass 'case-distinct JSON keys cannot be collapsed by canonical comparison' (-not (ICR-Same (ICR-Parse '{"a":1,"A":2}') (ICR-Parse '{"a":1}')))
$ReviewPacketId='reviewpacket-'+[guid]::NewGuid().ToString(); $ExpectedPacketSha256='e'*64; $Action='read'
Test-Reject 'absent read creates no packet store' { Test-Invoke }
Test-Pass 'absent inspection remains read only' (-not (Test-Path -LiteralPath (Join-Path $testState 'invoice-campaign-review-packets')))
Remove-Variable ExpectedPacketSha256
$Action='prepare'; $ExpectedPacketSha256=$null; $script:testPhase='PENDING'; Test-Reject 'unsealed campaign cannot prepare' { Test-Invoke }; $script:testPhase='FINISHED'
$summary=Test-Invoke; $directory=Join-Path $testState ('invoice-campaign-review-packets/'+$ReviewPacketId); $packetPath=Join-Path $directory 'packet.json'; $receiptPath=Join-Path $directory 'receipt.json'
$packet=ICR-Json $packetPath; $packetHash=Test-Hash $packetPath
$ExpectedPacketSha256=$packetHash
Test-Pass 'all twelve rotated slots preserve failures uncertainty and missing answers' ($summary.trials -eq 12 -and $summary.participantCallsInitiatedByThisCommand -eq 0 -and $summary.qualityReview -ceq 'NOT_PERFORMED' -and (ICR-Same @($packet.inventory.trials.trial.runId) @($testTrials.runId)))
Test-Pass 'frozen timing is established separately from transient inventories' ($summary.criteriaTiming -ceq 'FROZEN_BEFORE_TRIALS')
$Action='read'; $again=Test-Invoke; $Action='prepare'; $resumed=Test-Invoke
Test-Pass 'completed prepare and read preserve packet bytes' ($again.packetSha256 -ceq $packetHash -and $resumed.packetSha256 -ceq $packetHash)
$Action='read'; $savedReceipt=[IO.File]::ReadAllBytes($receiptPath); [IO.File]::Delete($receiptPath)
Test-Reject 'missing receipt is not accepted by read' { Test-Invoke }; $Action='prepare'; $null=Test-Invoke
Test-Pass 'missing seal recovery preserves original packet' ((Test-Hash $packetPath) -ceq $packetHash)
[IO.File]::Delete($receiptPath); [IO.File]::WriteAllBytes(($receiptPath+'.pending'),$savedReceipt); $null=Test-Invoke
Test-Pass 'matching pending receipt recovers exactly' ((Test-Hash $packetPath) -ceq $packetHash)
$selectionPath=Join-Path $directory 'selection-2.json'; [IO.File]::Move($selectionPath,($selectionPath+'.pending')); $Action='read'
Test-Reject 'pending selection blocks read' { Test-Invoke }; $Action='prepare'; $null=Test-Invoke
Test-Pass 'matching selection recovery preserves all bindings' ((Test-Hash $packetPath) -ceq $packetHash)
$Action='read'
Test-Altered $selectionPath { [IO.File]::AppendAllText($selectionPath,' '); Test-Reject 'selection byte changes invalidate sealed packet' { Test-Invoke } }
Test-Altered $packetPath { $p=ICR-Json $packetPath; $p.inventory.trials=$p.inventory.trials[0..10]; [IO.File]::WriteAllText($packetPath,($p | ConvertTo-Json -Depth 80)); Test-Reject 'shrinking the denominator is rejected' { Test-Invoke } }
Test-Altered $packetPath { $p=ICR-Json $packetPath; $p.inventory.trials[0].units[0].pointer='/different'; [IO.File]::WriteAllText($packetPath,($p | ConvertTo-Json -Depth 80)); Test-Reject 'altered unit pointer is rejected even with a resealed receipt' { Test-Altered $receiptPath { [IO.File]::WriteAllText($receiptPath,(@{formatVersion=1;packetSha256=(Test-Hash $packetPath)} | ConvertTo-Json)); Test-Invoke } } }
Test-Altered $packetPath { $p=ICR-Json $packetPath; $p.qualityReview='PERFORMED'; [IO.File]::WriteAllText($packetPath,($p | ConvertTo-Json -Depth 80)); Test-Reject 'quality judgment cannot be supplied as input binding' { Test-Invoke } }
Test-Altered $packetPath { [IO.File]::WriteAllText($packetPath,'{"formatVersion":1,"formatVersion":1}'); Test-Reject 'duplicate JSON fields are rejected' { Test-Invoke } }
Test-Altered $packetPath { [IO.File]::WriteAllBytes($packetPath,[byte[]]@(0xc3,0x28)); Test-Reject 'invalid UTF8 is rejected' { Test-Invoke } }
Test-Altered $packetPath { [IO.File]::WriteAllBytes($packetPath,[byte[]]::new(2097153)); Test-Reject 'oversized packet is refused' { Test-Invoke } }
$recordPath=Join-Path $testRoot ($execution+'/records.json')
Test-Altered $recordPath { $r=ICR-Json $recordPath; $r.trials[0].runId=$r.trials[1].runId; [IO.File]::WriteAllText($recordPath,($r | ConvertTo-Json -Depth 80)); Test-Reject 'substituted campaign trial is refused' { Test-Invoke } }
$ExpectedReceiptSha256='f'*64; Test-Reject 'wrong independently expected campaign receipt is refused' { Test-Invoke }; $ExpectedReceiptSha256=$expected
$ExpectedPacketSha256='f'*64; Test-Reject 'wrong independently expected packet identity is refused' { Test-Invoke }; $ExpectedPacketSha256=$packetHash
Test-Altered (Join-Path $testRoot ($campaign+'/frozen.json')) { $f=ICR-Json (Join-Path $testRoot ($campaign+'/frozen.json')); $f.frozenAt=[DateTimeOffset]::UtcNow.AddHours(1).ToString('o'); [IO.File]::WriteAllText((Join-Path $testRoot ($campaign+'/frozen.json')),($f | ConvertTo-Json -Depth 80)); Test-Reject 'criteria declared after execution cannot claim frozen timing' { Test-Invoke } }
$script:testInspectionFault=$true; Test-Reject 'incomplete selected-arm inventory is refused' { Test-Invoke }; $script:testInspectionFault=$false
Test-Reject 'escaped artifact references are refused' { ICR-Path '../outside' }
$pending=Join-Path $directory 'packet.json.pending'; [IO.File]::WriteAllText($pending,'conflicting interrupted bytes')
Test-Reject 'conflicting pending publication is preserved and rejected' { Test-Invoke }; [IO.File]::Delete($pending)
$extra=Join-Path $directory 'extra.json'; [IO.File]::WriteAllText($extra,'{}'); Test-Reject 'unknown packet artifacts are rejected' { Test-Invoke }; [IO.File]::Delete($extra)
$lock=[IO.File]::Open((Join-Path $directory 'owner.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try { Test-Reject 'concurrent packet owner is refused' { Test-Invoke } } finally { $lock.Dispose() }
$sourcePath=Join-Path $testRoot 'evidence/inputs/manifest.json'
Test-Altered $sourcePath { [IO.File]::AppendAllText($sourcePath,' '); Test-Reject 'changed retained source invalidates packet' { Test-Invoke } }
$target=Join-Path $testRoot 'link-target'; [void][IO.Directory]::CreateDirectory($target); $link=Join-Path $testEvidence 'inputs/linked'
if($IsWindows) { $null=New-Item -ItemType Junction -Path $link -Target $target } else { $null=New-Item -ItemType SymbolicLink -Path $link -Target $target }
try { Test-Reject 'linked source directories are refused' { Test-Invoke } } finally { Remove-Item -LiteralPath $link -Force }
Test-Pass 'all original source bytes remain unchanged' (ICR-Same $before (ICR-Inventory $testSourceRefs))
Write-Output ('Invoice campaign review checks passed: '+$script:testChecks+'; synthetic controller/inspector fixtures only, no model calls or quality grading.')
