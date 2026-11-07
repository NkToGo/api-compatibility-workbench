package io.github.nktogo.apicompatibility.runner;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceReconciliationBrokerV2Test {
    @TempDir Path root;
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger(), credentials = new AtomicInteger();
    private static final String KEY = "fixture-final-never-a-real-key";
    private static final String PREFIX = "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"business/\",\"offset\":0,\"limit\":40}}";
    @BeforeEach void start() throws Exception { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start(); }
    @AfterEach void stop() { server.stop(0); }
    private ModelCallBroker broker() throws Exception {
        return new ModelCallBroker(root, () -> { credentials.incrementAndGet(); return KEY; },
                new ModelHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), Duration.ofSeconds(5)), Clock.systemUTC());
    }
    private static ModelCallBroker.Request request() { return new ModelCallBroker.Request("gpt-test", "authored captured final context", 2048, InvoiceReconciliationDecisionV2.PROTOCOL); }
    private static Map<String, Object> function(String name, String arguments) { return Map.of("type", "function_call", "name", name, "arguments", arguments); }
    private static byte[] response(List<?> output) { return CaseInputs.JSON.toJson(Map.of("status", "completed", "usage", Map.of("input_tokens", 10, "output_tokens", 2), "output", output)).getBytes(StandardCharsets.UTF_8); }
    private void reply(List<?> output) {
        server.createContext("/v1/responses", exchange -> { calls.incrementAndGet(); byte[] bytes = response(output);
            exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    private void noSecret() throws Exception {
        try (var files = Files.walk(root)) { for (var file : files.filter(Files::isRegularFile).toList()) assertFalse(Files.readString(file).contains(KEY)); }
    }
    @Test void finalTransportForcesExactStrictFunctionWithoutRetainingReasoningOrRepeatingCalls() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var body = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(InvoiceReconciliationDecisionV2.INSTRUCTIONS, body.get("instructions").getAsString());
            assertEquals(InvoiceReconciliationDecisionV2.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString());
            var tools = body.getAsJsonArray("tools"); assertEquals(1, tools.size()); var tool = tools.get(0).getAsJsonObject();
            assertEquals("function", tool.get("type").getAsString()); assertEquals(InvoiceReconciliationDecisionV2.FUNCTION, tool.get("name").getAsString());
            assertTrue(tool.get("strict").getAsBoolean()); assertEquals(InvoiceReconciliationDecisionV2.schema(), tool.get("parameters"));
            assertFalse(body.get("store").getAsBoolean()); assertFalse(body.get("background").getAsBoolean()); assertFalse(body.get("parallel_tool_calls").getAsBoolean());
            byte[] bytes = response(List.of(Map.of("type", "reasoning", "summary", List.of(Map.of("text", "do-not-retain-final-reasoning"))), function(InvoiceReconciliationDecisionV2.FUNCTION, PREFIX)));
            exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        var broker = broker(); String id = broker.register(request(), Duration.ofMinutes(1));
        assertEquals("COMPLETED", broker.execute(id)); assertEquals("COMPLETED", broker().execute(id));
        var outcome = broker.outcome(id); assertEquals("catalog_prefix", InvoiceReconciliationDecisionV2.parse(outcome.get("outputText").getAsString()).get("action").getAsString());
        assertFalse(outcome.toString().contains("do-not-retain-final-reasoning")); assertEquals(1, calls.get()); assertEquals(1, credentials.get()); noSecret();
    }
    @Test void historicalOriginalFreshReviewUnknownDuplicateAbsentAndTextResponsesAreRejectedWithoutReplay() throws Exception {
        var valid = function(InvoiceReconciliationDecisionV2.FUNCTION, PREFIX);
        var outputs = new ArrayList<List<?>>();
        for (String name : List.of(InvoiceReconciliationDecision.FUNCTION, InvoiceDecisionV2.FUNCTION, InvoiceFollowUpDecisionV2.FUNCTION, InvoiceTeamDecisionV2.FUNCTION, "unknown_final_function")) outputs.add(List.of(function(name, PREFIX)));
        outputs.add(List.of(valid, valid)); outputs.add(List.of()); outputs.add(List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", PREFIX)))));
        for (var output : outputs) { reply(output); var broker = broker(); String id = broker.register(request(), Duration.ofMinutes(1));
            assertEquals("INVALID_RESPONSE", broker.execute(id)); assertEquals("INVALID_RESPONSE", broker().execute(id)); assertFalse(broker.outcome(id).has("outputText")); server.removeContext("/v1/responses"); }
        assertEquals(outputs.size(), calls.get()); noSecret();
    }
    @Test void malformedMatchingArgumentsAreRetainedForExplicitHostRejection() throws Exception {
        String malformed = "{\"decision\":{\"action\":\"delegate\"}}"; reply(List.of(function(InvoiceReconciliationDecisionV2.FUNCTION, malformed)));
        var broker = broker(); String id = broker.register(request(), Duration.ofMinutes(1)); assertEquals("COMPLETED", broker.execute(id));
        assertEquals(malformed, broker.outcome(id).get("outputText").getAsString()); assertThrows(IllegalArgumentException.class, () -> InvoiceReconciliationDecisionV2.parse(malformed));
        assertEquals("COMPLETED", broker().execute(id)); assertEquals(1, calls.get()); noSecret();
    }
    @Test void changedFinalProtocolDeniesBeforeCredentialResolutionOrTransport() throws Exception {
        var broker = broker(); String id = broker.register(request(), Duration.ofMinutes(1)); Path file = root.resolve(id + "/request.json");
        var changed = CaseInputs.object(Files.readAllBytes(file)); changed.addProperty("protocol", InvoiceFollowUpDecisionV2.WEBHOOK); Files.writeString(file, changed.toString());
        assertEquals("AUTHORIZATION_DENIED", broker.execute(id)); assertEquals(0, credentials.get()); assertEquals(0, calls.get()); noSecret();
    }
}
