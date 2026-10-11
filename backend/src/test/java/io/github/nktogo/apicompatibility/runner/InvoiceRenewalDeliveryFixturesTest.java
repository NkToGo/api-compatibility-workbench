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

class InvoiceRenewalDeliveryFixturesTest {
    @Test void previouslyRegisteredDeliveryIdentitiesRemainUnchanged() throws Exception {
        var reference = InvoiceRenewalFixtures.object(Files.readAllBytes(Path.of(System.getProperty("workbench.repository"),
                "backend/src/test/resources/invoice-delivery-v1-identities.json")));
        var bundle = InvoiceRenewalDeliveryFixtures.load(root()); var keys = new HashSet<String>();
        assertEquals(bundle.identity(), reference.get("bundleIdentity").getAsString());
        for (var value : reference.getAsJsonArray("deliveries")) {
            var row = value.getAsJsonObject();
            assertTrue(keys.add(row.get("scenarioId").getAsString() + "/" + row.get("deliveryId").getAsString()));
            var delivery = scenario(bundle, row.get("scenarioId").getAsString()).deliveries().get(row.get("deliveryId").getAsString());
            assertEquals(row.get("inputIdentity").getAsString(), delivery.expected().inputIdentity());
            assertEquals(row.get("evidenceIdentity").getAsString(), delivery.expected().evidenceIdentity());
        }
        assertEquals(14, keys.size());
    }
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/delivery-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/delivery-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private InvoiceRenewalFixtures.Scenario scenario(InvoiceRenewalVariantFixtures.Bundle bundle, String id) {
        return bundle.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
    private InvoiceRenewalFixtures.Sequence sequence(InvoiceRenewalFixtures.Scenario scenario, String id) {
        return scenario.sequences().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
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
    private Snapshot run(InvoiceRenewalVariantFixtures.Bundle bundle, InvoiceRenewalFixtures.Scenario scenario,
                         InvoiceRenewalFixtures.Sequence sequence) {
        var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
        for (String id : sequence.deliveries()) {
            var delivery = scenario.deliveries().get(id);
            ledger.accept(observe(delivery, delivery.expected().json(), "COMPLETED"));
        }
        return ledger.snapshot();
    }
    private Map<Key, Effect> keys(String installation, Map<String, Effect> effects) {
        var result = new HashMap<Key, Effect>(); effects.forEach((id, effect) -> result.put(new Key(installation, id), effect));
        return result;
    }
    @Test void allTwentyFourAuthoredSequencesPreserveFiftyFourOrderedReceiptsAndExpectedEffects() throws Exception {
        // Golden facts check host-policy consistency; they are not recorded consumer executions.
        var bundle = InvoiceRenewalDeliveryFixtures.load(root()); int sequences = 0, receipts = 0;
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var snapshot = run(bundle, scenario, sequence);
            assertEquals(keys(bundle.installationId(), scenario.initialEffects()), snapshot.initialEffects());
            assertEquals(keys(bundle.installationId(), sequence.finalEffects()), snapshot.effects());
            assertEquals(sequence.outcomes(), snapshot.receipts().stream().map(Receipt::outcome).toList());
            assertEquals(sequence.deliveries().size(), snapshot.receipts().size());
            for (int i = 0; i < sequence.deliveries().size(); i++) {
                var receipt = snapshot.receipts().get(i); var delivery = scenario.deliveries().get(sequence.deliveries().get(i));
                assertEquals(i + 1, receipt.sequence()); assertEquals(Assertion.PASSED, receipt.observation().assertion());
                assertEquals(Status.RESOLVED, receipt.observation().actual().status());
                assertEquals(delivery.expected(), receipt.observation().actual());
                assertEquals(delivery.expected().evidenceIdentity(), receipt.observation().actual().evidenceIdentity()); receipts++;
            }
            sequences++;
        }
        assertEquals(24, sequences); assertEquals(54, receipts);
    }
    @Test void repeatedSameEventDifferentEventAndRepeatedRetrievalKeepOneEffectAndEveryReceipt() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        for (String train : List.of("acacia", "basil")) {
            var scenario = scenario(bundle, "duplicate-delivery-" + train);
            var first = scenario.deliveries().get("webhook"); var alternate = scenario.deliveries().get("webhook-alternate");
            assertEquals(invoice(first), invoice(alternate));
            assertNotEquals(first.input().getAsJsonObject("source").getAsJsonObject("payload").get("id"),
                    alternate.input().getAsJsonObject("source").getAsJsonObject("payload").get("id"));
            assertNotEquals(first.expected().inputIdentity(), alternate.expected().inputIdentity());
            assertNotEquals(first.expected().evidenceIdentity(), alternate.expected().evidenceIdentity());
            assertEquals(List.of("webhook", "webhook", "webhook-alternate"), sequence(scenario, "webhook-alone").deliveries());
            assertEquals(List.of("reconciliation", "reconciliation"), sequence(scenario, "reconciliation-alone").deliveries());
            for (var sequence : scenario.sequences()) {
                var snapshot = run(bundle, scenario, sequence); var retained = snapshot.receipts();
                assertEquals(1, snapshot.effects().size()); assertTrue(snapshot.initialEffects().isEmpty());
                assertEquals(Outcome.APPENDED, retained.getFirst().outcome());
                assertTrue(retained.subList(1, retained.size()).stream().allMatch(r -> r.outcome() == Outcome.DUPLICATE));
                // Two deliveries of exactly the same input retain two separate host observations.
                assertEquals(retained.get(0).observation().actual(), retained.get(1).observation().actual());
                assertNotSame(retained.get(0).observation(), retained.get(1).observation());
            }
        }
    }
    @Test void locallyValidSeededConflictsPreserveTheOriginalEffectInBothPathOrders() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        for (String train : List.of("acacia", "basil")) {
            var scenario = scenario(bundle, "seeded-conflict-" + train);
            assertEquals("sub_primary", scenario.initialEffects().get("in_primary").subscriptionId());
            for (var delivery : scenario.deliveries().values()) assertEquals("sub_secondary", delivery.expected().subscriptionId());
            for (var sequence : scenario.sequences()) {
                var snapshot = run(bundle, scenario, sequence);
                assertEquals(snapshot.initialEffects(), snapshot.effects());
                assertTrue(snapshot.receipts().stream().allMatch(r -> r.outcome() == Outcome.CONFLICT
                        && r.observation().assertion() == Assertion.PASSED));
            }
        }
    }
    @Test void freshDisagreementRetainsTheFirstValueAndTheConflictInEitherOrder() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        for (String train : List.of("acacia", "basil")) {
            var scenario = scenario(bundle, "fresh-conflict-" + train); assertTrue(scenario.initialEffects().isEmpty());
            assertEquals("sub_primary", scenario.deliveries().get("webhook").expected().subscriptionId());
            assertEquals("sub_secondary", scenario.deliveries().get("reconciliation").expected().subscriptionId());
            for (String order : List.of("webhook-first", "reconciliation-first")) {
                var snapshot = run(bundle, scenario, sequence(scenario, order));
                assertEquals(List.of(Outcome.APPENDED, Outcome.CONFLICT), snapshot.receipts().stream().map(Receipt::outcome).toList());
                assertEquals(order.equals("webhook-first") ? "sub_primary" : "sub_secondary",
                        snapshot.effects().get(new Key(bundle.installationId(), "in_primary")).subscriptionId());
                assertTrue(snapshot.receipts().stream().allMatch(r -> r.observation().assertion() == Assertion.PASSED));
            }
        }
    }
    @Test void wrongSeededAnswerCannotTurnAnExpectedConflictIntoAPassingDuplicate() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        for (var scenario : bundle.scenarios()) if (scenario.id().startsWith("seeded-conflict-"))
            for (var delivery : scenario.deliveries().values()) {
                var wrong = delivery.expected().json(); wrong.addProperty("subscriptionId", "sub_primary");
                var observation = observe(delivery, wrong, "COMPLETED");
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                var receipt = ledger.accept(observation);
                assertEquals(Assertion.FAILED, observation.assertion()); assertEquals(Outcome.DUPLICATE, receipt.outcome());
                assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
                assertEquals("sub_primary", receipt.observation().actual().subscriptionId());
                assertEquals("sub_secondary", delivery.expected().subscriptionId());
            }
    }
    @Test void choosingOneFreshAssociationWinnerRetainsFailedFactsEvenWhenTheFinalValueMatches() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        for (var scenario : bundle.scenarios()) if (scenario.id().startsWith("fresh-conflict-"))
            for (String wrongRole : List.of("webhook", "reconciliation")) {
                var delivery = scenario.deliveries().get(wrongRole); var wrong = delivery.expected().json();
                wrong.addProperty("subscriptionId", wrongRole.equals("webhook") ? "sub_secondary" : "sub_primary");
                var failed = observe(delivery, wrong, "COMPLETED");
                for (String order : List.of("webhook-first", "reconciliation-first")) {
                    var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                    for (String role : sequence(scenario, order).deliveries()) {
                        var good = scenario.deliveries().get(role);
                        ledger.accept(role.equals(wrongRole) ? failed : observe(good, good.expected().json(), "COMPLETED"));
                    }
                    assertEquals(Assertion.FAILED, failed.assertion());
                    assertEquals(List.of(Outcome.APPENDED, Outcome.DUPLICATE), ledger.snapshot().receipts().stream().map(Receipt::outcome).toList());
                    assertTrue(ledger.snapshot().receipts().stream().anyMatch(r -> r.observation() == failed));
                    assertEquals(wrong.get("subscriptionId").getAsString(),
                            ledger.snapshot().effects().get(new Key(bundle.installationId(), "in_primary")).subscriptionId());
                }
            }
    }
    @Test void incompleteAndUnboundAttemptsCannotChangeTheSeededLedger() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        for (var scenario : bundle.scenarios()) if (scenario.id().startsWith("seeded-conflict-"))
            for (var delivery : scenario.deliveries().values()) {
                var wrongIdentity = delivery.expected().json(); wrongIdentity.addProperty("inputIdentity", "a".repeat(64));
                var observations = List.of(observe(delivery, delivery.expected().json(), "INFRASTRUCTURE_ERROR"),
                        observe(delivery, null, "COMPLETED"), observe(delivery, wrongIdentity, "COMPLETED"));
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                for (var observation : observations) assertEquals(Outcome.NO_EFFECT, ledger.accept(observation).outcome());
                assertEquals(Assertion.NOT_EVALUATED, observations.get(0).assertion()); assertNotNull(observations.get(0).actual());
                assertEquals(Assertion.NOT_EVALUATED, observations.get(1).assertion()); assertNull(observations.get(1).actual());
                assertEquals(Assertion.FAILED, observations.get(2).assertion()); assertFalse(observations.get(2).identitiesMatch());
                assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects()); assertEquals(3, ledger.snapshot().receipts().size());
            }
    }
    @Test void workerReadsBindAllRawSourcesWithoutAnswersInitialEffectsOrExecutionGrants() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root()); var inputs = new HashSet<String>(); var evidence = new HashSet<String>();
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
            var state = input.getAsJsonObject("subscriptionState").getAsJsonObject("payload");
            assertEquals(delivery.expected().subscriptionId(), state.get("id").getAsString());
            assertEquals("cus_case", state.get("customer").getAsString()); assertEquals("active", state.get("status").getAsString());
            var invoice = invoice(delivery); assertEquals("in_primary", invoice.get("id").getAsString());
            assertEquals(delivery.expected().subscriptionId(), scenario.id().endsWith("acacia") ? invoice.get("subscription").getAsString()
                    : invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription").getAsString());
            var response = delivery.oracle().request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
            assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), response.keySet());
        }
        assertEquals(14, inputs.size()); assertEquals(14, evidence.size()); assertEquals(18, sources.size());
    }
    @Test void separatePinnedBytesAndImmutableInitialStateCannotAuthorizeFullCaseCoverage() throws Exception {
        var bundle = InvoiceRenewalDeliveryFixtures.load(root());
        assertEquals(Set.of("duplicate-delivery", "conflicting-association"), bundle.familyIds()); assertEquals(6, bundle.scenarios().size());
        assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertNotEquals(InvoiceRenewalRefusalFixtures.BUNDLE_SHA256, bundle.identity());
        assertThrows(UnsupportedOperationException.class, () -> scenario(bundle, "seeded-conflict-basil").initialEffects().clear());
        byte[] original = bundle.bytes("scenarios.json"), changed = bundle.bytes("scenarios.json"); changed[0] = 0;
        assertArrayEquals(original, bundle.bytes("scenarios.json")); assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().clear());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../refusal-v1/bundle.json"));
        for (String name : List.of("duplicate-delivery-acacia-webhook-alternate.json", "state-secondary-basil.json", "scenarios.json", "expectations.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/delivery-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalDeliveryFixtures.load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString());
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalDeliveryFixtures.load(copy));
        }
        Path missing = copy(); Files.delete(missing.resolve("fixtures/delivery-v1/expectations.json"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalDeliveryFixtures.load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/delivery-v1/grant.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalDeliveryFixtures.load(extra));
        Path contract = copy(); Files.writeString(contract.resolve("business-oracle.md"), "changed");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalDeliveryFixtures.load(contract));
    }
}
