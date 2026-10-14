package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookSignatures.*;

class InvoiceWebhookSignaturesTest {
    private static final long NOW = 1700000000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String SECRET = "whsec_synthetic_signature_control";
    private static final byte[] BODY = "{\"text\":\"Grüße 🌍\"}\r\n".getBytes(StandardCharsets.UTF_8);
    // Independently calculated with Node crypto, not the production verifier or this test's signing helper.
    private static final String KNOWN_SIGNATURE = "bf189ae7486df03ab7f5e4cc57f1f68416f17901d092ad7b1247c24d34406c58";
    private static final String KNOWN_SOURCE = "06944f4f11a709ef7eb7ff8e20c81129dc1efe1bee1ddb01491c78b87b4833e5";
    private static final Scope SCOPE = new Scope("install_case", "endpoint_case", "keys_v1");
    private static SigningKey key(String ref, String secret) { return new SigningKey(ref, secret.getBytes(StandardCharsets.UTF_8)); }
    private static Receipt verify(byte[] bytes, String header) { return InvoiceWebhookSignatures.verify(SCOPE, bytes, header, List.of(key("key_current", SECRET)), CLOCK); }
    static String signed(byte[] bytes, long timestamp, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(bytes));
    }
    private static String knownHeader() { return "t=" + NOW + ",v1=" + KNOWN_SIGNATURE; }
    private static InvoiceRenewalFixtures.Bundle bundle() throws Exception { return InvoiceRenewalFixtures.load(InvoiceSandboxTest.root()); }
    private static InvoiceRenewalFixtures.Delivery delivery() throws Exception { return InvoiceSandboxTest.delivery("target-agreement", "webhook"); }
    private static HostEvidence host(InvoiceRenewalFixtures.Delivery delivery, byte[] bytes, Verification adapter) throws Exception {
        return new HostEvidence(bundle().identity(), delivery.expected().inputIdentity(), delivery.expected().evidenceIdentity(), bytes,
                Verification.VERIFIED, delivery.input().getAsJsonObject("source").get("sha256").getAsString(),
                adapter, delivery.expected().effectiveApiVersion());
    }
    private static byte[] source(InvoiceRenewalFixtures.Delivery delivery) throws Exception {
        return bundle().bytes(delivery.input().getAsJsonObject("source").get("file").getAsString());
    }

    @Test void independentUtf8AndCrLfVectorVerifiesWithoutReserializing() {
        var receipt = verify(BODY, knownHeader()); assertTrue(receipt.verified()); assertEquals(Outcome.VERIFIED, receipt.outcome());
        assertEquals(KNOWN_SOURCE, receipt.sourceIdentity()); assertEquals(KNOWN_SOURCE, receipt.json().get("sourceIdentity").getAsString());
        assertEquals("key_current", receipt.json().getAsJsonArray("matchingKeyReferences").get(0).getAsString());
    }
    @Test void changedWhitespaceOrderEncodingOrSingleByteCannotReuseASignature() {
        for (byte[] changed : List.of("{\"text\":\"Grüße 🌍\"}".getBytes(StandardCharsets.UTF_8),
                BODY.clone(), "{ \"text\":\"Grüße 🌍\"}\r\n".getBytes(StandardCharsets.UTF_8))) {
            if (Arrays.equals(changed, BODY)) changed[0] ^= 1;
            assertEquals(Outcome.SIGNATURE_REJECTED, verify(changed, knownHeader()).outcome());
        }
    }
    @Test void onlyV1AndExactlyOneCanonicalTimestampAreAccepted() {
        for (String header : List.of("v1=" + KNOWN_SIGNATURE, "t=" + NOW, "t=" + NOW + ",v0=" + KNOWN_SIGNATURE,
                knownHeader() + ",t=" + NOW, knownHeader() + ",t=" + (NOW + 1), "t=0,v1=" + KNOWN_SIGNATURE,
                "t=-1,v1=" + KNOWN_SIGNATURE, "t=01700000000,v1=" + KNOWN_SIGNATURE,
                "t=9223372036854775808,v1=" + KNOWN_SIGNATURE, "t=9223372036854775807,v1=" + KNOWN_SIGNATURE,
                knownHeader() + ",v1=broken", knownHeader() + ",", knownHeader() + "\r\n", knownHeader() + ",v0=bad\t")) {
            var receipt = verify(BODY, header); assertEquals(Outcome.HEADER_REJECTED, receipt.outcome()); assertFalse(receipt.verified());
        }
    }
    @Test void unknownSchemesDoNotAuthenticateButCannotHideAMismatchingV1() {
        assertTrue(verify(BODY, "v0=not_a_signature, " + knownHeader() + ",v2=ignored").verified());
        assertEquals(Outcome.SIGNATURE_REJECTED, verify(BODY, "t=" + NOW + ",v0=" + KNOWN_SIGNATURE + ",v1=" + "0".repeat(64)).outcome());
    }
    @Test void boundsRejectMissingOversizedSourcesHeadersAndSignatureLists() {
        assertEquals(Outcome.SOURCE_REJECTED, verify(null, knownHeader()).outcome());
        assertEquals(Outcome.SOURCE_REJECTED, verify(new byte[0], knownHeader()).outcome());
        assertEquals(Outcome.SOURCE_REJECTED, verify(new byte[MAX_SOURCE_BYTES + 1], knownHeader()).outcome());
        assertEquals(Outcome.HEADER_REJECTED, verify(BODY, null).outcome());
        assertEquals(Outcome.HEADER_REJECTED, verify(BODY, "x".repeat(MAX_HEADER_CHARS + 1)).outcome());
        assertEquals(Outcome.HEADER_REJECTED, verify(BODY, knownHeader() + (",v1=" + KNOWN_SIGNATURE).repeat(MAX_SIGNATURES)).outcome());
        assertEquals(Outcome.HEADER_REJECTED, verify(BODY, knownHeader() + ",v0=ignored".repeat(32)).outcome());
    }
    @Test void exactPositiveAgeWindowAlsoRejectsFutureDatedAndSubsecondOutsideDeliveries() throws Exception {
        for (long offset : List.of(-300L, -1L, 0L, 1L, 300L)) assertTrue(verify(BODY, signed(BODY, NOW + offset, SECRET)).verified());
        for (long offset : List.of(-301L, 301L)) assertEquals(Outcome.TIMESTAMP_REJECTED, verify(BODY, signed(BODY, NOW + offset, SECRET)).outcome());
        var fractional = Clock.fixed(CLOCK.instant().plusNanos(1), ZoneOffset.UTC);
        assertEquals(Outcome.TIMESTAMP_REJECTED, InvoiceWebhookSignatures.verify(SCOPE, BODY, signed(BODY, NOW - 300, SECRET),
                List.of(key("key_current", SECRET)), fractional).outcome());
    }
    @Test void freshRetryTimestampAuthenticatesAnOldEventButOldSignatureDoesNot() throws Exception {
        byte[] oldEvent = "{\"created\":1000}".getBytes(StandardCharsets.UTF_8);
        assertTrue(verify(oldEvent, signed(oldEvent, NOW, SECRET)).verified());
        assertEquals(Outcome.TIMESTAMP_REJECTED, verify(oldEvent, signed(oldEvent, NOW - 301, SECRET)).outcome());
    }
    @Test void rotationAcceptsAnyConfiguredActiveKeyAndAnyV1ButRejectsRemovedKeys() throws Exception {
        String previous = "whsec_synthetic_previous_control";
        String oldHeader = signed(BODY, NOW, previous); String combined = oldHeader + ",v1=" + KNOWN_SIGNATURE;
        var keys = List.of(key("key_previous", previous), key("key_current", SECRET));
        var receipt = InvoiceWebhookSignatures.verify(SCOPE, BODY, combined, keys, CLOCK);
        assertTrue(receipt.verified()); assertEquals(List.of("key_previous", "key_current"),
                receipt.json().getAsJsonArray("matchingKeyReferences").asList().stream().map(JsonElement::getAsString).toList());
        assertTrue(InvoiceWebhookSignatures.verify(SCOPE, BODY, oldHeader, keys, CLOCK).verified());
        assertEquals(Outcome.SIGNATURE_REJECTED, verify(BODY, oldHeader).outcome());
    }
    @Test void invalidKeyConfigurationAndReferencesFailWithoutEchoingValues() {
        assertThrows(IllegalArgumentException.class, () -> new Scope("bad/path", "endpoint", "keys"));
        assertThrows(IllegalArgumentException.class, () -> key("bad/ref", SECRET));
        assertThrows(IllegalArgumentException.class, () -> new SigningKey("key", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SigningKey("key", new byte[1025]));
        assertThrows(IllegalArgumentException.class, () -> InvoiceWebhookSignatures.verify(SCOPE, BODY, knownHeader(), List.of(), CLOCK));
        var duplicate = List.of(key("same", SECRET), key("same", "different_synthetic_key"));
        var failure = assertThrows(IllegalArgumentException.class, () -> InvoiceWebhookSignatures.verify(SCOPE, BODY, knownHeader(), duplicate, CLOCK));
        assertFalse(failure.getMessage().contains(SECRET));
        var tooMany = new ArrayList<SigningKey>(); for (int i = 0; i <= MAX_ACTIVE_KEYS; i++) tooMany.add(key("key_" + i, SECRET));
        assertThrows(IllegalArgumentException.class, () -> InvoiceWebhookSignatures.verify(SCOPE, BODY, knownHeader(), tooMany, CLOCK));
    }
    @Test void secretsSourcesAndDiagnosticCopiesCannotMutateAuthority() {
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8); var key = new SigningKey("key_current", secret); Arrays.fill(secret, (byte) 0);
        byte[] source = BODY.clone(); var receipt = InvoiceWebhookSignatures.verify(SCOPE, source, knownHeader(), List.of(key), CLOCK); source[0] ^= 1;
        var diagnostic = receipt.json(); diagnostic.addProperty("outcome", "FORGED"); diagnostic.getAsJsonObject("scope").addProperty("endpointId", "other");
        assertTrue(receipt.verified()); assertEquals(KNOWN_SOURCE, receipt.sourceIdentity()); assertTrue(receipt.matchesScope(SCOPE));
        assertEquals("VERIFIED", receipt.json().get("outcome").getAsString()); assertFalse(key.toString().contains(SECRET));
    }
    @Test void diagnosticsRetainPolicyImplementationTimeAndScopeWithoutTransportOrSecrets() {
        var receipt = verify(BODY, knownHeader()); var json = receipt.json();
        assertEquals(Set.of("formatVersion", "policy", "implementationIdentity", "outcome", "sourceIdentity", "checkedAt", "signatureTimestamp",
                "toleranceSeconds", "scope", "matchingKeyReferences", "receiptIdentity"), json.keySet());
        assertEquals(CLOCK.instant().toString(), json.get("checkedAt").getAsString()); assertEquals(NOW, json.get("signatureTimestamp").getAsLong());
        assertEquals(300, json.get("toleranceSeconds").getAsInt()); assertTrue(json.get("implementationIdentity").getAsString().matches("[0-9a-f]{64}"));
        String identity = json.remove("receiptIdentity").getAsString(); assertEquals(identity, CaseInputs.identity(json));
        for (String forbidden : List.of(SECRET, KNOWN_SIGNATURE, "Grüße", "Stripe-Signature")) assertFalse(receipt.json().toString().contains(forbidden));
        assertTrue(verify(null, knownHeader()).json().get("sourceIdentity").isJsonNull());
    }
    @Test void bothPinnedOfficialSdkVersionsAgreeOnTheIndependentVectorAndTamperRejection() throws Exception {
        InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()); // Validates the registered dependency hashes before loading.
        Path dependencies = InvoiceSandboxTest.root().resolve("target/dependencies");
        for (String sdk : List.of("28.4.0", "29.0.0")) {
            var urls = new java.net.URL[] {dependencies.resolve("stripe-java-" + sdk + ".jar").toUri().toURL(), dependencies.resolve("gson-2.10.1.jar").toUri().toURL()};
            try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                var method = loader.loadClass("com.stripe.net.Webhook$Signature").getMethod("verifyHeader", String.class, String.class, String.class, long.class, Clock.class);
                assertEquals(true, method.invoke(null, new String(BODY, StandardCharsets.UTF_8), knownHeader(), SECRET, 300L, CLOCK));
                var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(null, "{}", knownHeader(), SECRET, 300L, CLOCK));
                assertEquals("com.stripe.exception.SignatureVerificationException", failure.getCause().getClass().getName());
            }
        }
    }
    @Test void authoredVerifiedFlagCannotOverrideAMissingOrRejectedActualReceipt() throws Exception {
        var delivery = delivery(); byte[] bytes = source(delivery); var host = host(delivery, bytes, Verification.VERIFIED);
        for (Receipt receipt : Arrays.asList(null, verify(bytes, knownHeader()))) {
            var decision = inspectWebhook(delivery.input(), delivery.expected(), host, SCOPE, receipt);
            assertFalse(decision.admitted()); assertTrue(decision.issues().contains(receipt == null ? Issue.SIGNATURE_NOT_VERIFIED : Issue.SIGNATURE_REJECTED));
            assertThrows(IllegalArgumentException.class, decision::oracle);
            assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()), "webhook-basil-source", decision));
        }
    }
    @Test void authenticSignatureCannotBypassMissingAdapterOrApproveBusinessBehavior() throws Exception {
        var delivery = delivery(); byte[] bytes = source(delivery); var receipt = verify(bytes, signed(bytes, NOW, SECRET));
        assertTrue(receipt.verified());
        for (Verification adapter : List.of(Verification.NOT_CHECKED, Verification.ABSENT, Verification.REJECTED)) {
            var decision = inspectWebhook(delivery.input(), delivery.expected(), host(delivery, bytes, adapter), SCOPE, receipt);
            assertFalse(decision.admitted()); assertThrows(IllegalArgumentException.class, decision::oracle); assertEquals(receipt.json(), decision.signatureEvidence());
        }
        var authoredAdapter = inspectWebhook(delivery.input(), delivery.expected(), host(delivery, bytes, Verification.VERIFIED), SCOPE, receipt);
        assertTrue(authoredAdapter.admitted()); assertEquals(Set.of("admitted", "issues"), authoredAdapter.json().keySet());
        var diagnostic = authoredAdapter.signatureEvidence(); diagnostic.addProperty("outcome", "FORGED");
        assertEquals("VERIFIED", authoredAdapter.signatureEvidence().get("outcome").getAsString());
        assertEquals(Set.of("sdk", "entryClass", "sources", "dependencies"),
                InvoiceSandbox.startup(InvoiceConsumerBuilds.load(InvoiceSandboxTest.root()), "webhook-basil-source", authoredAdapter).keySet());
        var protocol = new InvoiceSandbox.Protocol(authoredAdapter.oracle()); protocol.request(InvoiceSandboxTest.compilation("SUCCEEDED"));
        protocol.request(InvoiceSandboxTest.read()); var wrong = delivery.expected().json(); wrong.addProperty("subscriptionId", "sub_wrong");
        protocol.request(InvoiceSandboxTest.returned(wrong));
        assertEquals("FAILED", protocol.result("COMPLETED").getAsJsonObject("observation").get("assertionOutcome").getAsString());
        assertNull(InvoiceSandboxTest.admit(bundle(), delivery).signatureEvidence()); // Authored history gains no receipt.
    }
    @Test void receiptCannotMoveBetweenInstallationsEndpointsKeySetsOrSourceBytes() throws Exception {
        var delivery = delivery(); byte[] bytes = source(delivery); var receipt = verify(bytes, signed(bytes, NOW, SECRET));
        for (Scope other : List.of(new Scope("install_other", "endpoint_case", "keys_v1"), new Scope("install_case", "endpoint_other", "keys_v1"),
                new Scope("install_case", "endpoint_case", "keys_v2"))) {
            var decision = inspectWebhook(delivery.input(), delivery.expected(), host(delivery, bytes, Verification.VERIFIED), other, receipt);
            assertTrue(decision.issues().contains(Issue.SIGNATURE_SCOPE_MISMATCH)); assertThrows(IllegalArgumentException.class, decision::oracle);
        }
        byte[] changed = Arrays.copyOf(bytes, bytes.length + 1); changed[changed.length - 1] = ' ';
        var changedDecision = inspectWebhook(delivery.input(), delivery.expected(), host(delivery, changed, Verification.VERIFIED), SCOPE, receipt);
        assertTrue(changedDecision.issues().contains(Issue.SIGNATURE_SOURCE_MISMATCH)); assertFalse(changedDecision.admitted());
    }
    @Test void authenticBinaryOrWrongEventStillFailsTheIndependentJsonAndVersionGate() throws Exception {
        var delivery = delivery(); byte[] invalid = {(byte) 0xff, 0x00}; var receipt = verify(invalid, signed(invalid, NOW, SECRET));
        assertTrue(receipt.verified());
        var decision = inspectWebhook(delivery.input(), delivery.expected(), host(delivery, invalid, Verification.VERIFIED), SCOPE, receipt);
        assertTrue(decision.issues().contains(Issue.INVALID_SOURCE)); assertFalse(decision.admitted());
        var wrongInput = delivery.input(); wrongInput.getAsJsonObject("source").getAsJsonObject("payload").addProperty("api_version", "unknown");
        var versionDecision = inspectWebhook(wrongInput, delivery.expected(), host(delivery, source(delivery), Verification.VERIFIED), SCOPE,
                verify(source(delivery), signed(source(delivery), NOW, SECRET)));
        assertTrue(versionDecision.issues().contains(Issue.UNKNOWN_EVENT_VERSION)); assertFalse(versionDecision.admitted());
    }
}
