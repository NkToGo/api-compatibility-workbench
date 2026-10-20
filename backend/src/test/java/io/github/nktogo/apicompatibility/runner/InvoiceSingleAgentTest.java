package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceSingleAgentTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final ScriptedBrokers brokers = new ScriptedBrokers();
    private Path inputs; private ContentDigest identity;
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceInvestigation(root, inputs, identity, clock, brokers); }
    private InvoiceSingleAgent adapter() throws IOException { return new InvoiceSingleAgent(root, runtime()); }
    private String fixture(int calls) throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); Path capture = Files.createDirectory(root.resolve("source"));
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), capture.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        inputs = capture.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
        var base = InvoiceSingleAgent.checkDefinition(identity); var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(),
                base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, 64000, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT));
    }
    private Path directory(String id) { return root.resolve("invoice-single-agent/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String finish(String response, List<String> citations) { return decision(Map.of("action", "finish", "response", response, "summary", "The available evidence is limited.",
            "summaryCitations", citations, "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "This is advisory evidence, not migration approval.", "citations", citations)))); }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonObject("usage"); }

    @Test void adaptiveCatalogAndTextReadsShareTheRuntimeAndExportUnreviewedAbstention() throws Exception {
        String id = fixture(10); brokers.script = (input, turn) -> switch (turn) {
            case 1 -> decision(Map.of("action", "catalog", "offset", 0, "limit", 40));
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
    }
    @Test void fullDeclaredCallPoolIsUsableAndExhaustionPreservesEveryReservation() throws Exception {
        String id = fixture(60); brokers.script = (input, turn) -> read("task"); var result = adapter().run(id);
        assertEquals("CALL_BUDGET_EXHAUSTED", result.get("stopReason").getAsString()); assertEquals(60, brokers.dispatched);
        assertEquals(60, usage(result).get("reservedCalls").getAsInt()); assertEquals(61, usage(result).get("toolCalls").getAsInt());
        assertEquals("NOT_PROVIDED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
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
        String id = fixture(6); brokers.script = (input, turn) -> turn == 1 ? decision(Map.of("action", "catalog", "offset", 0, "limit", 40)) : finish("ABSTAINED", List.of("read-1"));
        assertEquals("UNSUPPORTED_DOSSIER", adapter().run(id).get("stopReason").getAsString());
        assertFalse(InvoiceSingleAgent.supported(InvoiceInvestigationDecision.parse(finish("ABSTAINED", List.of("read-99"))), List.of(), json(Map.of("sourceExecutionCoverageSatisfied", true))));
    }
    @Test void requiredReadGapsAndIncompleteSourceExecutionCannotBecomeAnswered() {
        var pages = new ArrayList<JsonObject>(); var aliases = new ArrayList<>(InvoiceInvestigationDecision.REQUIRED); aliases.addAll(List.of("business/example", "order/example"));
        int index = 0; for (String alias : aliases) pages.add(json(Map.of("kind", "read_text", "pageId", "read-" + ++index, "alias", alias, "offset", 0, "nextOffset", 3, "totalCharacters", 3)));
        var decision = InvoiceInvestigationDecision.parse(finish("ANSWERED", List.of("read-1"))); var complete = json(Map.of("sourceExecutionCoverageSatisfied", true));
        assertTrue(InvoiceSingleAgent.supported(decision, pages, complete));
        assertFalse(InvoiceSingleAgent.supported(decision, pages, json(Map.of("sourceExecutionCoverageSatisfied", false))));
        pages.getFirst().addProperty("totalCharacters", 8); assertFalse(InvoiceSingleAgent.supported(decision, pages, complete));
        var second = pages.getFirst().deepCopy(); second.addProperty("pageId", "read-10"); second.addProperty("offset", 5); second.addProperty("nextOffset", 8); pages.add(second);
        assertFalse(InvoiceSingleAgent.supported(decision, pages, complete));
        var bridge = second.deepCopy(); bridge.addProperty("pageId", "read-11"); bridge.addProperty("offset", 3); bridge.addProperty("nextOffset", 5); pages.add(bridge);
        assertTrue(InvoiceSingleAgent.supported(decision, pages, complete));
    }
    @Test void duplicateFieldsFractionalOffsetsAndPermissionActionsFailStrictParsing() {
        for (String raw : List.of("{\"decision\":{\"action\":\"catalog\",\"action\":\"catalog\",\"offset\":0,\"limit\":40}}", "{\"decision\":{\"action\":\"catalog\",\"offset\":0.5,\"limit\":40}}",
                decision(Map.of("action", "grant_permission")), decision(Map.of("action", "catalog", "offset", "0", "limit", 40)), decision(Map.of("action", "read_text", "alias", "task", "offset", 0, "maxCharacters", 4001))))
            assertThrows(RuntimeException.class, () -> InvoiceInvestigationDecision.parse(raw));
    }
    @Test void contextTrimmingKeepsAllReadMetadataAndRejectsAnInsufficientByteCeiling() {
        var pages = new ArrayList<JsonObject>(); for (int i = 1; i <= 59; i++) pages.add(json(Map.of("pageId", "read-" + i, "kind", "read_text", "alias", "task", "offset", 0, "nextOffset", 4000,
                "totalCharacters", 4000, "content", "😀".repeat(2000))));
        var metadata = json(Map.of("sourceExecutionCoverageSatisfied", true)); String text = InvoiceSingleAgent.context(metadata, pages, 1, 18000);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 18000); var context = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(59, context.getAsJsonArray("readMetadata").size()); assertTrue(context.getAsJsonArray("recentPages").size() < 6);
        assertThrows(CoordinationRuntime.Stop.class, () -> InvoiceSingleAgent.context(metadata, pages, 1, 100));
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
                    InvoiceSingleAgent.class.getName(), action, root.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().remove("OPENAI_API_KEY"); var process = builder.start();
            try { assertTrue(process.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); assertTrue(Files.readString(output).contains("Status: COMPLETED")); }
            finally { process.destroyForcibly(); }
        }
        assertEquals(before, artifactHashes()); assertEquals(result, adapter().retained(id)); assertEquals(1, brokers.dispatched);
    }
    private Map<String,String> artifactHashes() throws Exception {
        var hashes = new TreeMap<String,String>(); for (String name : List.of("invoice-comparison-trials", "invoice-single-agent")) {
            try (var files = Files.walk(root.resolve(name))) { for (var file : files.filter(Files::isRegularFile).toList()) if (!file.getFileName().toString().endsWith(".lock")) hashes.put(root.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file))); }
        } return hashes;
    }
    @Test void invoiceModelFactoryCannotExpandIntoHistoricalProtocolsAndReadOnlyEntryStaysClosed() throws Exception {
        String id = fixture(6); var policy = InvoiceSingleAgent.policy(); var definition = InvoiceSingleAgent.checkDefinition(identity);
        assertThrows(IllegalArgumentException.class, () -> CoordinationRuntime.forInvoice(root, inputs, identity).register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), policy));
        var expanded = new CoordinationRuntime.Policy(1, 100, policy.adapterIdentity(), policy.promptIdentity(), policy.toolPolicyIdentity(), policy.contextPolicyIdentity(), policy.finalSelection(),
                List.of(new CoordinationRuntime.Participant("worker", Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(BundleInvestigationDecision.PROTOCOL))));
        assertThrows(IllegalArgumentException.class, () -> runtime().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), expanded));
        assertEquals(0, runtime().snapshot(id).getAsJsonObject("usage").get("reservedCalls").getAsInt());
    }
    private final class ScriptedBrokers implements CoordinationRuntime.Brokers {
        int registered, dispatched; boolean interruptAfterPersist, interruptRegistration; String remote = "RESPONSE_RECEIVED";
        BiFunction<JsonObject,Integer,String> script = (input, turn) -> finish("ABSTAINED", List.of()); final List<JsonObject> contexts = new ArrayList<>();
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered++;
                    if (interruptRegistration) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file)); assertTrue(permitted.getAsBoolean()); dispatched++;
                    var input = JsonParser.parseString(CaseInputs.object(Files.readAllBytes(store.resolve(id + "/request.json"))).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    var response = json(Map.of("callId", id, "status", remote.equals("RESPONSE_RECEIVED") ? "COMPLETED" : "UNKNOWN_REMOTE_OUTCOME", "remoteOutcome", remote,
                            "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, dispatched))); DurableExecution.publish(file, response);
                    if (interruptAfterPersist) { interruptAfterPersist = false; throw new IOException("Interrupted controller"); } return response;
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
