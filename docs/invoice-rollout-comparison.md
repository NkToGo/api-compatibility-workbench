# Compare invoice rollout attempts

Compare one baseline and one target [protected reconciliation delivery](invoice-rollout-delivery.md)
against their selected planning snapshot and source capture. The comparison is
offline. It reads local records and uses the existing compatibility rules.
Run each delivery under its own approval and use the same trusted worker image
and controller build for both.

After [building the backend](../README.md#build-and-test), run:

```powershell
./scripts/Compare-InvoiceRollout.ps1 -InputId INPUT_ID -CaptureId CAPTURE_ID `
    -BaselineRunId BASELINE_RUN_ID -TargetRunId TARGET_RUN_ID
```

Use complete IDs. The optional `EvidenceDirectory` and `RunsDirectory` must
select the stores used by the runner. Omit a run ID when a delivery has yet to
run. When retries exist, name the attempt to review on each side. The command
never selects the latest receipt or reruns an attempt.

For each selected receipt the command verifies the current preflight binding,
the phase, fixture, build and approval scope. For a completed assertion it also
checks the historical grant at dispatch, admission, successful compilation and
transport, worker removal, and recorded input and evidence identities. It
derives the assertion from the returned normalized fact and the independently
pinned expectation. The output contains assertion states and hashes of the
accepted receipts. It excludes the underlying facts and payloads.

The two runs must share the worker image, sandbox and controller identities.
The selected reconciliation delivery must have a source, configuration or API
version change and the same expected business behavior. For example, a change
limited to the webhook does not establish a reconciliation transition.

| Result | Meaning for the selected delivery |
| --- | --- |
| `NO_REGRESSION_OBSERVED` | Both completed assertions passed. |
| `REGRESSION_OBSERVED` | The completed baseline passed and the completed target failed its registered expectation. |
| `UNRESOLVED` | Evidence is missing, incomplete, inconsistent or incomparable, or the baseline failed. Inspect the individual sides and blockers. |

Java exits `0` for an assessed result, `3` for an unresolved result and `2`
for invalid command inputs or unreadable planning evidence. The PowerShell
wrapper warns for unresolved output and raises an error for exit `2`.

Records come from a trusted local journal. Digests detect inconsistent
bindings and identify the bytes assessed. They do not authenticate records
copied from outside the operator's trust boundary. A grant that expired or was
revoked after completion does not rewrite a result. This assessment covers one
synthetic reconciliation delivery and does not approve a deployment.
