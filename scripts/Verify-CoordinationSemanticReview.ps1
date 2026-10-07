#requires -Version 7.0
[CmdletBinding()]
param([string]$ReviewPath, [string]$StateDirectory)
$ErrorActionPreference='Stop'
$crRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $ReviewPath) { $ReviewPath=Join-Path $crRoot 'docs/evaluations/coordination-review-2026-10-07.json' }
if (-not $StateDirectory) { $StateDirectory=Join-Path $crRoot '.workbench' }
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
. (Join-Path $PSScriptRoot 'Prepare-CoordinationReview.ps1') -CampaignId ('campaign-'+[guid]::Empty) -StateDirectory $StateDirectory

function CR-Text($Value) { return $Value -is [string] -and -not [string]::IsNullOrWhiteSpace($Value) -and $Value.Length -le 16000 }
function CR-Digest($Value) {
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject (RP-Canonical $Value) -Depth 80 -Compress))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}
function CR-Pointer($Value,[string]$Pointer) {
    RP-Require (($Pointer -ceq '') -or ($Pointer.StartsWith('/') -and $Pointer -notmatch '~(?![01])')) 'Invalid review JSON pointer.'
    if ($Pointer -ceq '') { return ,$Value }
    foreach ($part in $Pointer.Substring(1).Split('/')) {
        $key=$part.Replace('~1','/').Replace('~0','~')
        if ($Value -is [Collections.IDictionary]) {
            RP-Require (@($Value.Keys | Where-Object {$_ -ceq $key}).Count -eq 1) 'Missing review pointer member.'
            $Value=$Value[$key]
        } elseif ($Value -is [Collections.IList]) {
            RP-Require ($key -cmatch '^(0|[1-9][0-9]{0,7})$' -and [long]$key -lt $Value.Count) 'Invalid review pointer index.'
            $Value=$Value[[int]$key]
        } else { throw 'Review pointer crosses a scalar.' }
    }
    return ,$Value
}
function CR-Artifact($Packet,[string]$Artifact) {
    RP-Require ($Packet.inventory.sourceArtifacts.Contains($Artifact)) 'Evidence is outside the sealed packet.'
    if (-not $script:crCache.ContainsKey($Artifact)) {
        $path=RP-Path $Artifact; $bytes=RP-Bytes $path
        $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
        RP-Require ($hash -ceq $Packet.inventory.sourceArtifacts[$Artifact]) 'Changed reviewed evidence.'
        $text=[Text.Encoding]::UTF8.GetString($bytes)
        try { $value=ConvertFrom-Json -InputObject $text -AsHashtable -Depth 80 } catch { $value=$text }
        $script:crCache[$Artifact]=$value
    }
    return ,$script:crCache[$Artifact]
}
function CR-Validate($Review,$Packet) {
    $script:crCache=@{}
    try {
        RP-Shape $Review @('formatVersion','reviewId','packetId','packetSha256','rubricSha256','reviewer','scope','evidence','cases')
        RP-Require ($Review.formatVersion -eq 1 -and $Review.reviewId -cmatch '^semanticreview-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and $Review.packetId -ceq $Packet.packetId -and (CR-Text $Review.scope)) 'Invalid semantic review identity.'
        RP-Shape $Review.reviewer @('method','label','reviewedOn','independentHumanAdjudication','calibratedReviewerAccuracy','newProviderCalls')
        RP-Require ($Review.reviewer.method -ceq 'model-assisted-claim-level-inspection' -and (CR-Text $Review.reviewer.label) -and $Review.reviewer.reviewedOn -cmatch '^20[0-9]{2}-[0-9]{2}-[0-9]{2}$' -and $Review.reviewer.independentHumanAdjudication -is [bool] -and -not $Review.reviewer.independentHumanAdjudication -and $Review.reviewer.calibratedReviewerAccuracy -is [bool] -and -not $Review.reviewer.calibratedReviewerAccuracy -and $Review.reviewer.newProviderCalls -is [long] -and $Review.reviewer.newProviderCalls -ge 0) 'Invalid or overstated reviewer provenance.'
        $rubric=CR-Artifact $Packet $Packet.inventory.rubricArtifact
        RP-Require ($Review.rubricSha256 -ceq $Packet.inventory.sourceArtifacts[$Packet.inventory.rubricArtifact]) 'Changed review rubric.'
        $evidence=@{}
        RP-Require ($Review.evidence -is [Collections.IList] -and $Review.evidence.Count -ge 1 -and $Review.evidence.Count -le 5000) 'Missing or oversized review evidence.'
        foreach ($ref in $Review.evidence) {
            RP-Shape $ref @('evidenceId','artifact','pointer','sha256','valueSha256')
            RP-Require ($ref.evidenceId -cmatch '^[a-z0-9-]{1,120}$' -and -not $evidence.ContainsKey($ref.evidenceId) -and $ref.sha256 -ceq $Packet.inventory.sourceArtifacts[$ref.artifact]) 'Invalid or duplicate review evidence binding.'
            $value=CR-Pointer (CR-Artifact $Packet $ref.artifact) $ref.pointer
            RP-Require ($ref.valueSha256 -ceq (CR-Digest $value)) 'Changed review evidence pointer value.'
            $evidence[$ref.evidenceId]=$ref
        }
        RP-Require ($Review.cases -is [Collections.IList] -and $Review.cases.Count -eq $Packet.inventory.cases.Count) 'Incomplete reviewed case denominator.'
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $usedEvidence=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $rows=[Collections.Generic.List[object]]::new(); $unitCount=0; $claimCount=0; $historicalUnsupported=0; $historicalUnresolved=0
        foreach ($row in $Review.cases) {
            RP-Shape $row @('position','runId','caseId','inputIdentity','outputIdentity','response','coverageComplete','criteria','units')
            RP-Require ($row.position -is [long] -and $seen.Add(($row.position.ToString()+'/'+$row.caseId))) 'Invalid or duplicate reviewed case.'
            $sources=@($Packet.inventory.cases | Where-Object {$_.position -eq $row.position -and $_.caseId -ceq $row.caseId})
            RP-Require ($sources.Count -eq 1) 'Unexpected reviewed case.'
            $source=$sources[0]
            RP-Require ($row.runId -ceq $source.runId -and (RP-Same $row.inputIdentity $source.inputIdentity) -and (RP-Same $row.outputIdentity $source.outputIdentity) -and $row.response -ceq $source.response -and $row.coverageComplete -is [bool] -and $row.coverageComplete) 'Changed case binding or pending review coverage.'
            $required=@($rubric.cases | Where-Object {$_.caseId -ceq $row.caseId})
            RP-Require ($required.Count -eq 1 -and $row.criteria -is [Collections.IList] -and $row.criteria.Count -eq $required[0].criteria.Count) 'Incomplete criterion review.'
            RP-Require ($row.units -is [Collections.IList] -and $row.units.Count -eq $source.units.Count) 'Incomplete review unit coverage.'
            $units=@{}; $unsupported=0; $unresolved=0
            foreach ($unit in $row.units) {
                RP-Shape $unit @('unitId','valueSha256','coverage','claims','coveredBy','notes')
                RP-Require (-not $units.ContainsKey($unit.unitId) -and (CR-Text $unit.notes) -and $unit.claims -is [Collections.IList] -and $unit.coveredBy -is [Collections.IList]) 'Duplicate or malformed reviewed unit.'
                $items=@($source.units | Where-Object {$_.unitId -ceq $unit.unitId}); RP-Require ($items.Count -eq 1) 'Unexpected reviewed unit.'
                $item=$items[0]; $value=CR-Pointer (CR-Artifact $Packet $item.artifact) $item.pointer
                RP-Require ($unit.valueSha256 -ceq (CR-Digest $value)) 'Changed reviewed claim value.'
                if ($item.kind -ceq 'FINAL_DOSSIER') {
                    RP-Require ($value -is [Collections.IDictionary]) 'Invalid dossier container.'
                    $allowed=@('formatVersion','inputSha256','caseId','inputIdentity','response','scope','executionDiagnostics','projectionDiagnostics','unresolved','qualityReview','summary','summaryCitations','findings','selectedDecision','authority')
                    foreach ($key in $value.Keys) { RP-Require ($key -cin $allowed) 'Uncovered dossier field requires a new review protocol.' }
                    if ($value.Contains('selectedDecision')) { RP-Require ($value.selectedDecision -is [string] -and $value.selectedDecision -cmatch '^[a-zA-Z0-9-]{1,128}$') 'Selection metadata cannot contain unreviewed claims.' }
                }
                $mandatory=($item.kind -cin @('FINAL_SUMMARY','FINAL_FINDING','ORIGINAL_CLAIM','FRESH_CLAIM','STRUCTURED_DIAGNOSTIC')) -or ($item.kind -ceq 'TEAM_CONTEXT' -and (($item.pointer -ceq '/coordinatorReview') -or ($item.pointer -ceq '/possibleConflicts' -and @($value).Count -gt 0)))
                RP-Require ($unit.coverage -cin @('CLAIMS','CONTAINER','CONTEXT') -and ((-not $mandatory) -or ($unit.coverage -ceq 'CLAIMS' -and $unit.claims.Count -gt 0))) ('Material review unit cannot be excluded as context: '+$row.position+'/'+$row.caseId+'/'+$unit.unitId+'.')
                if ($unit.coverage -ceq 'CLAIMS') { RP-Require ($unit.claims.Count -gt 0 -and $unit.coveredBy.Count -eq 0) 'Missing claim decomposition.' }
                elseif ($unit.coverage -ceq 'CONTEXT') { RP-Require ($unit.claims.Count -eq 0 -and $unit.coveredBy.Count -eq 0 -and $item.kind -ceq 'TEAM_CONTEXT') 'Invalid context exclusion.' }
                else { RP-Require ($unit.claims.Count -eq 0 -and $item.kind -cin @('FINAL_DOSSIER','RECONCILIATION')) 'Invalid container delegation.' }
                $ids=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
                foreach ($claim in $unit.claims) {
                    RP-Shape $claim @('claimId','proposition','verdict','material','endorsed','evidenceIds','rationale')
                    RP-Require ($claim.claimId -cmatch '^claim-[1-9][0-9]*$' -and $ids.Add($claim.claimId) -and (CR-Text $claim.proposition) -and (CR-Text $claim.rationale) -and $claim.verdict -cin @('SUPPORTED_WITHIN_RECORDED_SCOPE','UNSUPPORTED','UNRESOLVED') -and $claim.material -is [bool] -and $claim.endorsed -is [bool] -and $claim.evidenceIds -is [Collections.IList] -and $claim.evidenceIds.Count -gt 0) 'Incomplete or invalid material claim judgment.'
                    if ($item.kind -cin @('FINAL_SUMMARY','FINAL_FINDING','STRUCTURED_DIAGNOSTIC','ORIGINAL_CLAIM','FRESH_CLAIM')) { RP-Require $claim.material 'Claim-bearing passages cannot be relabeled as nonmaterial metadata.' }
                    if ($item.kind -cin @('FINAL_SUMMARY','FINAL_FINDING','STRUCTURED_DIAGNOSTIC')) { RP-Require $claim.endorsed 'Final material claims cannot be excluded from acceptance.' }
                    $refIds=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
                    foreach ($id in $claim.evidenceIds) { RP-Require ($evidence.ContainsKey($id) -and $refIds.Add($id)) 'Missing or repeated claim evidence reference.'; [void]$usedEvidence.Add($id) }
                    if ($claim.material -and $claim.verdict -ceq 'UNSUPPORTED') { $historicalUnsupported++; if ($claim.endorsed) {$unsupported++} }
                    if ($claim.material -and $claim.verdict -ceq 'UNRESOLVED') { $historicalUnresolved++; if ($claim.endorsed) {$unresolved++} }
                    $claimCount++
                }
                $units[$unit.unitId]=$unit; $unitCount++
            }
            $finalUnits=@($source.units | Where-Object {$_.kind -cin @('FINAL_SUMMARY','FINAL_FINDING','STRUCTURED_DIAGNOSTIC')} | ForEach-Object {$_.unitId})
            foreach ($item in $source.units) {
                $unit=$units[$item.unitId]
                if ($item.kind -cin @('FINAL_DOSSIER','RECONCILIATION')) {
                    RP-Require ($unit.coverage -ceq 'CONTAINER' -and ([Collections.Generic.HashSet[string]]::new([string[]]$unit.coveredBy,[StringComparer]::Ordinal)).SetEquals([string[]]$finalUnits) -and $unit.coveredBy.Count -eq $finalUnits.Count) 'Incomplete or duplicate container coverage.'
                    if ($item.kind -ceq 'RECONCILIATION') {
                        $terminal=CR-Artifact $Packet $item.artifact
                        foreach ($key in $terminal.Keys) { RP-Require ($key -cin @('status','stopReason','reservedCalls','pages','decision')) 'Uncovered reconciliation field.' }
                        RP-Shape $terminal.decision @('action','cases')
                        RP-Require ($terminal.decision.action -ceq 'finish') 'Nonterminal reconciliation cannot delegate final claim coverage.'
                        foreach ($answer in $terminal.decision.cases) {
                            $other=@($Packet.inventory.cases | Where-Object {$_.runId -ceq $row.runId -and $_.caseId -ceq $answer.caseId})
                            RP-Require ($other.Count -eq 1) 'Unexpected reconciliation answer.'
                            $final=CR-Artifact $Packet $other[0].dossierArtifact
                            foreach ($field in @('caseId','response','summary','summaryCitations','findings')) { RP-Require (RP-Same $answer[$field] $final[$field]) 'Reconciliation differs from reviewed final dossier.' }
                        }
                        RP-Require ($terminal.decision.cases.Count -eq @($Packet.inventory.cases | Where-Object {$_.runId -ceq $row.runId}).Count -and @($terminal.decision.cases.caseId | Sort-Object -Unique).Count -eq $terminal.decision.cases.Count) 'Incomplete reconciliation delegation.'
                    }
                }
            }
            $criterionIds=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($criterion in $row.criteria) {
                RP-Shape $criterion @('criterionId','judgment','rationale','unitIds')
                RP-Require ($criterionIds.Add($criterion.criterionId) -and $criterion.criterionId -cin @($required[0].criteria.id) -and $criterion.judgment -cin @('SATISFIED','NOT_SATISFIED','UNRESOLVED') -and (CR-Text $criterion.rationale) -and $criterion.unitIds -is [Collections.IList] -and $criterion.unitIds.Count -gt 0) 'Invalid or missing criterion judgment.'
                $citations=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
                foreach ($id in $criterion.unitIds) { RP-Require ($units.ContainsKey($id) -and $citations.Add($id)) 'Invalid criterion unit citation.' }
            }
            $responseMatches=(($required[0].expectedResponse -ceq 'ANSWER' -and $row.response -ceq 'ANSWERED') -or ($required[0].expectedResponse -ceq 'ABSTAIN' -and $row.response -ceq 'ABSTAINED'))
            $accepted=$responseMatches -and $unsupported -eq 0 -and $unresolved -eq 0 -and @($row.criteria | Where-Object {$_.judgment -cne 'SATISFIED'}).Count -eq 0
            $rows.Add([ordered]@{position=$row.position;caseId=$row.caseId;arm=$source.slot.arm;acceptedUnderRecordedReview=$accepted;materialUnsupportedClaims=$unsupported;materialUnresolvedClaims=$unresolved;criterionJudgments=@($row.criteria | ForEach-Object {[ordered]@{id=$_.criterionId;judgment=$_.judgment}})})
        }
        RP-Require ($usedEvidence.SetEquals([string[]]@($evidence.Keys))) 'Unused or incomplete evidence catalog.'
        foreach ($entry in $script:crCache.GetEnumerator()) { RP-Require ((RP-Hash (RP-Path $entry.Key)) -ceq $Packet.inventory.sourceArtifacts[$entry.Key]) 'Review evidence changed during verification.' }
        return [ordered]@{status='SEMANTIC_REVIEW_BINDINGS_MATCHED';reviewId=$Review.reviewId;packetId=$Packet.packetId;caseOutputs=$rows.Count;reviewUnits=$unitCount;claimJudgments=$claimCount;historicalUnsupportedClaims=$historicalUnsupported;historicalUnresolvedClaims=$historicalUnresolved;cases=$rows.ToArray();scope='Verifies identities, declared coverage and consistent acceptance gates; does not judge semantic truth, authenticate the reviewer or authorize execution.'}
    } finally { $script:crCache=$null }
}
function Invoke-CoordinationSemanticReview {
    $bytes=RP-Bytes ([IO.Path]::GetFullPath($ReviewPath)) 1048576
    $review=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($bytes)) -AsHashtable -Depth 80
    RP-Require ($review.packetId -cmatch '^reviewpacket-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid reviewed packet reference.'
    $packetPath=RP-Path ('coordination-review-packets/'+$review.packetId+'/packet.json')
    RP-Require ((RP-Hash $packetPath) -ceq $review.packetSha256) 'Changed reviewed packet bytes.'
    $packet=RP-Json $packetPath
    $validated=(& (Join-Path $PSScriptRoot 'Prepare-CoordinationReview.ps1') -Action read -CampaignId $packet.inventory.campaignId -ReviewPacketId $review.packetId -StateDirectory $StateDirectory | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80
    RP-Require ($validated.packetSha256 -ceq $review.packetSha256) 'Changed semantic review input seal.'
    $result=CR-Validate $review $packet
    RP-Require ([Convert]::ToBase64String((RP-Bytes ([IO.Path]::GetFullPath($ReviewPath)) 1048576)) -ceq [Convert]::ToBase64String($bytes) -and (RP-Hash $packetPath) -ceq $review.packetSha256) 'Review changed during verification.'
    $result.reviewSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    $result | ConvertTo-Json -Depth 12
}
if ($MyInvocation.InvocationName -ne '.') { Invoke-CoordinationSemanticReview }
