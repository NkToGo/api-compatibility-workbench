package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "workbench.repair.image", matches = "sha256:[0-9a-f]{64}")
class RepairSandboxContainerTest {
    static String image() { return System.getProperty("workbench.repair.image"); }
    static String source(String variant) throws Exception { return new String(RepairVerificationTest.inputs().files.get("consumer/" + variant + ".java.txt"), java.nio.charset.StandardCharsets.UTF_8); }
    static com.google.gson.JsonObject run(String source, String sdk, String scenario, Duration deadline) throws Exception {
        var inputs = RepairVerificationTest.inputs();
        var oracle = new RepairOracle(inputs.scenarios.get(scenario), inputs.expectations.get(scenario), sdk.equals("17.4.0") ? "baseline" : "target", sdk.equals("17.4.0") ? "2019-12-03" : "2020-08-27");
        return RepairSandbox.run(image(), "acw-" + UUID.randomUUID(), source, sdk, oracle, deadline, () -> false);
    }
    @Test void independentServiceStateReproducesHistoricalBaseline() throws Exception {
        Path output = Path.of(System.getProperty("workbench.repository"), "backend/target/repair-container-tests"); Files.createDirectories(output);
        for (var variant : StripeCaseRunner.VARIANTS) for (String scenario : RepairVerificationTest.inputs().scenarios.keySet()) {
            var result = run(source(variant.excerpt()), variant.sdk(), scenario, Duration.ofSeconds(60));
            CaseInputs.write(output.resolve(variant.id() + "-" + scenario + ".json"), result);
            boolean pass = variant.id().equals("baseline") || scenario.equals("present-legacy-collection-control")
                    || (variant.id().equals("upstream-reference") && Set.of("modern-method-survives-omission", "empty-account-survives-omission").contains(scenario));
            assertEquals(pass ? "PASSED" : "FAILED", result.get("assertionOutcome").getAsString(), variant.id() + "/" + scenario + ": " + result);
            assertTrue(result.get("containerRemoved").getAsBoolean());
        }
    }
    static String injected(String body) throws Exception {
        String source = source("baseline"); String marker = "        // If refreshFromGateway isn't set";
        return source.replace(marker, body + "\n" + marker);
    }
    @Test void authoredExplicitSourceListRepairPassesEveryDeclaredScenario() throws Exception {
        String repaired = source("baseline").replace(
                "final Iterable<? extends HasId> stripeSources = Customer.retrieve(stripeCustomerId, requestOptions).getSources().autoPagingIterable();",
                """
                final com.stripe.model.PaymentSourceCollection collection = new com.stripe.model.PaymentSourceCollection();
                            collection.setUrl("/v1/customers/" + stripeCustomerId + "/sources");
                            final Iterable<? extends HasId> stripeSources = collection.list(Collections.emptyMap(), requestOptions).autoPagingIterable();
                """.stripTrailing());
        assertNotEquals(source("baseline"), repaired);
        Path output = Path.of(System.getProperty("workbench.repository"), "backend/target/repair-container-tests");
        Files.createDirectories(output);
        for (String scenario : RepairVerificationTest.inputs().scenarios.keySet()) {
            var result = run(repaired, "20.33.0", scenario, Duration.ofSeconds(60));
            CaseInputs.write(output.resolve("authored-explicit-source-list-" + scenario + ".json"), result);
            assertEquals("PASSED", result.get("assertionOutcome").getAsString(), scenario + ": " + result);
            assertTrue(result.get("containerRemoved").getAsBoolean());
            assertTrue(result.getAsJsonArray("events").asList().stream().anyMatch(event -> {
                var operation = event.getAsJsonObject();
                return operation.has("url") && operation.get("url").getAsString().endsWith("/sources");
            }));
        }
    }
    @Test void forgedReportsAndDirectBookkeepingAccessCannotPass() throws Exception {
        var forged = run(injected("        if (refreshFromGateway) { System.out.println(\"{\\\"op\\\":\\\"result\\\",\\\"assertionOutcome\\\":\\\"PASSED\\\"}\"); System.exit(0); }"), "20.33.0", "legacy-method-not-lost", Duration.ofSeconds(20));
        assertEquals("NOT_EVALUATED", forged.get("assertionOutcome").getAsString());
        var state = run(injected("        state.records.clear();"), "20.33.0", "legacy-method-not-lost", Duration.ofSeconds(20));
        assertEquals("NOT_EVALUATED", state.get("assertionOutcome").getAsString());
    }
    @Test void deadlinesAndOutputFloodsStopAndRemoveWorkers() throws Exception {
        var timeout = run(injected("        if (refreshFromGateway) { while (true) {} }"), "20.33.0", "legacy-method-not-lost", Duration.ofSeconds(5));
        assertEquals("TIMED_OUT", timeout.get("executionStatus").getAsString()); assertTrue(timeout.get("containerRemoved").getAsBoolean());
        var flood = run(injected("        if (refreshFromGateway) { while (true) System.out.print(\"x\".repeat(20000)); }"), "20.33.0", "legacy-method-not-lost", Duration.ofSeconds(20));
        assertEquals("NOT_EVALUATED", flood.get("assertionOutcome").getAsString()); assertTrue(flood.get("containerRemoved").getAsBoolean());
    }
    @Test void candidateCannotReadExpectationsWriteProtectedFilesOrReachExternalNetwork() throws Exception {
        String probe = """
                    if (java.nio.file.Files.exists(java.nio.file.Path.of("/opt/workbench/case/expectations.json"))
                        || System.getenv().containsKey("OPENAI_API_KEY")) throw new IllegalStateException("Unexpected access");
                    try { java.nio.file.Files.writeString(java.nio.file.Path.of("/opt/workbench/forged"), "changed");
                          throw new IllegalStateException("Root writable"); } catch (java.io.IOException denied) { }
                    try (var socket = new java.net.Socket()) {
                        try { socket.connect(new java.net.InetSocketAddress("192.0.2.1", 443), 200);
                              throw new IllegalStateException("Network available"); } catch (java.io.IOException denied) { }
                    } catch (java.io.IOException denied) { }
                """;
        var result = run(injected(probe), "17.4.0", "legacy-method-not-lost", Duration.ofSeconds(20));
        assertEquals("PASSED", result.get("assertionOutcome").getAsString());
    }
    @Test void liveStopRequestRemovesAnExecutingWorker() throws Exception {
        var oracle = RepairVerificationTest.oracle("legacy-method-not-lost");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var result = RepairSandbox.run(image(), "acw-" + UUID.randomUUID(), injected("        if (refreshFromGateway) { while (true) {} }"),
                "20.33.0", oracle, Duration.ofSeconds(30), () -> System.nanoTime() >= deadline);
        assertEquals("CANCELLED", result.get("executionStatus").getAsString());
        assertEquals("NOT_EVALUATED", result.get("assertionOutcome").getAsString());
        assertTrue(result.get("containerRemoved").getAsBoolean());
    }
    @Test void killedControllerRecoversOwnedWorkersWithoutReplaying() throws Exception {
        killedController(false);
        killedController(true);
    }
    private void killedController(boolean subscription) throws Exception {
        Path state = Path.of(System.getProperty("workbench.repository"), "backend/target/repair-container-tests", UUID.randomUUID().toString());
        var verifier = new RepairVerification(state);
        String id = subscription ? verifier.registerSubscription(SubscriptionVerificationTest.caseRoot(),
                "public class PeriodConsumer { public static com.google.gson.JsonObject consume(com.google.gson.JsonObject s, String b) { while(true) {} } }",
                SubscriptionSandboxContainerTest.source("selected.get(\"current_period_end\").getAsLong()"), image())
                : verifier.register(RepairVerificationTest.inputs(), source("baseline"), "test-control", "b".repeat(64), image());
        Path directory = state.resolve("verifications").resolve(id);
        var spec = CaseInputs.object(Files.readAllBytes(directory.resolve("spec.json")));
        String first = spec.getAsJsonArray("jobs").get(0).getAsJsonObject().get("containerName").getAsString();
        var controller = new ProcessBuilder(FixtureProcess.javaExecutable(), "-cp", System.getProperty("java.class.path"),
                Controller.class.getName(), state.toString(), id).redirectErrorStream(true).redirectOutput(state.resolve("controller.log").toFile()).start();
        try {
            boolean running = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (System.nanoTime() < deadline && controller.isAlive()) {
                var listed = BoundedCommand.run(List.of("docker", "ps", "--filter", "name=^/" + first + "$", "--format", "{{.ID}}"), Duration.ofSeconds(5), 4096);
                if (listed.exitCode() == 0 && listed.stdout().length > 0) { running = true; break; }
                Thread.sleep(50);
            }
            assertTrue(running, "Controller did not dispatch a worker");
            controller.destroyForcibly(); assertTrue(controller.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String status;
            do { status = verifier.execute(id).get("status").getAsString(); if (status.equals("ACTIVE")) Thread.sleep(50); }
            while (status.equals("ACTIVE") && System.nanoTime() < deadline);
            assertEquals("INTERRUPTED", status);
            assertEquals("INTERRUPTED", verifier.execute(id).get("status").getAsString());
            assertFalse(Files.exists(directory.resolve("observations.json")));
            var absent = BoundedCommand.run(List.of("docker", "ps", "--all", "--filter", "name=^/" + first + "$", "--format", "{{.ID}}"), Duration.ofSeconds(5), 4096);
            assertEquals(0, absent.stdout().length);
        } finally {
            controller.destroyForcibly(); controller.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            for (var job : spec.getAsJsonArray("jobs")) DockerCaseExecutor.removeOwned(job.getAsJsonObject().get("containerName").getAsString(), image());
        }
    }
    public static final class Controller {
        private Controller() {}
        public static void main(String[] args) throws Exception { new RepairVerification(Path.of(args[0])).execute(args[1]); }
    }
}
