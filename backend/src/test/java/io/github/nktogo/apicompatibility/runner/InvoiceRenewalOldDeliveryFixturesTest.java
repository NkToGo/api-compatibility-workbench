package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalLedger.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOldDeliveryFixtures.*;

class InvoiceRenewalOldDeliveryFixturesTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/old-delivery-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/old-delivery-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private Observation observe(Delivery delivery, JsonObject actual, String status) {
        var oracle = delivery.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read"));
        if (actual != null) oracle.request(InvoiceRenewalTestInputs.returned(actual));
        return oracle.observation(status);
    }
    private Map<Key, Effect> keys(String installation, Map<String, Effect> effects) {
        var result = new HashMap<Key, Effect>(); effects.forEach((id, effect) -> result.put(new Key(installation, id), effect)); return result;
    }
    @Test void eightAuthoredSequencesKeepNineGoldenReceiptsAndThreeBlockedPositionsSeparate() throws Exception {
        // Supplies golden facts only. No actual consumer or legacy adapter runs in this test.
        var bundle = load(root()); int sequences = 0, receipts = 0, blocked = 0;
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), Map.of()); var outcomes = new ArrayList<StepOutcome>();
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id);
                if (delivery.blocked()) {
                    var before = ledger.snapshot(); assertNull(delivery.expected());
                    assertEquals(Reason.UNSUPPORTED_ADAPTER, delivery.blockingReason());
                    assertThrows(IllegalArgumentException.class, delivery::oracle);
                    assertEquals(before, ledger.snapshot()); outcomes.add(StepOutcome.BLOCKED_MISSING_ADAPTER); blocked++;
                } else {
                    var expected = delivery.expected(); assertEquals(Status.RESOLVED, expected.status()); assertNull(expected.reason());
                    assertEquals("in_primary", expected.invoiceId()); assertEquals("cus_case", expected.customerId()); assertEquals("sub_primary", expected.subscriptionId());
                    var receipt = ledger.accept(observe(delivery, expected.json(), "COMPLETED"));
                    assertEquals(Assertion.PASSED, receipt.observation().assertion()); assertEquals(expected, receipt.observation().actual());
                    assertEquals(ledger.snapshot().receipts().size(), receipt.sequence());
                    outcomes.add(StepOutcome.valueOf(receipt.outcome().name())); receipts++;
                }
            }
            assertEquals(sequence.outcomes(), outcomes); assertEquals(keys(bundle.installationId(), sequence.finalEffects()), ledger.snapshot().effects());
            assertEquals(sequence.deliveries().stream().filter(id -> !scenario.deliveries().get(id).blocked()).count(), ledger.snapshot().receipts().size());
            sequences++;
        }
        assertEquals(8, sequences); assertEquals(9, receipts); assertEquals(3, blocked);
    }
    @Test void oldEventVersionStaysAcaciaDespiteBasilSdkEndpointRequestsAndStateEvidence() throws Exception {
        var bundle = load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var input = delivery.input(); boolean webhook = delivery.id().equals("webhook"); var config = input.getAsJsonObject("configuration");
            assertEquals("29.0.0", config.get("sdkVersion").getAsString());
            for (String name : List.of("requestApiVersion", "endpointApiVersion")) assertEquals("2025-03-31.basil", config.get(name).getAsString());
            assertEquals("2025-02-24.acacia", config.get("accountApiVersion").getAsString());
            assertEquals(webhook ? "2025-02-24.acacia" : "2025-03-31.basil", input.get("effectiveApiVersion").getAsString());
            if (!webhook || delivery.blocked()) assertTrue(config.get("adapterApiVersion").isJsonNull());
            else assertEquals("2025-02-24.acacia", config.get("adapterApiVersion").getAsString());
            var context = input.getAsJsonObject("deliveryContext"); var source = input.getAsJsonObject("source");
            assertEquals(input.get("effectiveApiVersion"), source.get("apiVersion"));
            assertEquals("2025-03-31.basil", input.getAsJsonObject("subscriptionState").get("apiVersion").getAsString());
            assertEquals("active", input.getAsJsonObject("subscriptionState").getAsJsonObject("payload").get("status").getAsString());
            assertTrue(context.get("declaredDeliveryAt").getAsLong() > context.get("declaredUpgradeAt").getAsLong());
            var payload = source.getAsJsonObject("payload");
            var invoice = webhook ? payload.getAsJsonObject("data").getAsJsonObject("object") : payload;
            if (webhook) {
                assertEquals("invoice.paid", payload.get("type").getAsString()); assertEquals("2025-02-24.acacia", payload.get("api_version").getAsString());
                assertTrue(payload.get("created").getAsLong() < context.get("declaredUpgradeAt").getAsLong());
                assertFalse(invoice.has("parent")); assertEquals("sub_primary", invoice.get("subscription").getAsString());
            } else {
                assertFalse(invoice.has("subscription")); assertEquals("subscription_details", invoice.getAsJsonObject("parent").get("type").getAsString());
                assertEquals("sub_primary", invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription").getAsString());
            }
        }
    }
    @Test void missingAdapterCannotCreateAnOracleOrHideBehindTheWorkingReconciliationPath() throws Exception {
        var bundle = load(root()); var declared = bundle.scenarios().getFirst(); var absent = bundle.scenarios().getLast();
        assertFalse(declared.requiresBlockedPathReview()); assertTrue(absent.requiresBlockedPathReview());
        var webhook = absent.deliveries().get("webhook"); assertTrue(webhook.blocked()); assertNull(webhook.expected());
        assertThrows(IllegalArgumentException.class, webhook::oracle);
        for (var sequence : absent.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), Map.of());
            for (String id : sequence.deliveries()) {
                var delivery = absent.deliveries().get(id);
                if (!delivery.blocked()) assertEquals(Outcome.APPENDED, ledger.accept(observe(delivery, delivery.expected().json(), "COMPLETED")).outcome());
            }
            assertTrue(absent.requiresBlockedPathReview());
            assertEquals(sequence.deliveries().contains("reconciliation") ? 1 : 0, ledger.snapshot().receipts().size());
            assertTrue(ledger.snapshot().receipts().stream().allMatch(r -> r.observation().consumerId().equals("invoice-reconciliation")));
        }
    }
    @Test void wrongVersionAndAssociationRemainFailedEvenWhenTheFinalLedgerLooksCorrect() throws Exception {
        var bundle = load(root()); var delivery = bundle.scenarios().getFirst().deliveries().get("webhook");
        var version = delivery.expected().json(); version.addProperty("effectiveApiVersion", "2025-03-31.basil");
        var observation = observe(delivery, version, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion());
        var ledger = new InvoiceRenewalLedger(bundle.installationId(), Map.of()); assertEquals(Outcome.APPENDED, ledger.accept(observation).outcome());
        assertEquals("2025-03-31.basil", ledger.snapshot().receipts().getFirst().observation().actual().effectiveApiVersion());
        assertEquals(Map.of(new Key(bundle.installationId(), "in_primary"), new Effect("cus_case", "sub_primary", Action.RECORD_PAID_RENEWAL)), ledger.snapshot().effects());
        assertEquals("2025-02-24.acacia", delivery.expected().effectiveApiVersion());
        var wrongAssociation = delivery.expected().json(); wrongAssociation.addProperty("subscriptionId", "sub_secondary");
        var wrong = observe(delivery, wrongAssociation, "COMPLETED"); assertEquals(Assertion.FAILED, wrong.assertion());
        assertEquals(Outcome.CONFLICT, ledger.accept(wrong).outcome()); assertEquals("sub_primary", ledger.snapshot().effects().values().iterator().next().subscriptionId());
        assertEquals("sub_secondary", ledger.snapshot().receipts().getLast().observation().actual().subscriptionId());
    }
    @Test void incompleteMissingAndUnboundReturnsCannotInventSuccessfulOldEventExecution() throws Exception {
        var bundle = load(root()); var declared = bundle.scenarios().getFirst(); var webhook = declared.deliveries().get("webhook");
        var reconciliation = declared.deliveries().get("reconciliation");
        for (String status : List.of("TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), Map.of());
            ledger.accept(observe(reconciliation, reconciliation.expected().json(), "COMPLETED"));
            var before = ledger.snapshot().effects(); var incomplete = observe(webhook, webhook.expected().json(), status);
            assertEquals(Assertion.NOT_EVALUATED, incomplete.assertion()); assertEquals(status, incomplete.executionStatus());
            assertEquals(Outcome.NO_EFFECT, ledger.accept(incomplete).outcome()); assertEquals(before, ledger.snapshot().effects());
            assertEquals(2, ledger.snapshot().receipts().size());
        }
        assertEquals(Assertion.NOT_EVALUATED, observe(webhook, null, "COMPLETED").assertion());
        for (String name : List.of("inputIdentity", "evidenceIdentity")) {
            var unbound = webhook.expected().json(); unbound.addProperty(name, "0".repeat(64));
            var observation = observe(webhook, unbound, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion()); assertFalse(observation.identitiesMatch());
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), Map.of()); assertEquals(Outcome.NO_EFFECT, ledger.accept(observation).outcome());
            assertTrue(ledger.snapshot().effects().isEmpty());
        }
    }
    @Test void fourDistinctInputIdentitiesBindTheSameRawSourcesWithoutLeakingExpectedAdmissionOrAnswers() throws Exception {
        var bundle = load(root()); var inputs = new HashSet<String>(); var evidence = new HashSet<String>(); var files = new HashSet<String>();
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            assertTrue(inputs.add(delivery.inputIdentity())); assertTrue(evidence.add(delivery.evidenceIdentity()));
            var input = delivery.input(); assertEquals(Set.of("scenarioId", "familyId", "deliveryId", "consumerId", "installationId",
                    "effectiveApiVersion", "configuration", "approvedBindings", "deliveryContext", "source", "subscriptionState"), input.keySet());
            for (String name : List.of("source", "subscriptionState")) {
                var source = input.getAsJsonObject(name); String file = source.get("file").getAsString(); files.add(file);
                assertEquals(CaseInputs.hash(bundle.bytes(file)), source.get("sha256").getAsString());
                assertEquals(InvoiceRenewalFixtures.object(bundle.bytes(file)), source.getAsJsonObject("payload"));
            }
            if (!delivery.blocked()) {
                assertEquals(delivery.inputIdentity(), delivery.expected().inputIdentity()); assertEquals(delivery.evidenceIdentity(), delivery.expected().evidenceIdentity());
                var response = delivery.oracle().request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
                assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), response.keySet());
            }
        }
        assertEquals(4, inputs.size()); assertEquals(4, evidence.size()); assertEquals(3, files.size());
    }
    @Test void immutableRegistrationAndSeparatePinsDoNotMakeFifteenFamiliesFullCaseCoverage() throws Exception {
        var bundle = load(root()); assertEquals(Set.of("old-delivery-after-upgrade"), bundle.familyIds());
        assertNotEquals(InvoiceRenewalUnpaidFixtures.BUNDLE_SHA256, bundle.identity()); assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalAssociationFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalRefusalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalDeliveryFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalStateFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalUnpaidFixtures.load(root()).requireFullCaseCoverage());
        byte[] original = bundle.bytes("event-acacia.json"); bundle.bytes("event-acacia.json")[0] = 0; assertArrayEquals(original, bundle.bytes("event-acacia.json"));
        var delivery = bundle.scenarios().getFirst().deliveries().get("webhook"); String identity = delivery.inputIdentity();
        delivery.input().getAsJsonObject("configuration").addProperty("adapterApiVersion", "changed"); assertEquals(identity, delivery.inputIdentity());
        assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().clear());
        assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().getFirst().deliveries().clear());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../unpaid-v1/bundle.json"));
    }
    @Test void tamperingCannotTurnAbsentAdapterIntoDeclaredCoverageOrRewriteSourceVersions() throws Exception {
        for (String name : List.of("event-acacia.json", "invoice-basil.json", "state-basil.json", "scenarios.json", "expectations.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/old-delivery-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString()); assertThrows(IllegalArgumentException.class, () -> load(copy));
        }
    }
    @Test void missingExtraAndChangedContractFilesCannotRegisterOldDeliveryEvidence() throws Exception {
        Path missing = copy(); Files.delete(missing.resolve("fixtures/old-delivery-v1/event-acacia.json")); assertThrows(IllegalArgumentException.class, () -> load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/old-delivery-v1/grant.json"), "{}"); assertThrows(IllegalArgumentException.class, () -> load(extra));
        Path changed = copy(); Files.writeString(changed.resolve("business-oracle.md"), "changed"); assertThrows(IllegalArgumentException.class, () -> load(changed));
    }
}
