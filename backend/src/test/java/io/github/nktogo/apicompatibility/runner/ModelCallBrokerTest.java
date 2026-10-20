package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ModelCallBrokerTest {
    @TempDir Path root;
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger credentialReads = new AtomicInteger();
    private static final String KEY = "fixture-secret-never-a-real-key";
    private static final String RESPONSE = """
            {"status":"completed","output":[{"type":"reasoning","summary":[{"text":"do-not-retain-reasoning"}]},
            {"type":"message","content":[{"type":"output_text","text":"ready"}]}],"usage":{"input_tokens":10,"output_tokens":2}}
            """;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private ModelCallBroker broker() throws Exception { return broker(Clock.systemUTC(), Duration.ofSeconds(5)); }
    private ModelCallBroker broker(Clock clock, Duration timeout) throws Exception {
        return new ModelCallBroker(root, () -> { credentialReads.incrementAndGet(); return KEY; },
                new ModelHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), timeout), clock);
    }
    private String register(ModelCallBroker broker) throws Exception {
        return broker.register(new ModelCallBroker.Request("gpt-test", "approved input", 128), Duration.ofMinutes(1));
    }
    private void reply(int code, String text) {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("x-request-id", "req_fixture");
            exchange.getResponseHeaders().add("retry-after", "12");
            exchange.getResponseHeaders().add("location", "http://127.0.0.1:" + server.getAddress().getPort() + "/stolen");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(code, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    @Test void invoiceTransportBindsStrictFunctionAndRetainsNoPrivateReasoning() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(InvoiceInvestigationDecision.FUNCTION, request.getAsJsonObject("tool_choice").get("name").getAsString());
            assertFalse(request.get("parallel_tool_calls").getAsBoolean()); assertFalse(request.get("store").getAsBoolean());
            var tool = request.getAsJsonArray("tools").get(0).getAsJsonObject(); assertTrue(tool.get("strict").getAsBoolean());
            assertEquals(InvoiceInvestigationDecision.schema(), tool.get("parameters"));
            String response = CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                    java.util.Map.of("type", "reasoning", "summary", List.of(java.util.Map.of("text", "do-not-retain-reasoning"))),
                    java.util.Map.of("type", "function_call", "name", InvoiceInvestigationDecision.FUNCTION, "arguments", "{\"decision\":{\"action\":\"catalog\",\"offset\":0,\"limit\":40}}"))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "captured invoice", 2048, InvoiceInvestigationDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); var outcome = broker.outcome(id);
        assertEquals("catalog", InvoiceInvestigationDecision.parse(outcome.get("outputText").getAsString()).get("action").getAsString());
        assertFalse(outcome.toString().contains("do-not-retain-reasoning")); assertEquals(1, calls.get());
    }
    @Test void invoiceTransportRejectsAHistoricalDecisionFunction() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes(); String response = CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "output", List.of(
                    java.util.Map.of("type", "function_call", "name", BundleInvestigationDecision.FUNCTION, "arguments", "{}")), "usage", java.util.Map.of("input_tokens", 1, "output_tokens", 1)));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "captured invoice", 2048, InvoiceInvestigationDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("INVALID_RESPONSE", broker.execute(id)); assertFalse(broker.outcome(id).has("outputText"));
    }
    @Test void bundleDecisionTransportExtractsOnlyTheExpectedFunctionAndIgnoresReasoning() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(BundleInvestigationDecision.FUNCTION, request.getAsJsonObject("tool_choice").get("name").getAsString());
            assertEquals(BundleInvestigationDecision.INSTRUCTIONS, request.get("instructions").getAsString());
            var decision = java.util.Map.of("decision", java.util.Map.of("action", "read_evidence", "caseId", CaseInputs.CASE_ID, "inputId", "description", "offset", 0, "maxCharacters", 4000));
            String response = CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                    java.util.Map.of("type", "reasoning", "summary", List.of(java.util.Map.of("text", "do-not-retain-reasoning"))),
                    java.util.Map.of("type", "function_call", "name", BundleInvestigationDecision.FUNCTION, "arguments", CaseInputs.JSON.toJson(decision)))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "both cases", 2048, BundleInvestigationDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); var outcome = broker.outcome(id);
        assertEquals("read_evidence", BundleInvestigationDecision.parse(outcome.get("outputText").getAsString()).get("action").getAsString());
        assertFalse(outcome.toString().contains("do-not-retain-reasoning")); assertEquals(1, calls.get());
    }

    @Test void caseDecisionTransportExtractsTheScopedFunctionForBothCaseProtocols() throws Exception {
        for (String caseId : List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)) {
            String protocol = CaseInvestigationDecision.protocol(caseId);
            server.createContext("/v1/responses", exchange -> {
                calls.incrementAndGet(); var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
                assertEquals(CaseInvestigationDecision.FUNCTION, request.getAsJsonObject("tool_choice").get("name").getAsString());
                assertEquals(CaseInvestigationDecision.schema(protocol), request.getAsJsonArray("tools").get(0).getAsJsonObject().get("parameters"));
                String arguments = CaseInputs.JSON.toJson(java.util.Map.of("decision", java.util.Map.of("action", "read_evidence", "caseId", caseId, "inputId", "description", "offset", 0, "maxCharacters", 4000)));
                String response = CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                        java.util.Map.of("type", "reasoning", "summary", List.of(java.util.Map.of("text", "do-not-retain-reasoning"))),
                        java.util.Map.of("type", "function_call", "name", CaseInvestigationDecision.FUNCTION, "arguments", arguments))));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "scoped case", 2048, protocol), Duration.ofMinutes(1));
            assertEquals("COMPLETED", broker.execute(id)); var outcome = broker.outcome(id);
            assertEquals(caseId, CaseInvestigationDecision.parse(outcome.get("outputText").getAsString(), caseId).get("caseId").getAsString());
            assertFalse(outcome.toString().contains("do-not-retain-reasoning")); server.removeContext("/v1/responses");
        }
        assertEquals(2, calls.get()); assertNoSecret();
    }

    @Test void capturedTeamTransportUsesItsOwnStrictFunctionAndRetainsNoReasoning() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(CapturedTeamDecision.FUNCTION, request.getAsJsonObject("tool_choice").get("name").getAsString());
            assertEquals(CapturedTeamDecision.INSTRUCTIONS, request.get("instructions").getAsString());
            var tool = request.getAsJsonArray("tools").get(0).getAsJsonObject(); assertTrue(tool.get("strict").getAsBoolean());
            assertEquals(CapturedTeamDecision.schema(), tool.get("parameters")); assertFalse(request.get("parallel_tool_calls").getAsBoolean());
            assertFalse(request.get("store").getAsBoolean()); assertFalse(request.get("background").getAsBoolean());
            var arguments = java.util.Map.of("decision", java.util.Map.of("action", "finish", "summary", "Bounded fixture review", "citations", List.of("worker-1"), "followUps", List.of(), "conflicts", List.of()));
            String response = CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                    java.util.Map.of("type", "reasoning", "summary", List.of(java.util.Map.of("text", "do-not-retain-reasoning"))),
                    java.util.Map.of("type", "function_call", "name", CapturedTeamDecision.FUNCTION, "arguments", CaseInputs.JSON.toJson(arguments)))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "captured review", 2048, CapturedTeamDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); assertEquals("finish", CapturedTeamDecision.parse(broker.outcome(id).get("outputText").getAsString()).get("action").getAsString());
        assertFalse(broker.outcome(id).toString().contains("do-not-retain-reasoning")); assertEquals("COMPLETED", broker.execute(id)); assertEquals(1, calls.get()); assertNoSecret();
    }

    @Test void capturedReconciliationTransportBindsItsSchemaAndIgnoresReasoning() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(CapturedReconciliationDecision.FUNCTION, request.getAsJsonObject("tool_choice").get("name").getAsString());
            assertEquals(CapturedReconciliationDecision.INSTRUCTIONS, request.get("instructions").getAsString());
            assertEquals(CapturedReconciliationDecision.schema(), request.getAsJsonArray("tools").get(0).getAsJsonObject().get("parameters"));
            assertFalse(request.get("parallel_tool_calls").getAsBoolean()); assertFalse(request.get("store").getAsBoolean()); assertFalse(request.get("background").getAsBoolean());
            var args = java.util.Map.of("decision", java.util.Map.of("action", "read_evidence", "caseId", CaseInputs.CASE_ID, "inputId", "execution", "offset", 0, "maxCharacters", 4000));
            var body = CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                    java.util.Map.of("type", "reasoning", "summary", List.of(java.util.Map.of("text", "do-not-retain-reasoning"))),
                    java.util.Map.of("type", "function_call", "name", CapturedReconciliationDecision.FUNCTION, "arguments", CaseInputs.JSON.toJson(args))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "captured reconciliation", 2048, CapturedReconciliationDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); assertEquals("read_evidence", CapturedReconciliationDecision.parse(broker.outcome(id).get("outputText").getAsString()).get("action").getAsString());
        assertFalse(broker.outcome(id).toString().contains("do-not-retain-reasoning")); assertNoSecret();
    }
    @Test void reconciliationCannotAcceptTheEarlierReviewFunctionAsItsOwnDecision() throws Exception {
        reply(200, CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                java.util.Map.of("type", "function_call", "name", CapturedTeamDecision.FUNCTION, "arguments", "{\"decision\":{\"action\":\"finish\"}}")))));
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "captured reconciliation", 2048, CapturedReconciliationDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("INVALID_RESPONSE", broker.execute(id)); assertFalse(broker.outcome(id).has("outputText")); assertEquals(1, calls.get()); assertNoSecret();
    }
    @Test void capturedTeamTransportCannotAcceptAWorkerFunctionAsItsOwnDecision() throws Exception {
        reply(200, CaseInputs.JSON.toJson(java.util.Map.of("status", "completed", "usage", java.util.Map.of("input_tokens", 20, "output_tokens", 30), "output", List.of(
                java.util.Map.of("type", "function_call", "name", CaseInvestigationDecision.FUNCTION, "arguments", "{\"decision\":{\"action\":\"finish\"}}")))));
        var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "captured review", 2048, CapturedTeamDecision.PROTOCOL), Duration.ofMinutes(1));
        assertEquals("INVALID_RESPONSE", broker.execute(id)); assertEquals("INVALID_RESPONSE", broker.execute(id)); assertFalse(broker.outcome(id).has("outputText")); assertEquals(1, calls.get()); assertNoSecret();
    }

    @Test void dispatchIsScopedAndFinalizationDoesNotRepeatTheCallOrRetainSecrets() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer " + KEY, exchange.getRequestHeaders().getFirst("Authorization"));
            var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(5, request.size()); assertFalse(request.get("store").getAsBoolean());
            assertFalse(request.get("background").getAsBoolean()); assertFalse(request.has("tools"));
            byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker(); String id = register(broker);
        assertEquals("COMPLETED", broker.execute(id));
        assertEquals("COMPLETED", broker().execute(id));
        assertEquals("COMPLETED", broker().revoke(id));
        assertEquals(1, calls.get());
        String result = Files.readString(root.resolve(id).resolve("result.json"));
        assertTrue(result.contains("ready")); assertFalse(result.contains("do-not-retain-reasoning"));
        assertNoSecret();
    }

    @Test void requestMutationAndExpiryDenyBeforeCredentialResolution() throws Exception {
        reply(200, RESPONSE);
        var broker = broker(); String id = register(broker);
        var changed = CaseInputs.object(Files.readAllBytes(root.resolve(id).resolve("request.json")));
        changed.addProperty("model", "gpt-other"); CaseInputs.write(root.resolve(id).resolve("request.json"), changed);
        assertEquals("AUTHORIZATION_DENIED", broker.execute(id));
        Instant now = Instant.now();
        var early = broker(Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(5)); String expired = register(early);
        assertEquals("AUTHORIZATION_DENIED", broker(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC), Duration.ofSeconds(5)).execute(expired));
        assertEquals(0, calls.get());
        assertEquals(0, credentialReads.get());
    }

    @Test void queuedRevocationAndInterruptedDispatchNeverReplay() throws Exception {
        reply(200, RESPONSE); var broker = broker(); String revoked = register(broker);
        assertEquals("CLOSED_WITHOUT_DISPATCH", broker.revoke(revoked));
        assertEquals("CLOSED_WITHOUT_DISPATCH", broker.execute(revoked));
        String interrupted = register(broker);
        Files.writeString(root.resolve(interrupted).resolve("started.json"), "{}");
        assertEquals("UNKNOWN_REMOTE_OUTCOME", broker().recover(interrupted));
        assertEquals("UNKNOWN_REMOTE_OUTCOME", broker.execute(interrupted));
        assertEquals(0, calls.get());
    }

    @Test void providerCredentialRejectionRateLimitsAndRedirectsAreSanitizedAndNotRetried() throws Exception {
        for (int status : new int[] {401, 403, 429, 302, 503}) {
            reply(status, "provider error containing " + KEY);
            var broker = broker(); String id = register(broker);
            String expected = switch(status) { case 401, 403 -> "CREDENTIAL_REJECTED"; case 429 -> "RATE_LIMITED"; case 302 -> "REDIRECT_REJECTED"; default -> "PROVIDER_ERROR"; };
            assertEquals(expected, broker.execute(id)); assertEquals(expected, broker.execute(id));
            server.removeContext("/v1/responses");
        }
        assertEquals(5, calls.get()); assertNoSecret();
    }

    @Test void liveRevocationDiscardsLateOutputAndKeepsUnknownRemoteOutcome() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var broker = broker(); String id = register(broker);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> broker.execute(id));
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertEquals("REVOCATION_REQUESTED", broker().revoke(id));
                assertEquals("ACCESS_ENDED", result.get(5, TimeUnit.SECONDS));
                assertEquals("ACCESS_ENDED", broker().recover(id));
                assertTrue(Files.readString(root.resolve(id).resolve("result.json")).contains("UNKNOWN"));
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
        }
    }

    @Test void parentTrialAdmissionDeniesBeforeCredentialReadAndCanStopInFlightTransport() throws Exception {
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var transport = new ModelHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), Duration.ofSeconds(5));
        var broker = new ModelCallBroker(root, () -> { credentialReads.incrementAndGet(); return KEY; }, transport, Clock.systemUTC(), allowed::get);
        String denied = register(broker); assertEquals("AUTHORIZATION_DENIED", broker.execute(denied)); assertEquals(0, credentialReads.get());
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        allowed.set(true); String id = register(broker);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> broker.execute(id));
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS)); allowed.set(false);
                assertEquals("ACCESS_ENDED", result.get(5, TimeUnit.SECONDS));
                assertEquals("UNKNOWN", broker.outcome(id).get("remoteOutcome").getAsString()); assertEquals(1, calls.get());
                allowed.set(true); assertEquals("ACCESS_ENDED", broker.execute(id)); assertEquals(1, calls.get()); assertNoSecret();
            } finally { release.countDown(); }
        }
    }

    @Test void oversizedMalformedIncompleteAndSecretEchoResponsesCannotBecomeSuccess() throws Exception {
        for (String response : new String[] {"x".repeat(262145), "not-json", "{\"status\":\"incomplete\"}", RESPONSE.replace("ready", KEY), RESPONSE.replace("ready", KEY.replace("f", "\\u0066"))}) {
            reply(200, response); var broker = broker(); String id = register(broker);
            assertNotEquals("COMPLETED", broker.execute(id));
            assertNoSecret(); server.removeContext("/v1/responses");
        }
        assertEquals(5, calls.get());
    }

    @Test void missingCredentialAndUnexpectedDestinationFailClosed() throws Exception {
        var broker = new ModelCallBroker(root, () -> null, new ModelHttpTransport(), Clock.systemUTC());
        assertEquals("CREDENTIAL_UNAVAILABLE", broker.execute(register(broker)));
        assertThrows(IllegalArgumentException.class, () -> new ModelHttpTransport(URI.create("https://example.com"), Duration.ofSeconds(1)));
        assertEquals(0, calls.get());
    }

    @Test void timeoutCoversAStalledResponseBodyWithoutAutomaticReplay() throws Exception {
        var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try {
            var broker = broker(Clock.systemUTC(), Duration.ofSeconds(2)); String id = register(broker);
            assertEquals("UNKNOWN_REMOTE_OUTCOME", broker.execute(id));
            assertEquals("UNKNOWN_REMOTE_OUTCOME", broker.execute(id));
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void permissionExpiryDuringARequestDiscardsTheRemoteResult() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var broker = broker(clock, Duration.ofSeconds(5)); String id = register(broker);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> broker.execute(id));
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                now.set(now.get().plusSeconds(60));
                assertEquals("ACCESS_ENDED", result.get(5, TimeUnit.SECONDS));
                assertEquals("ACCESS_ENDED", broker.recover(id)); assertEquals(1, calls.get());
            } finally { release.countDown(); }
        }
    }

    @Test void controllerDeathAfterServerReceiptCannotResendTheRequest() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        String id = register(broker());
        var controller = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Controller.class.getName(), root.toString(), id,
                Integer.toString(server.getAddress().getPort())).redirectErrorStream(true)
                .redirectOutput(root.resolve("controller.log").toFile()).start();
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS));
            controller.destroyForcibly(); assertTrue(controller.waitFor(10, TimeUnit.SECONDS));
            String status = "ACTIVE"; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (status.equals("ACTIVE") && System.nanoTime() < deadline) { status = broker().recover(id); if (status.equals("ACTIVE")) Thread.sleep(50); }
            assertEquals("UNKNOWN_REMOTE_OUTCOME", status);
            assertEquals("UNKNOWN_REMOTE_OUTCOME", broker().execute(id));
            assertEquals(1, calls.get()); assertNoSecret();
        } finally { controller.destroyForcibly(); release.countDown(); }
    }

    public static final class Controller {
        private Controller() {}
        public static void main(String[] args) throws Exception {
            var transport = new ModelHttpTransport(URI.create("http://127.0.0.1:" + args[2] + "/v1/responses"), Duration.ofSeconds(30));
            new ModelCallBroker(Path.of(args[0]), () -> KEY, transport, Clock.systemUTC()).execute(args[1]);
        }
    }

    private void assertNoSecret() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) assertFalse(Files.readString(path).contains(KEY), path.getFileName().toString());
        }
    }

    @Test void structuredInvestigationRequestRetainsItsExactGrantAcrossProcesses() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            var body = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().get("strict").getAsBoolean());
            assertFalse(body.get("store").getAsBoolean()); assertFalse(body.get("parallel_tool_calls").getAsBoolean());
            assertTrue(body.get("instructions").getAsString().contains("untrusted data"));
            byte[] bytes = "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"investigation_decision\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker();
        String id = broker.register(new ModelCallBroker.Request("gpt-test", "case state", 2048, "investigation-v1"), Duration.ofMinutes(1));
        var controller = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Controller.class.getName(), root.toString(), id,
                Integer.toString(server.getAddress().getPort())).redirectErrorStream(true)
                .redirectOutput(root.resolve("structured-controller.log").toFile()).start();
        try {
            assertTrue(controller.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, controller.exitValue());
            assertEquals("COMPLETED", broker.outcome(id).get("status").getAsString()); assertEquals(1, calls.get());
            assertNoSecret();
        } finally { controller.destroyForcibly(); }
    }

    @Test void boundedInvestigationUsesSeparateInstructionsWithUnchangedToolPermissions() throws Exception {
        var original = new ModelCallBroker.Request("gpt-test", "case", 2048, "investigation-v1");
        var bounded = new ModelCallBroker.Request("gpt-test", "case", 2048, "investigation-v2");
        var oldBody = CaseInputs.object(original.body()); var newBody = CaseInputs.object(bounded.body());
        assertEquals(InvestigationDecision.INSTRUCTIONS, oldBody.get("instructions").getAsString());
        assertEquals(InvestigationDecision.BOUNDED_INSTRUCTIONS, newBody.get("instructions").getAsString());
        assertEquals(oldBody.get("tools"), newBody.get("tools"));
        assertEquals(oldBody.get("tool_choice"), newBody.get("tool_choice"));
        assertNotEquals(CaseInputs.hash(original.body()), CaseInputs.hash(bounded.body()));
        reply(200, "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"investigation_decision\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");
        var broker = broker(); String id = broker.register(bounded, Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); assertNoSecret();
    }

    @Test void subscriptionProtocolUsesScopedInstructionsAndRepairFunction() throws Exception {
        var request = new ModelCallBroker.Request("gpt-test", "evidence", 4096, "subscription-repair-v1");
        var body = CaseInputs.object(request.body());
        assertEquals(SubscriptionCandidates.INSTRUCTIONS, body.get("instructions").getAsString());
        assertEquals("repair_proposal", body.getAsJsonObject("tool_choice").get("name").getAsString());
        assertFalse(body.get("parallel_tool_calls").getAsBoolean());
        reply(200, "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"repair_proposal\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");
        var broker = broker(); String id = broker.register(request, Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); assertNoSecret();
    }
    @Test void fixtureRepairProtocolPreservesLegacyPromptGrantsAndToolPermissions() throws Exception {
        // Snapshot the deployed repair-v1 prompt: old grants bind its exact wire bytes.
        assertEquals("6991ec1c448f6a05b47247a60f338516c4abe288c8bcff8e10690bf33a003686",
                CaseInputs.hash(RepairProposal.INSTRUCTIONS.getBytes(StandardCharsets.UTF_8)));
        var oldRequest = new ModelCallBroker.Request("gpt-test", "evidence", 4096, "repair-v1");
        var newRequest = new ModelCallBroker.Request("gpt-test", "evidence", 4096, "repair-v2");
        var oldBody = CaseInputs.object(oldRequest.body()); var newBody = CaseInputs.object(newRequest.body());
        assertEquals(RepairProposal.INSTRUCTIONS, oldBody.get("instructions").getAsString());
        assertEquals(RepairProposal.FIXTURE_INSTRUCTIONS, newBody.get("instructions").getAsString());
        assertEquals(oldBody.get("tools"), newBody.get("tools"));
        assertEquals(oldBody.get("tool_choice"), newBody.get("tool_choice"));
        assertFalse(newBody.get("parallel_tool_calls").getAsBoolean());
        assertNotEquals(CaseInputs.hash(oldRequest.body()), CaseInputs.hash(newRequest.body()));
        reply(200, "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"repair_proposal\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");
        var broker = broker();
        for (var request : new ModelCallBroker.Request[] {oldRequest, newRequest}) {
            String id = broker.register(request, Duration.ofMinutes(1));
            assertEquals("COMPLETED", broker().execute(id));
            assertEquals("COMPLETED", broker().execute(id));
        }
        assertEquals(2, calls.get()); assertNoSecret();
    }
    @Test void investigationRejectsMultipleMessagesAndMultipleDecisions() throws Exception {
        for (String output : new String[] {
                "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}{}\"}]}]",
                "[{\"type\":\"function_call\",\"name\":\"investigation_decision\",\"arguments\":\"{}\"},{\"type\":\"function_call\",\"name\":\"investigation_decision\",\"arguments\":\"{}\"}]"}) {
            reply(200, "{\"status\":\"completed\",\"output\":" + output + ",\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");
            var broker = broker(); String id = broker.register(new ModelCallBroker.Request("gpt-test", "case", 2048, "investigation-v1"), Duration.ofMinutes(1));
            assertEquals("INVALID_RESPONSE", broker.execute(id)); server.removeContext("/v1/responses");
        }
    }
    @Test void coordinationProtocolUsesItsOwnSchemaAndRejectsInvestigationFunctionResponses() throws Exception {
        var request = new ModelCallBroker.Request("gpt-test", "captured handoff", 2048, "coordination-v1"); var body = CaseInputs.object(request.body());
        assertEquals("coordination_decision", body.getAsJsonObject("tool_choice").get("name").getAsString());
        assertEquals(CoordinationDecision.INSTRUCTIONS, body.get("instructions").getAsString());
        var v2 = CaseInputs.object(new ModelCallBroker.Request("gpt-test", "captured handoff", 2048, "coordination-v2").body());
        assertEquals(CoordinationDecision.OBSERVED_REFERENCE_INSTRUCTIONS, v2.get("instructions").getAsString());
        assertEquals(body.get("tools"), v2.get("tools")); assertEquals(body.get("tool_choice"), v2.get("tool_choice"));
        assertFalse(body.get("parallel_tool_calls").getAsBoolean());
        for (String function : List.of("investigation_decision", "coordination_decision")) {
            reply(200, "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"" + function + "\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");
            var broker = broker(); String id = broker.register(request, Duration.ofMinutes(1));
            assertEquals(function.equals("coordination_decision") ? "COMPLETED" : "INVALID_RESPONSE", broker.execute(id)); server.removeContext("/v1/responses");
        }
        assertNoSecret();
    }
    @Test void reconciliationProtocolRejectsOtherFunctionsAndNeverRetainsReasoningItems() throws Exception {
        var request = new ModelCallBroker.Request("gpt-test", "captured follow-up", 2048, "reconciliation-v1"); var body = CaseInputs.object(request.body());
        assertEquals("reconciliation_decision", body.getAsJsonObject("tool_choice").get("name").getAsString());
        assertEquals(ReconciliationDecision.schema(), body.getAsJsonArray("tools").get(0).getAsJsonObject().get("parameters"));
        assertFalse(body.get("parallel_tool_calls").getAsBoolean());
        for (String function : List.of("coordination_decision", "reconciliation_decision")) {
            reply(200, "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"do-not-retain-reasoning\"}]},{\"type\":\"function_call\",\"name\":\"" + function + "\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");
            var broker = broker(); String id = broker.register(request, Duration.ofMinutes(1));
            assertEquals(function.equals("reconciliation_decision") ? "COMPLETED" : "INVALID_RESPONSE", broker.execute(id));
            assertFalse(Files.readString(root.resolve(id).resolve("result.json")).contains("do-not-retain-reasoning")); server.removeContext("/v1/responses");
        }
        assertNoSecret();
    }
}
