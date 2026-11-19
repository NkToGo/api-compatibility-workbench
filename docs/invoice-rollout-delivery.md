# Protected invoice rollout delivery

This command runs one registered synthetic reconciliation delivery from the
baseline or target of a matching [invoice capture preflight](invoice-capture-preflight.md).
It uses the host's validated, byte-matching consumer build and its pinned
fixtures and expectations. A separate local authorization covers the exact
attempt. The result describes one delivery's execution and assertion.

After [preparing and building the backend](../README.md#build-and-test), start a
Linux Docker engine with cgroup v2 and seccomp, then build the trusted worker:

```powershell
./scripts/Build-InvoiceWorker.ps1
$image = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
$attempt = "acw-$([guid]::NewGuid())"
$selection = @{
    RunId = $attempt
    InputId = 'INPUT_ID'
    CaptureId = 'CAPTURE_ID'
    Phase = 'TARGET'
    ImageId = $image
}
./scripts/Run-InvoiceRolloutDelivery.ps1 -Preview @selection
```

Replace the placeholder IDs with the full planning snapshot and capture IDs.
If needed, set `EvidenceDirectory` to their shared evidence store. The preview
revalidates those records, the selected fixture, the host build and dependencies,
and identifies the controller and sandbox code. It checks the image ID's format
without contacting Docker or creating a run. Review the displayed scope before
using its identity to authorize execution explicitly:

```powershell
./scripts/Run-InvoiceRolloutDelivery.ps1 -ApproveScope SCOPE_ID @selection
```

Use the full `scopeIdentity` returned by the preview. The runner recomputes
the scope, and any change to the approved inputs, phase, image, runtime or
attempt ID requires a new approval. The operator supplies a trusted local
worker image. The existing sandbox verifies the Docker platform and the
created container's isolation configuration before starting it.
Base-build configuration digests use a fixed field order so separate preview
and execution processes agree. Regenerate earlier preflight reports if a digest
from a prior build differs. Retained historical observations keep their original
identities.

## Authorization and evidence

The approval creates permission for one attempt and one delivery for two
minutes. The protected runner checks that permission before dispatch, during
execution and before accepting the result. The attached execution has the
existing 30-second limit. An operator can revoke an active attempt in another
terminal with the same `RunsDirectory`:

```powershell
./scripts/Run-InvoiceRolloutDelivery.ps1 -Revoke -RunId $attempt
```

The run directory defaults to `.workbench/invoice-rollout-runs/<run-id>/`, outside
Maven build output. It contains the approved scope, authorization, admission
decision, and, once dispatch is attempted, the separately allocated container
name. `result.json` retains disposition, assertion and any protected execution
record. A passing result requires a completed delivery, a passing host assertion
and confirmed worker removal. Nonpassing results retain their observed status
instead of becoming successful on retry. The launcher returns an error for a
refused or nonpassing operation.

An existing attempt ID is never used twice. An interrupted controller can leave
an incomplete directory, and cleanup problems are reported as unconfirmed.
Retain those records and use the recorded worker name and image for operator
reconciliation. Revocation is cooperative, and a completed result remains
unchanged. This step provides no automatic recovery or redispatch. Keep the
run directory on trusted local storage and restrict access to it and Docker.

This execution uses the synthetic reconciliation fixture and has no provider
network access. Use the [offline rollout comparison](invoice-rollout-comparison.md)
to assess two separately approved reconciliation attempts. Webhook execution,
live Stripe evidence and migration approval require separate work.
