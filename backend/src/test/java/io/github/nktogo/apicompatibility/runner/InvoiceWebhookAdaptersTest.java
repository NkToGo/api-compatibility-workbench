package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookAdapters.*;

class InvoiceWebhookAdaptersTest {
    static final String ACACIA = "2025-02-24.acacia", BASIL = "2025-03-31.basil";
    static final String SECRET = "whsec_synthetic_adapter_control";
    static final InvoiceWebhookSignatures.Scope SCOPE = new InvoiceWebhookSignatures.Scope("install_case", "endpoint_case", "keys_v1");
    static byte[] source(String version) throws Exception {
        return InvoiceRenewalFixtures.load(InvoiceSandboxTest.root()).bytes(version.equals(ACACIA) ? "event-acacia.json" : "event-basil.json");
    }
    static InvoiceWebhookSignatures.Receipt authenticate(byte[] bytes) throws Exception { return authenticate(bytes, SCOPE, 1700000000L); }
    static InvoiceWebhookSignatures.Receipt authenticate(byte[] bytes, InvoiceWebhookSignatures.Scope scope, long time) throws Exception {
        return InvoiceWebhookSignatures.verify(scope, bytes, InvoiceWebhookSignaturesTest.signed(bytes, time, SECRET),
                List.of(new InvoiceWebhookSignatures.SigningKey("key_current", SECRET.getBytes(StandardCharsets.UTF_8))), Clock.fixed(Instant.ofEpochSecond(time), ZoneOffset.UTC));
    }
    static Prepared prepared() throws Exception { byte[] bytes = source(BASIL); return prepare(bytes, "29.0.0", BASIL, SCOPE, authenticate(bytes)); }
    static JsonObject report(Prepared prepared, boolean accepted) {
        var report = new JsonObject(); report.addProperty("sourceIdentity", prepared.sourceIdentity()); report.addProperty("mainSdk", prepared.mainSdk());
        report.addProperty("mainApiVersion", api(prepared.mainSdk())); report.addProperty("adapterSdk", sdk(prepared.adapterVersion())); report.addProperty("adapterApiVersion", prepared.adapterVersion());
        report.addProperty("primaryAccepted", accepted && api(prepared.mainSdk()).equals(prepared.adapterVersion())); report.addProperty("adapterAccepted", accepted);
        report.add("invoice", accepted ? prepared.projection() : JsonNull.INSTANCE); var message = new JsonObject(); message.addProperty("op", "adapter"); message.add("report", report); return message;
    }
    static HostEvidence host(String bundle, JsonObject input, String evidence, byte[] bytes) {
        var configuration = input.getAsJsonObject("configuration"); var version = configuration.get("adapterApiVersion");
        return new HostEvidence(bundle, InvoiceRenewalOracle.inputIdentity(input), evidence, bytes, Verification.VERIFIED, CaseInputs.hash(bytes), Verification.VERIFIED,
                version == null || version.isJsonNull() ? null : version.getAsString());
    }

    @Test void exactRoutesAreExplicitAndNeverSelectAForwardOrUnknownVersion() {
        assertTrue(supported("28.4.0", ACACIA)); assertTrue(supported("29.0.0", BASIL)); assertTrue(supported("29.0.0", ACACIA));
        for (String main : Arrays.asList(null, "latest", "30.0.0", "")) assertFalse(supported(main, BASIL));
        for (String version : Arrays.asList(null, "2025-04-30.basil", "latest", "")) assertFalse(supported("29.0.0", version));
        assertFalse(supported("28.4.0", BASIL)); assertNull(api("latest")); assertNull(sdk("latest"));
    }
    @Test void missingRejectedOrUnboundAuthenticationStopsBeforeAnyExecution() throws Exception {
        byte[] bytes = source(BASIL); var auth = authenticate(bytes); byte[] changed = Arrays.copyOf(bytes, bytes.length + 1); changed[changed.length - 1] = ' ';
        assertEquals(Outcome.AUTHENTICATION_REQUIRED, prepare(bytes, "29.0.0", BASIL, SCOPE, null).rejection());
        assertEquals(Outcome.AUTHENTICATION_REQUIRED, prepare(changed, "29.0.0", BASIL, SCOPE, auth).rejection());
        assertEquals(Outcome.AUTHENTICATION_REQUIRED, prepare(bytes, "29.0.0", BASIL, new InvoiceWebhookSignatures.Scope("install_case", "other", "keys_v1"), auth).rejection());
        var rejected = InvoiceWebhookSignatures.verify(SCOPE, bytes, "t=1700000000,v1=" + "0".repeat(64), List.of(new InvoiceWebhookSignatures.SigningKey("key", SECRET.getBytes(StandardCharsets.UTF_8))), Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC));
        var receipt = verify(null, bytes, "29.0.0", BASIL, SCOPE, rejected, "unused", "unused", Duration.ZERO, () -> { throw new AssertionError("Execution permission requested"); });
        assertEquals(Outcome.AUTHENTICATION_REQUIRED, receipt.outcome()); assertFalse(receipt.json().get("executionAttempted").getAsBoolean());
    }
    @Test void absentAdapterCannotBeInferredFromMainSdkOrEventMetadata() throws Exception {
        byte[] bytes = source(ACACIA); var auth = authenticate(bytes);
        assertEquals(Outcome.ABSENT, prepare(bytes, "29.0.0", null, SCOPE, auth).rejection());
        assertEquals(Outcome.REJECTED, prepare(bytes, "29.0.0", BASIL, SCOPE, auth).rejection());
        assertEquals(Outcome.REJECTED, prepare(bytes, "latest", ACACIA, SCOPE, auth).rejection());
        assertEquals(Outcome.REJECTED, prepare(source(BASIL), "28.4.0", BASIL, SCOPE, authenticate(source(BASIL))).rejection());
        var unknown = verify(null, bytes, "untrusted_sdk_value", ACACIA, SCOPE, auth, "unused", "unused", Duration.ZERO, () -> { throw new AssertionError(); });
        assertFalse(unknown.json().toString().contains("untrusted_sdk_value")); assertTrue(unknown.json().get("mainSdk").isJsonNull());
        assertTrue(unknown.json().get("configurationIdentity").getAsString().matches("[0-9a-f]{64}"));
    }
    @Test void authenticatedMalformedJsonAndUnsupportedEventTypesRemainBlocked() throws Exception {
        for (byte[] bytes : List.of("{\"duplicate\":1,\"duplicate\":2}".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xff}, "{}".getBytes(StandardCharsets.UTF_8),
                ("{\"api_version\":\"" + BASIL + "\",\"type\":\"invoice.paid\",\"data\":[]}").getBytes(StandardCharsets.UTF_8)))
            assertEquals(Outcome.REJECTED, prepare(bytes, "29.0.0", BASIL, SCOPE, authenticate(bytes)).rejection());
        var changed = InvoiceRenewalFixtures.object(source(BASIL)); changed.addProperty("type", "customer.created"); byte[] bytes = changed.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(Outcome.REJECTED, prepare(bytes, "29.0.0", BASIL, SCOPE, authenticate(bytes)).rejection());
        assertEquals(Outcome.AUTHENTICATION_REQUIRED, prepare(new byte[InvoiceWebhookSignatures.MAX_SOURCE_BYTES + 1], "29.0.0", BASIL, SCOPE, authenticate(source(BASIL))).rejection());
    }
    @Test void startupContainsOnlyRawSourceAndPinnedRuntimeSelectionWithItsOwnBound() throws Exception {
        var event = InvoiceRenewalFixtures.object(source(BASIL)); event.addProperty("padding", "a".repeat(250000)); byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        var prepared = prepare(bytes, "29.0.0", BASIL, SCOPE, authenticate(bytes)); assertTrue(prepared.ready());
        var startup = startup(InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()), prepared, bytes);
        assertEquals(Set.of("mainSdk", "adapterApiVersion", "source", "dependencies"), startup.keySet()); assertEquals(DEPENDENCIES, startup.getAsJsonObject("dependencies").keySet());
        assertArrayEquals(bytes, Base64.getDecoder().decode(startup.get("source").getAsString()));
        int length = new Gson().toJson(startup).getBytes(StandardCharsets.UTF_8).length; assertTrue(length > 150000 && length < 400000);
        assertFalse(startup.toString().contains(SECRET)); assertFalse(startup.has("expected"));
        assertThrows(IllegalArgumentException.class, () -> startup(InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()), prepared, source(ACACIA)));
    }
    @Test void authoredParserControlsRetainSuccessAndRefusalWithoutMintingReceipts() throws Exception {
        for (boolean accepted : List.of(true, false)) {
            var prepared = prepared(); var protocol = new Protocol(prepared); var message = report(prepared, accepted);
            assertEquals(new JsonObject(), protocol.request(message)); var result = protocol.result("COMPLETED");
            assertEquals(message.get("report"), result.get("report")); assertFalse(result.get("protocolInvalid").getAsBoolean());
            assertFalse(result.has("outcome")); assertFalse(result.has("signatureReceiptIdentity"));
        }
    }
    @Test void forgedBindingsProjectionsAndVerdictsPoisonTheProtocolAndRetainTheReport() throws Exception {
        var prepared = prepared(); var mutations = new ArrayList<JsonObject>();
        for (String field : List.of("sourceIdentity", "mainSdk", "mainApiVersion", "adapterSdk", "adapterApiVersion")) { var message = report(prepared, true); message.getAsJsonObject("report").addProperty(field, "forged"); mutations.add(message); }
        var projection = report(prepared, true); projection.getAsJsonObject("report").getAsJsonObject("invoice").addProperty("subscriptionId", "sub_wrong"); mutations.add(projection);
        var verdict = report(prepared, true); verdict.getAsJsonObject("report").addProperty("assertionOutcome", "PASSED"); mutations.add(verdict);
        var booleanType = report(prepared, true); booleanType.getAsJsonObject("report").addProperty("adapterAccepted", "true"); mutations.add(booleanType);
        for (var message : mutations) {
            var protocol = new Protocol(prepared); assertThrows(IllegalArgumentException.class, () -> protocol.request(message));
            var result = protocol.result("COMPLETED"); assertTrue(result.get("protocolInvalid").getAsBoolean()); assertEquals(message.get("report"), result.get("report"));
        }
    }
    @Test void duplicatesInterruptionAndPostSealMutationCannotCreateAcceptance() throws Exception {
        var prepared = prepared(); var protocol = new Protocol(prepared); var message = report(prepared, true); protocol.request(message);
        assertThrows(IllegalArgumentException.class, () -> protocol.request(message)); var poisoned = protocol.result("PROTOCOL_ERROR");
        assertTrue(poisoned.get("protocolInvalid").getAsBoolean()); assertEquals(message.get("report"), poisoned.get("report"));
        var interrupted = new Protocol(prepared); interrupted.request(message); var sealed = interrupted.result("TIMED_OUT");
        assertThrows(IllegalArgumentException.class, () -> interrupted.request(message)); sealed.addProperty("transportStatus", "COMPLETED");
        assertEquals("TIMED_OUT", interrupted.result("COMPLETED").get("transportStatus").getAsString());
        assertTrue(new Protocol(prepared).result("COMPLETED").get("report").isJsonNull());
    }
    @Test void largerStartupAndStrictEntrySelectionDoNotRelaxOtherWorkers() {
        assertEquals(400000, RepairSandbox.startupLimit(InvoiceEventAdapterWorker.class.getName()));
        for (String entry : List.of(InvoiceWorkerMain.class.getName(), RepairWorkerMain.class.getName())) assertEquals(150000, RepairSandbox.startupLimit(entry));
        assertThrows(IllegalArgumentException.class, () -> RepairSandbox.startupLimit("arbitrary.Main"));
        assertTrue(RepairSandbox.command("sha256:" + "0".repeat(64), "acw-00000000-0000-0000-0000-000000000000", InvoiceEventAdapterWorker.class.getName()).contains(InvoiceEventAdapterWorker.class.getName()));
    }
    @Test void authoredAdapterFlagCannotOverrideAbsentActualVerification() throws Exception {
        var delivery = InvoiceSandboxTest.delivery("target-agreement", "webhook"); byte[] bytes = source(BASIL); var auth = authenticate(bytes);
        var host = host(InvoiceRenewalFixtures.load(InvoiceSandboxTest.root()).identity(), delivery.input(), delivery.expected().evidenceIdentity(), bytes);
        var missing = inspectVerifiedWebhook(delivery.input(), delivery.expected(), host, SCOPE, auth, null);
        assertFalse(missing.admitted()); assertTrue(missing.issues().contains(Issue.ADAPTER_NOT_VERIFIED)); assertThrows(IllegalArgumentException.class, missing::oracle);
        var absent = verify(null, bytes, "29.0.0", null, SCOPE, auth, "unused", "unused", Duration.ZERO, () -> { throw new AssertionError(); });
        assertEquals(Outcome.ABSENT, absent.outcome());
        assertTrue(inspectVerifiedWebhook(delivery.input(), delivery.expected(), host, SCOPE, auth, absent).issues().contains(Issue.ADAPTER_ABSENT));
        assertNull(InvoiceSandboxTest.admit(InvoiceRenewalFixtures.load(InvoiceSandboxTest.root()), delivery).adapterEvidence());
    }
}
