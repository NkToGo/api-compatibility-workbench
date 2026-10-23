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

/** Durable invoice original-worker/review prefix; does not finalize or export a whole team trial. */
public final class InvoiceTeamRunner {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID, COORDINATOR = "invoice-coordinator";
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private final CoordinationRuntime.Policy executionPolicy;
    record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    private static final class Worker {
        final String id; final ContentDigest input;
        final List<JsonObject> pages = new ArrayList<>(); JsonObject metadata, finish;
        String status = "PENDING", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        Worker(String id, String input) { this.id = id; this.input = new ContentDigest(input); }
        InvoiceTeamReview.WorkerResult result() {
            return new InvoiceTeamReview.WorkerResult(id, input, InvoiceTeamReview.WorkerStatus.valueOf(status), reason, finish, metadata, pages);
        }
    }
    public InvoiceTeamRunner(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, CoordinationRuntime.forInvoiceInvestigation(state, inputs, expected));
    }
    InvoiceTeamRunner(Path state, CoordinationRuntime runtime) throws IOException { this(state, runtime, null); }
    InvoiceTeamRunner(Path state, CoordinationRuntime runtime, CoordinationRuntime.Policy executionPolicy) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime; this.executionPolicy = executionPolicy;
        root = this.state.resolve("invoice-team-runs"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        var participants = new ArrayList<CoordinationRuntime.Participant>();
        for (String worker : InvoiceWorkerDecision.WORKERS) participants.add(new CoordinationRuntime.Participant(worker, Set.of(CASE), Set.of(InvoiceWorkerDecision.protocol(worker))));
        participants.add(new CoordinationRuntime.Participant(COORDINATOR, Set.of(CASE), Set.of(InvoiceTeamDecision.PROTOCOL)));
        return new CoordinationRuntime.Policy(2, 100, new ContentDigest(RepairVerification.harness()),
                digest(InvoiceWorkerDecision.instructions(InvoiceWorkerDecision.RECONCILIATION) + InvoiceWorkerDecision.instructions(InvoiceWorkerDecision.WEBHOOK)
                        + encoded(InvoiceInvestigationDecision.schema()) + InvoiceTeamDecision.INSTRUCTIONS + encoded(InvoiceTeamDecision.schema())),
                digest(ComparisonEvidence.INVOICE_TOOLS), digest("invoice-originals-isolated-fair-rounds-metadata-sealed-barrier-v1:"
                        + InvoiceTeamReview.CONTEXT + ":" + CoordinationTeamBudget.POLICY + ":" + InvoiceTeamReview.SELECTION),
                "invoice-originals-and-review-only-no-comparison-export", participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.COORDINATED_TEAM, "Invoice team runner requires the coordinated arm");
        CoordinationTeamBudget.forCeiling(definition.limits().modelCalls());
        var policy = executionPolicy == null ? policy() : executionPolicy;
        String id = runtime.register(bundle, definition, slot, policy); Path run = Files.createDirectory(root.resolve(id));
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
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete invoice team prefix");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            CaseInputs.require(spec.policy().equals(executionPolicy == null ? policy() : executionPolicy), "Changed invoice team implementation; use a new trial");
            var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls());
            var workers = InvoiceWorkerDecision.WORKERS.stream().map(w -> new Worker(w, spec.bundleId())).toList();
            int used = originals(run, spec, allocation, workers); if (used < 0) return json(Map.of("status", "ACTIVE", "runId", id));
            retain(run, "originals.json", Map.of("workers", workers.stream().map(Worker::result).toList(), "reservedCalls", used, "allocation", allocation));
            retain(run, "originals-receipt.json", Map.of("originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")));
            var review = new InvoiceTeamReview(workers.stream().map(Worker::result).toList());
            if (!review(run, spec, allocation, used, review)) return json(Map.of("status", "ACTIVE", "runId", id));
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
    private int originals(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, List<Worker> workers) throws IOException {
        int used = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 1; used < allocation.investigation(); round++) {
                var active = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (active.isEmpty()) break;
                int remaining = allocation.remaining(CoordinationTeamBudget.Stage.INVESTIGATION, new CoordinationTeamBudget.Reservations(used, 0, 0, 0));
                var selected = remaining == 1 && active.size() == 2 ? List.of(active.get((round - 1) % 2)) : active; String step = "round-" + round + ".json";
                retain(run, step, Map.of("round", round, "decisionsRemaining", remaining, "workers", selected.stream().map(w -> w.id).toList(), "specSha256", hash(run, "spec.json")));
                int turn = round;
                var futures = selected.stream().map(w -> executor.submit(() -> action(run, spec, w.id, turn, step, () -> originalRequest(spec, w, remaining), w, null, w.pages))).toList();
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
    private static ModelCallBroker.Request originalRequest(Spec spec, Worker worker, int remaining) {
        CaseInputs.require(worker.metadata != null, "Missing invoice worker metadata"); var metadata = worker.metadata.deepCopy();
        metadata.addProperty("workerId", worker.id); metadata.addProperty("workerRole", InvoiceWorkerDecision.role(worker.id));
        return new ModelCallBroker.Request(spec.definition().model(), InvoiceSingleAgent.context(metadata, worker.pages, remaining, spec.definition().limits().inputBytesPerCall()),
                spec.definition().limits().outputTokensPerCall(), InvoiceWorkerDecision.protocol(worker.id));
    }
    private boolean review(Path run, Spec spec, CoordinationTeamBudget.Allocation allocation, int originalCalls, InvoiceTeamReview review) throws IOException {
        var pages = new ArrayList<JsonObject>(); int used = 0; JsonObject selection = null; String status = "FAILED", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        for (int turn = 1; ; turn++) {
            int remaining = allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, new CoordinationTeamBudget.Reservations(originalCalls, used, 0, 0)); if (remaining == 0) break;
            String step = "review-turn-" + turn + ".json";
            retain(run, step, Map.of("turn", turn, "decisionsRemaining", remaining, "originalsSha256", hash(run, "originals.json"), "specSha256", hash(run, "spec.json")));
            var event = action(run, spec, COORDINATOR, turn, step, () -> new ModelCallBroker.Request(spec.definition().model(), review.context(pages, remaining, spec.definition().limits().inputBytesPerCall()),
                    spec.definition().limits().outputTokensPerCall(), InvoiceTeamDecision.PROTOCOL), null, review, pages);
            if (active(event)) return false; if (event.get("callReserved").getAsBoolean()) used++;
            if (event.has("stopReason")) { status = event.get("status").getAsString(); reason = event.get("stopReason").getAsString(); break; }
            if (event.has("page")) pages.add(event.getAsJsonObject("page"));
            else { selection = review.select(event.getAsJsonObject("decision"), pages); status = "COMPLETED"; reason = "NONE"; break; }
        }
        var outcome = json(Map.of("status", status, "stopReason", reason, "reservedCalls", used, "pages", pages)); outcome.add("selection", selection == null ? JsonNull.INSTANCE : selection);
        retain(run, "review.json", outcome); return true;
    }
    private JsonObject action(Path run, Spec spec, String participant, int turn, String step, java.util.function.Supplier<ModelCallBroker.Request> request,
                              Worker worker, InvoiceTeamReview review, List<JsonObject> pages) throws IOException {
        if (worker != null && exists(run, "metadata-" + participant + ".json")) {
            worker.metadata = record(run, "metadata-" + participant + ".json"); verifyMetadata(spec, worker);
        }
        return action(run, spec, participant, turn, step, () -> {
            if (worker != null && worker.metadata == null) {
                try {
                    worker.metadata = runtime.describe(spec.runId(), participant, "metadata", CASE).getAsJsonObject("page");
                    retain(run, "metadata-" + participant + ".json", worker.metadata); verifyMetadata(spec, worker);
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
            return request.get();
        }, worker == null ? InvoiceTeamDecision::parse : InvoiceInvestigationDecision::parse, d -> validate(d, worker, review, pages));
    }
    JsonObject action(Path run, Spec spec, String participant, int turn, String step, java.util.function.Supplier<ModelCallBroker.Request> request,
                      java.util.function.Function<String, JsonObject> decoder, java.util.function.Consumer<JsonObject> validator) throws IOException {
        String prefix = turn + "-" + participant, eventFile = "event-" + prefix + ".json", seal = "event-" + prefix + "-receipt.json";
        if (exists(run, seal)) {
            var event = record(run, eventFile); CaseInputs.require(record(run, seal).equals(eventReceipt(run, prefix, step, participant)), "Changed sealed invoice team event");
            CaseInputs.require(event.get("turn").getAsInt() == turn && event.get("participant").getAsString().equals(participant)
                    && event.get("callReserved").getAsBoolean() == runtime.reserved(spec.runId(), participant, "decision-" + turn), "Changed invoice team reservation");
            if (exists(run, "request-" + prefix + ".json")) retain(run, "request-" + prefix + ".json", request.get());
            if (event.has("decision")) validator.accept(decoder.apply(JSON.toJson(Map.of("decision", event.get("decision")))));
            if (event.has("page")) verifyPage(spec, participant, turn, event.getAsJsonObject("decision"), event.getAsJsonObject("page")); return event;
        }
        JsonObject event;
        try {
            var payload = request.get(); retain(run, "request-" + prefix + ".json", payload);
            var response = runtime.call(spec.runId(), participant, "decision-" + turn, payload); String status = response.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("status", "ACTIVE"));
            if (!status.equals("COMPLETED")) event = stopped("MODEL_" + status);
            else {
                JsonObject decision = null;
                try { decision = decoder.apply(response.get("outputText").getAsString()); }
                catch (RuntimeException ignored) { /* Untrusted output does not grant tools or permissions. */ }
                if (decision == null) event = stopped("INVALID_MODEL_DECISION");
                else try {
                    validator.accept(decision);
                    if (decision.get("action").getAsString().equals("finish")) { runtime.start(spec.runId()); event = json(Map.of("decision", decision)); }
                    else event = json(Map.of("decision", decision, "page", readPage(spec, participant, turn, decision)));
                } catch (IllegalArgumentException e) { event = stopped("UNSUPPORTED_MODEL_DECISION"); }
            }
        } catch (CoordinationRuntime.Stop e) { if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE")); event = stopped(e.getMessage()); }
        event.addProperty("turn", turn); event.addProperty("participant", participant); event.addProperty("callReserved", runtime.reserved(spec.runId(), participant, "decision-" + turn));
        retain(run, eventFile, event); publish(run, seal, eventReceipt(run, prefix, step, participant)); return event;
    }
    private static void validate(JsonObject decision, Worker worker, InvoiceTeamReview review, List<JsonObject> pages) {
        var parsed = worker == null ? InvoiceTeamDecision.parse(JSON.toJson(Map.of("decision", decision))) : InvoiceInvestigationDecision.parse(JSON.toJson(Map.of("decision", decision)));
        if (!parsed.get("action").getAsString().equals("finish")) return;
        if (worker == null) review.select(parsed, pages);
        else CaseInputs.require(InvoiceWorkerDecision.supported(worker.id, parsed, pages, worker.metadata), "Unsupported invoice original dossier");
    }
    private static JsonObject stopped(String reason) {
        String status = reason.equals("TRIAL_REVOKED") ? "CANCELLED" : Set.of("MODEL_UNKNOWN_REMOTE_OUTCOME", "MODEL_INTERRUPTED", "MODEL_ACCESS_ENDED", "CALL_REGISTRATION_UNCERTAIN").contains(reason) ? "UNKNOWN_REMOTE_OUTCOME" : "FAILED";
        return json(Map.of("status", status, "stopReason", reason));
    }
    private JsonObject readPage(Spec spec, String participant, int turn, JsonObject decision) throws IOException {
        String kind = decision.get("action").getAsString(), operation = "read-" + turn;
        var read = kind.equals("catalog") ? runtime.catalog(spec.runId(), participant, operation, CASE, decision.get("offset").getAsInt(), decision.get("limit").getAsInt())
                : runtime.readPage(spec.runId(), participant, operation, CASE, decision.get("alias").getAsString(), decision.get("offset").getAsInt(), decision.get("maxCharacters").getAsInt());
        return page(read, kind, turn);
    }
    private static JsonObject page(JsonObject read, String kind, int turn) {
        var page = read.getAsJsonObject("page").deepCopy(); page.addProperty("kind", kind); page.addProperty("pageId", "read-" + turn); page.addProperty("contentTrust", "untrusted-data"); return page;
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
        CaseInputs.require(metadata.equals(retainedRead(spec, participant, "metadata", new ComparisonEvidence.PageRequest("describe", CASE, "", 0, 0)).get("page")), "Changed invoice worker metadata binding");
    }
    private void verifyPage(Spec spec, String participant, int turn, JsonObject decision, JsonObject captured) throws IOException {
        String kind = decision.get("action").getAsString(); var expected = new ComparisonEvidence.PageRequest(kind, CASE, kind.equals("catalog") ? "" : decision.get("alias").getAsString(),
                decision.get("offset").getAsInt(), decision.get(kind.equals("catalog") ? "limit" : "maxCharacters").getAsInt());
        CaseInputs.require(captured.equals(page(retainedRead(spec, participant, "read-" + turn, expected), kind, turn)), "Changed invoice page runtime binding");
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
        var workers = originals.getAsJsonArray("workers").asList().stream().map(v -> InvoiceTeamReview.retainedWorker(v.getAsJsonObject())).toList(); var guard = new InvoiceTeamReview(workers);
        var pages = review.getAsJsonArray("pages").asList().stream().map(JsonElement::getAsJsonObject).toList(); InvoiceTeamReview.validatePages(pages);
        if (review.get("status").getAsString().equals("COMPLETED")) {
            CaseInputs.require(review.get("stopReason").getAsString().equals("NONE") && !review.get("selection").isJsonNull()
                    && review.get("selection").equals(guard.select(review.getAsJsonObject("selection").getAsJsonObject("reviewDecision"), pages)), "Changed selected invoice review");
        } else CaseInputs.require(review.get("selection").isJsonNull(), "Stopped invoice review cannot select questions");
        var reserved = new CoordinationTeamBudget.Reservations(originals.get("reservedCalls").getAsInt(), review.get("reservedCalls").getAsInt(), 0, 0);
        var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); allocation.remaining(CoordinationTeamBudget.Stage.REVIEW, reserved);
        CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == reserved.total() && originals.get("allocation").equals(JSON.toJsonTree(allocation)), "Changed invoice prefix accounting");
        var result = json(Map.of("formatVersion", 1, "runId", spec.runId(), "status", review.get("status").getAsString().equals("COMPLETED") ? "REVIEW_RECORDED" : "REVIEW_STOPPED",
                "wholeTrialStatus", "PENDING", "pendingStages", List.of("FOLLOW_UP", "RECONCILIATION", "COMPARISON_EXPORT"), "workers", originals.get("workers"), "coordinatorReview", review,
                "stageReservations", reserved, "allocation", allocation, "artifactSha256", artifacts(run)));
        result.add("stageTrace", trace); result.addProperty("qualityReview", "NOT_PERFORMED"); result.addProperty("authority", "invoice-originals-and-review-prefix-not-completed-team-or-semantic-acceptance"); return result;
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(hash(parent(spec.runId()), "spec.json")), "Changed invoice team registration");
        runtime.snapshot(spec.runId()); var host = record(parent(spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition())) && host.get("slot").equals(JSON.toJsonTree(spec.slot()))
                && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.COORDINATED_TEAM, "Changed invoice team runtime binding");
        CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); artifacts(run); return spec;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); try (var files = Files.list(run)) { for (var path : files.toList()) {
            String name = path.getFileName().toString(); if (Set.of("owner.lock", "result.json", "receipt.json").contains(name)) continue;
            CaseInputs.require(Set.of("spec.json", "registration.json", "originals.json", "originals-receipt.json", "review.json", "stage-trace.json", "stage-trace-receipt.json", "metadata-invoice-webhook.json", "metadata-invoice-reconciliation.json").contains(name)
                    || name.matches("(?:round|review-turn)-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-invoice-(?:webhook|reconciliation|coordinator)(?:-receipt)?\\.json"), "Unexpected invoice team artifact"); hashes.put(name, hash(run, name));
        } } return hashes;
    }
    private Path parent(String id) { return state.resolve("invoice-comparison-trials/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice team reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var base = InvoiceSingleAgent.checkDefinition(bundle); var implementations = new EnumMap<>(base.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-team-review-prefix-check", base.origin(), base.model(), digest("unreviewed-invoice-prefix-check"), implementations, base.workloads(), 1, base.limits());
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        CaseInputs.require(args.length >= 4, "Invoice inputs and independently retained identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceTeamRunner(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
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
