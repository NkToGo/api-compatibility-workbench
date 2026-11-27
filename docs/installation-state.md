# Configure installation state for subscription repairs

The subscription repair workflow can use one state directory configured by the
host operator. Verification, recovery, branch checks, approval, draft publication,
status and evidence export then read their records from the same installation.

## Choose a data directory

Provision one existing, absolute directory on a trusted local filesystem, outside
the workbench checkout. Use physical paths for both directories, with no symlinks,
junctions or other reparse points in their ancestors. Keep the directory, local
consumer checkout, export destinations and GitHub App credentials under the
operator's control.
Use separate service accounts, state directories and GitHub App credentials
for separate organizations. The operating system enforces access to those
resources. Apply separate access controls to backups and export destinations.

Set this variable in the host process that runs the workbench commands:

```powershell
$env:WORKBENCH_INSTALLATION_STATE_DIRECTORY = '/srv/workbench/billing/state'
```

On Windows use the installation's fully qualified local drive path. Existing
records can stay in their current layout wherever the directory meets the path
requirements. Keep related journals and receipts together when relocating them.

## How commands use the directory

The setting applies to these subscription workflow commands:

| Operation | Scripts |
| --- | --- |
| Verification and recovery | `Verify-SubscriptionRepairCommits.ps1` in execute mode, `Verify-SubscriptionCandidate.ps1` |
| Commit and branch checks | `Check-RepairCommit.ps1`, `Check-RepairBranches.ps1` |
| Approval, publication and reconciliation | `Repair-PublicationApproval.ps1`, `Publish-RepairDraft.ps1` |
| Observation and export | `Get-RepairPullRequestStatus.ps1`, `Get-RepairDeploymentStatus.ps1`, `Export-RepairEvidence.ps1` |

When configured, these scripts reject an invalid directory or a conflicting
`-StateDirectory` before launching Java. An explicit absolute `-StateDirectory`
can repeat the configured path. With the variable unset, the scripts use the
explicit directory or the checkout's `.workbench` default. Preview of subscription
repair commits still uses only the selected local Git objects. Export destinations
remain explicit, and other workbench commands have their own state settings.

The setting selects local state for a trusted installation. Run the commands from
an environment controlled by the operator, with filesystem access granted to the
designated account. On a shared host, the isolation boundary is the service
account and its permissions. Hosted organization authorization, retention and
deletion controls require separate release steps.

## Verify the path boundary

After building the backend, run `./scripts/Test-WorkbenchInstallationState.ps1`.
It exercises invalid paths, linked ancestors and the legacy default. It runs the
actual approval command against the same operation ID in two temporary journals,
checking that the configured installation selects which record can be revoked.
The check uses local synthetic records and no GitHub or model calls.
