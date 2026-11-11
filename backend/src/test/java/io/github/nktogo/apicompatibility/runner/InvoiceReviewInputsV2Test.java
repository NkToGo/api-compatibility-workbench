package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceReviewInputsV2Test {
    @TempDir Path root;
    @TempDir static Path captured;
    private static Path originalInputs;
    private static ContentDigest inputIdentity;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>();
    private boolean unknownSingle, failIndependent, failFinal;
    private InvoiceReviewInputsV2.Selection selected;
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), captured.resolve("capture"), Duration.ofSeconds(30), () -> true);
        originalInputs = captured.resolve("inputs"); inputIdentity = InvoiceComparisonInputs.capture(plan, run, originalInputs).identity();
    }
    private InvoiceReviewInputsV2 inspector() { return new InvoiceReviewInputsV2(root); }
    private static JsonObject object(Path path) throws IOException { return InvoiceReviewInputs.object(Files.readAllBytes(path), 8388608); }
    private static ContentDigest digest(Path file) throws IOException { return new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))); }
    private InvoiceReviewInputsV2.Selection fixture() throws Exception {
        if (selected != null) return selected;
        try (var paths = Files.walk(originalInputs)) { for (var path : paths.toList()) {
            Path target = root.resolve("inputs").resolve(originalInputs.relativize(path));
            if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
        } }
        Files.createDirectory(root.resolve("contract"));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json")) Files.writeString(root.resolve("contract/" + name), "synthetic protected contract " + name);
        Files.copy(Path.of(System.getProperty("workbench.repository")).resolve("docs/evaluations/invoice-review-rubric-v1.json"), root.resolve("rubric.json"));
        var sources = new ArrayList<InvoiceReviewInputs.Source>();
        for (Arm arm : Arm.values()) {
            Path state = Files.createDirectory(root.resolve(arm.name())); var clock = new CoordinationRuntimeTest.MutableClock();
            var slot = new Slot("invoice-joint", 1, arm); String id, store; JsonObject result;
            var bundle = InvoiceComparisonInputs.open(root.resolve("inputs"), inputIdentity);
            switch (arm) {
                case DETERMINISTIC -> { var a = new InvoiceDiagnosticsV2(state, CoordinationRuntime.forInvoiceNavigation(state, root.resolve("inputs"), inputIdentity, clock));
                    id = a.register(inputIdentity.sha256(), InvoiceDiagnosticsV2.checkDefinition(inputIdentity), slot); result = a.run(id); store = "invoice-diagnostics-v2"; }
                case SINGLE_AGENT -> { var a = new InvoiceSingleAgentV2(state, bundle, CoordinationRuntime.forInvoiceInvestigationV2(state, root.resolve("inputs"), inputIdentity, clock, this::broker));
                    id = a.register(inputIdentity.sha256(), InvoiceSingleAgentV2.checkDefinition(inputIdentity), slot); result = a.run(id); store = "invoice-single-agent-v2"; }
                case INDEPENDENT_WORKERS -> { var a = new InvoiceIndependentWorkersV2(state, bundle, CoordinationRuntime.forInvoiceInvestigationV2(state, root.resolve("inputs"), inputIdentity, clock, this::broker));
                    id = a.register(inputIdentity.sha256(), InvoiceIndependentWorkersV2.checkDefinition(inputIdentity), slot); result = a.run(id); store = "invoice-independent-workers-v2"; }
                case COORDINATED_TEAM -> { var a = new InvoiceTeamComparisonV2(state, bundle, CoordinationRuntime.forInvoiceReconciliationV2(state, root.resolve("inputs"), inputIdentity, clock, this::broker));
                    var base = InvoiceTeamComparisonV2.checkDefinition(inputIdentity);
                    var definition = new Definition(base.experimentId(), base.origin(), base.model(), base.rubricIdentity(), base.implementations(), base.workloads(), 1, new Limits(60, 1200000, 64000, 2048));
                    id = a.register(inputIdentity.sha256(), definition, slot); result = a.run(id); store = "invoice-team-comparisons-v2"; }
                default -> throw new IllegalArgumentException();
            }
            assertTrue(result.has("trial")); sources.add(new InvoiceReviewInputs.Source(arm, arm.name(), id, digest(state.resolve(store + "/" + id + "/result.json"))));
        }
        selected = new InvoiceReviewInputsV2.Selection(2, "inputs", inputIdentity, "contract", new ContentDigest(CaseInputs.identity(CaseInputs.inventory(root.resolve("contract")))),
                "rubric.json", digest(root.resolve("rubric.json")), InvoiceDecisionV2.taskIdentity(), sources); return selected;
    }
    private CoordinationRuntime.Broker broker(Path store, BooleanSupplier permitted) throws IOException {
        Files.createDirectories(store);
        return new CoordinationRuntime.Broker() {
            public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); return id;
            }
            public JsonObject execute(String id) throws IOException {
                assertTrue(permitted.getAsBoolean()); calls.incrementAndGet(); var request = object(store.resolve(id + "/request.json"));
                var input = JsonParser.parseString(request.get("input").getAsString()).getAsJsonObject();
                boolean review = input.has("workers"), finalReview = input.has("history");
                String participant = review ? "invoice-coordinator" : input.getAsJsonObject("subject").get("participantId").getAsString();
                String key = store.getParent().getParent() + "/" + participant; int turn = turns.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                boolean unknown = unknownSingle && store.startsWith(root.resolve("SINGLE_AGENT")); JsonObject response;
                if (unknown) response = CaseInputs.JSON.toJsonTree(Map.of("callId", id, "status", "RATE_LIMITED", "remoteOutcome", "RESPONSE_RECEIVED", "httpStatus", 429)).getAsJsonObject();
                else {
                    Object decision;
                    if (failFinal && finalReview || failIndependent && participant.equals("invoice-webhook") && store.startsWith(root.resolve("INDEPENDENT_WORKERS"))) decision = Map.of("action", "grant");
                    else if (turn == 1) decision = Map.of("action", "read_text", "alias", "task", "offset", 0, "maxCharacters", 4000);
                    else if (review) {
                        var questions = new ArrayList<Object>();
                        for (var value : input.getAsJsonArray("workers")) { var worker = value.getAsJsonObject(); questions.add(Map.of("workerId", worker.get("workerId").getAsString(), "question", "Which ledger effects remain unresolved?", "citations", List.of(worker.get("referenceId").getAsString()))); }
                        decision = Map.of("action", "finish", "summary", "Preserve both original outcomes.", "citations", List.of("worker-1", "worker-2"), "followUps", questions,
                                "conflicts", List.of(Map.of("claimIds", List.of("claim-1", "claim-2"), "statement", "A possible difference remains unresolved.")));
                    } else decision = Map.of("action", "finish", "response", "ABSTAINED", "summary", "Captured scope requires further review.", "summaryCitations", List.of("read-1"),
                            "findings", List.of(Map.of("kind", "LIMITATION", "statement", "This does not establish release approval.", "citations", List.of("read-1"))));
                    response = CaseInputs.JSON.toJsonTree(Map.of("callId", id, "status", "COMPLETED", "remoteOutcome", "RESPONSE_RECEIVED", "inputTokens", 5, "outputTokens", 2,
                            "outputText", JSON.toJson(Map.of("decision", decision)))).getAsJsonObject();
                }
                DurableExecution.publish(store.resolve(id + "/result.json"), response); return response;
            }
            public JsonObject recover(String id) throws IOException { return object(store.resolve(id + "/result.json")); }
        };
    }
    private Map<String, String> hashes() throws IOException {
        var hashes = new TreeMap<String, String>(); try (var paths = Files.walk(root)) { for (var p : paths.filter(Files::isRegularFile).toList()) hashes.put(root.relativize(p).toString(), CaseInputs.hash(Files.readAllBytes(p))); } return hashes;
    }
    private static JsonArray units(JsonObject inventory, int arm) { return inventory.getAsJsonArray("trials").get(arm).getAsJsonObject().getAsJsonArray("units"); }
    private InvoiceReviewInputsV2.Selection sources(InvoiceReviewInputsV2.Selection s, List<InvoiceReviewInputs.Source> sources) {
        return new InvoiceReviewInputsV2.Selection(2, s.inputDirectory(), s.inputIdentity(), s.contractDirectory(), s.contractIdentity(), s.rubricArtifact(), s.rubricIdentity(), s.publicTaskSha256(), sources);
    }
    @Test void retainedFourArmInspectionIsRepeatableAndCreatesNoFilesOrCalls() throws Exception {
        var s = fixture(); Path selection = root.resolve("selection.json"); Files.writeString(selection, JSON.toJson(s));
        var before = hashes(); int used = calls.get(); var inventory = inspector().inspect(s);
        assertEquals(4, inventory.getAsJsonArray("trials").size()); assertEquals("NOT_INFERRED_FROM_SELECTION", inventory.get("criteriaTiming").getAsString());
        assertEquals("NOT_ESTABLISHED", inventory.get("campaignAcceptance").getAsString()); assertEquals("NOT_PERFORMED", inventory.get("qualityReview").getAsString());
        assertEquals(inventory, inspector().inspect(s)); assertEquals(before, hashes()); assertEquals(used, calls.get());
        var capturedOutput = new java.io.ByteArrayOutputStream(); var originalOutput = System.out;
        try (var output = new java.io.PrintStream(capturedOutput, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(output); InvoiceReviewInputsV2.main(new String[]{"inspect", root.toString(), selection.toString()});
        } finally { System.setOut(originalOutput); }
        String emitted = capturedOutput.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(inventory, JsonParser.parseString(emitted)); assertFalse(emitted.contains("Captured scope requires further review."));
        assertEquals(before, hashes()); assertEquals(used, calls.get());
        for (var row : inventory.getAsJsonArray("trials")) assertTrue(row.getAsJsonObject().getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("review").isJsonNull());
    }
    @Test void everyUnitResolvesToItsExactValueDigestIncludingNestedFinalAdvice() throws Exception {
        var inventory = inspector().inspect(fixture()); boolean nestedSummary = false;
        for (var row : inventory.getAsJsonArray("trials")) { var ids = new HashSet<String>(); for (var v : row.getAsJsonObject().getAsJsonArray("units")) {
            var unit = v.getAsJsonObject(); assertTrue(ids.add(unit.get("unitId").getAsString())); String ref = unit.get("artifact").getAsString(), pointer = unit.get("pointer").getAsString();
            var value = InvoiceReviewInputs.pointer(object(root.resolve(ref)), pointer);
            assertEquals(CoordinationTeamRunner.digest(CoordinationTeamRunner.encoded(value)).sha256(), unit.getAsJsonObject("valueIdentity").get("sha256").getAsString());
            assertTrue(inventory.getAsJsonObject("sourceArtifacts").has(ref)); nestedSummary |= pointer.equals("/decision/summary");
        } } assertTrue(nestedSummary);
    }
    @Test void originalFreshClaimsQuestionsConflictsAndAllModelResponsesRemainVisible() throws Exception {
        var inventory = inspector().inspect(fixture()); var team = units(inventory, 3);
        for (String kind : List.of("ORIGINAL_CLAIM", "FRESH_CLAIM", "RECONCILIATION", "TEAM_SOURCE")) assertTrue(team.asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals(kind)), kind);
        for (String pointer : List.of("/selectedFollowUps", "/possibleConflicts", "/coordinatorReview")) assertTrue(team.asList().stream().anyMatch(u -> u.getAsJsonObject().get("pointer").getAsString().equals(pointer)));
        assertTrue(team.asList().stream().filter(u -> u.getAsJsonObject().get("kind").getAsString().equals("ORIGINAL_CLAIM")).allMatch(u -> u.getAsJsonObject().get("directFinalCitation").getAsString().equals("NOT_CITED")));
        long responses = 0; for (var row : inventory.getAsJsonArray("trials")) responses += row.getAsJsonObject().getAsJsonArray("units").asList().stream().filter(u -> u.getAsJsonObject().get("kind").getAsString().equals("MODEL_RESPONSE")).count();
        assertEquals(calls.get(), responses);
    }
    @Test void uncertainResponseHasAnOutcomeUnitWithoutInventedTextOrUsage() throws Exception {
        unknownSingle = true; var inventory = inspector().inspect(fixture()); var row = inventory.getAsJsonArray("trials").get(1).getAsJsonObject();
        assertEquals("UNKNOWN_REMOTE_OUTCOME", row.getAsJsonObject("trial").get("status").getAsString()); assertTrue(row.get("dossierArtifact").isJsonNull());
        assertTrue(units(inventory, 1).asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals("BROKER_OUTCOME")));
        assertFalse(units(inventory, 1).asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals("MODEL_RESPONSE")));
        assertEquals(1, row.getAsJsonObject("trial").getAsJsonObject("usage").get("unresolvedUsageSlots").getAsInt());
    }
    @Test void rejectedFinishesAndPartialIndependentOutputAreNotDroppedOrPromoted() throws Exception {
        failIndependent = true; failFinal = true; var inventory = inspector().inspect(fixture());
        assertTrue(units(inventory, 2).asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals("PARTIAL_AGGREGATE")));
        var team = inventory.getAsJsonArray("trials").get(3).getAsJsonObject(); assertTrue(team.get("dossierArtifact").isJsonNull());
        assertTrue(units(inventory, 3).asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals("MODEL_RESPONSE")));
        assertEquals("NOT_PROVIDED", team.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
    }
    @Test void missingRuntimeFailsBeforeAReaderCanCreateDirectories() throws Exception {
        var s = fixture(); Path runtime = root.resolve("SINGLE_AGENT/invoice-v2-trials"); Path moved = root.resolve("held-runtime"); Files.move(runtime, moved);
        var before = hashes(); assertThrows(RuntimeException.class, () -> inspector().inspect(s)); assertFalse(Files.exists(runtime)); assertEquals(before, hashes());
    }
    @Test void changedResultIdentityAndMissingArmCannotShrinkTheDenominator() throws Exception {
        var s = fixture(); var values = new ArrayList<>(s.sources()); var source = values.getFirst();
        values.set(0, new InvoiceReviewInputs.Source(source.arm(), source.stateDirectory(), source.runId(), new ContentDigest("b".repeat(64))));
        assertThrows(RuntimeException.class, () -> inspector().inspect(sources(s, values))); assertThrows(RuntimeException.class, () -> sources(s, s.sources().subList(0, 3)));
        values.set(1, source); assertThrows(RuntimeException.class, () -> sources(s, values));
    }
    @Test void v1NamespacesCannotMasqueradeAsV2Evidence() throws Exception {
        var s = fixture(); Files.move(root.resolve("DETERMINISTIC/invoice-diagnostics-v2"), root.resolve("DETERMINISTIC/invoice-diagnostics"));
        var before = hashes(); assertThrows(RuntimeException.class, () -> inspector().inspect(s)); assertEquals(before, hashes());
    }
    @Test void tamperedHistoryIsRejectedEvenWhenSelectedResultIsUnchanged() throws Exception {
        var s = fixture(); var source = s.sources().get(3); Path file = root.resolve("COORDINATED_TEAM/invoice-team-reconciliation-v2-prefix-runs/" + source.runId() + "/result.json");
        var changed = object(file); changed.addProperty("injected", true); Files.writeString(file, JSON.toJson(changed)); assertThrows(RuntimeException.class, () -> inspector().inspect(s));
    }
    @Test void protectedContractAndRubricChangesInvalidateInspection() throws Exception {
        var s = fixture(); Path file = root.resolve("contract/business-oracle.md"); String before = Files.readString(file); Files.writeString(file, before + "changed");
        assertThrows(RuntimeException.class, () -> inspector().inspect(s)); Files.writeString(file, before); Files.writeString(root.resolve("rubric.json"), "{}"); assertThrows(RuntimeException.class, () -> inspector().inspect(s));
    }
    @Test void duplicateKeysAndMalformedUtf8CannotHideInsideRuntimeRecords() throws Exception {
        var s = fixture(); var source = s.sources().get(1); Path file = root.resolve("SINGLE_AGENT/invoice-v2-trials/" + source.runId() + "/result.json"); byte[] before = Files.readAllBytes(file);
        Files.writeString(file, "{\"x\":1,\"x\":2}"); assertThrows(RuntimeException.class, () -> inspector().inspect(s)); Files.write(file, new byte[]{(byte)0xc3, 0x28}); assertThrows(RuntimeException.class, () -> inspector().inspect(s));
        Files.write(file, before); assertNotNull(inspector().inspect(s));
    }
    @Test void unknownSelectionFieldsAndOldVersionAreRejected() throws Exception {
        var s = fixture(); Path selection = root.resolve("selection.json"); var tree = JSON.toJsonTree(s).getAsJsonObject(); tree.addProperty("accepted", true); Files.writeString(selection, JSON.toJson(tree));
        assertThrows(RuntimeException.class, () -> InvoiceReviewInputsV2.main(new String[]{"inspect", root.toString(), selection.toString()}));
        assertThrows(RuntimeException.class, () -> new InvoiceReviewInputsV2.Selection(1, s.inputDirectory(), s.inputIdentity(), s.contractDirectory(), s.contractIdentity(), s.rubricArtifact(), s.rubricIdentity(), s.publicTaskSha256(), s.sources()));
        assertThrows(RuntimeException.class, () -> new InvoiceReviewInputsV2.Selection(2, "../inputs", s.inputIdentity(), s.contractDirectory(), s.contractIdentity(), s.rubricArtifact(), s.rubricIdentity(), s.publicTaskSha256(), s.sources()));
        assertThrows(RuntimeException.class, () -> new InvoiceReviewInputsV2.Selection(2, s.inputDirectory(), s.inputIdentity(), s.contractDirectory(), s.contractIdentity(), s.rubricArtifact(), s.rubricIdentity(), "0".repeat(64), s.sources()));
    }
    @Test void oversizedEvidenceIsRefusedWithoutChangingOrTruncatingIt() throws Exception {
        var s = fixture(); var source = s.sources().getFirst(); Path file = root.resolve("DETERMINISTIC/invoice-diagnostics-v2/" + source.runId() + "/extra.txt");
        byte[] oversized = new byte[1048577]; Arrays.fill(oversized, (byte)'x'); Files.write(file, oversized); var before = hashes();
        assertThrows(RuntimeException.class, () -> inspector().inspect(s)); assertEquals(before, hashes()); assertEquals(1048577, Files.size(file));
    }
    @Test void linkedEvidenceCannotEscapeTheSelectedStore() throws Exception {
        var s = fixture(); Path outside = root.resolve("outside"); Files.createDirectory(outside);
        Path link = root.resolve("contract/linked");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException e) { Assumptions.assumeTrue(false, "Symbolic links unavailable on this host"); }
        try { assertThrows(RuntimeException.class, () -> inspector().inspect(s)); }
        finally { Files.delete(link); }
    }
}
