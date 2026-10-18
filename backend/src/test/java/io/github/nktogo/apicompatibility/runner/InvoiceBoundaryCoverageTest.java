package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceBoundaryCoverage.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

class InvoiceBoundaryCoverageTest {
    @TempDir Path temporary;
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static Plan plan() throws Exception { return load(InvoiceSandboxTest.root()); }
    private static InvoiceRenewalBoundaryFixtures.Control control(Plan plan, String id) {
        return plan.sequences().stream().map(Sequence::control).filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
    private InvoiceBoundaryAdmissionCoverage.Run admission(Plan plan, String image) throws Exception {
        return InvoiceBoundaryAdmissionCoverage.run(plan.admission(), image, temporary.resolve(UUID.randomUUID().toString()), Duration.ofSeconds(30), () -> true);
    }
    private InvoiceBoundaryExecutionCoverage.Run execution(Plan plan, String image) throws Exception {
        return InvoiceBoundaryExecutionCoverage.run(plan.execution(), image, temporary.resolve(UUID.randomUUID().toString()), Duration.ofSeconds(30), () -> true);
    }
    private static Observation observed(InvoiceRenewalBoundaryFixtures.Control control, String status, java.util.function.UnaryOperator<com.google.gson.JsonObject> change) {
        var oracle = control.admission().oracle(); oracle.request(InvoiceSandboxTest.read()); oracle.request(InvoiceSandboxTest.returned(change.apply(control.expectedFact().json()))); return oracle.observation(status);
    }
    @Test void completeRegistrySelectsAll34Controls102Placements170PositionsAndOppositeHealthyRoles() throws Exception {
        var plan = plan(); assertEquals(102, plan.sequences().size()); assertEquals(34, plan.sequences().stream().map(s -> s.control().id()).distinct().count());
        assertEquals(170, plan.sequences().stream().mapToInt(s -> s.order().size()).sum()); assertEquals(26, plan.admission().entries().size()); assertEquals(10, plan.execution().entries().size());
        assertEquals(0, plan.metadata().get("consumerDispatchesDuringReplay").getAsInt());
        for (var sequence : plan.sequences()) {
            assertNotEquals(sequence.control().consumer(), sequence.healthy().consumer()); assertEquals(sequence.placement().equals("alone") ? 1 : 2, sequence.order().size());
            if (sequence.placement().equals("healthy-first")) assertSame(sequence.healthy(), sequence.order().getFirst());
            if (sequence.placement().equals("healthy-last")) assertSame(sequence.healthy(), sequence.order().getLast());
        }
        assertEquals(72, plan.sequences().stream().filter(s -> !s.control().expected().admitted()).count());
        var metadata = plan.metadata(); plan.metadata().getAsJsonArray("sequences").remove(0); assertEquals(metadata, plan.metadata());
        assertThrows(UnsupportedOperationException.class, () -> plan.sequences().clear()); assertThrows(UnsupportedOperationException.class, () -> plan.sequences().getFirst().order().clear());
    }
    @Test void wrongVersionRemainsAFailedConsumerAssertionEvenWhenItsLedgerEffectMatches() throws Exception {
        var control = control(plan(), "wrong-version-return"); var ledger = new InvoiceRenewalLedger("install_case", Map.of("in_primary", new InvoiceRenewalLedger.Effect("cus_case", "sub_primary", InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL)));
        var observed = observed(control, "COMPLETED", json -> { json.addProperty("effectiveApiVersion", "2025-02-24.acacia"); return json; });
        var result = observe(control, observed, ledger, "DUPLICATE"); assertEquals(Outcome.VERIFIED, result.outcome()); assertEquals(Assertion.FAILED, observed.assertion());
        assertEquals("FAILED", result.evidence().getAsJsonObject("observation").get("assertionOutcome").getAsString()); assertEquals("DUPLICATE", result.evidence().get("actualOutcome").getAsString());
        assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
    }
    @Test void unexpectedIncorrectAssociationPreservesConflictAndTheFirstRecordedEffect() throws Exception {
        var control = control(plan(), "wrong-version-return"); var ledger = new InvoiceRenewalLedger("install_case", Map.of("in_primary", new InvoiceRenewalLedger.Effect("cus_case", "sub_primary", InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL)));
        var observed = observed(control, "COMPLETED", json -> { json.addProperty("effectiveApiVersion", "2025-02-24.acacia"); json.addProperty("subscriptionId", "sub_wrong"); return json; });
        var result = observe(control, observed, ledger, "DUPLICATE"); assertEquals(Outcome.FAILED, result.outcome()); assertEquals("CONFLICT", result.evidence().get("actualOutcome").getAsString());
        assertEquals("sub_wrong", observed.actual().subscriptionId()); assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects()); assertEquals(1, ledger.snapshot().receipts().size());
    }
    @Test void incompleteAndUnboundFactsHaveReceiptsButCannotEraseExistingEffects() throws Exception {
        var plan = plan(); var initial = Map.of("in_primary", new InvoiceRenewalLedger.Effect("cus_case", "sub_primary", InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL), "in_other", new InvoiceRenewalLedger.Effect("cus_case", "sub_other", InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL));
        for (String id : List.of("returned-timed-out", "returned-cancelled", "returned-infrastructure-error", "unbound-return")) {
            var control = control(plan, id); var observed = observed(control, control.executionStatus(), json -> { if (id.equals("unbound-return")) json.addProperty("inputIdentity", "0".repeat(64)); return json; });
            var ledger = new InvoiceRenewalLedger("install_case", initial); var result = observe(control, observed, ledger, "NO_EFFECT"); assertEquals(Outcome.VERIFIED, result.outcome());
            assertEquals("NO_EFFECT", result.evidence().get("actualOutcome").getAsString()); assertNotNull(observed.actual()); assertEquals(1, ledger.snapshot().receipts().size()); assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
        }
    }
    @Test void foreignConsumerOrExpectedScopeCannotReachTheLedger() throws Exception {
        var plan = plan(); var target = control(plan, "admit-webhook");
        for (String other : List.of("admit-reconciliation", "wrong-version-return")) {
            var observed = observed(control(plan, other), "COMPLETED", json -> json); var ledger = new InvoiceRenewalLedger("install_case", Map.of());
            var result = observe(target, observed, ledger, "APPENDED"); assertEquals(Outcome.NOT_EVALUATED, result.outcome()); assertEquals("OBSERVATION_SCOPE_MISMATCH", result.evidence().get("bindingIssue").getAsString()); assertTrue(ledger.snapshot().effects().isEmpty()); assertTrue(ledger.snapshot().receipts().isEmpty());
        }
    }
    @Test void unavailableCapturedEvidenceKeepsAllPositionsUnevaluatedWithoutInventingBlocksOrFacts() throws Exception {
        var plan = plan(); var run = evaluate(plan, admission(plan, IMAGE), execution(plan, IMAGE), temporary.resolve("missing"), () -> false);
        assertFalse(run.boundaryCoverageSatisfied()); assertEquals(102, run.sequences().size()); assertTrue(run.sequences().stream().allMatch(s -> s.outcome() == Outcome.NOT_EVALUATED));
        for (var sequence : run.sequences()) for (var position : sequence.json().getAsJsonArray("positions")) {
            var row = position.getAsJsonObject(); assertTrue(row.get("observation").isJsonNull()); assertTrue(row.get("ledgerReceipt").isJsonNull()); assertTrue(row.get("actualOutcome").isJsonNull());
        }
        assertEquals(0, run.json().get("boundaryControlsVerified").getAsInt()); assertEquals(0, run.json().get("boundaryPlacementsVerified").getAsInt()); assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
    }
    @Test void preReplayInterruptionRetainsEveryPositionAndCreateOnlyHistory() throws Exception {
        var plan = plan(); var admission = admission(plan, IMAGE); var execution = execution(plan, IMAGE); Path output = temporary.resolve("cancelled");
        var run = evaluate(plan, admission, execution, output, () -> true); assertEquals(102, run.sequences().size()); assertTrue(run.sequences().stream().allMatch(s -> s.outcome() == Outcome.NOT_ATTEMPTED));
        try (var files = Files.list(output)) { assertEquals(104, files.count()); }
        var report = run.json(); run.json().getAsJsonArray("sequences").remove(0); run.sequences().getFirst().json().addProperty("outcome", "VERIFIED"); assertEquals(report, run.json()); assertThrows(UnsupportedOperationException.class, () -> run.sequences().clear());
        byte[] saved = Files.readAllBytes(output.resolve("report.json")); assertThrows(FileAlreadyExistsException.class, () -> evaluate(plan, admission, execution, output, () -> true)); assertArrayEquals(saved, Files.readAllBytes(output.resolve("report.json")));
    }
    @Test void differentPlanOrImageRunsAreRejectedBeforeCreatingAnyReplayRecords() throws Exception {
        var plan = plan(); var admission = admission(plan, IMAGE); var execution = execution(plan, IMAGE); Path output = temporary.resolve("invalid");
        assertTrue(admission.belongsTo(plan.admission())); assertTrue(execution.belongsTo(plan.execution()));
        assertThrows(IllegalArgumentException.class, () -> evaluate(plan(), admission, execution, output, () -> true));
        assertThrows(IllegalArgumentException.class, () -> evaluate(plan, admission, execution(plan, "sha256:" + "b".repeat(64)), output, () -> true)); assertFalse(Files.exists(output));
    }
}
