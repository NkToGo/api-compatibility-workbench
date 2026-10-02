package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Host-created consumer tasks with independent stores and sealed, narrow admission grants. */
public final class ConsumerTasks {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Set<String> TOOLS = Set.of("describe_case", "read_input", "run_case", "read_run");
    private static final Set<String> SUBSCRIPTION_INPUTS = Set.of("README.md", "integrity.json", "fixtures.json",
            "expectations.json", "baseline.txt", "candidate.txt");
    private final Path root;
    private final Clock clock;
    private final Backends backends;

    public ConsumerTasks(Path state) throws IOException { this(state, Clock.systemUTC(), new Backends() {}); }
    ConsumerTasks(Path state, Clock clock, Backends backends) throws IOException {
        this.root = state.toAbsolutePath().normalize().resolve("tasks");
        Files.createDirectories(root); directory(root);
        this.clock = clock; this.backends = backends;
    }

    public String registerCustomer(Path caseRoot, Path dependencies, String image, String model) throws IOException {
        validateModel(model);
        Path task = newTask(); Path state = task.resolve("state");
        String context = new InvestigationTools(state).register(caseRoot, dependencies, image);
        var artifacts = inventory(state, "investigations/" + context);
        return seal(task, context, model, "fixed-container", artifacts);
    }

    public String registerSubscription(Path evidenceState, String verificationId, String model) throws IOException {
        validateModel(model); valid(verificationId, "verification");
        Path source = evidenceState.toAbsolutePath().normalize().resolve("verifications").resolve(verificationId);
        // Only inert evidence is imported: no execution authorization, broker grant or active ownership marker.
        var captured = new TreeMap<String, byte[]>();
        for (String name : List.of("spec.json", "result.json", "observations.json")) captured.put(name, bytes(source, name));
        var spec = CaseInputs.object(captured.get("spec.json")); var result = CaseInputs.object(captured.get("result.json"));
        CaseInputs.require(verificationId.equals(spec.get("verificationId").getAsString())
                && verificationId.equals(result.get("verificationId").getAsString())
                && "subscription-period-v1".equals(spec.get("protocol").getAsString())
                && SubscriptionPeriodCase.CASE_ID.equals(spec.get("caseId").getAsString())
                && "COMPLETED".equals(result.get("status").getAsString())
                && spec.getAsJsonObject("inputs").keySet().equals(SUBSCRIPTION_INPUTS), "Unsupported retained subscription evidence");
        for (String name : SUBSCRIPTION_INPUTS) captured.put("inputs/" + name, bytes(source, "inputs/" + name));
        var jobs = result.getAsJsonObject("jobArtifacts");
        CaseInputs.require(!jobs.keySet().isEmpty() && jobs.size() <= 128, "Invalid retained job inventory");
        for (String name : jobs.keySet()) {
            CaseInputs.require(name.matches("(baseline|target|candidate)-[a-z0-9-]{1,100}\\.json"), "Unsupported retained job path");
            captured.put("jobs/" + name, bytes(source, "jobs/" + name));
        }
        CaseInputs.require(captured.values().stream().mapToLong(b -> b.length).sum() <= 16 * 1024 * 1024, "Retained evidence too large");
        CaseInputs.require(inventory(source, "inputs").keySet().equals(SUBSCRIPTION_INPUTS.stream()
                .map(n -> "inputs/" + n).collect(java.util.stream.Collectors.toSet()))
                && inventory(source, "jobs").keySet().equals(jobs.keySet().stream()
                .map(n -> "jobs/" + n).collect(java.util.stream.Collectors.toSet())), "Unexpected retained artifacts");
        RepairVerification.retained(source);
        Path task = newTask(); Path state = task.resolve("state");
        Path target = state.resolve("verifications").resolve(verificationId);
        for (var entry : captured.entrySet()) {
            Path path = target.resolve(entry.getKey()); Files.createDirectories(path.getParent());
            Files.write(path, entry.getValue(), StandardOpenOption.CREATE_NEW);
        }
        // Revalidate the captured copy, including hashes. Source-store edits cannot change this snapshot.
        String context = new SubscriptionInvestigationTools(state).register(verificationId);
        var artifacts = new TreeMap<>(inventory(state, "verifications/" + verificationId));
        artifacts.putAll(inventory(state, "subscription-investigations/" + context));
        return seal(task, context, model, "retained-only", artifacts);
    }

    private Path newTask() throws IOException {
        directory(root);
        Path task = Files.createDirectory(root.resolve("task-" + UUID.randomUUID()));
        Files.createDirectory(task.resolve("state")); return task;
    }
    private String seal(Path task, String context, String model, String mode, Map<String, String> artifacts) throws IOException {
        var description = ConsumerInvestigator.toolBackend(task.resolve("state")).invoke(context, "describe_case", new JsonObject());
        CaseInputs.require(description.get("ok").getAsBoolean(), "Invalid task context");
        String id = task.getFileName().toString(); Instant issued = clock.instant();
        var spec = new TreeMap<String, Object>(Map.of("formatVersion", 1, "taskId", id, "contextId", context,
                "model", model, "executionMode", mode, "case", description, "artifacts", artifacts,
                "maxDecisions", ConsumerInvestigator.MAX_DECISIONS));
        spec.put("issuedAt", issued.toString()); spec.put("expiresAt", issued.plus(Duration.ofMinutes(20)).toString());
        DurableExecution.publish(task.resolve("task.json"), spec);
        DurableExecution.publish(task.resolve("grant.json"), Map.of("formatVersion", 1, "taskId", id,
                "taskSha256", CaseInputs.hash(bytes(task, "task.json")), "permission", "investigate-registered-consumer",
                "model", model, "tools", new TreeSet<>(TOOLS), "expiresAt", spec.get("expiresAt")));
        return id;
    }

    /** A host entry point; neither task registration nor task selection is exposed as a model tool. */
    public JsonObject invoke(String id, String tool, JsonObject arguments) throws IOException {
        Path task = task(id); JsonObject spec = spec(task);
        try (var lease = LocalFileLease.tryAcquire(task.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("ok", false, "error", "TASK_ACTIVE"));
            return scopedTools(task, spec).invoke(spec.get("contextId").getAsString(), tool, arguments);
        }
        catch (AccessEnded e) { return json(Map.of("formatVersion", 1, "ok", false, "error", e.status)); }
    }

    public JsonObject run(String id) throws IOException {
        Path task = task(id);
        try (var lease = LocalFileLease.tryAcquire(task.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("taskId", id, "status", "ACTIVE"));
            var spec = spec(task);
            // An unrelated host entry point must not finalize or consume a batch-owned task.
            try { requireOwner(task); }
            catch (AccessEnded e) { return json(Map.of("taskId", id, "status", e.status)); }
            if (Files.exists(task.resolve("result.json"), LinkOption.NOFOLLOW_LINKS)) return retained(task, spec);
            try {
                requireAccess(task, spec);
                var investigator = new ConsumerInvestigator(task.resolve("state/agents"), scopedModels(task, spec),
                        scopedTools(task, spec), clock);
                String agent;
                if (Files.exists(task.resolve("agent.json"), LinkOption.NOFOLLOW_LINKS)) agent = agent(task, spec);
                else {
                    agent = investigator.create(spec.get("contextId").getAsString(), spec.get("model").getAsString());
                    DurableExecution.publish(task.resolve("agent.json"), Map.of("agentId", agent,
                            "sessionSha256", CaseInputs.hash(bytes(task, "state/agents/" + agent + "/session.json"))));
                }
                var result = investigator.run(agent);
                if (result.get("status").getAsString().equals("ACTIVE")) return json(Map.of("taskId", id, "status", "ACTIVE", "agentId", agent));
                requireAccess(task, spec);
                return finish(task, result.get("status").getAsString(), agent);
            } catch (AccessEnded e) { return finish(task, e.status, null); }
        }
    }

    public void revoke(String id) throws IOException {
        Path task = task(id); spec(task);
        try { Files.write(task.resolve("revoked"), new byte[0], StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ignored) { /* Revocation cannot be renewed. */ }
    }

    private ConsumerInvestigator.Tools scopedTools(Path task, JsonObject spec) throws IOException {
        var delegate = backends.tools(task.resolve("state"));
        return (context, tool, arguments) -> {
            requireAccess(task, spec);
            if (!context.equals(spec.get("contextId").getAsString()) || !TOOLS.contains(tool))
                return json(Map.of("formatVersion", 1, "ok", false, "error", "INVALID_REQUEST"));
            var response = delegate.invoke(context, tool, arguments);
            requireAccess(task, spec); return response;
        };
    }

    private ConsumerInvestigator.Models scopedModels(Path task, JsonObject spec) throws IOException {
        var delegate = backends.models(task.resolve("state"));
        return new ConsumerInvestigator.Models() {
            public String register(ModelCallBroker.Request request) throws IOException {
                requireAccess(task, spec);
                CaseInputs.require(spec.get("model").getAsString().equals(request.model())
                        && "investigation-v2".equals(request.protocol()) && request.maxOutputTokens() <= 2048
                        && spec.get("case").equals(CaseInputs.object(request.input().getBytes(StandardCharsets.UTF_8)).get("case")),
                        "Request outside task scope");
                String call = delegate.register(request); valid(call, "model");
                bytes(task, "state/model-calls/" + call + "/request.json"); return call;
            }
            public JsonObject execute(String id) throws IOException {
                requireAccess(task, spec); valid(id, "model");
                var request = CaseInputs.JSON.fromJson(CaseInputs.object(bytes(task, "state/model-calls/" + id + "/request.json")), ModelCallBroker.Request.class);
                CaseInputs.require(spec.get("model").getAsString().equals(request.model()) && "investigation-v2".equals(request.protocol())
                        && request.maxOutputTokens() <= 2048
                        && spec.get("case").equals(CaseInputs.object(request.input().getBytes(StandardCharsets.UTF_8)).get("case")), "Foreign model call");
                var response = delegate.execute(id); requireAccess(task, spec); return response;
            }
        };
    }

    private void requireAccess(Path task, JsonObject spec) {
        requireOwner(task);
        if (Files.exists(task.resolve("revoked"), LinkOption.NOFOLLOW_LINKS)) throw new AccessEnded("TASK_REVOKED");
        if (!clock.instant().isBefore(Instant.parse(spec.get("expiresAt").getAsString()))) throw new AccessEnded("TASK_EXPIRED");
        try {
            var grant = CaseInputs.object(bytes(task, "grant.json"));
            CaseInputs.require(grant.get("formatVersion").getAsInt() == 1 && grant.get("taskId").equals(spec.get("taskId"))
                    && grant.get("taskSha256").getAsString().equals(CaseInputs.hash(bytes(task, "task.json")))
                    && grant.get("permission").getAsString().equals("investigate-registered-consumer")
                    && grant.get("model").equals(spec.get("model")) && grant.get("expiresAt").equals(spec.get("expiresAt"))
                    && grant.get("tools").equals(CaseInputs.JSON.toJsonTree(new TreeSet<>(TOOLS)))
                    && !clock.instant().isBefore(Instant.parse(spec.get("issuedAt").getAsString())), "Task admission denied");
        } catch (IOException | RuntimeException e) { throw new AccessEnded("TASK_AUTHORIZATION_DENIED"); }
        try { verifyArtifacts(task, spec); }
        catch (IOException | RuntimeException e) { throw new AccessEnded("TASK_INPUT_IDENTITY_MISMATCH"); }
    }

    private void requireOwner(Path task) {
        if (!Files.exists(task.resolve("parallel-owner.json"), LinkOption.NOFOLLOW_LINKS)) return;
        try {
            var owner = CaseInputs.object(bytes(task, "parallel-owner.json"));
            CaseInputs.require(owner.get("taskId").getAsString().equals(task.getFileName().toString())
                    && owner.get("batchId").getAsString().equals(backends.batchId()), "Task belongs to a batch");
        } catch (IOException | RuntimeException e) { throw new AccessEnded("TASK_ASSIGNED_TO_BATCH"); }
    }

    // Used only by the trusted host scheduler while holding every selected task's lease.
    JsonObject claimable(String id) throws IOException {
        Path task = task(id); var spec = spec(task); requireAccess(task, spec);
        CaseInputs.require(!Files.exists(task.resolve("parallel-owner.json"), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(task.resolve("agent.json"), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(task.resolve("result.json"), LinkOption.NOFOLLOW_LINKS), "Task already used or assigned");
        String requests = spec.get("executionMode").getAsString().equals("retained-only")
                ? "subscription-investigations/" : "investigations/";
        Path directory = task.resolve("state/" + requests + spec.get("contextId").getAsString() + "/requests");
        if (Files.exists(directory)) try (var paths = Files.list(directory)) {
            CaseInputs.require(paths.findAny().isEmpty(), "Task already has tool requests");
        }
        return json(Map.of("taskSha256", CaseInputs.hash(bytes(task, "task.json")),
                "grantSha256", CaseInputs.hash(bytes(task, "grant.json"))));
    }

    private JsonObject spec(Path task) throws IOException {
        var spec = CaseInputs.object(bytes(task, "task.json"));
        String mode = spec.get("executionMode").getAsString();
        CaseInputs.require(spec.get("formatVersion").getAsInt() == 1 && spec.get("taskId").getAsString().equals(task.getFileName().toString())
                && spec.get("maxDecisions").getAsInt() == ConsumerInvestigator.MAX_DECISIONS
                && Set.of("fixed-container", "retained-only").contains(mode), "Invalid consumer task");
        valid(spec.get("contextId").getAsString(), mode.equals("retained-only") ? "subscription-investigation" : "investigation");
        validateModel(spec.get("model").getAsString());
        CaseInputs.require(Duration.between(Instant.parse(spec.get("issuedAt").getAsString()), Instant.parse(spec.get("expiresAt").getAsString()))
                .equals(Duration.ofMinutes(20)), "Invalid task lifetime");
        return spec;
    }
    private static void verifyArtifacts(Path task, JsonObject spec) throws IOException {
        var artifacts = spec.getAsJsonObject("artifacts");
        CaseInputs.require(!artifacts.keySet().isEmpty() && artifacts.size() <= 256, "Invalid task inventory");
        for (String name : artifacts.keySet())
            CaseInputs.require(CaseInputs.hash(bytes(task, "state/" + name)).equals(artifacts.get(name).getAsString()), "Changed task input");
    }
    private static String agent(Path task, JsonObject spec) throws IOException {
        var mapping = CaseInputs.object(bytes(task, "agent.json")); String id = mapping.get("agentId").getAsString(); valid(id, "agent");
        byte[] captured = bytes(task, "state/agents/" + id + "/session.json"); var session = CaseInputs.object(captured);
        CaseInputs.require(mapping.get("sessionSha256").getAsString().equals(CaseInputs.hash(captured))
                && session.get("contextId").equals(spec.get("contextId")) && session.get("case").equals(spec.get("case"))
                && session.get("model").equals(spec.get("model")) && session.get("agentId").getAsString().equals(id), "Changed task agent");
        return id;
    }
    private JsonObject finish(Path task, String status, String agentId) throws IOException {
        var result = json(Map.of("formatVersion", 1, "taskId", task.getFileName().toString(), "status", status,
                "taskSha256", CaseInputs.hash(bytes(task, "task.json")), "authority", "task-status-not-execution-evidence",
                "finishedAt", clock.instant().toString()));
        if (agentId != null) {
            result.addProperty("agentId", agentId);
            result.addProperty("agentResultSha256", CaseInputs.hash(bytes(task, "state/agents/" + agentId + "/result.json")));
        }
        DurableExecution.publish(task.resolve("result.json"), result); return result;
    }
    private static JsonObject retained(Path task, JsonObject spec) throws IOException {
        var result = CaseInputs.object(bytes(task, "result.json"));
        CaseInputs.require(result.get("taskSha256").getAsString().equals(CaseInputs.hash(bytes(task, "task.json")))
                && result.get("taskId").equals(spec.get("taskId")), "Changed completed task");
        verifyArtifacts(task, spec);
        if (result.has("agentId")) {
            String id = agent(task, spec);
            CaseInputs.require(result.get("agentId").getAsString().equals(id)
                    && result.get("agentResultSha256").getAsString().equals(CaseInputs.hash(bytes(task, "state/agents/" + id + "/result.json"))), "Changed task result");
        }
        return result;
    }
    Path task(String id) { valid(id, "task"); Path task = root.resolve(id); directory(root); directory(task); return task; }
    private static void valid(String id, String prefix) { CaseInputs.require(id != null && id.matches(prefix + "-" + UUID_PATTERN), "Invalid task reference"); }
    private static void validateModel(String model) { new ModelCallBroker.Request(model, "validate", 2048, "investigation-v2"); }
    private static Map<String, String> inventory(Path state, String relative) throws IOException {
        Path start = state.resolve(relative); var result = new TreeMap<String, String>();
        try (var paths = Files.walk(start)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { directory(path); continue; }
                String name = state.relativize(path).toString().replace('\\', '/'); result.put(name, CaseInputs.hash(bytes(state, name)));
            }
        }
        return result;
    }
    static byte[] bytes(Path base, String relative) throws IOException {
        CaseInputs.require(relative.matches("[a-zA-Z0-9_./-]+") && !relative.startsWith("/")
                && Arrays.stream(relative.split("/", -1)).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")), "Invalid task artifact path");
        Path path = base.resolve(relative).normalize();
        Path absolute = path.toAbsolutePath();
        for (Path parent = absolute.getParent(); parent != null; parent = parent.getParent()) directory(parent);
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing or linked task artifact");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(1048577); CaseInputs.require(data.length <= 1048576, "Task artifact too large"); return data;
        }
    }
    static void directory(Path path) {
        CaseInputs.require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path), "Missing or linked task directory");
        // Both forms canonicalize Windows short names; only the followed form resolves junctions.
        try { CaseInputs.require(path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)), "Redirected task directory"); }
        catch (IOException e) { throw new IllegalArgumentException("Unavailable task directory"); }
    }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static final class AccessEnded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String status;
        AccessEnded(String status) { this.status = status; }
    }
    interface Backends {
        default String batchId() { return null; }
        default ConsumerInvestigator.Models models(Path state) throws IOException { return ConsumerInvestigator.modelBackend(state); }
        default ConsumerInvestigator.Tools tools(Path state) throws IOException { return ConsumerInvestigator.toolBackend(state); }
    }

    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 3, "Usage: ConsumerTasks customer STATE CASE DEPENDENCIES IMAGE MODEL | subscription STATE SOURCE_STATE VERIFICATION MODEL | run STATE TASK | revoke STATE TASK");
        var tasks = new ConsumerTasks(Path.of(args[1]));
        if (args[0].equals("customer") && args.length == 6)
            System.out.println(tasks.registerCustomer(Path.of(args[2]), Path.of(args[3]), args[4], args[5]));
        else if (args[0].equals("subscription") && args.length == 5)
            System.out.println(tasks.registerSubscription(Path.of(args[2]), args[3], args[4]));
        else if (args[0].equals("revoke") && args.length == 3) { tasks.revoke(args[2]); System.out.println("Task revoked: " + args[2]); }
        else if (args[0].equals("run") && args.length == 3) {
            var result = tasks.run(args[2]); System.out.println("Task: " + args[2]); System.out.println("Status: " + result.get("status").getAsString());
            if (!Set.of("INVESTIGATED", "ABSTAINED").contains(result.get("status").getAsString())) throw new IOException("Task did not finish; inspect its retained records");
        } else throw new IllegalArgumentException("Invalid consumer task action");
    }
}
