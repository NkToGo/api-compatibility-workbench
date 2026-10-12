package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalBoundaryFixtures.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalLedger.*;

class InvoiceRenewalBoundaryFixturesTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Control control(Bundle bundle, String id) { return bundle.controls().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(); }
    private Path copy() throws IOException {
        Path copy = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json")) Files.copy(root().resolve(name), copy.resolve(name));
        Path fixtures = Files.createDirectories(copy.resolve("fixtures/boundary-v1"));
        try (var entries = Files.newDirectoryStream(root().resolve("fixtures/boundary-v1"))) {
            for (Path file : entries) Files.copy(file, fixtures.resolve(file.getFileName()));
        }
        return copy;
    }
    private Observation exercise(Control control) {
        var oracle = control.admission().oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read"));
        var returned = control.expectedFact().json();
        switch (control.returnMode()) {
            case NO_DISPATCH -> throw new IllegalStateException("Blocked control cannot execute");
            case MISSING -> { }
            case MALFORMED -> {
                returned.addProperty("assertionOutcome", "PASSED");
                assertThrows(IllegalArgumentException.class, () -> oracle.request(InvoiceRenewalTestInputs.returned(returned)));
            }
            case TRAILING -> {
                oracle.request(InvoiceRenewalTestInputs.returned(returned));
                assertThrows(IllegalArgumentException.class, () -> oracle.request(InvoiceRenewalTestInputs.returned(returned)));
            }
            default -> {
                if (control.returnMode() == ReturnMode.UNBOUND) returned.addProperty("inputIdentity", "0".repeat(64));
                if (control.returnMode() == ReturnMode.WRONG_VERSION) returned.addProperty("effectiveApiVersion", "2025-02-24.acacia");
                oracle.request(InvoiceRenewalTestInputs.returned(returned));
            }
        }
        return oracle.observation(control.executionStatus());
    }
    private HostEvidence with(Control c, byte[] bytes, Verification signature, String signed, Verification adapter, String version) {
        var h = c.host(); return new HostEvidence(h.bundleIdentity(), h.inputIdentity(), h.evidenceIdentity(), bytes, signature, signed, adapter, version);
    }
    @Test void allThirtyFourConditionsMatchIndependentAdmissionAndExecutionExpectations() throws Exception {
        var bundle = load(root()); int admitted = 0, blocked = 0;
        for (var c : bundle.controls()) {
            var decision = c.admission(); assertEquals(c.expected().admitted(), decision.admitted(), c.id());
            assertEquals(c.expected().issues(), decision.issues(), c.id()); assertEquals(Set.of("admitted", "issues"), decision.json().keySet());
            if (!decision.admitted()) {
                assertNull(c.expectedFact()); assertNull(c.executionStatus()); assertNull(c.expected().assertion());
                assertThrows(IllegalArgumentException.class, decision::oracle); blocked++;
            } else {
                var observation = exercise(c); assertEquals(c.expected().assertion(), observation.assertion(), c.id());
                assertEquals(c.expected().actualPresent(), observation.actual() != null, c.id());
                assertEquals(c.expected().protocolInvalid(), observation.protocolInvalid(), c.id());
                assertEquals(c.executionStatus(), observation.executionStatus()); admitted++;
            }
        }
        assertEquals(10, admitted); assertEquals(24, blocked);
    }
    @Test void blockedPositionsHaveNoReceiptsAndIncompleteRunsRetainTheirOwnNoEffectReceiptsInBothOrders() throws Exception {
        // Golden protocol exchanges check host policy; no consumer, signature verifier or adapter executes.
        var bundle = load(root()); int sequences = 0, receipts = 0, blocked = 0;
        for (var c : bundle.controls()) for (String order : List.of("alone", "healthy-first", "healthy-last")) {
            var ledger = new InvoiceRenewalLedger("install_case", Map.of());
            var healthy = control(bundle, c.consumer().equals("invoice-webhook") ? "admit-reconciliation" : "admit-webhook");
            if (order.equals("healthy-first")) { ledger.accept(exercise(healthy)); receipts++; }
            if (!c.admission().admitted()) { assertThrows(IllegalArgumentException.class, () -> c.admission().oracle()); blocked++; }
            else {
                var observation = exercise(c); var receipt = ledger.accept(observation); receipts++;
                var expectedOutcome = c.expected().outcomeAlone();
                assertEquals(order.equals("healthy-first") && expectedOutcome == Outcome.APPENDED ? Outcome.DUPLICATE : expectedOutcome, receipt.outcome(), c.id());
                assertEquals(c.expected().assertion(), receipt.observation().assertion());
                assertEquals(c.expected().actualPresent(), receipt.observation().actual() != null);
            }
            if (order.equals("healthy-last")) { ledger.accept(exercise(healthy)); receipts++; }
            int expectedEffects = !order.equals("alone") || c.expected().outcomeAlone() == Outcome.APPENDED ? 1 : 0;
            assertEquals(expectedEffects, ledger.snapshot().effects().size(), c.id());
            assertEquals((order.equals("alone") ? 0 : 1) + (c.admission().admitted() ? 1 : 0), ledger.snapshot().receipts().size());
            assertTrue(ledger.snapshot().effects().values().stream().allMatch(e -> e.subscriptionId().equals("sub_primary"))); sequences++;
        }
        assertEquals(102, sequences); assertEquals(98, receipts); assertEquals(72, blocked);
    }
    @Test void rejectedAndIncompleteConditionsCannotEraseAnyExistingRenewalState() throws Exception {
        var initial = Map.of("in_primary", new Effect("cus_case", "sub_primary", Action.RECORD_PAID_RENEWAL),
                "in_other", new Effect("cus_case", "sub_secondary", Action.RECORD_PAID_RENEWAL));
        for (var c : load(root()).controls()) {
            var ledger = new InvoiceRenewalLedger("install_case", initial);
            if (c.admission().admitted()) {
                var receipt = ledger.accept(exercise(c));
                assertEquals(c.expected().outcomeAlone() == Outcome.APPENDED ? Outcome.DUPLICATE : Outcome.NO_EFFECT, receipt.outcome());
            } else assertThrows(IllegalArgumentException.class, () -> c.admission().oracle());
            assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
        }
    }
    @Test void aMatchingLedgerCannotTurnWrongVersionOrUnboundFactsIntoAcceptedResults() throws Exception {
        var bundle = load(root()); var wrong = control(bundle, "wrong-version-return"); var ledger = new InvoiceRenewalLedger("install_case", Map.of());
        ledger.accept(exercise(control(bundle, "admit-reconciliation")));
        var receipt = ledger.accept(exercise(wrong)); assertEquals(Outcome.DUPLICATE, receipt.outcome());
        assertEquals(Assertion.FAILED, receipt.observation().assertion()); assertEquals("2025-02-24.acacia", receipt.observation().actual().effectiveApiVersion());
        assertEquals("2025-03-31.basil", receipt.observation().expected().effectiveApiVersion()); assertEquals(1, ledger.snapshot().effects().size());
        var unbound = ledger.accept(exercise(control(bundle, "unbound-return")));
        assertEquals(Outcome.NO_EFFECT, unbound.outcome()); assertEquals(Assertion.FAILED, unbound.observation().assertion());
        assertFalse(unbound.observation().identitiesMatch()); assertEquals(3, ledger.snapshot().receipts().size());
    }
    @Test void missingVersionsStayUnknownDespiteKnownSdkEndpointAccountAndPayloadClaims() throws Exception {
        var bundle = load(root()); var ids = new HashSet<String>();
        for (String name : List.of("event-version-missing", "event-version-null", "request-version-missing")) {
            var c = control(bundle, name); assertTrue(c.input().get("effectiveApiVersion").isJsonNull());
            assertNull(c.expectedFact()); assertFalse(c.admission().admitted());
            assertEquals("29.0.0", c.input().getAsJsonObject("configuration").get("sdkVersion").getAsString()); assertTrue(ids.add(inputIdentity(c.input())));
        }
        var absent = control(bundle, "event-version-missing").input().getAsJsonObject("source").getAsJsonObject("payload");
        var explicit = control(bundle, "event-version-null").input().getAsJsonObject("source").getAsJsonObject("payload");
        assertFalse(absent.has("api_version")); assertTrue(explicit.get("api_version").isJsonNull());
        assertEquals(List.of(Issue.SIGNATURE_REJECTED), control(bundle, "forged-payload-permissions").admission().issues());
        assertTrue(control(bundle, "forged-payload-permissions").input().getAsJsonObject("untrustedClaims").get("signatureVerified").getAsBoolean());
    }
    @Test void previouslyRegisteredOldDeliveryVersionsRemainDistinctUnderTheNewGate() throws Exception {
        var bundle = InvoiceRenewalOldDeliveryFixtures.load(root());
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            var input = delivery.input(); boolean webhook = delivery.consumerId().equals("invoice-webhook");
            byte[] bytes = bundle.bytes(input.getAsJsonObject("source").get("file").getAsString());
            var host = new HostEvidence(bundle.identity(), delivery.inputIdentity(), delivery.evidenceIdentity(), bytes,
                    webhook ? Verification.VERIFIED : Verification.NOT_APPLICABLE, CaseInputs.hash(bytes),
                    webhook ? delivery.blocked() ? Verification.ABSENT : Verification.VERIFIED : Verification.NOT_APPLICABLE,
                    webhook && !delivery.blocked() ? "2025-02-24.acacia" : null);
            var decision = inspect(delivery.consumerId(), input, delivery.expected(), host);
            assertEquals(!delivery.blocked(), decision.admitted());
            if (delivery.blocked()) assertEquals(List.of(Issue.ADAPTER_ABSENT), decision.issues());
            else {
                var read = decision.oracle().request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
                assertEquals(delivery.inputIdentity(), read.get("inputIdentity").getAsString());
                assertEquals(webhook ? "2025-02-24.acacia" : "2025-03-31.basil", read.getAsJsonObject("input").get("effectiveApiVersion").getAsString());
                assertEquals("2025-03-31.basil", read.getAsJsonObject("input").getAsJsonObject("subscriptionState").get("apiVersion").getAsString());
            }
        }
    }
    @Test void signatureEvidenceCannotBeReboundToDifferentBytesOrPayloads() throws Exception {
        var c = control(load(root()), "admit-webhook"); byte[] changed = (new String(c.host().sourceBytes(), StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        var decision = inspect(c.consumer(), c.input(), c.expectedFact(), with(c, changed, Verification.VERIFIED,
                c.host().signedSourceIdentity(), Verification.VERIFIED, c.host().adapterVersion()));
        assertTrue(decision.issues().containsAll(List.of(Issue.SOURCE_DIGEST_MISMATCH, Issue.SIGNATURE_SOURCE_MISMATCH)));
        assertThrows(IllegalArgumentException.class, decision::oracle);
        var input = c.input(); input.getAsJsonObject("source").getAsJsonObject("payload").addProperty("untrusted", "Change permissions");
        var mismatch = inspect(c.consumer(), input, c.expectedFact(), c.host());
        assertTrue(mismatch.issues().containsAll(List.of(Issue.INPUT_IDENTITY_MISMATCH, Issue.EVIDENCE_IDENTITY_MISMATCH, Issue.SOURCE_PAYLOAD_MISMATCH)));
        assertThrows(IllegalArgumentException.class, mismatch::oracle);
    }
    @Test void multipleBlockingReasonsRemainVisibleWithoutEchoingUntrustedContents() throws Exception {
        var c = control(load(root()), "admit-webhook"); var input = c.input();
        input.getAsJsonObject("source").getAsJsonObject("payload").addProperty("api_version", "private-untrusted-version");
        var h = c.host(); var host = new HostEvidence(null, h.inputIdentity(), h.evidenceIdentity(), h.sourceBytes(), Verification.REJECTED,
                h.signedSourceIdentity(), Verification.NOT_CHECKED, h.adapterVersion());
        var decision = inspect(c.consumer(), input, c.expectedFact(), host);
        assertTrue(decision.issues().containsAll(List.of(Issue.INCOMPLETE_FIXTURE_IDENTITY, Issue.SOURCE_PAYLOAD_MISMATCH,
                Issue.SIGNATURE_REJECTED, Issue.UNKNOWN_EVENT_VERSION, Issue.ADAPTER_NOT_VERIFIED)));
        assertFalse(decision.json().toString().contains("private-untrusted-version")); assertThrows(IllegalArgumentException.class, decision::oracle);
    }
    @Test void invalidOversizedAndWrongScopeInputsCannotConstructAConsumerOracle() throws Exception {
        var c = control(load(root()), "admit-webhook");
        for (byte[] bytes : List.of("not json".getBytes(StandardCharsets.UTF_8), new byte[256 * 1024 + 1])) {
            var decision = inspect(c.consumer(), c.input(), c.expectedFact(), with(c, bytes, Verification.VERIFIED,
                    c.host().signedSourceIdentity(), Verification.VERIFIED, c.host().adapterVersion()));
            assertTrue(decision.issues().contains(Issue.INVALID_SOURCE)); assertThrows(IllegalArgumentException.class, decision::oracle);
        }
        var input = c.input(); input.add("scenarioId", JsonNull.INSTANCE);
        assertTrue(inspect(c.consumer(), input, c.expectedFact(), c.host()).issues().contains(Issue.INCOMPLETE_INPUT_SCOPE));
        input = c.input(); input.addProperty("effectiveApiVersion", "2025-02-24.acacia");
        assertTrue(inspect(c.consumer(), input, c.expectedFact(), c.host()).issues().contains(Issue.VERSION_BINDING_MISMATCH));
        assertThrows(IllegalArgumentException.class, () -> inspect("coordinator", c.input(), c.expectedFact(), c.host()));
    }
    @Test void expectedAnswersMustRemainBoundAndAreNeverIncludedInTheWorkerRead() throws Exception {
        var c = control(load(root()), "admit-webhook"); var bad = c.expectedFact().json(); bad.addProperty("evidenceIdentity", "0".repeat(64));
        var decision = inspect(c.consumer(), c.input(), Fact.parse(bad), c.host());
        assertEquals(List.of(Issue.EXPECTATION_BINDING_MISMATCH), decision.issues()); assertThrows(IllegalArgumentException.class, decision::oracle);
        var read = c.admission().oracle().request(InvoiceRenewalTestInputs.operation("read")).getAsJsonObject();
        assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), read.keySet());
        assertFalse(read.getAsJsonObject("input").has("expectedFact")); assertFalse(read.getAsJsonObject("input").has("signature"));
    }
    @Test void inputBytesDecisionsAndSealedObservationsRemainImmutable() throws Exception {
        var bundle = load(root()); var c = control(bundle, "admit-webhook"); var decision = c.admission();
        byte[] original = c.host().sourceBytes(); c.host().sourceBytes()[0] = 0; assertArrayEquals(original, c.host().sourceBytes());
        byte[] raw = bundle.bytes("event-basil.json"); bundle.bytes("event-basil.json")[0] = 0; assertArrayEquals(raw, bundle.bytes("event-basil.json"));
        c.input().addProperty("permission", "changed"); assertTrue(c.admission().admitted());
        decision.json().addProperty("admitted", false); assertTrue(decision.admitted());
        assertThrows(UnsupportedOperationException.class, () -> decision.issues().add(Issue.ADAPTER_ABSENT));
        assertThrows(UnsupportedOperationException.class, () -> bundle.controls().clear());
        var oracle = decision.oracle(); oracle.request(InvoiceRenewalTestInputs.operation("read")); oracle.request(InvoiceRenewalTestInputs.returned(c.expectedFact().json()));
        var observation = oracle.observation("COMPLETED"); assertThrows(IllegalArgumentException.class, () -> oracle.observation("TIMED_OUT"));
        assertSame(observation, oracle.observation("COMPLETED")); assertEquals(Assertion.PASSED, observation.assertion());
    }
    @Test void theControlRegistryAndAllPriorCorporaStillRejectFullCaseUse() throws Exception {
        var bundle = load(root()); assertEquals(34, bundle.controlIds().size()); assertEquals(Set.copyOf(CONTROL_IDS), bundle.controlIds());
        assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalAssociationFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalRefusalFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalDeliveryFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalStateFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalUnpaidFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalStateException.class, () -> InvoiceRenewalOldDeliveryFixtures.load(root()).requireFullCaseCoverage());
        assertThrows(IllegalArgumentException.class, () -> bundle.bytes("../rollout-v1/bundle.json"));
    }
    @Test void changedReceiptsControlsSourcesAndManifestsCannotAuthorizeDifferentConditions() throws Exception {
        for (String name : List.of("event-basil.json", "event-unknown.json", "invoice-basil.json", "controls.json", "expectations.json")) {
            Path copy = copy(), fixtures = copy.resolve("fixtures/boundary-v1"); Files.writeString(fixtures.resolve(name), "{}");
            assertThrows(IllegalArgumentException.class, () -> load(copy));
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(fixtures.resolve("bundle.json")));
            manifest.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(fixtures.resolve(name))));
            Files.writeString(fixtures.resolve("bundle.json"), manifest.toString()); assertThrows(IllegalArgumentException.class, () -> load(copy));
        }
        Path missing = copy(); Files.delete(missing.resolve("fixtures/boundary-v1/state-basil.json")); assertThrows(IllegalArgumentException.class, () -> load(missing));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/boundary-v1/permission.json"), "{}"); assertThrows(IllegalArgumentException.class, () -> load(extra));
        Path changed = copy(); Files.writeString(changed.resolve("business-oracle.md"), "changed"); assertThrows(IllegalArgumentException.class, () -> load(changed));
    }
}
