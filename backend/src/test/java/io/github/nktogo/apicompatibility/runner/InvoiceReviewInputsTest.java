package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceReviewInputsTest {
    @TempDir Path root;
    private final Map<String, JsonObject> responses = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();
    private boolean failFinalAndIndependentWebhook;
    private Path inputs; private ContentDigest identity; private InvoiceReviewInputs.Selection selection;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private InvoiceReviewInputs adapter() throws IOException { return new InvoiceReviewInputs(root, root.resolve("out")); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject object(Path path) throws IOException { return CaseInputs.object(Files.readAllBytes(path)); }
    private static ContentDigest digest(Path file) throws IOException { return new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))); }
    private static final String BAD = "trial-00000000-0000-0000-0000-000000000000";
    private InvoiceReviewInputs.Selection fixture() throws Exception {
        if (selection != null) return selection;
        Files.createDirectory(root.resolve("out")); Files.createDirectory(root.resolve("contract"));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json")) Files.writeString(root.resolve("contract/" + name), "protected synthetic contract " + name);
        Path repository = InvoiceSandboxTest.root(); Files.copy(Path.of(System.getProperty("workbench.repository")).resolve("docs/evaluations/invoice-review-rubric-v1.json"), root.resolve("rubric.json"));
        var plan = InvoiceCaseCoverage.load(repository); var capture = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("capture"), Duration.ofSeconds(30), () -> true);
        inputs = root.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, capture, inputs).identity(); var sources = new ArrayList<InvoiceReviewInputs.Source>();
        for (Arm arm : Arm.values()) {
            Path state = Files.createDirectory(root.resolve(arm.name())); var runtime = CoordinationRuntime.forInvoiceInvestigation(state, inputs, identity, new CoordinationRuntimeTest.MutableClock(), this::broker);
            var slot = new Slot("invoice-joint", 1, arm); String id; JsonObject result; String store;
            switch (arm) {
                case DETERMINISTIC -> { var a = new InvoiceDiagnostics(state, runtime); id = a.register(identity.sha256(), InvoiceDiagnostics.checkDefinition(identity), slot); result = a.run(id); store = "invoice-diagnostics"; }
                case SINGLE_AGENT -> { var a = new InvoiceSingleAgent(state, runtime); id = a.register(identity.sha256(), InvoiceSingleAgent.checkDefinition(identity), slot); result = a.run(id); store = "invoice-single-agent"; }
                case INDEPENDENT_WORKERS -> { var a = new InvoiceIndependentWorkers(state, runtime); id = a.register(identity.sha256(), InvoiceIndependentWorkers.checkDefinition(identity), slot); result = a.run(id); store = "invoice-independent-workers"; }
                case COORDINATED_TEAM -> { var a = new InvoiceTeamComparison(state, runtime); id = a.register(identity.sha256(), InvoiceTeamComparison.checkDefinition(identity), slot); result = a.run(id); store = "invoice-team-comparisons"; }
                default -> throw new IllegalArgumentException();
            }
            assertTrue(result.has("trial")); sources.add(new InvoiceReviewInputs.Source(arm, arm.name(), id, digest(state.resolve(store + "/" + id + "/result.json"))));
        }
        selection = new InvoiceReviewInputs.Selection(1, "inputs", identity, "contract", new ContentDigest(CaseInputs.identity(CaseInputs.inventory(root.resolve("contract")))), "rubric.json", digest(root.resolve("rubric.json")), sources); return selection;
    }
    private CoordinationRuntime.Broker broker(Path store, BooleanSupplier permitted) throws IOException {
        Files.createDirectories(store);
        return new CoordinationRuntime.Broker() {
            public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registrations.incrementAndGet();
                JsonObject decision;
                if (request.protocol().equals(InvoiceTeamDecision.PROTOCOL)) decision = json(Map.of("action", "finish", "summary", "Both role histories are retained.", "citations", List.of("worker-1", "worker-2"), "followUps", List.of(), "conflicts", List.of(Map.of("claimIds", List.of("claim-1", "claim-2"), "statement", "A possible scope difference remains unresolved."))));
                else if (failFinalAndIndependentWebhook && (request.protocol().equals(InvoiceReconciliationDecision.PROTOCOL)
                        || store.startsWith(root.resolve("INDEPENDENT_WORKERS")) && request.protocol().equals(InvoiceWorkerDecision.WEBHOOK))) decision = json(Map.of("action", "grant"));
                else decision = json(Map.of("action", "finish", "response", "ABSTAINED", "summary", "Partial inspection does not establish correctness.", "summaryCitations", List.of(), "findings", List.of()));
                var response = json(Map.of("callId", id, "status", "COMPLETED", "remoteOutcome", "RESPONSE_RECEIVED", "inputTokens", 5, "outputTokens", 2, "outputText", JSON.toJson(Map.of("decision", decision))));
                responses.put(id, response); DurableExecution.publish(store.resolve(id + "/result.json"), response); return id;
            }
            public JsonObject execute(String id) { assertTrue(permitted.getAsBoolean()); return responses.get(id); }
            public JsonObject recover(String id) { return responses.get(id); }
        };
    }
    @Test void allFourExactHistoricalTrialsBindWithoutCredentialsOrChangingSources() throws Exception {
        var selected = fixture(); var before = sourceHashes(); int calls = registrations.get(); String id = adapter().prepare(selected); var packet = adapter().read(id);
        assertEquals("REVIEW_INPUTS_BOUND", packet.get("status").getAsString()); assertEquals("NOT_PERFORMED", packet.get("qualityReview").getAsString()); assertEquals(calls, registrations.get()); assertEquals(before, sourceHashes());
        var rows = packet.getAsJsonObject("inventory").getAsJsonArray("trials"); assertEquals(4, rows.size());
        for (var row : rows) { assertFalse(row.getAsJsonObject().getAsJsonArray("units").isEmpty()); assertTrue(row.getAsJsonObject().getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("review").isJsonNull()); }
        assertEquals("POST_HOC_FOR_EXISTING_OPERATIONAL_TRIALS", packet.getAsJsonObject("inventory").get("criteriaTiming").getAsString());
        assertEquals(id, adapter().prepare(id, selected)); assertEquals(packet, adapter().read(id)); assertEquals(before, sourceHashes());
        var overlapping = new InvoiceReviewInputs(root, root.resolve("contract"));
        assertThrows(RuntimeException.class, () -> overlapping.prepare(selected)); assertFalse(Files.exists(root.resolve("contract/invoice-review-packets"))); assertEquals(before, sourceHashes());
    }
    @Test void originalUncitedClaimsConflictsAndEveryModelResponseRemainReviewable() throws Exception {
        var selected = fixture(); var packet = adapter().read(adapter().prepare(selected)); var units = packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(3).getAsJsonObject().getAsJsonArray("units");
        var claims = units.asList().stream().map(JsonElement::getAsJsonObject).filter(u -> u.get("kind").getAsString().equals("ORIGINAL_CLAIM")).toList(); assertEquals(2, claims.size());
        assertTrue(claims.stream().allMatch(c -> c.get("directFinalCitation").getAsString().equals("NOT_CITED")));
        assertTrue(units.asList().stream().anyMatch(u -> u.getAsJsonObject().get("pointer").getAsString().equals("/possibleConflicts")));
        assertEquals(4, units.asList().stream().filter(u -> u.getAsJsonObject().get("kind").getAsString().equals("MODEL_RESPONSE")).count());
    }
    @Test void missingSelectedResultIsRefusedRatherThanShrinkingTheFourArmDenominator() throws Exception {
        var s = fixture(); var first = s.sources().getFirst(); var altered = new ArrayList<>(s.sources()); altered.set(0, new InvoiceReviewInputs.Source(first.arm(), first.stateDirectory(), BAD, first.resultIdentity()));
        assertThrows(RuntimeException.class, () -> adapter().prepare(replace(s, altered))); assertEquals(4, s.sources().size());
        assertThrows(RuntimeException.class, () -> replace(s, s.sources().subList(0, 3)));
        var duplicate = new ArrayList<>(s.sources()); duplicate.set(1, first); assertThrows(RuntimeException.class, () -> replace(s, duplicate));
    }
    @Test void externalInputAndResultIdentitiesCannotBeTakenFromChangedFolderClaims() throws Exception {
        var s = fixture(); var first = s.sources().getFirst(); var changed = new ArrayList<>(s.sources()); changed.set(0, new InvoiceReviewInputs.Source(first.arm(), first.stateDirectory(), first.runId(), new ContentDigest("b".repeat(64))));
        assertThrows(RuntimeException.class, () -> adapter().prepare(replace(s, changed)));
        var wrongInput = new InvoiceReviewInputs.Selection(1, s.inputDirectory(), new ContentDigest("c".repeat(64)), s.contractDirectory(), s.contractIdentity(), s.rubricArtifact(), s.rubricIdentity(), s.sources());
        assertThrows(RuntimeException.class, () -> adapter().prepare(wrongInput));
    }
    @Test void protectedContractAndRubricChangesInvalidateAlreadyBoundPackets() throws Exception {
        var s = fixture(); String id = adapter().prepare(s); Path contract = root.resolve("contract/business-oracle.md"); String before = Files.readString(contract);
        Files.writeString(contract, before + " changed"); assertThrows(RuntimeException.class, () -> adapter().read(id)); Files.writeString(contract, before); assertNotNull(adapter().read(id));
        Files.writeString(root.resolve("rubric.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().read(id));
    }
    @Test void alteredUnitPointerOrAddedSemanticJudgmentCannotMasqueradeAsBoundInputs() throws Exception {
        var s = fixture(); String id = adapter().prepare(s); Path file = packet(id).resolve("packet.json"); var before = Files.readAllBytes(file); var changed = object(file);
        changed.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonArray("units").remove(0); Files.writeString(file, JSON.toJson(changed));
        reseal(id); assertThrows(RuntimeException.class, () -> adapter().read(id)); Files.write(file, before); reseal(id);
        changed = object(file); changed.addProperty("accepted", true); Files.writeString(file, JSON.toJson(changed)); reseal(id); assertThrows(RuntimeException.class, () -> adapter().read(id));
    }
    @Test void publicationCanRecoverOnlyItsExactMissingReceiptAndMatchingPendingBytes() throws Exception {
        var s = fixture(); String id = adapter().prepare(s); var original = adapter().read(id); Path receipt = packet(id).resolve("receipt.json"); byte[] bytes = Files.readAllBytes(receipt); Files.delete(receipt);
        assertThrows(RuntimeException.class, () -> adapter().read(id)); assertEquals(id, adapter().prepare(id, s)); assertEquals(original, adapter().read(id));
        Files.delete(receipt); Files.write(packet(id).resolve("receipt.json.pending"), bytes); assertEquals(id, adapter().prepare(id, s)); assertEquals(original, adapter().read(id));
        Files.writeString(packet(id).resolve("packet.json.pending"), "changed"); assertThrows(RuntimeException.class, () -> adapter().read(id)); assertThrows(RuntimeException.class, () -> adapter().prepare(id, s));
    }
    @Test void localOwnershipAndUnknownArtifactsDoNotReplacePublishedEvidence() throws Exception {
        var s = fixture(); String id = adapter().prepare(s); var packet = packet(id);
        try (var lease = LocalFileLease.acquire(packet.resolve("owner.lock"))) { assertNotNull(lease); assertThrows(RuntimeException.class, () -> adapter().prepare(id, s)); }
        Files.writeString(packet.resolve("extra.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().read(id));
    }
    @Test void linkedContractAndEscapedSelectionPathsAreRefused() throws Exception {
        var s = fixture(); var f = s.sources().getFirst(); assertThrows(RuntimeException.class, () -> new InvoiceReviewInputs.Source(f.arm(), "../escaped", f.runId(), f.resultIdentity()));
        Path outside = Files.createDirectory(root.resolve("outside")); Files.writeString(outside.resolve("data.json"), "{}");
        try { Files.createSymbolicLink(root.resolve("contract/link"), outside); }
        catch (UnsupportedOperationException | IOException | SecurityException e) {
            if (!System.getProperty("os.name").startsWith("Windows")) org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable");
            var command = new ProcessBuilder("cmd", "/c", "mklink", "/J", root.resolve("contract/link").toString(), outside.toString()).redirectErrorStream(true).start();
            if (!command.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { command.destroyForcibly(); org.junit.jupiter.api.Assumptions.abort("Junction creation timed out"); }
            if (command.exitValue() != 0) org.junit.jupiter.api.Assumptions.abort("Junctions unavailable");
        }
        assertThrows(RuntimeException.class, () -> adapter().prepare(s));
    }
    @Test void pointerEscapingBoundsAndExactValuesAreCheckedWithoutInterpretingClaims() {
        var data = json(Map.of("a/b", List.of(Map.of("~key", "untrusted statement")))); assertEquals(new JsonPrimitive("untrusted statement"), InvoiceReviewInputs.pointer(data, "/a~1b/0/~0key"));
        for (String p : List.of("a/b", "/a~1b/01", "/a~1b/2", "/a~1b/0/~3key", "/missing", "/a~1b/0/~0key/next")) assertThrows(RuntimeException.class, () -> InvoiceReviewInputs.pointer(data, p));
    }
    @Test void rubricIsProtectedCriterionMembershipNotASuppliedAcceptanceVerdict() throws Exception {
        var r = object(Path.of(System.getProperty("workbench.repository")).resolve("docs/evaluations/invoice-review-rubric-v1.json")); assertDoesNotThrow(() -> InvoiceReviewInputs.validateRubric(r));
        r.getAsJsonArray("criteria").remove(0); assertThrows(RuntimeException.class, () -> InvoiceReviewInputs.validateRubric(r));
        var typed = object(Path.of(System.getProperty("workbench.repository")).resolve("docs/evaluations/invoice-review-rubric-v1.json")); typed.getAsJsonArray("criteria").get(0).getAsJsonObject().addProperty("requirement", true);
        assertThrows(RuntimeException.class, () -> InvoiceReviewInputs.validateRubric(typed));
    }
    @Test void readOfAbsentPacketCreatesNoStore() throws Exception {
        Files.createDirectory(root.resolve("out")); var a = adapter(); assertFalse(Files.exists(root.resolve("out/invoice-review-packets")));
        assertThrows(RuntimeException.class, () -> a.read("reviewpacket-00000000-0000-0000-0000-000000000000")); assertFalse(Files.exists(root.resolve("out/invoice-review-packets")));
    }
    @Test void strictJsonParsingRejectsDuplicatesTrailingBytesAndOversizedOrInvalidUtf8Data() {
        var large = new TreeMap<String, String>(); for (int i = 0; i < 1000; i++) large.put("artifact-" + i, "a".repeat(64));
        assertEquals(1000, InvoiceReviewInputs.object(JSON.toJson(large).getBytes(StandardCharsets.UTF_8)).size());
        for (String raw : List.of("{\"same\":1,\"same\":2}", "{} {}", "{\"n\":NaN}", "{\"array\":[" + "0,".repeat(5000) + "0]}")) assertThrows(RuntimeException.class, () -> InvoiceReviewInputs.object(raw.getBytes(StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class, () -> InvoiceReviewInputs.object(new byte[] {(byte) 0xff})); assertThrows(RuntimeException.class, () -> InvoiceReviewInputs.object(new byte[1048577]));
    }
    @Test void missingFinalOutputAndPartialIndependentAggregationRemainInTheInventory() throws Exception {
        failFinalAndIndependentWebhook = true; var s = fixture(); var rows = adapter().read(adapter().prepare(s)).getAsJsonObject("inventory").getAsJsonArray("trials");
        var independent = rows.get(2).getAsJsonObject(); var team = rows.get(3).getAsJsonObject();
        for (var row : List.of(independent, team)) assertEquals("NOT_PROVIDED", row.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
        assertFalse(independent.get("dossierArtifact").isJsonNull()); assertTrue(independent.getAsJsonArray("units").asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals("PARTIAL_AGGREGATE")));
        assertTrue(team.get("dossierArtifact").isJsonNull()); assertEquals(2, team.getAsJsonArray("units").asList().stream().filter(u -> u.getAsJsonObject().get("kind").getAsString().equals("ORIGINAL_CLAIM")).count());
        assertFalse(team.getAsJsonArray("units").asList().stream().anyMatch(u -> u.getAsJsonObject().get("kind").getAsString().equals("FINAL_DOSSIER")));
    }
    @Test void completedPacketRepreparesInAFreshJvmWithoutChangingItsOriginalBytes() throws Exception {
        var s = fixture(); String id = adapter().prepare(s); var packet = packet(id).resolve("packet.json");
        // Also retain a valid earlier noncanonical encoding, with its corresponding byte receipt.
        var value = object(packet); var reordered = new JsonObject(); var keys = new ArrayList<>(value.keySet()); keys.sort(Comparator.reverseOrder()); keys.forEach(k -> reordered.add(k, value.get(k)));
        Files.writeString(packet, JSON.toJson(reordered)); reseal(id); byte[] before = Files.readAllBytes(packet); var evidenceBefore = sourceHashes();
        Path plan = root.resolve("selection.json"); Files.writeString(plan, JSON.toJson(s));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), InvoiceReviewInputs.class.getName(), "prepare", root.toString(), root.resolve("out").toString(), plan.toString(), id).redirectErrorStream(true).redirectOutput(root.resolve("fresh-jvm.log").toFile());
        builder.environment().remove("OPENAI_API_KEY"); var process = builder.start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Fresh-JVM packet inspection timed out"); }
        assertEquals(0, process.exitValue(), "Fresh-JVM retained input validation failed"); assertArrayEquals(before, Files.readAllBytes(packet)); assertEquals(evidenceBefore, sourceHashes()); assertNotNull(adapter().read(id));
    }
    private static InvoiceReviewInputs.Selection replace(InvoiceReviewInputs.Selection s, List<InvoiceReviewInputs.Source> sources) { return new InvoiceReviewInputs.Selection(1, s.inputDirectory(), s.inputIdentity(), s.contractDirectory(), s.contractIdentity(), s.rubricArtifact(), s.rubricIdentity(), sources); }
    private Path packet(String id) { return root.resolve("out/invoice-review-packets/" + id); }
    private void reseal(String id) throws IOException { Files.writeString(packet(id).resolve("receipt.json"), JSON.toJson(Map.of("packetSha256", digest(packet(id).resolve("packet.json")).sha256()))); }
    private Map<String, String> sourceHashes() throws IOException {
        var map = new TreeMap<String, String>(); for (String name : List.of("inputs", "contract", "DETERMINISTIC", "SINGLE_AGENT", "INDEPENDENT_WORKERS", "COORDINATED_TEAM")) try (var files = Files.walk(root.resolve(name))) {
            for (var p : files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) map.put(root.relativize(p).toString(), CaseInputs.hash(Files.readAllBytes(p)));
        } return map;
    }
}
