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
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalVariantFixtures.*;

class InvoiceRenewalUnpaidFixturesTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/unpaid-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/unpaid-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private Observation observe(InvoiceRenewalFixtures.Delivery delivery, JsonObject actual, String execution) {
        var oracle = delivery.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read"));
        if (actual != null) oracle.request(InvoiceRenewalTestInputs.returned(actual));
        return oracle.observation(execution);
    }
    private Map<Key, Effect> keys(String installation, Map<String, Effect> effects) {
        var result = new HashMap<Key, Effect>(); effects.forEach((id, effect) -> result.put(new Key(installation, id), effect));
        return result;
    }
    @Test void allSixteenAuthoredSequencesRetainTwentyFourRefusalsAndTheDeclaredInitialState() throws Exception {
        // Golden facts check host-policy consistency, not actual consumer compatibility.
        var bundle = InvoiceRenewalUnpaidFixtures.load(root()); int sequences = 0, receipts = 0;
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            var outcomes = new ArrayList<Outcome>();
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id); var expected = delivery.expected();
                assertEquals(Status.UNRESOLVED, expected.status()); assertEquals(Reason.NOT_PAID, expected.reason());
                assertEquals("in_primary", expected.invoiceId()); assertEquals("cus_case", expected.customerId());
                assertEquals("sub_primary", expected.subscriptionId());
                assertEquals(scenario.id().endsWith("acacia") ? "2025-02-24.acacia" : "2025-03-31.basil", expected.effectiveApiVersion());
                var receipt = ledger.accept(observe(delivery, expected.json(), "COMPLETED"));
                assertEquals(Assertion.PASSED, receipt.observation().assertion()); assertEquals(Outcome.NO_EFFECT, receipt.outcome());
                assertEquals(expected, receipt.observation().actual()); receipts++;
                assertEquals(outcomes.size() + 1, receipt.sequence()); outcomes.add(receipt.outcome());
            }
            assertEquals(sequence.outcomes(), outcomes);
            assertEquals(scenario.initialEffects(), sequence.finalEffects());
            assertEquals(keys(bundle.installationId(), sequence.finalEffects()), ledger.snapshot().effects());
            assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
            assertEquals(sequence.deliveries().size(), ledger.snapshot().receipts().size()); sequences++;
        }
        assertEquals(16, sequences); assertEquals(24, receipts);
    }
    @Test void bothVersionsHoldOpenInvoicesKnownAssociationsActiveStateAndExplicitSeedSelection() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root());
        for (var scenario : bundle.scenarios()) {
            assertEquals(scenario.id().contains("seeded") ? Map.of("in_primary", new Effect("cus_case", "sub_primary", Action.RECORD_PAID_RENEWAL))
                    : Map.of(), scenario.initialEffects());
            for (var delivery : scenario.deliveries().values()) {
                var payload = delivery.input().getAsJsonObject("source").getAsJsonObject("payload");
                boolean webhook = delivery.consumerId().equals("invoice-webhook");
                // Open payload inside invoice.paid is intentionally inconsistent, not a claimed provider shape.
                if (webhook) assertEquals("invoice.paid", payload.get("type").getAsString());
                var invoice = webhook ? payload.getAsJsonObject("data").getAsJsonObject("object") : payload;
                assertEquals("open", invoice.get("status").getAsString()); assertFalse(invoice.get("paid").getAsBoolean());
                assertEquals("subscription_cycle", invoice.get("billing_reason").getAsString());
                assertEquals("cus_case", invoice.get("customer").getAsString());
                assertEquals("sub_primary", scenario.id().endsWith("acacia") ? invoice.get("subscription").getAsString()
                        : invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription").getAsString());
                if (scenario.id().endsWith("basil")) assertEquals("subscription_details", invoice.getAsJsonObject("parent").get("type").getAsString());
                var state = delivery.input().getAsJsonObject("subscriptionState"); assertEquals("subscription-retrieval", state.get("kind").getAsString());
                var subscription = state.getAsJsonObject("payload");
                assertEquals("active", subscription.get("status").getAsString()); assertEquals("sub_primary", subscription.get("id").getAsString());
                assertEquals("cus_case", subscription.get("customer").getAsString());
            }
        }
    }
    @Test void wrongRefusalReasonsLostAssociationsAndStandaloneAnswersFailEvenWithMatchingEffects() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var wrongReason = delivery.expected().json(); wrongReason.addProperty("reason", "STATE_NOT_ACTIVE");
            var lostAssociation = delivery.expected().json(); lostAssociation.add("subscriptionId", JsonNull.INSTANCE);
            var nonSubscription = delivery.expected().json(); nonSubscription.addProperty("status", "NON_SUBSCRIPTION");
            nonSubscription.add("subscriptionId", JsonNull.INSTANCE); nonSubscription.add("reason", JsonNull.INSTANCE);
            for (var actual : List.of(wrongReason, lostAssociation, nonSubscription)) {
                var observation = observe(delivery, actual, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion());
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                assertEquals(Outcome.NO_EFFECT, ledger.accept(observation).outcome());
                assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
                assertEquals(observation.actual(), ledger.snapshot().receipts().getFirst().observation().actual());
            }
        }
    }
    @Test void falsePaidFactsRetainErroneousAppendsOrDuplicatesInsteadOfBecomingAcceptedAnswers() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var wrong = delivery.expected().json(); wrong.addProperty("status", "RESOLVED"); wrong.add("reason", JsonNull.INSTANCE);
            var observation = observe(delivery, wrong, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion());
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            assertEquals(scenario.initialEffects().isEmpty() ? Outcome.APPENDED : Outcome.DUPLICATE, ledger.accept(observation).outcome());
            assertEquals(Map.of(new Key(bundle.installationId(), "in_primary"), new Effect("cus_case", "sub_primary", Action.RECORD_PAID_RENEWAL)), ledger.snapshot().effects());
            assertEquals(Status.RESOLVED, ledger.snapshot().receipts().getFirst().observation().actual().status());
            assertEquals(Reason.NOT_PAID, delivery.expected().reason());
        }
    }
    @Test void unpaidRefusalsDoNotRevokeOrRewriteAnyPreexistingEffects() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root());
        var initial = Map.of("in_primary", new Effect("cus_case", "sub_secondary", Action.RECORD_PAID_RENEWAL),
                "in_other", new Effect("cus_case", "sub_primary", Action.RECORD_PAID_RENEWAL));
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), initial);
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id);
                assertEquals(Outcome.NO_EFFECT, ledger.accept(observe(delivery, delivery.expected().json(), "COMPLETED")).outcome());
            }
            assertEquals(keys(bundle.installationId(), initial), ledger.snapshot().effects());
            assertEquals(sequence.deliveries().size(), ledger.snapshot().receipts().size());
        }
    }
    @Test void incompleteMissingAndUnboundOutputsCannotSatisfyTheRefusalOrAlterSeededEffects() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var wrong = delivery.expected().json(); wrong.addProperty("status", "RESOLVED"); wrong.add("reason", JsonNull.INSTANCE);
            for (String status : List.of("TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")) {
                var incomplete = observe(delivery, wrong, status); assertEquals(Assertion.NOT_EVALUATED, incomplete.assertion());
                assertEquals(status, incomplete.executionStatus()); assertNotNull(incomplete.actual());
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                assertEquals(Outcome.NO_EFFECT, ledger.accept(incomplete).outcome());
                assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
            }
            assertEquals(Assertion.NOT_EVALUATED, observe(delivery, null, "COMPLETED").assertion());
            for (String field : List.of("inputIdentity", "evidenceIdentity")) {
                var unbound = wrong.deepCopy(); unbound.addProperty(field, "0".repeat(64));
                var observation = observe(delivery, unbound, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion());
                assertFalse(observation.identitiesMatch()); var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                assertEquals(Outcome.NO_EFFECT, ledger.accept(observation).outcome());
                assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
            }
        }
    }
    @Test void workerInputsBindAllTenSourcesAndEightDistinctDeliveriesWithoutExposingAnswersOrInitialLedger() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root()); var inputs = new HashSet<String>(); var evidence = new HashSet<String>();
        var sources = new HashSet<String>();
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var input = delivery.input(); assertTrue(inputs.add(delivery.expected().inputIdentity())); assertTrue(evidence.add(delivery.expected().evidenceIdentity()));
            assertEquals(delivery.expected().inputIdentity(), InvoiceRenewalOracle.inputIdentity(input));
            assertEquals(Set.of("scenarioId", "familyId", "deliveryId", "consumerId", "installationId", "effectiveApiVersion",
                    "configuration", "approvedBindings", "source", "subscriptionState"), input.keySet());
            for (String field : List.of("source", "subscriptionState")) {
                var source = input.getAsJsonObject(field); String name = source.get("file").getAsString(); sources.add(name);
                byte[] bytes = bundle.bytes(name); assertEquals(CaseInputs.hash(bytes), source.get("sha256").getAsString());
                assertEquals(InvoiceRenewalFixtures.object(bytes), source.getAsJsonObject("payload"));
                assertEquals(delivery.expected().effectiveApiVersion(), source.get("apiVersion").getAsString());
            }
            var response = delivery.oracle().request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
            assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), response.keySet());
            assertEquals(input, response.getAsJsonObject("input"));
        }
        assertEquals(8, inputs.size()); assertEquals(8, evidence.size()); assertEquals(10, sources.size());
    }
    @Test void immutableUnpaidRegistrationAndCompiledSeedSelectionCannotGrantFullCoverage() throws Exception {
        var bundle = InvoiceRenewalUnpaidFixtures.load(root());
        assertEquals(Set.of("not-paid"), bundle.familyIds()); assertEquals(4, bundle.scenarios().size());
        assertNotEquals(InvoiceRenewalStateFixtures.BUNDLE_SHA256, bundle.identity());
        assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalAssociationFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalRefusalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalDeliveryFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalStateFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalArgumentException.class, () -> new Variant("invalid", "duplicate-delivery", "acacia", Layout.SEEDED_REFUSAL));
        assertThrows(IllegalArgumentException.class, () -> new Variant("invalid", "not-paid", "acacia", Layout.SEEDED_REFUSAL, StateEvidence.INACTIVE));
        var scenario = bundle.scenarios().get(1); assertThrows(UnsupportedOperationException.class, () -> scenario.initialEffects().clear());
        var delivery = scenario.deliveries().get("webhook"); delivery.input().addProperty("initialEffects", "changed");
        assertEquals(delivery.expected().inputIdentity(), InvoiceRenewalOracle.inputIdentity(delivery.input()));
        byte[] original = bundle.bytes("state-primary-acacia.json"), changed = bundle.bytes("state-primary-acacia.json"); changed[0] = 0;
        assertArrayEquals(original, bundle.bytes("state-primary-acacia.json")); assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().clear());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../state-v1/bundle.json"));
    }
    @Test void tamperedMissingExtraAndChangedContractInputsCannotRegisterUnpaidFixtures() throws Exception {
        for (String name : List.of("open-fresh-acacia-webhook.json", "open-seeded-basil-reconciliation.json", "state-primary-basil.json",
                "expectations.json", "scenarios.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/unpaid-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalUnpaidFixtures.load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString());
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalUnpaidFixtures.load(copy));
        }
        Path missing = copy(); Files.delete(missing.resolve("fixtures/unpaid-v1/state-primary-acacia.json"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalUnpaidFixtures.load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/unpaid-v1/grant.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalUnpaidFixtures.load(extra));
        Path contract = copy(); Files.writeString(contract.resolve("business-oracle.md"), "changed");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalUnpaidFixtures.load(contract));
    }
}
