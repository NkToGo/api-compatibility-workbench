package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationInputsTest {
    @TempDir Path root;
    static String fixture(Path state) throws Exception { return capture(state, new Tools(false), new Tools(true)); }
    private static String capture(Path state, Tools customer, Tools subscription) throws Exception {
        return new CoordinationInputs(state).capture(new CoordinationInputs.Source(state, "investigation-" + UUID.randomUUID(), "recorded"), customer,
                new CoordinationInputs.Source(state, "subscription-investigation-" + UUID.randomUUID(), "recorded"), subscription);
    }
    @Test void capturesOnlyDeclaredInputsAndCompletedEvidenceWithoutRunningAnythingOrImportingGrants() throws Exception {
        var customer = new Tools(false); var subscription = new Tools(true); String bundle = capture(root, customer, subscription); var inputs = new CoordinationInputs(root);
        assertEquals(Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID), inputs.identities(bundle).keySet());
        assertArrayEquals(customer.artifacts.get("baseline-source"), inputs.read(bundle, CaseInputs.CASE_ID, "baseline-source"));
        customer.artifacts.put("baseline-source", "changed original".getBytes(StandardCharsets.UTF_8));
        assertFalse(new String(inputs.read(bundle, CaseInputs.CASE_ID, "baseline-source"), StandardCharsets.UTF_8).contains("changed original"));
        assertEquals(0, customer.executions + subscription.executions);
        try (var files = Files.walk(root.resolve("coordination-inputs/" + bundle))) {
            assertTrue(files.noneMatch(p -> Set.of("grant.json", "candidate.txt", "model-calls", "agents").contains(p.getFileName().toString())));
        }
        assertThrows(IllegalArgumentException.class, () -> inputs.read(bundle, SubscriptionPeriodCase.CASE_ID, "candidate.txt"));
        assertThrows(IllegalArgumentException.class, () -> inputs.read(bundle, CaseInputs.CASE_ID, "../grant.json"));
        assertThrows(IllegalArgumentException.class, () -> inputs.identities("../outside"));
    }
    @Test void reconstructsPagedUnicodeInputWithoutChangingItsIdentity() throws Exception {
        var customer = new Tools(false); customer.artifacts.put("provenance", ("a".repeat(3999) + "😀" + "z".repeat(5000)).getBytes(StandardCharsets.UTF_8));
        String bundle = capture(root, customer, new Tools(true)); assertArrayEquals(customer.artifacts.get("provenance"), new CoordinationInputs(root).read(bundle, CaseInputs.CASE_ID, "provenance"));
    }
    @Test void missingOrChangedEvidenceAndExpandedInventoriesCannotBeCaptured() throws Exception {
        var customer = new Tools(false); customer.status = "ACTIVE";
        assertThrows(IllegalArgumentException.class, () -> capture(root, customer, new Tools(true)));
        customer.status = "COMPLETED"; customer.extraAlias = true;
        assertThrows(IllegalArgumentException.class, () -> capture(root, customer, new Tools(true)));
        customer.extraAlias = false; customer.changeDuringRead = true;
        assertThrows(IllegalArgumentException.class, () -> capture(root, customer, new Tools(true)));
    }
    @Test void alteredCapturedArtifactsOrIncompletePublicationAreRejected() throws Exception {
        String bundle = fixture(root); var inputs = new CoordinationInputs(root); Path directory = root.resolve("coordination-inputs/" + bundle);
        Files.writeString(directory.resolve(CaseInputs.CASE_ID + "/execution"), "{}"); assertThrows(IllegalArgumentException.class, () -> inputs.identities(bundle));
        String other = fixture(root); Files.delete(root.resolve("coordination-inputs/" + other + "/receipt.json"));
        assertThrows(IllegalArgumentException.class, () -> inputs.identities(other));
    }
    static final class Tools implements ConsumerInvestigator.Tools {
        final String caseId; final Map<String, byte[]> artifacts = new TreeMap<>(); int executions; String status = "COMPLETED"; boolean extraAlias, changeDuringRead;
        Tools(boolean subscription) throws Exception {
            caseId = subscription ? SubscriptionPeriodCase.CASE_ID : CaseInputs.CASE_ID;
            Path source = subscription ? SubscriptionVerificationTest.caseRoot() : InvestigationToolsTest.caseRoot();
            for (var entry : (subscription ? SubscriptionInvestigationTools.INPUTS : InvestigationTools.INPUTS).entrySet()) {
                artifacts.put(entry.getKey(), subscription && entry.getKey().equals("baseline-source")
                        ? "synthetic registered baseline source\n".getBytes(StandardCharsets.UTF_8) : Files.readAllBytes(source.resolve(entry.getValue())));
            }
        }
        public JsonObject invoke(String context, String tool, JsonObject args) {
            Object value;
            if (tool.equals("describe_case")) {
                var hashes = new TreeMap<String, String>(); artifacts.forEach((k, v) -> hashes.put(k, CaseInputs.hash(v))); if (extraAlias) hashes.put("grant.json", "0".repeat(64));
                value = Map.of("caseId", caseId, "inputs", hashes, "executionMode", "retained-only");
            } else if (tool.equals("read_run")) value = Map.of("authority", "recorded-execution", "status", status, "observations", Map.of("baseline", List.of(), "target", List.of()));
            else if (tool.equals("read_input")) {
                String alias = args.get("inputId").getAsString(), text = new String(artifacts.get(alias), StandardCharsets.UTF_8); int offset = args.get("offset").getAsInt(), end = Math.min(text.length(), offset + 4000);
                value = Map.of("inputId", alias, "sha256", changeDuringRead ? "0".repeat(64) : CaseInputs.hash(artifacts.get(alias)), "offset", offset, "nextOffset", end, "totalCharacters", text.length(), "content", text.substring(offset, end));
            } else { executions++; throw new AssertionError("Capture must never dispatch execution"); }
            return CaseInputs.JSON.toJsonTree(Map.of("ok", true, "value", value)).getAsJsonObject();
        }
    }
}
