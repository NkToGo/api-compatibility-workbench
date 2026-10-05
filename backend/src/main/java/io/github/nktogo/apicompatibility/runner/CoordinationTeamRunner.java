package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Durable original investigations and review; never exports a completed team comparison. */
public final class CoordinationTeamRunner {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String COORDINATOR = "coordinator";
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    private static final class Worker {
        final CaseTask task;
        final List<JsonObject> pages = new ArrayList<>();
        String status = "PENDING", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        JsonObject finish;
        Worker(CaseTask task) { this.task = task; }
        CapturedTeamReview.WorkerResult result() {
            return new CapturedTeamReview.WorkerResult(task, CapturedTeamReview.WorkerStatus.valueOf(status), reason, finish, pages);
        }
    }
    public CoordinationTeamRunner(Path state) throws IOException { this(state, new CoordinationRuntime(state)); }
    CoordinationTeamRunner(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("coordination-team-runs"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        var participants = new ArrayList<CoordinationRuntime.Participant>();
        for (String id : List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID))
            participants.add(new CoordinationRuntime.Participant(id, Set.of(id), Set.of(CaseInvestigationDecision.protocol(id))));
        participants.add(new CoordinationRuntime.Participant(COORDINATOR, Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID), Set.of(CapturedTeamDecision.PROTOCOL)));
        return new CoordinationRuntime.Policy(2, 100, new ContentDigest(RepairVerification.harness()),
                digest(CaseInvestigationDecision.INSTRUCTIONS + encoded(CaseInvestigationDecision.schema("customer-captured-v1"))
                        + encoded(CaseInvestigationDecision.schema("subscription-captured-v1")) + CapturedTeamDecision.INSTRUCTIONS + encoded(CapturedTeamDecision.schema())),
                digest("captured-only-utf16-pages-4000-case-scoped-originals-two-case-review-no-execution-v1"),
                digest("isolated-originals-fair-rounds-one-per-active-worker-sealed-stage-barrier-v1:" + CapturedTeamReview.CONTEXT
                        + ":" + CoordinationTeamBudget.POLICY + ":" + CapturedTeamReview.SELECTION),
                "originals-and-review-only-no-team-comparison-export", participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.COORDINATED_TEAM, "Team runner requires the coordinated arm");
        CoordinationTeamBudget.forCeiling(definition.limits().modelCalls());
        var policy = policy(); String id = runtime.register(bundle, definition, slot, policy); Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", new Spec(1, id, bundle, definition, slot, policy, hash(parent(id), "spec.json")));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete team-stage result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            CaseInputs.require(spec.policy().equals(policy()), "Changed team implementation; use a new run");
            var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls());
            var workers = workload(spec).cases().stream().sorted(Comparator.comparing(CaseTask::caseId)).map(Worker::new).toList();
            int originalCalls = originals(run, spec, allocation, workers);
            if (originalCalls < 0) return json(Map.of("status", "ACTIVE", "runId", id));
            var checkpoint = json(Map.of("workers", workers.stream().map(Worker::result).toList(), "reservedCalls", originalCalls, "allocation", allocation));
            retain(run, "originals.json", checkpoint);
            retain(run, "originals-receipt.json", Map.of("originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")));
            var review = new CapturedTeamReview(workers.stream().map(Worker::result).toList());
            if (!review(run, spec, allocation, originalCalls, review)) return json(Map.of("status", "ACTIVE", "runId", id));
            // Freeze accounting at this stage boundary. Do not finish or renew the whole trial.
            if (!exists(run, "stage-trace.json")) {
                var trace = runtime.snapshot(id); var outcome = record(run, "review.json");
                CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == originalCalls + outcome.get("reservedCalls").getAsInt(), "Unexpected team-stage reservations");
                publish(run, "stage-trace.json", trace);
            }
            if (!exists(run, "stage-trace-receipt.json")) {
                var frozen = record(run, "stage-trace.json"); var current = runtime.snapshot(id);
                var a = frozen.deepCopy(); var b = current.deepCopy(); a.remove("elapsedMillis"); b.remove("elapsedMillis");
                CaseInputs.require(a.equals(b) && frozen.get("elapsedMillis").getAsLong() <= current.get("elapsedMillis").getAsLong(), "Changed incomplete stage trace");
                publish(run, "stage-trace-receipt.json", Map.of("traceSha256", hash(run, "stage-trace.json")));
            }
            publish(run, "result.json", result(run, spec)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private int originals(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, List<Worker> workers) throws IOException {
        int used = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 1; used < allocation.investigation(); round++) {
                var active = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (active.isEmpty()) break;
                int remaining = allocation.remaining(CoordinationTeamBudget.Stage.INVESTIGATION, new CoordinationTeamBudget.Reservations(used, 0, 0, 0));
                var selected = remaining == 1 && active.size() == 2 ? List.of(active.get((round - 1) % 2)) : active;
                String roundFile = "round-" + round + ".json";
                retain(run, roundFile, Map.of("round", round, "decisionsRemaining", remaining, "cases", selected.stream().map(w -> w.task.caseId()).toList(), "specSha256", hash(run, "spec.json")));
                int turn = round;
                var futures = selected.stream().map(w -> executor.submit(() -> action(run, spec, w.task.caseId(), turn, roundFile,
                        () -> new ModelCallBroker.Request(spec.definition().model(), CoordinationSingleAgent.context(new Workload("isolated-case", List.of(w.task)), w.pages, remaining,
                                spec.definition().limits().inputBytesPerCall()), spec.definition().limits().outputTokensPerCall(), CaseInvestigationDecision.protocol(w.task.caseId())), w.pages, null))).toList();
                var events = new ArrayList<JsonObject>(); Throwable failure = null;
                for (var future : futures) {
                    try { events.add(future.get()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for team workers", e); }
                    catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                }
                if (failure instanceof IOException e) throw e;
                if (failure instanceof RuntimeException e) throw e;
                if (failure != null) throw new IOException("Team worker failed locally", failure);
                if (events.stream().anyMatch(CoordinationTeamRunner::active)) return -1;
                for (int i = 0; i < selected.size(); i++) {
                    var worker = selected.get(i); var event = events.get(i);
                    if (event.has("stopReason")) { worker.status = event.get("status").getAsString(); worker.reason = event.get("stopReason").getAsString(); }
                    else if (event.has("page")) worker.pages.add(event.getAsJsonObject("page"));
                    else { worker.finish = event.getAsJsonObject("decision"); worker.status = "COMPLETED"; worker.reason = "NONE"; }
                }
                used += (int) events.stream().filter(e -> e.get("callReserved").getAsBoolean()).count();
            }
        }
        for (var worker : workers) if (worker.status.equals("PENDING")) worker.status = "FAILED";
        return used;
    }
    private boolean review(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, int originalCalls, CapturedTeamReview review) throws IOException {
        var pages = new ArrayList<JsonObject>(); int used = 0; JsonObject selection = null;
        String status = "FAILED", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        for (int turn = 1; ; turn++) {
            int remaining = allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, new CoordinationTeamBudget.Reservations(originalCalls, used, 0, 0)); if (remaining == 0) break;
            String turnFile = "review-turn-" + turn + ".json";
            retain(run, turnFile, Map.of("turn", turn, "decisionsRemaining", remaining, "originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")));
            var event = action(run, spec, COORDINATOR, turn, turnFile,
                    () -> new ModelCallBroker.Request(spec.definition().model(), review.context(pages, remaining, spec.definition().limits().inputBytesPerCall()),
                            spec.definition().limits().outputTokensPerCall(), CapturedTeamDecision.PROTOCOL), pages, review);
            if (active(event)) return false;
            if (event.get("callReserved").getAsBoolean()) used++;
            if (event.has("stopReason")) { status = event.get("status").getAsString(); reason = event.get("stopReason").getAsString(); break; }
            if (event.has("page")) pages.add(event.getAsJsonObject("page"));
            else { selection = review.select(event.getAsJsonObject("decision"), pages); status = "COMPLETED"; reason = "NONE"; break; }
        }
        var outcome = json(Map.of("status", status, "stopReason", reason, "reservedCalls", used, "pages", pages));
        outcome.add("selection", selection == null ? JsonNull.INSTANCE : selection); retain(run, "review.json", outcome); return true;
    }
    private JsonObject action(Path run, Spec spec, String participant, int turn, String stepFile, java.util.function.Supplier<ModelCallBroker.Request> request,
                              List<JsonObject> pages, CapturedTeamReview review) throws IOException {
        String prefix = turn + "-" + participant, eventFile = "event-" + prefix + ".json", seal = "event-" + prefix + "-receipt.json";
        if (exists(run, seal)) {
            var event = record(run, eventFile); CaseInputs.require(record(run, seal).equals(eventReceipt(run, prefix, stepFile)), "Changed retained team event");
            CaseInputs.require(event.get("turn").getAsInt() == turn && event.get("participant").getAsString().equals(participant)
                    && event.get("callReserved").getAsBoolean() == runtime.reserved(spec.runId(), participant, "decision-" + turn), "Changed retained team operation");
            if (exists(run, "request-" + prefix + ".json")) retain(run, "request-" + prefix + ".json", request.get());
            if (event.has("page")) verifyPage(spec, participant, turn, event.getAsJsonObject("decision"), event.getAsJsonObject("page"));
            if (event.has("decision")) validateDecision(participant, event.getAsJsonObject("decision"), pages, review);
            return event;
        }
        JsonObject event;
        try {
            var payload = request.get(); retain(run, "request-" + prefix + ".json", payload);
            var response = runtime.call(spec.runId(), participant, "decision-" + turn, payload); String status = response.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("status", "ACTIVE"));
            if (!status.equals("COMPLETED")) event = stopped("MODEL_" + status);
            else {
                JsonObject decision = null;
                try { decision = participant.equals(COORDINATOR) ? CapturedTeamDecision.parse(response.get("outputText").getAsString())
                        : CaseInvestigationDecision.parse(response.get("outputText").getAsString(), participant); }
                catch (RuntimeException ignored) { /* Untrusted decisions cannot change the runtime. */ }
                if (decision == null) event = stopped("INVALID_MODEL_DECISION");
                else {
                    try {
                        validateDecision(participant, decision, pages, review);
                        if (decision.get("action").getAsString().equals("read_evidence")) {
                            String caseId = decision.get("caseId").getAsString(), alias = decision.get("inputId").getAsString();
                            var read = runtime.read(spec.runId(), participant, "read-" + turn, caseId, alias);
                            event = json(Map.of("decision", decision, "page", page(read, turn, decision)));
                        } else { runtime.start(spec.runId()); event = json(Map.of("decision", decision)); }
                    } catch (IllegalArgumentException e) { event = stopped("UNSUPPORTED_MODEL_DECISION"); }
                }
            }
        } catch (CoordinationRuntime.Stop e) {
            if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE")); event = stopped(e.getMessage());
        }
        event.addProperty("turn", turn); event.addProperty("participant", participant);
        event.addProperty("callReserved", runtime.reserved(spec.runId(), participant, "decision-" + turn));
        retain(run, eventFile, event); publish(run, seal, eventReceipt(run, prefix, stepFile)); return event;
    }
    private static void validateDecision(String participant, JsonObject decision, List<JsonObject> pages, CapturedTeamReview review) {
        var parsed = participant.equals(COORDINATOR) ? CapturedTeamDecision.parse(JSON.toJson(Map.of("decision", decision)))
                : CaseInvestigationDecision.parse(JSON.toJson(Map.of("decision", decision)), participant);
        if (parsed.get("action").getAsString().equals("finish")) {
            if (review != null) review.select(parsed, pages);
            else CaseInputs.require(CoordinationSingleAgent.supported(parsed, pages), "Unsupported original dossier");
        }
    }
    private static JsonObject stopped(String reason) {
        String status = reason.equals("TRIAL_REVOKED") ? "CANCELLED"
                : Set.of("MODEL_UNKNOWN_REMOTE_OUTCOME", "MODEL_INTERRUPTED", "MODEL_ACCESS_ENDED", "CALL_REGISTRATION_UNCERTAIN").contains(reason) ? "UNKNOWN_REMOTE_OUTCOME" : "FAILED";
        return json(Map.of("status", status, "stopReason", reason));
    }
    private static JsonObject page(JsonObject read, int turn, JsonObject decision) {
        String full = read.get("content").getAsString(); int offset = decision.get("offset").getAsInt(); CaseInputs.require(offset <= full.length(), "Page offset beyond captured input");
        CaseInputs.require(offset == 0 || offset == full.length() || !Character.isHighSurrogate(full.charAt(offset - 1)) || !Character.isLowSurrogate(full.charAt(offset)), "Page offset splits a Unicode character");
        int end = Math.min(full.length(), offset + decision.get("maxCharacters").getAsInt());
        if (end < full.length() && end > offset && Character.isHighSurrogate(full.charAt(end - 1)) && Character.isLowSurrogate(full.charAt(end))) end--;
        CaseInputs.require(end > offset || offset == full.length(), "Page limit cannot hold the next Unicode character");
        String content = full.substring(offset, end);
        return json(Map.of("pageId", "read-" + turn, "caseId", read.get("caseId").getAsString(), "inputId", read.get("inputId").getAsString(), "artifactSha256", read.get("sha256").getAsString(),
                "pageSha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)), "offset", offset, "nextOffset", end, "totalCharacters", full.length(), "content", content, "contentTrust", "untrusted-data"));
    }
    private void verifyPage(Spec spec, String participant, int turn, JsonObject decision, JsonObject page) throws IOException {
        String key = CaseInputs.hash((participant + ":read-" + turn).getBytes(StandardCharsets.UTF_8));
        var read = record(parent(spec.runId()), "reads/" + key + ".json");
        CaseInputs.require(read.get("participant").getAsString().equals(participant) && read.get("caseId").equals(decision.get("caseId"))
                && read.get("inputId").equals(decision.get("inputId")) && page.equals(page(read, turn, decision)), "Changed team page runtime binding");
    }
    private static JsonObject eventReceipt(Path run, String prefix, String stepFile) throws IOException {
        var hashes = new TreeMap<String, String>(); hashes.put("specSha256", hash(run, "spec.json")); hashes.put("eventSha256", hash(run, "event-" + prefix + ".json")); hashes.put("stepSha256", hash(run, stepFile));
        if (exists(run, "request-" + prefix + ".json")) hashes.put("requestSha256", hash(run, "request-" + prefix + ".json")); return json(hashes);
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))) && result.equals(result(run, spec)), "Changed or incomplete team-stage result"); return result;
    }
    private JsonObject result(Path run, Spec spec) throws IOException {
        var trace = record(run, "stage-trace.json"); var current = runtime.snapshot(spec.runId());
        CaseInputs.require(record(run, "stage-trace-receipt.json").equals(json(Map.of("traceSha256", hash(run, "stage-trace.json")))), "Changed stage trace receipt");
        for (String field : List.of("runId", "specSha256", "definitionIdentity", "slot")) CaseInputs.require(trace.get(field).equals(current.get(field)), "Changed team-stage trace identity");
        // Later append-only operations or revocation do not erase this historical prefix.
        for (var entry : trace.getAsJsonObject("artifactSha256").entrySet())
            CaseInputs.require(entry.getValue().equals(current.getAsJsonObject("artifactSha256").get(entry.getKey())), "Changed historical team-stage runtime artifact");
        var originals = record(run, "originals.json"); var review = record(run, "review.json");
        CaseInputs.require(record(run, "originals-receipt.json").equals(json(Map.of("originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")))), "Changed team stage barrier");
        var reserved = new CoordinationTeamBudget.Reservations(originals.get("reservedCalls").getAsInt(), review.get("reservedCalls").getAsInt(), 0, 0);
        var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, reserved);
        CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == reserved.total(), "Changed stage accounting");
        var result = json(Map.of("formatVersion", 1, "runId", spec.runId(), "status", review.get("status").getAsString().equals("COMPLETED") ? "REVIEW_RECORDED" : "REVIEW_STOPPED",
                "wholeTrialStatus", "PENDING", "pendingStages", List.of("FOLLOW_UP", "RECONCILIATION", "COMPARISON_EXPORT"), "workers", originals.get("workers"), "coordinatorReview", review,
                "stageReservations", reserved, "allocation", allocation, "artifactSha256", artifacts(run)));
        result.add("stageTrace", trace); result.addProperty("qualityReview", "NOT_PERFORMED"); result.addProperty("authority", "original-investigation-and-review-prefix-not-completed-team-trial-or-semantic-acceptance"); return result;
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(hash(parent(spec.runId()), "spec.json")), "Changed team registration");
        runtime.snapshot(spec.runId()); var host = record(parent(spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.COORDINATED_TEAM, "Changed team runtime binding");
        CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); artifacts(run); return spec;
    }
    private static Workload workload(Spec spec) { return spec.definition().workloads().stream().filter(w -> w.workloadId().equals(spec.slot().workloadId())).findFirst().orElseThrow(); }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString(); if (Set.of("owner.lock", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", "originals.json", "originals-receipt.json", "review.json", "stage-trace.json", "stage-trace-receipt.json").contains(name)
                        || name.matches("(?:round|review-turn)-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-(?:coordinator|stripe-customer-sources-2020-08-27|stripe-subscription-periods-2025-07-30)(?:-receipt)?\\.json"), "Unexpected team-stage artifact");
                hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private Path parent(String id) { return state.resolve("coordination-trials/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid team run reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean active(JsonObject event) { return event.has("status") && event.get("status").getAsString().equals("ACTIVE"); }
    private static boolean exists(Path run, String name) { return Files.exists(run.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path run, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(run, name)); }
    private static JsonObject record(Path run, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(run, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static String encoded(JsonElement value) { return JSON.toJson(canonical(value)); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(k -> sorted.add(k, canonical(value.getAsJsonObject().get(k)))); return sorted; }
        if (value.isJsonArray()) { var array = new JsonArray(); value.getAsJsonArray().forEach(v -> array.add(canonical(v))); return array; } return value;
    }
    private static void retain(Path run, String name, Object value) throws IOException { if (exists(run, name)) CaseInputs.require(record(run, name).equals(json(value)), "Changed resumed team action"); else publish(run, name, value); }
    private static void publish(Path run, String name, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); Path pending = run.resolve(name + ".pending");
        CaseInputs.require(!exists(run, name) && !exists(run, name + ".pending") && bytes.length <= 1048576, "Existing, incomplete or oversized team artifact");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, run.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(Path state, String bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-adapter-check-placeholder-" + arm.name()));
        implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        var tasks = new CoordinationInputs(state).identities(bundle).entrySet().stream().map(e -> new CaseTask(e.getKey(), e.getValue(), ExpectedResponse.ANSWER, List.of("observed-regressions"))).toList();
        return new Definition("team-review-prefix-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-prefix-check"), implementations,
                List.of(new Workload("two-case", tasks)), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: CoordinationTeamRunner policy STATE | check STATE BUNDLE | run/read STATE TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        var adapter = new CoordinationTeamRunner(Path.of(args[1])); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 3, "Invalid stage check arguments"); id = adapter.register(args[2], checkDefinition(Path.of(args[1]), args[2]), new Slot("two-case", 1, Arm.COORDINATED_TEAM)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "run", "read" -> { CaseInputs.require(args.length == 3, "Invalid stage action arguments"); id = args[2]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid team-stage action");
        }
        System.out.println("Status: " + result.get("status").getAsString() + "; whole team comparison: PENDING; quality review: NOT_PERFORMED");
        if (!active(result)) System.out.println("Model calls: " + result.getAsJsonObject("stageTrace").getAsJsonObject("usage").get("reservedCalls").getAsInt() + "; new case executions: 0");
        System.out.println("Stage report: " + adapter.directory(id).resolve("result.json"));
    }
}
