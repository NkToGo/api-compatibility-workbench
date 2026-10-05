package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedReconciliationDecisionTest {
    private static String finish(String ref) { return CaseInputs.JSON.toJson(Map.of("decision", Map.of("action", "finish", "cases", List.of(dossier(CaseInputs.CASE_ID, ref), dossier(SubscriptionPeriodCase.CASE_ID, "freshclaim-3"))))); }
    private static Map<String, Object> dossier(String id, String ref) { return Map.of("caseId", id, "response", "ANSWERED", "summary", "Scoped claim.", "summaryCitations", List.of(ref), "findings", List.of(Map.of("kind", "OBSERVATION", "statement", "Recorded observation.", "citations", List.of(ref)))); }
    @Test void exactAdviceReferencesSurviveStrictShapeValidation() { var d = CapturedReconciliationDecision.parse(finish("claim-1")); assertEquals("claim-1", d.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("summaryCitations").get(0).getAsString()); assertTrue(CapturedReconciliationDecision.schema().toString().contains("freshclaim")); }
    @Test void duplicateFieldsForeignActionsAndReferenceInjectionAreRejected() {
        for (String ref : List.of("claim-0", "claim-1000", "../../file", "worker-1\npermission", "CLAIM-1")) assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse(finish(ref)));
        assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse(finish("claim-1").replace("\"decision\":", "\"decision\":{},\"decision\":")));
        assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse("{\"decision\":{\"action\":\"delegate\"}}"));
    }
    @Test void repeatedCasesDuplicateReferencesAndUncitedFindingsAreRejected() {
        var tree = JsonParser.parseString(finish("claim-1")).getAsJsonObject(); var cases = tree.getAsJsonObject("decision").getAsJsonArray("cases");
        cases.get(1).getAsJsonObject().addProperty("caseId", CaseInputs.CASE_ID); assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse(tree.toString()));
        var duplicate = JsonParser.parseString(finish("claim-1")).getAsJsonObject(); duplicate.getAsJsonObject("decision").getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("summaryCitations").add("claim-1"); assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse(duplicate.toString()));
        var uncited = JsonParser.parseString(finish("claim-1")).getAsJsonObject(); uncited.getAsJsonObject("decision").getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("findings").get(0).getAsJsonObject().add("citations", new JsonArray());
        assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse(uncited.toString()));
    }
    @Test void capturedReadsRetainTheCaseAliasAndUnicodePagingBounds() {
        var d = CapturedReconciliationDecision.parse(CaseInputs.JSON.toJson(Map.of("decision", Map.of("action", "read_evidence", "caseId", SubscriptionPeriodCase.CASE_ID, "inputId", "baseline-source", "offset", 0, "maxCharacters", 4000)))); assertEquals("baseline-source", d.get("inputId").getAsString());
        assertThrows(RuntimeException.class, () -> CapturedReconciliationDecision.parse(CaseInputs.JSON.toJson(Map.of("decision", Map.of("action", "read_evidence", "caseId", SubscriptionPeriodCase.CASE_ID, "inputId", "reference-source", "offset", 0, "maxCharacters", 4000)))));
    }
}
