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

class InvoiceRenewalStateFixturesTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/state-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/state-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private Reason reason(String id) {
        if (id.startsWith("absent-")) return Reason.STATE_UNAVAILABLE;
        if (id.startsWith("failed-")) return Reason.STATE_FAILED;
        if (id.startsWith("inactive-")) return Reason.STATE_NOT_ACTIVE;
        return Reason.STATE_BINDING_MISMATCH;
    }
    private JsonObject invoice(InvoiceRenewalFixtures.Delivery delivery) {
        var payload = delivery.input().getAsJsonObject("source").getAsJsonObject("payload");
        return delivery.consumerId().equals("invoice-webhook") ? payload.getAsJsonObject("data").getAsJsonObject("object") : payload;
    }
    private Observation observe(InvoiceRenewalFixtures.Delivery delivery, JsonObject actual, String execution) {
        var oracle = delivery.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read"));
        if (actual != null) oracle.request(InvoiceRenewalTestInputs.returned(actual));
        return oracle.observation(execution);
    }
    @Test void allFortyAuthoredSequencesKeepSpecificRefusalFactsAndSixtyNoEffectReceipts() throws Exception {
        // Golden facts validate host-policy consistency, not actual consumer compatibility.
        var bundle = InvoiceRenewalStateFixtures.load(root()); int sequences = 0, receipts = 0;
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            assertTrue(scenario.initialEffects().isEmpty()); var outcomes = new ArrayList<Outcome>();
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id); var expected = delivery.expected();
                assertEquals(Status.UNRESOLVED, expected.status()); assertEquals("in_primary", expected.invoiceId());
                assertEquals("cus_case", expected.customerId()); assertEquals("sub_primary", expected.subscriptionId());
                assertEquals(reason(scenario.id()), expected.reason());
                var observation = observe(delivery, expected.json(), "COMPLETED");
                assertEquals(Assertion.PASSED, observation.assertion()); assertEquals(expected, observation.actual());
                var receipt = ledger.accept(observation); assertEquals(Outcome.NO_EFFECT, receipt.outcome());
                assertEquals(expected.evidenceIdentity(), receipt.observation().actual().evidenceIdentity());
                outcomes.add(receipt.outcome()); receipts++;
            }
            assertEquals(sequence.outcomes(), outcomes); assertTrue(sequence.finalEffects().isEmpty());
            assertTrue(ledger.snapshot().effects().isEmpty());
            assertEquals(sequence.deliveries().size(), ledger.snapshot().receipts().size()); sequences++;
        }
        assertEquals(40, sequences); assertEquals(60, receipts);
    }
    @Test void lookupAbsenceFailureInactiveStateAndIdentityMismatchesRemainDifferentEvidence() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var source = delivery.input().getAsJsonObject("subscriptionState"); var state = source.getAsJsonObject("payload");
            var invoice = invoice(delivery); assertEquals("paid", invoice.get("status").getAsString()); assertTrue(invoice.get("paid").getAsBoolean());
            assertEquals("sub_primary", scenario.id().endsWith("acacia") ? invoice.get("subscription").getAsString()
                    : invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription").getAsString());
            if (scenario.id().startsWith("absent-") || scenario.id().startsWith("failed-")) {
                assertEquals("authored-state-lookup", source.get("kind").getAsString());
                assertEquals(Set.of("formatVersion", "origin", "resource", "requestedCustomerId", "requestedSubscriptionId",
                        "outcome", "response", "failureCode"), state.keySet());
                assertEquals("authored-synthetic", state.get("origin").getAsString()); assertFalse(state.has("object"));
                assertEquals("cus_case", state.get("requestedCustomerId").getAsString());
                assertEquals("sub_primary", state.get("requestedSubscriptionId").getAsString()); assertTrue(state.get("response").isJsonNull());
                if (scenario.id().startsWith("absent-")) {
                    assertEquals("ABSENT", state.get("outcome").getAsString()); assertTrue(state.get("failureCode").isJsonNull());
                } else {
                    assertEquals("FAILED", state.get("outcome").getAsString()); assertEquals("LOOKUP_FAILED", state.get("failureCode").getAsString());
                }
            } else {
                assertEquals("subscription-retrieval", source.get("kind").getAsString()); assertEquals("subscription", state.get("object").getAsString());
                assertEquals(scenario.id().startsWith("inactive-") ? "canceled" : "active", state.get("status").getAsString());
                assertEquals(scenario.id().startsWith("wrong-customer-") ? "cus_other" : "cus_case", state.get("customer").getAsString());
                assertEquals(scenario.id().startsWith("wrong-subscription-") ? "sub_secondary" : "sub_primary", state.get("id").getAsString());
            }
        }
    }
    @Test void wrongReasonsLostAssociationAndNonSubscriptionAnswersFailDespiteAnEmptyFinalLedger() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var wrongReason = delivery.expected().json();
            wrongReason.addProperty("reason", reason(scenario.id()) == Reason.STATE_UNAVAILABLE ? "STATE_FAILED" : "STATE_UNAVAILABLE");
            var lostAssociation = delivery.expected().json(); lostAssociation.add("subscriptionId", JsonNull.INSTANCE);
            var nonSubscription = delivery.expected().json(); nonSubscription.addProperty("status", "NON_SUBSCRIPTION");
            nonSubscription.add("subscriptionId", JsonNull.INSTANCE); nonSubscription.add("reason", JsonNull.INSTANCE);
            for (var actual : List.of(wrongReason, lostAssociation, nonSubscription)) {
                var observation = observe(delivery, actual, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion());
                assertNotEquals(delivery.expected(), observation.actual());
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                assertEquals(Outcome.NO_EFFECT, ledger.accept(observation).outcome()); assertTrue(ledger.snapshot().effects().isEmpty());
                assertEquals(observation.actual(), ledger.snapshot().receipts().getFirst().observation().actual());
            }
        }
    }
    @Test void inventingActiveStateFromAPaidInvoiceRetainsTheFailedFactAndItsActualEffect() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var wrong = delivery.expected().json(); wrong.addProperty("status", "RESOLVED"); wrong.add("reason", JsonNull.INSTANCE);
            var observation = observe(delivery, wrong, "COMPLETED"); assertEquals(Assertion.FAILED, observation.assertion());
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            assertEquals(Outcome.APPENDED, ledger.accept(observation).outcome());
            // Retain actual erroneous behavior rather than replacing it with the expected empty ledger.
            assertEquals("sub_primary", ledger.snapshot().effects().get(new Key(bundle.installationId(), "in_primary")).subscriptionId());
            assertEquals(Status.RESOLVED, ledger.snapshot().receipts().getFirst().observation().actual().status());
            assertEquals(Status.UNRESOLVED, delivery.expected().status());
        }
    }
    @Test void supportedRefusalsNeverEraseAnExistingRenewalEffect() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root());
        var initial = Map.of("in_primary", new Effect("cus_case", "sub_secondary", Action.RECORD_PAID_RENEWAL));
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), initial);
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id);
                assertEquals(Outcome.NO_EFFECT, ledger.accept(observe(delivery, delivery.expected().json(), "COMPLETED")).outcome());
            }
            assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
            assertEquals(sequence.deliveries().size(), ledger.snapshot().receipts().size());
        }
    }
    @Test void aFailedStateLookupIsNotAConsumerExecutionFailureAndMissingOutputIsNotARefusal() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            for (String status : List.of("TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")) {
                var incomplete = observe(delivery, delivery.expected().json(), status);
                assertEquals(Assertion.NOT_EVALUATED, incomplete.assertion()); assertNotNull(incomplete.actual());
                assertEquals(status, incomplete.executionStatus());
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                assertEquals(Outcome.NO_EFFECT, ledger.accept(incomplete).outcome());
            }
            var missing = observe(delivery, null, "COMPLETED");
            assertEquals(Assertion.NOT_EVALUATED, missing.assertion()); assertNull(missing.actual());
            if (scenario.id().startsWith("failed-")) {
                var completed = observe(delivery, delivery.expected().json(), "COMPLETED");
                assertEquals(Assertion.PASSED, completed.assertion()); assertEquals(Reason.STATE_FAILED, completed.actual().reason());
            }
        }
    }
    @Test void workerInputsBindAllThirtyRawSourcesAndDoNotExposeAnswersOrPermissions() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root()); var inputs = new HashSet<String>(); var evidence = new HashSet<String>();
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
        assertEquals(20, inputs.size()); assertEquals(20, evidence.size()); assertEquals(30, sources.size());
    }
    @Test void immutableStateRegistrationCannotGrantFullCaseCoverage() throws Exception {
        var bundle = InvoiceRenewalStateFixtures.load(root());
        assertEquals(Set.of("unavailable-state"), bundle.familyIds()); assertEquals(10, bundle.scenarios().size());
        assertNotEquals(InvoiceRenewalDeliveryFixtures.BUNDLE_SHA256, bundle.identity());
        assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalAssociationFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalRefusalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalDeliveryFixtures.load(root()).requireFullCaseCoverage());
        var delivery = bundle.scenarios().getFirst().deliveries().get("webhook"); delivery.input().addProperty("familyId", "changed");
        assertEquals(delivery.expected().inputIdentity(), InvoiceRenewalOracle.inputIdentity(delivery.input()));
        byte[] original = bundle.bytes("state-absent-acacia.json"), changed = bundle.bytes("state-absent-acacia.json"); changed[0] = 0;
        assertArrayEquals(original, bundle.bytes("state-absent-acacia.json")); assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().clear());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../delivery-v1/bundle.json"));
    }
    @Test void tamperedMissingExtraAndChangedContractInputsCannotRegisterStateEvidence() throws Exception {
        for (String name : List.of("state-absent-acacia.json", "state-failed-basil.json", "state-wrong-subscription-basil.json",
                "absent-state-acacia-webhook.json", "expectations.json", "scenarios.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/state-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalStateFixtures.load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString());
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalStateFixtures.load(copy));
        }
        Path missing = copy(); Files.delete(missing.resolve("fixtures/state-v1/state-absent-acacia.json"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalStateFixtures.load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/state-v1/grant.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalStateFixtures.load(extra));
        Path contract = copy(); Files.writeString(contract.resolve("business-oracle.md"), "changed");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalStateFixtures.load(contract));
    }
}
