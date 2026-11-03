package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure question-bound fresh contexts and advisory finishes over caller-admitted v2 review histories. */
final class InvoiceFollowUpV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final String CONTEXT = "invoice-v2-fresh-role-owned-context-one-question-exact-anchors";
    static final String SOURCE = "invoice-v2-recomputed-review-selection-target-anchor-depth-one-no-inherited-read-ownership";
    private final InvoiceComparisonInputs.Bundle inputs;
    private final JsonObject selection;
    private final String selectionIdentity;
    private final Map<String, JsonObject> questions = new TreeMap<>();

    InvoiceFollowUpV2(InvoiceComparisonInputs.Bundle inputs, List<InvoiceTeamReviewV2.WorkerResult> originals, String coordinator,
                      JsonObject reviewDecision, JsonObject coordinatorBootstrap, List<JsonObject> coordinatorReads) {
        this.inputs = Objects.requireNonNull(inputs);
        CaseInputs.require(InvoiceWorkerDecision.WORKERS.stream().noneMatch(w -> InvoiceFollowUpDecisionV2.participant(w).equals(coordinator)),
                "Coordinator cannot share a fresh participant identity");
        selection = new InvoiceTeamReviewV2(coordinator, inputs, originals).select(reviewDecision, coordinatorBootstrap, coordinatorReads);
        selectionIdentity = hash(selection);
        var references = new TreeMap<String, JsonObject>();
        for (String field : List.of("workers", "originalClaims")) for (var item : selection.getAsJsonArray(field)) {
            var reference = item.getAsJsonObject(); references.put(reference.get("referenceId").getAsString(), reference);
        }
        for (var read : coordinatorReads) {
            String id = read.get("operationId").getAsString();
            if (selection.getAsJsonObject("referenceIdentities").has(id)) references.put(id, read);
        }
        for (var item : selection.getAsJsonArray("selectedFollowUps")) {
            var proposal = item.getAsJsonObject(); String worker = proposal.get("workerId").getAsString();
            var anchors = new JsonArray();
            for (var citation : proposal.getAsJsonArray("citations")) {
                String id = citation.getAsString(); var reference = Objects.requireNonNull(references.get(id));
                String referenceHash = selection.getAsJsonObject("referenceIdentities").get(id).getAsString();
                CaseInputs.require(referenceHash.equals(hash(reference)), "Changed selected follow-up anchor");
                String kind = id.startsWith("worker-") ? "ORIGINAL_OUTCOME" : id.startsWith("claim-") ? "ORIGINAL_CLAIM" : "COORDINATOR_TEXT";
                var anchor = json(Map.of("anchorId", "anchor-" + (anchors.size() + 1), "sourceReferenceId", id, "sourceSha256", referenceHash,
                        "kind", kind, "sourceParticipant", kind.equals("COORDINATOR_TEXT") ? coordinator : reference.get("workerId").getAsString(),
                        "value", reference.deepCopy(), "authority", "selected-untrusted-anchor-not-fresh-read-or-grant"));
                anchors.add(anchor);
            }
            var question = json(Map.of("workerId", worker, "participantId", InvoiceFollowUpDecisionV2.participant(worker), "inputIdentity", inputs.identity(),
                    "protocol", InvoiceFollowUpDecisionV2.protocol(worker), "contractIdentity", InvoiceFollowUpDecisionV2.contractIdentity(worker),
                    "publicTaskSha256", InvoiceDecisionV2.taskIdentity(), "selectionIdentity", selectionIdentity, "questionIdentity", hash(proposal),
                    "delegatedQuestion", proposal.deepCopy(), "questionAnchors", anchors));
            question.addProperty("sourcePolicy", SOURCE); question.addProperty("delegationDepth", 1);
            questions.put(worker, question);
        }
    }
    List<String> selectedWorkers() { return List.copyOf(questions.keySet()); }
    JsonObject selection() { return selection.deepCopy(); }
    String selectionIdentity() { return selectionIdentity; }
    JsonObject question(String worker) {
        InvoiceFollowUpDecisionV2.scope(worker);
        CaseInputs.require(questions.containsKey(worker), "No selected question for this fresh role"); return questions.get(worker).deepCopy();
    }
    private InvoiceReadiness.Subject subject(String worker) {
        question(worker); return new InvoiceReadiness.Subject(InvoiceFollowUpDecisionV2.participant(worker), inputs.identity(), InvoiceFollowUpDecisionV2.scope(worker));
    }
    String context(String worker, JsonObject bootstrap, List<JsonObject> reads, CoordinationTeamBudget.Allocation allocation,
                   CoordinationTeamBudget.Reservations reserved, int activeParticipants, int byteLimit) {
        CaseInputs.require(allocation.ceiling() <= 60 && allocation.equals(CoordinationTeamBudget.forCeiling(allocation.ceiling()))
                && byteLimit >= 1 && byteLimit <= 64000, "Invalid fresh v2 stage allocation or context limit");
        question(worker);
        CaseInputs.require(activeParticipants >= 1 && activeParticipants <= selectedWorkers().size(), "Invalid selected fresh participant count");
        int remaining = allocation.remaining(CoordinationTeamBudget.Stage.FOLLOW_UP, reserved);
        if (remaining == 0) throw new CoordinationRuntime.Stop("CALL_BUDGET_EXHAUSTED");
        var context = JsonParser.parseString(InvoiceContextV2.build(subject(worker), inputs, bootstrap, reads,
                new InvoiceContextV2.Scheduling(remaining, activeParticipants), 64000)).getAsJsonObject();
        context.addProperty("protocol", InvoiceFollowUpDecisionV2.protocol(worker)); context.addProperty("contractIdentity", InvoiceFollowUpDecisionV2.contractIdentity(worker));
        context.addProperty("contextPolicy", CONTEXT); context.add("followUp", question(worker));
        context.add("teamStage", json(Map.of("stage", "FOLLOW_UP", "allocation", allocation, "reserved", reserved,
                "callsRemainingInFollowUpStage", remaining, "activeParticipants", activeParticipants, "budgetPolicy", CoordinationTeamBudget.POLICY,
                "authority", "caller-supplied-shared-stage-snapshot-not-permission-or-new-pool")));
        context.addProperty("authority", "validated-question-context-not-runtime-admission-or-semantic-acceptance");
        var recent = context.getAsJsonArray("recentPages"); String input = InvoiceDecisionV2.canonical(context);
        while (bytes(input) > byteLimit && !recent.isEmpty()) { recent.remove(0); input = InvoiceDecisionV2.canonical(context); }
        if (bytes(input) > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED"); return input;
    }
    JsonObject accept(String worker, JsonObject decision, JsonObject bootstrap, List<JsonObject> reads) {
        var parsed = InvoiceFollowUpDecisionV2.parse(CaseInputs.JSON.toJson(Map.of("decision", decision)));
        CaseInputs.require(parsed.get("action").getAsString().equals("finish"), "Expected a fresh v2 finish");
        var readiness = InvoiceContextV2.readiness(subject(worker), inputs, bootstrap, reads);
        CaseInputs.require(InvoiceFinishV2.supported(subject(worker), parsed, readiness), "Unsupported fresh v2 advisory dossier");
        var identities = new TreeMap<String, String>();
        for (var read : reads) if (readiness.citableTextPageIds().contains(read.get("operationId").getAsString())) identities.put(read.get("operationId").getAsString(), hash(read));
        return json(Map.of("formatVersion", 2, "questionBinding", question(worker), "decision", parsed, "readiness", readiness.toJson(),
                "ownTextReferenceIdentities", identities, "qualityReview", "NOT_PERFORMED",
                "authority", "validated-advisory-follow-up-not-runtime-completion-or-semantic-acceptance"));
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static String hash(JsonElement value) { return CaseInputs.hash(InvoiceDecisionV2.canonical(value).getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
}
