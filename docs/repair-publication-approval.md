# Approve a repair pull request

Record a time-limited local approval for one draft repair pull request using the
exact [local repair binding](repair-commit-binding.md) you reviewed. The workflow
uses the trusted local state directory and existing read-only
[GitHub App configuration](github-repository-connection.md).

## Record an approval

Run the local repair commit check and inspect its output and publication intent.
Pass its `bindingIdentity` explicitly when you approve the operation.

```powershell
$selection = @{
    VerificationId = 'verification-EXISTING-UUID'
    Repository = './consumer-repository'
    Intent = './local-repair-intent.json'
    SourcePath = 'src/main/PeriodConsumer.java'
}
./scripts/Repair-PublicationApproval.ps1 -Approve @selection -BindingIdentity REVIEWED_BINDING_ID
```

The command recomputes the binding and runs a fresh
[GitHub branch check](repair-branch-check.md). Both branches must match before it
atomically records the approval, observed branches and expiry under
`.workbench/repair-publications/<operationId>/approval.json`. Use
`-StateDirectory` for another trusted local store. An approval lasts 30 minutes.
Recording again for the same operation preserves the original record. Use a new
publication intent for a new approval.

## Check or revoke an approval

```powershell
./scripts/Repair-PublicationApproval.ps1 -Check @selection
./scripts/Repair-PublicationApproval.ps1 -Revoke -OperationId OPERATION_UUID
```

`-Check` reopens the approval, verifies that it applies to the recomputed binding,
checks GitHub again and rechecks expiry and revocation before returning.
`-Revoke` requires only the operation ID and writes a persistent local revocation
marker. It works offline and can be repeated.

| Result | Meaning |
| --- | --- |
| `RECORDED` | An approval was saved after matching the reviewed binding and live branch observations. |
| `APPLICABLE` | The existing approval still applies at the end of this check. |
| `STALE` | The reviewed evidence changed or the branch check found a blocker. |
| `UNCONFIRMED` | A fresh matching branch observation could not be established. |
| `EXPIRED`, `REVOKED`, `UNAVAILABLE`, `INVALID` | The saved approval is unusable. |
| `ALREADY_RECORDED`, `BUSY` | An approval already exists or another local writer holds the operation's lease. |

These are local approval commands. The record covers the selected draft PR
operation. The eventual publisher must repeat validation at dispatch and record
the outcome durably. The check is point-in-time. It does not lock GitHub branches
or authenticate individual callers of the local command. Protect the state
directory with operating-system access controls.

Java returns exit code `0` for recorded approval, applicable approval, or a
successful revoke, `3` for a non-applicable or interrupted operation, and `2`
for an invalid invocation or configuration. The PowerShell wrapper warns for
exit `3` and raises an error for exit `2`.
