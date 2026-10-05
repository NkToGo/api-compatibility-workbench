package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Pure lineage/context guards over host-authenticated stage records. No permissions or dispatch. */
final class CapturedTeamReconciliation {
    static final String CONTEXT = "all-original-and-fresh-claims-outcomes-questions-conflicts-all-own-read-metadata-latest-six-contents-v1";
    static final String SELECTION = "final-reconciliation-only-no-fallback-or-best-attempt-v1";
    private final JsonObject evidence;
    private final Map<String, JsonObject> references = new TreeMap<>();
    private final Set<String> answeredClaims = new HashSet<>();
    CapturedTeamReconciliation(JsonObject prefix, JsonArray fresh) {
        var workers = new ArrayList<CapturedTeamReview.WorkerResult>();
        for (var w : prefix.getAsJsonArray("workers")) workers.add(CapturedTeamReview.retainedWorker(w.getAsJsonObject()));
        var original = JsonParser.parseString(new CapturedTeamReview(workers).context(List.of(), 1, 64000)).getAsJsonObject();
        var review = prefix.getAsJsonObject("coordinatorReview"); var selection = review.get("selection");
        var advice = json(Map.of("status", review.get("status"), "stopReason", review.get("stopReason"),
                "decision", selection.isJsonNull() ? JsonNull.INSTANCE : selection.getAsJsonObject().get("reviewDecision"),
                "selectedFollowUps", selection.isJsonNull() ? new JsonArray() : selection.getAsJsonObject().get("selectedFollowUps")));
        evidence = json(Map.of("workers", original.get("workers"), "originalClaims", original.get("originalClaims"),
                "coordinatorReview", advice, "freshWorkers", new JsonArray(), "freshClaims", new JsonArray(),
                "possibleConflicts", conflicts(prefix), "qualityReview", "NOT_PERFORMED", "contentTrust", "untrusted-data"));
        addReferences(original.getAsJsonArray("workers"), original.getAsJsonArray("originalClaims"));
        for (var item : fresh) {
            var entry = item.getAsJsonObject(); var worker = CapturedTeamReview.retainedWorker(entry.getAsJsonObject("worker"));
            String id = "freshworker-" + (evidence.getAsJsonArray("freshWorkers").size() + 1);
            var disposition = json(Map.of("referenceId", id, "caseId", worker.task().caseId(), "inputIdentity", worker.task().inputIdentity(), "status", worker.status(),
                    "stopReason", worker.stopReason(), "question", entry.get("question"), "participant", entry.get("participant"),
                    "reportIdentity", digest(encoded(json(Map.of("worker", worker, "question", entry.get("question"))))),
                    "response", worker.finishDecision() == null ? "NOT_PROVIDED" : worker.finishDecision().getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString()));
            evidence.getAsJsonArray("freshWorkers").add(disposition); references.put(id, disposition);
            if (worker.finishDecision() == null) continue;
            var dossier = worker.finishDecision().getAsJsonArray("cases").get(0).getAsJsonObject();
            freshClaim(disposition, "summary", "SUMMARY", dossier.get("summary"), dossier.get("summaryCitations"));
            int n = 0; for (var f : dossier.getAsJsonArray("findings")) {
                var finding = f.getAsJsonObject(); freshClaim(disposition, "findings/" + n++, finding.get("kind").getAsString(), finding.get("statement"), finding.get("citations"));
            }
        }
    }
    private static JsonArray conflicts(JsonObject prefix) {
        var selection = prefix.getAsJsonObject("coordinatorReview").get("selection");
        return selection.isJsonNull() ? new JsonArray() : selection.getAsJsonObject().getAsJsonArray("possibleConflicts").deepCopy();
    }
    private void addReferences(JsonArray workers, JsonArray claims) {
        for (var w : workers) references.put(w.getAsJsonObject().get("referenceId").getAsString(), w.getAsJsonObject());
        for (var c : claims) {
            var claim = c.getAsJsonObject(); String id = claim.get("referenceId").getAsString(); references.put(id, claim);
            if (references.get(claim.get("workerReferenceId").getAsString()).get("response").getAsString().equals("ANSWERED")) answeredClaims.add(id);
        }
    }
    private void freshClaim(JsonObject worker, String position, String kind, JsonElement statement, JsonElement pages) {
        String id = "freshclaim-" + (evidence.getAsJsonArray("freshClaims").size() + 1);
        var claim = json(Map.of("referenceId", id, "caseId", worker.get("caseId"), "inputIdentity", worker.get("inputIdentity"), "workerReferenceId", worker.get("referenceId"),
                "workerReportIdentity", worker.get("reportIdentity"), "position", position, "kind", kind, "statement", statement, "workerPageIds", pages,
                "authority", "fresh-model-advice-not-execution-or-semantic-acceptance"));
        evidence.getAsJsonArray("freshClaims").add(claim); references.put(id, claim);
        if (worker.get("response").getAsString().equals("ANSWERED")) answeredClaims.add(id);
    }
    JsonObject evidence() { return evidence.deepCopy(); }
    String context(List<JsonObject> pages, int remaining, int byteLimit) {
        CaseInputs.require(remaining >= 1 && remaining <= 500, "Invalid reconciliation decision budget");
        CapturedTeamReview.validatePages(pages, Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID));
        var context = evidence.deepCopy(); context.add("readMetadata", CapturedTeamReview.metadata(pages));
        var recent = new JsonArray(); pages.subList(Math.max(0, pages.size() - 6), pages.size()).forEach(p -> recent.add(p.deepCopy()));
        context.add("recentPages", recent); context.addProperty("decisionsRemaining", remaining);
        return bounded(context, byteLimit);
    }
    static String bounded(JsonObject context, int byteLimit) {
        CaseInputs.require(byteLimit >= 1 && byteLimit <= 64000, "Invalid team context ceiling");
        String input = encoded(context); var recent = context.getAsJsonArray("recentPages");
        while (input.getBytes(StandardCharsets.UTF_8).length > byteLimit && !recent.isEmpty()) { recent.remove(0); input = encoded(context); }
        if (input.getBytes(StandardCharsets.UTF_8).length > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED"); return input;
    }
    void validate(JsonObject decision, List<JsonObject> pages) {
        if (!decision.get("action").getAsString().equals("finish")) return;
        CapturedTeamReview.validatePages(pages, Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)); var eligible = new TreeMap<>(references);
        pages.forEach(p -> eligible.put(p.get("pageId").getAsString(), p));
        for (var item : decision.getAsJsonArray("cases")) {
            var dossier = item.getAsJsonObject(); String caseId = dossier.get("caseId").getAsString(); var cites = new HashSet<String>();
            List<JsonArray> groups = new ArrayList<>(); groups.add(dossier.getAsJsonArray("summaryCitations"));
            dossier.getAsJsonArray("findings").forEach(f -> groups.add(f.getAsJsonObject().getAsJsonArray("citations")));
            for (var group : groups) for (var v : group) {
                String id = v.getAsString(); CaseInputs.require(eligible.containsKey(id) && eligible.get(id).get("caseId").getAsString().equals(caseId), "Unseen or foreign reconciliation reference"); cites.add(id);
            }
            if (dossier.get("response").getAsString().equals("ANSWERED")) CaseInputs.require(!dossier.getAsJsonArray("summaryCitations").isEmpty()
                    && !dossier.getAsJsonArray("findings").isEmpty() && (cites.stream().anyMatch(answeredClaims::contains)
                    || CoordinationSingleAgent.complete(pages, caseId, "baseline-source") && CoordinationSingleAgent.complete(pages, caseId, "execution")), "Unsupported reconciled answer");
        }
    }
}
