package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceSingleAgentV2Test {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final ScriptedBrokers brokers = new ScriptedBrokers();
    private Path inputs; private ContentDigest identity; private InvoiceComparisonInputs.Bundle bundle;
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceInvestigationV2(root, inputs, identity, clock, brokers); }
    private InvoiceSingleAgentV2 adapter() throws IOException { return new InvoiceSingleAgentV2(root, bundle, runtime()); }
    private String fixture(int calls) throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); Path capture = Files.createDirectory(root.resolve("source"));
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), capture.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        inputs = capture.resolve("inputs"); bundle = InvoiceComparisonInputs.capture(plan, run, inputs); identity = bundle.identity();
        var base = InvoiceSingleAgentV2.checkDefinition(identity); var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(),
                base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, 64000, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT));
    }
    private Path directory(String id) { return root.resolve("invoice-single-agent-v2/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String finish(String response, List<String> citations) { return decision(Map.of("action", "finish", "response", response, "summary", "The available evidence is limited.",
            "summaryCitations", citations, "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "This is advisory evidence, not migration approval.", "citations", citations)))); }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonObject("usage"); }

    @Test void adaptiveCatalogAndTextReadsShareTheRuntimeAndExportUnreviewedAbstention() throws Exception {
        String id = fixture(3); brokers.script = (input, turn) -> switch (turn) {
            case 1 -> decision(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 40));
            case 2 -> read("task");
            default -> finish("ABSTAINED", List.of("read-2"));
        };
        var result = adapter().run(id); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(3, usage(result).get("reservedCalls").getAsInt()); assertEquals(3, usage(result).get("toolCalls").getAsInt());
        assertEquals(0, usage(result).get("caseExecutions").getAsInt()); assertEquals(3, brokers.dispatched);
        var output = result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals("ABSTAINED", output.get("response").getAsString()); assertTrue(output.get("review").isJsonNull());
        assertFalse(CaseInputs.object(Files.readAllBytes(directory(id).resolve("dossier.json"))).get("sourceExecutionCoverageSatisfied").getAsBoolean());
        assertEquals(result, adapter().run(id)); assertEquals(3, brokers.dispatched);
        var context = brokers.contexts.getLast();
        assertEquals(InvoiceDecisionV2.taskIdentity(), context.getAsJsonObject("publicTask").get("sha256").getAsString());
        assertEquals("source/", context.getAsJsonArray("readMetadata").get(0).getAsJsonObject().get("prefix").getAsString());
        assertTrue(context.getAsJsonObject("scheduling").get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertEquals(1, context.getAsJsonObject("scheduling").get("sharedCallsRemainingBeforeRound").getAsInt());
        assertEquals(0, context.getAsJsonObject("captureMetadata").get("modelCalls").getAsInt());
        assertEquals(InvoiceDecisionV2.taskIdentity(), CaseInputs.object(Files.readAllBytes(directory(id).resolve("dossier.json"))).get("publicTaskSha256").getAsString());
    }
    @Test void fullDeclaredCallPoolIsUsableAndExhaustionPreservesEveryReservation() throws Exception {
        String id = fixture(60); brokers.script = (input, turn) -> read("task"); var result = adapter().run(id);
        assertEquals("CALL_BUDGET_EXHAUSTED", result.get("stopReason").getAsString()); assertEquals(60, brokers.dispatched);
        assertEquals(60, usage(result).get("reservedCalls").getAsInt()); assertEquals(61, usage(result).get("toolCalls").getAsInt());
        assertEquals("NOT_PROVIDED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
        assertTrue(brokers.contexts.getLast().getAsJsonObject("scheduling").get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertFalse(Files.exists(directory(id).resolve("dossier.json")));
    }
    @Test void completedCallResumesWithoutRedispatchAndRebuildsIdenticalContext() throws Exception {
        String id = fixture(6); brokers.script = (input, turn) -> turn == 1 ? read("task") : finish("ABSTAINED", List.of("read-1"));
        brokers.interruptAfterPersist = true; assertThrows(IOException.class, () -> adapter().run(id)); assertEquals(1, brokers.dispatched);
        assertFalse(Files.exists(directory(id).resolve("event-1.json"))); var result = adapter().run(id);
        assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(2, brokers.dispatched);
        assertEquals(1, brokers.contexts.getLast().getAsJsonArray("readMetadata").size());
    }
    @Test void unmappedRegistrationKeepsAnUncertainSlotWithoutRetrying() throws Exception {
        String id = fixture(6); brokers.interruptRegistration = true; assertThrows(IOException.class, () -> adapter().run(id));
        var result = adapter().run(id); assertEquals("CALL_REGISTRATION_UNCERTAIN", result.get("stopReason").getAsString());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt()); assertEquals(1, brokers.registered); assertEquals(0, brokers.dispatched);
    }
    @Test void unknownRemoteOutcomeCannotBecomeASuccessfulAbstention() throws Exception {
        String id = fixture(6); brokers.remote = "UNKNOWN"; var result = adapter().run(id);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(1, usage(result).get("reservedCalls").getAsInt());
    }
    @Test void outOfScopeReadsAndUnsupportedCitationsStopWithoutInventingEvidence() throws Exception {
        String id = fixture(6); brokers.script = (input, turn) -> read("../receipt.json");
        assertEquals("INVALID_EVIDENCE_READ", adapter().run(id).get("stopReason").getAsString()); assertEquals(1, usage(adapter().retained(id)).get("toolCalls").getAsInt());
    }
    @Test void inventedOrCatalogOnlyCitationsAreRejected() throws Exception {
        String id = fixture(6); brokers.script = (input, turn) -> turn == 1 ? decision(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 40)) : finish("ABSTAINED", List.of("read-1"));
        assertEquals("UNSUPPORTED_DOSSIER", adapter().run(id).get("stopReason").getAsString());
        var rejected = CaseInputs.object(Files.readAllBytes(directory(id).resolve("event-2.json")));
        assertEquals("REJECTED_FINISH", rejected.get("actionStatus").getAsString());
        assertTrue(rejected.getAsJsonObject("readiness").getAsJsonArray("citableTextPageIds").isEmpty());
    }
    @Test void expiryAndRevocationSurviveRestartWithoutAnotherCall() throws Exception {
        String id = fixture(6); runtime().start(id); clock.now = clock.now.plusSeconds(1201);
        assertEquals("TRIAL_EXPIRED", adapter().run(id).get("stopReason").getAsString()); assertEquals(0, brokers.dispatched);
    }
    @Test void revocationStopsBeforeCredentialOrModelWork() throws Exception {
        String id = fixture(6); runtime().revoke(id); var result = adapter().run(id);
        assertEquals("CANCELLED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(0, brokers.registered);
    }
    @Test void retainedRequestAndFinalArtifactsCannotChangeAndOwnReceiptCanRecover() throws Exception {
        String id = fixture(6); var result = adapter().run(id); Files.delete(directory(id).resolve("receipt.json"));
        assertThrows(RuntimeException.class, () -> adapter().retained(id)); assertEquals(result, adapter().run(id)); assertEquals(1, brokers.dispatched);
        Files.writeString(directory(id).resolve("dossier.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(id));
    }
    @Test void changedResumedRequestFailsBeforeAnotherDispatch() throws Exception {
        String id = fixture(6); brokers.interruptAfterPersist = true; assertThrows(IOException.class, () -> adapter().run(id));
        Files.writeString(directory(id).resolve("request-1.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(1, brokers.dispatched);
    }
    @Test void freshJvmReadsAndResumesCompletedTrialWithoutCredentialsOrWrites() throws Exception {
        String id = fixture(6); var result = adapter().run(id); Path output = root.resolve("fresh-jvm.log");
        var before = artifactHashes();
        for (String action : List.of("read", "run")) {
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                    InvoiceSingleAgentV2.class.getName(), action, root.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().remove("OPENAI_API_KEY"); var process = builder.start();
            try { assertTrue(process.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); assertTrue(Files.readString(output).contains("Status: COMPLETED")); }
            finally { process.destroyForcibly(); }
        }
        assertEquals(before, artifactHashes()); assertEquals(result, adapter().retained(id)); assertEquals(1, brokers.dispatched);
    }
    private Map<String,String> artifactHashes() throws Exception {
        var hashes = new TreeMap<String,String>(); for (String name : List.of("invoice-v2-trials", "invoice-single-agent-v2")) {
            try (var files = Files.walk(root.resolve(name))) { for (var file : files.filter(Files::isRegularFile).toList()) if (!file.getFileName().toString().endsWith(".lock")) hashes.put(root.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file))); }
        } return hashes;
    }
    @Test void rejectedAnswerPreservesCandidateAndCannotTriggerASecondAttempt() throws Exception {
        String id = fixture(6); brokers.script = (input, turn) -> finish("ANSWERED", List.of("read-99"));
        var result = adapter().run(id); assertEquals("UNSUPPORTED_DOSSIER", result.get("stopReason").getAsString());
        var event = CaseInputs.object(Files.readAllBytes(directory(id).resolve("event-1.json")));
        assertEquals("ANSWERED", event.getAsJsonObject("decision").get("response").getAsString());
        assertEquals("REJECTED_FINISH", event.get("actionStatus").getAsString());
        assertFalse(Files.exists(directory(id).resolve("dossier.json"))); assertEquals(result, adapter().run(id));
        assertEquals(1, brokers.dispatched); assertEquals(1, usage(result).get("reservedCalls").getAsInt());
    }
    @Test void invalidModelActionStaysRejectedWithoutHostWrittenFindings() throws Exception {
        String id = fixture(6); brokers.script = (input, turn) -> decision(Map.of("action", "grant_permission"));
        var result = adapter().run(id); assertEquals("INVALID_MODEL_DECISION", result.get("stopReason").getAsString());
        assertEquals("REJECTED_DECISION", CaseInputs.object(Files.readAllBytes(directory(id).resolve("event-1.json"))).get("actionStatus").getAsString());
        assertEquals(1, brokers.dispatched); assertFalse(Files.exists(directory(id).resolve("dossier.json")));
    }
    @Test void insufficientContextBytesStopBeforeTheFirstModelReservation() throws Exception {
        fixture(6); var base = InvoiceSingleAgentV2.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(),
                base.workloads(), 1, new Limits(6, 1200000, 64, 2048));
        String id = adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT));
        var result = adapter().run(id); assertEquals("CONTEXT_BUDGET_EXHAUSTED", result.get("stopReason").getAsString());
        assertEquals(0, usage(result).get("reservedCalls").getAsInt()); assertEquals(1, usage(result).get("toolCalls").getAsInt());
        assertEquals(0, brokers.registered);
    }
    @Test void changedOwnedEventCannotBeSubstitutedDuringRestart() throws Exception {
        String id = fixture(6); brokers.script = (input, turn) -> turn == 1 ? read("task") : finish("ABSTAINED", List.of("read-1"));
        brokers.interruptAfterPersist = true; brokers.interruptAfterCall = 2; assertThrows(IOException.class, () -> adapter().run(id));
        Path file = directory(id).resolve("event-1.json"); var event = CaseInputs.object(Files.readAllBytes(file));
        event.getAsJsonObject("page").addProperty("participant", "peer"); Files.writeString(file, event.toString());
        assertThrows(IllegalArgumentException.class, () -> adapter().run(id)); assertEquals(2, brokers.dispatched);
    }
    @Test void inputArmAndOldRuntimeCannotReplaceTheRegisteredV2Path() throws Exception {
        fixture(6); var definition = InvoiceSingleAgentV2.checkDefinition(identity);
        assertThrows(IllegalArgumentException.class, () -> adapter().register("0".repeat(64), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT)));
        assertThrows(IllegalArgumentException.class, () -> adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.DETERMINISTIC)));
        var old = CoordinationRuntime.forInvoiceInvestigation(root, inputs, identity, clock, brokers);
        assertThrows(IllegalArgumentException.class, () -> new InvoiceSingleAgentV2(root, bundle, old).register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT)));
        assertEquals(0, brokers.registered);
    }
    @Test void actualBrokerLoopFollowsPrefixAndTextDecisionsThenStopsAtOneAuthoredAbstention() throws Exception {
        fixture(6); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var requests = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            requests.incrementAndGet(); var body = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(InvoiceDecisionV2.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString());
            var context = JsonParser.parseString(body.get("input").getAsString()).getAsJsonObject();
            assertEquals("invoice-single-agent-v2", context.getAsJsonObject("subject").get("participantId").getAsString());
            String action = switch (context.getAsJsonArray("readMetadata").size()) {
                case 0 -> decision(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 40));
                case 1 -> read("task");
                default -> finish("ABSTAINED", List.of("read-2"));
            };
            byte[] response = CaseInputs.JSON.toJson(Map.of("status", "completed", "usage", Map.of("input_tokens", 5, "output_tokens", 2),
                    "output", List.of(Map.of("type", "function_call", "name", InvoiceDecisionV2.FUNCTION, "arguments", action)))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); try (var output = exchange.getResponseBody()) { output.write(response); }
        }); server.start();
        try {
            CoordinationRuntime.Brokers actual = (store, permitted) -> {
                var broker = new ModelCallBroker(store, () -> "fixture-credential-not-a-real-key", new ModelHttpTransport(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), Duration.ofSeconds(5)), clock, permitted);
                return new CoordinationRuntime.Broker() {
                    public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException { return broker.register(request, lifetime); }
                    public JsonObject execute(String id) throws IOException { broker.execute(id); return broker.outcome(id); }
                    public JsonObject recover(String id) throws IOException { broker.recover(id); return broker.outcome(id); }
                };
            };
            var runtime = CoordinationRuntime.forInvoiceInvestigationV2(root, inputs, identity, clock, actual);
            var adapter = new InvoiceSingleAgentV2(root, bundle, runtime); String id = adapter.register(identity.sha256(),
                    InvoiceSingleAgentV2.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT));
            var result = adapter.run(id); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
            assertEquals("ABSTAINED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
            assertEquals(3, usage(result).get("reservedCalls").getAsInt()); assertEquals(3, usage(result).get("toolCalls").getAsInt());
            assertEquals(15, usage(result).get("inputTokens").getAsInt()); assertEquals(0, usage(result).get("caseExecutions").getAsInt());
            assertEquals(result, adapter.run(id)); assertEquals(3, requests.get());
        } finally { server.stop(0); }
    }
    private final class ScriptedBrokers implements CoordinationRuntime.Brokers {
        int registered, dispatched, interruptAfterCall = 1; boolean interruptAfterPersist, interruptRegistration; String remote = "RESPONSE_RECEIVED";
        BiFunction<JsonObject,Integer,String> script = (input, turn) -> finish("ABSTAINED", List.of()); final List<JsonObject> contexts = new ArrayList<>();
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    assertEquals(InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT), request.protocol());
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered++;
                    if (interruptRegistration) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file)); assertTrue(permitted.getAsBoolean()); dispatched++;
                    var input = JsonParser.parseString(CaseInputs.object(Files.readAllBytes(store.resolve(id + "/request.json"))).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    var response = json(Map.of("callId", id, "status", remote.equals("RESPONSE_RECEIVED") ? "COMPLETED" : "UNKNOWN_REMOTE_OUTCOME", "remoteOutcome", remote,
                            "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, dispatched))); DurableExecution.publish(file, response);
                    if (interruptAfterPersist && dispatched == interruptAfterCall) { interruptAfterPersist = false; throw new IOException("Interrupted controller"); } return response;
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
