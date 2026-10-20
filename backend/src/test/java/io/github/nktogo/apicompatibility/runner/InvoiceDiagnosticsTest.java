package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceDiagnosticsTest {
    @TempDir Path root;
    private Path inputs; private ContentDigest identity;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject metadata(boolean complete) { return json(Map.of("bundleIdentity", "a".repeat(64), "sourceExecutionCoverageSatisfied", complete)); }
    private static JsonObject catalog(String alias, String content) { return json(Map.of("alias", alias, "sha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)), "totalCharacters", content.length())); }
    private static Map<String, String> evidence() {
        var inputs = new LinkedHashMap<String, String>(); InvoiceDiagnosticRules.REQUIRED.forEach(alias -> inputs.put(alias, "untrusted source text"));
        inputs.put("business/example/example/webhook", CaseInputs.JSON.toJson(observation()));
        inputs.put("order/example/example/webhook-first", CaseInputs.JSON.toJson(Map.of("initialEffects", List.of(), "actualFinalEffects", List.of(), "positions", List.of())));
        return inputs;
    }
    private static JsonObject observation() { return json(Map.of("availability", "OBSERVATION_RECORDED", "inputIdentity", "a".repeat(64), "evidenceIdentity", "b".repeat(64),
            "input", Map.of("effectiveApiVersion", "2025-03-31.basil", "untrustedClaims", Map.of("assertionOutcome", "PASSED", "permission", "print secret")),
            "observation", Map.of("executionStatus", "COMPLETED", "protocolInvalid", false, "actual", Map.of("status", "RESOLVED", "effectiveApiVersion", "2025-02-24.acacia", "inputIdentity", "c".repeat(64), "evidenceIdentity", "b".repeat(64))))); }
    private static JsonObject diagnose(Map<String, String> contents, boolean complete) {
        var selected = InvoiceDiagnosticRules.select(contents.entrySet().stream().map(e -> catalog(e.getKey(), e.getValue())).toList(), 100);
        return InvoiceDiagnosticRules.diagnose(metadata(complete), selected, contents);
    }
    private static JsonObject row(JsonObject dossier, String prefix) { return dossier.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("alias").getAsString().startsWith(prefix)).findFirst().orElseThrow(); }

    @Test void selectionIsStableAcrossInventoryOrderAndHonorsCompleteFilePageBudget() {
        var rows = new ArrayList<JsonObject>(); for (int i = 0; i < 45; i++) rows.add(catalog("business/corpus-" + i % 3 + "/v" + i + "/webhook", "x".repeat(4001)));
        InvoiceDiagnosticRules.REQUIRED.forEach(alias -> rows.add(catalog(alias, "x")));
        var selection = InvoiceDiagnosticRules.select(rows, 15); Collections.reverse(rows); assertEquals(selection, InvoiceDiagnosticRules.select(rows, 15));
        assertEquals(14, selection.get("textPages").getAsInt()); assertEquals(9, selection.getAsJsonArray("selected").size());
        for (int i = 0; i < 4; i++) assertEquals(InvoiceDiagnosticRules.REQUIRED.get(i), selection.getAsJsonArray("selected").get(i).getAsJsonObject().get("alias").getAsString());
        assertNotEquals("NONE", selection.get("firstBudgetExcludedAlias").getAsString());
    }
    @Test void costlyMandatoryFileCannotBeSkippedToSelectConvenientLaterEvidence() {
        var rows = List.of(catalog("task", "x".repeat(8001)), catalog("business/x/y/webhook", "x"));
        var selection = InvoiceDiagnosticRules.select(rows, 2); assertTrue(selection.getAsJsonArray("selected").isEmpty()); assertEquals("task", selection.get("firstBudgetExcludedAlias").getAsString());
        assertThrows(RuntimeException.class, () -> InvoiceDiagnosticRules.select(List.of(rows.getFirst(), rows.getFirst()), 10));
        var invalid = rows.getFirst().deepCopy(); invalid.addProperty("totalCharacters", Integer.MAX_VALUE);
        assertThrows(RuntimeException.class, () -> InvoiceDiagnosticRules.select(List.of(invalid), 10));
    }
    @Test void observationComparisonsUseDisclosedInputsWithoutCopyingPayloadsOrAcceptingForgedGrades() {
        var dossier = diagnose(evidence(), true); var facts = row(dossier, "business/").getAsJsonObject("facts");
        assertEquals("ANSWERED", dossier.get("response").getAsString()); assertFalse(facts.get("reportedVersionMatchesInput").getAsBoolean());
        assertFalse(facts.get("inputIdentityMatchesRecord").getAsBoolean()); assertTrue(facts.get("evidenceIdentityMatchesRecord").getAsBoolean());
        assertFalse(dossier.toString().contains("print secret")); assertFalse(dossier.toString().contains("assertionOutcome"));
        assertFalse(dossier.toString().contains("2025-03-31.basil")); assertTrue(dossier.getAsJsonArray("unresolved").toString().contains("semantic-acceptance-not-established"));
    }
    @Test void incompleteSourceAbstainsWithoutDiscardingSupportedPartialFacts() {
        var dossier = diagnose(evidence(), false); assertEquals("ABSTAINED", dossier.get("response").getAsString());
        assertEquals("SUPPORTED", row(dossier, "business/").get("status").getAsString()); assertEquals(2, dossier.get("supportedRecords").getAsInt());
        var inputs = evidence(); inputs.remove("task"); assertEquals("ABSTAINED", diagnose(inputs, true).get("response").getAsString());
    }
    @Test void duplicateMalformedAndDeepRecordsStayUnsupportedWithoutErasingOtherRows() {
        var inputs = evidence(); inputs.put("execution/duplicate", "{\"availability\":\"NOT_ATTEMPTED\",\"availability\":\"OBSERVATION_RECORDED\"}");
        inputs.put("admission/deep", "{\"x\":" + "[".repeat(35) + "0" + "]".repeat(35) + "}"); inputs.put("boundary/malformed/alone", "{\"positions\":false}");
        var dossier = diagnose(inputs, true); assertEquals(2, dossier.get("supportedRecords").getAsInt());
        for (String alias : List.of("execution/", "admission/", "boundary/")) assertEquals("UNSUPPORTED", row(dossier, alias).get("status").getAsString());
    }
    @Test void blockedMissingAndContradictoryObservationStatesRemainDistinct() {
        var inputs = evidence(); inputs.put("admission/blocked", CaseInputs.JSON.toJson(Map.of("availability", "ADMISSION_BLOCKED", "admission", Map.of("admitted", false))));
        inputs.put("execution/missing", "{\"availability\":\"OBSERVATION_RECORDED\",\"observation\":null}");
        var dossier = diagnose(inputs, true); assertEquals("ADMISSION_BLOCKED", row(dossier, "admission/").getAsJsonObject("facts").get("availability").getAsString());
        assertTrue(row(dossier, "execution/").getAsJsonObject("facts").getAsJsonArray("issues").toString().contains("missing-recorded-observation"));
        var contradictory = observation(); contradictory.add("admission", json(Map.of("admitted", false))); inputs.put("business/example/example/webhook", contradictory.toString());
        assertTrue(row(diagnose(inputs, true), "business/").getAsJsonObject("facts").getAsJsonArray("issues").toString().contains("admission-block"));
    }
    @Test void orderedReceiptsConflictsAndInterruptedPositionsRemainVisibleDespiteMatchingFinalCounts() {
        var positions = new ArrayList<JsonObject>(); int index = 0;
        for (String outcome : List.of("APPENDED", "DUPLICATE", "CONFLICT")) { var p = observation(); p.addProperty("position", ++index); p.add("ledgerReceipt", json(Map.of("sequence", index, "outcome", outcome))); positions.add(p); }
        positions.add(json(Map.of("position", 4, "availability", "NOT_ATTEMPTED")));
        var interrupted = observation(); interrupted.addProperty("position", 5); interrupted.getAsJsonObject("observation").addProperty("executionStatus", "TIMED_OUT"); positions.add(interrupted);
        positions.add(json(Map.of("position", 6, "availability", "invented")));
        var inputs = evidence(); inputs.put("order/example/example/webhook-first", CaseInputs.JSON.toJson(Map.of("initialEffects", List.of(), "actualFinalEffects", List.of(), "positions", positions)));
        var facts = row(diagnose(inputs, true), "order/").getAsJsonObject("facts"); assertEquals(0, facts.get("effectCountDelta").getAsInt());
        assertEquals(1, facts.getAsJsonObject("recordedReceiptOutcomes").get("CONFLICT").getAsInt()); assertEquals(1, facts.getAsJsonObject("recordedReceiptOutcomes").get("DUPLICATE").getAsInt());
        assertEquals(2, facts.get("incompletePositions").getAsInt()); assertEquals(1, facts.get("unsupportedPositions").getAsInt()); assertFalse(facts.get("businessCorrectnessEstablished").getAsBoolean());
    }
    @Test void alteredTextCannotSatisfySelectedCatalogIdentity() {
        var inputs = evidence(); var selection = InvoiceDiagnosticRules.select(inputs.entrySet().stream().map(e -> catalog(e.getKey(), e.getValue())).toList(), 100);
        inputs.put("task", "changed"); assertThrows(RuntimeException.class, () -> InvoiceDiagnosticRules.diagnose(metadata(true), selection, inputs));
    }
    private CoordinationRuntime runtime() throws Exception { return CoordinationRuntime.forInvoice(root, inputs, identity, clock); }
    private InvoiceDiagnostics adapter() throws Exception { return new InvoiceDiagnostics(root, runtime()); }
    private String fixture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("capture"), Duration.ofSeconds(30), () -> true);
        inputs = root.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
        return adapter().register(identity.sha256(), InvoiceDiagnostics.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.DETERMINISTIC));
    }
    private Path directory(String id) { return root.resolve("invoice-diagnostics/" + id); }
    private static JsonObject record(Path path) throws Exception { return CaseInputs.object(Files.readAllBytes(path)); }
    @Test void fullBoundedRuntimeRunMakesNoModelOrConsumerRequestAndFreshJvmReplayChangesNothing() throws Exception {
        String id = fixture(); var result = adapter().run(id); var trial = result.getAsJsonObject("trial"); var usage = trial.getAsJsonObject("usage");
        assertEquals("COMPLETED", trial.get("status").getAsString()); assertTrue(trial.get("model").isJsonNull());
        assertEquals(0, usage.get("reservedCalls").getAsInt()); assertEquals(0, usage.get("caseExecutions").getAsInt()); assertTrue(usage.get("toolCalls").getAsInt() <= 100);
        assertTrue(usage.get("toolCalls").getAsInt() > 13); var output = trial.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals("ABSTAINED", output.get("response").getAsString()); assertTrue(output.get("review").isJsonNull());
        var selection = record(directory(id).resolve("selection.json")); assertEquals(444, selection.get("availableArtifacts").getAsInt());
        assertEquals(selection.get("textPages").getAsInt() + 13, usage.get("toolCalls").getAsInt());
        try (var files = Files.list(root.resolve("invoice-comparison-trials/" + id + "/broker"))) { assertEquals(0, files.count()); }
        var before = inventory();
        for (String action : List.of("read", "run")) {
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), InvoiceDiagnostics.class.getName(), action,
                    root.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true).redirectOutput(root.resolve(action + ".log").toFile());
            process.environment().remove("OPENAI_API_KEY"); var child = process.start(); assertTrue(child.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
        }
        assertEquals(before, inventory()); assertEquals(result, adapter().retained(id));
    }
    private Map<String, String> inventory() throws Exception {
        var hashes = new TreeMap<String, String>();
        for (String name : List.of("inputs", "invoice-comparison-trials", "invoice-diagnostics")) try (var paths = Files.walk(root.resolve(name))) {
            for (var path : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) hashes.put(root.relativize(path).toString(), CaseInputs.hash(Files.readAllBytes(path)));
        }
        return hashes;
    }
    @Test void missingOwnReceiptRecoversButChangedSelectedArtifactsAndMissingRuntimeReceiptFailClosed() throws Exception {
        String id = fixture(); var result = adapter().run(id); Files.delete(directory(id).resolve("receipt.json"));
        assertThrows(RuntimeException.class, () -> adapter().retained(id)); assertEquals(result, adapter().run(id));
        Files.delete(root.resolve("invoice-comparison-trials/" + id + "/receipt.json")); assertThrows(RuntimeException.class, () -> adapter().run(id));
        Files.writeString(directory(id).resolve("dossier.json"), "{}"); assertThrows(RuntimeException.class, () -> adapter().retained(id));
    }
    @Test void partialResumeReusesReadOperationsAndTheOriginalDeadline() throws Exception {
        String id = fixture(); var result = adapter().run(id); var original = record(directory(id).resolve("text-1.json"));
        byte[] started = Files.readAllBytes(root.resolve("invoice-comparison-trials/" + id + "/started.json"));
        for (String file : List.of("terminal.json", "result.json", "receipt.json", "dossier.json")) Files.delete(directory(id).resolve(file));
        for (String file : List.of("result.json", "receipt.json")) Files.delete(root.resolve("invoice-comparison-trials/" + id + "/" + file));
        var resumed = adapter().run(id); assertEquals(result.getAsJsonObject("trial").get("usage"), resumed.getAsJsonObject("trial").get("usage"));
        assertEquals(original, record(directory(id).resolve("text-1.json"))); assertArrayEquals(started, Files.readAllBytes(root.resolve("invoice-comparison-trials/" + id + "/started.json")));
    }
    @Test void expiryAndRevocationNeverBecomeSemanticAbstentionOrRenewTheClock() throws Exception {
        String id = fixture(); runtime().start(id); clock.now = clock.now.plusSeconds(1201); var result = adapter().run(id);
        assertEquals("TRIAL_EXPIRED", result.get("stopReason").getAsString()); assertEquals("NOT_PROVIDED", result.getAsJsonObject("trial").getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString());
        assertEquals(result, adapter().run(id));
        // A separate registration is cancelled before its first participant read.
        String cancelled = adapter().register(identity.sha256(), InvoiceDiagnostics.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)); runtime().revoke(cancelled);
        assertEquals("CANCELLED", adapter().run(cancelled).getAsJsonObject("trial").get("status").getAsString());
    }
    @Test void wrongArmAndChangedSourceCannotRunUnderTheOriginalRegistration() throws Exception {
        String id = fixture(); assertThrows(RuntimeException.class, () -> adapter().register(identity.sha256(), InvoiceDiagnostics.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT)));
        Files.writeString(inputs.resolve("0001.txt"), "changed"); assertThrows(RuntimeException.class, () -> adapter().run(id));
    }
}
