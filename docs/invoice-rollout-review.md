# Review invoice rollout evidence

Record a local maintainer decision about one
[offline invoice comparison](invoice-rollout-comparison.md). The decision
applies to the selected synthetic reconciliation delivery and its pinned evidence.

After [building the backend](../README.md#build-and-test), preview the evidence
for the selected planning input, capture and attempts:

```powershell
$selection = @{
    InputId = 'INPUT_ID'
    CaptureId = 'CAPTURE_ID'
    BaselineRunId = 'BASELINE_RUN_ID'
    TargetRunId = 'TARGET_RUN_ID'
}
./scripts/Review-InvoiceRollout.ps1 -Preview @selection
```

Replace the placeholders with complete IDs. Omit an attempt if none was selected.
The optional `EvidenceDirectory` and `RunsDirectory` parameters must select the
stores used for the comparison. The preview shows the comparison and an
`evidenceIdentity` covering its output and the exact local journal bytes used.
The comparison binds the retained planning and capture IDs, preflight identity,
and attempt IDs. Journal hashes cover the files actually read, including the
scope, result, execution grant, dispatch and admission records for each completed
attempt. No fixture payloads are copied to the review.

Copy the preview's `evidenceIdentity` after inspecting the comparison:

```powershell
./scripts/Review-InvoiceRollout.ps1 @selection -ReviewedIdentity EVIDENCE_ID `
    -Disposition ACCEPT_NO_REGRESSION
```

`ACCEPT_NO_REGRESSION` requires a completed no-regression comparison.
`HOLD` records a decision to defer, including for regressions or incomplete
evidence. The command recomputes the evidence and refuses to save if the ID
differs. It stores the review as immutable local evidence under
`invoice-rollout-reviews/` in the evidence store and returns its `reviewId`.
Recording the same disposition for the same evidence returns the same ID.

Check a saved decision against the selection under consideration now:

```powershell
./scripts/Review-InvoiceRollout.ps1 @selection -ReviewId REVIEW_ID
```

Supply current IDs, including any newly selected attempt. This command works
offline and does not discover newer GitHub revisions or choose attempts for you.

| State | Meaning |
| --- | --- |
| `CURRENT` | The current evidence is identical to the evidence reviewed. |
| `STALE` | The selected inputs or evidence have changed. The historical decision is preserved. |
| `CURRENT_EVIDENCE_UNAVAILABLE` | The current comparison cannot be opened, for example because the planning capture is missing or corrupt. The historical decision is preserved. |

Java returns exit code `0` for success, `3` for a stale or unavailable
check, and `2` for invalid commands, failed recording or unreadable review
records. The PowerShell wrapper warns for exit `3` and raises an error for
exit `2`.

The check is a point-in-time comparison within a trusted local store.
`CURRENT` describes evidence applicability, including for a hold. Review IDs
are selected explicitly. The command neither selects a latest decision nor
authenticates a reviewer, and it grants no permission to execute a test,
publish a pull request or deploy a change.
