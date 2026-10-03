package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable reconciliation inputs and deterministic guards around advisory model assessments. */
final class ReconciliationEvidence {
    private ReconciliationEvidence() {}
    static JsonObject capture(Path state, String roundId, Path review, Map<String, String> artifacts) throws IOException {
        var dispatch = new FollowUpDispatch(state); var completed = dispatch.readCompleted(roundId);
        Path round = state.resolve("follow-ups/" + roundId); var spec = read(round, "follow-up.json");
        Path source = round.resolve("inputs/review"); var originalSpec = read(source, "review.json");
        var original = read(source, "result.json"); var handoff = read(source, "inputs/handoff.json");
        capture(review, artifacts, "source-review.json", ConsumerTasks.bytes(source, "review.json"));
        capture(review, artifacts, "source-review-result.json", ConsumerTasks.bytes(source, "result.json"));
        capture(review, artifacts, "source-handoff.json", ConsumerTasks.bytes(source, "inputs/handoff.json"));
        capture(review, artifacts, "follow-up.json", ConsumerTasks.bytes(round, "follow-up.json"));
        capture(review, artifacts, "follow-up-result.json", ConsumerTasks.bytes(round, "result.json"));
        var refs = new LinkedHashMap<String, JsonObject>(); var tasks = new LinkedHashMap<String, JsonObject>();
        for (var task : handoff.getAsJsonObject("tasks").entrySet()) {
            var metadata = task.getValue().getAsJsonObject().deepCopy(); metadata.addProperty("stage", "ORIGINAL"); tasks.put(task.getKey(), metadata);
        }
        for (var entry : handoff.getAsJsonObject("references").entrySet()) {
            String id = entry.getKey(), path = "inputs/references/" + id + ".json";
            byte[] value = ConsumerTasks.bytes(source, path);
            CaseInputs.require(CaseInputs.hash(value).equals(originalSpec.getAsJsonObject("artifacts").get(path).getAsString()), "Changed original reference");
            capture(review, artifacts, "references/" + id + ".json", value);
            var metadata = entry.getValue().getAsJsonObject().deepCopy(); metadata.addProperty("stage", "ORIGINAL"); refs.put(id, metadata);
        }
        var proposals = new LinkedHashMap<String, JsonObject>();
        for (var entry : spec.getAsJsonObject("selections").entrySet()) {
            String proposal = entry.getKey(); var selection = entry.getValue().getAsJsonObject();
            String old = selection.get("sourceTaskId").getAsString(), child = selection.get("taskId").getAsString();
            Path task = round.resolve("child-state/tasks/" + child);
            byte[] originalBytes = ConsumerTasks.bytes(source, "inputs/" + old + "/task.json"), newBytes = ConsumerTasks.bytes(task, "task.json");
            CaseInputs.require(CaseInputs.hash(originalBytes).equals(selection.get("sourceTaskSha256").getAsString()), "Changed original task identity");
            var oldTask = CaseInputs.object(originalBytes); var newTask = CaseInputs.object(newBytes);
            var oldCase = oldTask.getAsJsonObject("case"); var newCase = newTask.getAsJsonObject("case").deepCopy();
            CaseInputs.require(newCase.getAsJsonObject("value").remove("followUp").equals(selection.get("focus"))
                    && newCase.equals(oldCase) && oldTask.get("artifacts").equals(newTask.get("artifacts")), "Unmatched follow-up inputs");
            capture(review, artifacts, "tasks/" + old + "/task.json", originalBytes);
            capture(review, artifacts, "tasks/" + child + "/task.json", newBytes);
            var outcome = completed.getAsJsonObject("responses").getAsJsonObject(proposal);
            CaseInputs.require(CaseInputs.hash(newBytes).equals(outcome.get("taskSha256").getAsString()), "Changed fresh task identity");
            var metadata = json(Map.of("stage", "FOLLOW_UP", "sourceTaskId", old, "proposalId", proposal, "caseId", oldCase.getAsJsonObject("value").get("caseId"),
                    "taskSha256", outcome.get("taskSha256"), "status", outcome.get("status"), "inputComparability", "MATCHED_REGISTERED_CASE_INPUTS"));
            tasks.put(child, metadata);
            String question = reference(review, artifacts, refs, child, "QUESTION", selection.get("focus"), "advisory-question-not-evidence");
            reference(review, artifacts, refs, child, "TASK_OUTCOME", outcome, "advisory-follow-up-outcome");
            if (outcome.has("taskResultSha256")) {
                byte[] result = ConsumerTasks.bytes(task, "result.json");
                CaseInputs.require(CaseInputs.hash(result).equals(outcome.get("taskResultSha256").getAsString()), "Changed follow-up task result");
                capture(review, artifacts, "tasks/" + child + "/result.json", result);
                if (outcome.has("agentId")) {
                    byte[] bytes = ConsumerTasks.bytes(task, "state/agents/" + outcome.get("agentId").getAsString() + "/result.json");
                    CaseInputs.require(CaseInputs.hash(bytes).equals(outcome.get("agentResultSha256").getAsString()), "Changed follow-up claims");
                    capture(review, artifacts, "tasks/" + child + "/agent-result.json", bytes); var agent = CaseInputs.object(bytes);
                    if (agent.has("findings")) {
                        var findings = agent.getAsJsonObject("findings");
                        reference(review, artifacts, refs, child, "SUMMARY", findings.get("summary"), "model-advice");
                        for (var claim : findings.getAsJsonArray("findings")) reference(review, artifacts, refs, child, "CLAIM", claim, "model-advice");
                    }
                    for (var event : agent.getAsJsonArray("events")) reference(review, artifacts, refs, child, "TOOL_EVENT", event, "captured-observation-with-original-authority");
                }
            }
            proposals.put(proposal, json(Map.of("sourceTaskId", old, "taskId", child, "questionReferenceId", question,
                    "childStatus", outcome.get("status"), "inputComparability", "MATCHED_REGISTERED_CASE_INPUTS")));
        }
        var conflicts = new LinkedHashMap<String, JsonObject>();
        for (var item : original.getAsJsonArray("events")) {
            var result = item.getAsJsonObject().getAsJsonObject("result");
            if (!result.get("ok").getAsBoolean() || !result.has("value") || !result.getAsJsonObject("value").has("conflictId")) continue;
            var conflict = result.getAsJsonObject("value").deepCopy();
            String ref = reference(review, artifacts, refs, "", "CONFLICT", conflict, "possible-conflict-model-inference");
            conflict.addProperty("recordReferenceId", ref); conflicts.put(conflict.get("conflictId").getAsString(), conflict);
        }
        CaseInputs.require(completed.equals(dispatch.readCompleted(roundId)), "Follow-up changed during capture");
        var result = json(Map.of("followUpId", roundId, "followUpResultSha256", artifacts.get("inputs/follow-up-result.json"),
                "sourceReviewStatus", original.get("status"), "proposals", proposals, "conflicts", conflicts, "tasks", tasks, "references", refs));
        capture(review, artifacts, "handoff.json", (result + "\n").getBytes(StandardCharsets.UTF_8)); return result;
    }
    private static void capture(Path review, Map<String, String> artifacts, String name, byte[] bytes) throws IOException {
        CaseInputs.require(bytes.length <= 1048576 && artifacts.size() < 220, "Reconciliation capture limit exceeded");
        String path = "inputs/" + name; Path target = review.resolve(path); Files.createDirectories(target.getParent()); Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        artifacts.put(path, CaseInputs.hash(bytes));
        long total = 0; for (String artifact : artifacts.keySet()) total += Files.size(review.resolve(artifact));
        CaseInputs.require(total <= 16 * 1024 * 1024, "Reconciliation capture too large");
    }
    private static String reference(Path review, Map<String, String> artifacts, Map<String, JsonObject> refs, String task, String kind, JsonElement value, String authority) throws IOException {
        CaseInputs.require(refs.size() < 200, "Too many reconciliation references"); String id = "ref-" + (refs.size() + 1);
        capture(review, artifacts, "references/" + id + ".json", (json(Map.of("referenceId", id, "taskId", task, "kind", kind, "value", value, "authority", authority)) + "\n").getBytes(StandardCharsets.UTF_8));
        var metadata = json(Map.of("taskId", task, "kind", kind, "stage", kind.equals("CONFLICT") ? "ORIGINAL" : "FOLLOW_UP"));
        if (kind.equals("TOOL_EVENT")) { metadata.add("tool", value.getAsJsonObject().get("tool")); metadata.add("eventId", value.getAsJsonObject().get("eventId")); }
        if (kind.equals("CLAIM")) metadata.add("eventCitations", value.getAsJsonObject().get("citations"));
        refs.put(id, metadata); return id;
    }
    static JsonObject act(Path review, JsonObject handoff, JsonObject d, Set<String> seen, JsonArray history, int turn) throws IOException {
        var citations = CoordinationDecision.references(d, "citations", 1, 6); var refs = handoff.getAsJsonObject("references");
        if (!seen.containsAll(citations)) return error("UNOBSERVED_RECONCILIATION_SUPPORT");
        String action = d.get("action").getAsString(); JsonObject value;
        if (action.equals("assess_response")) {
            String id = d.get("proposalId").getAsString(); var proposals = handoff.getAsJsonObject("proposals");
            if (!proposals.has(id)) return error("UNKNOWN_PROPOSAL"); var proposal = proposals.getAsJsonObject(id); String task = proposal.get("taskId").getAsString();
            if (has(history, "proposalId", id)) return error("RESPONSE_ALREADY_ASSESSED");
            if (!citations.contains(proposal.get("questionReferenceId").getAsString())
                    || citations.stream().noneMatch(r -> is(refs, r, task, "CLAIM", "SUMMARY", "TASK_OUTCOME"))) return error("UNSUPPORTED_RESPONSE_ASSESSMENT");
            String coverage = d.get("coverage").getAsString();
            if (!coverage.equals("INSUFFICIENT_EVIDENCE") && (!proposal.get("childStatus").getAsString().equals("INVESTIGATED")
                    || !observedClaim(review, refs, citations, task))) return error("UNSUPPORTED_ANSWER_COVERAGE");
            value = json(Map.of("proposalId", id, "coverage", coverage, "statement", d.get("statement"), "citations", citations,
                    "state", "ADVISORY_REQUIRES_SEMANTIC_REVIEW", "childStatus", proposal.get("childStatus")));
        } else if (action.equals("relate_claims")) {
            String old = d.get("originalClaimId").getAsString(), fresh = d.get("followUpClaimId").getAsString();
            if (!citations.containsAll(List.of(old, fresh)) || !refs.has(old) || !refs.has(fresh)) return error("UNSUPPORTED_CLAIM_RELATIONSHIP");
            var a = refs.getAsJsonObject(old); var b = refs.getAsJsonObject(fresh);
            if (!a.get("kind").getAsString().equals("CLAIM") || !b.get("kind").getAsString().equals("CLAIM")
                    || !a.get("stage").getAsString().equals("ORIGINAL") || !b.get("stage").getAsString().equals("FOLLOW_UP")
                    || !handoff.getAsJsonObject("tasks").getAsJsonObject(b.get("taskId").getAsString()).get("sourceTaskId").equals(a.get("taskId"))) return error("FOREIGN_CLAIM_RELATIONSHIP");
            String pair = old + ":" + fresh;
            if (has(history, "claimPair", pair)) return error("CLAIM_PAIR_ALREADY_REVIEWED");
            if (count(history, "claimPair") >= 4) return error("CLAIM_RELATIONSHIP_BUDGET_EXHAUSTED");
            if (!d.get("relationship").getAsString().equals("INDETERMINATE") && (!observedTool(review, refs, citations, a.get("taskId").getAsString(), a)
                    || !observedTool(review, refs, citations, b.get("taskId").getAsString(), b))) return error("UNSUPPORTED_CLAIM_RELATIONSHIP");
            value = json(Map.of("claimPair", pair, "originalClaimId", old, "followUpClaimId", fresh, "relationship", d.get("relationship"),
                    "statement", d.get("statement"), "citations", citations, "inputComparability", "MATCHED_REGISTERED_CASE_INPUTS", "state", "ADVISORY_REQUIRES_SEMANTIC_REVIEW"));
        } else {
            String id = d.get("conflictId").getAsString(); var conflicts = handoff.getAsJsonObject("conflicts");
            if (!conflicts.has(id)) return error("UNKNOWN_CONFLICT"); var conflict = conflicts.getAsJsonObject(id);
            if (has(history, "conflictId", id)) return error("CONFLICT_ALREADY_ASSESSED");
            var required = CoordinationDecision.references(conflict, "claimIds", 2, 2); required.add(conflict.get("recordReferenceId").getAsString());
            if (!citations.containsAll(required)) return error("UNSUPPORTED_CONFLICT_ASSESSMENT");
            if (d.get("assessment").getAsString().equals("SCOPE_DIFFERENCE") && !conflict.get("scope").getAsString().equals("DIFFERENT_CASE_IDS")) return error("UNPROVEN_SCOPE_DIFFERENCE");
            value = json(Map.of("conflictId", id, "assessment", d.get("assessment"), "statement", d.get("statement"),
                    "citations", citations, "state", "UNRESOLVED", "scope", conflict.get("scope")));
        }
        value.addProperty("assessmentId", "assessment-" + turn); value.addProperty("authority", "reconciliation-model-advice");
        return json(Map.of("ok", true, "value", value));
    }
    private static boolean is(JsonObject refs, String ref, String task, String... kinds) {
        var r = refs.getAsJsonObject(ref); return r != null && r.get("taskId").getAsString().equals(task) && Set.of(kinds).contains(r.get("kind").getAsString());
    }
    private static boolean observedClaim(Path review, JsonObject refs, Set<String> citations, String task) throws IOException {
        for (String ref : citations) if (is(refs, ref, task, "CLAIM") && observedTool(review, refs, citations, task, refs.getAsJsonObject(ref))) return true;
        return false;
    }
    private static boolean observedTool(Path review, JsonObject refs, Set<String> citations, String task, JsonObject claim) throws IOException {
        for (String ref : citations) if (is(refs, ref, task, "TOOL_EVENT")) {
            if (!claim.getAsJsonArray("eventCitations").contains(refs.getAsJsonObject(ref).get("eventId"))) continue;
            var event = read(review, "inputs/references/" + ref + ".json").getAsJsonObject("value");
            if (event.getAsJsonObject("result").get("ok").getAsBoolean()) return true;
        } return false;
    }
    private static boolean has(JsonArray history, String key, String value) {
        for (var item : history) { var result = item.getAsJsonObject().getAsJsonObject("result");
            if (result.get("ok").getAsBoolean() && result.has("value") && result.getAsJsonObject("value").has(key)
                    && result.getAsJsonObject("value").get(key).getAsString().equals(value)) return true;
        } return false;
    }
    private static int count(JsonArray history, String key) {
        int n = 0; for (var item : history) { var result = item.getAsJsonObject().getAsJsonObject("result");
            if (result.get("ok").getAsBoolean() && result.has("value") && result.getAsJsonObject("value").has(key)) n++;
        } return n;
    }
    static JsonObject ledger(JsonObject handoff, JsonArray history) {
        var responses = new JsonObject(); var conflicts = handoff.getAsJsonObject("conflicts").deepCopy(); var relationships = new JsonArray(); var claims = new JsonObject();
        for (var entry : handoff.getAsJsonObject("proposals").entrySet()) {
            var row = entry.getValue().getAsJsonObject().deepCopy(); row.addProperty("coverage", "NOT_REVIEWED");
            row.addProperty("authority", "reconciliation-model-advice"); responses.add(entry.getKey(), row);
        }
        for (var entry : handoff.getAsJsonObject("references").entrySet()) if (entry.getValue().getAsJsonObject().get("kind").getAsString().equals("CLAIM")) {
            var claim = entry.getValue().getAsJsonObject().deepCopy();
            var task = handoff.getAsJsonObject("tasks").getAsJsonObject(claim.get("taskId").getAsString());
            claim.add("taskSha256", task.get("taskSha256")); claim.add("caseId", task.get("caseId")); claim.addProperty("authority", "model-advice");
            claims.add(entry.getKey(), claim);
        }
        for (var item : history) {
            var result = item.getAsJsonObject().getAsJsonObject("result"); if (!result.get("ok").getAsBoolean() || !result.has("value")) continue;
            var value = result.getAsJsonObject("value");
            if (value.has("proposalId")) responses.getAsJsonObject(value.get("proposalId").getAsString()).add("assessment", value.deepCopy());
            if (value.has("conflictId")) conflicts.getAsJsonObject(value.get("conflictId").getAsString()).add("assessment", value.deepCopy());
            if (value.has("claimPair")) relationships.add(value.deepCopy());
        }
        for (var row : responses.entrySet()) { var r = row.getValue().getAsJsonObject(); if (r.has("assessment")) r.add("coverage", r.getAsJsonObject("assessment").get("coverage")); }
        return json(Map.of("followUpId", handoff.get("followUpId"), "followUpResultSha256", handoff.get("followUpResultSha256"),
                "sourceReviewStatus", handoff.get("sourceReviewStatus"), "responses", responses, "conflicts", conflicts, "claimReferences", claims,
                "relationships", relationships, "authority", "reconciliation-model-advice", "semanticAcceptance", "NOT_ESTABLISHED"));
    }
    static String status(JsonObject handoff, JsonArray history) {
        if (count(history, "proposalId") != handoff.getAsJsonObject("proposals").size() || count(history, "conflictId") != handoff.getAsJsonObject("conflicts").size()) return "RECONCILIATION_INCOMPLETE";
        return handoff.getAsJsonObject("conflicts").isEmpty() ? "RECONCILIATION_RECORDED" : "RECONCILIATION_WITH_UNRESOLVED_CONFLICTS";
    }
    private static JsonObject read(Path base, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(base, name)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject error(String code) { return json(Map.of("ok", false, "error", code)); }
}
