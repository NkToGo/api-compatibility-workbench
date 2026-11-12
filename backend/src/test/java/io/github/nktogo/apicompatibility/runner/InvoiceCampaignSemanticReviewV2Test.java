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

class InvoiceCampaignSemanticReviewV2Test {
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
        packet=object(Map.of("formatVersion",2,"packetId","reviewpacket-v2-"+UUID.randomUUID(),"createdAtUnixMillis",1L,"producer",Map.of("extractor","invoice-campaign-review-v2","scriptSha256","1".repeat(64),"inspectionRuntimeArtifacts",Map.of("api-compatibility-backend-0.1.0-SNAPSHOT.jar","3".repeat(64),"dependency/json.jar","4".repeat(64))),"qualityReview","NOT_PERFORMED","authority","campaign-bound-review-inputs-not-semantic-acceptance",
                "inventory",Map.of("campaignId","invoicecampaign-v2-"+UUID.randomUUID(),"expectedCampaignReceiptSha256","2".repeat(64),"criteriaTiming","FROZEN_BEFORE_TRIALS","campaignAcceptance","NOT_ESTABLISHED","inputIdentity",input,"contractIdentity",digest("contract"),"rubricIdentity",new ContentDigest(hashes.get("evidence/rubric.json")),"rubricArtifact","evidence/rubric.json","sourceArtifacts",hashes,"trials",rows)));
        packet.getAsJsonObject("inventory").addProperty("publicTaskSha256",InvoiceDecisionV2.taskIdentity());
        review=InvoiceSemanticReview.campaignDraftV2(packet,selected);
    }
    private JsonObject check() throws Exception { return InvoiceSemanticReview.validateCampaignV2(review,packet,selected,files::get); }
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
        fixture();assertThrows(RuntimeException.class,()->InvoiceSemanticReview.validateCampaignV2(review,packet,digest("other"),files::get));
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
        fixture();Path file=publishPacket(true);var identity=new ContentDigest(CaseInputs.hash(Files.readAllBytes(file)));var engine=new InvoiceCampaignSemanticReviewV2(root,file,identity);Path destination=Files.createDirectories(root.resolve("reviews")).resolve("draft.json");engine.create(destination);
        byte[] before=Files.readAllBytes(destination);var result=engine.verify(destination,new ContentDigest(CaseInputs.hash(before)));assertEquals(72,result.get("pendingCriteria").getAsInt());assertArrayEquals(before,Files.readAllBytes(destination));assertThrows(Exception.class,()->engine.create(destination));assertThrows(RuntimeException.class,()->InvoiceReviewInputs.object(Files.readAllBytes(file)));
    }
    @Test void outputCannotOverlapProtectedEvidenceOrState() throws Exception {
        fixture();Path file=publishPacket(false);var engine=new InvoiceCampaignSemanticReviewV2(root,file,new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))));assertThrows(RuntimeException.class,()->engine.create(root.resolve("evidence/extra.json")));assertThrows(RuntimeException.class,()->engine.create(root.resolve("state/extra.json")));assertFalse(Files.exists(root.resolve("evidence/extra.json")));
    }
    @Test void tamperedReviewAndOverLimitPacketRemainRejected() throws Exception {
        fixture();Path file=publishPacket(false);var engine=new InvoiceCampaignSemanticReviewV2(root,file,new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))));Path destination=Files.createDirectories(root.resolve("reviews")).resolve("draft.json");engine.create(destination);assertThrows(RuntimeException.class,()->engine.verify(destination,digest("wrong review")));Files.write(file,new byte[2097153]);assertThrows(RuntimeException.class,()->engine.create(root.resolve("reviews/other.json")));
    }
    @Test void v2IdentityTaskAndCampaignBindingsCannotBeRelabeled() throws Exception {
        fixture(); assertEquals(2,review.get("formatVersion").getAsInt()); assertTrue(review.get("reviewId").getAsString().startsWith("semanticreview-v2-"));
        assertThrows(RuntimeException.class,()->InvoiceSemanticReview.campaignDraft(packet,selected));
        assertThrows(RuntimeException.class,()->InvoiceSemanticReview.validateCampaign(review,packet,selected,files::get));
        for(String key:List.of("publicTaskSha256","campaignId","expectedCampaignReceiptSha256")) {
            var before=review.get(key); review.addProperty(key,"changed"); assertThrows(RuntimeException.class,this::check); review.add(key,before);
        }
        review.addProperty("formatVersion",1); assertThrows(RuntimeException.class,this::check);
    }
    @Test void wrongProducerRuntimeAndTaskAreRefusedBeforeDrafting() throws Exception {
        fixture(); var producer=packet.getAsJsonObject("producer"); var before=producer.deepCopy(); producer.addProperty("extractor","invoice-campaign-review-v1");
        assertThrows(RuntimeException.class,()->InvoiceSemanticReview.campaignDraftV2(packet,selected)); packet.add("producer",before.deepCopy());
        packet.getAsJsonObject("producer").getAsJsonObject("inspectionRuntimeArtifacts").addProperty("../outside.jar","1".repeat(64)); assertThrows(RuntimeException.class,()->InvoiceSemanticReview.campaignDraftV2(packet,selected)); packet.add("producer",before);
        packet.getAsJsonObject("inventory").addProperty("publicTaskSha256","a".repeat(64)); assertThrows(RuntimeException.class,()->InvoiceSemanticReview.campaignDraftV2(packet,selected));
    }
    private void nested() throws Exception {
        fixture(); var original=packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject();
        String artifact=original.getAsJsonArray("units").get(0).getAsJsonObject().get("artifact").getAsString();
        file(artifact,Map.of("decision",Map.of("summary","A nested final assertion.","response","ANSWERED","summaryCitations",List.of("read-1")),"caseId",InvoiceRenewalFixtures.CASE_ID,"publicTaskSha256",InvoiceDecisionV2.taskIdentity()));
        packet.getAsJsonObject("inventory").getAsJsonObject("sourceArtifacts").addProperty(artifact,CaseInputs.hash(files.get(artifact)));
        var units=new JsonArray(); units.add(unit("unit-1","FINAL_DOSSIER",artifact,"")); units.add(unit("unit-2","ADVISORY_DECISION",artifact,"/decision"));
        units.add(unit("unit-3","SUMMARY",artifact,"/decision/summary")); units.add(unit("unit-4","DOSSIER_SECTION",artifact,"/decision/summaryCitations"));
        units.add(unit("unit-5","DOSSIER_SECTION",artifact,"/decision/response")); units.add(unit("unit-6","DOSSIER_SECTION",artifact,"/caseId")); units.add(unit("unit-7","DOSSIER_SECTION",artifact,"/publicTaskSha256"));
        original.add("units",units); review=InvoiceSemanticReview.campaignDraftV2(packet,selected); complete();
        var reviewed=row(0).getAsJsonArray("units"); var rootUnit=reviewed.get(0).getAsJsonObject(); rootUnit.addProperty("coverage","CONTAINER"); rootUnit.add("claims",new JsonArray()); rootUnit.add("coveredBy",JSON.toJsonTree(List.of("unit-2","unit-3","unit-4","unit-5","unit-6","unit-7")));
        var decision=reviewed.get(1).getAsJsonObject(); decision.addProperty("coverage","CONTAINER"); decision.add("coveredBy",JSON.toJsonTree(List.of("unit-3","unit-4","unit-5")));
        var summary=reviewed.get(2).getAsJsonObject(); summary.addProperty("coverage","CLAIMS"); summary.getAsJsonArray("claims").add(object(Map.of("claimId","claim-1","anchor",Map.of("representation","NATIVE","pointer","","valueIdentity",summary.get("valueIdentity")),"proposition","Synthetic nested final assertion.","verdict","SUPPORTED_WITHIN_RECORDED_SCOPE","material",true,"endorsed",true,"evidenceIds",List.of("fact"),"rationale","Supplied binding control only.")));
        for(int i=3;i<7;i++) reviewed.get(i).getAsJsonObject().addProperty("coverage","CONTEXT");
    }
    @Test void nestedFinalDecisionDelegatesOnlyToItsOwnDescendantUnits() throws Exception {
        nested(); assertTrue(check().getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());
        var decision=row(0).getAsJsonArray("units").get(1).getAsJsonObject();
        for(var refs:List.of(List.of("unit-2","unit-3","unit-4","unit-5"),List.of("unit-3","unit-4","unit-5","unit-6"),List.of("unit-3"))) {
            decision.add("coveredBy",JSON.toJsonTree(refs)); assertThrows(RuntimeException.class,this::check);
        }
    }
    @Test void unsupportedNestedFinalAdviceCannotBeHiddenByUnendorsement() throws Exception {
        nested(); var claim=row(0).getAsJsonArray("units").get(2).getAsJsonObject().getAsJsonArray("claims").get(0).getAsJsonObject(); claim.addProperty("verdict","UNSUPPORTED");
        var result=check(); assertEquals(1,result.get("historicalMaterialUnsupported").getAsInt()); assertFalse(result.getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());
        claim.addProperty("endorsed",false); assertThrows(RuntimeException.class,this::check);
    }
    @Test void wholeNestedDecisionCannotExcludeItsMaterialClaimsFromGates() throws Exception {
        nested(); var decision=row(0).getAsJsonArray("units").get(1).getAsJsonObject(); decision.addProperty("coverage","CLAIMS"); decision.add("coveredBy",new JsonArray());
        decision.getAsJsonArray("claims").add(object(Map.of("claimId","claim-1","anchor",Map.of("representation","NATIVE","pointer","","valueIdentity",decision.get("valueIdentity")),"proposition","Synthetic whole final decision assertion.","verdict","UNSUPPORTED","material",true,"endorsed",false,"evidenceIds",List.of("fact"),"rationale","Cannot exclude the final answer.")));
        assertThrows(RuntimeException.class,this::check);
    }
    @Test void typedV2PrefixRequestsAreContextButExtraNarrativeIsNot() throws Exception {
        fixture(); String artifact="evidence/prefix.json"; file(artifact,Map.of("outputText",JSON.toJson(Map.of("decision",Map.of("action","catalog_prefix","prefix","source/","offset",0,"limit",1)))));
        packet.getAsJsonObject("inventory").getAsJsonObject("sourceArtifacts").addProperty(artifact,CaseInputs.hash(files.get(artifact)));
        packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonArray("units").add(unit("unit-2","MODEL_RESPONSE",artifact,"/outputText"));
        review=InvoiceSemanticReview.campaignDraftV2(packet,selected); complete(); row(0).getAsJsonArray("units").get(1).getAsJsonObject().addProperty("coverage","CONTEXT"); assertNotNull(check());
        file(artifact,Map.of("outputText",JSON.toJson(Map.of("decision",Map.of("action","catalog_prefix","prefix","source/","offset",0,"limit",1,"explanation","Unverified narrative.")))));
        packet.getAsJsonObject("inventory").getAsJsonObject("sourceArtifacts").addProperty(artifact,CaseInputs.hash(files.get(artifact)));
        var changed=unit("unit-2","MODEL_RESPONSE",artifact,"/outputText"); packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonArray("units").set(1,changed);
        row(0).getAsJsonArray("units").get(1).getAsJsonObject().add("valueIdentity",changed.get("valueIdentity")); assertThrows(RuntimeException.class,this::check);
    }
    @Test void strictFileParsingCannotBeBypassedWithMatchingExternalDigests() throws Exception {
        fixture(); Path file=publishPacket(false); Path reviews=Files.createDirectories(root.resolve("reviews")); Path destination=reviews.resolve("draft.json");
        var engine=new InvoiceCampaignSemanticReviewV2(root,file,new ContentDigest(CaseInputs.hash(Files.readAllBytes(file)))); engine.create(destination);
        for(byte[] invalid:List.of("{\"formatVersion\":2,\"formatVersion\":2}".getBytes(StandardCharsets.UTF_8),new byte[]{(byte)0xc3,0x28},new byte[2097153])) {
            Files.write(destination,invalid); assertThrows(RuntimeException.class,()->engine.verify(destination,new ContentDigest(CaseInputs.hash(invalid))));
        }
        Files.writeString(file,"{\"formatVersion\":2,\"formatVersion\":2}"); var changed=new InvoiceCampaignSemanticReviewV2(root,file,new ContentDigest(CaseInputs.hash(Files.readAllBytes(file))));
        assertThrows(RuntimeException.class,()->changed.create(reviews.resolve("other.json"))); assertFalse(Files.exists(reviews.resolve("other.json")));
    }
    @Test void failedCancelledUnknownAndMissingAnswersCannotPassSatisfiedCriteria() throws Exception {
        fixture(); complete(); var trial=packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonObject("trial");
        for(String status:List.of("FAILED","CANCELLED","UNKNOWN_REMOTE_OUTCOME")) {
            trial.addProperty("status",status); assertFalse(check().getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());
        }
        trial.addProperty("status","COMPLETED");
        for(String response:List.of("ABSTAINED","NOT_PROVIDED")) {
            trial.getAsJsonArray("cases").get(0).getAsJsonObject().addProperty("response",response); row(0).getAsJsonObject("output").addProperty("response",response);
            assertFalse(check().getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean());
        }
    }
    @Test void unendorsedOriginalAndFreshErrorsRemainInHistoricalCounts() throws Exception {
        fixture(); complete(); var unit=packet.getAsJsonObject("inventory").getAsJsonArray("trials").get(0).getAsJsonObject().getAsJsonArray("units").get(0).getAsJsonObject();
        var claim=row(0).getAsJsonArray("units").get(0).getAsJsonObject().getAsJsonArray("claims").get(0).getAsJsonObject(); claim.addProperty("endorsed",false); claim.addProperty("verdict","UNSUPPORTED");
        for(String kind:List.of("ORIGINAL_CLAIM","FRESH_CLAIM")) {
            unit.addProperty("kind",kind); var result=check(); assertEquals(1,result.get("historicalMaterialUnsupported").getAsInt());
            assertTrue(result.getAsJsonArray("trials").get(0).getAsJsonObject().get("satisfiesDeclaredReview").getAsBoolean()); assertEquals("NOT_ESTABLISHED",result.get("campaignAcceptance").getAsString());
        }
    }
}
