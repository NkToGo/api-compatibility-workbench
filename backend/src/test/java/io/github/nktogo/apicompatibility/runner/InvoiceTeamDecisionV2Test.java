package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTeamDecisionV2Test {
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static String raw(JsonObject value) { return CaseInputs.JSON.toJson(Map.of("decision", value)); }
    private static JsonObject finish() { return json(Map.of("action", "finish", "summary", "Advisory review", "citations", List.of("worker-1"), "followUps", List.of(), "conflicts", List.of())); }
    private static JsonObject question(String worker) { return json(Map.of("workerId", worker, "question", "What evidence remains missing?", "citations", List.of("worker-1"))); }
    @Test void fourStrictActionsIncludeBoundedPrefixNavigationAndTheExistingInertFinishShape() {
        for (var d : List.of(finish(), json(Map.of("action", "catalog", "offset", 0, "limit", 40)),
                json(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 40)),
                json(Map.of("action", "read_text", "alias", "task", "offset", 0, "maxCharacters", 4000)))) assertEquals(d, InvoiceTeamDecisionV2.parse(raw(d)));
        assertEquals(4, InvoiceTeamDecisionV2.schema().getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf").size());
        strict(InvoiceTeamDecisionV2.schema());
    }
    private static void strict(JsonElement value) {
        if (value.isJsonObject()) {
            var o = value.getAsJsonObject();
            if (o.has("type") && o.get("type").equals(new JsonPrimitive("object"))) {
                assertFalse(o.get("additionalProperties").getAsBoolean());
                assertEquals(o.getAsJsonObject("properties").keySet(), new HashSet<>(o.getAsJsonArray("required").asList().stream().map(JsonElement::getAsString).toList()));
            }
            o.entrySet().forEach(e -> strict(e.getValue()));
        } else if (value.isJsonArray()) value.getAsJsonArray().forEach(InvoiceTeamDecisionV2Test::strict);
    }
    @Test void malformedDuplicateExtraAuthorityAndOutOfRangeActionsAreRejected() {
        for (String raw : List.of(raw(finish()) + " {}", "{\"decision\":{\"action\":\"finish\",\"action\":\"finish\"}}", "{\"decision\":null}", "{\"decision\":true}"))
            assertThrows(RuntimeException.class, () -> InvoiceTeamDecisionV2.parse(raw));
        var extra = finish(); extra.addProperty("permission", "execute");
        for (var d : List.of(extra, json(Map.of("action", "delegate")), json(Map.of("action", "run_tests")),
                json(Map.of("action", "catalog_prefix", "prefix", "../", "offset", 0, "limit", 40)),
                json(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0.5, "limit", 40)),
                json(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", "0", "limit", 40)),
                json(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 769, "limit", 40)),
                json(Map.of("action", "catalog_prefix", "prefix", "source/", "offset", 0, "limit", 41))))
            assertThrows(RuntimeException.class, () -> InvoiceTeamDecisionV2.parse(raw(d)));
    }
    @Test void duplicateTargetsAndConflictPairsAndModelWrittenResolutionStayInvalid() {
        var duplicates = finish(); duplicates.add("followUps", CaseInputs.JSON.toJsonTree(List.of(question("invoice-reconciliation"), question("invoice-reconciliation"))));
        assertThrows(RuntimeException.class, () -> InvoiceTeamDecisionV2.parse(raw(duplicates)));
        var unknown = finish(); unknown.add("followUps", CaseInputs.JSON.toJsonTree(List.of(question("invented-worker")))); assertThrows(RuntimeException.class, () -> InvoiceTeamDecisionV2.parse(raw(unknown)));
        var conflict = json(Map.of("claimIds", List.of("claim-1", "claim-2"), "statement", "Possible disagreement"));
        var repeated = finish(); repeated.add("conflicts", CaseInputs.JSON.toJsonTree(List.of(conflict, conflict))); assertThrows(RuntimeException.class, () -> InvoiceTeamDecisionV2.parse(raw(repeated)));
        conflict.addProperty("state", "RESOLVED"); var resolved = finish(); resolved.add("conflicts", CaseInputs.JSON.toJsonTree(List.of(conflict))); assertThrows(RuntimeException.class, () -> InvoiceTeamDecisionV2.parse(raw(resolved)));
    }
    @Test void separateContractDoesNotExpandTheExistingBrokerOrV2AdmissionFactories() throws Exception {
        assertFalse(InvoiceDecisionV2.accepts(InvoiceTeamDecisionV2.PROTOCOL));
        assertThrows(RuntimeException.class, () -> new ModelCallBroker.Request("gpt-5.6-terra", "input", 2048, InvoiceTeamDecisionV2.PROTOCOL));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.invoiceInvestigationV2Policy(2, 100, List.of(new CoordinationRuntime.Participant(
                "invoice-coordinator-v2", Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(InvoiceTeamDecisionV2.PROTOCOL)))));
        assertEquals(64, InvoiceTeamDecisionV2.contractIdentity().length());
        assertNotEquals(InvoiceDecisionV2.contractIdentity(InvoiceReadiness.Scope.JOINT), InvoiceTeamDecisionV2.contractIdentity());
        var schema = InvoiceTeamDecisionV2.schema(); schema.addProperty("permission", "execute"); assertFalse(InvoiceTeamDecisionV2.schema().has("permission"));
    }
}
