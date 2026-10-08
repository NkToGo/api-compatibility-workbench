package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceRenewalFixturesTest {
    @TempDir Path temporary;
    private Path caseRoot() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(caseRoot().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/rollout-v1"));
        try (var entries = Files.newDirectoryStream(caseRoot().resolve("fixtures/rollout-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    @Test void registeredVersionsRetainRawSourcesAndEachConsumersConfiguration() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(caseRoot());
        assertEquals("install_case", bundle.installationId());
        assertEquals(List.of("legacy-agreement", "target-agreement", "request-first", "event-first"),
                bundle.scenarios().stream().map(InvoiceRenewalFixtures.Scenario::id).toList());
        var vectors = Map.of("legacy-agreement", List.of("2025-02-24.acacia", "2025-02-24.acacia"),
                "target-agreement", List.of("2025-03-31.basil", "2025-03-31.basil"),
                "request-first", List.of("2025-02-24.acacia", "2025-03-31.basil"),
                "event-first", List.of("2025-03-31.basil", "2025-02-24.acacia"));
        for (var scenario : bundle.scenarios()) {
            int path = 0;
            for (String role : List.of("webhook", "reconciliation")) {
                var delivery = scenario.deliveries().get(role); var input = delivery.input();
                String version = vectors.get(scenario.id()).get(path++);
                assertEquals(version, input.get("effectiveApiVersion").getAsString());
                assertEquals(version, delivery.expected().effectiveApiVersion());
                assertEquals(InvoiceRenewalOracle.inputIdentity(input), delivery.expected().inputIdentity());
                for (String field : List.of("source", "subscriptionState")) {
                    var source = input.getAsJsonObject(field);
                    byte[] raw = bundle.bytes(source.get("file").getAsString());
                    assertEquals(CaseInputs.hash(raw), source.get("sha256").getAsString());
                    assertEquals(InvoiceRenewalFixtures.object(raw), source.getAsJsonObject("payload"));
                    assertEquals(version, source.get("apiVersion").getAsString());
                }
                var config = input.getAsJsonObject("configuration");
                assertEquals("2025-02-24.acacia", config.get("accountApiVersion").getAsString());
                assertEquals(vectors.get(scenario.id()).getFirst(), config.get("endpointApiVersion").getAsString());
                if (role.equals("webhook")) assertEquals(version, input.getAsJsonObject("source").getAsJsonObject("payload").get("api_version").getAsString());
                else assertTrue(config.get("adapterApiVersion").isJsonNull());
            }
        }
    }
    @Test void fixturesExplicitlyRepresentTheTwoHistoricalAssociationLocations() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(caseRoot());
        var acacia = InvoiceRenewalFixtures.object(bundle.bytes("invoice-acacia.json"));
        var basil = InvoiceRenewalFixtures.object(bundle.bytes("invoice-basil.json"));
        assertEquals("sub_primary", acacia.get("subscription").getAsString()); assertFalse(acacia.has("parent"));
        assertFalse(basil.has("subscription")); var parent = basil.getAsJsonObject("parent");
        assertEquals("subscription_details", parent.get("type").getAsString());
        assertTrue(parent.get("quote_details").isJsonNull());
        assertEquals("sub_primary", parent.getAsJsonObject("subscription_details").get("subscription").getAsString());
        for (String train : List.of("acacia", "basil")) {
            var event = InvoiceRenewalFixtures.object(bundle.bytes("event-" + train + ".json"));
            assertFalse(event.get("livemode").getAsBoolean()); assertEquals("invoice.paid", event.get("type").getAsString());
            assertEquals(InvoiceRenewalFixtures.object(bundle.bytes("invoice-" + train + ".json")), event.getAsJsonObject("data").getAsJsonObject("object"));
        }
    }
    @Test void allAuthoredSequenceAnswersAgreeWithPureHostPolicyWithoutRunningAConsumer() throws Exception {
        // Golden facts supplied to a host unit test are not recorded consumer execution evidence.
        var bundle = InvoiceRenewalFixtures.load(caseRoot()); int sequences = 0;
        for (var scenario : bundle.scenarios()) {
            assertTrue(scenario.initialEffects().isEmpty()); assertEquals(4, scenario.sequences().size());
            for (var sequence : scenario.sequences()) {
                var ledger = new InvoiceRenewalLedger(bundle.installationId(), scenario.initialEffects());
                var outcomes = new ArrayList<InvoiceRenewalLedger.Outcome>();
                for (String id : sequence.deliveries()) {
                    var delivery = scenario.deliveries().get(id); var fact = delivery.expected();
                    assertEquals(InvoiceRenewalOracle.Status.RESOLVED, fact.status());
                    assertEquals("in_primary", fact.invoiceId()); assertEquals("cus_case", fact.customerId());
                    assertEquals("sub_primary", fact.subscriptionId()); assertNull(fact.reason());
                    var oracle = delivery.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read"));
                    oracle.request(InvoiceRenewalTestInputs.returned(fact.json()));
                    outcomes.add(ledger.accept(oracle.observation("COMPLETED")).outcome());
                }
                assertEquals(sequence.outcomes(), outcomes);
                var actual = new HashMap<String, InvoiceRenewalLedger.Effect>();
                ledger.snapshot().effects().forEach((key, effect) -> { assertEquals("install_case", key.installationId()); actual.put(key.invoiceId(), effect); });
                assertEquals(sequence.finalEffects(), actual); sequences++;
            }
        }
        assertEquals(16, sequences);
    }
    @Test void missingFamiliesAndAdmissionControlsBlockFullCaseCoverage() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(caseRoot());
        assertEquals(List.of("expanded-association", "two-subscriptions", "standalone-invoice", "quote-parent",
                "missing-association", "contradictory-parent", "duplicate-delivery", "conflicting-association",
                "unavailable-state", "not-paid", "old-delivery-after-upgrade"), bundle.missingScenarioIds());
        assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
    }
    @Test void workerReadsContainInputsAndIdentityButNoGoldenAnswersOrExecutionGrant() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(caseRoot());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var oracle = delivery.oracle(); var response = oracle.request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
            assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), response.keySet());
            assertEquals(delivery.input(), response.getAsJsonObject("input"));
            assertFalse(response.has("expected")); assertFalse(response.has("sequences")); assertFalse(response.has("permission"));
            assertEquals(InvoiceRenewalOracle.Assertion.NOT_EVALUATED, oracle.observation("INFRASTRUCTURE_ERROR").assertion());
        }
    }
    @Test void sourceAndGoldenAnswersHaveDistinctBoundIdentities() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(caseRoot());
        var requestFirst = bundle.scenarios().get(2);
        var webhook = requestFirst.deliveries().get("webhook").expected();
        var request = requestFirst.deliveries().get("reconciliation").expected();
        assertNotEquals(webhook.inputIdentity(), request.inputIdentity());
        assertNotEquals(webhook.evidenceIdentity(), request.evidenceIdentity());
        assertNotEquals(webhook.inputIdentity(), webhook.evidenceIdentity());
        var edited = requestFirst.deliveries().get("reconciliation").input();
        edited.getAsJsonObject("configuration").addProperty("adapterApiVersion", "2025-03-31.basil");
        assertNotEquals(request.inputIdentity(), InvoiceRenewalOracle.inputIdentity(edited));
    }
    @Test void returnedInputsAndBytesCannotMutateRegisteredEvidence() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(caseRoot());
        var delivery = bundle.scenarios().getFirst().deliveries().get("webhook");
        delivery.input().getAsJsonObject("source").addProperty("sha256", "changed");
        byte[] original = bundle.bytes("event-acacia.json"); byte[] changed = bundle.bytes("event-acacia.json"); changed[0] = 0;
        assertArrayEquals(original, bundle.bytes("event-acacia.json"));
        assertEquals(delivery.expected().inputIdentity(), InvoiceRenewalOracle.inputIdentity(delivery.input()));
        assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().clear());
        assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().getFirst().deliveries().clear());
        assertThrows(UnsupportedOperationException.class, () -> bundle.scenarios().getFirst().sequences().getFirst().finalEffects().clear());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../business-oracle.md"));
    }
    @Test void rawByteChangeIsRejectedEvenWhenParsedJsonIsUnchanged() throws Exception {
        Path root = copy(); Path source = root.resolve("fixtures/rollout-v1/event-acacia.json");
        byte[] original = Files.readAllBytes(source); Files.writeString(source, new String(original, StandardCharsets.UTF_8) + " ");
        assertEquals(InvoiceRenewalFixtures.object(original), InvoiceRenewalFixtures.object(Files.readAllBytes(source)));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(root));
    }
    @Test void rewritingTheManifestCannotAuthorizeChangedInputs() throws Exception {
        Path root = copy(); Path fixtures = root.resolve("fixtures/rollout-v1");
        Files.writeString(fixtures.resolve("expectations.json"), "{}");
        var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
        manifest.getAsJsonObject("files").addProperty("expectations.json", CaseInputs.hash(Files.readAllBytes(fixtures.resolve("expectations.json"))));
        Files.writeString(fixtures.resolve("bundle.json"), manifest.toString());
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(root));
    }
    @Test void changingAnOriginalContractFileOrIndexBlocksRegistration() throws Exception {
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json")) {
            Path root = copy(); Files.writeString(root.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(root), name);
        }
    }
    @Test void deletingEitherSourceOrExpectedAnswersBlocksRegistration() throws Exception {
        for (String name : List.of("event-acacia.json", "expectations.json", "scenarios.json")) {
            Path root = copy(); Files.delete(root.resolve("fixtures/rollout-v1").resolve(name));
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(root), name);
        }
    }
    @Test void unregisteredEntriesAndOversizedSourcesAreRejected() throws Exception {
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/rollout-v1/permission.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(extra));
        Path large = copy(); Files.write(large.resolve("fixtures/rollout-v1/event-acacia.json"), new byte[262145]);
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(large));
    }
    @Test void aDirectoryCannotReplaceARegisteredSource() throws Exception {
        Path root = copy(); Path source = root.resolve("fixtures/rollout-v1/event-acacia.json");
        Files.delete(source); Files.createDirectory(source);
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(root));
    }
    @Test @EnabledOnOs(OS.LINUX) void symbolicSourcesAndAncestorAliasesCannotEnterRegistration() throws Exception {
        Path linkedFile = copy(); Path source = linkedFile.resolve("fixtures/rollout-v1/event-acacia.json");
        Files.delete(source); Files.createSymbolicLink(source, caseRoot().resolve("fixtures/rollout-v1/event-acacia.json").toAbsolutePath());
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(linkedFile));
        Path original = copy(); Path alias = temporary.resolve("linked-case"); Files.createSymbolicLink(alias, original);
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.load(alias));
    }
    @Test void strictJsonRejectsDuplicatesTrailingDataMalformedUtf8AndExcessiveNesting() {
        for (String value : List.of("{\"id\":1,\"id\":2}", "{\"nested\":{\"id\":1,\"id\":2}}",
                "{} {}", "{unquoted:1}", "[1]", "{\"value\":NaN}", "{\"value\":" + "[".repeat(26) + "null" + "]".repeat(26) + "}",
                "{\"values\":[" + String.join(",", Collections.nCopies(257, "0")) + "]}"))
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.object(value.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.object(new byte[]{'{', '"', (byte) 0xff, '"', ':', '1', '}'}));
    }
    @Test void strictJsonPreservesExplicitNullAndRejectsWithoutEchoingPayloads() {
        var explicit = InvoiceRenewalFixtures.object("{\"adapter\":null}".getBytes(StandardCharsets.UTF_8));
        var absent = InvoiceRenewalFixtures.object("{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(explicit.get("adapter").isJsonNull()); assertFalse(absent.has("adapter"));
        assertNotEquals(InvoiceRenewalOracle.inputIdentity(explicit), InvoiceRenewalOracle.inputIdentity(absent));
        String sentinel = "private payload sentinel";
        var error = assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.object(("{\"" + sentinel + "\":}").getBytes(StandardCharsets.UTF_8)));
        assertEquals("Invalid invoice fixture JSON", error.getMessage()); assertNull(error.getCause());
    }
}
