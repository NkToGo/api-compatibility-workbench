package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure review boundary over host-verified invoice histories; no filesystem, model call or dispatch. */
final class InvoiceTeamReview {
    static final String SELECTION = "invoice-all-valid-questions-sorted-by-worker-once-depth-one-v1";
    static final String CONTEXT = "invoice-all-original-claims-outcomes-all-read-metadata-latest-six-pages-utf8-trim-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    enum WorkerStatus { COMPLETED, FAILED, CANCELLED, UNKNOWN_REMOTE_OUTCOME }
    record WorkerResult(String workerId, ContentDigest inputIdentity, WorkerStatus status, String stopReason,
                        JsonObject finishDecision, JsonObject metadata, List<JsonObject> pages) {
        WorkerResult {
            InvoiceWorkerDecision.protocol(workerId); Objects.requireNonNull(inputIdentity); Objects.requireNonNull(status);
            CaseInputs.require(stopReason != null && stopReason.matches("[A-Z][A-Z0-9_]{0,99}"), "Invalid invoice worker stop reason");
            pages = pages.stream().map(JsonObject::deepCopy).toList(); validatePages(pages);
            metadata = metadata == null ? null : metadata.deepCopy();
            if (metadata != null) {
                CaseInputs.require(metadata.get("caseId").getAsString().equals(InvoiceRenewalFixtures.CASE_ID)
                        && metadata.get("bundleIdentity").getAsString().equals(inputIdentity.sha256())
                        && metadata.get("sourceExecutionCoverageSatisfied").isJsonPrimitive()
                        && metadata.get("sourceExecutionCoverageSatisfied").getAsJsonPrimitive().isBoolean(), "Foreign invoice worker metadata");
            } else CaseInputs.require(pages.isEmpty(), "Worker pages need bound metadata");
            if (status == WorkerStatus.COMPLETED) {
                CaseInputs.require(stopReason.equals("NONE") && finishDecision != null && metadata != null, "Incomplete invoice worker dossier");
                finishDecision = InvoiceInvestigationDecision.parse(JSON.toJson(Map.of("decision", finishDecision)));
                CaseInputs.require(finishDecision.get("action").getAsString().equals("finish")
                        && InvoiceWorkerDecision.supported(workerId, finishDecision, pages, metadata), "Unsupported invoice worker dossier");
            } else CaseInputs.require(finishDecision == null && !stopReason.equals("NONE"), "Stopped worker cannot supply a completed dossier");
        }
        @Override public JsonObject finishDecision() { return finishDecision == null ? null : finishDecision.deepCopy(); }
        @Override public JsonObject metadata() { return metadata == null ? null : metadata.deepCopy(); }
        @Override public List<JsonObject> pages() { return pages.stream().map(JsonObject::deepCopy).toList(); }
    }
    private final ContentDigest inputIdentity;
    private final JsonArray workers = new JsonArray(), claims = new JsonArray();
    private final Map<String, JsonObject> references = new TreeMap<>();
    InvoiceTeamReview(List<WorkerResult> originals) {
        var sorted = originals.stream().sorted(Comparator.comparing(WorkerResult::workerId)).toList();
        CaseInputs.require(sorted.size() == 2 && sorted.stream().map(WorkerResult::workerId).toList().equals(InvoiceWorkerDecision.WORKERS), "Both invoice worker outcomes required");
        inputIdentity = sorted.getFirst().inputIdentity();
        CaseInputs.require(sorted.stream().allMatch(w -> w.inputIdentity().equals(inputIdentity)), "Invoice workers need the same bundle");
        for (var worker : sorted) {
            String id = "worker-" + (workers.size() + 1);
            var outcome = json(Map.of("referenceId", id, "workerId", worker.workerId(), "workerRole", InvoiceWorkerDecision.role(worker.workerId()),
                    "caseId", InvoiceRenewalFixtures.CASE_ID, "inputIdentity", inputIdentity, "status", worker.status(), "stopReason", worker.stopReason(),
                    "response", worker.finishDecision == null ? "NOT_PROVIDED" : worker.finishDecision.get("response").getAsString(),
                    "reportIdentity", hash(json(Map.of("workerId", worker.workerId(), "inputIdentity", inputIdentity, "status", worker.status(), "stopReason", worker.stopReason(),
                            "decision", worker.finishDecision == null ? JsonNull.INSTANCE : worker.finishDecision,
                            "metadata", worker.metadata == null ? JsonNull.INSTANCE : worker.metadata, "readMetadata", metadata(worker.pages)))),
                    "authority", "host-recorded-worker-disposition-not-answer-quality"));
            workers.add(outcome); references.put(id, outcome);
            if (worker.finishDecision == null) continue;
            addClaim(outcome, "summary", "SUMMARY", worker.finishDecision.get("summary").getAsString(), worker.finishDecision.getAsJsonArray("summaryCitations"));
            int index = 0;
            for (var item : worker.finishDecision.getAsJsonArray("findings")) {
                var finding = item.getAsJsonObject(); addClaim(outcome, "findings/" + index++, finding.get("kind").getAsString(), finding.get("statement").getAsString(), finding.getAsJsonArray("citations"));
            }
        }
    }
    private void addClaim(JsonObject worker, String position, String kind, String statement, JsonArray citations) {
        var claim = worker.deepCopy(); claim.addProperty("referenceId", "claim-" + (claims.size() + 1));
        claim.addProperty("workerReferenceId", worker.get("referenceId").getAsString()); claim.addProperty("position", position);
        claim.addProperty("kind", kind); claim.addProperty("statement", statement); claim.add("workerPageIds", citations.deepCopy());
        claim.addProperty("authority", "original-model-advice-not-execution-or-semantic-acceptance"); claims.add(claim); references.put(claim.get("referenceId").getAsString(), claim);
    }
    String context(List<JsonObject> pages, int remaining, int byteLimit) {
        CaseInputs.require(remaining >= 1 && remaining <= 500 && byteLimit >= 1 && byteLimit <= 64000, "Invalid invoice review context limits"); validatePages(pages);
        var recent = new JsonArray(); pages.subList(Math.max(0, pages.size() - 6), pages.size()).forEach(p -> recent.add(p.deepCopy()));
        var context = json(Map.of("caseId", InvoiceRenewalFixtures.CASE_ID, "inputIdentity", inputIdentity, "workers", workers, "originalClaims", claims,
                "readMetadata", metadata(pages), "recentPages", recent, "decisionsRemaining", remaining, "qualityReview", "NOT_PERFORMED", "contentTrust", "untrusted-data",
                "availableTools", List.of("catalog", "read_text", "finish")));
        var disclosed = context.getAsJsonArray("recentPages"); String input = encoded(context);
        while (input.getBytes(StandardCharsets.UTF_8).length > byteLimit && !disclosed.isEmpty()) { disclosed.remove(0); input = encoded(context); }
        if (input.getBytes(StandardCharsets.UTF_8).length > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED"); return input;
    }
    JsonObject select(JsonObject decision, List<JsonObject> pages) {
        var d = InvoiceTeamDecision.parse(JSON.toJson(Map.of("decision", decision))); CaseInputs.require(d.get("action").getAsString().equals("finish"), "Only a finished invoice review selects questions");
        validatePages(pages); var eligible = new TreeMap<>(references);
        pages.stream().filter(p -> p.get("kind").getAsString().equals("read_text")).forEach(p -> eligible.put(p.get("pageId").getAsString(), p));
        requireReferences(CapturedTeamDecision.references(d, "citations", 1, 6, false), eligible);
        var selected = new JsonArray();
        var questions = d.getAsJsonArray("followUps").asList().stream().map(JsonElement::getAsJsonObject).sorted(Comparator.comparing(q -> q.get("workerId").getAsString())).toList();
        for (var question : questions) {
            String worker = question.get("workerId").getAsString(); var ids = CapturedTeamDecision.references(question, "citations", 1, 6, false); requireReferences(ids, eligible);
            CaseInputs.require(ids.stream().anyMatch(id -> references.containsKey(id) && references.get(id).get("workerId").getAsString().equals(worker)), "Invoice question needs an original target-role anchor");
            var proposal = question.deepCopy(); proposal.addProperty("proposalId", "follow-up-" + (selected.size() + 1)); proposal.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            proposal.add("inputIdentity", JSON.toJsonTree(inputIdentity)); proposal.addProperty("delegationDepth", 1);
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
        var bindings = new TreeMap<String, String>(); eligible.forEach((id, value) -> bindings.put(id, hash(value)));
        return json(Map.of("formatVersion", 1, "inputIdentity", inputIdentity, "workers", workers, "originalClaims", claims, "reviewDecision", d,
                "selectedFollowUps", selected, "possibleConflicts", conflicts, "referenceIdentities", bindings, "selectionPolicy", SELECTION, "qualityReview", "NOT_PERFORMED"));
    }
    private static void requireReferences(Set<String> ids, Map<String, JsonObject> eligible) { for (String id : ids) CaseInputs.require(eligible.containsKey(id), "Unseen invoice review reference"); }
    static void validatePages(List<JsonObject> pages) {
        CaseInputs.require(pages.size() <= 500, "Too many invoice review pages"); var ids = new HashSet<String>(); var artifacts = new HashMap<String, String>();
        var texts = new HashMap<String, List<JsonObject>>(); Integer catalogSize = null;
        for (var p : pages) {
            String kind = text(p, "kind"), pageId = text(p, "pageId");
            CaseInputs.require(pageId.matches("read-[1-9][0-9]{0,2}") && ids.add(pageId) && text(p, "contentTrust").equals("untrusted-data"), "Invalid invoice page identity");
            int offset = integer(p, "offset"), next = integer(p, "nextOffset");
            if (kind.equals("read_text")) {
                CaseInputs.require(p.keySet().equals(Set.of("kind", "pageId", "contentTrust", "alias", "sha256", "offset", "nextOffset", "totalCharacters", "content")), "Invalid invoice text page shape");
                int total = integer(p, "totalCharacters"); String content = text(p, "content");
                CaseInputs.require(offset >= 0 && next >= offset && next <= total && total <= 1048576 && content.length() == next - offset && content.length() <= 4000, "Invalid invoice text bounds");
                artifact(artifacts, p); texts.computeIfAbsent(text(p, "alias"), unused -> new ArrayList<>()).add(p);
            } else {
                CaseInputs.require(kind.equals("catalog") && p.keySet().equals(Set.of("kind", "pageId", "contentTrust", "offset", "nextOffset", "totalArtifacts", "artifacts")), "Invalid invoice catalog shape");
                var rows = p.getAsJsonArray("artifacts"); int total = integer(p, "totalArtifacts");
                CaseInputs.require(offset >= 0 && next >= offset && next <= total && total <= 768 && rows.size() <= 40 && rows.size() == next - offset, "Invalid invoice catalog bounds");
                CaseInputs.require(catalogSize == null || catalogSize == total, "Inconsistent invoice catalog size"); catalogSize = total;
                String previous = "";
                for (var item : rows) {
                    var row = item.getAsJsonObject(); CaseInputs.require(row.keySet().equals(Set.of("alias", "sha256", "totalCharacters")) && text(row, "alias").compareTo(previous) > 0, "Invalid invoice catalog row");
                    artifact(artifacts, row); previous = text(row, "alias");
                }
            }
        }
        for (var values : texts.values()) {
            values.sort(Comparator.comparingInt(p -> integer(p, "offset"))); int end = 0; var content = new StringBuilder();
            for (var page : values) {
                int offset = integer(page, "offset"), next = integer(page, "nextOffset");
                // Even incomplete overlapping pages must agree with each other.
                for (var other : values) {
                    int from = Math.max(offset, integer(other, "offset")), to = Math.min(next, integer(other, "nextOffset"));
                    if (from < to) CaseInputs.require(text(page, "content").substring(from - offset, to - offset).equals(text(other, "content").substring(from - integer(other, "offset"), to - integer(other, "offset"))), "Conflicting invoice text pages");
                }
                if (offset > end) continue;
                if (next > end) { content.append(text(page, "content").substring(end - offset)); end = next; }
            }
            if (end == integer(values.getFirst(), "totalCharacters")) CaseInputs.require(CaseInputs.hash(content.toString().getBytes(StandardCharsets.UTF_8)).equals(text(values.getFirst(), "sha256")), "Changed complete invoice artifact");
        }
    }
    private static void artifact(Map<String, String> artifacts, JsonObject row) {
        String alias = text(row, "alias"), hash = text(row, "sha256"); int total = integer(row, "totalCharacters");
        CaseInputs.require(alias.length() <= 300 && alias.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") && Arrays.stream(alias.split("/", -1)).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))
                && hash.matches("[0-9a-f]{64}") && total >= 0 && total <= 1048576, "Invalid invoice artifact metadata");
        String previous = artifacts.putIfAbsent(alias, hash + ":" + total); CaseInputs.require(previous == null || previous.equals(hash + ":" + total), "Inconsistent invoice artifact identity");
    }
    private static int integer(JsonObject value, String field) {
        var item = value.get(field); CaseInputs.require(item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber(), "Expected invoice page integer"); return item.getAsBigDecimal().intValueExact();
    }
    private static String text(JsonObject value, String field) {
        var item = value.get(field); CaseInputs.require(item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString(), "Expected invoice page text"); return item.getAsString();
    }
    private static JsonArray metadata(List<JsonObject> pages) {
        var result = new JsonArray(); for (var p : pages) { var copy = p.deepCopy(); copy.remove("content"); copy.remove("artifacts"); copy.addProperty("pageIdentity", hash(p)); result.add(copy); } return result;
    }
    private static String hash(JsonObject value) { return CaseInputs.hash(encoded(value).getBytes(StandardCharsets.UTF_8)); }
    private static String encoded(JsonObject value) { return JSON.toJson(canonical(value)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(k -> sorted.add(k, canonical(value.getAsJsonObject().get(k)))); return sorted; }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(canonical(v))); return result; } return value;
    }
}
