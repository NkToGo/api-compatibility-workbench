package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

class InvoiceSemanticReviewTest {
    private final Map<String, byte[]> files = new HashMap<>();
    private JsonObject packet, review;
    private JsonObject object(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private void file(String path, Object value) { files.put(path, encoded(CaseInputs.JSON.toJsonTree(value)).getBytes(StandardCharsets.UTF_8)); }
    private JsonObject unit(String id, String kind, String artifact, String pointer) {
        var value = InvoiceReviewInputs.pointer(InvoiceReviewInputs.object(files.get(artifact)), pointer);
        return object(Map.of("unitId", id, "kind", kind, "artifact", artifact, "pointer", pointer, "valueIdentity", digest(encoded(value)), "referenceId", "", "directFinalCitation", "NOT_CITED"));
    }
    private void fixture() throws Exception {
        files.put("rubric.json", Files.readAllBytes(Path.of(System.getProperty("workbench.repository"), "docs/evaluations/invoice-review-rubric-v1.json")));
        file("facts.json", Map.of("outcome", "One synthetic observation failed."));
        file("history.json", Map.of("statement", "A historical assertion remains visible.", "conflicts", List.of(Map.of("statement", "A possible conflict remains unresolved."))));
        file("responses.json", Map.of("catalog", "{\"decision\":{\"action\":\"catalog\",\"offset\":0,\"limit\":40}}",
                "finish", "{\"decision\":{\"action\":\"finish\",\"summary\":\"A historical assertion remains visible.\"}}"));
        var rows = new JsonArray(); var arms = List.of("DETERMINISTIC", "SINGLE_AGENT", "INDEPENDENT_WORKERS", "COORDINATED_TEAM");
        for (int i = 0; i < 4; i++) {
            String path = "dossier-" + i + ".json"; file(path, Map.of("summary", "One synthetic observation failed.", "authority", "model-advice-not-semantic-acceptance"));
            var units = new JsonArray(); units.add(unit("unit-1", "FINAL_DOSSIER", path, "")); units.add(unit("unit-2", "SUMMARY", path, "/summary"));
            units.add(unit("unit-3", "DOSSIER_SECTION", path, "/authority")); units.add(unit("unit-4", "MODEL_RESPONSE", "responses.json", "/catalog"));
            units.add(unit("unit-5", "ORIGINAL_CLAIM", "history.json", "")); units.add(unit("unit-6", "MODEL_RESPONSE", "responses.json", "/finish"));
            units.add(unit("unit-7", "TEAM_CONTEXT", "history.json", "/conflicts"));
            var output = object(Map.of("caseId", InvoiceRenewalFixtures.CASE_ID, "response", "ANSWERED", "outputIdentity", digest(encoded(InvoiceReviewInputs.object(files.get(path))))));
            rows.add(object(Map.of("position", i + 1, "source", Map.of("arm", arms.get(i), "stateDirectory", "arm-" + i, "runId", "trial-" + UUID.randomUUID(), "resultIdentity", digest("result-" + i)),
                    "trial", Map.of("status", "COMPLETED", "cases", List.of(output)), "units", units)));
        }
        var artifacts = new TreeMap<String, String>(); files.forEach((name, bytes) -> artifacts.put(name, CaseInputs.hash(bytes)));
        packet = object(Map.of("packetId", "reviewpacket-00000000-0000-0000-0000-000000000001", "inventory", Map.of("sourceArtifacts", artifacts, "rubricArtifact", "rubric.json",
                "rubricIdentity", new ContentDigest(artifacts.get("rubric.json")), "contractIdentity", digest("contract"), "criteriaTiming", "POST_HOC_FOR_EXISTING_OPERATIONAL_TRIALS", "trials", rows)));
        review = InvoiceSemanticReview.draft(packet, digest("independently selected packet bytes"));
    }
    private JsonObject row(int i) { return review.getAsJsonArray("trials").get(i).getAsJsonObject(); }
    private JsonObject item(int row, int unit) { return packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(row).getAsJsonObject().getAsJsonArray("units").get(unit).getAsJsonObject(); }
    private JsonObject reviewedUnit(int row, int unit) { return row(row).getAsJsonArray("units").get(unit).getAsJsonObject(); }
    private JsonObject judgment(int row, int unit) { return reviewedUnit(row, unit).getAsJsonArray("claims").get(0).getAsJsonObject(); }
    private JsonObject check() throws IOException { return InvoiceSemanticReview.validate(review, packet, files::get); }
    private void complete() {
        var reviewer = review.getAsJsonObject("reviewer"); reviewer.addProperty("method", "model-assisted-offline-inspection"); reviewer.addProperty("reviewedOn", "2026-10-24"); reviewer.addProperty("label", "Synthetic binding control; not a semantic performance measurement");
        String fact = "facts.json"; review.getAsJsonArray("evidence").add(object(Map.of("evidenceId", "fact", "artifact", fact, "sha256", CaseInputs.hash(files.get(fact)), "pointer", "/outcome", "valueIdentity", digest(encoded(new JsonPrimitive("One synthetic observation failed."))))));
        for (int r = 0; r < 4; r++) {
            row(r).addProperty("coverageComplete", true);
            for (int u = 0; u < 7; u++) {
                var unit = reviewedUnit(r, u); unit.addProperty("notes", "Synthetic declared inspection, not reviewer accuracy evidence.");
                if (u == 0) { unit.addProperty("coverage", "CONTAINER"); unit.add("coveredBy", CaseInputs.JSON.toJsonTree(List.of("unit-2", "unit-3"))); }
                else if (u == 2 || u == 3) unit.addProperty("coverage", "CONTEXT");
                else {
                    unit.addProperty("coverage", "CLAIMS"); var source = item(r, u); JsonElement value = InvoiceReviewInputs.pointer(InvoiceReviewInputs.object(files.get(source.get("artifact").getAsString())), source.get("pointer").getAsString());
                    String pointer = u == 4 ? "/statement" : u == 5 ? "/decision/summary" : u == 6 ? "/0/statement" : "";
                    if (u == 5) value = InvoiceReviewInputs.object(value.getAsString().getBytes(StandardCharsets.UTF_8));
                    var claim = object(Map.of("claimId", "claim-1", "anchor", Map.of("representation", u == 5 ? "JSON_TEXT" : "NATIVE", "pointer", pointer, "valueIdentity", digest(encoded(InvoiceReviewInputs.pointer(value, pointer)))),
                            "proposition", "Synthetic proposition under the recorded scope.", "verdict", "SUPPORTED_WITHIN_RECORDED_SCOPE", "material", true, "endorsed", u == 1, "evidenceIds", List.of("fact"), "rationale", "Synthetic recorded-fact binding only."));
                    unit.getAsJsonArray("claims").add(claim);
                }
            }
            for (var criterion : row(r).getAsJsonArray("criteria")) {
                var c = criterion.getAsJsonObject(); c.addProperty("judgment", "SATISFIED"); c.addProperty("rationale", "Synthetic declared criterion inspection."); c.add("unitIds", CaseInputs.JSON.toJsonTree(List.of("unit-2"))); c.add("evidenceIds", CaseInputs.JSON.toJsonTree(List.of("fact")));
            }
        }
    }
    private boolean accepted(JsonObject result, int row) { return result.getAsJsonArray("trials").get(row).getAsJsonObject().get("satisfiesDeclaredPostHocReview").getAsBoolean(); }

    @Test void pendingDraftPreservesAllUnitsAndCannotEstablishAnyAcceptance() throws Exception {
        fixture(); var result = check(); assertEquals(28, result.get("pendingUnits").getAsInt()); assertEquals(24, result.get("pendingCriteria").getAsInt()); assertEquals(0, result.get("claimJudgments").getAsInt());
        assertEquals("NOT_ESTABLISHED", result.get("campaignAcceptance").getAsString()); for (int i = 0; i < 4; i++) assertFalse(accepted(result, i));
    }
    @Test void completeSuppliedJudgmentsProduceOnlyDescriptiveGates() throws Exception {
        fixture(); complete(); var result = check(); assertEquals(4, result.get("reviewedTrials").getAsInt()); assertEquals(16, result.get("claimJudgments").getAsInt());
        for (int i = 0; i < 4; i++) assertTrue(accepted(result, i)); assertEquals("NOT_ESTABLISHED", result.get("campaignAcceptance").getAsString());
    }
    @Test void unsupportedAndUnresolvedFinalAssertionsBlockGatesWithoutChangingVerdicts() throws Exception {
        fixture(); complete(); for (String verdict : List.of("UNSUPPORTED", "UNRESOLVED")) {
            judgment(0, 1).addProperty("verdict", verdict); var result = check(); assertFalse(accepted(result, 0)); assertTrue(accepted(result, 1)); assertEquals(verdict, judgment(0, 1).get("verdict").getAsString());
        }
    }
    @Test void historicalErrorsRemainCountedEvenWhenExplicitlyNotEndorsed() throws Exception {
        fixture(); complete(); judgment(0, 4).addProperty("verdict", "UNSUPPORTED"); var result = check(); assertTrue(accepted(result, 0)); assertEquals(1, result.get("historicalMaterialUnsupported").getAsInt());
        judgment(0, 4).addProperty("endorsed", true); assertFalse(accepted(check(), 0));
    }
    @Test void failedOrAbstainedTrialsCannotPassEvenWithSatisfiedCriteria() throws Exception {
        fixture(); complete(); var trial = packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonObject("trial"); trial.addProperty("status", "FAILED"); assertFalse(accepted(check(), 0));
        trial.addProperty("status", "COMPLETED"); trial.getAsJsonArray("cases").get(0).getAsJsonObject().addProperty("response", "ABSTAINED"); row(0).getAsJsonObject("output").addProperty("response", "ABSTAINED"); assertFalse(accepted(check(), 0));
    }
    @Test void missingOrReorderedTrialAndUnitDenominatorsAreRefused() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); review.getAsJsonArray("trials").remove(0); assertThrows(RuntimeException.class, this::check); review = saved.deepCopy();
        row(0).getAsJsonArray("units").remove(0); assertThrows(RuntimeException.class, this::check); review = saved.deepCopy(); row(1).add("source", row(0).get("source").deepCopy()); assertThrows(RuntimeException.class, this::check);
    }
    @Test void changedProtectedIdentityAndUnknownFieldsCannotSupplyAcceptance() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); review.add("contractIdentity", object(digest("other contract"))); assertThrows(RuntimeException.class, this::check); review = saved;
        review.addProperty("accepted", true); assertThrows(RuntimeException.class, this::check);
    }
    @Test void staleSourceBytesAndPointersInvalidateReviewBindings() throws Exception {
        fixture(); complete(); var old = files.get("facts.json"); file("facts.json", Map.of("outcome", "changed")); assertThrows(RuntimeException.class, this::check); files.put("facts.json", old);
        review.getAsJsonArray("evidence").get(0).getAsJsonObject().addProperty("pointer", "/missing"); assertThrows(RuntimeException.class, this::check);
    }
    @Test void changedAnchorsDuplicateClaimsAndInventedEvidenceAreRefused() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); judgment(0, 1).getAsJsonObject("anchor").addProperty("pointer", "/missing"); assertThrows(RuntimeException.class, this::check); review = saved.deepCopy();
        reviewedUnit(0, 1).getAsJsonArray("claims").add(judgment(0, 1).deepCopy()); assertThrows(RuntimeException.class, this::check); review = saved.deepCopy(); judgment(0, 1).add("evidenceIds", CaseInputs.JSON.toJsonTree(List.of("invented"))); assertThrows(RuntimeException.class, this::check);
    }
    @Test void incompleteReviewCannotAttestCompleteOrCitePendingUnits() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); var u = reviewedUnit(0, 1); u.addProperty("coverage", "PENDING"); u.add("claims", new JsonArray()); assertThrows(RuntimeException.class, this::check);
        review = saved; row(0).getAsJsonArray("criteria").get(0).getAsJsonObject().addProperty("judgment", "PENDING"); assertThrows(RuntimeException.class, this::check);
    }
    @Test void summaryClaimsAndNonemptyConflictsCannotBeHiddenAsContext() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); for (int index : List.of(1, 6)) { review = saved.deepCopy(); var u = reviewedUnit(0, index); u.addProperty("coverage", "CONTEXT"); u.add("claims", new JsonArray()); assertThrows(RuntimeException.class, this::check); }
    }
    @Test void containersRequireTheirExactDirectSectionsWithoutCycles() throws Exception {
        fixture(); complete(); var container = reviewedUnit(0, 0); container.add("coveredBy", CaseInputs.JSON.toJsonTree(List.of("unit-1", "unit-2"))); assertThrows(RuntimeException.class, this::check);
        container.add("coveredBy", CaseInputs.JSON.toJsonTree(List.of("unit-2"))); assertThrows(RuntimeException.class, this::check);
    }
    @Test void finalAssertionsCannotBeRelabeledAsNonmaterialOrUnendorsed() throws Exception {
        fixture(); complete();
        for (int unit : List.of(1, 4, 5, 6)) { judgment(0, unit).addProperty("material", false); assertThrows(RuntimeException.class, this::check); judgment(0, unit).addProperty("material", true); }
        judgment(0, 1).addProperty("endorsed", false); assertThrows(RuntimeException.class, this::check);
    }
    @Test void rejectedFinishTextRequiresJudgmentsAndDecodedAnchorsRemainBound() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); var response = reviewedUnit(0, 5); response.addProperty("coverage", "CONTEXT"); response.add("claims", new JsonArray()); assertThrows(RuntimeException.class, this::check);
        review = saved; judgment(0, 5).getAsJsonObject("anchor").add("valueIdentity", object(digest("invented response"))); assertThrows(RuntimeException.class, this::check);
    }
    @Test void judgmentsRequireUnblindedOfflineProvenanceAndStrictBooleanTypes() throws Exception {
        fixture(); complete(); var saved = review.deepCopy(); review.getAsJsonObject("reviewer").addProperty("method", "NOT_RECORDED"); assertThrows(RuntimeException.class, this::check); review = saved.deepCopy();
        review.getAsJsonObject("reviewer").addProperty("calibratedReviewerAccuracy", true); assertThrows(RuntimeException.class, this::check); review = saved.deepCopy(); row(0).addProperty("coverageComplete", "true"); assertThrows(RuntimeException.class, this::check);
    }
    @Test void aPartialBoundJudgmentPreservesEveryOtherUnitAndCriterionAsPending() throws Exception {
        fixture(); complete();
        for (int r = 0; r < 4; r++) {
            row(r).addProperty("coverageComplete", false);
            for (int u = 0; u < 7; u++) if (r != 0 || u != 1) { var item = reviewedUnit(r, u); item.addProperty("coverage", "PENDING"); item.add("claims", new JsonArray()); item.add("coveredBy", new JsonArray()); }
            for (var element : row(r).getAsJsonArray("criteria")) { var c = element.getAsJsonObject(); c.addProperty("judgment", "PENDING"); c.add("unitIds", new JsonArray()); c.add("evidenceIds", new JsonArray()); }
        }
        var result = check(); assertEquals(27, result.get("pendingUnits").getAsInt()); assertEquals(24, result.get("pendingCriteria").getAsInt()); assertEquals(1, result.get("claimJudgments").getAsInt()); assertEquals(0, result.get("reviewedTrials").getAsInt());
        for (int r = 0; r < 4; r++) assertFalse(accepted(result, r));
    }
    @Test void unmetCriteriaRemainValidJudgmentsButBlockGatesAndMembershipCannotChange() throws Exception {
        fixture(); complete(); var criterion = row(0).getAsJsonArray("criteria").get(0).getAsJsonObject();
        for (String verdict : List.of("NOT_SATISFIED", "UNRESOLVED")) { criterion.addProperty("judgment", verdict); assertFalse(accepted(check(), 0)); }
        criterion.addProperty("criterionId", "invented"); assertThrows(RuntimeException.class, this::check);
    }
}
