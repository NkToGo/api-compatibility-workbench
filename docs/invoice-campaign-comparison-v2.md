# Invoice v2 campaign: bound paired comparison

`Compare-InvoiceCampaignV2.ps1` connects a complete [v2 claim review](invoice-campaign-claim-review-v2.md)
to the existing paired quality/resource evaluator. It verifies the full campaign,
packet and independently selected review before deriving separate reviewed
records. It starts no participant, calls no model and supplies no new judgments.
The historical v1 command and evidence retain their existing behavior.

## Publish and inspect

Build using the README commands. Select a complete sealed v2 campaign, its packet,
a completed review revision and their independently retained digests. Use a
separate destination outside the original evidence/state and selected review:

```powershell
./scripts/Compare-InvoiceCampaignV2.ps1 -ReportAction publish `
  -CampaignId invoicecampaign-v2-EXISTING-UUID `
  -ExpectedReceiptSha256 EXPECTED-CAMPAIGN-RECEIPT-SHA256 `
  -ReviewPacketId reviewpacket-v2-EXISTING-UUID `
  -ExpectedPacketSha256 EXPECTED-PACKET-SHA256 `
  -ReviewPath TRUSTED-REVIEWS/completed.json `
  -ReviewSha256 EXPECTED-REVIEW-SHA256 `
  -ReviewEvidenceDirectory TRUSTED-COMMON-ROOT `
  -EvidenceDirectory TRUSTED-EVIDENCE -StateDirectory TRUSTED-STATE `
  -RuntimeDirectory TRUSTED-CAMPAIGN-RUNTIME `
  -InspectionRuntimeDirectory TRUSTED-PACKET-INSPECTOR `
  -ReviewRuntimeDirectory TRUSTED-REVIEW-ENGINE `
  -ComparisonRuntimeDirectory TRUSTED-COMPARISON-ENGINE `
  -ReportDirectory TRUSTED-REPORTS/invoice-v2-comparison
```

Use the same pins and destination with `-ReportAction read` to revalidate an
existing complete report. Read evaluates the supplied files again; it neither
starts trials nor completes interrupted publication. A pending review cannot be
exported as completed work, even if its bindings are mechanically valid.

The first three runtime options keep their [review roles](invoice-campaign-claim-review-v2.md).
`ComparisonRuntimeDirectory` selects the existing read-only
`CoordinationComparisonReport evaluate` command and defaults to the selected
review runtime. Its relative JAR names and SHA-256 hashes are retained in the
receipt and checked around evaluation/publication. Later reads reject changed
binaries before evaluator launch; a byte-identical copy at another path is valid.
Preserve all selected runtimes. Host configuration selects trusted executables;
a digest is not a sandbox or an authenticity signature.

## Records and conclusions

The report directory contains three canonical UTF-8/LF JSON files, each limited
to 1 MiB, plus an exclusive `owner.lock`:

- `records.json` is a generic comparison record format 2 export. The original
  operational format 1 records remain unchanged. Only review metadata is added
  to provided answers or abstentions; absent outputs retain null identities and
  null reviews. All twelve original slots, statuses, dispositions, identities,
  usage and durations remain intact.
- `assessment.json` contains the existing evaluator's twelve rows and six
  paired comparisons under the frozen plan. It retains unestablished quality
  blocks and inclusive resources, including failed/uncertain work and incomplete
  usage. Unknown usage is not assigned zero tokens; token counts are not a
  currency invoice.
- `receipt.json` uses format 2 and role
  `bound-invoice-v2-campaign-comparison`. It binds the campaign receipt, public
  task, packet, exact review, original records, plan, derived output and selected
  evaluator binaries. It retains the complete inspection and reviewer provenance,
  including historical unsupported/unresolved claim counts.

The independently pinned review must cover all twelve trials with no pending
units or criteria. Provided outputs receive explicit six-criterion judgments and
endorsed material unsupported/unresolved counts. Failed, cancelled, uncertain,
missing and abstained work cannot become accepted merely through satisfied
criteria. Full review coverage does not prove the review correct.

The descriptive evaluator is shared with the [historical report](invoice-campaign-results.md);
the new host does not redefine its acceptance gates, denominator or resource
arithmetic. `routingDecision` stays `NOT_ESTABLISHED`. Bindings and tests do not
establish semantic truth, useful coordination, release approval or Milestone 4
acceptance. No actual revised measurement or completed v2 semantic inspection
is supplied by this command.

## Recovery and verification

Publication is create-only. Repeating unchanged inputs must match existing
bytes. A matching interrupted `.pending` file can be promoted by `publish`;
changed pending bytes, a pending sibling of a completed file, unknown directory
entries, linked paths, overlap and concurrent ownership are refused and retained.
An incomplete report remains incomplete until a successful publication; individual
files without the final receipt are not a sealed report. Use stable trusted
stores: this is not an atomic snapshot across changing external files, nor a
defense against replacing both evidence and all independently expected hashes.

After building, run `./scripts/Test-InvoiceCampaignComparisonV2.ps1`. Its explicit
synthetic verification/evaluator fixtures check complete export, failures,
unknown usage, task/version binding, runtime identity, owner exclusion, output
tampering and interrupted recovery. A separate actual evaluator replay uses the
published historical supplied records, including the selected-runtime entry
point. It repeats no model/provider calls and makes no new semantic judgments.
The unchanged historical comparison controls remain in CI on both platforms.
