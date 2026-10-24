package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceCampaignTest {
    @TempDir Path root;
    private Path state, directory;
    private InvoiceCampaign campaigns;
    private InvoiceCampaign.Selection selection;
    private String id;
    private void fixture() throws Exception {
        state = Files.createDirectory(root.resolve("state")); campaigns = new InvoiceCampaign(root, state);
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); Path capture = Files.createDirectory(root.resolve("capture"));
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), capture.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        var identity = InvoiceComparisonInputs.capture(plan, run, capture.resolve("inputs")).identity();
        Path contract = Files.createDirectory(root.resolve("protected")); var hashes = new TreeMap<String, String>();
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json")) {
            byte[] content = "Synthetic contract fixture; not measured findings.".getBytes(StandardCharsets.UTF_8);
            Files.write(contract.resolve(name), content); hashes.put(name, CaseInputs.hash(content));
        }
        byte[] rubric = Files.readAllBytes(Path.of(System.getProperty("workbench.repository"), "docs/evaluations/invoice-review-rubric-v1.json"));
        Files.write(root.resolve("rubric.json"), rubric);
        selection = new InvoiceCampaign.Selection(1, "capture/inputs", identity, "protected", new ContentDigest(CaseInputs.identity(hashes)), "rubric.json", new ContentDigest(CaseInputs.hash(rubric)));
        id = campaigns.freeze(selection); directory = state.resolve("invoice-campaigns").resolve(id);
    }
    private static JsonObject read(Path path) throws Exception { return InvoiceReviewInputs.object(Files.readAllBytes(path)); }
    private static void write(Path path, JsonObject value) throws Exception { Files.writeString(path, CaseInputs.JSON.toJson(value)); }
    private static Map<String, String> inventory(Path path) throws Exception {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(path)) { for (Path file : files.filter(Files::isRegularFile).toList()) if (!file.getFileName().toString().endsWith(".lock")) result.put(path.relativize(file).toString(), CaseInputs.hash(Files.readAllBytes(file))); }
        return result;
    }
    private void mutate(Consumer<JsonObject> action) throws Exception {
        var frozen = read(directory.resolve("frozen.json")); action.accept(frozen); write(directory.resolve("frozen.json"), frozen);
        var receipt = read(directory.resolve("receipt.json")); receipt.addProperty("frozenSha256", CaseInputs.hash(Files.readAllBytes(directory.resolve("frozen.json")))); write(directory.resolve("receipt.json"), receipt);
    }
    @Test void sealedRegistrationBindsEverySlotWithoutDispatchOrChangingEvidence() throws Exception {
        fixture(); var before = inventory(root); var registration = campaigns.read(id);
        assertEquals(12, registration.get("plannedTrials").getAsInt()); assertEquals(12, registration.get("plannedCaseOutputs").getAsInt());
        assertEquals(0, registration.get("participantCallsInitiatedByThisCommand").getAsInt()); assertEquals("NOT_ESTABLISHED_BY_REGISTRATION", registration.get("qualityReview").getAsString());
        assertEquals(id, campaigns.freeze(id, selection)); campaigns.verifyCurrentPolicies(id); assertEquals(before, inventory(root));
        var frozen = read(directory.resolve("frozen.json")); var order = frozen.getAsJsonArray("order");
        for (int rep = 1; rep <= 3; rep++) {
            var arms = new HashSet<String>();
            for (int offset = 0; offset < 4; offset++) {
                var slot = order.get((rep - 1) * 4 + offset).getAsJsonObject(); assertEquals(rep, slot.get("repetition").getAsInt());
                assertEquals(Arm.values()[(offset + rep - 1) % 4].name(), slot.get("arm").getAsString()); arms.add(slot.get("arm").getAsString());
            }
            assertEquals(4, arms.size());
        }
        var definition = frozen.getAsJsonObject("definition"); assertEquals(60, definition.getAsJsonObject("limits").get("modelCalls").getAsInt());
        assertEquals(6, definition.getAsJsonArray("workloads").get(0).getAsJsonObject().getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("criteria").size());
        for (String store : List.of("invoice-comparison-trials", "invoice-single-agent", "invoice-independent-workers", "invoice-team-comparisons", "model-calls", "coordination-campaigns")) assertFalse(Files.exists(state.resolve(store)));
    }
    @Test void changedCapturedInputContractAndRubricCannotBeReadOrResumed() throws Exception {
        fixture();
        for (Path path : List.of(root.resolve("capture/inputs/manifest.json"), root.resolve("protected/business-oracle.md"), root.resolve("rubric.json"))) {
            byte[] original = Files.readAllBytes(path); Files.writeString(path, " ", StandardOpenOption.APPEND);
            assertThrows(Exception.class, () -> campaigns.read(id)); assertThrows(Exception.class, () -> campaigns.freeze(id, null)); Files.write(path, original);
        }
        campaigns.read(id);
    }
    @Test void resealedModelLimitsOrderAndCriterionChangesAreRejected() throws Exception {
        fixture(); byte[] frozen = Files.readAllBytes(directory.resolve("frozen.json")), receipt = Files.readAllBytes(directory.resolve("receipt.json"));
        List<Consumer<JsonObject>> attacks = List.of(
                f -> f.getAsJsonObject("definition").addProperty("model", "different-model"),
                f -> f.getAsJsonObject("definition").getAsJsonObject("limits").addProperty("modelCalls", 61),
                f -> f.getAsJsonArray("order").remove(11),
                f -> f.getAsJsonArray("order").set(1, f.getAsJsonArray("order").get(0).deepCopy()),
                f -> f.getAsJsonArray("order").set(0, f.getAsJsonArray("order").get(1).deepCopy()),
                f -> f.getAsJsonObject("definition").getAsJsonArray("workloads").get(0).getAsJsonObject().getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("criteria").remove(0),
                f -> f.getAsJsonObject("policies").getAsJsonObject("SINGLE_AGENT").addProperty("maxReads", 101),
                f -> f.getAsJsonObject("teamAllocation").addProperty("review", 7));
        for (var attack : attacks) {
            mutate(attack); assertThrows(Exception.class, () -> campaigns.read(id));
            Files.write(directory.resolve("frozen.json"), frozen); Files.write(directory.resolve("receipt.json"), receipt);
        }
        campaigns.read(id);
    }
    @Test void missingMappingOrReceiptRecoversSameComparisonWithoutReplacement() throws Exception {
        fixture(); var before = inventory(root); String comparison = campaigns.read(id).get("comparisonId").getAsString();
        Files.delete(directory.resolve("registration.json")); Files.delete(directory.resolve("receipt.json"));
        assertThrows(Exception.class, () -> campaigns.read(id)); assertEquals(id, campaigns.freeze(id, null));
        assertEquals(comparison, campaigns.read(id).get("comparisonId").getAsString()); assertEquals(before, inventory(root));
        try (var files = Files.list(state.resolve("coordination-comparisons"))) { assertEquals(1, files.count()); }
    }
    @Test void reorderedReceiptSurvivesSeparateProcessResumeWithoutRewritingBytes() throws Exception {
        fixture(); Path receiptPath = directory.resolve("receipt.json"); var receipt = read(receiptPath); var reversed = new JsonObject();
        var keys = new ArrayList<>(receipt.keySet()); Collections.reverse(keys); keys.forEach(key -> reversed.add(key, receipt.get(key)));
        write(receiptPath, reversed); byte[] before = Files.readAllBytes(receiptPath); campaigns.read(id);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var builder = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), InvoiceCampaign.class.getName(),
                "resume", root.toString(), state.toString(), id).redirectErrorStream(true).redirectOutput(root.resolve("resume-output.txt").toFile());
        builder.environment().remove("OPENAI_API_KEY"); var child = builder.start();
        try { assertTrue(child.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), "Fresh-process invoice resume must retain a semantically equal receipt"); }
        finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        assertArrayEquals(before, Files.readAllBytes(receiptPath)); campaigns.read(id);
    }
    @Test void frozenOnlyInterruptionCompletesOriginalConditions() throws Exception {
        fixture(); String comparison = campaigns.read(id).get("comparisonId").getAsString();
        Files.delete(directory.resolve("registration.json")); Files.delete(directory.resolve("receipt.json")); Files.delete(directory.resolve("plan.json")); Files.delete(directory.resolve("rubric.json"));
        assertEquals(id, campaigns.freeze(id, null)); assertEquals(comparison, campaigns.read(id).get("comparisonId").getAsString());
    }
    @Test void ambiguousOrIncompleteOrphanComparisonCannotCauseAnotherRegistration() throws Exception {
        fixture(); String comparison = campaigns.read(id).get("comparisonId").getAsString();
        Files.delete(directory.resolve("registration.json")); Files.delete(directory.resolve("receipt.json"));
        Path original = state.resolve("coordination-comparisons").resolve(comparison), duplicate = Files.createDirectory(original.getParent().resolve("comparison-" + UUID.randomUUID()));
        Files.copy(original.resolve("plan.json"), duplicate.resolve("plan.json"));
        assertThrows(Exception.class, () -> campaigns.freeze(id, null)); Files.delete(duplicate.resolve("plan.json")); Files.delete(duplicate);
        Files.delete(original.resolve("registration.json")); assertThrows(Exception.class, () -> campaigns.freeze(id, null));
        try (var files = Files.list(original.getParent())) { assertEquals(1, files.count()); }
    }
    @Test void changedComparisonAndUnexpectedOrPendingFilesAreNotAccepted() throws Exception {
        fixture(); String comparison = campaigns.read(id).get("comparisonId").getAsString(); Path parent = state.resolve("coordination-comparisons").resolve(comparison).resolve("plan.json");
        byte[] saved = Files.readAllBytes(parent); Files.writeString(parent, " ", StandardOpenOption.APPEND); assertThrows(Exception.class, () -> campaigns.read(id)); Files.write(parent, saved);
        for (String name : List.of("unexpected.json", "frozen.json.pending")) {
            Files.writeString(directory.resolve(name), "{}"); assertThrows(Exception.class, () -> campaigns.read(id)); assertThrows(Exception.class, () -> campaigns.freeze(id, null)); Files.delete(directory.resolve(name));
        }
        assertThrows(Exception.class, () -> campaigns.read("../escape"));
    }
    @Test void activePublisherAndLinkedEvidenceCannotBeAdopted() throws Exception {
        fixture();
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) { assertNotNull(lease); assertThrows(Exception.class, () -> campaigns.freeze(id, null)); }
        Path link = root.resolve("protected/link");
        try { Files.createSymbolicLink(link, root.resolve("rubric.json")); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException e) { return; }
        assertThrows(Exception.class, () -> campaigns.read(id));
    }
    @Test void duplicateJsonFieldsInvalidUtf8AndOverlappingSelectionsAreRejected() throws Exception {
        fixture(); byte[] original = Files.readAllBytes(directory.resolve("frozen.json"));
        Files.writeString(directory.resolve("frozen.json"), "{\"formatVersion\":1,\"formatVersion\":1}"); assertThrows(Exception.class, () -> campaigns.read(id));
        Files.write(directory.resolve("frozen.json"), new byte[] {(byte) 0xff}); assertThrows(Exception.class, () -> campaigns.read(id)); Files.write(directory.resolve("frozen.json"), original);
        var overlapping = new InvoiceCampaign.Selection(1, selection.inputDirectory(), selection.inputIdentity(), "state", selection.contractIdentity(), selection.rubricArtifact(), selection.rubricIdentity());
        assertThrows(Exception.class, () -> campaigns.freeze(id, overlapping));
        assertThrows(Exception.class, () -> new InvoiceCampaign.Selection(1, "../escape", selection.inputIdentity(), "protected", selection.contractIdentity(), "rubric.json", selection.rubricIdentity()));
    }
    @Test void historicalPolicySnapshotCanBeInspectedButCannotAuthorizeChangedCode() throws Exception {
        fixture();
        mutate(f -> {
            var policy = f.getAsJsonObject("policies").getAsJsonObject("SINGLE_AGENT"); policy.getAsJsonObject("promptIdentity").addProperty("sha256", "e".repeat(64));
            var decoded = CaseInputs.JSON.fromJson(policy, CoordinationRuntime.Policy.class);
            f.getAsJsonObject("definition").getAsJsonObject("implementations").add("SINGLE_AGENT", CaseInputs.JSON.toJsonTree(CoordinationRuntime.implementationIdentity(decoded)));
        });
        // Rebind a synthetic historical plan and mapping, then exercise the separate live-policy gate.
        var frozen = read(directory.resolve("frozen.json")); var plan = CaseInputs.JSON.toJsonTree(new CoordinationComparisonReport.Plan(1, CaseInputs.JSON.fromJson(frozen.get("definition"), Definition.class))).getAsJsonObject();
        byte[] encoded = (new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create().toJson(plan) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("plan.json"), encoded);
        var mapping = read(directory.resolve("registration.json")); Path comparison = state.resolve("coordination-comparisons").resolve(mapping.get("comparisonId").getAsString()); Files.write(comparison.resolve("plan.json"), encoded);
        var registration = read(comparison.resolve("registration.json")); registration.addProperty("planSha256", CaseInputs.hash(encoded)); registration.add("definitionIdentity", CaseInputs.JSON.toJsonTree(identity(CaseInputs.JSON.fromJson(frozen.get("definition"), Definition.class)))); write(comparison.resolve("registration.json"), registration);
        mapping.addProperty("comparisonPlanSha256", CaseInputs.hash(encoded)); mapping.addProperty("comparisonRegistrationSha256", CaseInputs.hash(Files.readAllBytes(comparison.resolve("registration.json")))); write(directory.resolve("registration.json"), mapping);
        var receipt = read(directory.resolve("receipt.json")); receipt.addProperty("planSha256", CaseInputs.hash(encoded)); receipt.addProperty("registrationSha256", CaseInputs.hash(Files.readAllBytes(directory.resolve("registration.json")))); write(directory.resolve("receipt.json"), receipt);
        campaigns.read(id); assertThrows(Exception.class, () -> campaigns.verifyCurrentPolicies(id));
    }
}
