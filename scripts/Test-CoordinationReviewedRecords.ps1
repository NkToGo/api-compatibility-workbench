#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testState=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../backend/target/reviewed-record-tests/'+[guid]::NewGuid().ToString('N')+'/.workbench')))
[void][IO.Directory]::CreateDirectory($testState)
if($IsWindows){[IO.File]::SetAttributes($testState,([IO.File]::GetAttributes($testState) -bor [IO.FileAttributes]::Hidden))}
. (Join-Path $PSScriptRoot 'Export-CoordinationReviewedRecords.ps1') -ReviewPath unused -RecordsPath unused -StateDirectory $testState
function Test-Clone($Value){return ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Value -Depth 80) -AsHashtable -Depth 80}
$testPassed=0
function Test-Pass([string]$Name,[bool]$Condition){if(-not $Condition){throw ('Failed control: '+$Name)}; $script:testPassed++; Write-Output ('PASS: '+$Name)}
$slot=@{workloadId='synthetic';arm='SINGLE_AGENT';repetition=1}; $run='synthetic-run'; $input=@{sha256=('1'*64)}; $output=@{sha256=('2'*64)}
$original=Test-Clone @{formatVersion=1;trials=@(@{slot=$slot;runId=$run;definitionIdentity=@{sha256=('3'*64)};status='COMPLETED';elapsedMillis=100;
    usage=@{reservedCalls=1;completedResponses=1;inputTokens=100;outputTokens=10};cases=@(@{caseId='customer';inputIdentity=$input;outputIdentity=$output;response='ANSWERED';review=$null},@{caseId='subscription';inputIdentity=$input;outputIdentity=$output;response='ANSWERED';review=$null})})}
$packet=Test-Clone @{packetId=('reviewpacket-'+[guid]::NewGuid().ToString());inventory=@{cases=@(@{position=1;runId=$run;slot=$slot;caseId='customer';inputIdentity=$input;outputIdentity=$output;response='ANSWERED'},@{position=1;runId=$run;slot=$slot;caseId='subscription';inputIdentity=$input;outputIdentity=$output;response='ANSWERED'})}}
$verified=Test-Clone @{status='SEMANTIC_REVIEW_BINDINGS_MATCHED';packetId=$packet.packetId;reviewSha256=('4'*64);caseOutputs=2;cases=@(
    @{position=1;caseId='customer';arm='SINGLE_AGENT';materialUnsupportedClaims=2;materialUnresolvedClaims=0;criterionJudgments=@(@{id='outcome';judgment='NOT_SATISFIED'})},
    @{position=1;caseId='subscription';arm='SINGLE_AGENT';materialUnsupportedClaims=0;materialUnresolvedClaims=1;criterionJudgments=@(@{id='outcome';judgment='SATISFIED'})})}
function Test-Reject([string]$Name,[scriptblock]$Change,[string]$Expected){
    $o=Test-Clone $original; $p=Test-Clone $packet; $v=Test-Clone $verified; & $Change $o $p $v
    $caught=$false; try{$null=CCR-Derive $o $p $v}catch{$caught=$_.Exception.Message -match $Expected}; Test-Pass $Name $caught
}
$before=ConvertTo-Json -InputObject $original -Depth 80 -Compress; $exported=CCR-Derive $original $packet $verified
Test-Pass 'v2 retains explicit unsupported and unresolved counts without changing original records' ($exported.formatVersion -eq 2 -and $exported.trials[0].cases[0].review.materialUnsupportedClaims -eq 2 -and $exported.trials[0].cases[1].review.materialUnresolvedClaims -eq 1 -and $before -ceq (ConvertTo-Json -InputObject $original -Depth 80 -Compress))
Test-Pass 'unresolved claims remain separate from completed coverage and satisfied criteria' ($exported.trials[0].cases[1].review.claimAuditComplete -and $exported.trials[0].cases[1].review.judgments.outcome -ceq 'SATISFIED' -and $exported.trials[0].cases[1].review.materialUnsupportedClaims -eq 0)
Test-Pass 'exact reviewed artifact identity is exported for every case' (@($exported.trials[0].cases | Where-Object {$_.review.reviewIdentity.sha256 -ceq $verified.reviewSha256}).Count -eq 2)
Test-Reject 'missing reviewed case rejected' {param($o,$p,$v) $v.cases=@($v.cases[0])} 'denominator'
Test-Reject 'duplicate reviewed case rejected' {param($o,$p,$v) $v.cases[1]=Test-Clone $v.cases[0]} 'Duplicate, missing'
Test-Reject 'missing original case rejected' {param($o,$p,$v) $o.trials[0].cases=@($o.trials[0].cases[0])} 'missing planned'
Test-Reject 'duplicate original case rejected' {param($o,$p,$v) $o.trials[0].cases[1]=Test-Clone $o.trials[0].cases[0]} 'Duplicate, missing'
Test-Reject 'changed run rejected' {param($o,$p,$v) $o.trials[0].runId='other-run'} 'binding'
Test-Reject 'changed repetition rejected' {param($o,$p,$v) $o.trials[0].slot.repetition=2} 'binding'
Test-Reject 'changed input rejected' {param($o,$p,$v) $o.trials[0].cases[0].inputIdentity.sha256='5'*64} 'binding'
Test-Reject 'changed output rejected' {param($o,$p,$v) $o.trials[0].cases[0].outputIdentity.sha256='5'*64} 'binding'
Test-Reject 'changed disposition rejected' {param($o,$p,$v) $o.trials[0].cases[0].response='ABSTAINED'} 'binding'
Test-Reject 'wrong reviewed arm rejected' {param($o,$p,$v) $v.cases[0].arm='COORDINATED_TEAM'} 'binding'
Test-Reject 'already reviewed record cannot be overwritten' {param($o,$p,$v) $o.trials[0].cases[0].review=@{claimAuditComplete=$true}} 'already reviewed'
Test-Reject 'unvalidated review summary rejected' {param($o,$p,$v) $v.status='PENDING'} 'validated review'
Test-Reject 'different sealed packet rejected' {param($o,$p,$v) $v.packetId='reviewpacket-'+[guid]::NewGuid().ToString()} 'validated review'
Test-Reject 'missing unresolved count cannot become zero' {param($o,$p,$v) $v.cases[1].Remove('materialUnresolvedClaims')} 'explicit claim counts'
Test-Reject 'negative unresolved count rejected' {param($o,$p,$v) $v.cases[1].materialUnresolvedClaims=[long]-1} 'explicit claim counts'
Test-Reject 'overflowing unresolved count rejected' {param($o,$p,$v) $v.cases[1].materialUnresolvedClaims=[long]100001} 'explicit claim counts'
Test-Reject 'criterion cannot silently lose its judgment' {param($o,$p,$v) $v.cases[1].criterionJudgments[0].judgment='UNKNOWN'} 'criterion judgment'
Test-Reject 'duplicate criterion rejected' {param($o,$p,$v) $v.cases[1].criterionJudgments+=Test-Clone $v.cases[1].criterionJudgments[0]} 'criterion judgment'
Test-Reject 'no criteria rejected' {param($o,$p,$v) $v.cases[1].criterionJudgments=@()} 'Missing exported criteria'
$path=Join-Path $testState 'records.json'; CCR-PublishOrMatch $path $exported $false; $hash=RP-Hash $path
Test-Pass 'published UTF-8 JSON uses portable LF bytes' (-not ([Text.Encoding]::UTF8.GetString((RP-Bytes $path))).Contains("`r"))
CCR-PublishOrMatch $path $exported $true; CCR-PublishOrMatch $path $exported $false
Test-Pass 'read and repeated export preserve exact published bytes' ((RP-Hash $path) -ceq $hash)
$changed=Test-Clone $exported; $changed.trials[0].elapsedMillis=1
$caught=$false; try{CCR-PublishOrMatch $path $changed $false}catch{$caught=$_.Exception.Message -match 'Changed reviewed export'}
Test-Pass 'changed completed export cannot be rewritten' $caught
$pending=Join-Path $testState 'pending.json'; $caught=$false; try{CCR-PublishOrMatch $pending $exported $true}catch{$caught=$_.Exception.Message -match 'Incomplete reviewed export'}
Test-Pass 'read refuses an incomplete export' $caught
[IO.File]::WriteAllBytes(($pending+'.pending'),(CCR-Bytes $exported))
CCR-PublishOrMatch $pending $exported $false
Test-Pass 'same export resumes interrupted create-only publication' ((RP-Hash $pending) -ceq $hash -and -not (Test-Path -LiteralPath ($pending+'.pending')))
. (Join-Path $PSScriptRoot 'Export-CoordinationReviewedRecords.ps1') -Action read -ReviewPath unused -RecordsPath unused -StateDirectory $testState
Test-Pass 'read mode survives nested helper imports' ($Action -ceq 'read')
[ordered]@{status='PASSED';checks=$testPassed;modelCalls=0;consumerExecutions=0} | ConvertTo-Json
