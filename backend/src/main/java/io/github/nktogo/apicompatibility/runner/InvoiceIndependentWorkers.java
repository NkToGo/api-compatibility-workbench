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

/** Independent invoice-role contexts; deterministic scheduling and verbatim aggregation only. */
public final class InvoiceIndependentWorkers {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID;
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    private static final class Worker {
        final String id; final List<JsonObject> pages = new ArrayList<>(); JsonObject metadata;
        String status = "PENDING", reason = "CALL_BUDGET_EXHAUSTED";
        Worker(String id) { this.id = id; }
    }
    public InvoiceIndependentWorkers(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, CoordinationRuntime.forInvoiceInvestigation(state, inputs, expected));
    }
    InvoiceIndependentWorkers(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("invoice-independent-workers"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        var participants = InvoiceWorkerDecision.WORKERS.stream().map(id -> new CoordinationRuntime.Participant(id, Set.of(CASE), Set.of(InvoiceWorkerDecision.protocol(id)))).toList();
        return new CoordinationRuntime.Policy(2, 100, new ContentDigest(RepairVerification.harness()),
                digest(InvoiceWorkerDecision.instructions(InvoiceWorkerDecision.RECONCILIATION) + InvoiceWorkerDecision.instructions(InvoiceWorkerDecision.WEBHOOK)
                        + JSON.toJson(canonical(InvoiceInvestigationDecision.schema()))), digest(ComparisonEvidence.INVOICE_TOOLS),
                digest("invoice-isolated-role-context-all-read-metadata-latest-six-contents-utf8-trim-fair-rounds-shared-pool-v1"),
                "verbatim-independent-dossiers-conservative-joint-response", participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.INDEPENDENT_WORKERS, "Invoice workers require their own arm");
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
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete invoice worker result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed invoice worker implementation; use a new trial");
                var workers = InvoiceWorkerDecision.WORKERS.stream().map(Worker::new).toList(); int scheduled = 0;
                try (var executor = Executors.newFixedThreadPool(2)) {
                    for (int round = 1; scheduled < spec.definition().limits().modelCalls(); round++) {
                        var active = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (active.isEmpty()) break;
                        int remaining = spec.definition().limits().modelCalls() - scheduled;
                        var selected = remaining == 1 && active.size() == 2 ? List.of(active.get((round - 1) % 2)) : active;
                        retain(run, "round-" + round + ".json", Map.of("round", round, "decisionsRemaining", remaining,
                                "workers", selected.stream().map(w -> w.id).toList(), "specSha256", hash(run, "spec.json")));
                        int currentRound = round;
                        var futures = selected.stream().map(w -> executor.submit(() -> action(run, spec, w, currentRound, remaining))).toList();
                        var events = new ArrayList<JsonObject>(); Throwable failure = null;
                        // Drain both tasks before releasing ownership, including after one local task fails.
                        for (var future : futures) {
                            try { events.add(future.get()); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for invoice workers", e); }
                            catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                        }
                        if (failure instanceof IOException e) throw e;
                        if (failure instanceof RuntimeException e) throw e;
                        if (failure != null) throw new IOException("Invoice worker failed locally", failure);
                        if (events.stream().anyMatch(e -> e.has("status") && e.get("status").getAsString().equals("ACTIVE"))) return json(Map.of("status", "ACTIVE", "runId", id));
                        for (int i = 0; i < selected.size(); i++) apply(run, spec, selected.get(i), currentRound, events.get(i));
                        scheduled += (int)events.stream().filter(e -> e.get("callReserved").getAsBoolean()).count();
                    }
                }
                for (var worker : workers) if (worker.status.equals("PENDING")) worker.status = "FAILED";
                var status = workers.stream().allMatch(w -> w.status.equals("COMPLETED")) ? CoordinationTrial.Status.COMPLETED
                        : workers.stream().anyMatch(w -> w.status.equals("CANCELLED")) ? CoordinationTrial.Status.CANCELLED : CoordinationTrial.Status.FAILED;
                if (status != CoordinationTrial.Status.CANCELLED && runtime.snapshot(id).getAsJsonObject("usage").get("unresolvedUsageSlots").getAsLong() > 0)
                    status = CoordinationTrial.Status.UNKNOWN_REMOTE_OUTCOME;
                var dispositions = JSON.toJsonTree(workers.stream().map(w -> Map.of("workerId", w.id, "status", w.status, "stopReason", w.reason)).toList()).getAsJsonArray();
                retain(run, "dossier.json", aggregate(run, spec, dispositions));
                publish(run, "terminal.json", Map.of("status", status, "workers", dispositions, "artifactSha256", artifacts(run)));
            }
            var terminal = record(run, "terminal.json"); var trace = runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString()));
            publish(run, "result.json", result(run, spec, trace)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private JsonObject action(Path run, Spec spec, Worker worker, int round, int remaining) throws IOException {
        String prefix = round + "-" + worker.id, eventName = "event-" + prefix + ".json", seal = "event-" + prefix + "-receipt.json", metadataName = "metadata-" + worker.id + ".json";
        if (exists(run, metadataName)) worker.metadata = record(run, metadataName);
        if (exists(run, seal)) {
            var event = record(run, eventName);
            CaseInputs.require(record(run, seal).equals(eventReceipt(run, prefix, worker.id)), "Changed retained invoice worker event");
            CaseInputs.require(event.get("callReserved").getAsBoolean() == runtime.reserved(spec.runId(), worker.id, "decision-" + round), "Changed invoice worker reservation accounting");
            if (exists(run, "request-" + prefix + ".json")) retain(run, "request-" + prefix + ".json", request(spec, worker, remaining)); return event;
        }
        JsonObject event;
        try {
            if (worker.metadata == null) {
                worker.metadata = runtime.describe(spec.runId(), worker.id, "metadata", CASE).getAsJsonObject("page"); retain(run, metadataName, worker.metadata);
            }
            var request = request(spec, worker, remaining); retain(run, "request-" + prefix + ".json", request);
            var response = runtime.call(spec.runId(), worker.id, "decision-" + round, request); String status = response.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("status", "ACTIVE"));
            if (!status.equals("COMPLETED")) event = stopped("MODEL_" + status);
            else {
                JsonObject decision = null;
                try { decision = InvoiceInvestigationDecision.parse(response.get("outputText").getAsString()); }
                catch (RuntimeException ignored) { /* Untrusted decisions cannot expand host capabilities. */ }
                if (decision == null) event = stopped("INVALID_MODEL_DECISION");
                else if (!decision.get("action").getAsString().equals("finish")) {
                    try { event = json(Map.of("decision", decision, "page", page(spec, worker.id, round, decision))); }
                    catch (IllegalArgumentException e) { event = stopped("INVALID_EVIDENCE_READ"); }
                } else if (!InvoiceWorkerDecision.supported(worker.id, decision, worker.pages, worker.metadata)) event = stopped("UNSUPPORTED_DOSSIER");
                else { runtime.start(spec.runId()); event = json(Map.of("decision", decision)); }
            }
        } catch (CoordinationRuntime.Stop e) {
            if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE")); event = stopped(e.getMessage());
        }
        event.addProperty("round", round); event.addProperty("workerId", worker.id);
        event.addProperty("callReserved", runtime.reserved(spec.runId(), worker.id, "decision-" + round));
        retain(run, eventName, event); publish(run, seal, eventReceipt(run, prefix, worker.id)); return event;
    }
    private static JsonObject stopped(String reason) { return json(Map.of("workerStatus", reason.equals("TRIAL_REVOKED") ? "CANCELLED" : "FAILED", "stopReason", reason)); }
    private static ModelCallBroker.Request request(Spec spec, Worker worker, int remaining) {
        CaseInputs.require(worker.metadata != null, "Missing invoice worker metadata"); var description = worker.metadata.deepCopy();
        description.addProperty("workerId", worker.id); description.addProperty("workerRole", InvoiceWorkerDecision.role(worker.id));
        return new ModelCallBroker.Request(spec.definition().model(), InvoiceSingleAgent.context(description, worker.pages, remaining, spec.definition().limits().inputBytesPerCall()),
                spec.definition().limits().outputTokensPerCall(), InvoiceWorkerDecision.protocol(worker.id));
    }
    private JsonObject page(Spec spec, String worker, int round, JsonObject decision) throws IOException {
        String kind = decision.get("action").getAsString(), operation = "read-" + round;
        var value = kind.equals("catalog") ? runtime.catalog(spec.runId(), worker, operation, CASE, decision.get("offset").getAsInt(), decision.get("limit").getAsInt())
                : runtime.readPage(spec.runId(), worker, operation, CASE, decision.get("alias").getAsString(), decision.get("offset").getAsInt(), decision.get("maxCharacters").getAsInt());
        var page = value.getAsJsonObject("page").deepCopy(); page.addProperty("pageId", operation); page.addProperty("kind", kind); page.addProperty("contentTrust", "untrusted-data"); return page;
    }
    private static void apply(Path run, Spec spec, Worker worker, int round, JsonObject event) throws IOException {
        CaseInputs.require(event.get("round").getAsInt() == round && event.get("workerId").getAsString().equals(worker.id), "Changed invoice worker event scope");
        if (event.has("workerStatus")) { worker.status = event.get("workerStatus").getAsString(); worker.reason = event.get("stopReason").getAsString(); return; }
        var decision = InvoiceInvestigationDecision.parse(JSON.toJson(Map.of("decision", event.get("decision"))));
        if (!decision.get("action").getAsString().equals("finish")) worker.pages.add(event.getAsJsonObject("page"));
        else {
            CaseInputs.require(InvoiceWorkerDecision.supported(worker.id, decision, worker.pages, worker.metadata), "Changed supported invoice worker dossier");
            var dossier = decision.deepCopy(); dossier.remove("action"); dossier.addProperty("caseId", CASE); dossier.addProperty("bundleIdentity", spec.bundleId());
            dossier.addProperty("workerId", worker.id); dossier.addProperty("workerRole", InvoiceWorkerDecision.role(worker.id)); dossier.addProperty("selectedDecision", "event-" + round + "-" + worker.id);
            dossier.addProperty("sourceExecutionCoverageSatisfied", worker.metadata.get("sourceExecutionCoverageSatisfied").getAsBoolean());
            dossier.addProperty("authority", "model-advice-not-semantic-acceptance"); retain(run, worker.id + ".json", dossier); worker.status = "COMPLETED"; worker.reason = "NONE";
        }
    }
    private static JsonObject eventReceipt(Path run, String prefix, String worker) throws IOException {
        var hashes = new TreeMap<String, String>(); hashes.put("specSha256", hash(run, "spec.json")); hashes.put("eventSha256", hash(run, "event-" + prefix + ".json"));
        hashes.put("roundSha256", hash(run, "round-" + prefix.substring(0, prefix.indexOf('-')) + ".json"));
        for (String name : List.of("request-" + prefix + ".json", "metadata-" + worker + ".json")) if (exists(run, name)) hashes.put(name, hash(run, name)); return json(hashes);
    }
    private static JsonObject aggregate(Path run, Spec spec, JsonArray dispositions) throws IOException {
        CaseInputs.require(dispositions.size() == 2, "Missing invoice worker disposition"); var rows = new JsonArray(); boolean complete = true, answered = true;
        for (int i = 0; i < 2; i++) {
            String worker = InvoiceWorkerDecision.WORKERS.get(i); var disposition = dispositions.get(i).getAsJsonObject();
            CaseInputs.require(worker.equals(disposition.get("workerId").getAsString()), "Changed invoice worker membership"); String name = worker + ".json";
            boolean done = disposition.get("status").getAsString().equals("COMPLETED"); CaseInputs.require(done == exists(run, name), "Changed completed invoice worker output");
            var row = disposition.deepCopy(); row.addProperty("workerRole", InvoiceWorkerDecision.role(worker));
            if (done) {
                var dossier = record(run, name); CaseInputs.require(dossier.get("workerId").getAsString().equals(worker) && dossier.get("caseId").getAsString().equals(CASE)
                        && dossier.get("bundleIdentity").getAsString().equals(spec.bundleId()), "Changed invoice worker dossier binding");
                row.add("dossier", dossier); row.addProperty("dossierSha256", hash(run, name)); answered &= dossier.get("response").getAsString().equals("ANSWERED");
            } else { row.add("dossier", JsonNull.INSTANCE); row.add("dossierSha256", JsonNull.INSTANCE); complete = false; }
            rows.add(row);
        }
        return json(Map.of("formatVersion", 1, "caseId", CASE, "bundleIdentity", spec.bundleId(), "response", !complete ? "NOT_PROVIDED" : answered ? "ANSWERED" : "ABSTAINED",
                "workers", rows, "aggregation", "verbatim-no-model-summary-or-conflict-resolution", "qualityReview", "NOT_PERFORMED"));
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete invoice worker result");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived invoice worker record"); return result;
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.get("status").equals(trace.get("status"))
                && terminal.get("artifactSha256").equals(JSON.toJsonTree(artifacts(run))), "Changed selected invoice worker artifacts");
        var dossier = record(run, "dossier.json"); CaseInputs.require(dossier.equals(aggregate(run, spec, terminal.getAsJsonArray("workers"))), "Changed joint invoice dossier");
        var response = CoordinationTrial.Response.valueOf(dossier.get("response").getAsString());
        var output = new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), response, response == CoordinationTrial.Response.NOT_PROVIDED ? null : new ContentDigest(hash(run, "dossier.json")), null);
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json"));
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(),
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")),
                JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), List.of(output));
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "workers", terminal.get("workers"), "trial", trial,
                "qualityReview", "NOT_PERFORMED", "authority", "independent-invoice-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json")), "Changed invoice worker registration");
        runtime.snapshot(spec.runId()); var host = record(state.resolve("invoice-comparison-trials/" + spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.INDEPENDENT_WORKERS, "Changed invoice worker runtime binding"); return spec;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", "dossier.json", "invoice-webhook.json", "invoice-reconciliation.json", "metadata-invoice-webhook.json", "metadata-invoice-reconciliation.json").contains(name)
                        || name.matches("round-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-invoice-(?:webhook|reconciliation)(?:-receipt)?\\.json"), "Unexpected invoice worker artifact"); hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private String runtimeHash(String id, String name) throws IOException { return hash(state.resolve("invoice-comparison-trials/" + id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice worker trial reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean exists(Path root, String name) { return Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, name)); }
    private static JsonObject record(Path root, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key)))); return sorted; }
        if (value.isJsonArray()) { var array = new JsonArray(); value.getAsJsonArray().forEach(v -> array.add(canonical(v))); return array; } return value;
    }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static void retain(Path root, String name, Object value) throws IOException { if (exists(root, name)) CaseInputs.require(record(root, name).equals(json(value)), "Changed resumed invoice worker action"); else publish(root, name, value); }
    private static void publish(Path root, String name, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); CaseInputs.require(!exists(root, name) && bytes.length <= 1048576, "Existing or oversized invoice worker artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-invoice-adapter-check-" + arm.name()));
        implementations.put(Arm.INDEPENDENT_WORKERS, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-independent-workers-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-invoice-adapter-check"), implementations,
                List.of(new Workload("invoice-joint", List.of(new CaseTask(CASE, bundle, ExpectedResponse.ANSWER, List.of("joint-invoice-evidence"))))), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { System.out.println(JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        CaseInputs.require(args.length >= 4, "Invoice input directory and independent expected identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceIndependentWorkers(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid invoice worker check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.INDEPENDENT_WORKERS)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 7, "Invalid invoice worker registration arguments"); Path planPath = Path.of(args[4]).toAbsolutePath();
                var tree = record(planPath.getParent(), planPath.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid invoice comparison plan shape");
                System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.INDEPENDENT_WORKERS))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid invoice worker trial arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid invoice worker action");
        }
        if (result.has("status")) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Usage: " + JSON.toJson(trial.get("usage"))); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
