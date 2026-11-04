package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceFollowUpRunnerV2Test {
    @TempDir Path root;
    @TempDir static Path captureRoot;
    private static Path inputs; private static ContentDigest identity;
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), captureRoot.resolve("capture"), Duration.ofSeconds(30), () -> true);
        inputs = captureRoot.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
    }
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Scripts scripts = new Scripts();
    private CoordinationRuntime runtime() throws IOException { return CoordinationRuntime.forInvoiceFollowUpV2(root, inputs, identity, clock, scripts); }
    private InvoiceFollowUpRunnerV2 adapter() throws IOException { return new InvoiceFollowUpRunnerV2(root, InvoiceComparisonInputs.open(inputs, identity), runtime()); }
    private String fixture(int calls) throws Exception { return fixture(calls, 64000); }
    private JsonObject execute(int calls) throws Exception { return execute(calls, 64000); }
    private JsonObject execute(int calls, int bytes) throws Exception { String id = fixture(calls, bytes); return adapter().run(id); }
    private String fixture(int calls, int bytes) throws Exception {
        var base = InvoiceFollowUpRunnerV2.checkDefinition(identity);
        var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(calls, 1200000, bytes, 2048));
        return adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM));
    }
    private Path run(String id) { return root.resolve("invoice-follow-up-v2-runs/" + id); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(Path file) throws IOException { return CaseInputs.object(Files.readAllBytes(file)); }
    private static String decision(Object value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static boolean fresh(JsonObject input) { return input.has("followUp"); }
    private static boolean review(JsonObject input) { return input.has("workers"); }
    private static String participant(JsonObject input) { return review(input) ? "invoice-coordinator" : input.getAsJsonObject("subject").get("participantId").getAsString(); }
    private static String read(String alias) { return decision(Map.of("action", "read_text", "alias", alias, "offset", 0, "maxCharacters", 4000)); }
    private static String original(JsonObject input, List<String> citations) {
        return decision(Map.of("action", "finish", "response", "ABSTAINED", "summary", "original-claim-" + participant(input), "summaryCitations", citations,
                "findings", citations.isEmpty() ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "The captured evidence does not establish release approval.", "citations", citations))));
    }
    private static String finishReview(JsonObject input, boolean questions) {
        var proposals = new ArrayList<Object>();
        if (questions) for (var value : input.getAsJsonArray("workers")) {
            var w = value.getAsJsonObject(); proposals.add(Map.of("workerId", w.get("workerId").getAsString(), "question", "Which recorded ledger effects remain unexplained?", "citations", List.of(w.get("referenceId").getAsString())));
        }
        return decision(Map.of("action", "finish", "summary", "Original outcomes remain advisory.", "citations", List.of("worker-1", "worker-2"), "followUps", proposals, "conflicts", List.of()));
    }
    private static JsonObject usage(JsonObject result) { return result.getAsJsonObject("stageTrace").getAsJsonObject("usage"); }


    private static final String WEBHOOK = "fresh-invoice-webhook", RECON = "fresh-invoice-reconciliation";
    private Path parent(String id) { return root.resolve("invoice-team-follow-up-v2-trials/" + id); }
    private Path prefix(String id) { return root.resolve("invoice-team-follow-up-v2-prefix-runs/" + id); }
    private InvoiceTeamRunnerV2 prefixAdapter() throws IOException { return InvoiceTeamRunnerV2.forFollowUps(root, InvoiceComparisonInputs.open(inputs, identity), runtime()); }
    private static JsonArray freshWorkers(JsonObject result) { return result.getAsJsonObject("fresh").getAsJsonArray("workers"); }
    private static JsonObject outcome(JsonObject result, String id) { return freshWorkers(result).asList().stream().map(JsonElement::getAsJsonObject).filter(v -> v.get("participantId").getAsString().equals(id)).findFirst().orElseThrow(); }
    private static String oneQuestion(JsonObject input) {
        var value = JsonParser.parseString(finishReview(input, true)).getAsJsonObject(); value.getAsJsonObject("decision").getAsJsonArray("followUps").remove(0); return value.toString();
    }
    @Test void selectedFreshRolesUseOwnContextsOnePoolAndASealedSourcePrefix() throws Exception {
        scripts.barrier = new CyclicBarrier(2);
        scripts.originalScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        scripts.reviewScript = (input, turn) -> turn == 1 ? read("task") : finishReview(input, true);
        scripts.freshScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        String id = fixture(60); var result = adapter().run(id);
        assertEquals("FOLLOW_UP_RECORDED", result.get("status").getAsString()); assertEquals("PENDING", result.get("wholeTrialStatus").getAsString()); assertFalse(result.has("trial"));
        assertEquals(10, scripts.dispatched.get()); assertEquals(2, scripts.maximum.get()); assertEquals(10, usage(result).get("reservedCalls").getAsInt()); assertEquals(10, usage(result).get("toolCalls").getAsInt());
        assertEquals(4, result.getAsJsonObject("stageReservations").get("followUp").getAsInt()); assertFalse(Files.exists(parent(id).resolve("result.json")));
        assertEquals(0, usage(result).get("caseExecutions").getAsInt());
        for (String worker : List.of(WEBHOOK, RECON)) {
            var value = outcome(result, worker); assertEquals("COMPLETED", value.get("status").getAsString());
            assertEquals("NOT_PERFORMED", value.getAsJsonObject("accepted").get("qualityReview").getAsString());
            assertEquals(worker, value.getAsJsonObject("bootstrap").get("participant").getAsString());
        }
        for (var context : scripts.contexts) if (fresh(context)) {
            String own = participant(context); assertEquals(own, context.getAsJsonObject("followUp").get("participantId").getAsString());
            assertEquals(1, context.getAsJsonObject("followUp").getAsJsonArray("questionAnchors").size()); assertFalse(context.has("workers")); assertFalse(context.has("originalClaims"));
            assertTrue(context.getAsJsonArray("readMetadata").size() <= 1);
            for (var page : outcome(result, own).getAsJsonArray("reads")) assertEquals(own, page.getAsJsonObject().get("participant").getAsString());
            assertFalse(context.toString().contains("original-claim-invoice-"));
        }
        assertEquals(result, adapter().run(id)); assertEquals(10, scripts.dispatched.get());
    }
    @Test void oneOrZeroSelectedQuestionsNeverBootstrapTheOtherRole() throws Exception {
        scripts.reviewScript = (input, turn) -> oneQuestion(input); String id = fixture(60); var result = adapter().run(id);
        assertEquals(1, freshWorkers(result).size()); assertEquals(WEBHOOK, freshWorkers(result).get(0).getAsJsonObject().get("participantId").getAsString());
        assertFalse(Files.exists(run(id).resolve("metadata-" + RECON + ".json"))); assertEquals(4, scripts.dispatched.get());
        scripts.reviewScript = (input, turn) -> finishReview(input, false); String empty = fixture(60); var skipped = adapter().run(empty);
        assertEquals("FOLLOW_UP_SKIPPED", skipped.get("status").getAsString()); assertEquals(0, freshWorkers(skipped).size()); assertFalse(skipped.getAsJsonObject("selection").get("selection").isJsonNull());
        assertEquals(3, usage(skipped).get("reservedCalls").getAsInt()); assertEquals(3, usage(skipped).get("toolCalls").getAsInt());
    }
    @Test void unavailableReviewSelectionKeepsTheFailureAndDispatchesNoFreshWork() throws Exception {
        scripts.unknownParticipant = "invoice-coordinator"; var result = execute(60);
        assertEquals("FOLLOW_UP_SKIPPED", result.get("status").getAsString()); assertTrue(result.getAsJsonObject("selection").get("selection").isJsonNull());
        assertEquals(0, freshWorkers(result).size()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt()); assertEquals(3, scripts.dispatched.get());
        assertTrue(result.getAsJsonObject("originalsAndReview").toString().contains("MODEL_UNKNOWN_REMOTE_OUTCOME"));
    }
    @Test void forwardCarryStopsAtFiftyFourWithoutPrivateWorkerCapsOrFreeFinishes() throws Exception {
        scripts.freshScript = (input, turn) -> read("task"); var result = execute(60);
        assertEquals(2, result.getAsJsonObject("stageReservations").get("investigation").getAsInt()); assertEquals(1, result.getAsJsonObject("stageReservations").get("review").getAsInt());
        assertEquals(51, result.getAsJsonObject("stageReservations").get("followUp").getAsInt()); assertEquals(54, usage(result).get("reservedCalls").getAsInt());
        for (var value : freshWorkers(result)) { assertEquals("STAGE_CALL_BUDGET_EXHAUSTED", value.getAsJsonObject().get("stopReason").getAsString()); assertTrue(value.getAsJsonObject().get("accepted").isJsonNull()); }
        assertEquals(0, result.getAsJsonObject("stageReservations").get("reconciliation").getAsInt());
    }
    @Test void oddFreshOpportunityFollowsRoundParityAndTheSharedStageSnapshot() throws Exception {
        scripts.freshScript = (input, turn) -> read("task"); var result = execute(9);
        assertEquals(3, scripts.turns.get(RECON).get()); assertEquals(2, scripts.turns.get(WEBHOOK).get()); assertEquals(8, usage(result).get("reservedCalls").getAsInt());
        for (var context : scripts.contexts) if (fresh(context)) assertEquals("FOLLOW_UP", context.getAsJsonObject("teamStage").get("stage").getAsString());
    }
    @Test void exhaustedSharedReadsCanBlockOneFreshBootstrapWithoutChargingACall() throws Exception {
        String id = fixture(60); for (int i = 0; i < 96; i++) runtime().describe(id, "invoice-coordinator", "earlier-" + i, InvoiceRenewalFixtures.CASE_ID);
        var result = adapter().run(id); assertEquals(100, usage(result).get("toolCalls").getAsInt()); assertEquals(4, usage(result).get("reservedCalls").getAsInt());
        assertEquals(1, freshWorkers(result).asList().stream().filter(v -> v.getAsJsonObject().get("stopReason").getAsString().equals("READ_BUDGET_EXHAUSTED")).count());
        assertEquals(result, adapter().retained(id));
    }
    @Test void uncertainFreshRegistrationStaysChargedWithoutRedispatch() throws Exception {
        scripts.interruptRegistrationParticipant = WEBHOOK; scripts.interruptRegistration.set(true); String id = fixture(60);
        assertThrows(IOException.class, () -> adapter().run(id)); var result = adapter().run(id);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", outcome(result, WEBHOOK).get("status").getAsString()); assertEquals("CALL_REGISTRATION_UNCERTAIN", outcome(result, WEBHOOK).get("stopReason").getAsString());
        assertEquals(5, scripts.registered.get()); assertEquals(4, scripts.dispatched.get()); assertEquals(5, usage(result).get("reservedCalls").getAsInt()); assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt());
        assertEquals(result, adapter().run(id)); assertEquals(4, scripts.dispatched.get());
    }
    @Test void unknownFreshResponseNeverBecomesAnAcceptedAnswer() throws Exception {
        scripts.unknownParticipant = WEBHOOK; String id = fixture(60); var result = adapter().run(id);
        assertEquals("UNKNOWN_REMOTE_OUTCOME", outcome(result, WEBHOOK).get("status").getAsString()); assertTrue(outcome(result, WEBHOOK).get("accepted").isJsonNull());
        assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt()); assertEquals(result, adapter().run(id)); assertEquals(5, scripts.dispatched.get());
    }
    @Test void completedFreshResponseSurvivesControllerInterruption() throws Exception {
        scripts.interruptParticipant = WEBHOOK; scripts.interruptAfterPersist.set(true); String id = fixture(60); assertThrows(IOException.class, () -> adapter().run(id));
        assertEquals(5, scripts.dispatched.get()); var result = adapter().run(id); assertEquals("COMPLETED", outcome(result, WEBHOOK).get("status").getAsString());
        assertEquals(5, scripts.dispatched.get()); assertEquals(result, adapter().run(id));
    }
    @Test void localFreshFailureDrainsItsSiblingBeforeReleasingOwnership() throws Exception {
        scripts.blockWorker = WEBHOOK; scripts.entered = new CountDownLatch(1); scripts.release = new CountDownLatch(1); scripts.interruptParticipant = RECON; scripts.interruptAfterPersist.set(true); String id = fixture(60);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> adapter().run(id));
            try { assertTrue(scripts.entered.await(30, TimeUnit.SECONDS)); assertFalse(task.isDone()); assertNull(LocalFileLease.tryAcquire(run(id).resolve("owner.lock"))); }
            finally { scripts.release.countDown(); }
            assertThrows(ExecutionException.class, () -> task.get(30, TimeUnit.SECONDS));
        }
        assertEquals(0, scripts.concurrent.get()); assertEquals("FOLLOW_UP_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(5, scripts.dispatched.get());
    }
    @Test void inheritedCitationsAndUnsupportedAnswersRetainRejectedCandidates() throws Exception {
        scripts.originalScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        scripts.freshScript = (input, turn) -> original(input, List.of("read-1")); String id = fixture(60); var result = adapter().run(id);
        for (String participant : List.of(WEBHOOK, RECON)) {
            assertEquals("UNSUPPORTED_DOSSIER", outcome(result, participant).get("stopReason").getAsString());
            var event = record(run(id).resolve("event-1-" + participant + ".json")); assertEquals("REJECTED_FINISH", event.get("actionStatus").getAsString()); assertTrue(event.has("decision"));
            assertEquals(0, event.getAsJsonObject("readiness").getAsJsonArray("citableTextPageIds").size());
        }
    }
    @Test void delegationAndInvalidEvidenceReadsCannotExpandTheDeclaredTools() throws Exception {
        scripts.freshScript = (input, turn) -> participant(input).equals(WEBHOOK) ? decision(Map.of("action", "delegate", "permission", "execute")) : read("../.env.local");
        var result = execute(60); assertEquals("INVALID_MODEL_DECISION", outcome(result, WEBHOOK).get("stopReason").getAsString()); assertEquals("INVALID_EVIDENCE_READ", outcome(result, RECON).get("stopReason").getAsString());
        assertEquals(5, scripts.dispatched.get()); assertEquals(5, usage(result).get("toolCalls").getAsInt());
    }
    @Test void expiryAndRevocationDuringFreshWorkPreserveStoppedOutcomes() throws Exception {
        scripts.reviewScript = (input, turn) -> oneQuestion(input); String expired = fixture(60); scripts.afterFresh = () -> clock.now = clock.now.plusMillis(1200000);
        var result = adapter().run(expired); assertEquals("TRIAL_EXPIRED", outcome(result, WEBHOOK).get("stopReason").getAsString()); assertTrue(outcome(result, WEBHOOK).get("accepted").isJsonNull());
        scripts.fired.set(false); String revoked = fixture(60); scripts.afterFresh = () -> assertDoesNotThrow(() -> runtime().revoke(revoked));
        var cancelled = adapter().run(revoked); assertEquals("CANCELLED", outcome(cancelled, WEBHOOK).get("status").getAsString()); assertEquals("TRIAL_REVOKED", outcome(cancelled, WEBHOOK).get("stopReason").getAsString());
        assertEquals(cancelled, adapter().run(revoked)); assertEquals(8, scripts.dispatched.get());
    }
    @Test void missingSealedFreshEventsAreNeverRecreatedByParticipantOperations() throws Exception {
        String id = fixture(60); adapter().run(id); int calls = scripts.dispatched.get(); Files.delete(run(id).resolve("result.json")); Files.delete(run(id).resolve("receipt.json")); Files.delete(run(id).resolve("event-1-" + WEBHOOK + "-receipt.json"));
        assertThrows(IllegalArgumentException.class, () -> adapter().run(id)); assertEquals(calls, scripts.dispatched.get()); assertFalse(Files.exists(run(id).resolve("event-1-" + WEBHOOK + "-receipt.json")));
    }
    @Test void missingAuthenticatedPrefixSealsOrSelectionCannotTriggerNewWork() throws Exception {
        String id = fixture(60); adapter().run(id); int calls = scripts.dispatched.get(); Files.delete(run(id).resolve("result.json")); Files.delete(run(id).resolve("receipt.json")); Files.delete(prefix(id).resolve("event-1-invoice-webhook-receipt.json"));
        assertThrows(IllegalArgumentException.class, () -> adapter().run(id)); assertEquals(calls, scripts.dispatched.get());
        String other = fixture(60); adapter().run(other); int after = scripts.dispatched.get(); Files.delete(run(other).resolve("result.json")); Files.delete(run(other).resolve("receipt.json")); Files.delete(run(other).resolve("selection.json"));
        assertThrows(IllegalArgumentException.class, () -> adapter().run(other)); assertEquals(after, scripts.dispatched.get());
    }
    @Test void alteredSelectionPagesAndRoundBindingsAreNotAcceptedAsOriginal() throws Exception {
        scripts.freshScript = (input, turn) -> turn == 1 ? read("task") : original(input, List.of("read-1"));
        for (String file : List.of("selection.json", "metadata-" + WEBHOOK + ".json", "fresh-round-1.json", "event-1-" + WEBHOOK + ".json")) {
            String id = fixture(60); adapter().run(id); int calls = scripts.dispatched.get(); var value = record(run(id).resolve(file)); value.addProperty("altered", true); Files.writeString(run(id).resolve(file), value.toString());
            assertThrows(IllegalArgumentException.class, () -> adapter().retained(id)); assertEquals(calls, scripts.dispatched.get());
        }
    }
    @Test void missingResultReceiptRecoversWithoutChangingOtherEvidence() throws Exception {
        String id = fixture(60); var result = adapter().run(id); Files.delete(run(id).resolve("receipt.json"));
        assertEquals(result, adapter().run(id)); assertEquals(5, scripts.dispatched.get());
    }
    @Test void externalReservationsCannotBeHiddenBeforeFreshDispatch() throws Exception {
        String id = fixture(60); prefixAdapter().run(id); var request = new ModelCallBroker.Request(InvoiceFollowUpRunnerV2.checkDefinition(identity).model(), "{\"workers\":[]}", 2048, InvoiceTeamDecisionV2.PROTOCOL);
        runtime().call(id, "invoice-coordinator", "outside-prefix", request); int before = scripts.dispatched.get();
        assertThrows(IllegalArgumentException.class, () -> adapter().run(id)); assertEquals(before, scripts.dispatched.get()); assertFalse(Files.exists(run(id).resolve("result.json")));
    }
    @Test void occupiedOwnerAndModelLanesPreserveUnreservedFreshOperations() throws Exception {
        String id = fixture(60); prefixAdapter().run(id); try (var lease = LocalFileLease.acquire(run(id).resolve("owner.lock"))) { assertNotNull(lease); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); }
        try (var first = LocalFileLease.acquire(parent(id).resolve("lane-1.lock")); var second = LocalFileLease.acquire(parent(id).resolve("lane-2.lock"))) {
            assertNotNull(first); assertNotNull(second); assertEquals("ACTIVE", adapter().run(id).get("status").getAsString()); assertEquals(3, scripts.dispatched.get());
        }
        assertEquals("FOLLOW_UP_RECORDED", adapter().run(id).get("status").getAsString()); assertEquals(5, scripts.dispatched.get());
    }
    @Test void freshJvmCompletedInspectionNeedsNoCredentialOrWrites() throws Exception {
        String id = fixture(60); var result = adapter().run(id); var before = inventory();
        for (String action : List.of("policy", "read", "run")) {
            var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), InvoiceFollowUpRunnerV2.class.getName(), action, root.toString()));
            if (!action.equals("policy")) args.addAll(List.of(inputs.toString(), identity.sha256(), id)); var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(root.resolve(action + ".log").toFile()); builder.environment().remove("OPENAI_API_KEY");
            var process = builder.start(); try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); } finally { process.destroyForcibly(); }
        }
        assertEquals(before, inventory()); assertEquals(result, adapter().retained(id)); assertEquals(5, scripts.dispatched.get());
    }

    @Test void fixedMembershipDoesNotUpgradePrefixOnlyRegistrations() throws Exception {
        assertEquals(5, InvoiceFollowUpRunnerV2.policy().participants().size()); assertEquals(3, InvoiceTeamRunnerV2.policy().participants().size());
        var oldRuntime = CoordinationRuntime.forInvoiceTeamReviewV2(root, inputs, identity, clock, scripts);
        var old = new InvoiceTeamRunnerV2(root, InvoiceComparisonInputs.open(inputs, identity), oldRuntime); var oldPlan = InvoiceTeamRunnerV2.checkDefinition(identity);
        String id = old.register(identity.sha256(), oldPlan, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM));
        var current = adapter(); assertThrows(IllegalArgumentException.class, () -> current.run(id));
        for (Path directory : List.of(run(id), prefix(id), parent(id))) Files.createDirectories(directory);
        for (String folder : List.of("reads", "calls", "broker")) Files.createDirectory(parent(id).resolve(folder));
        Path oldPrefix = root.resolve("invoice-team-v2-runs/" + id), oldParent = root.resolve("invoice-team-v2-trials/" + id);
        for (String file : List.of("spec.json", "registration.json")) { Files.copy(oldPrefix.resolve(file), run(id).resolve(file)); Files.copy(oldPrefix.resolve(file), prefix(id).resolve(file)); }
        for (String file : List.of("spec.json", "grant.json")) Files.copy(oldParent.resolve(file), parent(id).resolve(file));
        assertThrows(IllegalArgumentException.class, () -> current.run(id)); assertEquals(0, scripts.registered.get());
        assertFalse(Files.exists(oldParent.resolve("started.json")));
    }
    @Test void changedCaptureCannotStartNewParticipantWork() throws Exception {
        Path changed = root.resolve("changed-inputs"); copyInputs(changed); Files.writeString(changed.resolve("0001.txt"), "changed capture");
        assertThrows(RuntimeException.class, () -> new InvoiceFollowUpRunnerV2(root, changed, identity)); assertEquals(0, scripts.registered.get());
    }
    private static void copyInputs(Path destination) throws IOException {
        try (var paths = Files.walk(inputs)) { for (var file : paths.toList()) {
            Path target = destination.resolve(inputs.relativize(file)); if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
        } }
    }
    @Test void authoredReadyReadsSupportAnsweredFinishesWhileOriginalUncertaintyRemains() throws Exception {
        Path ready = root.resolve("authored-ready-inputs"); copyInputs(ready);
        // Authored structural gate control, not newly observed consumer execution or a measured model result.
        var manifest = record(ready.resolve("manifest.json")); manifest.addProperty("sourceExecutionCoverageSatisfied", true);
        byte[] bytes = CaseInputs.JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8); Files.write(ready.resolve("manifest.json"), bytes);
        var readyIdentity = new ContentDigest(CaseInputs.hash(bytes)); Files.writeString(ready.resolve("receipt.json"), json(Map.of("manifestSha256", readyIdentity.sha256())).toString());
        var bundle = InvoiceComparisonInputs.open(ready, readyIdentity); var actions = new TreeMap<String, List<String>>();
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            String role = InvoiceWorkerDecision.role(worker);
            String business = bundle.catalogPrefix("business/", 0, 40).getAsJsonArray("artifacts").asList().stream().map(v -> v.getAsJsonObject().get("alias").getAsString()).filter(a -> a.endsWith("/" + role)).findFirst().orElseThrow();
            String order = bundle.catalogPrefix("order/", 0, 1).getAsJsonArray("artifacts").get(0).getAsJsonObject().get("alias").getAsString();
            var list = new ArrayList<String>();
            for (String alias : List.of("task", "build-configuration", InvoiceWorkerDecision.baseline(worker), business, order)) {
                int offset = 0; JsonObject page;
                do { page = bundle.read(alias, offset, 4000); list.add(decision(Map.of("action", "read_text", "alias", alias, "offset", offset, "maxCharacters", 4000))); offset = page.get("nextOffset").getAsInt(); }
                while (offset < page.get("totalCharacters").getAsInt());
            }
            actions.put(InvoiceFollowUpDecisionV2.participant(worker), list);
        }
        scripts.unknownParticipant = "invoice-webhook";
        scripts.freshScript = (input, turn) -> {
            var list = actions.get(participant(input)); if (turn <= list.size()) return list.get(turn - 1);
            var finish = JsonParser.parseString(original(input, List.of("read-1"))).getAsJsonObject(); finish.getAsJsonObject("decision").addProperty("response", "ANSWERED"); return finish.toString();
        };
        var runtime = CoordinationRuntime.forInvoiceFollowUpV2(root, ready, readyIdentity, clock, scripts);
        var adapter = new InvoiceFollowUpRunnerV2(root, bundle, runtime); var plan = InvoiceFollowUpRunnerV2.checkDefinition(readyIdentity);
        String id = adapter.register(readyIdentity.sha256(), plan, new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); var result = adapter.run(id);
        for (String worker : List.of(WEBHOOK, RECON)) {
            var accepted = outcome(result, worker).getAsJsonObject("accepted"); assertEquals("ANSWERED", accepted.getAsJsonObject("decision").get("response").getAsString());
            assertTrue(accepted.getAsJsonObject("readiness").get("structuralAccessReady").getAsBoolean()); assertEquals("NOT_PERFORMED", accepted.get("qualityReview").getAsString());
        }
        assertTrue(result.getAsJsonObject("originalsAndReview").getAsJsonArray("workers").toString().contains("UNKNOWN_REMOTE_OUTCOME"));
        assertEquals(1, usage(result).get("unresolvedUsageSlots").getAsInt()); assertEquals(result, adapter.retained(id));
    }
    private Map<String, String> inventory() throws IOException {
        var values = new TreeMap<String, String>(); for (String folder : List.of("invoice-team-follow-up-v2-trials", "invoice-team-follow-up-v2-prefix-runs", "invoice-follow-up-v2-runs")) try (var paths = Files.walk(root.resolve(folder))) {
            for (var file : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) values.put(root.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file)));
        } return values;
    }
    private final class Scripts implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger(), concurrent = new AtomicInteger(), maximum = new AtomicInteger();
        final AtomicBoolean interruptAfterPersist = new AtomicBoolean(), interruptRegistration = new AtomicBoolean();
        final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>(); final List<JsonObject> contexts = new CopyOnWriteArrayList<>();
        BiFunction<JsonObject, Integer, String> originalScript = (input, turn) -> original(input, List.of());
        BiFunction<JsonObject, Integer, String> reviewScript = (input, turn) -> finishReview(input, true);
        BiFunction<JsonObject, Integer, String> freshScript = (input, turn) -> original(input, List.of());
        String interruptRegistrationParticipant; Runnable afterFresh; final AtomicBoolean fired = new AtomicBoolean();
        String interruptParticipant, unknownParticipant, blockWorker; CyclicBarrier barrier; CountDownLatch entered, release;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (participant(JsonParser.parseString(request.input()).getAsJsonObject()).equals(interruptRegistrationParticipant) && interruptRegistration.compareAndSet(true, false)) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return record(file); assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var input = JsonParser.parseString(record(store.resolve(id + "/request.json")).get("input").getAsString()).getAsJsonObject(); contexts.add(input);
                    String participant = participant(input); int turn = turns.computeIfAbsent(participant, ignored -> new AtomicInteger()).incrementAndGet(); int active = concurrent.incrementAndGet(); maximum.accumulateAndGet(active, Math::max);
                    try {
                        if (barrier != null && fresh(input) && turn == 1) try { barrier.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } catch (BrokenBarrierException | TimeoutException e) { throw new IOException(e); }
                        if (participant.equals(blockWorker)) { entered.countDown(); try { if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("Blocked worker timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
                        boolean unknown = participant.equals(unknownParticipant); var response = json(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                                "inputTokens", 5, "outputTokens", 2, "outputText", (review(input) ? reviewScript : fresh(input) ? freshScript : originalScript).apply(input, turn))); DurableExecution.publish(file, response);
                        if (fresh(input) && afterFresh != null && fired.compareAndSet(false, true)) afterFresh.run();
                        if (participant.equals(interruptParticipant) && interruptAfterPersist.compareAndSet(true, false)) throw new IOException("Interrupted controller"); return response;
                    } finally { concurrent.decrementAndGet(); }
                }
                public JsonObject recover(String id) throws IOException { return record(store.resolve(id + "/result.json")); }
            };
        }
    }
}
