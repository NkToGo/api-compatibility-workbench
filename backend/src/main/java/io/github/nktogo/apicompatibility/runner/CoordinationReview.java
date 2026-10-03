package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Adaptive review of captured investigator evidence. Proposals never grant or dispatch work. */
public final class CoordinationReview {
    private final Path state, root;
    private final Clock clock;
    private final ModelsFactory factory;
    public CoordinationReview(Path state) throws IOException { this(state, Clock.systemUTC(), ConsumerInvestigator::modelBackend); }
    CoordinationReview(Path state, Clock clock, ModelsFactory factory) throws IOException {
        this.state = state.toAbsolutePath().normalize(); root = this.state.resolve("coordination-reviews");
        Files.createDirectories(root); ConsumerTasks.directory(root); this.clock = clock; this.factory = factory;
    }
    public String register(String batchId, String model) throws IOException {
        new ModelCallBroker.Request(model, "validate", 2048, "coordination-v2");
        var batches = new ParallelInvestigations(state); var completed = batches.readCompleted(batchId);
        Path batch = state.resolve("parallel-investigations/" + batchId);
        Path review = Files.createDirectory(root.resolve("review-" + UUID.randomUUID())); Files.createDirectory(review.resolve("inputs"));
        var artifacts = new TreeMap<String, String>(); var references = new LinkedHashMap<String, Object>(); var tasks = new LinkedHashMap<String, Object>();
        capture(review, artifacts, "batch.json", ConsumerTasks.bytes(batch, "batch.json"));
        capture(review, artifacts, "batch-result.json", ConsumerTasks.bytes(batch, "result.json"));
        for (var item : completed.getAsJsonObject("children").entrySet()) {
            String id = item.getKey(); Path task = state.resolve("tasks/" + id);
            byte[] taskBytes = ConsumerTasks.bytes(task, "task.json"); var taskSpec = CaseInputs.object(taskBytes);
            CaseInputs.require(CaseInputs.hash(taskBytes).equals(item.getValue().getAsJsonObject().get("taskSha256").getAsString()), "Changed source task");
            capture(review, artifacts, id + "/task.json", taskBytes);
            String caseId = taskSpec.getAsJsonObject("case").getAsJsonObject("value").get("caseId").getAsString();
            var ids = new ArrayList<String>();
            ids.add(reference(review, artifacts, references, id, "TASK_OUTCOME", item.getValue()));
            var envelope = item.getValue().getAsJsonObject();
            if (envelope.has("taskResultSha256")) {
                byte[] resultBytes = ConsumerTasks.bytes(task, "result.json"); var result = CaseInputs.object(resultBytes);
                CaseInputs.require(CaseInputs.hash(resultBytes).equals(envelope.get("taskResultSha256").getAsString()), "Changed source result");
                capture(review, artifacts, id + "/task-result.json", resultBytes);
                if (result.has("agentId")) {
                    byte[] agentBytes = ConsumerTasks.bytes(task, "state/agents/" + result.get("agentId").getAsString() + "/result.json");
                    CaseInputs.require(CaseInputs.hash(agentBytes).equals(result.get("agentResultSha256").getAsString()), "Changed source findings");
                    capture(review, artifacts, id + "/agent-result.json", agentBytes); var agent = CaseInputs.object(agentBytes);
                    if (agent.has("findings")) {
                        var findings = agent.getAsJsonObject("findings");
                        ids.add(reference(review, artifacts, references, id, "SUMMARY", findings.get("summary")));
                        for (var claim : findings.getAsJsonArray("findings")) ids.add(reference(review, artifacts, references, id, "CLAIM", claim));
                    }
                    for (var event : agent.getAsJsonArray("events")) ids.add(reference(review, artifacts, references, id, "TOOL_EVENT", event));
                }
            }
            tasks.put(id, Map.of("caseId", caseId, "status", envelope.get("status").getAsString(), "executionMode",
                    taskSpec.get("executionMode").getAsString(), "taskSha256", CaseInputs.hash(taskBytes), "references", ids));
        }
        CaseInputs.require(completed.equals(batches.readCompleted(batchId)), "Batch changed during capture");
        var handoff = json(Map.of("batchId", batchId, "batchResultSha256", artifacts.get("inputs/batch-result.json"), "tasks", tasks, "references", references));
        capture(review, artifacts, "handoff.json", (handoff + "\n").getBytes(StandardCharsets.UTF_8));
        Instant issued = clock.instant(); String id = review.getFileName().toString();
        var spec = json(Map.of("formatVersion", 1, "reviewId", id, "model", model, "protocol", "coordination-v2", "maxDecisions", 12,
                "issuedAt", issued.toString(), "expiresAt", issued.plus(Duration.ofMinutes(20)).toString(), "artifacts", artifacts));
        DurableExecution.publish(review.resolve("review.json"), spec);
        DurableExecution.publish(review.resolve("grant.json"), grant(review, spec)); return id;
    }
    private static void capture(Path review, Map<String, String> artifacts, String relative, byte[] bytes) throws IOException {
        CaseInputs.require(bytes.length <= 1048576, "Oversized captured review input"); Path path = review.resolve("inputs/" + relative);
        Files.createDirectories(path.getParent()); Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        artifacts.put("inputs/" + relative, CaseInputs.hash(bytes));
    }
    private static String reference(Path review, Map<String, String> artifacts, Map<String, Object> references,
                                    String task, String kind, JsonElement value) throws IOException {
        CaseInputs.require(references.size() < 100, "Too many evidence references"); String id = "ref-" + (references.size() + 1);
        var record = json(Map.of("referenceId", id, "taskId", task, "kind", kind, "value", value,
                "authority", kind.equals("CLAIM") || kind.equals("SUMMARY") ? "model-advice" : "captured-observation-with-original-authority"));
        capture(review, artifacts, "references/" + id + ".json", (record + "\n").getBytes(StandardCharsets.UTF_8));
        var metadata = json(Map.of("taskId", task, "kind", kind));
        if (kind.equals("TOOL_EVENT")) { metadata.add("tool", value.getAsJsonObject().get("tool")); metadata.add("eventId", value.getAsJsonObject().get("eventId")); }
        if (kind.equals("CLAIM")) metadata.add("eventCitations", value.getAsJsonObject().get("citations"));
        references.put(id, metadata); return id;
    }

    public JsonObject run(String id) throws IOException {
        Path review = review(id);
        try (var lease = LocalFileLease.tryAcquire(review.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("reviewId", id, "status", "ACTIVE"));
            var spec = spec(review);
            if (Files.exists(review.resolve("result.json"), LinkOption.NOFOLLOW_LINKS)) return retained(review, spec);
            if (Files.exists(review.resolve("completion.json"), LinkOption.NOFOLLOW_LINKS)) {
                verifyInputs(review, spec);
                var result = materialize(review, spec, read(review, "completion.json"));
                DurableExecution.publish(review.resolve("result.json"), result); return result;
            }
            var history = new JsonArray(); var models = factory.models(review); var handoff = read(review, "inputs/handoff.json");
            try {
                for (int turn = 1; turn <= 12; turn++) {
                    admit(review, spec);
                    String mappingPath = "call-" + turn + ".json", reservationPath = "reservation-" + turn + ".json"; String call;
                    if (Files.exists(review.resolve(mappingPath), LinkOption.NOFOLLOW_LINKS)) call = read(review, mappingPath).get("callId").getAsString();
                    else {
                        if (Files.exists(review.resolve(reservationPath), LinkOption.NOFOLLOW_LINKS)) return finish(review, spec, "CALL_REGISTRATION_UNCERTAIN", null);
                        var payload = json(Map.of("handoff", handoff, "events", history, "decisionsRemaining", 13 - turn));
                        if (spec.get("protocol").getAsString().equals("coordination-v2")) payload.add("fullyReadReferences", CaseInputs.JSON.toJsonTree(new TreeSet<>(seen(history))));
                        String input = CaseInputs.JSON.toJson(payload);
                        if (input.getBytes(StandardCharsets.UTF_8).length > 64000) return finish(review, spec, "CONTEXT_BUDGET_EXHAUSTED", null);
                        var request = new ModelCallBroker.Request(spec.get("model").getAsString(), input, 2048, spec.get("protocol").getAsString());
                        DurableExecution.publish(review.resolve(reservationPath), Map.of("requestSha256", CaseInputs.hash(request.body())));
                        call = models.register(request);
                        DurableExecution.publish(review.resolve(mappingPath), Map.of("callId", call, "reservationSha256", hash(review, reservationPath)));
                    }
                    validateCall(review, spec, turn, call); admit(review, spec);
                    var response = models.execute(call); admit(review, spec); String status = response.get("status").getAsString();
                    if (status.equals("ACTIVE")) return json(Map.of("reviewId", id, "status", "ACTIVE"));
                    if (!status.equals("COMPLETED")) return finish(review, spec, "MODEL_" + status, null);
                    JsonObject decision;
                    try { decision = CoordinationDecision.parse(response.get("outputText").getAsString()); }
                    catch (RuntimeException e) { return finish(review, spec, "INVALID_MODEL_DECISION", null); }
                    if (decision.get("action").getAsString().equals("finish")) {
                        if (!seen(history).containsAll(CoordinationDecision.references(decision, "citations", 1, 6)))
                            return finish(review, spec, "UNSUPPORTED_REVIEW", null);
                        return finish(review, spec, count(history, "conflictId") > 0 ? "UNRESOLVED_CONFLICTS"
                                : count(history, "proposalId") > 0 ? "FOLLOW_UP_PROPOSED" : "REVIEW_RECORDED", decision);
                    }
                    String eventPath = "event-" + turn + ".json";
                    JsonObject event;
                    if (Files.exists(review.resolve(eventPath), LinkOption.NOFOLLOW_LINKS)) {
                        event = read(review, eventPath);
                        CaseInputs.require(event.equals(json(Map.of("eventId", "review-" + turn, "modelCallId", call, "decision", decision,
                                "result", act(review, handoff, decision, history, turn)))), "Changed review event");
                    } else {
                        var result = act(review, handoff, decision, history, turn); admit(review, spec);
                        event = json(Map.of("eventId", "review-" + turn, "modelCallId", call, "decision", decision, "result", result));
                        DurableExecution.publish(review.resolve(eventPath), event);
                    }
                    history.add(event);
                }
                return finish(review, spec, "DECISION_BUDGET_EXHAUSTED", null);
            } catch (Stop e) { return finish(review, spec, e.status, null); }
        }
    }
    private static JsonObject act(Path review, JsonObject handoff, JsonObject d, JsonArray history, int turn) throws IOException {
        var refs = handoff.getAsJsonObject("references"); String action = d.get("action").getAsString(); var seen = seen(history);
        if (action.equals("read_reference")) {
            String id = d.get("referenceId").getAsString(); if (!refs.has(id)) return error("UNKNOWN_REFERENCE");
            String text = new String(ConsumerTasks.bytes(review, "inputs/references/" + id + ".json"), StandardCharsets.UTF_8);
            int offset = d.get("offset").getAsInt(); if (offset >= text.length()) return error("INVALID_REFERENCE_RANGE");
            int end = Math.min(text.length(), offset + d.get("maxCharacters").getAsInt());
            return json(Map.of("ok", true, "value", Map.of("referenceId", id, "content", text.substring(offset, end), "offset", offset, "nextOffset", end, "complete", end == text.length())));
        }
        if (action.equals("propose_follow_up")) {
            String task = d.get("taskId").getAsString(); var citations = CoordinationDecision.references(d, "citations", 1, 6);
            if (!handoff.getAsJsonObject("tasks").has(task) || !seen.containsAll(citations)
                    || citations.stream().noneMatch(r -> refs.getAsJsonObject(r).get("taskId").getAsString().equals(task))) return error("UNSUPPORTED_FOLLOW_UP");
            if (count(history, "proposalId") >= 2) return error("FOLLOW_UP_BUDGET_EXHAUSTED");
            for (var item : history) {
                var result = item.getAsJsonObject().getAsJsonObject("result");
                if (result.has("value") && result.getAsJsonObject("value").has("proposalId")
                        && result.getAsJsonObject("value").get("taskId").getAsString().equals(task)) return error("FOLLOW_UP_ALREADY_PROPOSED");
            }
            return json(Map.of("ok", true, "value", Map.of("proposalId", "follow-up-" + turn, "taskId", task, "question", d.get("question").getAsString(),
                    "citations", citations, "state", "PROPOSED_NOT_DISPATCHED")));
        }
        var claims = CoordinationDecision.references(d, "claimIds", 2, 2);
        if (!seen.containsAll(claims) || claims.stream().anyMatch(r -> !refs.has(r) || !refs.getAsJsonObject(r).get("kind").getAsString().equals("CLAIM"))) return error("UNSUPPORTED_CONFLICT");
        if (count(history, "conflictId") >= 4) return error("CONFLICT_BUDGET_EXHAUSTED");
        for (var item : history) {
            var result = item.getAsJsonObject().getAsJsonObject("result");
            if (result.has("value") && result.getAsJsonObject("value").has("conflictId")
                    && CoordinationDecision.references(result.getAsJsonObject("value"), "claimIds", 2, 2).equals(claims)) return error("CONFLICT_ALREADY_RECORDED");
        }
        var cases = new HashSet<String>(); var identities = new TreeMap<String, String>();
        for (String claim : claims) {
            String task = refs.getAsJsonObject(claim).get("taskId").getAsString(); var source = handoff.getAsJsonObject("tasks").getAsJsonObject(task);
            cases.add(source.get("caseId").getAsString()); identities.put(task, source.get("taskSha256").getAsString());
        }
        return json(Map.of("ok", true, "value", Map.of("conflictId", "conflict-" + turn, "claimIds", claims, "statement", d.get("statement").getAsString(),
                "state", "UNRESOLVED", "authority", "possible-conflict-model-inference", "scope", cases.size() == 1 ? "SAME_CASE_ID_NOT_PROOF_OF_COMPARABILITY" : "DIFFERENT_CASE_IDS",
                "taskIdentities", identities)));
    }
    private static Set<String> seen(JsonArray history) {
        var result = new HashSet<String>(); var ranges = new HashMap<String, TreeMap<Integer, Integer>>(); var totals = new HashMap<String, Integer>();
        for (var item : history) {
            var event = item.getAsJsonObject(); var response = event.getAsJsonObject("result");
            if (event.getAsJsonObject("decision").get("action").getAsString().equals("read_reference") && response.get("ok").getAsBoolean()) {
                var value = response.getAsJsonObject("value"); String id = value.get("referenceId").getAsString();
                ranges.computeIfAbsent(id, ignored -> new TreeMap<>()).merge(value.get("offset").getAsInt(), value.get("nextOffset").getAsInt(), Math::max);
                if (value.get("complete").getAsBoolean()) totals.put(id, value.get("nextOffset").getAsInt());
            }
        }
        for (var entry : ranges.entrySet()) {
            int covered = 0;
            for (var range : entry.getValue().entrySet()) { if (range.getKey() > covered) break; covered = Math.max(covered, range.getValue()); }
            if (totals.containsKey(entry.getKey()) && covered >= totals.get(entry.getKey())) result.add(entry.getKey());
        }
        return result;
    }
    private static int count(JsonArray history, String key) {
        int count = 0; for (var item : history) {
            var result = item.getAsJsonObject().getAsJsonObject("result"); if (result.has("value") && result.getAsJsonObject("value").has(key)) count++;
        } return count;
    }
    private void admit(Path review, JsonObject spec) {
        if (Thread.currentThread().isInterrupted()) throw new Stop("REVIEW_INTERRUPTED");
        if (Files.exists(review.resolve("revoked"), LinkOption.NOFOLLOW_LINKS)) throw new Stop("REVIEW_REVOKED");
        if (!clock.instant().isBefore(Instant.parse(spec.get("expiresAt").getAsString()))) throw new Stop("REVIEW_EXPIRED");
        try { CaseInputs.require(spec.equals(spec(review)) && read(review, "grant.json").equals(grant(review, spec))
                && !clock.instant().isBefore(Instant.parse(spec.get("issuedAt").getAsString())), "Review grant denied"); }
        catch (IOException | RuntimeException e) { throw new Stop("REVIEW_AUTHORIZATION_DENIED"); }
        try { verifyInputs(review, spec); } catch (IOException | RuntimeException e) { throw new Stop("REVIEW_INPUT_IDENTITY_MISMATCH"); }
    }
    public void revoke(String id) throws IOException {
        Path review = review(id); spec(review);
        try { Files.write(review.resolve("revoked"), new byte[0], StandardOpenOption.CREATE_NEW); } catch (FileAlreadyExistsException ignored) {}
    }
    private static void validateCall(Path review, JsonObject spec, int turn, String id) throws IOException {
        CaseInputs.require(id.matches("model-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid review call");
        var mapping = read(review, "call-" + turn + ".json"); var reservation = read(review, "reservation-" + turn + ".json");
        var request = CaseInputs.JSON.fromJson(read(review, "model-calls/" + id + "/request.json"), ModelCallBroker.Request.class);
        var payload = CaseInputs.object(request.input().getBytes(StandardCharsets.UTF_8)); var previous = new JsonArray();
        for (int earlier = 1; earlier < turn; earlier++) previous.add(read(review, "event-" + earlier + ".json"));
        CaseInputs.require(mapping.get("reservationSha256").getAsString().equals(hash(review, "reservation-" + turn + ".json"))
                && reservation.get("requestSha256").getAsString().equals(CaseInputs.hash(request.body()))
                && request.model().equals(spec.get("model").getAsString()) && request.protocol().equals(spec.get("protocol").getAsString())
                && request.maxOutputTokens() == 2048 && payload.get("handoff").equals(read(review, "inputs/handoff.json"))
                && payload.get("events").equals(previous) && payload.get("decisionsRemaining").getAsInt() == 13 - turn, "Changed review request");
        if (request.protocol().equals("coordination-v2")) CaseInputs.require(payload.get("fullyReadReferences").equals(CaseInputs.JSON.toJsonTree(new TreeSet<>(seen(previous)))), "Changed observed reference list");
    }
    private JsonObject finish(Path review, JsonObject spec, String status, JsonObject decision) throws IOException {
        var completion = json(Map.of("status", status, "finishedAt", clock.instant().toString()));
        if (decision != null) completion.add("review", decision);
        DurableExecution.publish(review.resolve("completion.json"), completion);
        var result = materialize(review, spec, completion);
        DurableExecution.publish(review.resolve("result.json"), result); return result;
    }
    private static JsonObject materialize(Path review, JsonObject spec, JsonObject completion) throws IOException {
        var history = new JsonArray(); var handoff = read(review, "inputs/handoff.json");
        var records = new TreeMap<String, String>(); records.put("completion.json", hash(review, "completion.json"));
        int reserved = 0, registered = 0, completed = 0, unresolved = 0; long input = 0, output = 0;
        JsonObject lastDecision = null;
        for (int turn = 1; turn <= 12; turn++) {
            String reservation = "reservation-" + turn + ".json", mapping = "call-" + turn + ".json", event = "event-" + turn + ".json";
            if (!Files.exists(review.resolve(reservation))) continue; reserved++; records.put(reservation, hash(review, reservation));
            if (!Files.exists(review.resolve(mapping))) { unresolved++; continue; }
            String call = read(review, mapping).get("callId").getAsString(); validateCall(review, spec, turn, call); registered++;
            records.put(mapping, hash(review, mapping)); records.put("model-calls/" + call + "/request.json", hash(review, "model-calls/" + call + "/request.json"));
            String resultPath = "model-calls/" + call + "/result.json";
            if (!Files.exists(review.resolve(resultPath))) { unresolved++; continue; }
            records.put(resultPath, hash(review, resultPath)); var response = read(review, resultPath);
            lastDecision = null;
            if (response.get("status").getAsString().equals("COMPLETED")) {
                completed++; input += response.get("inputTokens").getAsLong(); output += response.get("outputTokens").getAsLong();
                try { lastDecision = CoordinationDecision.parse(response.get("outputText").getAsString()); }
                catch (RuntimeException e) { lastDecision = null; }
            }
            else if (!response.has("remoteOutcome") || !response.get("remoteOutcome").getAsString().equals("NOT_DISPATCHED")) unresolved++;
            if (Files.exists(review.resolve(event), LinkOption.NOFOLLOW_LINKS)) {
                CaseInputs.require(lastDecision != null && !lastDecision.get("action").getAsString().equals("finish"), "Invalid recorded review decision");
                var observation = read(review, event);
                CaseInputs.require(observation.equals(json(Map.of("eventId", "review-" + turn, "modelCallId", call, "decision", lastDecision,
                        "result", act(review, handoff, lastDecision, history, turn)))), "Changed recorded review action");
                records.put(event, hash(review, event)); history.add(observation);
            }
        }
        String status = completion.get("status").getAsString();
        if (completion.has("review")) {
            var decision = completion.getAsJsonObject("review");
            CaseInputs.require(decision.equals(lastDecision) && decision.get("action").getAsString().equals("finish")
                    && seen(history).containsAll(CoordinationDecision.references(decision, "citations", 1, 6)), "Changed final review decision");
            CaseInputs.require(status.equals(count(history, "conflictId") > 0 ? "UNRESOLVED_CONFLICTS"
                    : count(history, "proposalId") > 0 ? "FOLLOW_UP_PROPOSED" : "REVIEW_RECORDED"), "Invalid advisory review status");
        } else CaseInputs.require(!Set.of("UNRESOLVED_CONFLICTS", "FOLLOW_UP_PROPOSED", "REVIEW_RECORDED").contains(status), "Missing final review decision");
        var result = json(Map.of("reviewId", spec.get("reviewId").getAsString(), "reviewSha256", hash(review, "review.json"), "status", status,
                "authority", "coordinator-advice-not-execution-evidence", "events", history, "recordSha256", records, "finishedAt", completion.get("finishedAt").getAsString(),
                "usage", Map.of("reservedSlots", reserved, "registeredCalls", registered, "completedResponses", completed,
                        "inputTokens", input, "outputTokens", output, "unresolvedUsageSlots", unresolved)));
        if (completion.has("review")) result.add("review", completion.get("review"));
        return result;
    }
    private static JsonObject retained(Path review, JsonObject spec) throws IOException {
        var result = read(review, "result.json"); verifyInputs(review, spec);
        CaseInputs.require(result.equals(materialize(review, spec, read(review, "completion.json"))), "Changed completed review");
        return result;
    }
    private static void verifyInputs(Path review, JsonObject spec) throws IOException {
        var artifacts = spec.getAsJsonObject("artifacts"); CaseInputs.require(artifacts.size() >= 3 && artifacts.size() <= 120, "Invalid review inventory");
        for (var entry : artifacts.entrySet()) CaseInputs.require(entry.getValue().getAsString().equals(hash(review, entry.getKey())), "Changed review input");
    }
    private static JsonObject grant(Path review, JsonObject spec) throws IOException {
        return json(Map.of("reviewId", spec.get("reviewId").getAsString(), "reviewSha256", hash(review, "review.json"),
                "permission", "read-captured-evidence-and-propose-only", "model", spec.get("model").getAsString(), "expiresAt", spec.get("expiresAt").getAsString()));
    }
    private Path review(String id) {
        CaseInputs.require(id != null && id.matches("review-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid review reference");
        ConsumerTasks.directory(root); Path review = root.resolve(id); ConsumerTasks.directory(review); return review;
    }
    private static JsonObject spec(Path review) throws IOException {
        var spec = read(review, "review.json"); CaseInputs.require(spec.get("formatVersion").getAsInt() == 1
                && spec.get("reviewId").getAsString().equals(review.getFileName().toString()) && Set.of("coordination-v1", "coordination-v2").contains(spec.get("protocol").getAsString())
                && spec.get("maxDecisions").getAsInt() == 12 && Duration.between(Instant.parse(spec.get("issuedAt").getAsString()),
                Instant.parse(spec.get("expiresAt").getAsString())).equals(Duration.ofMinutes(20)), "Invalid review spec"); return spec;
    }
    private static JsonObject read(Path base, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(base, name)); }
    private static String hash(Path base, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(base, name)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject error(String code) { return json(Map.of("ok", false, "error", code)); }
    interface ModelsFactory { ConsumerInvestigator.Models models(Path state) throws IOException; }
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L; final String status; Stop(String status) { this.status = status; }
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3 || args.length == 4, "Usage: CoordinationReview register STATE BATCH MODEL | run/revoke STATE REVIEW");
        var reviews = new CoordinationReview(Path.of(args[1]));
        if (args[0].equals("register") && args.length == 4) System.out.println(reviews.register(args[2], args[3]));
        else if (args[0].equals("revoke") && args.length == 3) { reviews.revoke(args[2]); System.out.println("Review revoked: " + args[2]); }
        else if (args[0].equals("run") && args.length == 3) {
            var result = reviews.run(args[2]); System.out.println("Review: " + args[2]); System.out.println("Status: " + result.get("status").getAsString());
            if (result.has("events")) System.out.println("Follow-up proposals: " + count(result.getAsJsonArray("events"), "proposalId")
                    + "; unresolved possible conflicts: " + count(result.getAsJsonArray("events"), "conflictId") + "; usage: " + result.get("usage"));
            if (!Set.of("REVIEW_RECORDED", "FOLLOW_UP_PROPOSED", "UNRESOLVED_CONFLICTS").contains(result.get("status").getAsString())) throw new IOException("Review did not finish; inspect its retained records");
        } else throw new IllegalArgumentException("Invalid review action");
    }
}
