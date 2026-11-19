package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import io.github.nktogo.apicompatibility.compatibility.CompatibilityComparison;
import io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment;
import io.github.nktogo.apicompatibility.compatibility.ComparisonDefinition;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.compatibility.ExecutionObservation;
import io.github.nktogo.apicompatibility.compatibility.InputSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.nktogo.apicompatibility.runner.InvoiceCapturePreflight.Phase;

/** Read-only comparison of two explicitly selected local rollout attempts. */
public final class InvoiceRolloutComparison {
    private static final String ROLE = "invoice-reconciliation";
    private static final String CORPUS = "rollout-v1";
    private static final String SCENARIO = "registered-invoice-reconciliation";
    private static final String DELIVERY_POLICY = "invoice-rollout-delivery-v1";
    private static final Set<String> STATUSES = Set.of("COMPLETED", "TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR",
            "AUTHORIZATION_DENIED", "FIXTURE_ADMISSION_BLOCKED", "CLEANUP_UNCONFIRMED",
            "INTERRUPTED_CLEANUP_UNCONFIRMED", "EXECUTOR_ERROR_CLEANUP_UNCONFIRMED");

    public enum ReceiptState { UNSELECTED, NOT_CHECKED, MISSING_ATTEMPT, INCOMPLETE_ATTEMPT, SCOPE_MISMATCH, INVALID_RECORD, RECORDED }
    public enum Blocker { PREFLIGHT_BLOCKED, RECEIPT_UNAVAILABLE, ASSERTION_UNAVAILABLE, WORKER_OR_RUNTIME_DIFFERS,
        EXPECTATION_DIFFERS, DELIVERY_HAS_NO_TRANSITION }
    public record Side(Phase phase, String runId, ReceiptState state, String scopeIdentity, String receiptSha256,
                       String executionStatus, String recordedAssertion, String checkedAssertion, String authorizationDecision,
                       Boolean containerRemoved, String imageId, String runtimeIdentity, String sandboxIdentity) {}
    public record Report(int formatVersion, String authority, String preflightIdentity, String inputId, String captureId,
                         Side baseline, Side target, List<InvoiceCapturePreflight.Finding> preflightFindings,
                         List<Blocker> blockers, ComparisonAssessment.Conclusion conclusion,
                         ComparisonAssessment assessment) {
        public Report {
            preflightFindings = List.copyOf(preflightFindings);
            blockers = List.copyOf(blockers);
        }
    }
    private record Selected(InvoiceCapturePreflight.PhaseMatch match, InvoiceCapturePreflight.Delivery delivery,
                            InvoiceBusinessCoverage.Entry entry) {}
    private record Checked(Side side, InvoiceRenewalOracle.Assertion assertion) {}
    private record BusinessExpectation(InvoiceRenewalOracle.Status status, String invoiceId, String customerId,
                                       String subscriptionId, InvoiceRenewalOracle.Reason reason) {
        static BusinessExpectation of(InvoiceRenewalOracle.Fact fact) {
            return new BusinessExpectation(fact.status(), fact.invoiceId(), fact.customerId(), fact.subscriptionId(), fact.reason());
        }
    }
    private record Environment(String image, String sandboxPolicy, long deadlineSeconds) {}
    private record Harness(String controller, String sandbox) {}
    private record Fixtures(String corpus, String baselineInput, String targetInput) {}

    private InvoiceRolloutComparison() {}

    public static Report inspect(EvidenceStore store, Path caseRoot, Path runs, String inputId, String captureId,
                                 String baselineId, String targetId) throws IOException {
        validateRunId(baselineId);
        validateRunId(targetId);
        CaseInputs.require(baselineId == null || !baselineId.equals(targetId), "Select separate attempts");
        var preflight = InvoiceCapturePreflight.check(store, caseRoot, inputId, captureId);
        var blockers = new ArrayList<Blocker>();
        var baseline = unavailable(Phase.BASELINE, baselineId, baselineId == null ? ReceiptState.UNSELECTED : ReceiptState.NOT_CHECKED);
        var target = unavailable(Phase.TARGET, targetId, targetId == null ? ReceiptState.UNSELECTED : ReceiptState.NOT_CHECKED);
        ComparisonAssessment assessment = null;
        if (preflight.report().assessment().state() != InvoiceCapturePreflight.State.MATCHES_REGISTERED_ROLLOUT) {
            blockers.add(Blocker.PREFLIGHT_BLOCKED);
        } else {
            var before = select(preflight, Phase.BASELINE);
            var after = select(preflight, Phase.TARGET);
            baseline = read(runs, baselineId, preflight.report(), before);
            target = read(runs, targetId, preflight.report(), after);
            if (baseline.side().state() != ReceiptState.RECORDED || target.side().state() != ReceiptState.RECORDED) {
                blockers.add(Blocker.RECEIPT_UNAVAILABLE);
            }
            if (baseline.assertion() == InvoiceRenewalOracle.Assertion.NOT_EVALUATED
                    || target.assertion() == InvoiceRenewalOracle.Assertion.NOT_EVALUATED) {
                blockers.add(Blocker.ASSERTION_UNAVAILABLE);
            }
            if (bothEvaluated(baseline, target) && !sameRuntime(baseline.side(), target.side())) {
                blockers.add(Blocker.WORKER_OR_RUNTIME_DIFFERS);
            }
            var expectation = BusinessExpectation.of(before.entry().expected());
            if (!expectation.equals(BusinessExpectation.of(after.entry().expected()))) blockers.add(Blocker.EXPECTATION_DIFFERS);
            if (before.delivery().sourceIdentity().equals(after.delivery().sourceIdentity())
                    && before.delivery().configurationIdentity().equals(after.delivery().configurationIdentity())
                    && before.entry().expected().effectiveApiVersion().equals(after.entry().expected().effectiveApiVersion())) {
                blockers.add(Blocker.DELIVERY_HAS_NO_TRANSITION);
            }
            if (blockers.isEmpty()) {
                assessment = compare(preflight.report(), before, after, baseline, target, expectation);
            }
        }
        var conclusion = assessment == null ? ComparisonAssessment.Conclusion.UNRESOLVED : assessment.conclusion();
        return new Report(1, "TRUSTED_LOCAL_SYNTHETIC_ROLLOUT", preflight.report().preflightIdentity(), inputId, captureId,
                baseline.side(), target.side(), preflight.report().assessment().findings(), blockers, conclusion, assessment);
    }

    private static Selected select(InvoiceCapturePreflight.Checked preflight, Phase phase) {
        var match = preflight.report().assessment().matches().stream().filter(row -> row.phase() == phase).findFirst().orElseThrow();
        var delivery = match.deliveries().stream().filter(row -> row.consumerId().equals(ROLE)).findFirst().orElseThrow();
        var entry = preflight.plan().business().entries().stream().filter(row -> row.corpus().equals(CORPUS)
                && row.scenario().equals(match.scenarioId()) && row.delivery().equals(delivery.deliveryId()) && row.consumer().equals(ROLE))
                .findFirst().orElseThrow();
        return new Selected(match, delivery, entry);
    }

    private static Checked read(Path runs, String runId, InvoiceCapturePreflight.Report preflight, Selected selected) {
        Phase phase = selected.match().phase();
        if (runId == null) return unavailable(phase, null, ReceiptState.UNSELECTED);
        var directory = runs.toAbsolutePath().normalize().resolve(runId);
        try {
            try {
                Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                return unavailable(phase, runId, ReceiptState.MISSING_ATTEMPT);
            }
            ConsumerTasks.directory(directory);
            JsonObject scopeRecord = readOptional(directory.resolve("scope.json"));
            if (scopeRecord == null) {
                require(readOptional(directory.resolve("result.json")) == null);
                return unavailable(phase, runId, ReceiptState.INCOMPLETE_ATTEMPT);
            }
            var preview = CaseInputs.JSON.fromJson(scopeRecord, InvoiceRolloutDelivery.Preview.class);
            require(preview != null && preview.scope() != null && scopeRecord.equals(CaseInputs.JSON.toJsonTree(preview)));
            require(CaseInputs.identity(preview.scope()).equals(preview.scopeIdentity()));
            if (!matchesScope(preview.scope(), preflight, selected, runId)) {
                return unavailable(phase, runId, ReceiptState.SCOPE_MISMATCH);
            }
            byte[] receipt = bytesOptional(directory.resolve("result.json"));
            if (receipt == null) return unavailable(phase, runId, ReceiptState.INCOMPLETE_ATTEMPT);
            return checkReceipt(directory, preview, receipt, selected);
        } catch (IOException | RuntimeException invalid) {
            return unavailable(phase, runId, ReceiptState.INVALID_RECORD);
        }
    }

    private static boolean matchesScope(InvoiceRolloutDelivery.Scope scope, InvoiceCapturePreflight.Report preflight,
                                        Selected selected, String runId) {
        var facts = preflight.assessment();
        return scope.formatVersion() == 1 && DELIVERY_POLICY.equals(scope.policy()) && runId.equals(scope.runId())
                && preflight.preflightIdentity().equals(scope.preflightIdentity())
                && facts.inputs().inputId().equals(scope.inputId()) && facts.inputs().captureId().equals(scope.captureId())
                && scope.phase() == selected.match().phase() && selected.match().scenarioId().equals(scope.scenarioId())
                && facts.hostCase().equals(scope.hostCase()) && selected.delivery().equals(scope.delivery())
                && scope.imageId() != null && scope.imageId().matches("sha256:[0-9a-f]{64}")
                && isDigest(scope.runtimeIdentity()) && scope.deadlineSeconds() == 30 && scope.authorizationSeconds() == 120;
    }

    private static Checked checkReceipt(Path directory, InvoiceRolloutDelivery.Preview preview, byte[] bytes, Selected selected) throws IOException {
        var receipt = InvoiceRenewalFixtures.object(bytes);
        var result = CaseInputs.JSON.fromJson(receipt, InvoiceRolloutDelivery.Result.class);
        var execution = receipt.get("protectedExecution");
        var top = receipt.deepCopy();
        top.remove("protectedExecution");
        require(result != null && top.equals(CaseInputs.JSON.toJsonTree(result)));
        var scope = preview.scope();
        require(preview.scopeIdentity().equals(result.scopeIdentity()) && scope.runId().equals(result.runId())
                && result.status() != null && STATUSES.contains(result.status()));
        var recorded = InvoiceRenewalOracle.Assertion.valueOf(result.assertionOutcome());
        require(result.authorizationDecision() != null && (result.authorizationDecision().equals("NOT_CHECKED")
                || Set.of(ExecutionAuthorization.Decision.values()).stream().anyMatch(d -> d.name().equals(result.authorizationDecision()))));
        var assertion = InvoiceRenewalOracle.Assertion.NOT_EVALUATED;
        String sandbox = null;
        if (result.status().equals("COMPLETED")) {
            require(execution != null && execution.isJsonObject() && Boolean.TRUE.equals(result.containerRemoved())
                    && result.authorizationDecision().equals("ALLOWED"));
            String container = verifyDispatch(directory, preview);
            var record = execution.getAsJsonObject();
            var delivery = scope.delivery();
            require(record.get("formatVersion").equals(new JsonPrimitive(1))
                    && same(record, "imageId", scope.imageId()) && same(record, "containerName", container)
                    && same(record, "buildId", delivery.buildId()) && same(record, "sourceIdentity", delivery.sourceIdentity())
                    && same(record, "configurationIdentity", delivery.configurationIdentity())
                    && same(record, "dependenciesIdentity", scope.hostCase().dependencyIdentity())
                    && same(record, "transportStatus", "COMPLETED") && same(record, "executionStatus", "COMPLETED")
                    && record.get("workerExitCode").equals(new JsonPrimitive(0))
                    && record.get("containerRemoved").equals(new JsonPrimitive(true))
                    && record.get("transportInvalid").equals(new JsonPrimitive(false))
                    && same(record.getAsJsonObject("compilation"), "disposition", "SUCCEEDED"));
            sandbox = InvoiceRenewalFixtures.text(record, "harnessIdentity");
            require(isDigest(sandbox) && isDigest(InvoiceRenewalFixtures.text(record, "environmentIdentity")));
            assertion = assertion(record.getAsJsonObject("observation"), selected.entry().expected(), recorded);
        } else {
            require(recorded == InvoiceRenewalOracle.Assertion.NOT_EVALUATED);
        }
        var side = new Side(scope.phase(), scope.runId(), ReceiptState.RECORDED, preview.scopeIdentity(), CaseInputs.hash(bytes),
                result.status(), recorded.name(), assertion.name(), result.authorizationDecision(),
                result.containerRemoved(), scope.imageId(), scope.runtimeIdentity(), sandbox);
        return new Checked(side, assertion);
    }

    private static String verifyDispatch(Path directory, InvoiceRolloutDelivery.Preview preview) throws IOException {
        var scope = preview.scope();
        var grant = readOptional(directory.resolve("authorization.json"));
        var started = readOptional(directory.resolve("started.json"));
        var admission = readOptional(directory.resolve("admission.json"));
        require(grant != null && started != null && admission != null);
        require(grant.get("formatVersion").equals(new JsonPrimitive(1))
                && same(grant, "runId", scope.runId()) && same(grant, "imageId", scope.imageId())
                && same(grant, "mode", DELIVERY_POLICY + ":" + preview.scopeIdentity())
                && same(grant, "permission", "execute-invoice-rollout-delivery"));
        Instant issued = Instant.parse(InvoiceRenewalFixtures.text(grant, "issuedAt"));
        Instant expires = Instant.parse(InvoiceRenewalFixtures.text(grant, "expiresAt"));
        Instant dispatch = Instant.parse(InvoiceRenewalFixtures.text(started, "startedAt"));
        require(Duration.between(issued, expires).equals(Duration.ofSeconds(scope.authorizationSeconds()))
                && !dispatch.isBefore(issued) && dispatch.isBefore(expires));
        require(admission.get("admitted").equals(new JsonPrimitive(true)) && admission.getAsJsonArray("issues").isEmpty());
        String container = InvoiceRenewalFixtures.text(started, "containerName");
        validateRunId(container);
        require(!scope.runId().equals(container));
        return container;
    }

    private static InvoiceRenewalOracle.Assertion assertion(JsonObject observation, InvoiceRenewalOracle.Fact expected,
                                                            InvoiceRenewalOracle.Assertion recorded) {
        require(same(observation, "consumerId", ROLE) && same(observation, "executionStatus", "COMPLETED")
                && observation.get("protocolInvalid").equals(new JsonPrimitive(false))
                && expected.equals(fact(observation.get("expected"))));
        var actualValue = observation.get("actual");
        var actual = actualValue == null || actualValue.isJsonNull() ? null : fact(actualValue);
        boolean bound = actual != null && actual.inputIdentity().equals(expected.inputIdentity())
                && actual.evidenceIdentity().equals(expected.evidenceIdentity());
        var derived = actual == null ? InvoiceRenewalOracle.Assertion.NOT_EVALUATED
                : expected.equals(actual) ? InvoiceRenewalOracle.Assertion.PASSED : InvoiceRenewalOracle.Assertion.FAILED;
        require(observation.get("identitiesMatch").equals(new JsonPrimitive(bound)) && same(observation, "assertionOutcome", derived.name())
                && recorded == derived);
        return bound ? derived : InvoiceRenewalOracle.Assertion.NOT_EVALUATED;
    }

    private static InvoiceRenewalOracle.Fact fact(JsonElement value) {
        var fact = value.getAsJsonObject().deepCopy();
        // The journal serializer omits JSON nulls. Restore only the oracle's nullable fields.
        for (String name : List.of("customerId", "subscriptionId", "effectiveApiVersion", "reason")) {
            if (!fact.has(name)) fact.add(name, JsonNull.INSTANCE);
        }
        return InvoiceRenewalOracle.Fact.parse(fact);
    }

    private static ComparisonAssessment compare(InvoiceCapturePreflight.Report preflight, Selected before, Selected after,
                                                Checked baseline, Checked target, BusinessExpectation expectation) {
        var fixtures = digest(new Fixtures(preflight.assessment().hostCase().rolloutCorpusIdentity(),
                before.delivery().inputIdentity(), after.delivery().inputIdentity()));
        var harness = digest(new Harness(baseline.side().runtimeIdentity(), baseline.side().sandboxIdentity()));
        // Older worker JSON used unordered map serialization for environmentIdentity. Scope pins the common inputs directly.
        var environment = digest(new Environment(baseline.side().imageId(), "invoice-container-v1", 30));
        var expected = digest(expectation);
        var baselineInputs = snapshot(before, expected, fixtures, harness, environment);
        var targetInputs = snapshot(after, expected, fixtures, harness, environment);
        var definition = new ComparisonDefinition(baselineInputs, targetInputs, List.of(SCENARIO));
        return CompatibilityComparison.compare(definition, List.of(observation(baselineInputs, baseline)),
                List.of(observation(targetInputs, target)));
    }

    private static InputSnapshot snapshot(Selected selected, ContentDigest expectation, ContentDigest fixtures,
                                          ContentDigest harness, ContentDigest environment) {
        return new InputSnapshot(InvoiceRenewalFixtures.CASE_ID, "stripe", selected.entry().expected().effectiveApiVersion(),
                new ContentDigest(selected.delivery().sourceIdentity()), new ContentDigest(selected.delivery().configurationIdentity()),
                expectation, fixtures, harness, environment);
    }

    private static ExecutionObservation observation(InputSnapshot input, Checked side) {
        return new ExecutionObservation(SCENARIO, input, ExecutionObservation.Disposition.COMPLETED,
                ExecutionObservation.AssertionOutcome.valueOf(side.assertion().name()));
    }

    private static boolean bothEvaluated(Checked before, Checked after) {
        return before.assertion() != InvoiceRenewalOracle.Assertion.NOT_EVALUATED
                && after.assertion() != InvoiceRenewalOracle.Assertion.NOT_EVALUATED;
    }

    private static boolean sameRuntime(Side baseline, Side target) {
        return baseline.imageId().equals(target.imageId()) && baseline.runtimeIdentity().equals(target.runtimeIdentity())
                && baseline.sandboxIdentity().equals(target.sandboxIdentity());
    }

    private static Checked unavailable(Phase phase, String runId, ReceiptState state) {
        return new Checked(new Side(phase, runId, state, null, null, null, null, null, null, null, null, null, null),
                InvoiceRenewalOracle.Assertion.NOT_EVALUATED);
    }

    private static JsonObject readOptional(Path path) throws IOException {
        byte[] bytes = bytesOptional(path);
        return bytes == null ? null : InvoiceRenewalFixtures.object(bytes);
    }

    private static byte[] bytesOptional(Path path) throws IOException {
        try {
            require(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile());
        } catch (NoSuchFileException absent) {
            return null;
        }
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(65537);
            require(bytes.length <= 65536);
            return bytes;
        }
    }

    private static boolean same(JsonObject object, String key, String value) {
        return new JsonPrimitive(value).equals(object.get(key));
    }

    private static ContentDigest digest(Object value) { return new ContentDigest(CaseInputs.identity(value)); }
    private static boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void validateRunId(String runId) {
        CaseInputs.require(runId == null || runId.matches("acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "Invalid rollout attempt ID");
    }
    private static void require(boolean condition) { CaseInputs.require(condition, "Inconsistent rollout receipt"); }

    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 7, "Expected case, store, runs, plan, capture and two attempt selectors");
            var report = inspect(new EvidenceStore(Path.of(args[1])), Path.of(args[0]), Path.of(args[2]),
                    args[3], args[4], "-".equals(args[5]) ? null : args[5], "-".equals(args[6]) ? null : args[6]);
            System.out.println(CaseInputs.JSON.toJson(report));
            if (report.conclusion() == ComparisonAssessment.Conclusion.UNRESOLVED) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Invoice comparison failed. Check the selected evidence store and attempt IDs.");
            System.exit(2);
        }
    }
}
