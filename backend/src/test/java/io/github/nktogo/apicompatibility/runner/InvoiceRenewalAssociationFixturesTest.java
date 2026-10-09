package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceRenewalAssociationFixturesTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/association-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/association-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private JsonObject invoice(InvoiceRenewalFixtures.Delivery delivery) {
        var payload = delivery.input().getAsJsonObject("source").getAsJsonObject("payload");
        return delivery.consumerId().equals("invoice-webhook") ? payload.getAsJsonObject("data").getAsJsonObject("object") : payload;
    }
    private JsonElement association(JsonObject invoice, String train) {
        return train.equals("acacia") ? invoice.get("subscription")
                : invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").get("subscription");
    }
    @Test void expansionIsRegisteredOnEitherPathForBothDeclaredVersions() throws Exception {
        var bundle = InvoiceRenewalAssociationFixtures.load(root()); int variants = 0;
        for (var scenario : bundle.scenarios()) if (scenario.id().startsWith("expanded-")) {
            String train = scenario.id().endsWith("acacia") ? "acacia" : "basil";
            for (String role : List.of("webhook", "reconciliation")) {
                var delivery = scenario.deliveries().get(role); var value = association(invoice(delivery), train);
                boolean expanded = scenario.id().startsWith("expanded-" + role + "-");
                if (expanded) { assertTrue(value.isJsonObject()); assertEquals("sub_primary", value.getAsJsonObject().get("id").getAsString()); }
                else assertEquals("sub_primary", value.getAsString());
                assertEquals("sub_primary", delivery.expected().subscriptionId());
                assertEquals(InvoiceRenewalOracle.Status.RESOLVED, delivery.expected().status());
            }
            variants++;
        }
        assertEquals(4, variants);
    }
    @Test void bothInvoicesHaveSeparateBindingsDespiteOneCustomerAndReversedStateLists() throws Exception {
        var bundle = InvoiceRenewalAssociationFixtures.load(root()); int variants = 0;
        for (var scenario : bundle.scenarios()) if (scenario.id().startsWith("two-subscriptions")) {
            assertEquals(4, scenario.deliveries().size());
            for (String role : List.of("webhook", "reconciliation")) for (String suffix : List.of("primary", "secondary")) {
                var delivery = scenario.deliveries().get(role + "-" + suffix);
                assertEquals("in_" + suffix, invoice(delivery).get("id").getAsString());
                assertEquals("cus_case", delivery.expected().customerId()); assertEquals("sub_" + suffix, delivery.expected().subscriptionId());
                var source = delivery.input().getAsJsonObject("subscriptionState");
                assertEquals("subscription-state-list", source.get("kind").getAsString());
                var list = source.getAsJsonObject("payload").getAsJsonArray("data");
                assertEquals(role.equals("webhook") ? "sub_primary" : "sub_secondary", list.get(0).getAsJsonObject().get("id").getAsString());
                assertEquals(2, list.size()); assertFalse(source.getAsJsonObject("payload").get("has_more").getAsBoolean());
            }
            variants++;
        }
        assertEquals(2, variants);
    }
    @Test void explicitStandaloneAndQuoteOriginsAreNotMissingAssociationResults() throws Exception {
        var bundle = InvoiceRenewalAssociationFixtures.load(root()); int variants = 0;
        for (var scenario : bundle.scenarios()) if (scenario.id().startsWith("standalone-invoice") || scenario.id().startsWith("quote-parent")) {
            boolean quote = scenario.id().startsWith("quote-parent"), acacia = scenario.id().endsWith("acacia");
            for (var delivery : scenario.deliveries().values()) {
                var invoice = invoice(delivery);
                assertEquals(quote ? "quote_accept" : "manual", invoice.get("billing_reason").getAsString());
                if (acacia) {
                    assertTrue(invoice.has("subscription")); assertTrue(invoice.get("subscription").isJsonNull()); assertFalse(invoice.has("parent"));
                    if (quote) assertEquals("qt_case", invoice.get("quote").getAsString());
                } else {
                    assertFalse(invoice.has("subscription")); assertTrue(invoice.has("parent"));
                    if (!quote) assertTrue(invoice.get("parent").isJsonNull());
                    else {
                        var parent = invoice.getAsJsonObject("parent"); assertEquals("quote_details", parent.get("type").getAsString());
                        assertTrue(parent.get("subscription_details").isJsonNull()); assertEquals("qt_case", parent.getAsJsonObject("quote_details").get("quote").getAsString());
                    }
                }
                assertEquals(InvoiceRenewalOracle.Status.NON_SUBSCRIPTION, delivery.expected().status());
                assertNull(delivery.expected().subscriptionId()); assertNull(delivery.expected().reason());
            }
            variants++;
        }
        assertEquals(4, variants);
    }
    @Test void fortyAuthoredSequencesAgreeWithHostPolicyWithoutRecordingConsumerExecution() throws Exception {
        // Supplying independently authored answers here tests policy consistency, not Stripe parsing.
        var bundle = InvoiceRenewalAssociationFixtures.load(root()); int sequences = 0, receipts = 0;
        for (var scenario : bundle.scenarios()) for (var sequence : scenario.sequences()) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            var outcomes = new ArrayList<InvoiceRenewalLedger.Outcome>();
            for (String id : sequence.deliveries()) {
                var delivery = scenario.deliveries().get(id); var oracle = delivery.oracle();
                oracle.request(InvoiceRenewalTestInputs.operation("read")); oracle.request(InvoiceRenewalTestInputs.returned(delivery.expected().json()));
                var observation = oracle.observation("COMPLETED"); assertEquals(InvoiceRenewalOracle.Assertion.PASSED, observation.assertion());
                outcomes.add(ledger.accept(observation).outcome()); receipts++;
            }
            assertEquals(sequence.outcomes(), outcomes, scenario.id() + "/" + sequence.id());
            var actual = new HashMap<String, InvoiceRenewalLedger.Effect>();
            ledger.snapshot().effects().forEach((key, effect) -> actual.put(key.invoiceId(), effect));
            assertEquals(sequence.finalEffects(), actual, scenario.id() + "/" + sequence.id());
            assertEquals(sequence.deliveries().size(), ledger.snapshot().receipts().size()); sequences++;
        }
        assertEquals(40, sequences); assertEquals(72, receipts);
    }
    @Test void aCustomerOnlyOrFirstEntryAnswerFailsEvenWhenItAppendsTwoMarkers() throws Exception {
        var bundle = InvoiceRenewalAssociationFixtures.load(root());
        var scenario = bundle.scenarios().stream().filter(s -> s.id().equals("two-subscriptions-basil")).findFirst().orElseThrow();
        for (String role : List.of("webhook", "reconciliation")) {
            var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
            String first = role.equals("webhook") ? "sub_primary" : "sub_secondary"; int failed = 0;
            for (String suffix : List.of("primary", "secondary")) {
                var delivery = scenario.deliveries().get(role + "-" + suffix); var result = delivery.expected().json();
                result.addProperty("subscriptionId", first); var oracle = delivery.oracle();
                oracle.request(InvoiceRenewalTestInputs.operation("read")); oracle.request(InvoiceRenewalTestInputs.returned(result));
                var observation = oracle.observation("COMPLETED"); if (observation.assertion() == InvoiceRenewalOracle.Assertion.FAILED) failed++;
                assertEquals(InvoiceRenewalLedger.Outcome.APPENDED, ledger.accept(observation).outcome());
            }
            assertEquals(2, ledger.snapshot().effects().size()); assertEquals(1, failed);
        }
    }
    @Test void rawSourcesAreBoundButWorkerInputDoesNotDiscloseExpectedAnswersOrAnExecutionGrant() throws Exception {
        var bundle = InvoiceRenewalAssociationFixtures.load(root()); var identities = new HashSet<String>();
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var input = delivery.input(); assertTrue(identities.add(delivery.expected().inputIdentity()));
            assertEquals(InvoiceRenewalOracle.inputIdentity(input), delivery.expected().inputIdentity());
            for (String field : List.of("source", "subscriptionState")) {
                var source = input.getAsJsonObject(field); var raw = bundle.bytes(source.get("file").getAsString());
                assertEquals(CaseInputs.hash(raw), source.get("sha256").getAsString()); assertEquals(InvoiceRenewalFixtures.object(raw), source.getAsJsonObject("payload"));
            }
            var oracle = delivery.oracle(); var response = oracle.request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
            assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), response.keySet());
            assertEquals(InvoiceRenewalOracle.Assertion.NOT_EVALUATED, oracle.observation("TIMED_OUT").assertion());
        }
        assertEquals(24, identities.size());
    }
    @Test void partialCorporaKeepSeparateIdentitiesAndStillBlockFullCoverage() throws Exception {
        var rollout = InvoiceRenewalFixtures.load(root()); var associations = InvoiceRenewalAssociationFixtures.load(root());
        assertNotEquals(rollout.identity(), associations.identity()); assertEquals(4, rollout.scenarios().size()); assertEquals(10, associations.scenarios().size());
        assertEquals(Set.of("expanded-association", "two-subscriptions", "standalone-invoice", "quote-parent"), associations.familyIds());
        assertThrows(IllegalStateException.class, rollout::requireFullCaseCoverage); assertThrows(IllegalStateException.class, associations::requireFullCaseCoverage);
        var delivery = associations.scenarios().getFirst().deliveries().get("webhook");
        delivery.input().addProperty("familyId", "changed"); assertEquals(delivery.expected().inputIdentity(), InvoiceRenewalOracle.inputIdentity(delivery.input()));
        byte[] original = associations.bytes("expectations.json"); var changed = associations.bytes("expectations.json"); changed[0] = 0;
        assertArrayEquals(original, associations.bytes("expectations.json"));
        assertThrows(UnsupportedOperationException.class, () -> associations.scenarios().clear());
        assertThrows(IllegalArgumentException.class, () -> associations.bytes("../rollout-v1/bundle.json"));
    }
    @Test void changedSourcesAnswersAndRewrittenManifestCannotAuthorizeNewEvidence() throws Exception {
        for (String name : List.of("state-list-basil-reverse.json", "quote-parent-acacia-webhook.json", "scenarios.json", "expectations.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/association-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalAssociationFixtures.load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString());
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalAssociationFixtures.load(copy));
        }
    }
    @Test void missingAnswersExtraEntriesAndChangedOriginalContractBlockRegistration() throws Exception {
        Path missing = copy(); Files.delete(missing.resolve("fixtures/association-v1/expectations.json"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalAssociationFixtures.load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/association-v1/grant.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalAssociationFixtures.load(extra));
        Path changed = copy(); Files.writeString(changed.resolve("business-oracle.md"), "changed");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalAssociationFixtures.load(changed));
    }
}
