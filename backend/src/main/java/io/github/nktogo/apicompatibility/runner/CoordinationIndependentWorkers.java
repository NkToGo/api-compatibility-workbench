package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Two isolated adaptive contexts, with deterministic scheduling and aggregation only. */
public final class CoordinationIndependentWorkers {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    private static final class Worker {
        final CaseTask task;
        final List<JsonObject> pages = new ArrayList<>();
        String status = "PENDING", reason = "CALL_BUDGET_EXHAUSTED";
        Worker(CaseTask task) { this.task = task; }
    }
    public CoordinationIndependentWorkers(Path state) throws IOException { this(state, new CoordinationRuntime(state)); }
    CoordinationIndependentWorkers(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("coordination-independent-workers"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        var participants = List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID).stream()
                .map(id -> new CoordinationRuntime.Participant(id, Set.of(id), Set.of(CaseInvestigationDecision.protocol(id)))).toList();
        return new CoordinationRuntime.Policy(2, 100, new ContentDigest(RepairVerification.harness()),
                digest(CaseInvestigationDecision.INSTRUCTIONS + JSON.toJson(canonical(CaseInvestigationDecision.schema("customer-captured-v1")))
                        + JSON.toJson(canonical(CaseInvestigationDecision.schema("subscription-captured-v1")))),
                digest("captured-only-utf16-pages-4000-same-case-citations-complete-required-reads-v1"),
                digest("isolated-case-all-read-metadata-latest-six-contents-oldest-first-byte-trim-fair-rounds-one-call-per-active-worker-shared-pool-v1"),
                "complete-independent-dossiers-no-model-aggregation", participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.INDEPENDENT_WORKERS, "Independent workers require their own arm");
        var policy = policy(); String id = runtime.register(bundle, definition, slot, policy); Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", new Spec(1, id, bundle, definition, slot, policy, runtimeHash(id, "spec.json")));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete independent-worker result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed independent-worker implementation; use a new trial");
                var workers = workload(spec).cases().stream().sorted(Comparator.comparing(CaseTask::caseId)).map(Worker::new).toList();
                int scheduled = 0;
                try (var executor = Executors.newFixedThreadPool(2)) {
                    for (int round = 1; scheduled < spec.definition().limits().modelCalls(); round++) {
                        var active = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (active.isEmpty()) break;
                        int remaining = spec.definition().limits().modelCalls() - scheduled;
                        var selected = remaining == 1 && active.size() == 2 ? List.of(active.get((round - 1) % 2)) : active;
                        retain(run, "round-" + round + ".json", Map.of("round", round, "decisionsRemaining", remaining,
                                "cases", selected.stream().map(w -> w.task.caseId()).toList(), "specSha256", hash(run, "spec.json")));
                        int currentRound = round;
                        var futures = selected.stream().map(w -> executor.submit(() -> action(run, spec, w, currentRound, remaining))).toList();
                        var events = new ArrayList<JsonObject>(); Throwable failure = null;
                        // Drain both tasks before releasing ownership, including when one task failed locally.
                        for (var future : futures) {
                            try { events.add(future.get()); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for independent workers", e); }
                            catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                        }
                        if (failure instanceof IOException e) throw e;
                        if (failure instanceof RuntimeException e) throw e;
                        if (failure != null) throw new IOException("Independent worker failed locally", failure);
                        if (events.stream().anyMatch(e -> e.has("status") && e.get("status").getAsString().equals("ACTIVE"))) return json(Map.of("status", "ACTIVE", "runId", id));
                        for (int i = 0; i < selected.size(); i++) apply(run, selected.get(i), currentRound, events.get(i));
                        scheduled += (int) events.stream().filter(e -> e.get("callReserved").getAsBoolean()).count();
                    }
                }
                for (var worker : workers) if (worker.status.equals("PENDING")) worker.status = "FAILED";
                var status = workers.stream().allMatch(w -> w.status.equals("COMPLETED")) ? CoordinationTrial.Status.COMPLETED
                        : workers.stream().anyMatch(w -> w.status.equals("CANCELLED")) ? CoordinationTrial.Status.CANCELLED : CoordinationTrial.Status.FAILED;
                if (status != CoordinationTrial.Status.CANCELLED && runtime.snapshot(id).getAsJsonObject("usage").get("unresolvedUsageSlots").getAsLong() > 0)
                    status = CoordinationTrial.Status.UNKNOWN_REMOTE_OUTCOME;
                publish(run, "terminal.json", Map.of("status", status, "workers", workers.stream()
                        .map(w -> Map.of("caseId", w.task.caseId(), "status", w.status, "stopReason", w.reason)).toList(), "artifactSha256", artifacts(run)));
            }
            var terminal = record(run, "terminal.json"); var trace = runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString()));
            publish(run, "result.json", result(run, spec, trace)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
            return retained(id);
        }
    }
    private JsonObject action(Path run, Spec spec, Worker worker, int round, int remaining) throws IOException {
        String caseId = worker.task.caseId(), prefix = round + "-" + caseId, eventName = "event-" + prefix + ".json", seal = "event-" + prefix + "-receipt.json";
        if (exists(run, seal)) {
            var event = record(run, eventName);
            CaseInputs.require(record(run, seal).equals(eventReceipt(run, prefix)), "Changed retained worker event");
            CaseInputs.require(event.get("callReserved").getAsBoolean() == runtime.reserved(spec.runId(), caseId, "decision-" + round), "Changed retained worker reservation accounting");
            if (exists(run, "request-" + prefix + ".json")) retain(run, "request-" + prefix + ".json", request(spec, worker, remaining));
            return event;
        }
        JsonObject event;
        try {
            var request = request(spec, worker, remaining); retain(run, "request-" + prefix + ".json", request);
            var response = runtime.call(spec.runId(), caseId, "decision-" + round, request);
            String status = response.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("status", "ACTIVE"));
            if (!status.equals("COMPLETED")) event = stopped("MODEL_" + status);
            else {
                JsonObject decision = null;
                try { decision = CaseInvestigationDecision.parse(response.get("outputText").getAsString(), caseId); }
                catch (RuntimeException ignored) { /* Invalid model data never becomes a runnable action. */ }
                if (decision == null) event = stopped("INVALID_MODEL_DECISION");
                else if (decision.get("action").getAsString().equals("read_evidence")) {
                    try { event = json(Map.of("decision", decision, "page", page(spec, caseId, round, decision))); }
                    catch (IllegalArgumentException e) { event = stopped("INVALID_EVIDENCE_READ"); }
                } else if (!CoordinationSingleAgent.supported(decision, worker.pages)) event = stopped("UNSUPPORTED_DOSSIER");
                else { runtime.start(spec.runId()); event = json(Map.of("decision", decision)); }
            }
        } catch (CoordinationRuntime.Stop e) {
            if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE"));
            event = stopped(e.getMessage());
        }
        event.addProperty("round", round); event.addProperty("caseId", caseId);
        event.addProperty("callReserved", runtime.reserved(spec.runId(), caseId, "decision-" + round));
        retain(run, eventName, event); publish(run, seal, eventReceipt(run, prefix)); return event;
    }
    private static JsonObject stopped(String reason) { return json(Map.of("workerStatus", reason.equals("TRIAL_REVOKED") ? "CANCELLED" : "FAILED", "stopReason", reason)); }
    private static ModelCallBroker.Request request(Spec spec, Worker worker, int remaining) {
        String context = CoordinationSingleAgent.context(new Workload("isolated-case", List.of(worker.task)), worker.pages, remaining, spec.definition().limits().inputBytesPerCall());
        return new ModelCallBroker.Request(spec.definition().model(), context, spec.definition().limits().outputTokensPerCall(), CaseInvestigationDecision.protocol(worker.task.caseId()));
    }
    private JsonObject page(Spec spec, String caseId, int round, JsonObject decision) throws IOException {
        String alias = decision.get("inputId").getAsString(); var value = runtime.read(spec.runId(), caseId, "read-" + round, caseId, alias);
        String full = value.get("content").getAsString(); int offset = decision.get("offset").getAsInt(); CaseInputs.require(offset <= full.length(), "Page offset beyond captured input");
        int end = Math.min(full.length(), offset + decision.get("maxCharacters").getAsInt()); String content = full.substring(offset, end);
        return json(Map.of("pageId", "read-" + round, "caseId", caseId, "inputId", alias, "artifactSha256", value.get("sha256").getAsString(),
                "pageSha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)), "offset", offset, "nextOffset", end, "totalCharacters", full.length(), "content", content, "contentTrust", "untrusted-data"));
    }
    private static void apply(Path run, Worker worker, int round, JsonObject event) throws IOException {
        CaseInputs.require(event.get("round").getAsInt() == round && event.get("caseId").getAsString().equals(worker.task.caseId()), "Changed worker event scope");
        if (event.has("workerStatus")) { worker.status = event.get("workerStatus").getAsString(); worker.reason = event.get("stopReason").getAsString(); return; }
        var decision = CaseInvestigationDecision.parse(JSON.toJson(Map.of("decision", event.get("decision"))), worker.task.caseId());
        if (decision.get("action").getAsString().equals("read_evidence")) worker.pages.add(event.getAsJsonObject("page"));
        else {
            CaseInputs.require(CoordinationSingleAgent.supported(decision, worker.pages), "Changed supported worker dossier");
            var dossier = decision.getAsJsonArray("cases").get(0).getAsJsonObject().deepCopy();
            dossier.add("inputIdentity", JSON.toJsonTree(worker.task.inputIdentity())); dossier.addProperty("selectedDecision", "event-" + round + "-" + worker.task.caseId());
            dossier.addProperty("authority", "model-advice-not-semantic-acceptance"); retain(run, worker.task.caseId() + ".json", dossier);
            worker.status = "COMPLETED"; worker.reason = "NONE";
        }
    }
    private static JsonObject eventReceipt(Path run, String prefix) throws IOException {
        var hashes = new TreeMap<String, String>(); hashes.put("specSha256", hash(run, "spec.json"));
        hashes.put("eventSha256", hash(run, "event-" + prefix + ".json")); hashes.put("roundSha256", hash(run, "round-" + prefix.substring(0, prefix.indexOf('-')) + ".json"));
        if (exists(run, "request-" + prefix + ".json")) hashes.put("requestSha256", hash(run, "request-" + prefix + ".json")); return json(hashes);
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete independent-worker result");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived independent-worker record"); return result;
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.get("status").equals(trace.get("status"))
                && terminal.get("artifactSha256").equals(JSON.toJsonTree(artifacts(run))), "Changed selected independent-worker artifacts");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json")); var outputs = new ArrayList<CoordinationTrial.CaseOutput>();
        for (var task : workload(spec).cases()) {
            String name = task.caseId() + ".json";
            if (!exists(run, name)) outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.NOT_PROVIDED, null, null));
            else {
                var dossier = record(run, name); CaseInputs.require(dossier.get("caseId").getAsString().equals(task.caseId()) && dossier.get("inputIdentity").equals(JSON.toJsonTree(task.inputIdentity())), "Changed dossier input binding");
                outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.valueOf(dossier.get("response").getAsString()), new ContentDigest(hash(run, name)), null));
            }
        }
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(),
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")),
                JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), outputs);
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "workers", terminal.get("workers"), "trial", trial,
                "qualityReview", "NOT_PERFORMED", "authority", "independent-worker-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json")), "Changed independent-worker registration");
        runtime.snapshot(spec.runId()); var host = record(state.resolve("coordination-trials/" + spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.INDEPENDENT_WORKERS, "Changed independent-worker runtime binding"); return spec;
    }
    private static Workload workload(Spec spec) { return spec.definition().workloads().stream().filter(w -> w.workloadId().equals(spec.slot().workloadId())).findFirst().orElseThrow(); }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", CaseInputs.CASE_ID + ".json", SubscriptionPeriodCase.CASE_ID + ".json").contains(name)
                        || name.matches("round-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-stripe-(?:customer-sources-2020-08-27|subscription-periods-2025-07-30)(?:-receipt)?\\.json"), "Unexpected independent-worker artifact"); hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private String runtimeHash(String id, String name) throws IOException { return hash(state.resolve("coordination-trials/" + id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid independent-worker reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean exists(Path root, String name) { return Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, name)); }
    private static JsonObject record(Path root, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(k -> sorted.add(k, canonical(value.getAsJsonObject().get(k)))); return sorted; }
        if (value.isJsonArray()) { var array = new JsonArray(); value.getAsJsonArray().forEach(v -> array.add(canonical(v))); return array; } return value;
    }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static void retain(Path root, String name, Object value) throws IOException { if (exists(root, name)) CaseInputs.require(record(root, name).equals(json(value)), "Changed resumed independent-worker action"); else publish(root, name, value); }
    private static void publish(Path root, String name, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); CaseInputs.require(!exists(root, name) && bytes.length <= 1048576, "Existing or oversized independent-worker artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(Path state, String bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-adapter-check-placeholder-" + arm.name()));
        implementations.put(Arm.INDEPENDENT_WORKERS, CoordinationRuntime.implementationIdentity(policy()));
        var tasks = new CoordinationInputs(state).identities(bundle).entrySet().stream().map(e -> new CaseTask(e.getKey(), e.getValue(), ExpectedResponse.ANSWER, List.of("observed-regressions"))).toList();
        return new Definition("independent-workers-adapter-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-adapter-check"), implementations,
                List.of(new Workload("two-case", tasks)), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: CoordinationIndependentWorkers policy STATE | check STATE BUNDLE | register STATE BUNDLE PLAN WORKLOAD REPETITION | run/read STATE TRIAL");
        if (args[0].equals("policy") && args.length == 2) { System.out.println(JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        Path state = Path.of(args[1]); var adapter = new CoordinationIndependentWorkers(state); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 3, "Invalid adapter check arguments"); id = adapter.register(args[2], checkDefinition(state, args[2]), new Slot("two-case", 1, Arm.INDEPENDENT_WORKERS)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 6, "Invalid registration arguments"); Path planPath = Path.of(args[3]).toAbsolutePath();
                var tree = record(planPath.getParent(), planPath.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid comparison plan shape"); System.out.println(adapter.register(args[2], plan.definition(), new Slot(args[4], Integer.parseInt(args[5]), Arm.INDEPENDENT_WORKERS))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 3, "Invalid adapter action arguments"); id = args[2]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid independent-worker action");
        }
        if (result.has("status")) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Model calls: " + trial.getAsJsonObject("usage").get("reservedCalls").getAsLong() + "; new case executions: " + trial.getAsJsonObject("usage").get("caseExecutions").getAsLong());
        System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
