package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Durable v2 original-worker/review prefix; no fresh delegation or whole-trial export. */
public final class InvoiceTeamRunnerV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID, COORDINATOR = "invoice-coordinator";
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private final InvoiceComparisonInputs.Bundle inputs;
    record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                CoordinationRuntime.Policy policy, String runtimeSpecSha256, String publicTaskSha256) {}
    private static final class Worker {
        final String id; final ContentDigest input;
        final List<JsonObject> pages = new ArrayList<>(); JsonObject metadata, finish;
        String status = "PENDING", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        Worker(String id, String input) { this.id = id; this.input = new ContentDigest(input); }
        InvoiceTeamReviewV2.WorkerResult result() {
            var scope = scope(id);
            return new InvoiceTeamReviewV2.WorkerResult(id, input, InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope),
                    InvoiceDecisionV2.taskIdentity(), InvoiceTeamReviewV2.WorkerStatus.valueOf(status), reason, finish, metadata, pages);
        }
    }
    public InvoiceTeamRunnerV2(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, InvoiceComparisonInputs.open(inputs, expected), CoordinationRuntime.forInvoiceTeamReviewV2(state, inputs, expected));
    }
    InvoiceTeamRunnerV2(Path state, InvoiceComparisonInputs.Bundle inputs, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.inputs = Objects.requireNonNull(inputs); this.runtime = runtime;
        root = this.state.resolve("invoice-team-v2-runs"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        return CoordinationRuntime.invoiceTeamReviewV2Policy(2, 100, participants());
    }
    private static List<CoordinationRuntime.Participant> participants() {
        var participants = new ArrayList<CoordinationRuntime.Participant>();
        for (String worker : InvoiceWorkerDecision.WORKERS) participants.add(new CoordinationRuntime.Participant(worker, Set.of(CASE), Set.of(InvoiceDecisionV2.protocol(scope(worker)))));
        participants.add(new CoordinationRuntime.Participant(COORDINATOR, Set.of(CASE), Set.of(InvoiceTeamDecisionV2.PROTOCOL)));
        return List.copyOf(participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.COORDINATED_TEAM, "Invoice team runner requires the coordinated arm");
        CaseInputs.require(inputs.identity().sha256().equals(bundle), "Invoice v2 team input identity mismatch");
        CoordinationTeamBudget.forCeiling(definition.limits().modelCalls());
        var policy = policy();
        String id = runtime.register(bundle, definition, slot, policy); Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", new Spec(2, id, bundle, definition, slot, policy, hash(parent(id), "spec.json"), InvoiceDecisionV2.taskIdentity()));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete invoice team prefix");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            CaseInputs.require(spec.policy().equals(policy()), "Changed invoice team implementation; use a new trial");
            var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls());
            var workers = InvoiceWorkerDecision.WORKERS.stream().map(w -> new Worker(w, spec.bundleId())).toList();
            boolean sealedOriginals = exists(run, "originals.json");
            if (sealedOriginals) {
                var barrier = record(run, "originals.json");
                CaseInputs.require(barrier.get("originalArtifactSha256").equals(JSON.toJsonTree(originalArtifacts(run))), "Changed original-stage seals after barrier");
                requireHistoricalArtifacts(barrier.getAsJsonObject("runtimeArtifactSha256"), runtime.snapshot(id).getAsJsonObject("artifactSha256"));
            }
            int used = originals(run, spec, allocation, workers, sealedOriginals); if (used < 0) return json(Map.of("status", "ACTIVE", "runId", id));
            sealOriginals(run, spec, allocation, workers, used);
            var review = new InvoiceTeamReviewV2(COORDINATOR, inputs, workers.stream().map(Worker::result).toList());
            if (!review(run, spec, allocation, used, review, false)) return json(Map.of("status", "ACTIVE", "runId", id));
            // Preserve a prefix; the whole-trial deadline and remaining call pool stay unchanged.
            if (!exists(run, "stage-trace.json")) {
                var trace = runtime.snapshot(id);
                CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == used + record(run, "review.json").get("reservedCalls").getAsInt(), "Unexpected invoice stage reservations");
                publish(run, "stage-trace.json", trace);
            }
            if (!exists(run, "stage-trace-receipt.json")) {
                var frozen = record(run, "stage-trace.json"); var current = runtime.snapshot(id); var a = frozen.deepCopy(); var b = current.deepCopy(); a.remove("elapsedMillis"); b.remove("elapsedMillis");
                CaseInputs.require(a.equals(b) && frozen.get("elapsedMillis").getAsLong() <= current.get("elapsedMillis").getAsLong(), "Changed incomplete invoice stage trace");
                publish(run, "stage-trace-receipt.json", Map.of("traceSha256", hash(run, "stage-trace.json")));
            }
            publish(run, "result.json", result(run, spec)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private int originals(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, List<Worker> workers, boolean recordedOnly) throws IOException {
        int used = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 1; used < allocation.investigation(); round++) {
                var active = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (active.isEmpty()) break;
                int remaining = allocation.remaining(CoordinationTeamBudget.Stage.INVESTIGATION, new CoordinationTeamBudget.Reservations(used, 0, 0, 0));
                var selected = remaining == 1 && active.size() == 2 ? List.of(active.get((round - 1) % 2)) : active; String step = "round-" + round + ".json";
                if (recordedOnly) {
                    CaseInputs.require(exists(run, step), "Missing sealed original round");
                    for (var worker : selected) CaseInputs.require(exists(run, "event-" + round + "-" + worker.id + "-receipt.json"), "Missing sealed original event");
                }
                retain(run, step, Map.of("round", round, "decisionsRemaining", remaining, "activeParticipants", active.size(), "workers", selected.stream().map(w -> w.id).toList(), "specSha256", hash(run, "spec.json")));
                int turn = round;
                var futures = selected.stream().map(w -> executor.submit(() -> action(run, spec, w.id, turn, step, () -> originalRequest(spec, w, remaining, active.size()), w, null, w.pages))).toList();
                var events = new ArrayList<JsonObject>(); Throwable failure = null;
                // Join every submitted task before releasing ownership, including local failures.
                for (var future : futures) {
                    try { events.add(future.get()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for invoice team workers", e); }
                    catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                }
                if (failure instanceof IOException e) throw e; if (failure instanceof RuntimeException e) throw e;
                if (failure != null) throw new IOException("Invoice team worker failed locally", failure);
                if (events.stream().anyMatch(CoordinationTeamRunner::active)) return -1;
                for (int i = 0; i < selected.size(); i++) {
                    var w = selected.get(i); var event = events.get(i);
                    if (event.has("stopReason")) { w.status = event.get("status").getAsString(); w.reason = event.get("stopReason").getAsString(); }
                    else if (event.has("page")) w.pages.add(event.getAsJsonObject("page"));
                    else { w.finish = event.getAsJsonObject("decision"); w.status = "COMPLETED"; w.reason = "NONE"; }
                }
                used += (int)events.stream().filter(e -> e.get("callReserved").getAsBoolean()).count();
            }
        }
        for (var worker : workers) if (worker.status.equals("PENDING")) worker.status = "FAILED"; return used;
    }
    private ModelCallBroker.Request originalRequest(Spec spec, Worker worker, int remaining, int activeParticipants) {
        CaseInputs.require(worker.metadata != null, "Missing invoice worker metadata");
        return new ModelCallBroker.Request(spec.definition().model(), InvoiceContextV2.build(subject(spec, worker.id), inputs,
                worker.metadata, worker.pages, new InvoiceContextV2.Scheduling(remaining, activeParticipants), spec.definition().limits().inputBytesPerCall()),
                spec.definition().limits().outputTokensPerCall(), InvoiceDecisionV2.protocol(scope(worker.id)));
    }
    private boolean review(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, int originalCalls, InvoiceTeamReviewV2 review, boolean recordedOnly) throws IOException {
        var pages = new ArrayList<JsonObject>(); int used = 0; JsonObject selection = null; String status = "FAILED", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        for (int turn = 1; ; turn++) {
            int remaining = allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, new CoordinationTeamBudget.Reservations(originalCalls, used, 0, 0)); if (remaining == 0) break;
            String step = "review-turn-" + turn + ".json";
            if (recordedOnly) CaseInputs.require(exists(run, step) && exists(run, "event-" + turn + "-" + COORDINATOR + "-receipt.json"), "Missing sealed review event");
            retain(run, step, Map.of("turn", turn, "decisionsRemaining", remaining, "originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")));
            int reviewCalls = used;
            var event = action(run, spec, COORDINATOR, turn, step, () -> new ModelCallBroker.Request(spec.definition().model(), review.context(recordUnchecked(run, "metadata-" + COORDINATOR + ".json"), pages,
                    allocation, new CoordinationTeamBudget.Reservations(originalCalls, reviewCalls, 0, 0), spec.definition().limits().inputBytesPerCall()),
                    spec.definition().limits().outputTokensPerCall(), InvoiceTeamDecisionV2.PROTOCOL), null, review, pages);
            if (active(event)) return false; if (event.get("callReserved").getAsBoolean()) used++;
            if (event.has("stopReason")) { status = event.get("status").getAsString(); reason = event.get("stopReason").getAsString(); break; }
            if (event.has("page")) pages.add(event.getAsJsonObject("page"));
            else { selection = review.select(event.getAsJsonObject("decision"), record(run, "metadata-" + COORDINATOR + ".json"), pages); status = "COMPLETED"; reason = "NONE"; break; }
        }
        var outcome = json(Map.of("status", status, "stopReason", reason, "reservedCalls", used, "pages", pages)); outcome.add("selection", selection == null ? JsonNull.INSTANCE : selection);
        retain(run, "review.json", outcome); return true;
    }
    private JsonObject action(Path run, Spec spec, String participant, int turn, String step, java.util.function.Supplier<ModelCallBroker.Request> request,
                              Worker worker, InvoiceTeamReviewV2 review, List<JsonObject> pages) throws IOException {
        String prefix = turn + "-" + participant, eventFile = "event-" + prefix + ".json", seal = "event-" + prefix + "-receipt.json", metadataFile = "metadata-" + participant + ".json";
        JsonObject bootstrap = exists(run, metadataFile) ? record(run, metadataFile) : null;
        if (bootstrap != null) { verifyMetadata(spec, participant, bootstrap); if (worker != null) worker.metadata = bootstrap; }
        if (exists(run, seal)) {
            var event = record(run, eventFile);
            CaseInputs.require(record(run, seal).equals(eventReceipt(run, prefix, step, participant))
                    && event.get("turn").getAsInt() == turn && event.get("participant").getAsString().equals(participant)
                    && event.get("callReserved").getAsBoolean() == runtime.reserved(spec.runId(), participant, "decision-" + turn), "Changed sealed invoice v2 team event");
            if (exists(run, "request-" + prefix + ".json")) retain(run, "request-" + prefix + ".json", request.get());
            if (event.has("decision") && !event.has("stopReason")) validate(spec, event.getAsJsonObject("decision"), worker, review, bootstrap, pages);
            if (event.has("page")) verifyPage(spec, participant, turn, event.getAsJsonObject("decision"), event.getAsJsonObject("page"));
            return event;
        }
        JsonObject event, candidate = null;
        try {
            if (bootstrap == null) {
                bootstrap = runtime.describe(spec.runId(), participant, "metadata", CASE); retain(run, metadataFile, bootstrap);
                verifyMetadata(spec, participant, bootstrap); if (worker != null) worker.metadata = bootstrap;
            }
            var payload = request.get(); retain(run, "request-" + prefix + ".json", payload);
            var response = runtime.call(spec.runId(), participant, "decision-" + turn, payload); String status = response.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("status", "ACTIVE"));
            if (!status.equals("COMPLETED")) event = stopped("MODEL_" + status);
            else {
                try { candidate = worker == null ? InvoiceTeamDecisionV2.parse(response.get("outputText").getAsString()) : InvoiceDecisionV2.parse(response.get("outputText").getAsString()); }
                catch (RuntimeException ignored) { /* Untrusted output cannot expand the admitted action set. */ }
                if (candidate == null) { event = stopped("INVALID_MODEL_DECISION"); event.addProperty("actionStatus", "REJECTED_DECISION"); }
                else if (!candidate.get("action").getAsString().equals("finish")) {
                    try { event = json(Map.of("decision", candidate, "page", readPage(spec, participant, turn, candidate))); }
                    catch (IllegalArgumentException e) { event = stopped("INVALID_EVIDENCE_READ"); event.addProperty("actionStatus", "REJECTED_READ"); event.add("decision", candidate); }
                } else {
                    try { validate(spec, candidate, worker, review, bootstrap, pages); runtime.start(spec.runId()); event = json(Map.of("decision", candidate)); }
                    catch (IllegalArgumentException e) {
                        event = stopped("UNSUPPORTED_DOSSIER"); event.addProperty("actionStatus", "REJECTED_FINISH"); event.add("decision", candidate);
                        event.add("readiness", InvoiceContextV2.readiness(subject(spec, participant), inputs, bootstrap, pages).toJson());
                    }
                }
            }
        } catch (CoordinationRuntime.Stop e) {
            if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE")); event = stopped(e.getMessage());
            if (candidate != null) { event.add("decision", candidate); event.addProperty("actionStatus", "STOPPED_ACTION"); }
        }
        event.addProperty("turn", turn); event.addProperty("participant", participant);
        event.addProperty("callReserved", runtime.reserved(spec.runId(), participant, "decision-" + turn));
        retain(run, eventFile, event); publish(run, seal, eventReceipt(run, prefix, step, participant)); return event;
    }
    private void validate(Spec spec, JsonObject decision, Worker worker, InvoiceTeamReviewV2 review, JsonObject bootstrap, List<JsonObject> pages) {
        var parsed = worker == null ? InvoiceTeamDecisionV2.parse(JSON.toJson(Map.of("decision", decision))) : InvoiceDecisionV2.parse(JSON.toJson(Map.of("decision", decision)));
        if (!parsed.get("action").getAsString().equals("finish")) return;
        if (worker == null) review.select(parsed, bootstrap, pages);
        else CaseInputs.require(InvoiceFinishV2.supported(subject(spec, worker.id), parsed,
                InvoiceContextV2.readiness(subject(spec, worker.id), inputs, bootstrap, pages)), "Unsupported original v2 dossier");
    }
    private static InvoiceReadiness.Scope scope(String participant) {
        return switch (participant) { case "invoice-webhook" -> InvoiceReadiness.Scope.WEBHOOK; case "invoice-reconciliation" -> InvoiceReadiness.Scope.RECONCILIATION;
            default -> throw new IllegalArgumentException("Unknown original v2 worker"); };
    }
    private static InvoiceReadiness.Subject subject(Spec spec, String participant) {
        return new InvoiceReadiness.Subject(participant, new ContentDigest(spec.bundleId()), participant.equals(COORDINATOR) ? InvoiceReadiness.Scope.JOINT : scope(participant));
    }
    private static JsonObject recordUnchecked(Path run, String name) {
        try { return record(run, name); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private void sealOriginals(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, List<Worker> workers, int used) throws IOException {
        var value = json(Map.of("workers", workers.stream().map(Worker::result).toList(), "reservedCalls", used,
                "allocation", allocation, "publicTaskSha256", spec.publicTaskSha256(), "originalArtifactSha256", originalArtifacts(run)));
        var trace = runtime.snapshot(spec.runId());
        if (exists(run, "originals.json")) {
            var stored = record(run, "originals.json"); value.add("runtimeArtifactSha256", stored.get("runtimeArtifactSha256"));
            requireHistoricalArtifacts(stored.getAsJsonObject("runtimeArtifactSha256"), trace.getAsJsonObject("artifactSha256"));
        } else {
            CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == used, "Unexpected reservation before original barrier");
            value.add("runtimeArtifactSha256", trace.get("artifactSha256"));
        }
        retain(run, "originals.json", value);
        retain(run, "originals-receipt.json", Map.of("originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")));
    }
    private static Map<String, String> originalArtifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        for (var entry : artifacts(run).entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("round-") || name.matches("(?:metadata-invoice-(?:webhook|reconciliation)|(?:request|event)-[1-9][0-9]{0,2}-invoice-(?:webhook|reconciliation)(?:-receipt)?)\\.json")) hashes.put(name, entry.getValue());
        }
        return hashes;
    }
    private static void requireHistoricalArtifacts(JsonObject historical, JsonObject current) {
        CaseInputs.require(historical != null && current != null, "Missing historical runtime inventory");
        for (var entry : historical.entrySet()) CaseInputs.require(entry.getValue().equals(current.get(entry.getKey())), "Changed sealed runtime artifact");
    }
    private static JsonObject stopped(String reason) {
        String status = reason.equals("TRIAL_REVOKED") ? "CANCELLED" : Set.of("MODEL_UNKNOWN_REMOTE_OUTCOME", "MODEL_INTERRUPTED", "MODEL_ACCESS_ENDED", "CALL_REGISTRATION_UNCERTAIN").contains(reason) ? "UNKNOWN_REMOTE_OUTCOME" : "FAILED";
        return json(Map.of("status", status, "stopReason", reason));
    }
    private JsonObject readPage(Spec spec, String participant, int turn, JsonObject decision) throws IOException {
        String kind = decision.get("action").getAsString(), operation = "read-" + turn;
        return switch (kind) {
            case "catalog" -> runtime.catalog(spec.runId(), participant, operation, CASE, decision.get("offset").getAsInt(), decision.get("limit").getAsInt());
            case "catalog_prefix" -> runtime.catalogPrefix(spec.runId(), participant, operation, CASE, decision.get("prefix").getAsString(), decision.get("offset").getAsInt(), decision.get("limit").getAsInt());
            case "read_text" -> runtime.readPage(spec.runId(), participant, operation, CASE, decision.get("alias").getAsString(), decision.get("offset").getAsInt(), decision.get("maxCharacters").getAsInt());
            default -> throw new IllegalArgumentException("Unknown invoice v2 team read");
        };
    }
    private JsonObject retainedRead(Spec spec, String participant, String operation, ComparisonEvidence.PageRequest expected) throws IOException {
        var read = record(parent(spec.runId()), "reads/" + CaseInputs.hash((participant + ":" + operation).getBytes(StandardCharsets.UTF_8)) + ".json");
        CaseInputs.require(read.get("participant").getAsString().equals(participant) && read.get("operationId").getAsString().equals(operation) && read.get("caseId").getAsString().equals(CASE)
                && read.get("bundleIdentity").getAsString().equals(spec.bundleId()) && read.get("pageRequest").equals(JSON.toJsonTree(expected)), "Changed invoice runtime read binding"); return read;
    }
    private void verifyMetadata(Spec spec, Worker worker) throws IOException {
        verifyMetadata(spec, worker.id, worker.metadata);
    }
    void verifyMetadata(Spec spec, String participant, JsonObject metadata) throws IOException {
        CaseInputs.require(metadata.equals(retainedRead(spec, participant, "metadata", new ComparisonEvidence.PageRequest("describe", CASE, "", 0, 0))), "Changed invoice v2 bootstrap binding");
    }
    private void verifyPage(Spec spec, String participant, int turn, JsonObject decision, JsonObject captured) throws IOException {
        String kind = decision.get("action").getAsString(); var expected = new ComparisonEvidence.PageRequest(kind, CASE,
                kind.equals("catalog") ? "" : decision.get(kind.equals("catalog_prefix") ? "prefix" : "alias").getAsString(),
                decision.get("offset").getAsInt(), decision.get(kind.equals("read_text") ? "maxCharacters" : "limit").getAsInt());
        CaseInputs.require(captured.equals(retainedRead(spec, participant, "read-" + turn, expected)), "Changed invoice v2 page runtime binding");
    }
    private static JsonObject eventReceipt(Path run, String prefix, String step, String participant) throws IOException {
        var hashes = new TreeMap<String, String>(); hashes.put("specSha256", hash(run, "spec.json")); hashes.put("eventSha256", hash(run, "event-" + prefix + ".json")); hashes.put("stepSha256", hash(run, step));
        for (String name : List.of("request-" + prefix + ".json", "metadata-" + participant + ".json")) if (exists(run, name)) hashes.put(name, hash(run, name)); return json(hashes);
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))) && result.equals(result(run, spec)), "Changed or incomplete invoice team prefix"); return result;
    }
    private JsonObject result(Path run, Spec spec) throws IOException {
        var trace = record(run, "stage-trace.json"); var current = runtime.snapshot(spec.runId());
        CaseInputs.require(record(run, "stage-trace-receipt.json").equals(json(Map.of("traceSha256", hash(run, "stage-trace.json")))), "Changed invoice stage trace receipt");
        for (String field : List.of("runId", "specSha256", "definitionIdentity", "slot")) CaseInputs.require(trace.get(field).equals(current.get(field)), "Changed invoice prefix trace identity");
        for (var entry : trace.getAsJsonObject("artifactSha256").entrySet()) CaseInputs.require(entry.getValue().equals(current.getAsJsonObject("artifactSha256").get(entry.getKey())), "Changed historical invoice runtime artifact");
        var originals = record(run, "originals.json"); var review = record(run, "review.json");
        CaseInputs.require(record(run, "originals-receipt.json").equals(json(Map.of("originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")))), "Changed invoice original barrier");
        var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls());
        var replayWorkers = InvoiceWorkerDecision.WORKERS.stream().map(w -> new Worker(w, spec.bundleId())).toList();
        int originalCalls = originals(run, spec, allocation, replayWorkers, true);
        CaseInputs.require(originalCalls >= 0 && originals.get("workers").equals(JSON.toJsonTree(replayWorkers.stream().map(Worker::result).toList()))
                && originals.get("originalArtifactSha256").equals(JSON.toJsonTree(originalArtifacts(run)))
                && spec.publicTaskSha256().equals(originals.get("publicTaskSha256").getAsString()), "Changed original v2 histories or source binding");
        requireHistoricalArtifacts(originals.getAsJsonObject("runtimeArtifactSha256"), current.getAsJsonObject("artifactSha256"));
        var workers = originals.getAsJsonArray("workers").asList().stream().map(v -> InvoiceTeamReviewV2.retainedWorker(v.getAsJsonObject())).toList();
        var guard = new InvoiceTeamReviewV2(COORDINATOR, inputs, workers);
        CaseInputs.require(review(run, spec, allocation, originalCalls, guard, true), "Incomplete sealed v2 coordinator history");
        var reserved = new CoordinationTeamBudget.Reservations(originals.get("reservedCalls").getAsInt(), review.get("reservedCalls").getAsInt(), 0, 0);
        allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, reserved);
        CaseInputs.require(originalCalls == reserved.investigation() && trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == reserved.total()
                && originals.get("allocation").equals(JSON.toJsonTree(allocation)), "Changed invoice prefix accounting");
        var result = json(Map.of("formatVersion", 2, "runId", spec.runId(), "status", review.get("status").getAsString().equals("COMPLETED") ? "REVIEW_RECORDED" : "REVIEW_STOPPED",
                "wholeTrialStatus", "PENDING", "pendingStages", List.of("FOLLOW_UP", "RECONCILIATION", "COMPARISON_EXPORT"), "workers", originals.get("workers"), "coordinatorReview", review,
                "stageReservations", reserved, "allocation", allocation, "artifactSha256", artifacts(run)));
        result.add("stageTrace", trace); result.addProperty("qualityReview", "NOT_PERFORMED"); result.addProperty("authority", "invoice-originals-and-review-prefix-not-completed-team-or-semantic-acceptance"); return result;
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 2
                && inputs.identity().sha256().equals(spec.bundleId()) && InvoiceDecisionV2.taskIdentity().equals(spec.publicTaskSha256()) && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(hash(parent(spec.runId()), "spec.json")), "Changed invoice team registration");
        runtime.snapshot(spec.runId()); var host = record(parent(spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition())) && host.get("slot").equals(JSON.toJsonTree(spec.slot()))
                && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.COORDINATED_TEAM
                && spec.policy().parallelCalls() == 2 && spec.policy().maxReads() == 100
                && spec.policy().participants().equals(participants()), "Changed invoice team runtime binding or fixed membership");
        CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); artifacts(run); return spec;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); try (var files = Files.list(run)) { for (var path : files.toList()) {
            String name = path.getFileName().toString(); if (Set.of("owner.lock", "result.json", "receipt.json").contains(name)) continue;
            CaseInputs.require(Set.of("spec.json", "registration.json", "originals.json", "originals-receipt.json", "review.json", "stage-trace.json", "stage-trace-receipt.json", "metadata-invoice-webhook.json", "metadata-invoice-reconciliation.json", "metadata-invoice-coordinator.json").contains(name)
                    || name.matches("(?:round|review-turn)-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-invoice-(?:webhook|reconciliation|coordinator)(?:-receipt)?\\.json"), "Unexpected invoice team artifact"); hashes.put(name, hash(run, name));
        } } return hashes;
    }
    private Path parent(String id) { return state.resolve("invoice-team-v2-trials/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice team reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var base = InvoiceSingleAgentV2.checkDefinition(bundle); var implementations = new EnumMap<>(base.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-team-review-v2-prefix-check", base.origin(), base.model(), digest("unreviewed-invoice-v2-prefix-check"), implementations, base.workloads(), 1, base.limits());
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        CaseInputs.require(args.length >= 4, "Invoice inputs and independently retained identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceTeamRunnerV2(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid invoice prefix check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 7, "Invalid invoice prefix registration arguments"); Path path = Path.of(args[4]).toAbsolutePath(); var tree = record(path.getParent(), path.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid invoice prefix plan shape"); System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.COORDINATED_TEAM))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid invoice prefix action arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid invoice prefix action");
        }
        System.out.println("Status: " + result.get("status").getAsString() + "; whole team comparison: PENDING; quality review: NOT_PERFORMED");
        if (!active(result)) System.out.println("Usage: " + JSON.toJson(result.getAsJsonObject("stageTrace").get("usage")));
        System.out.println("Stage report: " + adapter.directory(id).resolve("result.json"));
    }
}
