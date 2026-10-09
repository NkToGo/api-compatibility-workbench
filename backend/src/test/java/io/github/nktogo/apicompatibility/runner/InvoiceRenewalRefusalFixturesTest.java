package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceRenewalRefusalFixturesTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/refusal-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/refusal-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private InvoiceRenewalFixtures.Scenario scenario(InvoiceRenewalVariantFixtures.Bundle bundle, String id) {
        return bundle.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
    private JsonObject invoice(InvoiceRenewalFixtures.Delivery delivery) {
        var payload = delivery.input().getAsJsonObject("source").getAsJsonObject("payload");
        return delivery.consumerId().equals("invoice-webhook") ? payload.getAsJsonObject("data").getAsJsonObject("object") : payload;
    }
    @Test void missingAndExplicitNullValuesRemainDistinctInputsWithTheSameMissingAssociationPolicy() throws Exception {
        var bundle = InvoiceRenewalRefusalFixtures.load(root());
        for (String role : List.of("webhook", "reconciliation")) {
            var missing = scenario(bundle, "missing-field-acacia").deliveries().get(role);
            var explicit = scenario(bundle, "null-association-acacia").deliveries().get(role);
            assertFalse(invoice(missing).has("subscription")); assertTrue(invoice(explicit).get("subscription").isJsonNull());
            assertNotEquals(missing.expected().inputIdentity(), explicit.expected().inputIdentity());
            var absentParent = invoice(scenario(bundle, "missing-parent-basil").deliveries().get(role));
            var nullParent = invoice(scenario(bundle, "null-parent-basil").deliveries().get(role));
            assertFalse(absentParent.has("parent")); assertTrue(nullParent.get("parent").isJsonNull());
            var absentDetails = invoice(scenario(bundle, "missing-details-basil").deliveries().get(role)).getAsJsonObject("parent");
            var nullDetails = invoice(scenario(bundle, "null-details-basil").deliveries().get(role)).getAsJsonObject("parent");
            assertFalse(absentDetails.has("subscription_details")); assertTrue(nullDetails.get("subscription_details").isJsonNull());
            var absentId = invoice(scenario(bundle, "missing-id-basil").deliveries().get(role)).getAsJsonObject("parent").getAsJsonObject("subscription_details");
            var nullId = invoice(scenario(bundle, "null-id-basil").deliveries().get(role)).getAsJsonObject("parent").getAsJsonObject("subscription_details");
            assertFalse(absentId.has("subscription")); assertTrue(nullId.get("subscription").isJsonNull());
        }
        long missingVariants = bundle.scenarios().stream().filter(s -> !s.id().startsWith("unknown-") && !s.id().startsWith("quote-") && !s.id().startsWith("conflicting-")).count();
        assertEquals(8, missingVariants);
    }
    @Test void threeAuthoredBasilContradictionsCannotChooseAnAssociationWinner() throws Exception {
        var bundle = InvoiceRenewalRefusalFixtures.load(root());
        for (String role : List.of("webhook", "reconciliation")) {
            var unknown = invoice(scenario(bundle, "unknown-parent-basil").deliveries().get(role));
            assertEquals("unregistered_origin", unknown.getAsJsonObject("parent").get("type").getAsString());
            var quote = invoice(scenario(bundle, "quote-with-subscription-basil").deliveries().get(role));
            assertEquals("quote_accept", quote.get("billing_reason").getAsString());
            assertEquals("quote_details", quote.getAsJsonObject("parent").get("type").getAsString());
            assertEquals("sub_primary", quote.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription").getAsString());
            var conflict = invoice(scenario(bundle, "conflicting-fields-basil").deliveries().get(role));
            assertEquals("sub_secondary", conflict.get("subscription").getAsString());
            assertEquals("sub_primary", conflict.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription").getAsString());
            for (String id : List.of("unknown-parent-basil", "quote-with-subscription-basil", "conflicting-fields-basil")) {
                var expected = scenario(bundle, id).deliveries().get(role).expected();
                assertEquals(InvoiceRenewalOracle.Status.UNRESOLVED, expected.status()); assertNull(expected.subscriptionId());
                assertEquals(InvoiceRenewalOracle.Reason.INVALID_ASSOCIATION, expected.reason());
            }
        }
    }
    @Test void allFortyFourAuthoredRefusalSequencesPreserveFactsAndCreateNoEffects() throws Exception {
        // Unit-policy consistency with golden facts is not actual consumer execution evidence.
        var bundle = InvoiceRenewalRefusalFixtures.load(root()); int sequences = 0, receipts = 0;
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            assertTrue(scenario.initialEffects().isEmpty());
            var outcomes = new ArrayList<InvoiceRenewalLedger.Outcome>();
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id); var expected = delivery.expected();
                assertEquals(InvoiceRenewalOracle.Status.UNRESOLVED, expected.status()); assertEquals("in_primary", expected.invoiceId());
                assertEquals("cus_case", expected.customerId()); assertNull(expected.subscriptionId());
                assertEquals(scenario.id().startsWith("unknown-") || scenario.id().startsWith("quote-") || scenario.id().startsWith("conflicting-")
                        ? InvoiceRenewalOracle.Reason.INVALID_ASSOCIATION : InvoiceRenewalOracle.Reason.MISSING_ASSOCIATION, expected.reason());
                var oracle = delivery.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read")); oracle.request(InvoiceRenewalTestInputs.returned(expected.json()));
                var observation = oracle.observation("COMPLETED"); assertEquals(InvoiceRenewalOracle.Assertion.PASSED, observation.assertion());
                outcomes.add(ledger.accept(observation).outcome()); receipts++;
            }
            assertEquals(sequence.outcomes(), outcomes); assertTrue(sequence.finalEffects().isEmpty()); assertTrue(ledger.snapshot().effects().isEmpty());
            assertEquals(sequence.deliveries().size(), ledger.snapshot().receipts().size()); sequences++;
        }
        assertEquals(44, sequences); assertEquals(66, receipts);
    }
    @Test void aNonSubscriptionAnswerFailsEvenThoughTheFinalLedgerWouldAlsoBeEmpty() throws Exception {
        var bundle = InvoiceRenewalRefusalFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var wrong = delivery.expected().json(); wrong.addProperty("status", "NON_SUBSCRIPTION"); wrong.add("reason", JsonNull.INSTANCE);
            var oracle = delivery.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read")); oracle.request(InvoiceRenewalTestInputs.returned(wrong));
            var observation = oracle.observation("COMPLETED"); assertEquals(InvoiceRenewalOracle.Assertion.FAILED, observation.assertion());
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            assertEquals(InvoiceRenewalLedger.Outcome.NO_EFFECT, ledger.accept(observation).outcome());
            assertTrue(ledger.snapshot().effects().isEmpty());
        }
    }
    @Test void failedOrMissingConsumerOutputCannotBecomeAPassingRefusal() throws Exception {
        var bundle = InvoiceRenewalRefusalFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var failed = delivery.oracle(); failed.request(InvoiceRenewalTestInputs.operation("read"));
            failed.request(InvoiceRenewalTestInputs.returned(delivery.expected().json()));
            var observation = failed.observation("INFRASTRUCTURE_ERROR");
            assertEquals(InvoiceRenewalOracle.Assertion.NOT_EVALUATED, observation.assertion()); assertNotNull(observation.actual());
            var missing = delivery.oracle(); missing.request(InvoiceRenewalTestInputs.operation("read"));
            var noResult = missing.observation("COMPLETED");
            assertEquals(InvoiceRenewalOracle.Assertion.NOT_EVALUATED, noResult.assertion()); assertNull(noResult.actual());
        }
    }
    @Test void workerReadsBindRawVersionsAndBytesWithoutAnswersOrExecutionPermission() throws Exception {
        var bundle = InvoiceRenewalRefusalFixtures.load(root()); var inputs = new HashSet<String>(); var evidence = new HashSet<String>();
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var input = delivery.input(); assertTrue(inputs.add(delivery.expected().inputIdentity())); assertTrue(evidence.add(delivery.expected().evidenceIdentity()));
            assertEquals(InvoiceRenewalOracle.inputIdentity(input), delivery.expected().inputIdentity());
            for (String field : List.of("source", "subscriptionState")) {
                var source = input.getAsJsonObject(field); byte[] raw = bundle.bytes(source.get("file").getAsString());
                assertEquals(CaseInputs.hash(raw), source.get("sha256").getAsString()); assertEquals(InvoiceRenewalFixtures.object(raw), source.getAsJsonObject("payload"));
                assertEquals(delivery.expected().effectiveApiVersion(), source.get("apiVersion").getAsString());
            }
            var response = delivery.oracle().request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
            assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), response.keySet());
            assertEquals(input, response.getAsJsonObject("input"));
        }
        assertEquals(22, inputs.size()); assertEquals(22, evidence.size());
    }
    @Test void bytesAndAnswersRemainPinnedAndNeitherPartialCorpusCanAuthorizeFullCoverage() throws Exception {
        var bundle = InvoiceRenewalRefusalFixtures.load(root());
        assertEquals(Set.of("missing-association", "contradictory-parent"), bundle.familyIds()); assertEquals(11, bundle.scenarios().size());
        assertNotEquals(InvoiceRenewalAssociationFixtures.BUNDLE_SHA256, bundle.identity());
        assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalAssociationFixtures.load(root()).requireFullCaseCoverage());
        var delivery = bundle.scenarios().getFirst().deliveries().get("webhook"); delivery.input().addProperty("familyId", "changed");
        assertEquals(delivery.expected().inputIdentity(), InvoiceRenewalOracle.inputIdentity(delivery.input()));
        byte[] original = bundle.bytes("scenarios.json"), changed = bundle.bytes("scenarios.json"); changed[0] = 0;
        assertArrayEquals(original, bundle.bytes("scenarios.json")); assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().clear());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../association-v1/bundle.json"));
        for (String name : List.of("missing-field-acacia-webhook.json", "state-primary-basil.json", "expectations.json", "scenarios.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/refusal-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRefusalFixtures.load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString());
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRefusalFixtures.load(copy));
        }
    }
    @Test void missingExtraAndChangedContractEntriesBlockRefusalRegistration() throws Exception {
        Path missing = copy(); Files.delete(missing.resolve("fixtures/refusal-v1/expectations.json"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRefusalFixtures.load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/refusal-v1/grant.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRefusalFixtures.load(extra));
        Path contract = copy(); Files.writeString(contract.resolve("business-oracle.md"), "changed");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRefusalFixtures.load(contract));
    }
}
