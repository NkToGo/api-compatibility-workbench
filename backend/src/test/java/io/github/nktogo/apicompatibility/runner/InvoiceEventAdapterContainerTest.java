package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookAdaptersTest.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceEventAdapterContainerTest {
    private static String image() { return System.getProperty("workbench.invoice.image"); }
    private static String name() { return "acw-" + UUID.randomUUID(); }
    private static InvoiceConsumerBuilds.Registration registration() throws Exception { return InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()); }
    private static void archive(String id, JsonObject receipt) throws Exception {
        Path directory = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-adapter-tests"); Files.createDirectories(directory); CaseInputs.write(directory.resolve(id + ".json"), receipt);
    }
    private static InvoiceWebhookAdapters.Receipt verify(byte[] bytes, String main, String version, InvoiceWebhookSignatures.Receipt auth) throws Exception {
        return InvoiceWebhookAdapters.verify(registration(), bytes, main, version, SCOPE, auth, image(), name(), Duration.ofSeconds(30), () -> false);
    }
    @Test void bothCurrentRoutesAndExplicitLegacyAdapterUseActualPinnedSdkParsers() throws Exception {
        for (var route : List.of(List.of("28.4.0", ACACIA), List.of("29.0.0", BASIL), List.of("29.0.0", ACACIA))) {
            String main = route.get(0), version = route.get(1); byte[] bytes = source(version); var receipt = verify(bytes, main, version, authenticate(bytes));
            var json = receipt.json(); archive(main + "-" + version, json); assertTrue(receipt.verified(), json.toString());
            assertEquals(InvoiceWebhookAdapters.Outcome.VERIFIED, receipt.outcome()); assertEquals(CaseInputs.hash(bytes), receipt.sourceIdentity());
            assertEquals(main, json.get("mainSdk").getAsString()); assertEquals(InvoiceWebhookAdapters.api(main), json.get("mainApiVersion").getAsString());
            assertEquals(InvoiceWebhookAdapters.sdk(version), json.get("adapterSdk").getAsString()); assertEquals(version, json.get("adapterApiVersion").getAsString());
            assertEquals(InvoiceWebhookAdapters.api(main).equals(version), json.get("primaryAccepted").getAsBoolean()); assertTrue(json.get("adapterAccepted").getAsBoolean());
            assertTrue(json.get("containerRemoved").getAsBoolean()); assertEquals("COMPLETED", json.get("executionStatus").getAsString());
            assertFalse(json.has("invoice")); assertFalse(json.has("source")); assertFalse(json.toString().contains(SECRET));
            for (String identity : List.of("harnessIdentity", "dependenciesIdentity", "configurationIdentity", "environmentIdentity", "projectionIdentity", "receiptIdentity")) assertTrue(json.get(identity).getAsString().matches("[0-9a-f]{64}"));
        }
    }
    @Test void realReceiptsGateBusinessExecutionWithoutTrustingAuthoredFlagsOrDiagnosticCopies() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(InvoiceSandboxTest.root()); var delivery = InvoiceSandboxTest.delivery("target-agreement", "webhook");
        byte[] bytes = source(BASIL); var auth = authenticate(bytes); var receipt = verify(bytes, "29.0.0", BASIL, auth);
        var host = new HostEvidence(bundle.identity(), delivery.expected().inputIdentity(), delivery.expected().evidenceIdentity(), bytes,
                Verification.NOT_CHECKED, null, Verification.NOT_CHECKED, null);
        var decision = inspectVerifiedWebhook(delivery.input(), delivery.expected(), host, SCOPE, auth, receipt); assertTrue(decision.admitted(), decision.issues().toString());
        var diagnostic = receipt.json(); diagnostic.addProperty("outcome", "FORGED"); var copy = decision.adapterEvidence(); copy.addProperty("outcome", "FORGED");
        assertEquals("VERIFIED", decision.adapterEvidence().get("outcome").getAsString());
        var execution = InvoiceSandbox.run(registration(), "webhook-basil-source", decision, image(), name(), Duration.ofSeconds(30), () -> false);
        assertEquals("PASSED", execution.getAsJsonObject("observation").get("assertionOutcome").getAsString());
        assertEquals(receipt.json(), execution.get("adapterVerification")); assertEquals(auth.json(), execution.get("signatureVerification")); archive("verified-business-exchange", execution);
        var freshAuth = authenticate(bytes, SCOPE, 1700000001L);
        assertTrue(inspectVerifiedWebhook(delivery.input(), delivery.expected(), host, SCOPE, freshAuth, receipt).issues().contains(Issue.ADAPTER_BINDING_MISMATCH));
        var otherScope = new InvoiceWebhookSignatures.Scope("install_case", "other_endpoint", "keys_v1"); var otherAuth = authenticate(bytes, otherScope, 1700000000L);
        assertTrue(inspectVerifiedWebhook(delivery.input(), delivery.expected(), host, otherScope, otherAuth, receipt).issues().contains(Issue.ADAPTER_BINDING_MISMATCH));
        byte[] changed = Arrays.copyOf(bytes, bytes.length + 1); changed[changed.length - 1] = ' ';
        assertTrue(inspectVerifiedWebhook(delivery.input(), delivery.expected(), host(bundle.identity(), delivery.input(), delivery.expected().evidenceIdentity(), changed), SCOPE, authenticate(changed), receipt).issues().contains(Issue.ADAPTER_SOURCE_MISMATCH));
        var input = delivery.input(); input.getAsJsonObject("configuration").addProperty("sdkVersion", "28.4.0");
        assertTrue(inspectVerifiedWebhook(input, delivery.expected(), host, SCOPE, auth, receipt).issues().contains(Issue.ADAPTER_VERSION_MISMATCH));
    }
    @Test void oldDeliveryKeepsMainBasilAndOriginalAcaciaWhileAbsentAdapterStillBlocks() throws Exception {
        var bundle = InvoiceRenewalOldDeliveryFixtures.load(InvoiceSandboxTest.root()); byte[] bytes = bundle.bytes("event-acacia.json"); var auth = authenticate(bytes);
        for (var scenario : bundle.scenarios()) {
            var delivery = scenario.deliveries().get("webhook"); var input = delivery.input(); var declared = input.getAsJsonObject("configuration").get("adapterApiVersion");
            String version = declared.isJsonNull() ? null : declared.getAsString(); var receipt = verify(bytes, "29.0.0", version, auth);
            var decision = inspectVerifiedWebhook(input, delivery.expected(), host(bundle.identity(), input, delivery.evidenceIdentity(), bytes), SCOPE, auth, receipt);
            archive(scenario.id(), receipt.json());
            if (delivery.blocked()) {
                assertEquals(InvoiceWebhookAdapters.Outcome.ABSENT, receipt.outcome()); assertFalse(receipt.json().get("executionAttempted").getAsBoolean());
                assertTrue(decision.issues().contains(Issue.ADAPTER_ABSENT)); assertThrows(IllegalArgumentException.class, decision::oracle);
            } else {
                assertTrue(receipt.verified()); assertTrue(decision.admitted(), decision.issues().toString()); assertEquals("29.0.0", decision.sdkVersion()); assertEquals(ACACIA, decision.effectiveApiVersion());
                // Parsing evidence does not add a dual-runtime business consumer to the frozen build matrix.
                for (var build : registration().builds()) assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(registration(), build.id(), decision));
            }
        }
    }
    @Test void actualSdkSchemaRefusalIsNotASuccessfulBusinessRefusalOrProcessFailure() throws Exception {
        var event = InvoiceRenewalFixtures.object(source(BASIL)); var malformed = new JsonObject(); malformed.addProperty("bad", "number-shape");
        event.getAsJsonObject("data").getAsJsonObject("object").add("amount_due", malformed); byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        var receipt = verify(bytes, "29.0.0", BASIL, authenticate(bytes)); var json = receipt.json(); archive("sdk-schema-refusal", json);
        assertEquals(InvoiceWebhookAdapters.Outcome.REJECTED, receipt.outcome()); assertEquals("COMPLETED", json.get("executionStatus").getAsString());
        assertFalse(json.get("adapterAccepted").getAsBoolean()); assertFalse(json.has("assertionOutcome")); assertTrue(json.get("containerRemoved").getAsBoolean());
    }
    @Test void changedDependenciesOrUnregisteredStartupFieldsNeverProduceAParserReport() throws Exception {
        byte[] bytes = source(BASIL); var prepared = InvoiceWebhookAdapters.prepare(bytes, "29.0.0", BASIL, SCOPE, authenticate(bytes));
        for (boolean dependency : List.of(true, false)) {
            var startup = InvoiceWebhookAdapters.startup(registration(), prepared, bytes);
            if (dependency) startup.getAsJsonObject("dependencies").addProperty("gson-2.10.1.jar", "0".repeat(64)); else startup.addProperty("consumerSource", "unregistered");
            var execution = RepairSandbox.runProtocol(image(), name(), startup, InvoiceEventAdapterWorker.class.getName(), new InvoiceWebhookAdapters.Protocol(prepared), Duration.ofSeconds(30), () -> false);
            assertNotEquals("COMPLETED", execution.get("executionStatus").getAsString()); assertTrue(execution.get("report").isJsonNull()); assertTrue(execution.get("containerRemoved").getAsBoolean());
        }
    }
    @Test void cancelledAndTimedOutChecksCannotMintVerifiedReceipts() throws Exception {
        byte[] bytes = source(BASIL); var auth = authenticate(bytes);
        var cancelled = InvoiceWebhookAdapters.verify(registration(), bytes, "29.0.0", BASIL, SCOPE, auth, image(), name(), Duration.ofSeconds(30), () -> true);
        var timed = InvoiceWebhookAdapters.verify(registration(), bytes, "29.0.0", BASIL, SCOPE, auth, image(), name(), Duration.ofMillis(1), () -> false);
        assertEquals("CANCELLED", cancelled.json().get("executionStatus").getAsString()); assertEquals("TIMED_OUT", timed.json().get("executionStatus").getAsString());
        for (var receipt : List.of(cancelled, timed)) { assertEquals(InvoiceWebhookAdapters.Outcome.NOT_EVALUATED, receipt.outcome()); assertTrue(receipt.json().get("containerRemoved").getAsBoolean()); assertFalse(receipt.verified()); }
        archive("cancelled-adapter", cancelled.json()); archive("timed-out-adapter", timed.json());
    }
}
