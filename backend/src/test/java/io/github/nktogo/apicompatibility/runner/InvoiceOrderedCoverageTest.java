package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceOrderedCoverage.*;

class InvoiceOrderedCoverageTest {
    @TempDir Path temporary;
    private static Plan plan() throws Exception { return load(InvoiceSandboxTest.root()); }
    private static InvoiceBusinessCoverage.Entry entry(Plan plan) {
        return plan.business().entries().stream().filter(e -> e.corpus().equals("rollout-v1") && e.scenario().equals("target-agreement") && e.delivery().equals("webhook")).findFirst().orElseThrow();
    }
    // These unit observations exercise the pure checker, not actual consumer execution or run authority.
    private static InvoiceRenewalOracle.Observation observation(InvoiceBusinessCoverage.Entry entry, JsonObject actual, String status) {
        var oracle = new InvoiceRenewalOracle(entry.consumer(), entry.input(), entry.expected()); oracle.request(InvoiceSandboxTest.read());
        if (actual != null) oracle.request(InvoiceSandboxTest.returned(actual)); return oracle.observation(status);
    }
    private static InvoiceRenewalLedger ledger() { return new InvoiceRenewalLedger("install_case", Map.of()); }
    @Test void planKeepsAll188Orders312PositionsAndThreeBlockedPositionsWithTheirOriginalScope() throws Exception {
        var plan = plan(); assertEquals(188, plan.sequences().size()); assertEquals(312, plan.sequences().stream().mapToInt(s -> s.entries().size()).sum());
        assertEquals(100, plan.sequences().stream().flatMap(s -> s.entries().stream()).map(InvoiceBusinessCoverage.Entry::key).distinct().count());
        assertEquals(3, plan.sequences().stream().flatMap(s -> s.entries().stream()).filter(InvoiceBusinessCoverage.Entry::expectedBlock).count());
        assertEquals(4, plan.sequences().stream().filter(Sequence::blockedPathReviewRequired).count());
        assertEquals(47, plan.sequences().stream().map(s -> s.corpus() + "/" + s.scenario()).distinct().count());
        for (var sequence : plan.sequences()) { assertEquals(sequence.entries().size(), sequence.expectedOutcomes().size()); for (var e : sequence.entries()) { assertEquals(sequence.corpusIdentity(), e.corpusIdentity()); assertEquals(sequence.installation(), e.input().get("installationId").getAsString()); } }
        assertTrue(plan.sequences().stream().anyMatch(s -> s.entries().stream().map(InvoiceBusinessCoverage.Entry::key).distinct().count() < s.entries().size()));
        assertTrue(plan.sequences().stream().anyMatch(s -> !s.initial().isEmpty()));
    }
    @Test void planAndCopiedPositionMetadataCannotBeEditedIntoDifferentOrdersOrEvidence() throws Exception {
        var plan = plan(); var metadata = plan.metadata(); var sequence = plan.sequences().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> plan.sequences().clear()); assertThrows(UnsupportedOperationException.class, () -> sequence.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> sequence.initial().clear()); assertThrows(UnsupportedOperationException.class, () -> sequence.expectedOutcomes().clear());
        plan.metadata().getAsJsonArray("sequences").remove(0); assertEquals(metadata, plan.metadata()); assertFalse(metadata.toString().contains("payload"));
        var entry = entry(plan); var checked = observe(entry, observation(entry, entry.expected().json(), "COMPLETED"), ledger(), "APPENDED");
        checked.evidence().getAsJsonObject("observation").addProperty("assertionOutcome", "FORGED"); assertEquals("PASSED", checked.evidence().getAsJsonObject("observation").get("assertionOutcome").getAsString());
        var omitted = metadata.deepCopy(); omitted.getAsJsonArray("sequences").remove(0); assertNotEquals(identity(metadata), identity(omitted));
        var nullable = new JsonObject(); nullable.add("field", JsonNull.INSTANCE); assertNotEquals(identity(new JsonObject()), identity(nullable));
    }
    @Test void aWrongVersionCannotPassEvenWhenTheLedgerEffectIsExactlyTheExpectedEffect() throws Exception {
        var entry = entry(plan()); var wrong = entry.expected().json(); wrong.addProperty("effectiveApiVersion", "2025-02-24.acacia");
        var ledger = ledger(); var checked = observe(entry, observation(entry, wrong, "COMPLETED"), ledger, "APPENDED");
        assertEquals(Outcome.FAILED, checked.outcome()); assertEquals("APPENDED", checked.evidence().get("actualOutcome").getAsString());
        assertEquals("sub_primary", ledger.snapshot().effects().values().iterator().next().subscriptionId());
        assertEquals("FAILED", checked.evidence().getAsJsonObject("observation").get("assertionOutcome").getAsString());
    }
    @Test void wrongAssociationsAndLaterConflictsPreserveTheFirstActualValueAndEveryReceipt() throws Exception {
        var entry = entry(plan()); var wrong = entry.expected().json(); wrong.addProperty("subscriptionId", "sub_secondary");
        var ledger = ledger(); var first = observe(entry, observation(entry, wrong, "COMPLETED"), ledger, "APPENDED");
        var second = observe(entry, observation(entry, entry.expected().json(), "COMPLETED"), ledger, "DUPLICATE");
        assertEquals(Outcome.FAILED, first.outcome()); assertEquals(Outcome.FAILED, second.outcome());
        assertEquals("CONFLICT", second.evidence().get("actualOutcome").getAsString()); assertEquals(2, ledger.snapshot().receipts().size());
        assertEquals("sub_secondary", ledger.snapshot().effects().values().iterator().next().subscriptionId());
    }
    @Test void incompleteAndUnboundActualFactsStayVisibleWithoutSupplyingEffects() throws Exception {
        var entry = entry(plan());
        for (String status : List.of("TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")) {
            var ledger = ledger(); var checked = observe(entry, observation(entry, entry.expected().json(), status), ledger, "NO_EFFECT");
            assertEquals(Outcome.NOT_EVALUATED, checked.outcome()); assertEquals("NO_EFFECT", checked.evidence().get("actualOutcome").getAsString());
            assertFalse(checked.evidence().getAsJsonObject("observation").get("actual").isJsonNull()); assertTrue(ledger.snapshot().effects().isEmpty());
        }
        var absent = observe(entry, observation(entry, null, "COMPLETED"), ledger(), "NO_EFFECT"); assertEquals(Outcome.NOT_EVALUATED, absent.outcome()); assertTrue(absent.evidence().getAsJsonObject("observation").get("actual").isJsonNull());
        var wrong = entry.expected().json(); wrong.addProperty("inputIdentity", "a".repeat(64)); var ledger = ledger();
        assertEquals(Outcome.FAILED, observe(entry, observation(entry, wrong, "COMPLETED"), ledger, "NO_EFFECT").outcome()); assertTrue(ledger.snapshot().effects().isEmpty()); assertEquals(1, ledger.snapshot().receipts().size());
    }
    @Test void anObservationForAnotherDeliveryOrRoleCannotMutateThisLedger() throws Exception {
        var plan = plan(); var entry = entry(plan); var other = plan.business().entries().getFirst(); var ledger = ledger();
        var checked = observe(entry, observation(other, other.expected().json(), "COMPLETED"), ledger, "APPENDED");
        assertEquals(Outcome.NOT_EVALUATED, checked.outcome()); assertEquals("OBSERVATION_SCOPE_MISMATCH", checked.evidence().get("bindingIssue").getAsString());
        var wrongRole = new InvoiceRenewalOracle("invoice-reconciliation", entry.input(), entry.expected()); wrongRole.request(InvoiceSandboxTest.read()); wrongRole.request(InvoiceSandboxTest.returned(entry.expected().json()));
        assertEquals(Outcome.NOT_EVALUATED, observe(entry, wrongRole.observation("COMPLETED"), ledger, "APPENDED").outcome()); assertTrue(ledger.snapshot().receipts().isEmpty()); assertTrue(ledger.snapshot().effects().isEmpty());
    }
    @Test void missingBusinessExecutionRetainsEveryOrderButCannotBecomePassingOrOverwriteHistory() throws Exception {
        var plan = plan(); var business = InvoiceBusinessCoverage.run(plan.business(), "sha256:" + "a".repeat(64), temporary.resolve("business"), Duration.ofSeconds(30), () -> true);
        Path output = temporary.resolve("ordered"); var run = evaluate(plan, business, output, () -> false);
        assertEquals(188, run.sequences().size()); assertTrue(run.sequences().stream().allMatch(s -> s.outcome() == Outcome.NOT_EVALUATED)); assertFalse(run.orderedCoverageSatisfied());
        assertFalse(run.json().get("fullCaseCoverageSatisfied").getAsBoolean()); assertThrows(IllegalStateException.class, run::requireFullCaseCoverage);
        assertEquals(identity(business.json()), run.json().get("businessEvidenceIdentity").getAsString());
        try (var files = Files.list(output)) { assertEquals(190, files.count()); }
        for (var sequence : run.sequences()) for (var value : sequence.json().getAsJsonArray("positions")) { var row = value.getAsJsonObject(); assertTrue(row.get("ledgerReceipt").isJsonNull()); assertTrue(row.get("observation").isJsonNull()); }
        var before = Files.readAllBytes(output.resolve("report.json")); assertThrows(FileAlreadyExistsException.class, () -> evaluate(plan, business, output, () -> false)); assertArrayEquals(before, Files.readAllBytes(output.resolve("report.json")));
        run.json().getAsJsonArray("sequences").remove(0); assertEquals(188, run.json().getAsJsonArray("sequences").size());
    }
    @Test void cancellationRetainsAllOrdersAndAnotherPlanCannotBorrowAnEarlierTypedRun() throws Exception {
        var plan = plan(); var business = InvoiceBusinessCoverage.run(plan.business(), "sha256:" + "a".repeat(64), temporary.resolve("business"), Duration.ofSeconds(30), () -> true);
        var run = evaluate(plan, business, temporary.resolve("stopped"), () -> true);
        assertEquals(188, run.sequences().size()); assertTrue(run.sequences().stream().allMatch(s -> s.outcome() == Outcome.NOT_ATTEMPTED)); assertFalse(run.orderedCoverageSatisfied());
        Path wrong = temporary.resolve("wrong-plan"); assertThrows(IllegalArgumentException.class, () -> evaluate(plan(), business, wrong, () -> false)); assertFalse(Files.exists(wrong));
    }
}
