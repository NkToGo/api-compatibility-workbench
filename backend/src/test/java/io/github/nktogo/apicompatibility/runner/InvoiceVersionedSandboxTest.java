package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceVersionedConsumerBuilds.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceSandboxTest.*;

class InvoiceVersionedSandboxTest {
    static JsonObject compilation(String component, String status) { var row = InvoiceSandboxTest.compilation(status); row.addProperty("component", component); return row; }
    static InvoiceVersionedProtocol protocol() throws Exception { return new InvoiceVersionedProtocol(delivery("target-agreement", "reconciliation").oracle()); }
    static void compiled(InvoiceVersionedProtocol protocol) { protocol.request(compilation("main", "SUCCEEDED")); protocol.request(compilation("acacia-adapter", "SUCCEEDED")); }
    static JsonObject startup() throws Exception {
        var registration = load(root()); var payload = new JsonObject(); payload.addProperty("buildId", BUILD_ID); var components = new JsonArray();
        for (var component : registration.components()) {
            var row = new JsonObject(); row.addProperty("id", component.id); row.addProperty("sdk", component.sdk); row.addProperty("entryClass", component.entry);
            var sources = new JsonObject(); registration.sources(component.id).forEach((name, bytes) -> sources.addProperty(name, new String(bytes, StandardCharsets.UTF_8))); row.add("sources", sources);
            var dependencies = new JsonObject(); for (String name : List.of("stripe-java-" + component.sdk + ".jar", "gson-2.10.1.jar")) dependencies.addProperty(name, CaseInputs.hash(registration.artifactBytes(name)));
            row.add("dependencies", dependencies); components.add(row);
        }
        payload.add("components", components); return payload;
    }
    @Test void authoredVerifierFlagsCannotAuthorizeTheNewBusinessRoute() throws Exception {
        var registration = load(root()); var bundle = InvoiceRenewalFixtures.load(root());
        var ordinary = admit(bundle, delivery("target-agreement", "webhook")); assertTrue(ordinary.admitted()); assertFalse(ordinary.observedWebhookVerified());
        assertThrows(IllegalArgumentException.class, () -> InvoiceVersionedSandbox.startup(registration, ordinary));
        var old = InvoiceRenewalOldDeliveryFixtures.load(root()); var d = old.scenarios().getFirst().deliveries().get("webhook"); byte[] bytes = old.bytes("event-acacia.json");
        var host = InvoiceWebhookAdaptersTest.host(old.identity(), d.input(), d.evidenceIdentity(), bytes);
        var authored = InvoiceRenewalAdmission.inspect("invoice-webhook", d.input(), d.expected(), host); assertTrue(authored.admitted());
        var signedOnly = InvoiceRenewalAdmission.inspectWebhook(d.input(), d.expected(), host, InvoiceWebhookAdaptersTest.SCOPE, InvoiceWebhookAdaptersTest.authenticate(bytes));
        assertTrue(signedOnly.admitted()); assertFalse(signedOnly.observedWebhookVerified());
        for (var decision : List.of(authored, signedOnly)) assertThrows(IllegalArgumentException.class, () -> InvoiceVersionedSandbox.startup(registration, decision));
        var copy = signedOnly.signatureEvidence(); copy.addProperty("outcome", "VERIFIED"); var input = authored.input(); input.getAsJsonObject("configuration").addProperty("sdkVersion", "28.4.0");
        assertEquals("29.0.0", authored.sdkVersion()); assertFalse(authored.observedWebhookVerified());
    }
    @Test void startupRejectsExtraComponentsSdkSubstitutionAndEventSuppliedPaths() throws Exception {
        var payload = startup(); assertDoesNotThrow(() -> InvoiceVersionedWorkerMain.validateStartup(payload));
        for (int mutation = 0; mutation < 7; mutation++) {
            var altered = payload.deepCopy(); var components = altered.getAsJsonArray("components"); var main = components.get(0).getAsJsonObject(); var companion = components.get(1).getAsJsonObject();
            switch (mutation) {
                case 0 -> altered.addProperty("expected", "forged");
                case 1 -> main.addProperty("sdk", "28.4.0");
                case 2 -> companion.addProperty("entryClass", "shell");
                case 3 -> companion.getAsJsonObject("sources").addProperty("../Injected.java", "unregistered");
                case 4 -> main.getAsJsonObject("dependencies").addProperty("stripe-java-28.4.0.jar", "a".repeat(64));
                case 5 -> components.remove(1);
                case 6 -> companion.addProperty("classesPath", "/host/private");
                default -> throw new AssertionError();
            }
            assertThrows(IllegalArgumentException.class, () -> InvoiceVersionedWorkerMain.validateStartup(altered));
        }
    }
    @Test void bothCompilerReceiptsPrecedeInputAndOnlyHostComparisonAssertsBusinessSuccess() throws Exception {
        for (boolean correct : List.of(true, false)) {
            var p = protocol(); compiled(p); var input = p.request(read()).getAsJsonObject(); assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), input.keySet());
            var fact = delivery("target-agreement", "reconciliation").expected().json(); if (!correct) fact.addProperty("subscriptionId", "sub_wrong"); p.request(returned(fact));
            var result = p.result("COMPLETED"); assertEquals(correct ? "PASSED" : "FAILED", observation(result).get("assertionOutcome").getAsString());
            assertEquals(2, result.getAsJsonArray("compilations").size()); assertEquals(fact, observation(result).get("actual"));
        }
    }
    @Test void partialOrFailedCompilationRetainsCompilerFactsWithoutAConsumerObservation() throws Exception {
        for (int missingOrFailed = 0; missingOrFailed < 4; missingOrFailed++) {
            var p = protocol();
            if (missingOrFailed > 0) p.request(compilation("main", missingOrFailed == 2 ? "FAILED" : "SUCCEEDED"));
            if (missingOrFailed > 1) p.request(compilation("acacia-adapter", missingOrFailed == 3 ? "FAILED" : "SUCCEEDED"));
            assertThrows(IllegalArgumentException.class, () -> p.request(read()));
            var result = p.result("COMPLETED"); assertEquals(Math.min(missingOrFailed, 2), result.getAsJsonArray("compilations").size());
            assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertTrue(observation(result).get("actual").isJsonNull());
        }
    }
    @Test void duplicateReorderedAndForgedCompilerMessagesCannotGrantExecution() throws Exception {
        for (int mutation = 0; mutation < 4; mutation++) {
            var p = protocol(); var message = compilation(mutation == 0 ? "acacia-adapter" : "main", "SUCCEEDED");
            if (mutation == 1) message.addProperty("assertionOutcome", "PASSED");
            if (mutation == 2) p.request(message.deepCopy());
            if (mutation == 3) { var row = new JsonObject(); row.addProperty("code", "compiler.err.control"); row.addProperty("source", "VersionedInvoiceWebhookConsumer.java"); row.addProperty("line", 1); row.addProperty("column", 1); row.addProperty("kind", "ERROR"); message.getAsJsonArray("diagnostics").add(row); }
            assertThrows(IllegalArgumentException.class, () -> p.request(message)); assertEquals("NOT_EVALUATED", observation(p.result("COMPLETED")).get("assertionOutcome").getAsString());
        }
    }
    @Test void diagnosticPathsAndPositionsAreBoundedToEachComponent() throws Exception {
        for (String source : List.of("AcaciaInvoiceAdapter.java", "/host/private/Consumer.java", "RenewalPolicy.java")) {
            var p = protocol(); p.request(compilation("main", "SUCCEEDED")); var receipt = compilation("acacia-adapter", "FAILED");
            var row = new JsonObject(); row.addProperty("code", "compiler.err.control"); row.addProperty("source", source); row.addProperty("line", source.equals("AcaciaInvoiceAdapter.java") ? 1.5 : 1); row.addProperty("column", 1); row.addProperty("kind", "ERROR"); receipt.getAsJsonArray("diagnostics").add(row);
            assertThrows(IllegalArgumentException.class, () -> p.request(receipt));
        }
    }
    @Test void missingForgedAndTrailingBusinessResultsRemainUnevaluated() throws Exception {
        for (int mutation = 0; mutation < 3; mutation++) {
            var p = protocol(); compiled(p); p.request(read()); var fact = delivery("target-agreement", "reconciliation").expected().json();
            if (mutation == 1) fact.addProperty("assertionOutcome", "PASSED");
            if (mutation == 2) p.request(returned(fact));
            if (mutation > 0) assertThrows(IllegalArgumentException.class, () -> p.request(returned(fact)));
            var result = p.result("COMPLETED"); assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertEquals(mutation == 2, !observation(result).get("actual").isJsonNull());
        }
    }
    @Test void interruptionsAndSealingPreserveFactsWithoutPromotingIncompleteExecution() throws Exception {
        for (String status : List.of("TIMED_OUT", "CANCELLED", "PROTOCOL_ERROR", "WORKER_FAILED", "OUTPUT_LIMIT")) {
            var p = protocol(); compiled(p); p.request(read()); p.request(returned(delivery("target-agreement", "reconciliation").expected().json())); var result = p.result(status);
            assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertFalse(observation(result).get("actual").isJsonNull());
            var original = result.deepCopy(); result.getAsJsonArray("compilations").remove(0); assertEquals(original, p.result(status));
            assertThrows(IllegalArgumentException.class, () -> p.request(compilation("main", "SUCCEEDED"))); assertThrows(IllegalArgumentException.class, () -> p.result("COMPLETED"));
        }
    }
    @Test void containerAndChildCommandsKeepTheHostAndTwoSdkClosuresSeparate() throws Exception {
        var command = RepairSandbox.command("sha256:" + "a".repeat(64), "acw-00000000-0000-0000-0000-000000000000", InvoiceVersionedWorkerMain.class.getName());
        assertTrue(command.containsAll(List.of("--network=none", "--read-only", "--cap-drop=ALL", "--user=65532:65532", InvoiceVersionedWorkerMain.class.getName())));
        assertFalse(command.stream().anyMatch(s -> s.startsWith("--volume") || s.startsWith("--env"))); assertEquals(150000, RepairSandbox.startupLimit(InvoiceVersionedWorkerMain.class.getName()));
        var child = InvoiceVersionedWorkerMain.consumerArguments(); String cp = child.get(child.indexOf("-cp") + 1);
        assertTrue(cp.contains("stripe-java-29.0.0.jar")); assertFalse(cp.contains("28.4.0") || cp.contains("acacia-adapter") || cp.contains("backend"));
        assertTrue(child.contains("-Dworkbench.invoice.legacy.sdk=/opt/workbench/dependencies/stripe-java-28.4.0.jar"));
        assertNotEquals(InvoiceSandbox.harnessIdentity(), InvoiceVersionedSandbox.harnessIdentity());
    }
}
