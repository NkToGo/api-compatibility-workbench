package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceSandboxTest.*;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceSandboxContainerTest {
    private static String image() { return System.getProperty("workbench.invoice.image"); }
    private static String name() { return "acw-" + UUID.randomUUID(); }
    private static void archive(String id, JsonObject result) throws Exception {
        Path directory = Path.of(System.getProperty("workbench.repository"), "backend/target/invoice-container-tests");
        Files.createDirectories(directory); CaseInputs.write(directory.resolve(id + ".json"), result);
    }
    private static JsonObject payload() throws Exception {
        var bundle = InvoiceRenewalFixtures.load(root());
        return InvoiceSandbox.startup(InvoiceConsumerBuilds.load(root()), "reconciliation-basil-source", admit(bundle, delivery("target-agreement", "reconciliation")));
    }
    private static JsonObject fault(JsonObject input, Duration duration, BooleanSupplier stop) throws Exception {
        var result = RepairSandbox.runProtocol(image(), name(), input, InvoiceWorkerMain.class.getName(), protocol(), duration, stop);
        assertTrue(result.get("containerRemoved").getAsBoolean()); return result;
    }
    @Test void bothRegisteredRolesExecuteAllFourRolloutVectorsWithHostAssertions() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root()); var bundle = InvoiceRenewalFixtures.load(root()); int completed = 0;
        for (var scenario : bundle.scenarios()) for (var delivery : scenario.deliveries().values()) {
            String role = delivery.consumerId().equals("invoice-webhook") ? "webhook" : "reconciliation";
            String build = role + (delivery.expected().effectiveApiVersion().endsWith("acacia") ? "-baseline" : "-basil-source");
            var result = InvoiceSandbox.run(registration, build, admit(bundle, delivery), image(), name(), Duration.ofSeconds(30), () -> false);
            archive(scenario.id() + "-" + role, result);
            assertEquals("COMPLETED", result.get("transportStatus").getAsString(), scenario.id() + "/" + role);
            assertEquals("SUCCEEDED", result.getAsJsonObject("compilation").get("disposition").getAsString());
            assertEquals("PASSED", observation(result).get("assertionOutcome").getAsString(), scenario.id() + "/" + role);
            assertEquals(delivery.expected().json(), observation(result).get("actual"));
            assertEquals(registration.sourceIdentity(build), result.get("sourceIdentity").getAsString());
            assertEquals(registration.configurationIdentity(build), result.get("configurationIdentity").getAsString());
            assertEquals(registration.dependenciesIdentity(), result.get("dependenciesIdentity").getAsString());
            assertEquals(image(), result.get("imageId").getAsString()); assertTrue(result.get("containerRemoved").getAsBoolean());
            assertTrue(result.get("harnessIdentity").getAsString().matches("[0-9a-f]{64}"));
            assertTrue(result.get("environmentIdentity").getAsString().matches("[0-9a-f]{64}")); completed++;
        }
        assertEquals(8, completed);
    }
    @Test void bothUnrepairedUpgradesRetainCompilerFailureWithoutAnInventedConsumerResult() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root()); var bundle = InvoiceRenewalFixtures.load(root());
        for (String role : List.of("webhook", "reconciliation")) {
            var delivery = delivery("target-agreement", role);
            var result = InvoiceSandbox.run(registration, role + "-unrepaired-target", admit(bundle, delivery), image(), name(), Duration.ofSeconds(30), () -> false);
            archive("unrepaired-target-" + role, result);
            assertEquals("FAILED", result.getAsJsonObject("compilation").get("disposition").getAsString());
            assertEquals(4, result.get("workerExitCode").getAsInt()); assertEquals("WORKER_FAILED", result.get("transportStatus").getAsString());
            assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertTrue(observation(result).get("actual").isJsonNull());
            assertTrue(result.getAsJsonObject("compilation").getAsJsonArray("diagnostics").asList().stream()
                    .anyMatch(v -> v.getAsJsonObject().get("code").getAsString().contains("cant.resolve")));
            assertTrue(result.get("containerRemoved").getAsBoolean());
        }
    }
    @Test void actualSignatureReceiptStaysOnHostWhileTheConsumerExecutes() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root()); var bundle = InvoiceRenewalFixtures.load(root());
        var delivery = delivery("target-agreement", "webhook");
        byte[] bytes = bundle.bytes(delivery.input().getAsJsonObject("source").get("file").getAsString());
        String secret = "whsec_synthetic_container_control"; long timestamp = 1700000000L;
        String header = InvoiceWebhookSignaturesTest.signed(bytes, timestamp, secret);
        var scope = new InvoiceWebhookSignatures.Scope("install_case", "endpoint_case", "keys_v1");
        var receipt = InvoiceWebhookSignatures.verify(scope, bytes, header,
                List.of(new InvoiceWebhookSignatures.SigningKey("key_current", secret.getBytes(StandardCharsets.UTF_8))),
                Clock.fixed(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC));
        // The adapter condition remains authored; this test establishes only actual HMAC and consumer execution.
        var host = new InvoiceRenewalAdmission.HostEvidence(bundle.identity(), delivery.expected().inputIdentity(), delivery.expected().evidenceIdentity(),
                bytes, InvoiceRenewalAdmission.Verification.NOT_CHECKED, null, InvoiceRenewalAdmission.Verification.VERIFIED, delivery.expected().effectiveApiVersion());
        var admission = InvoiceRenewalAdmission.inspectWebhook(delivery.input(), delivery.expected(), host, scope, receipt);
        assertTrue(admission.admitted());
        var startup = InvoiceSandbox.startup(registration, "webhook-basil-source", admission);
        assertEquals(Set.of("sdk", "entryClass", "sources", "dependencies"), startup.keySet());
        var result = InvoiceSandbox.run(registration, "webhook-basil-source", admission, image(), name(), Duration.ofSeconds(30), () -> false);
        assertEquals("PASSED", observation(result).get("assertionOutcome").getAsString());
        assertEquals(receipt.json(), result.getAsJsonObject("signatureVerification")); assertTrue(result.get("containerRemoved").getAsBoolean());
        assertFalse(result.toString().contains(secret)); assertFalse(result.toString().contains(header)); archive("authenticated-webhook", result);
    }
    @Test void imageDependencyMismatchAndUnregisteredSourceCannotCompileOrReturn() throws Exception {
        var mismatched = payload(); mismatched.getAsJsonObject("dependencies").addProperty("gson-2.10.1.jar", "0".repeat(64));
        var unregistered = payload(); unregistered.getAsJsonObject("sources").addProperty("Other.java", "class Other {}");
        for (var startup : List.of(mismatched, unregistered)) {
            var result = fault(startup, Duration.ofSeconds(30), () -> false);
            assertTrue(result.get("compilation").isJsonNull()); assertTrue(observation(result).get("actual").isJsonNull());
            assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString());
        }
    }
    @Test void consumerHasNoFixtureCatalogExpectationsCredentialOrHostMount() throws Exception {
        var startup = payload(); var sources = startup.getAsJsonObject("sources"); String entry = "InvoiceReconciliationConsumer.java";
        sources.addProperty(entry, sources.get(entry).getAsString().replace("var input =", """
            if (java.nio.file.Files.exists(java.nio.file.Path.of("/opt/workbench/case"))
                || java.nio.file.Files.exists(java.nio.file.Path.of("/opt/workbench/fixtures"))
                || java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"))
                || System.getenv().containsKey("OPENAI_API_KEY")) throw new AssertionError();
            var input ="""));
        var result = fault(startup, Duration.ofSeconds(30), () -> false);
        assertEquals("PASSED", observation(result).get("assertionOutcome").getAsString());
    }
    @Test void forgedProtocolOutputAndHangingConsumersCannotPass() throws Exception {
        var forged = payload(); var sources = forged.getAsJsonObject("sources"); String entry = "InvoiceReconciliationConsumer.java";
        sources.addProperty(entry, sources.get(entry).getAsString().replace("var input =", "System.out.println(\"{\\\"op\\\":\\\"PASSED\\\"}\"); var input ="));
        var poisoned = fault(forged, Duration.ofSeconds(30), () -> false); archive("forged-protocol", poisoned);
        assertEquals("NOT_EVALUATED", observation(poisoned).get("assertionOutcome").getAsString());
        assertTrue(poisoned.get("transportInvalid").getAsBoolean());
        var hanging = payload(); hanging.getAsJsonObject("sources").addProperty(entry, """
            package io.github.nktogo.invoiceconsumer;
            public final class InvoiceReconciliationConsumer {
              private InvoiceReconciliationConsumer() {}
              public static com.google.gson.JsonObject consume(com.google.gson.JsonObject exchange) { while (true) {} }
            }
            """);
        var timed = fault(hanging, Duration.ofSeconds(8), () -> false); archive("timed-out-consumer", timed);
        assertEquals("TIMED_OUT", timed.get("transportStatus").getAsString()); assertEquals("NOT_EVALUATED", observation(timed).get("assertionOutcome").getAsString());
        long stop = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        var cancelled = fault(hanging, Duration.ofSeconds(30), () -> System.nanoTime() >= stop); archive("cancelled-consumer", cancelled);
        assertEquals("CANCELLED", cancelled.get("transportStatus").getAsString()); assertEquals("NOT_EVALUATED", observation(cancelled).get("assertionOutcome").getAsString());
    }
}
