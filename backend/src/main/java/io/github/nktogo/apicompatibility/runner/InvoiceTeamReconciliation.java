package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Pure lineage and access guards over host-authenticated invoice histories; no dispatch. */
final class InvoiceTeamReconciliation {
    static final String CONTEXT = "invoice-all-original-fresh-claims-outcomes-questions-conflicts-own-metadata-latest-six-v1";
    static final String SELECTION = "invoice-one-final-joint-dossier-no-fallback-v1";
    private final JsonObject evidence;
    private final Map<String, JsonObject> references = new TreeMap<>();
    private final Map<String, String> answeredClaims = new HashMap<>();
    private final boolean sourceCoverage;

    static JsonObject selection(JsonObject prefix) {
        var workers = originals(prefix); var review = prefix.getAsJsonObject("coordinatorReview");
        var pages = review.getAsJsonArray("pages").asList().stream().map(JsonElement::getAsJsonObject).toList(); InvoiceTeamReview.validatePages(pages);
        if (!review.get("status").getAsString().equals("COMPLETED")) {
            CaseInputs.require(review.get("selection").isJsonNull(), "Stopped invoice review cannot select work"); return null;
        }
        var selected = review.getAsJsonObject("selection");
        CaseInputs.require(review.get("stopReason").getAsString().equals("NONE") && selected.equals(new InvoiceTeamReview(workers).select(selected.getAsJsonObject("reviewDecision"), pages)), "Changed invoice follow-up selection");
        return selected.deepCopy();
    }
    private static List<InvoiceTeamReview.WorkerResult> originals(JsonObject prefix) {
        var workers = prefix.getAsJsonArray("workers").asList().stream().map(v -> InvoiceTeamReview.retainedWorker(v.getAsJsonObject())).toList();
        new InvoiceTeamReview(workers); return workers;
    }
    static String freshParticipant(String worker) { InvoiceWorkerDecision.protocol(worker); return "fresh-" + worker; }
    static String freshContext(JsonObject prefix, JsonObject question, JsonObject metadata, List<JsonObject> pages, int remaining, int bytes) {
        var selected = selection(prefix); CaseInputs.require(selected != null && selected.getAsJsonArray("selectedFollowUps").asList().contains(question), "Unselected invoice question");
        String worker = question.get("workerId").getAsString(); var description = metadata.deepCopy(); description.addProperty("workerId", worker);
        description.addProperty("workerRole", InvoiceWorkerDecision.role(worker)); InvoiceTeamReview.validatePages(pages);
        var context = JsonParser.parseString(InvoiceSingleAgent.context(description, pages, remaining, bytes)).getAsJsonObject();
        var anchors = new TreeMap<String, JsonObject>();
        for (String group : List.of("workers", "originalClaims")) for (var value : selected.getAsJsonArray(group)) { var v = value.getAsJsonObject(); anchors.put(v.get("referenceId").getAsString(), v); }
        for (var value : prefix.getAsJsonObject("coordinatorReview").getAsJsonArray("pages")) { var v = value.getAsJsonObject(); anchors.put(v.get("pageId").getAsString(), v); }
        var chosen = new JsonArray(); for (var id : question.getAsJsonArray("citations")) chosen.add(anchors.get(id.getAsString()).deepCopy());
        context.add("delegatedQuestion", question.deepCopy()); context.add("questionAnchors", chosen); context.addProperty("delegationDepth", 1);
        context.addProperty("contentTrust", "untrusted-data"); return CapturedTeamReconciliation.bounded(context, bytes);
    }
    InvoiceTeamReconciliation(JsonObject prefix, JsonArray fresh) {
        var originals = originals(prefix); var review = new InvoiceTeamReview(originals);
        var inventory = JsonParser.parseString(review.context(List.of(), 1, 64000)).getAsJsonObject(); var selection = selection(prefix);
        var questions = selection == null ? new JsonArray() : selection.getAsJsonArray("selectedFollowUps");
        CaseInputs.require(fresh.size() == questions.size(), "Missing or extra invoice follow-up outcome");
        sourceCoverage = originals.stream().anyMatch(w -> w.metadata() != null && w.metadata().get("sourceExecutionCoverageSatisfied").getAsBoolean());
        var disposition = prefix.getAsJsonObject("coordinatorReview");
        evidence = json(Map.of("workers", inventory.get("workers"), "originalClaims", inventory.get("originalClaims"),
                "coordinatorReview", json(Map.of("status", disposition.get("status"), "stopReason", disposition.get("stopReason"), "selection", selection == null ? JsonNull.INSTANCE : selection)),
                "freshWorkers", new JsonArray(), "freshClaims", new JsonArray(), "possibleConflicts", selection == null ? new JsonArray() : selection.get("possibleConflicts"),
                "qualityReview", "NOT_PERFORMED", "contentTrust", "untrusted-data"));
        for (var w : inventory.getAsJsonArray("workers")) references.put(w.getAsJsonObject().get("referenceId").getAsString(), w.getAsJsonObject());
        for (var c : inventory.getAsJsonArray("originalClaims")) addReference(c.getAsJsonObject());
        for (int i = 0; i < fresh.size(); i++) {
            var entry = fresh.get(i).getAsJsonObject(); var question = questions.get(i).getAsJsonObject();
            CaseInputs.require(entry.keySet().equals(Set.of("question", "participant", "worker")) && entry.get("question").equals(question), "Changed invoice question lineage or order");
            var worker = InvoiceTeamReview.retainedWorker(entry.getAsJsonObject("worker"));
            CaseInputs.require(worker.workerId().equals(question.get("workerId").getAsString()) && question.get("inputIdentity").equals(CaseInputs.JSON.toJsonTree(worker.inputIdentity()))
                    && entry.get("participant").getAsString().equals(freshParticipant(worker.workerId())), "Foreign invoice follow-up worker");
            var outcome = json(Map.of("referenceId", "freshworker-" + (i + 1), "workerId", worker.workerId(), "inputIdentity", worker.inputIdentity(), "status", worker.status(), "stopReason", worker.stopReason(),
                    "question", question, "participant", entry.get("participant"), "reportIdentity", digest(encoded(json(Map.of("question", question, "worker", worker)))),
                    "response", worker.finishDecision() == null ? "NOT_PROVIDED" : worker.finishDecision().get("response").getAsString()));
            evidence.getAsJsonArray("freshWorkers").add(outcome); references.put(outcome.get("referenceId").getAsString(), outcome);
            if (worker.finishDecision() == null) continue; var d = worker.finishDecision();
            freshClaim(outcome, "summary", "SUMMARY", d.get("summary"), d.get("summaryCitations")); int n = 0;
            for (var f : d.getAsJsonArray("findings")) { var v = f.getAsJsonObject(); freshClaim(outcome, "findings/" + n++, v.get("kind").getAsString(), v.get("statement"), v.get("citations")); }
        }
    }
    private void freshClaim(JsonObject worker, String position, String kind, JsonElement statement, JsonElement pages) {
        var claim = worker.deepCopy(); claim.remove("question"); claim.addProperty("referenceId", "freshclaim-" + (evidence.getAsJsonArray("freshClaims").size() + 1));
        claim.add("workerReferenceId", worker.get("referenceId")); claim.addProperty("position", position); claim.addProperty("kind", kind);
        claim.add("statement", statement.deepCopy()); claim.add("workerPageIds", pages.deepCopy()); claim.addProperty("authority", "fresh-model-advice-not-execution-or-semantic-acceptance");
        evidence.getAsJsonArray("freshClaims").add(claim); addReference(claim);
    }
    private void addReference(JsonObject claim) {
        String id = claim.get("referenceId").getAsString(); references.put(id, claim);
        if (references.get(claim.get("workerReferenceId").getAsString()).get("response").getAsString().equals("ANSWERED")) answeredClaims.put(id, claim.get("workerId").getAsString());
    }
    JsonObject evidence() { return evidence.deepCopy(); }
    String context(List<JsonObject> pages, int remaining, int bytes) {
        CaseInputs.require(remaining >= 1 && remaining <= 500, "Invalid invoice reconciliation budget"); InvoiceTeamReview.validatePages(pages);
        var context = evidence.deepCopy(); var metadata = new JsonArray();
        for (var p : pages) { var copy = p.deepCopy(); copy.remove("content"); copy.remove("artifacts"); metadata.add(copy); }
        var recent = new JsonArray(); pages.subList(Math.max(0, pages.size() - 6), pages.size()).forEach(p -> recent.add(p.deepCopy()));
        context.add("readMetadata", metadata); context.add("recentPages", recent); context.addProperty("decisionsRemaining", remaining);
        return CapturedTeamReconciliation.bounded(context, bytes);
    }
    void validate(JsonObject decision, List<JsonObject> pages) {
        var d = InvoiceReconciliationDecision.parse(CaseInputs.JSON.toJson(Map.of("decision", decision))); if (!d.get("action").getAsString().equals("finish")) return;
        InvoiceTeamReview.validatePages(pages); var eligible = new TreeMap<>(references); var own = new HashSet<String>();
        pages.stream().filter(p -> p.get("kind").getAsString().equals("read_text")).forEach(p -> { String id = p.get("pageId").getAsString(); own.add(id); eligible.put(id, p); });
        var groups = new ArrayList<JsonArray>(); groups.add(d.getAsJsonArray("summaryCitations")); d.getAsJsonArray("findings").forEach(f -> groups.add(f.getAsJsonObject().getAsJsonArray("citations")));
        var cited = new HashSet<String>(); for (var group : groups) for (var id : group) { CaseInputs.require(eligible.containsKey(id.getAsString()), "Unseen invoice reconciliation reference"); cited.add(id.getAsString()); }
        if (d.get("response").getAsString().equals("ABSTAINED")) return;
        boolean bothRoles = cited.stream().filter(answeredClaims::containsKey).map(answeredClaims::get).collect(java.util.stream.Collectors.toSet()).containsAll(InvoiceWorkerDecision.WORKERS);
        boolean ownAccess = sourceCoverage && cited.stream().anyMatch(own::contains) && InvoiceInvestigationDecision.REQUIRED.stream().allMatch(a -> InvoiceSingleAgent.complete(pages, a))
                && pages.stream().anyMatch(p -> p.has("alias") && p.get("alias").getAsString().startsWith("business/") && InvoiceSingleAgent.complete(pages, p.get("alias").getAsString()))
                && pages.stream().anyMatch(p -> p.has("alias") && p.get("alias").getAsString().startsWith("order/") && InvoiceSingleAgent.complete(pages, p.get("alias").getAsString()));
        CaseInputs.require(!d.getAsJsonArray("summaryCitations").isEmpty() && !d.getAsJsonArray("findings").isEmpty() && (bothRoles || ownAccess), "Unsupported joint invoice answer");
    }
}
