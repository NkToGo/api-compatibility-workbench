package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** A trusted-host scheduler, not an agent or a compatibility verdict. */
public final class ParallelInvestigations {
    private final Path state, root;
    private final Clock clock;
    private final ConsumerTasks.Backends delegates;

    public ParallelInvestigations(Path state) throws IOException { this(state, Clock.systemUTC(), new ConsumerTasks.Backends() {}); }
    ParallelInvestigations(Path state, Clock clock, ConsumerTasks.Backends delegates) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.root = this.state.resolve("parallel-investigations");
        Files.createDirectories(root); ConsumerTasks.directory(root); this.clock = clock; this.delegates = delegates;
    }

    /** Select fresh host-registered tasks. Membership and budgets cannot be expanded after registration. */
    public String register(List<String> ids, int parallelism, int maxCalls) throws IOException {
        return register(ids, parallelism, maxCalls, false);
    }
    String registerFollowUps(List<String> ids, int maxCalls) throws IOException {
        CaseInputs.require(delegates.controllerId() != null, "Follow-ups require an owning controller");
        return register(ids, Math.min(2, ids.size()), maxCalls, true);
    }
    private String register(List<String> ids, int parallelism, int maxCalls, boolean owned) throws IOException {
        ConsumerTasks.controller(state, delegates.controllerId());
        CaseInputs.require(ids.size() >= (owned ? 1 : 2) && ids.size() <= (owned ? 2 : 4) && new HashSet<>(ids).size() == ids.size()
                && parallelism >= 1 && parallelism <= 2 && maxCalls >= 1 && maxCalls <= ids.size() * 12, "Invalid parallel budget");
        var tasks = new ConsumerTasks(state, clock, delegates); var leases = new ArrayList<LocalFileLease>();
        try {
            for (String id : new TreeSet<>(ids)) {
                var lease = LocalFileLease.tryAcquire(tasks.task(id).resolve("owner.lock"));
                CaseInputs.require(lease != null, "Selected task is active"); leases.add(lease);
            }
            var entries = new LinkedHashMap<String, JsonObject>();
            for (String id : ids) {
                if (owned) CaseInputs.require(record(tasks.task(id), "task.json").getAsJsonObject("case").getAsJsonObject("value")
                        .getAsJsonObject("followUp").get("controllerId").getAsString().equals(delegates.controllerId()), "Foreign follow-up task");
                entries.put(id, tasks.claimable(id));
            }
            String id = "batch-" + UUID.randomUUID(); Path batch = Files.createDirectory(root.resolve(id));
            Files.createDirectory(batch.resolve("calls")); Files.createDirectory(batch.resolve("children"));
            Instant issued = clock.instant();
            var spec = json(Map.of("formatVersion", owned ? 2 : 1, "batchId", id, "tasks", entries, "parallelism", parallelism,
                    "maxCalls", maxCalls, "issuedAt", issued.toString(), "expiresAt", issued.plus(Duration.ofMinutes(20)).toString()));
            if (owned) spec.addProperty("controllerId", delegates.controllerId());
            DurableExecution.publish(batch.resolve("batch.json"), spec);
            String hash = hash(batch, "batch.json");
            for (String task : ids) DurableExecution.publish(tasks.task(task).resolve("parallel-owner.json"),
                    Map.of("batchId", id, "taskId", task, "batchSha256", hash, "taskSha256", entries.get(task).get("taskSha256").getAsString()));
            // A partial registration has no root grant; its task claims remain inert rather than being reassigned.
            DurableExecution.publish(batch.resolve("grant.json"), Map.of("formatVersion", 1, "batchId", id,
                    "batchSha256", hash, "permission", "run-fixed-investigation-batch", "expiresAt", spec.get("expiresAt").getAsString()));
            return id;
        } finally { for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close(); }
    }

    public JsonObject run(String id) throws IOException {
        Path batch = batch(id);
        try (var lease = LocalFileLease.tryAcquire(batch.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("batchId", id, "status", "ACTIVE"));
            var spec = spec(batch);
            if (Files.exists(batch.resolve("result.json"), LinkOption.NOFOLLOW_LINKS)) return retained(batch, spec);
            try { ConsumerTasks.controller(state, delegates.controllerId()); }
            catch (IOException | RuntimeException e) { return json(Map.of("batchId", id, "status", "BATCH_CONTROLLED_STATE_DENIED")); }
            var pool = Executors.newFixedThreadPool(spec.get("parallelism").getAsInt());
            var futures = new ArrayList<Future<JsonObject>>();
            try {
                for (String task : spec.getAsJsonObject("tasks").keySet()) futures.add(pool.submit(() -> child(batch, spec, task)));
                var children = new LinkedHashMap<String, JsonObject>(); boolean active = false;
                for (var future : futures) {
                    var child = future.get(); children.put(child.get("taskId").getAsString(), child);
                    active |= child.get("status").getAsString().equals("ACTIVE");
                }
                if (active) return json(Map.of("batchId", id, "status", "ACTIVE", "children", children));
                var childHashes = new TreeMap<String, String>();
                for (String task : children.keySet()) childHashes.put(task, hash(batch, "children/" + task + ".json"));
                var result = json(Map.of("formatVersion", 1, "batchId", id, "batchSha256", hash(batch, "batch.json"),
                        "status", "COMPLETED", "authority", "orchestration-status-not-execution-evidence",
                        "finishedAt", clock.instant().toString(), "children", children, "childSha256", childHashes,
                        "usage", usage(batch, spec)));
                DurableExecution.publish(batch.resolve("result.json"), result); return result;
            } catch (InterruptedException e) {
                for (var future : futures) future.cancel(true);
                Thread.currentThread().interrupt(); throw new IOException("Batch controller interrupted; resume its existing records", e);
            } catch (ExecutionException e) { throw new IOException("Batch worker did not publish an outcome; inspect retained records", e); }
            finally {
                pool.shutdownNow(); boolean interrupted = Thread.interrupted();
                // Keep the root lease until admitted children drain, preventing overlap with a resumed controller.
                while (!pool.isTerminated()) try { pool.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private JsonObject child(Path batch, JsonObject spec, String id) throws IOException {
        String path = "children/" + id + ".json";
        if (Files.exists(batch.resolve(path), LinkOption.NOFOLLOW_LINKS)) return retainedChild(batch, spec, id);
        var backends = scoped(batch, spec, id); var tasks = new ConsumerTasks(state, clock, backends);
        String status; JsonObject taskResult = null;
        try {
            admit(batch, spec, id); taskResult = tasks.run(id); status = taskResult.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("taskId", id, "status", "ACTIVE"));
        } catch (Stop e) { status = e.status; }
        catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Child interrupted", e);
            status = "TASK_OPERATIONAL_FAILURE";
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Child interrupted");
        var result = json(Map.of("taskId", id, "status", status, "batchSha256", hash(batch, "batch.json"),
                "taskSha256", spec.getAsJsonObject("tasks").getAsJsonObject(id).get("taskSha256").getAsString(),
                "finishedAt", clock.instant().toString(), "authority", "orchestration-status-not-execution-evidence"));
        if (taskResult != null && Files.exists(tasks.task(id).resolve("result.json")))
            result.addProperty("taskResultSha256", hash(tasks.task(id), "result.json"));
        DurableExecution.publish(batch.resolve(path), result); return result;
    }

    private ConsumerTasks.Backends scoped(Path batch, JsonObject spec, String task) {
        return new ConsumerTasks.Backends() {
            public String batchId() { return spec.get("batchId").getAsString(); }
            public String controllerId() { return delegates.controllerId(); }
            public ConsumerInvestigator.Tools tools(Path taskState) throws IOException {
                var delegate = delegates.tools(taskState);
                return (context, tool, arguments) -> {
                    admit(batch, spec, task); var result = delegate.invoke(context, tool, arguments);
                    admit(batch, spec, task); return result;
                };
            }
            public ConsumerInvestigator.Models models(Path taskState) throws IOException {
                var delegate = delegates.models(taskState);
                return new ConsumerInvestigator.Models() {
                    public String register(ModelCallBroker.Request request) throws IOException {
                        admit(batch, spec, task);
                        int turn = 13 - CaseInputs.object(request.input().getBytes(StandardCharsets.UTF_8)).get("decisionsRemaining").getAsInt();
                        CaseInputs.require(turn >= 1 && turn <= 12, "Invalid task decision index");
                        String key = task + "-" + turn; String requestHash = CaseInputs.hash(request.body());
                        try (var lease = LocalFileLease.acquire(batch.resolve("budget.lock"))) {
                            Objects.requireNonNull(lease);
                            String slot = "calls/" + key + ".json", mapping = "calls/" + key + "-mapping.json";
                            if (Files.exists(batch.resolve(slot), LinkOption.NOFOLLOW_LINKS)) {
                                var reserved = record(batch, slot);
                                CaseInputs.require(reserved.equals(reservation(batch, spec, task, turn, requestHash)), "Changed reserved request");
                                if (!Files.exists(batch.resolve(mapping), LinkOption.NOFOLLOW_LINKS)) throw new Stop("BATCH_CALL_REGISTRATION_UNCERTAIN");
                                return mapped(batch, key, requestHash);
                            }
                            admit(batch, spec, task);
                            if (slots(batch).size() >= spec.get("maxCalls").getAsInt()) throw new Stop("BATCH_CALL_BUDGET_EXHAUSTED");
                            DurableExecution.publish(batch.resolve(slot), reservation(batch, spec, task, turn, requestHash));
                            String call;
                            try { call = delegate.register(request); }
                            catch (IOException | RuntimeException e) {
                                if (Thread.currentThread().isInterrupted()) throw new IOException("Registration interrupted", e);
                                throw new Stop("BATCH_CALL_REGISTRATION_UNCERTAIN");
                            }
                            DurableExecution.publish(batch.resolve(mapping), Map.of("callId", call, "reservationSha256", hash(batch, slot)));
                            return call;
                        }
                    }
                    public JsonObject execute(String call) throws IOException {
                        admit(batch, spec, task); boolean found = false;
                        try (var lease = LocalFileLease.acquire(batch.resolve("budget.lock"))) {
                            Objects.requireNonNull(lease);
                            for (String slot : slots(batch)) if (slot.startsWith(task + "-")) {
                                String mapping = "calls/" + slot.substring(0, slot.length() - 5) + "-mapping.json";
                                if (Files.exists(batch.resolve(mapping), LinkOption.NOFOLLOW_LINKS)) {
                                    var mapped = record(batch, mapping);
                                    CaseInputs.require(mapped.get("reservationSha256").getAsString().equals(hash(batch, "calls/" + slot)), "Changed call mapping");
                                    if (mapped.get("callId").getAsString().equals(call)) {
                                        var request = CaseInputs.JSON.fromJson(record(taskState, "model-calls/" + call + "/request.json"), ModelCallBroker.Request.class);
                                        int turn = Integer.parseInt(slot.substring(task.length() + 1, slot.length() - 5));
                                        CaseInputs.require(record(batch, "calls/" + slot).equals(reservation(batch, spec, task, turn,
                                                CaseInputs.hash(request.body()))), "Changed mapped request");
                                        found = true;
                                    }
                                }
                            }
                        }
                        CaseInputs.require(found, "Call outside batch budget");
                        var result = delegate.execute(call); admit(batch, spec, task); return result;
                    }
                };
            }
        };
    }

    private static JsonObject reservation(Path batch, JsonObject spec, String task, int turn, String requestHash) throws IOException {
        return json(Map.of("batchSha256", hash(batch, "batch.json"), "taskId", task,
                "taskSha256", spec.getAsJsonObject("tasks").getAsJsonObject(task).get("taskSha256").getAsString(),
                "turn", turn, "requestSha256", requestHash));
    }
    private static String mapped(Path batch, String key, String requestHash) throws IOException {
        var mapping = record(batch, "calls/" + key + "-mapping.json");
        CaseInputs.require(mapping.get("reservationSha256").getAsString().equals(hash(batch, "calls/" + key + ".json")), "Changed call reservation");
        CaseInputs.require(record(batch, "calls/" + key + ".json").get("requestSha256").getAsString().equals(requestHash), "Changed request");
        return mapping.get("callId").getAsString();
    }
    private void admit(Path batch, JsonObject spec, String task) {
        if (Thread.currentThread().isInterrupted()) throw new Stop("BATCH_INTERRUPTED");
        if (Files.exists(batch.resolve("revoked"), LinkOption.NOFOLLOW_LINKS)) throw new Stop("BATCH_REVOKED");
        if (!clock.instant().isBefore(Instant.parse(spec.get("expiresAt").getAsString()))) throw new Stop("BATCH_EXPIRED");
        try {
            ConsumerTasks.controller(state, delegates.controllerId());
            var grant = record(batch, "grant.json");
            CaseInputs.require(spec.equals(spec(batch)) && !clock.instant().isBefore(Instant.parse(spec.get("issuedAt").getAsString()))
                    && grant.equals(json(Map.of("formatVersion", 1, "batchId", spec.get("batchId").getAsString(),
                    "batchSha256", hash(batch, "batch.json"), "permission", "run-fixed-investigation-batch",
                    "expiresAt", spec.get("expiresAt").getAsString()))), "Batch admission denied");
        } catch (IOException | RuntimeException e) { throw new Stop("BATCH_AUTHORIZATION_DENIED"); }
        try {
            Path child = state.resolve("tasks").resolve(task); var expected = spec.getAsJsonObject("tasks").getAsJsonObject(task);
            CaseInputs.require(hash(child, "task.json").equals(expected.get("taskSha256").getAsString())
                    && hash(child, "grant.json").equals(expected.get("grantSha256").getAsString())
                    && record(child, "parallel-owner.json").equals(json(Map.of("batchId", spec.get("batchId").getAsString(),
                    "taskId", task, "batchSha256", hash(batch, "batch.json"), "taskSha256", expected.get("taskSha256").getAsString()))), "Changed batch task");
        } catch (IOException | RuntimeException e) { throw new Stop("BATCH_TASK_IDENTITY_MISMATCH"); }
    }

    public void revoke(String id) throws IOException {
        Path batch = batch(id); spec(batch);
        try { Files.write(batch.resolve("revoked"), new byte[0], StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ignored) { /* Permanent admission stop. */ }
    }

    /** Read validated historical outcomes without admitting unfinished investigation work. */
    public JsonObject readCompleted(String id) throws IOException {
        Path batch = batch(id);
        try (var lease = LocalFileLease.tryAcquire(batch.resolve("owner.lock"))) {
            CaseInputs.require(lease != null, "Batch is active");
            return retained(batch, spec(batch));
        }
    }

    private JsonObject retained(Path batch, JsonObject spec) throws IOException {
        var result = record(batch, "result.json");
        CaseInputs.require(result.get("batchSha256").getAsString().equals(hash(batch, "batch.json"))
                && result.getAsJsonObject("children").keySet().equals(spec.getAsJsonObject("tasks").keySet())
                && result.getAsJsonObject("childSha256").keySet().equals(spec.getAsJsonObject("tasks").keySet()), "Changed batch result");
        for (String task : spec.getAsJsonObject("tasks").keySet()) {
            CaseInputs.require(result.getAsJsonObject("childSha256").get(task).getAsString().equals(hash(batch, "children/" + task + ".json"))
                    && result.getAsJsonObject("children").get(task).equals(retainedChild(batch, spec, task)), "Changed child result");
        }
        CaseInputs.require(result.getAsJsonObject("usage").equals(usage(batch, spec)), "Changed batch accounting"); return result;
    }
    private JsonObject retainedChild(Path batch, JsonObject spec, String task) throws IOException {
        var child = record(batch, "children/" + task + ".json");
        CaseInputs.require(child.get("taskId").getAsString().equals(task)
                && child.get("batchSha256").getAsString().equals(hash(batch, "batch.json"))
                && child.get("taskSha256").equals(spec.getAsJsonObject("tasks").getAsJsonObject(task).get("taskSha256")), "Changed child identity");
        if (child.has("taskResultSha256")) {
            var tasks = new ConsumerTasks(state, clock, scoped(batch, spec, task));
            CaseInputs.require(child.get("taskResultSha256").getAsString().equals(hash(tasks.task(task), "result.json")), "Changed retained task outcome");
            CaseInputs.require(tasks.readResult(task).get("status").equals(child.get("status")), "Changed retained task status");
        }
        return child;
    }

    private JsonObject usage(Path batch, JsonObject spec) throws IOException {
        int registered = 0, completed = 0, unresolved = 0; long input = 0, output = 0;
        var inventory = new TreeMap<String, String>(); var calls = slots(batch);
        CaseInputs.require(calls.size() <= spec.get("maxCalls").getAsInt(), "Exceeded retained call budget");
        for (String slot : calls) {
            var reserved = record(batch, "calls/" + slot); String task = reserved.get("taskId").getAsString(); int turn = reserved.get("turn").getAsInt();
            CaseInputs.require(spec.getAsJsonObject("tasks").has(task) && slot.equals(task + "-" + turn + ".json") && turn >= 1 && turn <= 12
                    && reserved.equals(reservation(batch, spec, task, turn, reserved.get("requestSha256").getAsString())), "Invalid retained call reservation");
            inventory.put(slot, hash(batch, "calls/" + slot));
            String mapping = slot.substring(0, slot.length() - 5) + "-mapping.json";
            if (!Files.exists(batch.resolve("calls/" + mapping), LinkOption.NOFOLLOW_LINKS)) { unresolved++; continue; }
            String call = mapped(batch, slot.substring(0, slot.length() - 5), reserved.get("requestSha256").getAsString()); registered++;
            inventory.put(mapping, hash(batch, "calls/" + mapping));
            Path taskState = state.resolve("tasks/" + task + "/state");
            var request = CaseInputs.JSON.fromJson(record(taskState, "model-calls/" + call + "/request.json"), ModelCallBroker.Request.class);
            CaseInputs.require(CaseInputs.hash(request.body()).equals(reserved.get("requestSha256").getAsString()), "Changed accounted request");
            inventory.put(slot + ":request", hash(taskState, "model-calls/" + call + "/request.json"));
            String outcome = "model-calls/" + call + "/result.json";
            if (!Files.exists(taskState.resolve(outcome), LinkOption.NOFOLLOW_LINKS)) { unresolved++; continue; }
            var response = record(taskState, outcome); inventory.put(slot + ":result", hash(taskState, outcome));
            if (response.get("status").getAsString().equals("COMPLETED")) {
                completed++; input += response.get("inputTokens").getAsLong(); output += response.get("outputTokens").getAsLong();
            } else if (!response.has("remoteOutcome") || !response.get("remoteOutcome").getAsString().equals("NOT_DISPATCHED")) unresolved++;
        }
        return json(Map.of("reservedSlots", calls.size(), "registeredCalls", registered, "completedResponses", completed,
                "inputTokens", input, "outputTokens", output, "unresolvedUsageSlots", unresolved, "recordSha256", inventory));
    }
    private static List<String> slots(Path batch) throws IOException {
        Path calls = batch.resolve("calls"); ConsumerTasks.directory(calls);
        try (var paths = Files.list(calls)) {
            var files = paths.map(p -> p.getFileName().toString()).sorted().toList();
            CaseInputs.require(files.size() <= 192 && files.stream().allMatch(n -> n.matches("task-[0-9a-f-]{36}-(?:[1-9]|1[0-2])(?:-mapping)?\\.json(?:\\.pending)?")), "Unexpected budget record");
            return files.stream().filter(n -> n.endsWith(".json") && !n.endsWith("-mapping.json")).toList();
        }
    }
    private Path batch(String id) {
        CaseInputs.require(id != null && id.matches("batch-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid batch reference");
        ConsumerTasks.directory(root); Path batch = root.resolve(id); ConsumerTasks.directory(batch); return batch;
    }
    private static JsonObject spec(Path batch) throws IOException {
        var spec = record(batch, "batch.json"); var tasks = spec.getAsJsonObject("tasks");
        boolean owned = spec.get("formatVersion").getAsInt() == 2;
        if (owned) CaseInputs.require(spec.get("controllerId").equals(record(batch.getParent().getParent(), "controller.json").get("controllerId")), "Changed batch controller");
        CaseInputs.require((owned || spec.get("formatVersion").getAsInt() == 1) && spec.get("batchId").getAsString().equals(batch.getFileName().toString())
                && tasks.size() >= (owned ? 1 : 2) && tasks.size() <= (owned ? 2 : 4) && tasks.keySet().stream().allMatch(t -> t.matches("task-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                && spec.get("parallelism").getAsInt() >= 1 && spec.get("parallelism").getAsInt() <= 2
                && spec.get("maxCalls").getAsInt() >= 1 && spec.get("maxCalls").getAsInt() <= tasks.size() * 12
                && Duration.between(Instant.parse(spec.get("issuedAt").getAsString()), Instant.parse(spec.get("expiresAt").getAsString())).equals(Duration.ofMinutes(20)), "Invalid parallel batch");
        return spec;
    }
    private static String hash(Path base, String path) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(base, path)); }
    private static JsonObject record(Path base, String path) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(base, path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L; final String status;
        Stop(String status) { super(status); this.status = status; }
    }

    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 3, "Usage: ParallelInvestigations register STATE PARALLELISM MAX_CALLS TASK TASK... | run STATE BATCH | revoke STATE BATCH");
        var scheduler = new ParallelInvestigations(Path.of(args[1]));
        if (args[0].equals("register") && args.length >= 6)
            System.out.println(scheduler.register(Arrays.asList(args).subList(4, args.length), Integer.parseInt(args[2]), Integer.parseInt(args[3])));
        else if (args[0].equals("revoke") && args.length == 3) { scheduler.revoke(args[2]); System.out.println("Batch revoked: " + args[2]); }
        else if (args[0].equals("run") && args.length == 3) {
            var result = scheduler.run(args[2]); System.out.println("Batch: " + args[2]); System.out.println("Status: " + result.get("status").getAsString());
            if (result.has("children")) for (var entry : result.getAsJsonObject("children").entrySet())
                System.out.println(entry.getKey() + ": " + entry.getValue().getAsJsonObject().get("status").getAsString());
            if (result.has("usage")) {
                var usage = result.getAsJsonObject("usage");
                System.out.println("Reserved calls: " + usage.get("reservedSlots") + "; completed responses: " + usage.get("completedResponses")
                        + "; tokens: " + usage.get("inputTokens") + " input / " + usage.get("outputTokens") + " output");
            }
            if (!result.get("status").getAsString().equals("COMPLETED")) throw new IOException("Batch remains active; resume the same batch");
        } else throw new IllegalArgumentException("Invalid batch action");
    }
}
