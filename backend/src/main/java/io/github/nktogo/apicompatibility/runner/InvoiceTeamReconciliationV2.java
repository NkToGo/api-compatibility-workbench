package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceTeamReviewV2.*;

/** Pure lineage/access checks over caller-admitted histories; no runtime authentication or dispatch. */
final class InvoiceTeamReconciliationV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final String CONTEXT = "invoice-v2-final-owned-joint-context-all-original-fresh-claims-outcomes-conflicts";
    static final String SOURCE = "invoice-v2-recomputed-review-selection-exact-fresh-question-and-finish-no-replacement";
    record ReviewResult(WorkerStatus status, String stopReason, JsonObject decision, JsonObject bootstrap, List<JsonObject> reads) {
        ReviewResult {
            disposition(status, stopReason, decision, bootstrap, reads);
            decision = decision == null ? null : InvoiceTeamDecisionV2.parse(JSON.toJson(Map.of("decision", decision)));
            bootstrap = bootstrap == null ? null : bootstrap.deepCopy(); reads = reads.stream().map(JsonObject::deepCopy).toList();
        }
        @Override public JsonObject decision() { return decision == null ? null : decision.deepCopy(); }
        @Override public JsonObject bootstrap() { return bootstrap == null ? null : bootstrap.deepCopy(); }
        @Override public List<JsonObject> reads() { return reads.stream().map(JsonObject::deepCopy).toList(); }
    }
    private final InvoiceComparisonInputs.Bundle inputs;
    private final InvoiceReadiness.Subject reconciler;
    private final JsonObject evidence;
    private final Map<String, JsonObject> references = new TreeMap<>();
    private final Map<String, String> answeredClaims = new TreeMap<>();

    InvoiceTeamReconciliationV2(InvoiceComparisonInputs.Bundle inputs, String reconcilerId, List<WorkerResult> originals,
                                String coordinatorId, ReviewResult review, JsonArray fresh) {
        this.inputs = Objects.requireNonNull(inputs); Objects.requireNonNull(review); Objects.requireNonNull(fresh);
        var occupied = new HashSet<>(InvoiceWorkerDecision.WORKERS);
        for (String worker : InvoiceWorkerDecision.WORKERS) occupied.add(InvoiceFollowUpDecisionV2.participant(worker));
        CaseInputs.require(!occupied.contains(coordinatorId) && !occupied.contains(reconcilerId) && !Objects.equals(coordinatorId, reconcilerId),
                "Final reviewer requires its own participant identity");
        reconciler = new InvoiceReadiness.Subject(reconcilerId, inputs.identity(), InvoiceReadiness.Scope.JOINT);
        var originalReview = new InvoiceTeamReviewV2(coordinatorId, inputs, originals);
        var coordinator = new InvoiceReadiness.Subject(coordinatorId, inputs.identity(), InvoiceReadiness.Scope.JOINT);
        var reviewReadiness = review.bootstrap == null ? null : InvoiceContextV2.readiness(coordinator, inputs, review.bootstrap, review.reads);
        var follow = review.decision == null ? null : new InvoiceFollowUpV2(inputs, originals, coordinatorId, review.decision, review.bootstrap, review.reads);
        var selection = follow == null ? null : follow.selection();
        var selected = follow == null ? List.<String>of() : follow.selectedWorkers();
        CaseInputs.require(fresh.size() == selected.size(), "Missing or extra v2 fresh outcome");
        evidence = originalReview.evidence();
        var reviewOutcome = json(Map.of("participantId", coordinatorId, "status", review.status, "stopReason", review.stopReason,
                "historyIdentity", hash(JSON.toJsonTree(review))));
        reviewOutcome.add("decision", review.decision == null ? JsonNull.INSTANCE : review.decision.deepCopy());
        reviewOutcome.add("readiness", reviewReadiness == null ? JsonNull.INSTANCE : reviewReadiness.toJson());
        reviewOutcome.add("selectionIdentity", follow == null ? JsonNull.INSTANCE : new JsonPrimitive(follow.selectionIdentity()));
        evidence.add("coordinatorReview", reviewOutcome);
        evidence.add("selectedFollowUps", selection == null ? JsonNull.INSTANCE : selection.get("selectedFollowUps").deepCopy());
        evidence.add("possibleConflicts", selection == null ? new JsonArray() : selection.get("possibleConflicts").deepCopy());
        evidence.add("freshWorkers", new JsonArray()); evidence.add("freshClaims", new JsonArray());
        evidence.addProperty("qualityReview", "NOT_PERFORMED"); evidence.addProperty("contentTrust", "untrusted-data");
        evidence.addProperty("sourcePolicy", SOURCE);
        for (String group : List.of("workers", "originalClaims")) for (var value : evidence.getAsJsonArray(group)) addReference(value.getAsJsonObject());
        for (int i = 0; i < fresh.size(); i++) {
            var entry = fresh.get(i).getAsJsonObject(); String worker = selected.get(i), participant = InvoiceFollowUpDecisionV2.participant(worker);
            CaseInputs.require(entry.keySet().equals(Set.of("workerId", "participantId", "status", "stopReason", "questionBinding", "reads", "bootstrap", "decision", "accepted"))
                    && new JsonPrimitive(worker).equals(entry.get("workerId")) && new JsonPrimitive(participant).equals(entry.get("participantId"))
                    && Objects.requireNonNull(follow).question(worker).equals(entry.get("questionBinding")), "Changed v2 fresh question lineage or order");
            var status = WorkerStatus.valueOf(entry.get("status").getAsString()); String reason = entry.get("stopReason").getAsString();
            var decision = nullable(entry, "decision"); var bootstrap = nullable(entry, "bootstrap");
            var reads = entry.getAsJsonArray("reads").asList().stream().map(JsonElement::getAsJsonObject).toList();
            disposition(status, reason, decision, bootstrap, reads);
            var subject = new InvoiceReadiness.Subject(participant, inputs.identity(), InvoiceFollowUpDecisionV2.scope(worker));
            var readiness = bootstrap == null ? null : InvoiceContextV2.readiness(subject, inputs, bootstrap, reads);
            var accepted = decision == null ? JsonNull.INSTANCE : follow.accept(worker, decision, bootstrap, reads);
            CaseInputs.require(accepted.equals(entry.get("accepted")), "Changed v2 fresh accepted finish");
            var outcome = json(Map.of("referenceId", "freshworker-" + (i + 1), "workerId", worker, "participantId", participant,
                    "inputIdentity", inputs.identity(), "status", status, "stopReason", reason, "questionBinding", follow.question(worker),
                    "reportIdentity", hash(entry), "response", decision == null ? "NOT_PROVIDED" : decision.get("response").getAsString()));
            outcome.add("readiness", readiness == null ? JsonNull.INSTANCE : readiness.toJson());
            outcome.addProperty("authority", "caller-admitted-fresh-disposition-not-answer-quality");
            evidence.getAsJsonArray("freshWorkers").add(outcome); addReference(outcome);
            if (decision == null) continue;
            freshClaim(outcome, "summary", "SUMMARY", decision.get("summary"), decision.get("summaryCitations")); int position = 0;
            for (var item : decision.getAsJsonArray("findings")) { var finding = item.getAsJsonObject();
                freshClaim(outcome, "findings/" + position++, finding.get("kind").getAsString(), finding.get("statement"), finding.get("citations")); }
        }
    }
    private static void disposition(WorkerStatus status, String reason, JsonObject decision, JsonObject bootstrap, List<JsonObject> reads) {
        Objects.requireNonNull(status);
        CaseInputs.require(reason != null && reason.matches("[A-Z][A-Z0-9_]{0,99}") && reads != null && reads.size() <= InvoiceReadiness.MAX_PAGES
                && (bootstrap != null || reads.isEmpty()), "Invalid v2 final-review source disposition");
        if (status == WorkerStatus.COMPLETED) CaseInputs.require(reason.equals("NONE") && decision != null && bootstrap != null
                && new JsonPrimitive("finish").equals(decision.get("action")), "Incomplete v2 final-review source");
        else CaseInputs.require(!reason.equals("NONE") && decision == null, "Stopped v2 source cannot supply an answer");
    }
    private void freshClaim(JsonObject outcome, String position, String kind, JsonElement statement, JsonElement pages) {
        var claim = json(Map.of("referenceId", "freshclaim-" + (evidence.getAsJsonArray("freshClaims").size() + 1),
                "workerReferenceId", outcome.get("referenceId"), "workerId", outcome.get("workerId"), "inputIdentity", inputs.identity(),
                "reportIdentity", outcome.get("reportIdentity"), "position", position, "kind", kind, "statement", statement.deepCopy(), "workerPageIds", pages.deepCopy()));
        claim.addProperty("authority", "fresh-model-advice-not-execution-or-semantic-acceptance");
        evidence.getAsJsonArray("freshClaims").add(claim); addReference(claim);
    }
    private void addReference(JsonObject value) {
        String id = value.get("referenceId").getAsString(); references.put(id, value);
        if (value.has("workerReferenceId") && references.get(value.get("workerReferenceId").getAsString()).get("response").getAsString().equals("ANSWERED"))
            answeredClaims.put(id, value.get("workerId").getAsString());
    }
    JsonObject evidence() { return evidence.deepCopy(); }
    String context(JsonObject bootstrap, List<JsonObject> reads, CoordinationTeamBudget.Allocation allocation,
                   CoordinationTeamBudget.Reservations reserved, int byteLimit) {
        CaseInputs.require(allocation.ceiling() <= 60 && allocation.equals(CoordinationTeamBudget.forCeiling(allocation.ceiling()))
                && byteLimit >= 1 && byteLimit <= 64000, "Invalid v2 reconciliation context limits or allocation");
        int remaining = allocation.remaining(CoordinationTeamBudget.Stage.RECONCILIATION, reserved);
        if (remaining == 0) throw new CoordinationRuntime.Stop("CALL_BUDGET_EXHAUSTED");
        var context = JsonParser.parseString(InvoiceContextV2.build(reconciler, inputs, bootstrap, reads,
                new InvoiceContextV2.Scheduling(remaining, 1), 64000)).getAsJsonObject();
        context.addProperty("protocol", InvoiceReconciliationDecisionV2.PROTOCOL); context.addProperty("contractIdentity", InvoiceReconciliationDecisionV2.contractIdentity());
        context.addProperty("contextPolicy", CONTEXT); context.add("history", evidence());
        context.add("teamStage", json(Map.of("stage", "RECONCILIATION", "allocation", allocation, "reserved", reserved,
                "callsRemainingInReconciliationStage", remaining, "budgetPolicy", CoordinationTeamBudget.POLICY,
                "authority", "caller-supplied-final-stage-snapshot-not-permission-or-new-pool")));
        var recent = context.getAsJsonArray("recentPages"); String input = InvoiceDecisionV2.canonical(context);
        while (bytes(input) > byteLimit && !recent.isEmpty()) { recent.remove(0); input = InvoiceDecisionV2.canonical(context); }
        if (bytes(input) > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED"); return input;
    }
    JsonObject accept(JsonObject decision, JsonObject bootstrap, List<JsonObject> reads) {
        var parsed = InvoiceReconciliationDecisionV2.parse(JSON.toJson(Map.of("decision", decision)));
        CaseInputs.require(new JsonPrimitive("finish").equals(parsed.get("action")), "Expected a v2 final reconciliation finish");
        var readiness = InvoiceContextV2.readiness(reconciler, inputs, bootstrap, reads); var eligible = new TreeMap<>(references);
        for (var read : reads) if (readiness.citableTextPageIds().contains(read.get("operationId").getAsString())) eligible.put(read.get("operationId").getAsString(), read);
        var groups = new ArrayList<JsonArray>(); groups.add(parsed.getAsJsonArray("summaryCitations"));
        parsed.getAsJsonArray("findings").forEach(f -> groups.add(f.getAsJsonObject().getAsJsonArray("citations")));
        var cited = new HashSet<String>();
        for (var group : groups) for (var id : group) { CaseInputs.require(eligible.containsKey(id.getAsString()), "Unseen v2 final-review reference"); cited.add(id.getAsString()); }
        if (parsed.get("response").getAsString().equals("ANSWERED")) {
            boolean roles = cited.stream().filter(answeredClaims::containsKey).map(answeredClaims::get).collect(java.util.stream.Collectors.toSet()).containsAll(InvoiceWorkerDecision.WORKERS);
            boolean own = readiness.structuralAccessReady() && cited.stream().anyMatch(readiness.citableTextPageIds()::contains);
            CaseInputs.require(!parsed.getAsJsonArray("summaryCitations").isEmpty() && !parsed.getAsJsonArray("findings").isEmpty() && (roles || own), "Unsupported joint v2 advisory dossier");
        }
        var identities = new TreeMap<String, String>(); eligible.forEach((id, value) -> identities.put(id, hash(value)));
        return json(Map.of("formatVersion", 2, "protocol", InvoiceReconciliationDecisionV2.PROTOCOL, "contractIdentity", InvoiceReconciliationDecisionV2.contractIdentity(),
                "history", evidence(), "decision", parsed, "readiness", readiness.toJson(), "referenceIdentities", identities,
                "qualityReview", "NOT_PERFORMED", "authority", "validated-final-advice-not-runtime-completion-or-semantic-acceptance"));
    }
    private static JsonObject nullable(JsonObject value, String field) { return value.get(field).isJsonNull() ? null : value.getAsJsonObject(field); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String hash(JsonElement value) { return CaseInputs.hash(InvoiceDecisionV2.canonical(value).getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
}
