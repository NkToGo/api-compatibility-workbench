package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Pure host guards over verified worker histories; no model call, filesystem, grant or dispatch. */
final class CapturedTeamReview {
    static final String SELECTION = "all-valid-questions-sorted-by-case-id-once-v1";
    static final String CONTEXT = "all-original-claims-and-outcomes-all-review-read-metadata-latest-six-contents-oldest-first-byte-trim-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    enum WorkerStatus { COMPLETED, FAILED, CANCELLED, UNKNOWN_REMOTE_OUTCOME }
    record WorkerResult(CaseTask task, WorkerStatus status, String stopReason, JsonObject finishDecision, List<JsonObject> pages) {
        WorkerResult {
            Objects.requireNonNull(task); Objects.requireNonNull(status);
            CaseInputs.require(stopReason != null && stopReason.matches("[A-Z][A-Z0-9_]{0,99}"), "Invalid worker stop reason");
            pages = pages.stream().map(JsonObject::deepCopy).toList(); validatePages(pages, Set.of(task.caseId()));
            if (status == WorkerStatus.COMPLETED) {
                CaseInputs.require(stopReason.equals("NONE"), "Completed worker has a stop reason");
                CaseInputs.require(finishDecision != null, "Completed worker needs a dossier");
                finishDecision = CaseInvestigationDecision.parse(JSON.toJson(Map.of("decision", finishDecision)), task.caseId());
                CaseInputs.require(finishDecision.get("action").getAsString().equals("finish") && CoordinationSingleAgent.supported(finishDecision, pages), "Unsupported original worker dossier");
            } else CaseInputs.require(finishDecision == null && !stopReason.equals("NONE"), "Stopped worker cannot supply a completed dossier or success reason");
        }
        @Override public JsonObject finishDecision() { return finishDecision == null ? null : finishDecision.deepCopy(); }
        @Override public List<JsonObject> pages() { return pages.stream().map(JsonObject::deepCopy).toList(); }
    }
    private final List<WorkerResult> originals;
    private final JsonArray workers = new JsonArray(), claims = new JsonArray();
    private final Map<String, JsonObject> references = new TreeMap<>();
    CapturedTeamReview(List<WorkerResult> originals) {
        this.originals = originals.stream().sorted(Comparator.comparing(w -> w.task().caseId())).toList();
        CaseInputs.require(this.originals.size() == 2 && this.originals.stream().map(w -> w.task().caseId()).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)), "Both original worker outcomes required");
        for (var worker : this.originals) {
            String caseId = worker.task().caseId(), workerId = "worker-" + (workers.size() + 1);
            var outcome = json(Map.of("referenceId", workerId, "caseId", caseId, "inputIdentity", worker.task().inputIdentity(), "status", worker.status(),
                    "stopReason", worker.stopReason(), "response", worker.finishDecision == null ? "NOT_PROVIDED" : worker.finishDecision.getAsJsonArray("cases").get(0).getAsJsonObject().get("response").getAsString(),
                    "reportIdentity", hash(json(Map.of("inputIdentity", worker.task().inputIdentity(), "decision", worker.finishDecision == null ? JsonNull.INSTANCE : worker.finishDecision, "readMetadata", metadata(worker.pages)))),
                    "availableInputs", BundleInvestigationDecision.aliases(caseId), "authority", "host-recorded-worker-disposition-not-answer-quality"));
            workers.add(outcome); references.put(workerId, outcome);
            if (worker.finishDecision == null) continue;
            var dossier = worker.finishDecision.getAsJsonArray("cases").get(0).getAsJsonObject();
            addClaim(worker, outcome, "summary", "SUMMARY", dossier.get("summary").getAsString(), dossier.getAsJsonArray("summaryCitations"));
            int index = 0;
            for (var item : dossier.getAsJsonArray("findings")) {
                var finding = item.getAsJsonObject(); addClaim(worker, outcome, "findings/" + index++, finding.get("kind").getAsString(), finding.get("statement").getAsString(), finding.getAsJsonArray("citations"));
            }
        }
    }
    private void addClaim(WorkerResult worker, JsonObject outcome, String position, String kind, String statement, JsonArray citations) {
        String id = "claim-" + (claims.size() + 1); var claim = json(Map.of("referenceId", id, "caseId", worker.task().caseId(), "inputIdentity", worker.task().inputIdentity(),
                "workerReferenceId", outcome.get("referenceId").getAsString(), "workerReportIdentity", outcome.get("reportIdentity").getAsString(), "position", position,
                "kind", kind, "statement", statement, "workerPageIds", citations, "authority", "original-model-advice-not-execution-or-semantic-acceptance"));
        claims.add(claim); references.put(id, claim);
    }
    String context(List<JsonObject> reviewPages, int remaining, int byteLimit) {
        CaseInputs.require(remaining >= 1 && remaining <= 500 && byteLimit >= 1 && byteLimit <= 64000, "Invalid review context limits");
        validatePages(reviewPages, Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID));
        var recent = new JsonArray(); reviewPages.subList(Math.max(0, reviewPages.size() - 6), reviewPages.size()).forEach(p -> recent.add(p.deepCopy()));
        var context = json(Map.of("workers", workers, "originalClaims", claims, "readMetadata", metadata(reviewPages), "recentPages", recent,
                "decisionsRemaining", remaining, "qualityReview", "NOT_PERFORMED", "contentTrust", "untrusted-data"));
        var disclosed = context.getAsJsonArray("recentPages"); String input = encoded(context);
        while (input.getBytes(StandardCharsets.UTF_8).length > byteLimit && !disclosed.isEmpty()) { disclosed.remove(0); input = encoded(context); }
        if (input.getBytes(StandardCharsets.UTF_8).length > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED");
        return input;
    }
    JsonObject select(JsonObject decision, List<JsonObject> reviewPages) {
        var d = CapturedTeamDecision.parse(JSON.toJson(Map.of("decision", decision)));
        CaseInputs.require(d.get("action").getAsString().equals("finish"), "Only a finished review selects questions");
        validatePages(reviewPages, Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)); var eligible = new TreeMap<>(references);
        reviewPages.forEach(p -> eligible.put(p.get("pageId").getAsString(), p));
        requireReferences(CapturedTeamDecision.references(d, "citations", 1, 6, false), eligible, null);
        var questions = d.getAsJsonArray("followUps").asList().stream().map(JsonElement::getAsJsonObject).sorted(Comparator.comparing(q -> q.get("caseId").getAsString())).toList();
        var selected = new JsonArray();
        for (var question : questions) {
            String caseId = question.get("caseId").getAsString(); var citations = CapturedTeamDecision.references(question, "citations", 1, 6, false);
            requireReferences(citations, eligible, caseId);
            CaseInputs.require(citations.stream().anyMatch(references::containsKey), "Follow-up needs an original claim or outcome anchor");
            var proposal = question.deepCopy(); proposal.addProperty("proposalId", "follow-up-" + (selected.size() + 1));
            proposal.add("inputIdentity", JSON.toJsonTree(originals.stream().filter(w -> w.task().caseId().equals(caseId)).findFirst().orElseThrow().task().inputIdentity()));
            proposal.addProperty("delegationDepth", 1); proposal.addProperty("authority", "selected-advisory-question-not-grant-or-evidence"); selected.add(proposal);
        }
        var conflicts = new JsonArray();
        for (var item : d.getAsJsonArray("conflicts")) {
            var conflict = item.getAsJsonObject().deepCopy(); var ids = CapturedTeamDecision.references(conflict, "claimIds", 2, 2, true);
            requireReferences(ids, references, null);
            conflict.addProperty("conflictId", "conflict-" + (conflicts.size() + 1)); conflict.addProperty("state", "UNRESOLVED");
            conflict.addProperty("comparability", ids.stream().map(id -> references.get(id).get("caseId").getAsString()).distinct().count() == 2 ? "DIFFERENT_CASE_IDS" : "SAME_CASE_SCOPE_UNESTABLISHED");
            conflict.addProperty("authority", "possible-conflict-model-advice-not-proven-contradiction"); conflicts.add(conflict);
        }
        var bindings = new TreeMap<String, String>(); eligible.forEach((id, value) -> bindings.put(id, hash(value)));
        return json(Map.of("formatVersion", 1, "workers", workers, "originalClaims", claims, "reviewDecision", d, "selectedFollowUps", selected,
                "possibleConflicts", conflicts, "referenceIdentities", bindings, "selectionPolicy", SELECTION, "qualityReview", "NOT_PERFORMED",
                "authority", "review-and-selection-data-not-permission-or-compatibility-verdict"));
    }
    private static void requireReferences(Set<String> ids, Map<String, JsonObject> eligible, String caseId) {
        for (String id : ids) CaseInputs.require(eligible.containsKey(id) && (caseId == null || eligible.get(id).get("caseId").getAsString().equals(caseId)), "Unseen or foreign review reference");
    }
    private static void validatePages(List<JsonObject> pages, Set<String> cases) {
        CaseInputs.require(pages.size() <= 500, "Too many retained review pages"); var ids = new HashSet<String>(); var artifacts = new HashMap<String, String>();
        for (var p : pages) {
            CaseInputs.require(p.keySet().equals(Set.of("pageId", "caseId", "inputId", "artifactSha256", "pageSha256", "offset", "nextOffset", "totalCharacters", "content", "contentTrust")), "Invalid captured page shape");
            for (String field : List.of("pageId", "caseId", "inputId", "artifactSha256", "pageSha256", "content", "contentTrust"))
                CaseInputs.require(p.get(field).isJsonPrimitive() && p.get(field).getAsJsonPrimitive().isString(), "Invalid captured page text");
            for (String field : List.of("offset", "nextOffset", "totalCharacters"))
                CaseInputs.require(p.get(field).isJsonPrimitive() && p.get(field).getAsJsonPrimitive().isNumber(), "Invalid captured page integer");
            String id = p.get("caseId").getAsString(), alias = p.get("inputId").getAsString(), content = p.get("content").getAsString();
            CaseInputs.require(cases.contains(id) && BundleInvestigationDecision.aliases(id).contains(alias) && p.get("pageId").getAsString().matches("read-[1-9][0-9]{0,2}")
                    && ids.add(p.get("pageId").getAsString()) && p.get("artifactSha256").getAsString().matches("[0-9a-f]{64}") && p.get("contentTrust").getAsString().equals("untrusted-data"), "Invalid captured page scope");
            int offset = p.get("offset").getAsBigDecimal().intValueExact(), next = p.get("nextOffset").getAsBigDecimal().intValueExact(), total = p.get("totalCharacters").getAsBigDecimal().intValueExact();
            CaseInputs.require(offset >= 0 && next >= offset && next <= total && total <= 1048576 && content.length() == next - offset && content.length() <= 4000
                    && p.get("pageSha256").getAsString().equals(CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8))), "Changed captured page content");
            String identity = p.get("artifactSha256").getAsString() + ":" + total, previous = artifacts.putIfAbsent(id + ":" + alias, identity);
            CaseInputs.require(previous == null || previous.equals(identity), "Inconsistent captured page artifact");
        }
    }
    private static JsonArray metadata(List<JsonObject> pages) { var result = new JsonArray(); pages.forEach(p -> { var copy = p.deepCopy(); copy.remove("content"); result.add(copy); }); return result; }
    private static String hash(JsonObject object) { return CaseInputs.hash(encoded(object).getBytes(StandardCharsets.UTF_8)); }
    private static String encoded(JsonObject object) { return JSON.toJson(canonical(object)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var result = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(k -> result.add(k, canonical(value.getAsJsonObject().get(k)))); return result; }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(canonical(v))); return result; } return value;
    }
}
