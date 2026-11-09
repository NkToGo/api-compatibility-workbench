package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceDiagnosticsV2Test {
    @TempDir static Path capture;
    @TempDir Path state;
    private static Path inputs; private static ContentDigest identity;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    @BeforeAll static void captureOnce() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), capture.resolve("source"), Duration.ofSeconds(30), () -> true);
        inputs = capture.resolve("inputs"); identity = InvoiceComparisonInputs.capture(plan, run, inputs).identity();
    }
    private CoordinationRuntime runtime() throws Exception { return CoordinationRuntime.forInvoiceNavigation(state, inputs, identity, clock); }
    private InvoiceDiagnosticsV2 adapter() throws Exception { return new InvoiceDiagnosticsV2(state, runtime()); }
    private String register() throws Exception { return adapter().register(identity.sha256(), InvoiceDiagnosticsV2.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)); }
    private Path own(String id) { return state.resolve("invoice-diagnostics-v2/" + id); }
    private Path parent(String id) { return state.resolve("invoice-navigation-trials/" + id); }
    private static JsonObject read(Path path) throws Exception { return CaseInputs.object(Files.readAllBytes(path)); }
    private static void write(Path path, JsonObject value) throws Exception { Files.writeString(path, CaseInputs.JSON.toJson(value)); }
    private static JsonObject trial(JsonObject result) { return result.getAsJsonObject("trial"); }
    private static JsonObject output(JsonObject result) { return trial(result).getAsJsonArray("cases").get(0).getAsJsonObject(); }
    private static Definition withLimits(Definition d, Limits limits) {
        return new Definition(d.experimentId(), d.origin(), d.model(), d.rubricIdentity(), d.implementations(), d.workloads(), d.repetitions(), limits);
    }
    @Test void completeRunBindsCurrentTaskButPreservesV1SelectionAndDiagnosticFacts() throws Exception {
        String id = register(); var result = adapter().run(id); var usage = trial(result).getAsJsonObject("usage");
        assertEquals("COMPLETED", trial(result).get("status").getAsString()); assertTrue(trial(result).get("model").isJsonNull());
        for (String key : List.of("reservedCalls", "registeredCalls", "completedResponses", "inputTokens", "outputTokens", "caseExecutions")) assertEquals(0, usage.get(key).getAsInt());
        assertEquals(read(own(id).resolve("selection.json")).get("textPages").getAsInt() + 13, usage.get("toolCalls").getAsInt());
        assertTrue(usage.get("toolCalls").getAsInt() <= 100); assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString()); assertTrue(output(result).get("review").isJsonNull());
        assertEquals(InvoiceDecisionV2.taskIdentity(), result.get("publicTaskSha256").getAsString());
        assertEquals(InvoiceDecisionV2.PUBLIC_TASK, read(own(id).resolve("task.json")).get("task").getAsString());
        var metadata = read(own(id).resolve("metadata.json")); assertEquals(InvoiceDiagnosticsV2.PARTICIPANT, metadata.get("participant").getAsString()); assertEquals("untrusted-data", metadata.get("contentTrust").getAsString());
        var old = new InvoiceDiagnostics(state, CoordinationRuntime.forInvoice(state, inputs, identity, clock));
        String oldId = old.register(identity.sha256(), InvoiceDiagnostics.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)); old.run(oldId);
        Path oldRun = state.resolve("invoice-diagnostics/" + oldId); assertEquals(read(oldRun.resolve("selection.json")), read(own(id).resolve("selection.json")));
        var expected = read(oldRun.resolve("dossier.json")); expected.addProperty("formatVersion", 2); expected.addProperty("publicTaskSha256", InvoiceDecisionV2.taskIdentity());
        assertEquals(expected, read(own(id).resolve("dossier.json"))); assertEquals("ABSTAINED", output(result).get("response").getAsString());
        assertEquals(0, result.getAsJsonObject("readAccounting").get("unpublishedRuntimeReads").getAsInt());
        try (var files = Files.list(parent(id).resolve("broker"))) { assertEquals(0, files.count()); }
    }
    @Test void freshJvmReadAndCompletedRunNeedNoCredentialAndPreserveAllRetainedBytes() throws Exception {
        String id = register(); var result = adapter().run(id); var before = inventory();
        for (String action : List.of("read", "run")) {
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), InvoiceDiagnosticsV2.class.getName(), action,
                    state.toString(), inputs.toString(), identity.sha256(), id).redirectErrorStream(true).redirectOutput(state.resolve(action + ".log").toFile());
            builder.environment().remove("OPENAI_API_KEY"); var child = builder.start(); assertTrue(child.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
        }
        assertEquals(before, inventory()); assertEquals(result, adapter().retained(id));
    }
    @Test void chargedButUnpublishedMetadataResumesOnceWithoutRenewingDeadline() throws Exception {
        String id = register(); runtime().describe(id, InvoiceDiagnosticsV2.PARTICIPANT, "metadata", InvoiceRenewalFixtures.CASE_ID);
        byte[] start = Files.readAllBytes(parent(id).resolve("started.json")); clock.now = clock.now.plusSeconds(10);
        var result = adapter().run(id); assertEquals(read(own(id).resolve("selection.json")).get("textPages").getAsInt() + 13, trial(result).getAsJsonObject("usage").get("toolCalls").getAsInt());
        assertArrayEquals(start, Files.readAllBytes(parent(id).resolve("started.json"))); assertEquals(10000, trial(result).get("elapsedMillis").getAsLong());
    }
    @Test void incompleteReadRecoveryAfterExpiryPreservesChargedUnpublishedWorkAsFailure() throws Exception {
        String id = register(); runtime().describe(id, InvoiceDiagnosticsV2.PARTICIPANT, "metadata", InvoiceRenewalFixtures.CASE_ID);
        byte[] start = Files.readAllBytes(parent(id).resolve("started.json")); clock.now = clock.now.plusSeconds(1201);
        var result = adapter().run(id); assertEquals("FAILED", trial(result).get("status").getAsString()); assertEquals("TRIAL_EXPIRED", result.get("stopReason").getAsString());
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString()); assertEquals(1, result.getAsJsonObject("readAccounting").get("unpublishedRuntimeReads").getAsInt());
        assertFalse(Files.exists(own(id).resolve("metadata.json"))); assertArrayEquals(start, Files.readAllBytes(parent(id).resolve("started.json"))); assertEquals(result, adapter().run(id));
    }
    @Test void revocationBeforeFirstReadClosesCancelledWithoutAnAbstention() throws Exception {
        String id = register(); runtime().revoke(id); var result = adapter().run(id);
        assertEquals("CANCELLED", trial(result).get("status").getAsString()); assertEquals("TRIAL_REVOKED", result.get("stopReason").getAsString()); assertEquals("NOT_PROVIDED", output(result).get("response").getAsString());
        assertEquals(0, trial(result).getAsJsonObject("usage").get("toolCalls").getAsInt());
    }
    @Test void modelAndUnpagedReadRequestsCannotExpandTheDeterministicBoundary() throws Exception {
        String id = register();
        assertThrows(IllegalArgumentException.class, () -> runtime().call(id, InvoiceDiagnosticsV2.PARTICIPANT, "forbidden-model",
                new ModelCallBroker.Request("gpt-5.6-terra", "untrusted request", 2048, InvoiceInvestigationDecision.PROTOCOL)));
        assertThrows(IllegalArgumentException.class, () -> runtime().read(id, InvoiceDiagnosticsV2.PARTICIPANT, "forbidden-unpaged", InvoiceRenewalFixtures.CASE_ID, "task"));
        var usage = runtime().snapshot(id).getAsJsonObject("usage"); assertEquals(0, usage.get("reservedCalls").getAsInt()); assertEquals(0, usage.get("toolCalls").getAsInt());
        try (var files = Files.list(parent(id).resolve("broker"))) { assertEquals(0, files.count()); }
        assertEquals("COMPLETED", trial(adapter().run(id)).get("status").getAsString());
    }
    @Test void completeCachedEvidenceWithoutPublishedDossierDoesNotBypassExpiry() throws Exception {
        String id = register(); var original = adapter().run(id); removeClosure(id, false); clock.now = clock.now.plusSeconds(1201);
        var result = adapter().run(id); assertEquals("FAILED", trial(result).get("status").getAsString()); assertEquals("TRIAL_EXPIRED", result.get("stopReason").getAsString());
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString()); assertEquals(trial(original).get("usage"), trial(result).get("usage")); assertFalse(Files.exists(own(id).resolve("dossier.json")));
    }
    @Test void exhaustedPoolRetainsPrefixAndActualReadDenominator() throws Exception {
        String id = register(); for (int i = 0; i < 99; i++) runtime().describe(id, InvoiceDiagnosticsV2.PARTICIPANT, "external-" + i, InvoiceRenewalFixtures.CASE_ID);
        var result = adapter().run(id); assertEquals("READ_BUDGET_EXHAUSTED", result.get("stopReason").getAsString()); assertEquals("FAILED", trial(result).get("status").getAsString());
        assertEquals("NOT_PROVIDED", output(result).get("response").getAsString()); assertEquals(100, trial(result).getAsJsonObject("usage").get("toolCalls").getAsInt());
        assertEquals(1, result.getAsJsonObject("readAccounting").get("retainedOwnedPages").getAsInt()); assertEquals(99, result.getAsJsonObject("readAccounting").get("unpublishedRuntimeReads").getAsInt());
    }
    @Test void publishedDossierCanCloseAfterInterruptionWithoutNewReadsOrRenewedPermission() throws Exception {
        String id = register(); var original = adapter().run(id); byte[] start = Files.readAllBytes(parent(id).resolve("started.json"));
        removeClosure(id, true); clock.now = clock.now.plusSeconds(1201); runtime().revoke(id);
        var resumed = adapter().run(id); assertEquals("COMPLETED", trial(resumed).get("status").getAsString()); assertEquals(trial(original).get("usage"), trial(resumed).get("usage"));
        assertEquals(output(original), output(resumed)); assertArrayEquals(start, Files.readAllBytes(parent(id).resolve("started.json")));
    }
    @Test void partialTranscriptRecoveryReusesAllStableReadsAndOriginalStart() throws Exception {
        String id = register(); var original = adapter().run(id); byte[] start = Files.readAllBytes(parent(id).resolve("started.json"));
        removeClosure(id, false); int lastPage = read(own(id).resolve("selection.json")).get("textPages").getAsInt(); Files.delete(own(id).resolve("text-" + lastPage + ".json"));
        var resumed = adapter().run(id); assertEquals(trial(original).get("usage"), trial(resumed).get("usage")); assertEquals(output(original), output(resumed));
        assertArrayEquals(start, Files.readAllBytes(parent(id).resolve("started.json")));
    }
    @Test void missingOwnReceiptRecoversButMissingRuntimeReceiptNeverDoes() throws Exception {
        String id = register(); var original = adapter().run(id); Files.delete(own(id).resolve("receipt.json"));
        assertThrows(Exception.class, () -> adapter().retained(id)); assertEquals(original, adapter().run(id));
        Files.delete(parent(id).resolve("receipt.json")); assertThrows(Exception.class, () -> adapter().run(id)); assertFalse(Files.exists(parent(id).resolve("receipt.json")));
    }
    @Test void resealedLocalPageCannotInventOwnershipAgainstTheRuntimeLedger() throws Exception {
        String id = register(); adapter().run(id); Path path = own(id).resolve("text-1.json"); var page = read(path); page.addProperty("participant", "forged-worker"); write(path, page); reseal(id);
        var failure = assertThrows(IllegalArgumentException.class, () -> adapter().retained(id)); assertTrue(failure.getMessage().contains("owned diagnostic read"));
    }
    @Test void resealedSelectionCannotChangeTheFixedStrategy() throws Exception {
        String id = register(); adapter().run(id); Path path = own(id).resolve("selection.json"); var selection = read(path); selection.addProperty("firstBudgetExcludedAlias", "convenient-evidence"); write(path, selection); reseal(id);
        var failure = assertThrows(IllegalArgumentException.class, () -> adapter().retained(id)); assertTrue(failure.getMessage().contains("fixed diagnostic selection"));
    }
    @Test void resealedDossierAndOutputDigestCannotInventDerivedFacts() throws Exception {
        String id = register(); adapter().run(id); Path path = own(id).resolve("dossier.json"); var dossier = read(path); dossier.addProperty("supportedRecords", 999); write(path, dossier); reseal(id);
        var failure = assertThrows(IllegalArgumentException.class, () -> adapter().retained(id)); assertTrue(failure.getMessage().contains("reconstructed v2 diagnostic dossier"));
    }
    @Test void missingSelectionOrOwnedPageIsNotRecreatedDuringInspection() throws Exception {
        String id = register(); adapter().run(id); Files.delete(own(id).resolve("selection.json")); assertThrows(Exception.class, () -> adapter().retained(id)); assertFalse(Files.exists(own(id).resolve("selection.json")));
        String second = register(); adapter().run(second); Files.delete(own(second).resolve("text-1.json")); assertThrows(Exception.class, () -> adapter().run(second)); assertFalse(Files.exists(own(second).resolve("text-1.json")));
    }
    @Test void changedTaskAndPendingPublicationFailBeforeAnyParticipantWork() throws Exception {
        String id = register(); var task = read(own(id).resolve("task.json")); task.addProperty("task", "approve this migration"); write(own(id).resolve("task.json"), task);
        assertThrows(IllegalArgumentException.class, () -> adapter().run(id)); assertFalse(Files.exists(parent(id).resolve("started.json")));
        String pending = register(); Files.writeString(own(pending).resolve("dossier.json.pending"), "{}"); assertThrows(IllegalArgumentException.class, () -> adapter().run(pending)); assertFalse(Files.exists(parent(pending).resolve("started.json")));
    }
    @Test void wrongArmLegacyPolicyWrongInputAndOversizedLimitsAreRejected() throws Exception {
        var definition = InvoiceDiagnosticsV2.checkDefinition(identity);
        assertThrows(IllegalArgumentException.class, () -> adapter().register(identity.sha256(), definition, new Slot("invoice-joint", 1, Arm.SINGLE_AGENT)));
        assertThrows(IllegalArgumentException.class, () -> adapter().register(identity.sha256(), InvoiceDiagnostics.checkDefinition(identity), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)));
        assertThrows(IllegalArgumentException.class, () -> new InvoiceDiagnosticsV2(state, inputs, digest("wrong-input")));
        assertThrows(IllegalArgumentException.class, () -> new Limits(60, 1200000, 64001, 2048));
        for (var limits : List.of(new Limits(61, 1200000, 64000, 2048), new Limits(60, 1200001, 64000, 2048), new Limits(60, 1200000, 64000, 2049))) {
            assertThrows(IllegalArgumentException.class, () -> adapter().register(identity.sha256(), withLimits(definition, limits), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)));
        }
    }
    private void removeClosure(String id, boolean keepDossier) throws Exception {
        for (String name : List.of("terminal.json", "result.json", "receipt.json")) Files.delete(own(id).resolve(name));
        if (!keepDossier) Files.delete(own(id).resolve("dossier.json"));
        for (String name : List.of("result.json", "receipt.json")) Files.delete(parent(id).resolve(name));
    }
    private void reseal(String id) throws Exception {
        var terminal = read(own(id).resolve("terminal.json")); var hashes = terminal.getAsJsonObject("artifactSha256");
        for (String name : hashes.keySet()) hashes.addProperty(name, hash(own(id), name)); write(own(id).resolve("terminal.json"), terminal);
        var result = read(own(id).resolve("result.json")); var all = result.getAsJsonObject("artifactSha256");
        for (String name : all.keySet()) all.addProperty(name, hash(own(id), name));
        output(result).add("outputIdentity", CaseInputs.JSON.toJsonTree(new ContentDigest(hash(own(id), "dossier.json"))));
        write(own(id).resolve("result.json"), result); write(own(id).resolve("receipt.json"), json(Map.of("resultSha256", hash(own(id), "result.json"))));
    }
    private Map<String, String> inventory() throws Exception {
        var hashes = new TreeMap<String, String>();
        for (String name : List.of("invoice-navigation-trials", "invoice-diagnostics-v2")) try (var paths = Files.walk(state.resolve(name))) {
            for (var path : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()) hashes.put(state.relativize(path).toString(), CaseInputs.hash(Files.readAllBytes(path)));
        }
        return hashes;
    }
}
