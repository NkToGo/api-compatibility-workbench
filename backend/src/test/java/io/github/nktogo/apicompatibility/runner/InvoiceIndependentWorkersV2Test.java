package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.net.*;
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

class InvoiceIndependentWorkersV2Test {
    @TempDir Path root;
    private Path inputs; private ContentDigest identity; private InvoiceComparisonInputs.Bundle bundle;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceInvestigationV2(root, inputs, identity, clock, scripts); }
    private InvoiceIndependentWorkersV2 adapter() throws IOException { return new InvoiceIndependentWorkersV2(root, bundle, runtime()); }
    private void inputs() throws Exception {
        if (inputs != null) return;
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("capture"), Duration.ofSeconds(30), () -> true);
        inputs = root.resolve("inputs"); bundle = InvoiceComparisonInputs.capture(plan, run, inputs); identity = bundle.identity();
    }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private String fixture(int calls, int inputBytes) throws Exception {
        inputs(); var base = InvoiceIndependentWorkersV2.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, inputBytes, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS));
    }
    private Path run(String id) { return root.resolve("invoice-independent-workers-v2/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static String worker(JsonObject input) { return input.getAsJsonObject("subject").get("participantId").getAsString(); }
    private static InvoiceReadiness.Scope scope(String worker) {
        return worker.equals("invoice-webhook") ? InvoiceReadiness.Scope.WEBHOOK : InvoiceReadiness.Scope.RECONCILIATION;
    }
    private static String protocol(String worker) { return InvoiceDecisionV2.protocol(scope(worker)); }
    private InvoiceReadiness.Subject subject(String worker) { return new InvoiceReadiness.Subject(worker, identity, scope(worker)); }
    private JsonObject bootstrap(String worker) {
        return json(Map.of("participant", worker, "operationId", "metadata", "caseId", InvoiceRenewalFixtures.CASE_ID,
                "bundleIdentity", identity.sha256(), "pageRequest", new ComparisonEvidence.PageRequest("describe", InvoiceRenewalFixtures.CASE_ID, "", 0, 0),
                "page", bundle.describe(), "contentTrust", "untrusted-data"));
    }
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
            assertEquals(protocol(idInContext), context.get("protocol").getAsString());
            assertEquals(InvoiceDecisionV2.taskIdentity(), context.getAsJsonObject("publicTask").get("sha256").getAsString());
            assertEquals(2, context.getAsJsonObject("scheduling").get("activeParticipants").getAsInt());
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
        var first = scripts.contexts.getFirst().getAsJsonObject("scheduling");
        assertEquals("PARTIAL_ROUND_UNASSIGNED", first.get("laterSchedulingIfPeersStayActive").getAsString());
        assertFalse(first.get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertEquals(2, first.get("currentRoundReservations").getAsInt());
        var last = scripts.contexts.getLast().getAsJsonObject("scheduling");
        assertEquals(1, last.get("sharedCallsRemainingBeforeRound").getAsInt()); assertEquals(2, last.get("activeParticipants").getAsInt());
        assertTrue(last.get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertFalse(last.get("futureTurnGuaranteed").getAsBoolean());
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString()); assertTrue(output(result).get("outputIdentity").isJsonNull());
    }
    @Test void preReservationContextFailureDoesNotStealTheSiblingsCallOpportunity() throws Exception {
        inputs();
        int bytes = InvoiceContextV2.build(subject("invoice-webhook"), bundle, bootstrap("invoice-webhook"), List.of(),
                new InvoiceContextV2.Scheduling(1, 1), 64000).getBytes(StandardCharsets.UTF_8).length;
        assertThrows(CoordinationRuntime.Stop.class, () -> InvoiceContextV2.build(subject("invoice-reconciliation"), bundle,
                bootstrap("invoice-reconciliation"), List.of(), new InvoiceContextV2.Scheduling(1, 2), bytes));
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
    @Test void answeredWithoutRequiredOwnReadsIsRejectedWithCandidateAndReadinessRetained() throws Exception {
        scripts.script = (input, turn) -> turn == 1 ? read("task") : finish(worker(input), "ANSWERED", List.of("read-1"));
        String id = fixture(6); var result = adapter().run(id);
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var event = record(run(id).resolve("event-2-" + worker + ".json"));
            assertEquals("REJECTED_FINISH", event.get("actionStatus").getAsString());
            assertEquals("ANSWERED", event.getAsJsonObject("decision").get("response").getAsString());
            assertFalse(event.getAsJsonObject("readiness").get("structuralAccessReady").getAsBoolean());
            assertEquals(worker, event.getAsJsonObject("readiness").getAsJsonObject("subject").get("participantId").getAsString());
            assertFalse(Files.exists(run(id).resolve(worker + ".json")));
        }
    }
    @Test void anAuthoredReadyDisclosureExercisesBothRoleSpecificAnsweredFinishGates() throws Exception {
        inputs();
        // This is an authored disclosure fixture for the structural gate, not observed execution coverage.
        var manifest = record(inputs.resolve("manifest.json")); manifest.addProperty("sourceExecutionCoverageSatisfied", true);
        byte[] bytes = CaseInputs.JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8); Files.write(inputs.resolve("manifest.json"), bytes);
        identity = new ContentDigest(CaseInputs.hash(bytes)); Files.writeString(inputs.resolve("receipt.json"), json(Map.of("manifestSha256", identity.sha256())).toString());
        bundle = InvoiceComparisonInputs.open(inputs, identity);
        scripts.script = (input, turn) -> {
            String worker = worker(input), role = InvoiceWorkerDecision.role(worker);
            String business = bundle.catalogPrefix("business/", 0, 40).getAsJsonArray("artifacts").asList().stream()
                    .map(v -> v.getAsJsonObject().get("alias").getAsString()).filter(a -> a.endsWith("/" + role)).findFirst().orElseThrow();
            String order = bundle.catalogPrefix("order/", 0, 1).getAsJsonArray("artifacts").get(0).getAsJsonObject().get("alias").getAsString();
            for (String alias : List.of("task", "build-configuration", InvoiceWorkerDecision.baseline(worker), business, order)) {
                int offset = input.getAsJsonArray("readMetadata").asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(p -> p.has("alias") && p.get("alias").getAsString().equals(alias))
                        .mapToInt(p -> p.get("nextOffset").getAsInt()).max().orElse(0);
                if (offset < bundle.read(alias, 0, 4000).get("totalCharacters").getAsInt())
                    return decision(Map.of("action", "read_text", "alias", alias, "offset", offset, "maxCharacters", 4000));
            }
            return finish(worker, "ANSWERED", List.of("read-1"));
        };
        String id = fixture(60); var result = adapter().run(id);
        assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        assertEquals("ANSWERED", output(result).get("response").getAsString()); assertTrue(output(result).get("review").isJsonNull());
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var dossier = record(run(id).resolve(worker + ".json")); var ready = dossier.getAsJsonObject("readiness");
            assertTrue(ready.get("structuralAccessReady").getAsBoolean());
            assertEquals(scope(worker).name(), ready.getAsJsonObject("subject").get("scope").getAsString());
            assertEquals(3, ready.getAsJsonArray("requiredReads").size()); assertEquals("ANSWERED", dossier.get("response").getAsString());
        }
        assertEquals(0, usage(result).get("caseExecutions").getAsInt()); assertEquals(result, adapter().run(id));
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
        String expired = fixture(4); runtime().start(expired); byte[] clockBefore = Files.readAllBytes(root.resolve("invoice-v2-trials/" + expired + "/started.json"));
        clock.now = clock.now.plusSeconds(1201); var result = adapter().run(expired); assertTrue(result.get("workers").toString().contains("TRIAL_EXPIRED"));
        assertEquals(result, adapter().run(expired)); assertArrayEquals(clockBefore, Files.readAllBytes(root.resolve("invoice-v2-trials/" + expired + "/started.json")));
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
        String id = fixture(4); var wrong = new ModelCallBroker.Request("gpt-5.6-terra", "input", 2048, protocol("invoice-webhook"));
        assertThrows(RuntimeException.class, () -> runtime().call(id, "invoice-reconciliation", "wrong-protocol", wrong));
        assertThrows(RuntimeException.class, () -> runtime().readPage(id, "invented-worker", "wrong-worker", InvoiceRenewalFixtures.CASE_ID, "task", 0, 4000));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.forInvoice(root, inputs, identity).register(identity.sha256(), InvoiceIndependentWorkersV2.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS), InvoiceIndependentWorkersV2.policy()));
        assertThrows(RuntimeException.class, () -> new InvoiceIndependentWorkersV2(root, bundle,
                CoordinationRuntime.forInvoiceInvestigation(root, inputs, identity, clock, scripts)).register(identity.sha256(),
                InvoiceIndependentWorkersV2.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS)));
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
        Files.delete(root.resolve("invoice-v2-trials/" + id + "/receipt.json")); assertThrows(RuntimeException.class, () -> adapter().run(id));
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
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), InvoiceIndependentWorkersV2.class.getName(), action,
                    root.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true).redirectOutput(root.resolve(action + ".log").toFile());
            builder.environment().remove("OPENAI_API_KEY"); var process = builder.start(); try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); } finally { process.destroyForcibly(); }
        }
        assertEquals(before, inventory()); assertEquals(result, adapter().retained(id)); assertEquals(2, scripts.dispatched.get());
    }
    @Test void policyIdentityIsStableInAFreshJvm() throws Exception {
        Path output = root.resolve("policy.json"); var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                InvoiceIndependentWorkersV2.class.getName(), "policy", root.toString()).redirectErrorStream(true).redirectOutput(output.toFile());
        var process = builder.start(); try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); } finally { process.destroyForcibly(); }
        assertEquals(CoordinationRuntime.implementationIdentity(InvoiceIndependentWorkersV2.policy()).sha256(), record(output).getAsJsonObject("implementationIdentity").get("sha256").getAsString());
    }
    @Test void prefixReadsAreOwnedNonCitableAndDoNotReceiveTheSiblingsReadiness() throws Exception {
        scripts.script = (input, turn) -> switch (turn) {
            case 1 -> decision(Map.of("action", "catalog_prefix", "prefix", "source/" + InvoiceWorkerDecision.role(worker(input)), "offset", 0, "limit", 40));
            case 2 -> read(InvoiceWorkerDecision.baseline(worker(input)));
            default -> finish(worker(input), "ABSTAINED", List.of("read-2"));
        };
        String id = fixture(6); var result = adapter().run(id);
        assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString()); assertEquals(6, usage(result).get("toolCalls").getAsInt());
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var event = record(run(id).resolve("event-1-" + worker + ".json"));
            assertEquals(worker, event.getAsJsonObject("page").get("participant").getAsString());
            assertEquals("source/" + InvoiceWorkerDecision.role(worker), event.getAsJsonObject("page").getAsJsonObject("pageRequest").get("alias").getAsString());
            var dossier = record(run(id).resolve(worker + ".json")); var readiness = dossier.getAsJsonObject("readiness");
            assertEquals(JsonParser.parseString("[\"read-1\"]"), readiness.get("nonCitableCataloguePageIds"));
            assertEquals(JsonParser.parseString("[\"read-2\"]"), readiness.get("citableTextPageIds"));
            assertEquals(protocol(worker), dossier.get("protocol").getAsString());
            assertEquals(InvoiceDecisionV2.taskIdentity(), dossier.get("publicTaskSha256").getAsString());
        }
        assertTrue(scripts.contexts.stream().filter(c -> c.getAsJsonArray("readMetadata").size() == 2)
                .allMatch(c -> c.getAsJsonObject("scheduling").get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean()));
    }
    @Test void prefixCatalogueCitationsStayRejectedAndKeepTheAuthoredCandidate() throws Exception {
        scripts.script = (input, turn) -> turn == 1 ? decision(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 40))
                : finish(worker(input), "ABSTAINED", List.of("read-1"));
        String id = fixture(4); var result = adapter().run(id);
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var event = record(run(id).resolve("event-2-" + worker + ".json"));
            assertEquals("REJECTED_FINISH", event.get("actionStatus").getAsString());
            assertEquals("ABSTAINED", event.getAsJsonObject("decision").get("response").getAsString());
            assertTrue(event.getAsJsonObject("readiness").getAsJsonArray("citableTextPageIds").isEmpty());
        }
        assertEquals(result, adapter().run(id)); assertEquals(4, scripts.dispatched.get());
    }
    @Test void aFinishedPeerChangesSchedulingWithoutCreatingAPrivateTurnAllowance() throws Exception {
        scripts.script = (input, turn) -> worker(input).equals("invoice-reconciliation") || turn == 3 ? finish(worker(input), "ABSTAINED", List.of()) : read("task");
        String id = fixture(4); var result = adapter().run(id);
        assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
        var contexts = scripts.contexts.stream().filter(c -> worker(c).equals("invoice-webhook")).toList();
        assertEquals(3, contexts.size()); assertEquals(2, contexts.getFirst().getAsJsonObject("scheduling").get("activeParticipants").getAsInt());
        assertEquals(1, contexts.getLast().getAsJsonObject("scheduling").get("activeParticipants").getAsInt());
        assertTrue(contexts.getLast().getAsJsonObject("scheduling").get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertEquals(4, usage(result).get("reservedCalls").getAsInt());
    }
    @Test void aChangedOwnedWrapperCannotBeUsedToResumeACapturedRequest() throws Exception {
        scripts.script = (input, turn) -> read("task"); scripts.interruptAfterPersist.set(true); String id = fixture(6);
        assertThrows(IOException.class, () -> adapter().run(id));
        var file = run(id).resolve("metadata-invoice-webhook.json"); var wrapper = record(file); wrapper.addProperty("participant", "invoice-reconciliation");
        Files.writeString(file, wrapper.toString());
        assertThrows(RuntimeException.class, () -> adapter().run(id)); assertEquals(2, scripts.dispatched.get());
    }
    @Test void wrongArmAndMismatchedInputRejectBeforeAnyModelRegistration() throws Exception {
        inputs(); var base = InvoiceIndependentWorkersV2.checkDefinition(identity);
        assertThrows(RuntimeException.class, () -> adapter().register(identity.sha256(), base, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT)));
        assertThrows(RuntimeException.class, () -> adapter().register(new ContentDigest("a".repeat(64)).sha256(), base, new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS)));
        assertEquals(0, scripts.registered.get());
    }
    @Test void actualBrokerRunsBothScopedV2ProtocolsAgainstLoopbackOnly() throws Exception {
        inputs(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var requests = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        server.createContext("/v1/responses", exchange -> {
            try {
                requests.incrementAndGet(); var body = CaseInputs.object(exchange.getRequestBody().readAllBytes());
                var context = JsonParser.parseString(body.get("input").getAsString()).getAsJsonObject(); String worker = worker(context);
                assertEquals(protocol(worker), context.get("protocol").getAsString());
                assertEquals(InvoiceDecisionV2.instructions(scope(worker)), body.get("instructions").getAsString());
                assertEquals(InvoiceDecisionV2.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString());
                String action = switch (context.getAsJsonArray("readMetadata").size()) {
                    case 0 -> decision(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 40));
                    case 1 -> read(InvoiceWorkerDecision.baseline(worker));
                    default -> finish(worker, "ABSTAINED", List.of("read-2"));
                };
                byte[] response = CaseInputs.JSON.toJson(Map.of("status", "completed", "usage", Map.of("input_tokens", 5, "output_tokens", 2),
                        "output", List.of(Map.of("type", "function_call", "name", InvoiceDecisionV2.FUNCTION, "arguments", action)))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); try (var output = exchange.getResponseBody()) { output.write(response); }
            } catch (Throwable e) { failure.compareAndSet(null, e); exchange.close(); }
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
            var host = CoordinationRuntime.forInvoiceInvestigationV2(root, inputs, identity, clock, actual);
            var adapter = new InvoiceIndependentWorkersV2(root, bundle, host); var base = InvoiceIndependentWorkersV2.checkDefinition(identity);
            var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(6, 1200000, 64000, 2048));
            String id = adapter.register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS));
            var result = adapter.run(id); assertNull(failure.get());
            assertEquals("COMPLETED", result.getAsJsonObject("trial").get("status").getAsString());
            assertEquals("ABSTAINED", output(result).get("response").getAsString()); assertTrue(output(result).get("review").isJsonNull());
            assertEquals(6, requests.get()); assertEquals(6, usage(result).get("reservedCalls").getAsInt());
            assertEquals(6, usage(result).get("toolCalls").getAsInt()); assertEquals(30, usage(result).get("inputTokens").getAsInt());
            assertEquals(0, usage(result).get("caseExecutions").getAsInt());
            assertEquals(result, adapter.run(id)); assertEquals(6, requests.get());
        } finally { server.stop(0); }
    }
    private Map<String, String> inventory() throws IOException {
        var hashes = new TreeMap<String, String>(); for (String folder : List.of("inputs", "invoice-v2-trials", "invoice-independent-workers-v2")) try (var files = Files.walk(root.resolve(folder))) {
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
                    var context = JsonParser.parseString(request.input()).getAsJsonObject();
                    assertEquals(protocol(worker(context)), request.protocol());
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
