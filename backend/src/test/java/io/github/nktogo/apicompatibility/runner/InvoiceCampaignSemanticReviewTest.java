package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

class InvoiceCampaignSemanticReviewTest {
    @TempDir Path root;
    private final Map<String, byte[]> files = new TreeMap<>();
    private final ContentDigest selected = digest("independently selected synthetic packet");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private JsonObject packet, review;
    private JsonObject object(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private void file(String name, Object value) { files.put(name, encoded(CaseInputs.JSON.toJsonTree(value)).getBytes(StandardCharsets.UTF_8)); }
    private JsonObject unit(String id, String kind, String artifact, String pointer) {
        return object(Map.of("unitId", id, "kind", kind, "artifact", artifact, "pointer", pointer,
                "valueIdentity", digest(encoded(InvoiceReviewInputs.pointer(InvoiceReviewInputs.object(files.get(artifact)), pointer))), "referenceId", "", "directFinalCitation", "NOT_CITED"));
    }
    private void fixture() throws Exception {
        files.put("evidence/rubric.json", Files.readAllBytes(Path.of(System.getProperty("workbench.repository"), "docs/evaluations/invoice-review-rubric-v1.json")));
        file("evidence/facts.json", Map.of("statement", "A synthetic recorded observation."));
        file("evidence/broker.json", Map.of("formatVersion", 1, "callId", "model-"+UUID.randomUUID(), "status", "RATE_LIMITED", "remoteOutcome", "RESPONSE_RECEIVED", "httpStatus", 429, "authority", "model-output-not-execution-evidence", "finishedAt", "2026-10-26T15:16:00Z"));
        var rows = new JsonArray(); var input = digest("synthetic input"); var arms = List.of("DETERMINISTIC", "SINGLE_AGENT", "INDEPENDENT_WORKERS", "COORDINATED_TEAM");
        for (int i=0;i<12;i++) {
            int rep=i/4+1; String arm=arms.get((i%4+rep-1)%4), run="trial-"+UUID.randomUUID(), artifact="state/"+run+"/dossier.json";
            file(artifact, Map.of("summary", "A synthetic assertion remains reviewable."));
            var units = new JsonArray(); units.add(unit("unit-1", "SUMMARY", artifact, "/summary"));
            if(i==11) units.add(unit("unit-2", "BROKER_OUTCOME", "evidence/broker.json", ""));
            var output=object(Map.of("caseId", InvoiceRenewalFixtures.CASE_ID, "inputIdentity", input, "response", i==11?"NOT_PROVIDED":"ANSWERED")); output.add("review", JsonNull.INSTANCE);
            var trial=object(Map.of("runId", run, "status", i==11?"UNKNOWN_REMOTE_OUTCOME":"COMPLETED", "slot", Map.of("workloadId","invoice-joint","repetition",rep,"arm",arm), "cases",List.of(output)));
            rows.add(object(Map.of("position",i+1,"source",Map.of("arm",arm,"stateDirectory","state","runId",run,"resultIdentity",digest(run)),"trial",trial,"units",units)));
        }
        var hashes=new TreeMap<String,String>();files.forEach((name,bytes)->hashes.put(name,CaseInputs.hash(bytes)));
        packet=object(Map.of("formatVersion",1,"packetId","reviewpacket-"+UUID.randomUUID(),"createdAtUnixMillis",1L,"producer",Map.of("extractor","invoice-campaign-review-v1","scriptSha256","1".repeat(64)),"qualityReview","NOT_PERFORMED","authority","campaign-bound-review-inputs-not-semantic-acceptance",
                "inventory",Map.of("campaignId","invoicecampaign-"+UUID.randomUUID(),"expectedCampaignReceiptSha256","2".repeat(64),"criteriaTiming","FROZEN_BEFORE_TRIALS","campaignAcceptance","NOT_ESTABLISHED","inputIdentity",input,"contractIdentity",digest("contract"),"rubricIdentity",new ContentDigest(hashes.get("evidence/rubric.json")),"rubricArtifact","evidence/rubric.json","sourceArtifacts",hashes,"trials",rows)));
        review=InvoiceSemanticReview.campaignDraft(packet,selected);
    }
    private JsonObject check() throws Exception { return InvoiceSemanticReview.validateCampaign(review,packet,selected,files::get); }
    private JsonObject row(int i) { return review.getAsJsonArray("trials").get(i).getAsJsonObject(); }
    private void complete() {
        var reviewer=review.getAsJsonObject("reviewer");reviewer.addProperty("method","human-offline-inspection");reviewer.addProperty("label","Synthetic supplied-binding control, not semantic performance");reviewer.addProperty("reviewedOn","2026-10-27");
        review.getAsJsonArray("evidence").add(object(Map.of("evidenceId","fact","artifact","evidence/facts.json","sha256",CaseInputs.hash(files.get("evidence/facts.json")),"pointer","/statement","valueIdentity",digest(encoded(new JsonPrimitive("A synthetic recorded observation."))))));
        for(int i=0;i<12;i++) {
            row(i).addProperty("coverageComplete",true);
            var units=row(i).getAsJsonArray("units");var first=units.get(0).getAsJsonObject();first.addProperty("coverage","CLAIMS");
            first.getAsJsonArray("claims").add(object(Map.of("claimId","claim-1","anchor",Map.of("representation","NATIVE","pointer","","valueIdentity",first.get("valueIdentity")),"proposition","Synthetic declared proposition.","verdict","SUPPORTED_WITHIN_RECORDED_SCOPE","material",true,"endorsed",true,"evidenceIds",List.of("fact"),"rationale","Synthetic binding only.")));
            if(i==11) units.get(1).getAsJsonObject().addProperty("coverage","CONTEXT");
            for(var value:row(i).getAsJsonArray("criteria")) {var c=value.getAsJsonObject();c.addProperty("judgment","SATISFIED");c.addProperty("rationale","Synthetic criterion declaration.");c.add("unitIds",CaseInputs.JSON.toJsonTree(List.of("unit-1")));c.add("evidenceIds",CaseInputs.JSON.toJsonTree(List.of("fact")));}
        }
    }
    @Test void draftRetainsTwelveSlotsAndSeventyTwoPendingCriteria() throws Exception {
        fixture();var result=check();assertEquals(13,result.get("pendingUnits").getAsInt());assertEquals(72,result.get("pendingCriteria").getAsInt());assertEquals(0,result.get("claimJudgments").getAsInt());
        assertEquals("NOT_ESTABLISHED",result.get("campaignAcceptance").getAsString());for(var row:result.getAsJsonArray("trials"))assertFalse(row.getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());
    }
    @Test void suppliedCompletionKeepsUnknownAndMissingAnswersOutOfGates() throws Exception {
        fixture();complete();var result=check();assertEquals(12,result.get("reviewedTrials").getAsInt());assertTrue(result.getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());assertFalse(result.getAsJsonArray("trials").get(11).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());assertEquals("NOT_ESTABLISHED",result.get("campaignAcceptance").getAsString());
    }
    @Test void legacyAndCampaignTimingCannotBeInterchanged() throws Exception {
        fixture();assertThrows(RuntimeException.class,()->InvoiceSemanticReview.validate(review,packet,files::get));review.addProperty("criteriaTiming","POST_HOC_FOR_EXISTING_OPERATIONAL_TRIALS");assertThrows(RuntimeException.class,this::check);
    }
    @Test void wrongIndependentPacketDigestAndDuplicateRunAreRejected() throws Exception {
        fixture();assertThrows(RuntimeException.class,()->InvoiceSemanticReview.validateCampaign(review,packet,digest("other"),files::get));
        var rows=packet.getAsJsonObject("inventory").getAsJsonArray("trials");var first=rows.get(0).getAsJsonObject();var second=rows.get(1).getAsJsonObject();second.getAsJsonObject("source").add("runId",first.getAsJsonObject("source").get("runId"));second.getAsJsonObject("trial").add("runId",first.getAsJsonObject("trial").get("runId"));assertThrows(RuntimeException.class,this::check);
    }
    @Test void missingReorderedUnitsAndFalseCompletionCannotShrinkPendingWork() throws Exception {
        fixture();row(0).addProperty("coverageComplete",true);assertThrows(RuntimeException.class,this::check);fixture();row(11).getAsJsonArray("units").remove(1);assertThrows(RuntimeException.class,this::check);fixture();review.getAsJsonArray("trials").remove(0);assertThrows(RuntimeException.class,this::check);
    }
    @Test void unsupportedFinalClaimBlocksOnlyItsGateAndRemainsCounted() throws Exception {
        fixture();complete();row(0).getAsJsonArray("units").get(0).getAsJsonObject().getAsJsonArray("claims").get(0).getAsJsonObject().addProperty("verdict","UNSUPPORTED");var result=check();assertEquals(1,result.get("historicalMaterialUnsupported").getAsInt());assertFalse(result.getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());
    }
    @Test void brokerNarrativeCannotBeHiddenAsOperationalContext() throws Exception {
        fixture();complete();var broker=InvoiceReviewInputs.object(files.get("evidence/broker.json"));broker.addProperty("explanation","Unsupported narrative about quota.");file("evidence/broker.json",broker);
        packet.getAsJsonObject("inventory").getAsJsonObject("sourceArtifacts").addProperty("evidence/broker.json",CaseInputs.hash(files.get("evidence/broker.json")));
        var identity=CaseInputs.JSON.toJsonTree(digest(encoded(broker)));packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(11).getAsJsonObject().getAsJsonArray("units").get(1).getAsJsonObject().add("valueIdentity",identity);row(11).getAsJsonArray("units").get(1).getAsJsonObject().add("valueIdentity",identity);
        assertThrows(RuntimeException.class,this::check);
    }
    private Path publishPacket(boolean padded) throws Exception {
        for(var e:files.entrySet()){Path file=root.resolve(e.getKey());Files.createDirectories(file.getParent());Files.write(file,e.getValue());}
        Path directory=Files.createDirectories(root.resolve("state/packets"));Path file=directory.resolve("packet.json");String value=encoded(packet);if(padded)value+=" ".repeat(1048577-value.getBytes(StandardCharsets.UTF_8).length);Files.writeString(file,value);return file;
    }
    @Test void fileDraftAndVerificationAcceptBoundedCampaignPacketsBeyondLegacyLimit() throws Exception {
        fixture();Path file=publishPacket(true);var identity=new ContentDigest(CaseInputs.hash(Files.readAllBytes(file)));var engine=new InvoiceCampaignSemanticReview(root,file,identity);Path destination=Files.createDirectories(root.resolve("reviews")).resolve("draft.json");engine.create(destination);
        byte[] before=Files.readAllBytes(destination);var result=engine.verify(destination,new ContentDigest(CaseInputs.hash(before)));assertEquals(72,result.get("pendingCriteria").getAsInt());assertArrayEquals(before,Files.readAllBytes(destination));assertThrows(Exception.class,()->engine.create(destination));assertThrows(RuntimeException.class,()->InvoiceReviewInputs.object(Files.readAllBytes(file)));
    }
    @Test void outputCannotOverlapProtectedEvidenceOrState() throws Exception {
        fixture();Path file=publishPacket(false);var engine=new InvoiceCampaignSemanticReview(root,file,new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))));assertThrows(RuntimeException.class,()->engine.create(root.resolve("evidence/extra.json")));assertThrows(RuntimeException.class,()->engine.create(root.resolve("state/extra.json")));assertFalse(Files.exists(root.resolve("evidence/extra.json")));
    }
    @Test void tamperedReviewAndOverLimitPacketRemainRejected() throws Exception {
        fixture();Path file=publishPacket(false);var engine=new InvoiceCampaignSemanticReview(root,file,new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))));Path destination=Files.createDirectories(root.resolve("reviews")).resolve("draft.json");engine.create(destination);assertThrows(RuntimeException.class,()->engine.verify(destination,digest("wrong review")));Files.write(file,new byte[2097153]);assertThrows(RuntimeException.class,()->engine.create(root.resolve("reviews/other.json")));
    }
}
