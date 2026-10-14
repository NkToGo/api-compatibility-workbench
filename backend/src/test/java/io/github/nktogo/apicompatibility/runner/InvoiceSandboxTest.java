package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

class InvoiceSandboxTest {
    static Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    static Decision admit(InvoiceRenewalFixtures.Bundle bundle, InvoiceRenewalFixtures.Delivery delivery) {
        var source = delivery.input().getAsJsonObject("source"); boolean webhook = delivery.consumerId().equals("invoice-webhook");
        return inspect(delivery.consumerId(), delivery.input(), delivery.expected(), new HostEvidence(bundle.identity(),
                delivery.expected().inputIdentity(), delivery.expected().evidenceIdentity(), bundle.bytes(source.get("file").getAsString()),
                webhook ? Verification.VERIFIED : Verification.NOT_APPLICABLE, webhook ? source.get("sha256").getAsString() : null,
                webhook ? Verification.VERIFIED : Verification.NOT_APPLICABLE, webhook ? delivery.expected().effectiveApiVersion() : null));
    }
    static JsonObject compilation(String status) {
        var value = new JsonObject(); value.addProperty("op", "compile"); value.addProperty("disposition", status); value.add("diagnostics", new JsonArray()); return value;
    }
    static InvoiceRenewalFixtures.Delivery delivery(String scenario, String role) throws Exception {
        return InvoiceRenewalFixtures.load(root()).scenarios().stream().filter(s -> s.id().equals(scenario)).findFirst().orElseThrow().deliveries().get(role);
    }
    static InvoiceSandbox.Protocol protocol() throws Exception { return new InvoiceSandbox.Protocol(delivery("target-agreement", "reconciliation").oracle()); }
    static JsonObject read() { var v = new JsonObject(); v.addProperty("op", "read"); return v; }
    static JsonObject returned(JsonObject fact) { var v = new JsonObject(); v.addProperty("op", "return"); v.add("result", fact); return v; }
    static JsonObject observation(JsonObject result) { return result.getAsJsonObject("observation"); }

    @Test void onlyRegisteredSourcesAndDependencyHashesEnterTheStartup() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root()); var bundle = InvoiceRenewalFixtures.load(root());
        for (var build : registration.builds()) {
            var delivery = delivery(build.apiVersion().endsWith("acacia") ? "legacy-agreement" : "target-agreement", build.consumerId().equals("invoice-webhook") ? "webhook" : "reconciliation");
            var value = InvoiceSandbox.startup(registration, build.id(), admit(bundle, delivery));
            assertEquals(Set.of("sdk", "entryClass", "sources", "dependencies"), value.keySet());
            assertEquals(2, value.getAsJsonObject("sources").size()); assertEquals(2, value.getAsJsonObject("dependencies").size());
            assertFalse(value.toString().contains(delivery.expected().evidenceIdentity()));
            assertFalse(value.toString().contains(delivery.expected().inputIdentity()));
            var copy = value.deepCopy(); value.getAsJsonObject("sources").addProperty("RenewalPolicy.java", "changed");
            assertEquals(copy, InvoiceSandbox.startup(registration, build.id(), admit(bundle, delivery)));
        }
    }
    @Test void blockedInputsAndWrongRoleVersionOrSdkNeverReachDispatch() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root()); var boundary = InvoiceRenewalBoundaryFixtures.load(root()); int blocked = 0;
        for (var control : boundary.controls()) if (!control.admission().admitted()) {
            assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(registration, "webhook-basil-source", control.admission())); blocked++;
        }
        assertEquals(24, blocked);
        var bundle = InvoiceRenewalFixtures.load(root()); var decision = admit(bundle, delivery("target-agreement", "webhook"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(registration, "reconciliation-basil-source", decision));
        assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(registration, "webhook-baseline", decision));
        assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(registration, "unknown", decision));
    }
    @Test void onlyHostComparisonCanProducePassingOrFailingAssertions() throws Exception {
        for (boolean correct : List.of(true, false)) {
            var p = protocol(); p.request(compilation("SUCCEEDED"));
            var exchange = p.request(read()).getAsJsonObject(); assertEquals(Set.of("formatVersion", "input", "inputIdentity", "evidenceIdentity"), exchange.keySet());
            var fact = delivery("target-agreement", "reconciliation").expected().json(); if (!correct) fact.addProperty("subscriptionId", "sub_wrong");
            p.request(returned(fact)); var result = p.result("COMPLETED");
            assertEquals(correct ? "PASSED" : "FAILED", observation(result).get("assertionOutcome").getAsString());
            assertEquals(fact, observation(result).get("actual"));
        }
    }
    @Test void compileFailureAndMissingCompilerReceiptCannotPass() throws Exception {
        var p = protocol(); p.request(compilation("FAILED"));
        assertThrows(IllegalArgumentException.class, () -> p.request(read()));
        assertEquals("NOT_EVALUATED", observation(p.result("WORKER_FAILED")).get("assertionOutcome").getAsString());
        assertEquals("NOT_EVALUATED", observation(protocol().result("COMPLETED")).get("assertionOutcome").getAsString());
    }
    @Test void missingForgedAndTrailingResponsesRemainUnevaluated() throws Exception {
        var missing = protocol(); missing.request(compilation("SUCCEEDED")); missing.request(read());
        assertEquals("NOT_EVALUATED", observation(missing.result("COMPLETED")).get("assertionOutcome").getAsString());
        for (boolean trailing : List.of(true, false)) {
            var p = protocol(); p.request(compilation("SUCCEEDED")); p.request(read());
            var fact = delivery("target-agreement", "reconciliation").expected().json();
            if (trailing) p.request(returned(fact)); else fact.addProperty("assertionOutcome", "PASSED");
            assertThrows(IllegalArgumentException.class, () -> p.request(returned(fact)));
            var result = p.result("COMPLETED"); assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString());
            assertEquals(trailing, !observation(result).get("actual").isJsonNull());
        }
    }
    @Test void interruptedExchangesPreserveActualFactsWithoutAcceptance() throws Exception {
        for (String status : List.of("TIMED_OUT", "CANCELLED", "OUTPUT_LIMIT", "PROTOCOL_ERROR", "STREAM_ERROR", "INFRASTRUCTURE_ERROR")) {
            var p = protocol(); p.request(compilation("SUCCEEDED")); p.request(read()); p.request(returned(delivery("target-agreement", "reconciliation").expected().json()));
            var result = p.result(status); assertEquals(status, result.get("transportStatus").getAsString());
            assertFalse(observation(result).get("actual").isJsonNull()); assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString());
        }
    }
    @Test void duplicateJsonAndCompilerMetadataCannotLaunderAssertions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalFixtures.object("{\"op\":\"read\",\"op\":\"return\"}".getBytes(StandardCharsets.UTF_8)));
        var forged = compilation("SUCCEEDED"); forged.addProperty("assertionOutcome", "PASSED"); var p = protocol();
        assertThrows(IllegalArgumentException.class, () -> p.request(forged));
        assertEquals("NOT_EVALUATED", observation(p.result("COMPLETED")).get("assertionOutcome").getAsString());
        var twice = protocol(); twice.request(compilation("SUCCEEDED"));
        assertThrows(IllegalArgumentException.class, () -> twice.request(compilation("SUCCEEDED")));
    }
    @Test void commandKeepsIsolationAndHarnessHasASeparateIdentity() throws Exception {
        var command = RepairSandbox.command("sha256:" + "a".repeat(64), "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName());
        assertTrue(command.containsAll(List.of("--interactive", "--network=none", "--read-only", "--pull=never", "--cap-drop=ALL", "--user=65532:65532", InvoiceWorkerMain.class.getName())));
        assertFalse(command.stream().anyMatch(s -> s.startsWith("--volume") || s.startsWith("--env")));
        assertThrows(IllegalArgumentException.class, () -> RepairSandbox.command("image:latest", "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> RepairSandbox.command("sha256:" + "a".repeat(64), "acw-00000000-0000-0000-0000-000000000000", "shell"));
        assertTrue(InvoiceSandbox.harnessIdentity().matches("[0-9a-f]{64}"));
        assertNotEquals(InvoiceConsumerBuilds.REGISTRATION_SHA256, InvoiceSandbox.harnessIdentity());
    }
    @Test void terminalResultsCannotBeChangedByLateCompilerMessagesOrCallers() throws Exception {
        var p = protocol(); var sealed = p.result("COMPLETED"); var original = sealed.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> p.request(compilation("SUCCEEDED")));
        sealed.addProperty("transportInvalid", false); sealed.remove("compilation");
        assertEquals(original, p.result("COMPLETED"));
        assertThrows(IllegalArgumentException.class, () -> p.result("TIMED_OUT"));
    }
}
