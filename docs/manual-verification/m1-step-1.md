# Manual verification — Milestone 1, Step 1

Run these steps from Windows PowerShell at the repository root. This component is
a library, so there is no browser or server to start. JShell, included in JDK 21, lets you call the actual
comparison code interactively. The inputs below are synthetic metadata; they do
not contact or validate an external provider.

## 1. Verify the build

```powershell
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
```

Expected: `BUILD SUCCESS`, 48 tests, zero failures/errors/skips. Stop on a failure
and investigate before committing.

## 2. Open the interactive Java shell

```powershell
jshell --class-path backend/target/classes
```

At the `jshell>` prompt, paste the following setup. These are Java statements, not
PowerShell commands. The repeated digest is a synthetic placeholder for this
manual check only.

```java
import java.util.List;
import io.github.nktogo.apicompatibility.compatibility.*;
import io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.*;
var digest = new ContentDigest("a".repeat(64));
var baseline = new InputSnapshot("manual-case", "example-payments", "v1", digest, digest, digest, digest, digest, digest);
var target = new InputSnapshot("manual-case", "example-payments", "v2", digest, digest, digest, digest, digest, digest);
var definition = new ComparisonDefinition(baseline, target, List.of("entitlement"));
var before = new ExecutionObservation("entitlement", baseline, Disposition.COMPLETED, AssertionOutcome.PASSED);
var after = new ExecutionObservation("entitlement", target, Disposition.COMPLETED, AssertionOutcome.PASSED);
var result = CompatibilityComparison.compare(definition, List.of(before), List.of(after));
result.conclusion();
```

Expected: `NO_REGRESSION_OBSERVED`.

## 3. Exercise failure and uncertainty

Change the target assertion to a failure:

```java
var failed = new ExecutionObservation("entitlement", target, Disposition.COMPLETED, AssertionOutcome.FAILED);
CompatibilityComparison.compare(definition, List.of(before), List.of(failed)).conclusion();
```

Expected: `REGRESSION_OBSERVED`.

Now report a timeout instead of an evaluated assertion:

```java
var timedOut = new ExecutionObservation("entitlement", target, Disposition.TIMED_OUT, AssertionOutcome.NOT_EVALUATED);
var incomplete = CompatibilityComparison.compare(definition, List.of(before), List.of(timedOut));
incomplete.conclusion();
incomplete.issues();
```

Expected: `UNRESOLVED` and an issue with reason `EXECUTION_INCOMPLETE`.

Check that a successful retry cannot silently overwrite a conflicting failure:

```java
var conflicting = CompatibilityComparison.compare(definition, List.of(before), List.of(failed, after));
conflicting.conclusion();
conflicting.issues();
```

Expected: `UNRESOLVED` and `DUPLICATE_OBSERVATIONS`.

## 4. Check reuse against changed inputs

```java
result.appliesTo(definition);
var newerTarget = new InputSnapshot("manual-case", "example-payments", "v3", digest, digest, digest, digest, digest, digest);
result.appliesTo(new ComparisonDefinition(baseline, newerTarget, List.of("entitlement")));
result.conclusion();
```

Expected in order: `true`, `false`, `NO_REGRESSION_OBSERVED`. The historical result
keeps its original conclusion while refusing reuse for the new provider version.

Exit JShell:

```text
/exit
```

## 5. Compare with the contract

Return to the [review guide](../reviews/m1-step-1.md), inspect the implementation
and confirm that these results match the documented contract. Investigate any
mismatch.
