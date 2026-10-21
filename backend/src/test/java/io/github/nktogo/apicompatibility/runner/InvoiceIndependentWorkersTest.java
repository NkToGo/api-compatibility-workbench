package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceIndependentWorkersTest {
    @TempDir Path root;
    private Path inputs; private ContentDigest identity;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceInvestigation(root, inputs, identity, clock, scripts); }
    private InvoiceIndependentWorkers adapter() throws IOException { return new InvoiceIndependentWorkers(root, runtime()); }
    private void inputs() throws Exception {
        if (inputs != null) return;
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("capture"), Duration.ofSeconds(30), () -> true);
        inputs = root.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
    }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int inputBytes) throws Exception {
        inputs(); var base = InvoiceIndependentWorkers.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, inputBytes, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS));
    }
    private Path run(String id) { return root.resolve("invoice-independent-workers/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static String worker(JsonObject input) { return input.getAsJsonObject("case").get("workerId").getAsString(); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String finish(String worker, String response, List<String> citations) {
        return decision(Map.of("action", "finish", "response", response, "summary", "claim-only-" + worker, "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "Recorded evidence remains advisory.", "citations", citations))));
    }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonObject("usage"); }
    private static JsonObject output(JsonObject result) { return result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject(); }

    @Test void overlappingContextsUseOneLedgerAndKeepSiblingClaimsAndPagesIsolated() throws Exception {
        scripts.barrier = new CyclicBarrier(2); scripts.script = (input, turn) -> switch (turn) {
            case 1 -> decision(Map.of("action", "catalog", "offset", 0, "limit", 40));
            case 2 -> read(InvoiceWorkerDecision.baseline(worker(input)));
            default -> finish(worker(input), "ABSTAINED", List.of("read-2"));
        };
        String id = fixture(60); var result = adapter().run(id);
        assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(2, scripts.maximum.get());
        assertEquals(6, scripts.dispatched.get()); assertEquals(6, usage(result).get("reservedCalls").getAsInt()); assertEquals(6, usage(result).get("toolCalls").getAsInt());
        assertEquals(0, usage(result).get("caseExecutions").getAsInt()); assertEquals("ABSTAINED", output(result).get("response").getAsString()); assertTrue(output(result).get("review").isJsonNull());
        for (var context : scripts.contexts) {
            String idInContext = worker(context), sibling = InvoiceWorkerDecision.WORKERS.stream().filter(w -> !w.equals(idInContext)).findFirst().orElseThrow();
            assertFalse(context.has("workers")); assertFalse(context.toString().contains("claim-only-" + sibling));
            for (var page : context.getAsJsonArray("readMetadata")) if (page.getAsJsonObject().has("alias")) assertEquals(InvoiceWorkerDecision.baseline(idInContext), page.getAsJsonObject().get("alias").getAsString());
        }
        var dossier = record(run(id).resolve("dossier.json")); assertEquals(2, dossier.getAsJsonArray("workers").size());
        for (var item : dossier.getAsJsonArray("workers")) {
            var row = item.getAsJsonObject(); String worker = row.get("workerId").getAsString();
            assertEquals(record(run(id).resolve(worker + ".json")), row.get("dossier"));
            assertEquals(CaseInputs.hash(Files.readAllBytes(run(id).resolve(worker + ".json"))), row.get("dossierSha256").getAsString());
        }
        assertEquals(result, adapter().run(id)); assertEquals(6, scripts.dispatched.get());
    }
    @Test void survivingWorkerCanUseAllRemainingSixtyCallOpportunities() throws Exception {
        scripts.script = (input, turn) -> worker(input).equals("invoice-reconciliation") || turn == 59 ? finish(worker(input), "ABSTAINED", List.of()) : read("task");
        String id = fixture(60); var result = adapter().run(id); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals(60, scripts.dispatched.get()); assertEquals(59, scripts.turns.get("invoice-webhook").get()); assertEquals(1, scripts.turns.get("invoice-reconciliation").get());
        assertEquals(60, usage(result).get("reservedCalls").getAsInt());
    }
    @Test void oddBudgetHasFixedRoundMembershipAndExhaustionDoesNotBecomeAbstention() throws Exception {
        scripts.script = (input, turn) -> read("task"); String id = fixture(3); var result = adapter().run(id);
        assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(3, scripts.dispatched.get());
        assertEquals(1, scripts.turns.get("invoice-reconciliation").get()); assertEquals(2, scripts.turns.get("invoice-webhook").get());
        assertEquals("invoice-webhook", record(run(id).resolve("round-2.json")).getAsJsonArray("workers").get(0).getAsString());
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString()); assertTrue(output(result).get("outputIdentity").isJsonNull());
    }
    @Test void preReservationContextFailureDoesNotStealTheSiblingsCallOpportunity() throws Exception {
        inputs(); var metadata = InvoiceComparisonInputs.open(inputs, identity).describe(); metadata.addProperty("workerId", "invoice-webhook"); metadata.addProperty("workerRole", "webhook");
        int bytes = InvoiceSingleAgent.context(metadata, List.of(), 1, 64000).getBytes(StandardCharsets.UTF_8).length;
        metadata.addProperty("workerId", "invoice-reconciliation"); metadata.addProperty("workerRole", "reconciliation");
        assertThrows(CoordinationRuntime.Stop.class, () -> InvoiceSingleAgent.context(metadata, List.of(), 1, bytes));
        String id = fixture(1, bytes); var result = adapter().run(id); assertEquals(1, scripts.dispatched.get()); assertEquals(1, usage(result).get("reservedCalls").getAsInt());
        assertEquals("invoice-webhook", worker(scripts.contexts.getFirst())); assertTrue(result.get("workers").toString().contains("CONTEXT_BUDGET_EXHAUSTED"));
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
    }
    @Test void invalidActionsAndMissingCitationsStopOnlyTheirWorkerAndPreserveTheCompletedSibling() throws Exception {
        scripts.script = (input, turn) -> worker(input).equals("invoice-reconciliation") ? decision(Map.of("action", "delegate")) : finish(worker(input), "ABSTAINED", List.of());
        String id = fixture(4); var result = adapter().run(id); assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString());
        assertTrue(result.get("workers").toString().contains("INVALID_MODEL_DECISION")); assertTrue(Files.exists(run(id).resolve("invoice-webhook.json")));
        assertEquals("NOT_PROVIDED", record(run(id).resolve("dossier.json")).get("response").getAsString()); assertEquals(2, scripts.dispatched.get());
    }
    @Test void arbitraryPathsAndCatalogCitationsCannotBecomeEvidence() throws Exception {
        scripts.script = (input, turn) -> worker(input).equals("invoice-reconciliation") ? read("../receipt.json")
                : turn == 1 ? decision(Map.of("action", "catalog", "offset", 0, "limit", 40)) : finish(worker(input), "ABSTAINED", List.of("read-1"));
        String id = fixture(6); var result = adapter().run(id); assertTrue(result.get("workers").toString().contains("INVALID_EVIDENCE_READ"));
        assertTrue(result.get("workers").toString().contains("UNSUPPORTED_DOSSIER")); assertEquals(3, usage(result).get("toolCalls").getAsInt());
    }
    @Test void aSiblingPageIdIsNotAValidCitationWhenThisWorkerNeverReadIt() throws Exception {
        scripts.script = (input, turn) -> worker(input).equals("invoice-reconciliation") ? read("task") : finish(worker(input), "ABSTAINED", List.of("read-1"));
        String id = fixture(3); var result = adapter().run(id); assertTrue(result.get("workers").toString().contains("UNSUPPORTED_DOSSIER")); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
    }
    @Test void answeredAccessFloorRequiresOwnSourceAndBusinessPagesPlusTheSharedOrderEvidence() {
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var pages = new ArrayList<JsonObject>(); int i = 0;
            for (String alias : List.of("task", "build-configuration", InvoiceWorkerDecision.baseline(worker), "business/example/example/" + InvoiceWorkerDecision.role(worker), "order/example/example/arrival-order"))
                pages.add(json(Map.of("kind", "read_text", "pageId", "read-" + ++i, "alias", alias, "offset", 0, "nextOffset", 3, "totalCharacters", 3)));
            var decision = InvoiceInvestigationDecision.parse(finish(worker, "ANSWERED", List.of("read-1"))); var metadata = json(Map.of("sourceExecutionCoverageSatisfied", true));
            assertTrue(InvoiceWorkerDecision.supported(worker, decision, pages, metadata));
            assertFalse(InvoiceWorkerDecision.supported(worker, decision, pages, json(Map.of("sourceExecutionCoverageSatisfied", false))));
            pages.get(2).addProperty("totalCharacters", 4); assertFalse(InvoiceWorkerDecision.supported(worker, decision, pages, metadata)); pages.get(2).addProperty("totalCharacters", 3);
            pages.get(3).addProperty("alias", "business/example/example/other-role"); assertFalse(InvoiceWorkerDecision.supported(worker, decision, pages, metadata));
        }
    }
    @Test void completedResponseAndSealedSiblingEventSurviveInterruptionWithoutRedispatch() throws Exception {
        scripts.interruptAfterPersist.set(true); String id = fixture(6); assertThrows(IOException.class, () -> adapter().run(id));
        assertEquals(2, scripts.dispatched.get()); assertEquals(0, scripts.concurrent.get());
        var result = adapter().run(id); assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(2, scripts.dispatched.get()); assertEquals(2, scripts.registered.get());
    }
    @Test void unmappedRegistrationKeepsItsUncertainSlotAndDoesNotRepeatTransport() throws Exception {
        scripts.interruptRegistration.set(true); String id = fixture(4); assertThrows(IOException.class, () -> adapter().run(id));
        var result = adapter().run(id); assertEquals(2, scripts.registered.get()); assertEquals(1, scripts.dispatched.get());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
        assertTrue(result.get("workers").toString().contains("CALL_REGISTRATION_UNCERTAIN"));
    }
    @Test void unknownRemoteOutcomePreservesTheCompletedSiblingWithoutAFalseJointAnswer() throws Exception {
        scripts.unknownWorker = "invoice-reconciliation"; String id = fixture(4); var result = adapter().run(id);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
        assertTrue(Files.exists(run(id).resolve("invoice-webhook.json"))); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
    }
    @Test void expiryAndRevocationStopBeforeModelWorkAndCannotRenewTheClock() throws Exception {
        String expired = fixture(4); runtime().start(expired); byte[] clockBefore = Files.readAllBytes(root.resolve("invoice-comparison-trials/" + expired + "/started.json"));
        clock.now = clock.now.plusSeconds(1201); var result = adapter().run(expired); assertTrue(result.get("workers").toString().contains("TRIAL_EXPIRED"));
        assertEquals(result, adapter().run(expired)); assertArrayEquals(clockBefore, Files.readAllBytes(root.resolve("invoice-comparison-trials/" + expired + "/started.json")));
        String revoked = fixture(4); runtime().revoke(revoked); assertEquals("CANCELLED", adapter().run(revoked).getAsJsonObject("trial").get("status").getAsString()); assertEquals(0, scripts.registered.get());
    }
    @Test void expiryAfterOneCompletionRetainsThatDossierAndTheUnfinishedSibling() throws Exception {
        scripts.script = (input, turn) -> {
            if (worker(input).equals("invoice-reconciliation")) return finish(worker(input), "ABSTAINED", List.of());
            if (turn == 2) clock.now = clock.now.plusSeconds(1201); return read("task");
        };
        String id = fixture(6); var result = adapter().run(id); assertEquals("FAILED", result.getAsJsonObject("trial").get("status").getAsString());
        assertTrue(Files.exists(run(id).resolve("invoice-reconciliation.json"))); assertFalse(Files.exists(run(id).resolve("invoice-webhook.json"))); assertTrue(result.get("workers").toString().contains("TRIAL_EXPIRED"));
    }
    @Test void participantProtocolAndReadOnlyFactoryCannotExpandAccess() throws Exception {
        String id = fixture(4); var wrong = new ModelCallBroker.Request("gpt-test", "input", 2048, InvoiceWorkerDecision.WEBHOOK);
        assertThrows(RuntimeException.class, () -> runtime().call(id, "invoice-reconciliation", "wrong-protocol", wrong));
        assertThrows(RuntimeException.class, () -> runtime().readPage(id, "invented-worker", "wrong-worker", InvoiceRenewalFixtures.CASE_ID, "task", 0, 4000));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.forInvoice(root, inputs, identity).register(identity.sha256(), InvoiceIndependentWorkers.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS), InvoiceIndependentWorkers.policy()));
        assertEquals(0, scripts.registered.get());
    }
    @Test void sealedEventAndResumedRequestChangesAreRejectedBeforeRedispatch() throws Exception {
        scripts.interruptAfterPersist.set(true); String id = fixture(4); assertThrows(IOException.class, () -> adapter().run(id));
        Path event;
        try (var files = Files.list(run(id))) { event = files.filter(p -> p.getFileName().toString().matches("event-.*-receipt.json")).findFirst().orElseThrow(); }
        var eventPath = event.resolveSibling(event.getFileName().toString().replace("-receipt.json", ".json")); byte[] original = Files.readAllBytes(eventPath); Files.writeString(eventPath, "{}");
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(2, scripts.dispatched.get()); Files.write(eventPath, original);
        try (var files = Files.list(run(id))) { Files.writeString(files.filter(p -> p.getFileName().toString().startsWith("request-")).findFirst().orElseThrow(), "{}"); }
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(2, scripts.dispatched.get());
    }
    @Test void finalReceiptRecoveryAndSelectedOutputTamperingPreserveIntegrityBoundaries() throws Exception {
        String id = fixture(4); var result = adapter().run(id); Files.delete(run(id).resolve("receipt.json"));
        assertThrows(RuntimeException.class, () -> adapter().retained(id)); assertEquals(result, adapter().run(id));
        var path = run(id).resolve("invoice-webhook.json"); byte[] original = Files.readAllBytes(path); Files.writeString(path, "{}");
        assertThrows(RuntimeException.class, () -> adapter().retained(id)); Files.write(path, original);
        Files.delete(root.resolve("invoice-comparison-trials/" + id + "/receipt.json")); assertThrows(RuntimeException.class, () -> adapter().run(id));
    }
    @Test void exclusiveOwnershipReturnsActiveWithoutStartingWorkerOperations() throws Exception {
        String id = fixture(4); try (var lease = LocalFileLease.acquire(run(id).resolve("owner.lock"))) {
            assertNotNull(lease); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); assertEquals(0, scripts.registered.get());
        }
        assertEquals("COMPLETED", adapter().run(id).getAsJsonObject("trial").get("status").getAsString());
    }
    @Test void changedInputCannotReopenAnUnfinishedTrial() throws Exception {
        String id = fixture(4); Files.writeString(inputs.resolve("0001.txt"), "changed"); assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(0, scripts.registered.get());
    }
    @Test void freshJvmInspectionAndCompletedResumeNeedNoCredentialOrNewOperations() throws Exception {
        String id = fixture(4); var result = adapter().run(id); var before = inventory();
        for (String action : List.of("read", "run")) {
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), InvoiceIndependentWorkers.class.getName(), action,
                    root.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true).redirectOutput(root.resolve(action + ".log").toFile());
            builder.environment().remove("OPENAI_API_KEY"); var process = builder.start(); try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); } finally { process.destroyForcibly(); }
        }
        assertEquals(before, inventory()); assertEquals(result, adapter().retained(id)); assertEquals(2, scripts.dispatched.get());
    }
    @Test void policyIdentityIsStableInAFreshJvm() throws Exception {
        Path output = root.resolve("policy.json"); var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                InvoiceIndependentWorkers.class.getName(), "policy", root.toString()).redirectErrorStream(true).redirectOutput(output.toFile());
        var process = builder.start(); try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); } finally { process.destroyForcibly(); }
        assertEquals(CoordinationRuntime.implementationIdentity(InvoiceIndependentWorkers.policy()).sha256(), record(output).getAsJsonObject("implementationIdentity").get("sha256").getAsString());
    }
    private Map<String, String> inventory() throws IOException {
        var hashes = new TreeMap<String, String>(); for (String folder : List.of("inputs", "invoice-comparison-trials", "invoice-independent-workers")) try (var files = Files.walk(root.resolve(folder))) {
            for (var file : files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) hashes.put(root.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file)));
        } return hashes;
    }
    private final class Scripts implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrent = new AtomicInteger(), maximum = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> script = (input, turn) -> finish(worker(input), "ABSTAINED", List.of()); String unknownWorker; CyclicBarrier barrier;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return record(file); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(record(store.resolve(id + "/request.json")).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String worker = worker(input); int turn = turns.computeIfAbsent(worker, ignored -> new AtomicInteger()).incrementAndGet(); int active = concurrent.incrementAndGet(); maximum.accumulateAndGet(active, Math::max);
                    try {
                        if (barrier != null && turn == 1) try { barrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        boolean unknown = worker.equals(unknownWorker); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                                "inputTokens", 5, "outputTokens", 2, "outputText", script.apply(input, turn))); DurableExecution.publish(file, response);
                        if (interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { concurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return record(store.resolve(id + "/result.json")); }
            };
        }
    }
}
