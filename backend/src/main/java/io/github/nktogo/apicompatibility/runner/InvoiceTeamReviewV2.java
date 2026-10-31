package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure coordinator context and inert selection over caller-admitted v2 histories; no dispatch or permission. */
final class InvoiceTeamReviewV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final String CONTEXT = "invoice-v2-owned-coordinator-context-all-original-claims-readiness-stage-snapshot";
    static final String SELECTION = "invoice-all-valid-questions-sorted-by-worker-once-depth-one-v2";
    enum WorkerStatus { COMPLETED, FAILED, CANCELLED, UNKNOWN_REMOTE_OUTCOME }
    record WorkerResult(String workerId, ContentDigest inputIdentity, String protocol, String contractIdentity, String publicTaskSha256, WorkerStatus status, String stopReason,
                        JsonObject finishDecision, JsonObject bootstrap, List<JsonObject> reads) {
        WorkerResult {
            scope(workerId); Objects.requireNonNull(inputIdentity); Objects.requireNonNull(status);
            CaseInputs.require(InvoiceDecisionV2.protocol(scope(workerId)).equals(protocol)
                    && InvoiceDecisionV2.contractIdentity(scope(workerId)).equals(contractIdentity)
                    && InvoiceDecisionV2.taskIdentity().equals(publicTaskSha256), "Original worker requires its exact v2 role/task contract");
            CaseInputs.require(stopReason != null && stopReason.matches("[A-Z][A-Z0-9_]{0,99}"), "Invalid v2 worker stop reason");
            CaseInputs.require(reads != null && reads.size() <= InvoiceReadiness.MAX_PAGES, "Too many original v2 worker reads");
            reads = reads.stream().map(JsonObject::deepCopy).toList(); bootstrap = bootstrap == null ? null : bootstrap.deepCopy();
            CaseInputs.require(bootstrap != null || reads.isEmpty(), "Worker reads need a captured bootstrap");
            if (status == WorkerStatus.COMPLETED) {
                CaseInputs.require(stopReason.equals("NONE") && finishDecision != null && bootstrap != null, "Incomplete v2 worker dossier");
                finishDecision = InvoiceDecisionV2.parse(JSON.toJson(Map.of("decision", finishDecision)));
                CaseInputs.require(finishDecision.get("action").getAsString().equals("finish"), "Expected v2 worker finish");
            } else CaseInputs.require(finishDecision == null && !stopReason.equals("NONE"), "Stopped worker cannot supply a completed dossier");
        }
        @Override public JsonObject finishDecision() { return finishDecision == null ? null : finishDecision.deepCopy(); }
        @Override public JsonObject bootstrap() { return bootstrap == null ? null : bootstrap.deepCopy(); }
        @Override public List<JsonObject> reads() { return reads.stream().map(JsonObject::deepCopy).toList(); }
    }
    private final InvoiceComparisonInputs.Bundle inputs;
    private final InvoiceReadiness.Subject coordinator;
    private final JsonArray workers = new JsonArray(), claims = new JsonArray();
    private final Map<String, JsonObject> references = new TreeMap<>();

    InvoiceTeamReviewV2(String coordinatorId, InvoiceComparisonInputs.Bundle inputs, List<WorkerResult> originals) {
        this.inputs = Objects.requireNonNull(inputs);
        CaseInputs.require(!InvoiceWorkerDecision.WORKERS.contains(coordinatorId), "Coordinator must have its own participant");
        coordinator = new InvoiceReadiness.Subject(coordinatorId, inputs.identity(), InvoiceReadiness.Scope.JOINT);
        var sorted = originals.stream().sorted(Comparator.comparing(WorkerResult::workerId)).toList();
        CaseInputs.require(sorted.size() == 2 && sorted.stream().map(WorkerResult::workerId).toList().equals(InvoiceWorkerDecision.WORKERS), "Both original v2 worker outcomes required");
        for (var worker : sorted) {
            CaseInputs.require(worker.inputIdentity().equals(inputs.identity()), "Worker input differs from opened v2 bundle");
            var subject = new InvoiceReadiness.Subject(worker.workerId(), inputs.identity(), scope(worker.workerId()));
            var readiness = worker.bootstrap == null ? null : InvoiceContextV2.readiness(subject, inputs, worker.bootstrap, worker.reads);
            if (worker.finishDecision != null) CaseInputs.require(InvoiceFinishV2.supported(subject, worker.finishDecision, Objects.requireNonNull(readiness)), "Unsupported original v2 worker dossier");
            var outcome = json(Map.of("referenceId", "worker-" + (workers.size() + 1), "workerId", worker.workerId(),
                    "workerRole", InvoiceWorkerDecision.role(worker.workerId()), "caseId", InvoiceRenewalFixtures.CASE_ID, "inputIdentity", inputs.identity(),
                    "status", worker.status(), "stopReason", worker.stopReason(), "response", worker.finishDecision == null ? "NOT_PROVIDED" : worker.finishDecision.get("response").getAsString(),
                    "reportIdentity", hash(JSON.toJsonTree(worker)), "authority", "host-recorded-worker-disposition-not-answer-quality"));
            outcome.add("readiness", readiness == null ? JsonNull.INSTANCE : readiness.toJson());
            outcome.addProperty("protocol", worker.protocol()); outcome.addProperty("contractIdentity", worker.contractIdentity());
            outcome.addProperty("publicTaskSha256", worker.publicTaskSha256());
            workers.add(outcome); references.put(outcome.get("referenceId").getAsString(), outcome);
            if (worker.finishDecision == null) continue;
            addClaim(outcome, "summary", "SUMMARY", worker.finishDecision.get("summary").getAsString(), worker.finishDecision.getAsJsonArray("summaryCitations"));
            int index = 0;
            for (var value : worker.finishDecision.getAsJsonArray("findings")) {
                var finding = value.getAsJsonObject(); addClaim(outcome, "findings/" + index++, finding.get("kind").getAsString(), finding.get("statement").getAsString(), finding.getAsJsonArray("citations"));
            }
        }
    }
    static WorkerResult retainedWorker(JsonObject value) {
        CaseInputs.require(value != null && value.keySet().equals(Set.of("workerId", "inputIdentity", "protocol", "contractIdentity", "publicTaskSha256",
                "status", "stopReason", "finishDecision", "bootstrap", "reads")), "Invalid v2 worker history shape");
        var worker = new WorkerResult(value.get("workerId").getAsString(), JSON.fromJson(value.get("inputIdentity"), ContentDigest.class),
                value.get("protocol").getAsString(), value.get("contractIdentity").getAsString(), value.get("publicTaskSha256").getAsString(), WorkerStatus.valueOf(value.get("status").getAsString()),
                value.get("stopReason").getAsString(), value.get("finishDecision").isJsonNull() ? null : value.getAsJsonObject("finishDecision"),
                value.get("bootstrap").isJsonNull() ? null : value.getAsJsonObject("bootstrap"),
                value.getAsJsonArray("reads").asList().stream().map(JsonElement::getAsJsonObject).toList());
        CaseInputs.require(value.equals(JSON.toJsonTree(worker)), "Changed v2 worker history shape"); return worker;
    }
    private void addClaim(JsonObject worker, String position, String kind, String statement, JsonArray citations) {
        var claim = json(Map.of("referenceId", "claim-" + (claims.size() + 1), "workerReferenceId", worker.get("referenceId"),
                "workerId", worker.get("workerId"), "caseId", InvoiceRenewalFixtures.CASE_ID, "inputIdentity", inputs.identity(),
                "reportIdentity", worker.get("reportIdentity"), "position", position, "kind", kind, "statement", statement,
                "workerPageIds", citations.deepCopy()));
        claim.addProperty("authority", "original-model-advice-not-execution-or-semantic-acceptance");
        claims.add(claim); references.put(claim.get("referenceId").getAsString(), claim);
    }
    String context(JsonObject bootstrap, List<JsonObject> reads, CoordinationTeamBudget.Allocation allocation,
                   CoordinationTeamBudget.Reservations reserved, int byteLimit) {
        CaseInputs.require(allocation.ceiling() <= 60 && allocation.equals(CoordinationTeamBudget.forCeiling(allocation.ceiling()))
                && byteLimit >= 1 && byteLimit <= 64000, "Invalid v2 coordinator context limits or stage allocation");
        int remaining = allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, reserved);
        if (remaining == 0) throw new CoordinationRuntime.Stop("CALL_BUDGET_EXHAUSTED");
        var context = JsonParser.parseString(InvoiceContextV2.build(coordinator, inputs, bootstrap, reads,
                new InvoiceContextV2.Scheduling(remaining, 1), 64000)).getAsJsonObject();
        context.addProperty("protocol", InvoiceTeamDecisionV2.PROTOCOL); context.addProperty("contractIdentity", InvoiceTeamDecisionV2.contractIdentity());
        context.addProperty("contextPolicy", CONTEXT); context.add("workers", workers.deepCopy()); context.add("originalClaims", claims.deepCopy());
        context.add("teamStage", json(Map.of("stage", "REVIEW", "allocation", allocation, "reserved", reserved,
                "callsRemainingInReviewStage", remaining, "budgetPolicy", CoordinationTeamBudget.POLICY,
                "authority", "caller-supplied-stage-snapshot-not-grant-or-new-trial-pool")));
        context.addProperty("authority", "validated-review-context-not-runtime-admission-or-semantic-acceptance");
        var recent = context.getAsJsonArray("recentPages"); String input = InvoiceDecisionV2.canonical(context);
        while (bytes(input) > byteLimit && !recent.isEmpty()) { recent.remove(0); input = InvoiceDecisionV2.canonical(context); }
        if (bytes(input) > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED"); return input;
    }
    JsonObject select(JsonObject decision, JsonObject bootstrap, List<JsonObject> reads) {
        var d = InvoiceTeamDecisionV2.parse(JSON.toJson(Map.of("decision", decision)));
        CaseInputs.require(d.get("action").getAsString().equals("finish"), "Only a finished v2 review selects questions");
        var readiness = InvoiceContextV2.readiness(coordinator, inputs, bootstrap, reads);
        var eligible = new TreeMap<>(references);
        for (var read : reads) if (readiness.citableTextPageIds().contains(read.get("operationId").getAsString())) eligible.put(read.get("operationId").getAsString(), read);
        requireReferences(CapturedTeamDecision.references(d, "citations", 1, 6, false), eligible);
        var selected = new JsonArray();
        var questions = d.getAsJsonArray("followUps").asList().stream().map(JsonElement::getAsJsonObject).sorted(Comparator.comparing(q -> q.get("workerId").getAsString())).toList();
        for (var question : questions) {
            String worker = question.get("workerId").getAsString(); var ids = CapturedTeamDecision.references(question, "citations", 1, 6, false); requireReferences(ids, eligible);
            CaseInputs.require(ids.stream().anyMatch(id -> references.containsKey(id) && references.get(id).get("workerId").getAsString().equals(worker)), "V2 question needs an original target-role anchor");
            var proposal = question.deepCopy(); proposal.addProperty("proposalId", "follow-up-" + (selected.size() + 1)); proposal.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            proposal.add("inputIdentity", JSON.toJsonTree(inputs.identity())); proposal.addProperty("delegationDepth", 1);
            proposal.addProperty("authority", "selected-advisory-question-not-grant-or-evidence"); selected.add(proposal);
        }
        var conflicts = new JsonArray();
        for (var item : d.getAsJsonArray("conflicts")) {
            var conflict = item.getAsJsonObject().deepCopy(); var ids = CapturedTeamDecision.references(conflict, "claimIds", 2, 2, true); requireReferences(ids, references);
            conflict.addProperty("conflictId", "conflict-" + (conflicts.size() + 1)); conflict.addProperty("state", "UNRESOLVED");
            conflict.addProperty("comparability", ids.stream().map(id -> references.get(id).get("workerId").getAsString()).distinct().count() == 2
                    ? "SAME_CASE_DIFFERENT_ROLES_SCOPE_UNESTABLISHED" : "SAME_ROLE_SCOPE_UNESTABLISHED");
            conflict.addProperty("authority", "possible-conflict-model-advice-not-proven-contradiction"); conflicts.add(conflict);
        }
        var identities = new TreeMap<String, String>(); eligible.forEach((id, value) -> identities.put(id, hash(value)));
        var result = json(Map.of("formatVersion", 2, "inputIdentity", inputs.identity(), "workers", workers.deepCopy(), "originalClaims", claims.deepCopy(),
                "reviewDecision", d, "selectedFollowUps", selected, "possibleConflicts", conflicts, "referenceIdentities", identities,
                "selectionPolicy", SELECTION, "qualityReview", "NOT_PERFORMED"));
        result.addProperty("protocol", InvoiceTeamDecisionV2.PROTOCOL); result.addProperty("contractIdentity", InvoiceTeamDecisionV2.contractIdentity());
        result.addProperty("publicTaskSha256", InvoiceDecisionV2.taskIdentity()); return result;
    }
    private static InvoiceReadiness.Scope scope(String worker) {
        return switch (worker) {
            case "invoice-webhook" -> InvoiceReadiness.Scope.WEBHOOK;
            case "invoice-reconciliation" -> InvoiceReadiness.Scope.RECONCILIATION;
            default -> throw new IllegalArgumentException("Unknown fixed v2 worker");
        };
    }
    private static void requireReferences(Set<String> ids, Map<String, JsonObject> eligible) {
        for (String id : ids) CaseInputs.require(eligible.containsKey(id), "Unseen v2 coordinator reference");
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String hash(JsonElement value) { return CaseInputs.hash(InvoiceDecisionV2.canonical(value).getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
}
