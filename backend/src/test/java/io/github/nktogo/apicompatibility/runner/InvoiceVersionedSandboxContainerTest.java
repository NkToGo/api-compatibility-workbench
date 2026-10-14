package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookAdaptersTest.authenticate;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookAdaptersTest.host;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookAdaptersTest.SCOPE;
import static io.github.nktogo.apicompatibility.runner.InvoiceWebhookAdaptersTest.SECRET;
import static io.github.nktogo.apicompatibility.runner.InvoiceVersionedConsumerBuilds.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceSandboxTest.observation;

@EnabledIfSystemProperty(named = "workbench.invoice.image", matches = "sha256:[0-9a-f]{64}")
class InvoiceVersionedSandboxContainerTest {
    private static String image() { return System.getProperty("workbench.invoice.image"); }
    private static String name() { return "acw-" + UUID.randomUUID(); }
    private static Path root() { return InvoiceSandboxTest.root(); }
    private static InvoiceRenewalOldDeliveryFixtures.Delivery delivery() throws Exception {
        return InvoiceRenewalOldDeliveryFixtures.load(root()).scenarios().stream().filter(s -> s.id().equals("legacy-adapter-declared")).findFirst().orElseThrow().deliveries().get("webhook");
    }
    private static void archive(String id, JsonObject receipt) throws Exception {
        Path directory = Path.of(System.getProperty("workbench.repository"), "backend/target/versioned-invoice-container-tests"); Files.createDirectories(directory); CaseInputs.write(directory.resolve(id + ".json"), receipt);
    }
    private static InvoiceWebhookAdapters.Receipt adapter(byte[] bytes, String version, InvoiceWebhookSignatures.Receipt signature) throws Exception {
        return InvoiceWebhookAdapters.verify(InvoiceConsumerBuilds.load(root()), bytes, "29.0.0", version, SCOPE, signature, image(), name(), Duration.ofSeconds(30), () -> false);
    }
    private static JsonObject component(JsonObject payload, int index) { return payload.getAsJsonArray("components").get(index).getAsJsonObject(); }
    /** Mutated startups are adversarial harness controls, never registered product execution. */
    private static JsonObject fault(JsonObject payload, Duration deadline, BooleanSupplier stopped) throws Exception {
        var result = RepairSandbox.runProtocol(image(), name(), payload, InvoiceVersionedWorkerMain.class.getName(), new InvoiceVersionedProtocol(delivery().oracle()), deadline, stopped);
        result.addProperty("testAuthority", "ADVERSARIAL_CONTROL_ONLY"); assertTrue(result.get("containerRemoved").getAsBoolean()); return result;
    }
    @Test void authenticatedOldDeliveryExecutesUnderBasilMainWithAcaciaCompanionAndHostAssertions() throws Exception {
        var registration = load(root()); var bundle = InvoiceRenewalOldDeliveryFixtures.load(root()); var d = delivery(); byte[] bytes = bundle.bytes("event-acacia.json");
        var signature = authenticate(bytes); var parsed = adapter(bytes, ACACIA, signature); assertTrue(parsed.verified());
        var decision = inspectVerifiedWebhook(d.input(), d.expected(), host(bundle.identity(), d.input(), d.evidenceIdentity(), bytes), SCOPE, signature, parsed);
        assertTrue(decision.observedWebhookVerified(), decision.issues().toString()); var payload = InvoiceVersionedSandbox.startup(registration, decision);
        assertEquals(InvoiceVersionedSandboxTest.startup(), payload); assertFalse(payload.toString().contains(SECRET));
        assertFalse(payload.toString().contains(d.evidenceIdentity())); assertFalse(payload.toString().contains(d.inputIdentity()));
        assertFalse(payload.has("expected") || payload.has("ledger") || payload.has("signatureVerification") || payload.has("adapterVerification"));
        // Diagnostic JSON is a copy and cannot modify the typed authority or captured sources.
        decision.adapterEvidence().addProperty("outcome", "FORGED"); payload.getAsJsonArray("components").remove(1);
        var result = InvoiceVersionedSandbox.run(registration, decision, image(), name(), Duration.ofSeconds(30), () -> false); archive("registered-old-delivery", result);
        assertEquals("COMPLETED", result.get("transportStatus").getAsString()); assertEquals(0, result.get("workerExitCode").getAsInt()); assertTrue(result.get("containerRemoved").getAsBoolean());
        assertEquals("PASSED", observation(result).get("assertionOutcome").getAsString()); assertEquals(d.expected().json(), observation(result).get("actual"));
        assertEquals(ACACIA, observation(result).getAsJsonObject("actual").get("effectiveApiVersion").getAsString()); assertEquals(signature.json(), result.get("signatureVerification")); assertEquals(parsed.json(), result.get("adapterVerification"));
        assertFalse(parsed.json().get("primaryAccepted").getAsBoolean()); assertTrue(parsed.json().get("adapterAccepted").getAsBoolean());
        assertEquals(registration.identity(), result.get("registrationIdentity").getAsString()); assertEquals(registration.baseIdentity(), result.get("baseRegistrationIdentity").getAsString());
        for (int index = 0; index < 2; index++) {
            var component = registration.components().get(index); var compiler = result.getAsJsonArray("compilations").get(index).getAsJsonObject(); var bound = result.getAsJsonArray("components").get(index).getAsJsonObject();
            assertEquals(component.id, compiler.get("component").getAsString()); assertEquals("SUCCEEDED", compiler.get("disposition").getAsString());
            assertEquals(registration.sourceIdentity(component.id), bound.get("sourceIdentity").getAsString()); assertEquals(registration.dependenciesIdentity(component.id), bound.get("dependenciesIdentity").getAsString());
            assertEquals(component.sdk, bound.get("sdkVersion").getAsString()); assertEquals(registration.configurationIdentity(component.id), bound.get("configurationIdentity").getAsString());
        }
        for (String key : List.of("sourceIdentity", "configurationIdentity", "dependenciesIdentity", "harnessIdentity", "environmentIdentity")) assertTrue(result.get(key).getAsString().matches("[0-9a-f]{64}"));
        assertEquals(image(), result.get("imageId").getAsString());
        assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(InvoiceConsumerBuilds.load(root()), "webhook-baseline", decision));
        for (String field : List.of("requestApiVersion", "endpointApiVersion")) {
            var altered = d.input(); altered.getAsJsonObject("configuration").addProperty(field, ACACIA); String inputIdentity = InvoiceRenewalOracle.inputIdentity(altered);
            var binding = new JsonObject(); binding.addProperty("bundleIdentity", bundle.identity()); binding.addProperty("scenarioId", altered.get("scenarioId").getAsString()); binding.addProperty("deliveryId", altered.get("deliveryId").getAsString()); binding.addProperty("inputIdentity", inputIdentity);
            String evidenceIdentity = InvoiceRenewalOracle.inputIdentity(binding); var fact = d.expected().json(); fact.addProperty("inputIdentity", inputIdentity); fact.addProperty("evidenceIdentity", evidenceIdentity);
            var otherRoute = inspectVerifiedWebhook(altered, InvoiceRenewalOracle.Fact.parse(fact), host(bundle.identity(), altered, evidenceIdentity, bytes), SCOPE, signature, parsed);
            assertTrue(otherRoute.observedWebhookVerified()); assertThrows(IllegalArgumentException.class, () -> InvoiceVersionedSandbox.startup(registration, otherRoute));
        }
    }
    @Test void absentAdapterAndReboundAuthenticationBlockBeforeBusinessDispatch() throws Exception {
        var bundle = InvoiceRenewalOldDeliveryFixtures.load(root()); byte[] bytes = bundle.bytes("event-acacia.json"); var signature = authenticate(bytes);
        var absent = bundle.scenarios().stream().filter(s -> s.id().equals("legacy-adapter-absent")).findFirst().orElseThrow().deliveries().get("webhook"); var missing = adapter(bytes, null, signature);
        var denied = inspectVerifiedWebhook(absent.input(), absent.expected(), host(bundle.identity(), absent.input(), absent.evidenceIdentity(), bytes), SCOPE, signature, missing);
        assertFalse(denied.observedWebhookVerified()); assertEquals(InvoiceWebhookAdapters.Outcome.ABSENT, missing.outcome()); assertFalse(missing.json().get("executionAttempted").getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> InvoiceVersionedSandbox.run(load(root()), denied, "invalid-image", "invalid-container", Duration.ofSeconds(30), () -> false)); assertThrows(IllegalArgumentException.class, denied::oracle);
        var receipt = missing.json(); receipt.add("admission", denied.json()); archive("missing-adapter-predispatch", receipt);
        var d = delivery(); var parsed = adapter(bytes, ACACIA, signature); var rebound = inspectVerifiedWebhook(d.input(), d.expected(), host(bundle.identity(), d.input(), d.evidenceIdentity(), bytes), SCOPE, authenticate(bytes, SCOPE, 1700000001L), parsed);
        assertTrue(rebound.issues().contains(Issue.ADAPTER_BINDING_MISMATCH)); assertFalse(rebound.observedWebhookVerified()); assertThrows(IllegalArgumentException.class, () -> InvoiceVersionedSandbox.startup(load(root()), rebound));
    }
    @Test void actualMainAndCompanionLoadersCannotSeeEachOtherOrHostAuthority() throws Exception {
        var payload = InvoiceVersionedSandboxTest.startup(); var main = component(payload, 0).getAsJsonObject("sources"); var companion = component(payload, 1).getAsJsonObject("sources");
        main.addProperty("VersionedInvoiceWebhookConsumer.java", main.get("VersionedInvoiceWebhookConsumer.java").getAsString().replace("var input =", """
            for (String name : java.util.List.of("io.github.nktogo.invoiceconsumer.legacy.AcaciaInvoiceAdapter", "io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle")) {
                try { Class.forName(name); throw new AssertionError(); } catch (ClassNotFoundException expected) { }
            }
            if (java.nio.file.Files.exists(java.nio.file.Path.of("/opt/workbench/case"))
                || java.nio.file.Files.exists(java.nio.file.Path.of("/opt/workbench/fixtures"))
                || java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"))
                || System.getenv().containsKey("OPENAI_API_KEY")) throw new AssertionError();
            var input ="""));
        companion.addProperty("AcaciaInvoiceAdapter.java", companion.get("AcaciaInvoiceAdapter.java").getAsString().replace("var event =", """
            var loader = AcaciaInvoiceAdapter.class.getClassLoader();
            if (loader.getParent() != ClassLoader.getPlatformClassLoader()) throw new AssertionError();
            for (String name : java.util.List.of("io.github.nktogo.invoiceconsumer.VersionedInvoiceWebhookConsumer", "io.github.nktogo.invoiceconsumer.RenewalPolicy", "io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle")) {
                try { Class.forName(name, false, loader); throw new AssertionError(); } catch (ClassNotFoundException expected) { }
            }
            var event ="""));
        var result = fault(payload, Duration.ofSeconds(30), () -> false); archive("loader-isolation-control", result); assertEquals("PASSED", observation(result).get("assertionOutcome").getAsString());
    }
    @Test void eitherComponentCompilerFailureBlocksLaunchWhileRetainingBothReceipts() throws Exception {
        for (int index = 0; index < 2; index++) {
            var payload = InvoiceVersionedSandboxTest.startup(); var sources = component(payload, index).getAsJsonObject("sources"); String name = index == 0 ? "VersionedInvoiceWebhookConsumer.java" : "AcaciaInvoiceAdapter.java"; sources.addProperty(name, "invalid Java source");
            var result = fault(payload, Duration.ofSeconds(30), () -> false); archive(index == 0 ? "main-compiler-failure" : "companion-compiler-failure", result);
            assertEquals(2, result.getAsJsonArray("compilations").size()); assertEquals("FAILED", result.getAsJsonArray("compilations").get(index).getAsJsonObject().get("disposition").getAsString());
            assertEquals("SUCCEEDED", result.getAsJsonArray("compilations").get(1 - index).getAsJsonObject().get("disposition").getAsString());
            assertEquals(4, result.get("workerExitCode").getAsInt()); assertTrue(observation(result).get("actual").isJsonNull()); assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString());
        }
    }
    @Test void substitutedImageClosureAndExtraStartupAuthorityCannotProduceCompilerOrBusinessFacts() throws Exception {
        for (boolean dependency : List.of(true, false)) {
            var payload = InvoiceVersionedSandboxTest.startup();
            if (dependency) component(payload, 1).getAsJsonObject("dependencies").addProperty("stripe-java-28.4.0.jar", "0".repeat(64)); else payload.addProperty("expected", "forged");
            var result = fault(payload, Duration.ofSeconds(30), () -> false); archive(dependency ? "image-dependency-mismatch" : "extra-startup-authority", result);
            assertEquals(0, result.getAsJsonArray("compilations").size()); assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertTrue(observation(result).get("actual").isJsonNull());
        }
    }
    @Test void actualWrongCompanionRuntimeAndForgedConsumerVerdictsCannotPass() throws Exception {
        for (boolean runtime : List.of(true, false)) {
            var payload = InvoiceVersionedSandboxTest.startup(); var sources = component(payload, 0).getAsJsonObject("sources"); String source = sources.get("VersionedInvoiceWebhookConsumer.java").getAsString();
            source = runtime ? source.replace("runtimePath(\"sdk\").toUri().toURL()", "Path.of(\"/opt/workbench/dependencies/stripe-java-29.0.0.jar\").toUri().toURL()")
                    : source.replace("var input =", "System.out.println(\"{\\\"op\\\":\\\"PASSED\\\"}\"); var input =");
            sources.addProperty("VersionedInvoiceWebhookConsumer.java", source); var result = fault(payload, Duration.ofSeconds(30), () -> false); archive(runtime ? "wrong-companion-runtime" : "forged-consumer-verdict", result);
            assertEquals(2, result.getAsJsonArray("compilations").size()); for (var item : result.getAsJsonArray("compilations")) assertEquals("SUCCEEDED", item.getAsJsonObject().get("disposition").getAsString());
            assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertTrue(observation(result).get("actual").isJsonNull());
        }
    }
    @Test void hangingAndCancelledConsumersLeaveNoAcceptedBusinessObservation() throws Exception {
        var payload = InvoiceVersionedSandboxTest.startup(); component(payload, 0).getAsJsonObject("sources").addProperty("VersionedInvoiceWebhookConsumer.java", """
            package io.github.nktogo.invoiceconsumer;
            public final class VersionedInvoiceWebhookConsumer {
                private VersionedInvoiceWebhookConsumer() {}
                public static com.google.gson.JsonObject consume(com.google.gson.JsonObject exchange) { while (true) {} }
            }
            """);
        var timed = fault(payload, Duration.ofSeconds(8), () -> false); archive("timed-out-consumer", timed); assertEquals("TIMED_OUT", timed.get("transportStatus").getAsString());
        long stop = System.nanoTime() + Duration.ofSeconds(8).toNanos(); var cancelled = fault(payload, Duration.ofSeconds(30), () -> System.nanoTime() >= stop); archive("cancelled-consumer", cancelled); assertEquals("CANCELLED", cancelled.get("transportStatus").getAsString());
        for (var result : List.of(timed, cancelled)) { assertEquals("NOT_EVALUATED", observation(result).get("assertionOutcome").getAsString()); assertTrue(observation(result).get("actual").isJsonNull()); }
    }
}
