package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Host-authorized follow-up round. Advisory proposals cannot authorize this dispatcher. */
public final class FollowUpDispatch {
    private final Path state, root;
    private final Clock clock;
    private final ConsumerTasks.Backends delegates;
    public FollowUpDispatch(Path state) throws IOException { this(state, Clock.systemUTC(), new ConsumerTasks.Backends() {}); }
    FollowUpDispatch(Path state, Clock clock, ConsumerTasks.Backends delegates) throws IOException {
        this.state = state.toAbsolutePath().normalize(); root = this.state.resolve("follow-ups");
        Files.createDirectories(root); ConsumerTasks.directory(root); this.clock = clock; this.delegates = delegates;
    }
    public String register(String reviewId, List<String> proposalIds, int maxCalls) throws IOException {
        CaseInputs.require(proposalIds.size() >= 1 && proposalIds.size() <= 2 && new HashSet<>(proposalIds).size() == proposalIds.size(), "Select one or two distinct proposals");
        int budget = maxCalls == 0 ? proposalIds.size() * 12 : maxCalls;
        CaseInputs.require(budget >= 1 && budget <= proposalIds.size() * 12, "Invalid follow-up call budget");
        var reviews = new CoordinationReview(state); var original = reviews.readCompleted(reviewId);
        Path source = state.resolve("coordination-reviews/" + reviewId); var sourceSpec = read(source, "review.json");
        var proposals = new LinkedHashMap<String, JsonObject>(); var sourceTasks = new HashSet<String>();
        for (String selected : proposalIds) {
            CaseInputs.require(selected.matches("follow-up-(?:[1-9]|1[0-2])"), "Invalid proposal reference");
            JsonObject found = null;
            for (var event : original.getAsJsonArray("events")) {
                var outcome = event.getAsJsonObject().getAsJsonObject("result");
                if (outcome.get("ok").getAsBoolean() && outcome.has("value") && outcome.getAsJsonObject("value").has("proposalId")
                        && outcome.getAsJsonObject("value").get("proposalId").getAsString().equals(selected)) found = outcome.getAsJsonObject("value");
            }
            CaseInputs.require(found != null && found.get("state").getAsString().equals("PROPOSED_NOT_DISPATCHED")
                    && sourceTasks.add(found.get("taskId").getAsString()), "Unknown, rejected or duplicate-task proposal");
            proposals.put(selected, found.deepCopy());
        }
        Instant issued = clock.instant(); String id = "follow-up-" + UUID.randomUUID(); Path job = Files.createDirectory(root.resolve(id));
        Files.createDirectory(job.resolve("inputs")); Files.createDirectory(job.resolve("child-state"));
        DurableExecution.publish(job.resolve("child-state/controller.json"), Map.of("formatVersion", 1, "controllerId", id));
        var artifacts = new TreeMap<String, String>(); long total = 0;
        var names = new ArrayList<>(sourceSpec.getAsJsonObject("artifacts").keySet()); names.addAll(List.of("review.json", "result.json"));
        for (String name : names) {
            byte[] value = ConsumerTasks.bytes(source, name); total += value.length; CaseInputs.require(total <= 16 * 1024 * 1024, "Review capture too large");
            String target = "inputs/review/" + name; Path path = job.resolve(target); Files.createDirectories(path.getParent()); Files.write(path, value, StandardOpenOption.CREATE_NEW);
            artifacts.put(target, CaseInputs.hash(value));
        }
        CaseInputs.require(original.equals(reviews.readCompleted(reviewId)), "Review changed during capture");
        var tasks = new ConsumerTasks(job.resolve("child-state"), clock, scoped(job));
        var selections = new LinkedHashMap<String, Object>(); var childIds = new ArrayList<String>();
        for (var entry : proposals.entrySet()) {
            var proposal = entry.getValue(); String sourceTask = proposal.get("taskId").getAsString();
            String sourceHash = hash(job, "inputs/review/inputs/" + sourceTask + "/task.json");
            var focus = json(Map.of("controllerId", id, "reviewId", reviewId, "proposalId", entry.getKey(), "sourceTaskId", sourceTask,
                    "sourceTaskSha256", sourceHash, "question", proposal.get("question"), "sourceCitationIds", proposal.get("citations"),
                    "authority", "advisory-question-not-evidence", "contentTrust", "untrusted-data"));
            String child = tasks.registerFollowUp(state, sourceTask, sourceHash, focus); childIds.add(child);
            selections.put(entry.getKey(), Map.of("sourceTaskId", sourceTask, "sourceTaskSha256", sourceHash, "taskId", child, "focus", focus));
        }
        String batch = scheduler(job).registerFollowUps(childIds, budget);
        artifacts.put("child-state/controller.json", hash(job, "child-state/controller.json"));
        for (String name : List.of("batch.json", "grant.json")) {
            String path = "child-state/parallel-investigations/" + batch + "/" + name; artifacts.put(path, hash(job, path));
        }
        var spec = new TreeMap<String, Object>(Map.of("formatVersion", 1, "followUpId", id, "reviewId", reviewId,
                "reviewResultSha256", artifacts.get("inputs/review/result.json"), "batchId", batch, "selections", selections,
                "maxCalls", budget, "artifacts", artifacts, "issuedAt", issued.toString()));
        spec.put("expiresAt", issued.plus(Duration.ofMinutes(20)).toString());
        DurableExecution.publish(job.resolve("follow-up.json"), spec);
        DurableExecution.publish(job.resolve("grant.json"), grant(job, read(job, "follow-up.json"))); return id;
    }
    private ParallelInvestigations scheduler(Path job) throws IOException { return new ParallelInvestigations(job.resolve("child-state"), clock, scoped(job)); }
    private ConsumerTasks.Backends scoped(Path job) {
        return new ConsumerTasks.Backends() {
            public String controllerId() { return job.getFileName().toString(); }
            public ConsumerInvestigator.Models models(Path childState) throws IOException {
                var backend = delegates.models(childState);
                return new ConsumerInvestigator.Models() {
                    public String register(ModelCallBroker.Request request) throws IOException {
                        admit(job, spec(job)); String call = backend.register(request); admit(job, spec(job)); return call;
                    }
                    public JsonObject execute(String call) throws IOException {
                        admit(job, spec(job)); var response = backend.execute(call); admit(job, spec(job)); return response;
                    }
                };
            }
            public ConsumerInvestigator.Tools tools(Path childState) throws IOException {
                var backend = delegates.tools(childState);
                return (context, tool, arguments) -> { check(job); var result = backend.invoke(context, tool, arguments); check(job); return result; };
            }
        };
    }
    private void check(Path job) {
        try { admit(job, spec(job)); } catch (IOException e) { throw new Stop("FOLLOW_UP_AUTHORIZATION_DENIED"); }
    }
    public JsonObject run(String id) throws IOException {
        Path job = job(id);
        try (var lease = LocalFileLease.tryAcquire(job.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("followUpId", id, "status", "ACTIVE"));
            var spec = spec(job);
            if (Files.exists(job.resolve("result.json"), LinkOption.NOFOLLOW_LINKS)) return retained(job, spec);
            if (Files.exists(job.resolve("completion.json"), LinkOption.NOFOLLOW_LINKS)) {
                verifyArtifacts(job, spec); var result = materialize(job, spec, read(job, "completion.json"));
                DurableExecution.publish(job.resolve("result.json"), result); return result;
            }
            var batches = scheduler(job);
            try {
                admit(job, spec); var result = batches.run(spec.get("batchId").getAsString());
                if (result.get("status").getAsString().equals("ACTIVE")) return json(Map.of("followUpId", id, "status", "ACTIVE"));
                admit(job, spec); return finish(job, spec, "FOLLOW_UPS_RECORDED");
            } catch (Stop e) {
                batches.revoke(spec.get("batchId").getAsString());
                var result = batches.run(spec.get("batchId").getAsString());
                if (result.get("status").getAsString().equals("ACTIVE")) return json(Map.of("followUpId", id, "status", "ACTIVE", "admissionStop", e.status));
                return finish(job, spec, e.status);
            }
        }
    }
    public void revoke(String id) throws IOException {
        Path job = job(id); var spec = spec(job);
        try { Files.write(job.resolve("revoked"), new byte[0], StandardOpenOption.CREATE_NEW); } catch (FileAlreadyExistsException ignored) {}
        scheduler(job).revoke(spec.get("batchId").getAsString());
    }
    private void admit(Path job, JsonObject spec) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Follow-up controller interrupted; resume the same registration");
        if (Files.exists(job.resolve("revoked"), LinkOption.NOFOLLOW_LINKS)) throw new Stop("FOLLOW_UP_REVOKED");
        if (!clock.instant().isBefore(Instant.parse(spec.get("expiresAt").getAsString()))) throw new Stop("FOLLOW_UP_EXPIRED");
        try { CaseInputs.require(spec.equals(spec(job)) && read(job, "grant.json").equals(grant(job, spec))
                && !clock.instant().isBefore(Instant.parse(spec.get("issuedAt").getAsString())), "Follow-up permission denied"); }
        catch (IOException | RuntimeException e) { throw new Stop("FOLLOW_UP_AUTHORIZATION_DENIED"); }
        try { verifyArtifacts(job, spec); } catch (IOException | RuntimeException e) { throw new Stop("FOLLOW_UP_INPUT_IDENTITY_MISMATCH"); }
    }
    private JsonObject finish(Path job, JsonObject spec, String status) throws IOException {
        String batchResult = "child-state/parallel-investigations/" + spec.get("batchId").getAsString() + "/result.json";
        var completion = json(Map.of("status", status, "finishedAt", clock.instant().toString(), "batchResultSha256", hash(job, batchResult)));
        DurableExecution.publish(job.resolve("completion.json"), completion); var result = materialize(job, spec, completion);
        DurableExecution.publish(job.resolve("result.json"), result); return result;
    }
    private JsonObject materialize(Path job, JsonObject spec, JsonObject completion) throws IOException {
        var batch = scheduler(job).readCompleted(spec.get("batchId").getAsString());
        CaseInputs.require(batch.get("status").getAsString().equals("COMPLETED") && completion.get("batchResultSha256").getAsString()
                .equals(hash(job, "child-state/parallel-investigations/" + spec.get("batchId").getAsString() + "/result.json")), "Changed follow-up batch outcome");
        var responses = new LinkedHashMap<String, Object>();
        for (var item : spec.getAsJsonObject("selections").entrySet()) {
            var selected = item.getValue().getAsJsonObject(); String task = selected.get("taskId").getAsString();
            var outcome = batch.getAsJsonObject("children").getAsJsonObject(task);
            var row = json(Map.of("sourceTaskId", selected.get("sourceTaskId"), "sourceTaskSha256", selected.get("sourceTaskSha256"),
                    "taskId", task, "taskSha256", outcome.get("taskSha256"), "status", outcome.get("status"), "authority", "advisory-follow-up-outcome"));
            if (outcome.has("taskResultSha256")) {
                row.add("taskResultSha256", outcome.get("taskResultSha256"));
                var result = read(job, "child-state/tasks/" + task + "/result.json");
                if (result.has("agentId")) { row.add("agentId", result.get("agentId")); row.add("agentResultSha256", result.get("agentResultSha256")); }
            }
            responses.put(item.getKey(), row);
        }
        String status = completion.get("status").getAsString();
        CaseInputs.require(Set.of("FOLLOW_UPS_RECORDED", "FOLLOW_UP_REVOKED", "FOLLOW_UP_EXPIRED", "FOLLOW_UP_AUTHORIZATION_DENIED", "FOLLOW_UP_INPUT_IDENTITY_MISMATCH").contains(status), "Invalid follow-up status");
        var result = json(Map.of("followUpId", spec.get("followUpId"), "followUpSha256", hash(job, "follow-up.json"), "reviewId", spec.get("reviewId"),
                "reviewResultSha256", spec.get("reviewResultSha256"), "status", status, "authority", "follow-up-workflow-not-execution-evidence",
                "responses", responses, "reconciliation", "PENDING", "usage", batch.get("usage"), "completionSha256", hash(job, "completion.json")));
        result.add("finishedAt", completion.get("finishedAt")); return result;
    }
    private JsonObject retained(Path job, JsonObject spec) throws IOException {
        verifyArtifacts(job, spec); var result = read(job, "result.json");
        CaseInputs.require(result.equals(materialize(job, spec, read(job, "completion.json"))), "Changed completed follow-up round"); return result;
    }
    private static void verifyArtifacts(Path job, JsonObject spec) throws IOException {
        var artifacts = spec.getAsJsonObject("artifacts"); CaseInputs.require(artifacts.size() >= 6 && artifacts.size() <= 125, "Invalid follow-up inventory");
        for (var entry : artifacts.entrySet()) CaseInputs.require(entry.getValue().getAsString().equals(hash(job, entry.getKey())), "Changed follow-up input");
    }
    private static JsonObject grant(Path job, JsonObject spec) throws IOException {
        return json(Map.of("followUpId", spec.get("followUpId"), "followUpSha256", hash(job, "follow-up.json"),
                "permission", "execute-selected-follow-ups", "expiresAt", spec.get("expiresAt")));
    }
    private Path job(String id) {
        CaseInputs.require(id != null && id.matches("follow-up-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid follow-up reference");
        ConsumerTasks.directory(root); Path job = root.resolve(id); ConsumerTasks.directory(job); return job;
    }
    private static JsonObject spec(Path job) throws IOException {
        var spec = read(job, "follow-up.json"); var selected = spec.getAsJsonObject("selections");
        CaseInputs.require(spec.get("formatVersion").getAsInt() == 1 && spec.get("followUpId").getAsString().equals(job.getFileName().toString())
                && selected.size() >= 1 && selected.size() <= 2 && spec.get("maxCalls").getAsInt() >= 1 && spec.get("maxCalls").getAsInt() <= selected.size() * 12
                && Duration.between(Instant.parse(spec.get("issuedAt").getAsString()), Instant.parse(spec.get("expiresAt").getAsString())).equals(Duration.ofMinutes(20)), "Invalid follow-up definition");
        return spec;
    }
    private static JsonObject read(Path base, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(base, name)); }
    private static String hash(Path base, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(base, name)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L; final String status; Stop(String status) { super(status); this.status = status; }
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 3, "Usage: FollowUpDispatch register STATE REVIEW MAX_CALLS PROPOSAL... | run/revoke STATE FOLLOW_UP");
        var dispatch = new FollowUpDispatch(Path.of(args[1]));
        if (args[0].equals("register") && args.length >= 5) System.out.println(dispatch.register(args[2], Arrays.asList(args).subList(4, args.length), Integer.parseInt(args[3])));
        else if (args[0].equals("revoke") && args.length == 3) { dispatch.revoke(args[2]); System.out.println("Follow-up round revoked: " + args[2]); }
        else if (args[0].equals("run") && args.length == 3) {
            var result = dispatch.run(args[2]); System.out.println("Follow-up round: " + args[2]); System.out.println("Status: " + result.get("status").getAsString());
            if (result.has("responses")) for (var row : result.getAsJsonObject("responses").entrySet()) System.out.println(row.getKey() + ": " + row.getValue().getAsJsonObject().get("status").getAsString());
            if (result.has("usage")) { var usage = result.getAsJsonObject("usage").deepCopy(); usage.remove("recordSha256"); System.out.println("Usage: " + usage); }
            if (!result.get("status").getAsString().equals("FOLLOW_UPS_RECORDED")) throw new IOException("Follow-up round did not finish; inspect retained records");
        } else throw new IllegalArgumentException("Invalid follow-up action");
    }
}
