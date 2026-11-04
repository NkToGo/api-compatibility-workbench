package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;
import io.github.nktogo.apicompatibility.runner.InvoiceTeamRunnerV2.Spec;

/** Durable originals/review/selected-fresh prefix; reconciliation and whole-trial export remain pending. */
public final class InvoiceFollowUpRunnerV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID, COORDINATOR = "invoice-coordinator";
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private final InvoiceComparisonInputs.Bundle inputs;
    private static final class Fresh {
        final String worker, participant; final List<JsonObject> pages = new ArrayList<>();
        JsonObject metadata, finish; String status = "PENDING", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        Fresh(String worker) { this.worker = worker; participant = InvoiceFollowUpDecisionV2.participant(worker); }
        JsonObject outcome(InvoiceFollowUpV2 followUp) {
            var value = json(Map.of("workerId", worker, "participantId", participant, "status", status, "stopReason", reason, "questionBinding", followUp.question(worker), "reads", pages));
            value.add("bootstrap", metadata == null ? JsonNull.INSTANCE : metadata);
            value.add("decision", finish == null ? JsonNull.INSTANCE : finish);
            value.add("accepted", finish == null ? JsonNull.INSTANCE : followUp.accept(worker, finish, metadata, pages)); return value;
        }
    }
    public InvoiceFollowUpRunnerV2(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, InvoiceComparisonInputs.open(inputs, expected), CoordinationRuntime.forInvoiceFollowUpV2(state, inputs, expected));
    }
    InvoiceFollowUpRunnerV2(Path state, InvoiceComparisonInputs.Bundle inputs, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.inputs = Objects.requireNonNull(inputs); this.runtime = runtime;
        root = this.state.resolve("invoice-follow-up-v2-runs"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException { return InvoiceTeamRunnerV2.followUpPolicy(); }
    private InvoiceTeamRunnerV2 stages() throws IOException { return InvoiceTeamRunnerV2.forFollowUps(state, inputs, runtime); }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        String id = stages().register(bundle, definition, slot); Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", record(prefix(id), "spec.json")); publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete v2 follow-up result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            CaseInputs.require(spec.policy().equals(policy()), "Changed v2 follow-up implementation; register a new trial");
            var stages = stages(); var source = exists(run, "prefix.json") ? stages.retained(id) : stages.run(id); if (active(source)) return source;
            retain(run, "prefix.json", Map.of("resultSha256", hash(prefix(id), "result.json")));
            var followUp = followUp(spec, source); var prior = JSON.fromJson(source.get("stageReservations"), CoordinationTeamBudget.Reservations.class);
            var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls());
            if (!exists(run, "fresh-round-1.json") && !exists(run, "follow-ups.json"))
                CaseInputs.require(runtime.snapshot(id).getAsJsonObject("usage").get("reservedCalls").getAsInt() == prior.total(), "Unexpected reservations before v2 fresh stage");
            retain(run, "selection.json", selection(run, followUp));
            boolean sealed = exists(run, "follow-ups.json");
            if (sealed) preflight(run, record(run, "follow-ups.json"));
            var workers = selected(followUp); int used = followUps(run, spec, stages, followUp, prior, allocation, workers, sealed);
            if (used < 0) return json(Map.of("status", "ACTIVE", "runId", id));
            seal(run, spec, followUp, workers, used);
            if (!exists(run, "stage-trace.json")) {
                var trace = runtime.snapshot(id); CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == prior.total() + used, "Unexpected v2 fresh reservations");
                publish(run, "stage-trace.json", trace);
            }
            if (!exists(run, "stage-trace-receipt.json")) {
                var frozen = record(run, "stage-trace.json"); var current = runtime.snapshot(id); var a = frozen.deepCopy(); var b = current.deepCopy(); a.remove("elapsedMillis"); b.remove("elapsedMillis");
                CaseInputs.require(a.equals(b) && frozen.get("elapsedMillis").getAsLong() <= current.get("elapsedMillis").getAsLong(), "Changed incomplete v2 follow-up trace");
                publish(run, "stage-trace-receipt.json", Map.of("traceSha256", hash(run, "stage-trace.json")));
            }
            publish(run, "result.json", result(run, spec)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private InvoiceFollowUpV2 followUp(Spec spec, JsonObject source) throws IOException {
        var review = source.getAsJsonObject("coordinatorReview"); if (!review.get("status").getAsString().equals("COMPLETED")) return null;
        var decisions = new ArrayList<JsonObject>();
        for (String file : source.getAsJsonObject("artifactSha256").keySet()) if (file.matches("event-[1-9][0-9]{0,2}-invoice-coordinator\\.json")) {
            var event = record(prefix(spec.runId()), file);
            if (!event.has("stopReason") && event.has("decision") && event.getAsJsonObject("decision").get("action").getAsString().equals("finish")) decisions.add(event.getAsJsonObject("decision"));
        }
        CaseInputs.require(decisions.size() == 1, "Expected one authenticated coordinator finish");
        var originals = source.getAsJsonArray("workers").asList().stream().map(v -> InvoiceTeamReviewV2.retainedWorker(v.getAsJsonObject())).toList();
        var pages = review.getAsJsonArray("pages").asList().stream().map(JsonElement::getAsJsonObject).toList();
        var followUp = new InvoiceFollowUpV2(inputs, originals, COORDINATOR, decisions.getFirst(), record(prefix(spec.runId()), "metadata-" + COORDINATOR + ".json"), pages);
        CaseInputs.require(followUp.selection().equals(review.get("selection")), "Changed authenticated v2 review selection"); return followUp;
    }
    private static List<Fresh> selected(InvoiceFollowUpV2 followUp) { return followUp == null ? List.of() : followUp.selectedWorkers().stream().map(Fresh::new).toList(); }
    private static JsonObject selection(Path run, InvoiceFollowUpV2 followUp) throws IOException {
        var value = json(Map.of("prefixSha256", hash(run, "prefix.json"), "sourcePolicy", InvoiceFollowUpV2.SOURCE));
        value.add("selection", followUp == null ? JsonNull.INSTANCE : followUp.selection());
        value.addProperty("selectionIdentity", followUp == null ? null : followUp.selectionIdentity());
        value.add("questions", JSON.toJsonTree(followUp == null ? List.of() : followUp.selectedWorkers().stream().map(followUp::question).toList())); return value;
    }
    private int followUps(Path run, Spec spec, InvoiceTeamRunnerV2 stages, InvoiceFollowUpV2 followUp, CoordinationTeamBudget.Reservations prior,
                          CoordinationTeamBudget.Allocation allocation, List<Fresh> workers, boolean recordedOnly) throws IOException {
        int used = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 1; ; round++) {
                int remaining = allocation.remaining(CoordinationTeamBudget.Stage.FOLLOW_UP, reserved(prior, used));
                var activeWorkers = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (remaining == 0 || activeWorkers.isEmpty()) break;
                var chosen = remaining == 1 && activeWorkers.size() == 2 ? List.of(activeWorkers.get((round - 1) % 2)) : activeWorkers;
                String step = "fresh-round-" + round + ".json";
                if (recordedOnly) {
                    CaseInputs.require(exists(run, step), "Missing sealed v2 fresh round");
                    for (var w : chosen) CaseInputs.require(exists(run, "event-" + round + "-" + w.participant + "-receipt.json"), "Missing sealed v2 fresh event");
                }
                retain(run, step, Map.of("turn", round, "remaining", remaining, "activeParticipants", activeWorkers.size(), "participants", chosen.stream().map(w -> w.participant).toList(), "selectionSha256", hash(run, "selection.json"), "specSha256", hash(run, "spec.json")));
                int turn = round, usedBefore = used;
                var futures = chosen.stream().map(w -> executor.submit(() -> stages.action(run, spec, w.participant, turn, step,
                        () -> request(run, spec, followUp, w, allocation, reserved(prior, usedBefore), activeWorkers.size()), InvoiceFollowUpDecisionV2::parse,
                        (d, bootstrap) -> followUp.accept(w.worker, d, bootstrap, w.pages), subject(spec, w), w.pages))).toList();
                var events = new ArrayList<JsonObject>(); Throwable failure = null;
                for (var future : futures) try { events.add(future.get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for v2 fresh workers", e); }
                catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                if (failure instanceof UncheckedIOException e) throw e.getCause(); if (failure instanceof IOException e) throw e;
                if (failure instanceof RuntimeException e) throw e; if (failure != null) throw new IOException("V2 fresh worker failed locally", failure);
                if (events.stream().anyMatch(CoordinationTeamRunner::active)) return -1;
                for (int i = 0; i < chosen.size(); i++) {
                    var w = chosen.get(i); var event = events.get(i);
                    if (exists(run, "metadata-" + w.participant + ".json")) { w.metadata = record(run, "metadata-" + w.participant + ".json"); stages.verifyMetadata(spec, w.participant, w.metadata); }
                    if (event.has("stopReason")) { w.status = event.get("status").getAsString(); w.reason = event.get("stopReason").getAsString(); }
                    else if (event.has("page")) w.pages.add(event.getAsJsonObject("page"));
                    else { w.finish = event.getAsJsonObject("decision"); w.status = "COMPLETED"; w.reason = "NONE"; }
                    if (event.get("callReserved").getAsBoolean()) used++;
                }
            }
        }
        for (var worker : workers) if (worker.status.equals("PENDING")) worker.status = "FAILED"; return used;
    }
    private ModelCallBroker.Request request(Path run, Spec spec, InvoiceFollowUpV2 followUp, Fresh worker, CoordinationTeamBudget.Allocation allocation,
                                            CoordinationTeamBudget.Reservations reserved, int active) {
        try {
            String context = followUp.context(worker.worker, record(run, "metadata-" + worker.participant + ".json"), worker.pages, allocation, reserved, active, spec.definition().limits().inputBytesPerCall());
            return new ModelCallBroker.Request(spec.definition().model(), context, spec.definition().limits().outputTokensPerCall(), InvoiceFollowUpDecisionV2.protocol(worker.worker));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static InvoiceReadiness.Subject subject(Spec spec, Fresh worker) { return new InvoiceReadiness.Subject(worker.participant, new ContentDigest(spec.bundleId()), InvoiceFollowUpDecisionV2.scope(worker.worker)); }
    private static CoordinationTeamBudget.Reservations reserved(CoordinationTeamBudget.Reservations prior, int used) { return new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), used, 0); }
    private void preflight(Path run, JsonObject sealed) throws IOException {
        CaseInputs.require(sealed.get("freshArtifactSha256").equals(JSON.toJsonTree(freshArtifacts(run))), "Changed sealed v2 fresh artifacts");
        historical(sealed.getAsJsonObject("runtimeArtifactSha256"), runtime.snapshot(run.getFileName().toString()).getAsJsonObject("artifactSha256"));
    }
    private void seal(Path run, Spec spec, InvoiceFollowUpV2 followUp, List<Fresh> workers, int used) throws IOException {
        var value = json(Map.of("workers", workers.stream().map(w -> w.outcome(followUp)).toList(), "reservedCalls", used, "selectionSha256", hash(run, "selection.json"), "freshArtifactSha256", freshArtifacts(run)));
        if (exists(run, "follow-ups.json")) { var stored = record(run, "follow-ups.json"); value.add("runtimeArtifactSha256", stored.get("runtimeArtifactSha256")); }
        else value.add("runtimeArtifactSha256", runtime.snapshot(spec.runId()).get("artifactSha256"));
        retain(run, "follow-ups.json", value); retain(run, "follow-ups-receipt.json", Map.of("followUpsSha256", hash(run, "follow-ups.json"), "specSha256", hash(run, "spec.json")));
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var value = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))) && value.equals(result(run, spec)), "Changed or incomplete v2 fresh result"); return value;
    }
    private JsonObject result(Path run, Spec spec) throws IOException {
        var stages = stages(); var source = stages.retained(spec.runId());
        CaseInputs.require(record(run, "prefix.json").equals(json(Map.of("resultSha256", hash(prefix(spec.runId()), "result.json")))), "Changed v2 source prefix");
        var followUp = followUp(spec, source); CaseInputs.require(record(run, "selection.json").equals(selection(run, followUp)), "Changed v2 selected questions");
        var sealed = record(run, "follow-ups.json"); preflight(run, sealed);
        CaseInputs.require(record(run, "follow-ups-receipt.json").equals(json(Map.of("followUpsSha256", hash(run, "follow-ups.json"), "specSha256", hash(run, "spec.json")))), "Changed v2 fresh barrier receipt");
        var prior = JSON.fromJson(source.get("stageReservations"), CoordinationTeamBudget.Reservations.class); var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); var workers = selected(followUp);
        int used = followUps(run, spec, stages, followUp, prior, allocation, workers, true);
        CaseInputs.require(used >= 0 && sealed.get("reservedCalls").getAsInt() == used && sealed.get("workers").equals(JSON.toJsonTree(workers.stream().map(w -> w.outcome(followUp)).toList())), "Changed v2 fresh histories or accounting");
        var trace = record(run, "stage-trace.json"); var current = runtime.snapshot(spec.runId());
        CaseInputs.require(record(run, "stage-trace-receipt.json").equals(json(Map.of("traceSha256", hash(run, "stage-trace.json")))), "Changed v2 fresh trace receipt");
        for (String field : List.of("runId", "specSha256", "definitionIdentity", "slot")) CaseInputs.require(trace.get(field).equals(current.get(field)), "Changed v2 fresh trace identity");
        historical(trace.getAsJsonObject("artifactSha256"), current.getAsJsonObject("artifactSha256"));
        var reservations = reserved(prior, used); allocation.remaining(CoordinationTeamBudget.Stage.FOLLOW_UP, reservations);
        CaseInputs.require(trace.getAsJsonObject("usage").get("reservedCalls").getAsInt() == reservations.total(), "Unaccounted v2 fresh model reservation");
        var value = json(Map.of("formatVersion", 2, "runId", spec.runId(), "status", workers.isEmpty() ? "FOLLOW_UP_SKIPPED" : "FOLLOW_UP_RECORDED", "wholeTrialStatus", "PENDING", "pendingStages", List.of("RECONCILIATION", "COMPARISON_EXPORT"),
                "prefixResultIdentity", new ContentDigest(hash(prefix(spec.runId()), "result.json")), "originalsAndReview", source, "stageReservations", reservations, "allocation", allocation, "artifactSha256", artifacts(run)));
        value.add("selection", record(run, "selection.json")); value.add("fresh", sealed); value.add("stageTrace", trace);
        value.addProperty("qualityReview", "NOT_PERFORMED"); value.addProperty("authority", "selected-v2-fresh-prefix-not-completed-team-or-semantic-acceptance"); return value;
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 2 && spec.runId().equals(run.getFileName().toString()) && inputs.identity().sha256().equals(spec.bundleId())
                && tree.equals(record(prefix(spec.runId()), "spec.json")) && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(hash(parent(spec.runId()), "spec.json")), "Changed v2 fresh registration");
        runtime.snapshot(spec.runId()); var files = artifacts(run);
        if (files.keySet().stream().anyMatch(n -> n.startsWith("fresh-round-") || n.startsWith("metadata-") || n.startsWith("request-") || n.startsWith("event-") || n.startsWith("follow-ups") || n.startsWith("stage-trace")))
            CaseInputs.require(exists(run, "prefix.json") && exists(run, "selection.json"), "Missing published v2 fresh source barrier");
        if (exists(run, "follow-ups-receipt.json")) CaseInputs.require(exists(run, "follow-ups.json"), "Missing sealed v2 fresh stage");
        if (exists(run, "stage-trace-receipt.json")) CaseInputs.require(exists(run, "stage-trace.json"), "Missing sealed v2 fresh trace");
        return spec;
    }
    private static void historical(JsonObject old, JsonObject current) {
        CaseInputs.require(old != null && current != null, "Missing v2 historical inventory");
        for (var entry : old.entrySet()) CaseInputs.require(entry.getValue().equals(current.get(entry.getKey())), "Changed v2 sealed runtime artifact");
    }
    private static Map<String, String> freshArtifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); for (var entry : artifacts(run).entrySet()) if (entry.getKey().startsWith("fresh-round-") || entry.getKey().startsWith("metadata-") || entry.getKey().startsWith("request-") || entry.getKey().startsWith("event-")) hashes.put(entry.getKey(), entry.getValue()); return hashes;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); try (var paths = Files.list(run)) { for (var path : paths.toList()) {
            String name = path.getFileName().toString(); if (Set.of("owner.lock", "result.json", "receipt.json").contains(name)) continue;
            CaseInputs.require(Set.of("spec.json", "registration.json", "prefix.json", "selection.json", "follow-ups.json", "follow-ups-receipt.json", "stage-trace.json", "stage-trace-receipt.json", "metadata-fresh-invoice-webhook.json", "metadata-fresh-invoice-reconciliation.json").contains(name)
                    || name.matches("fresh-round-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-fresh-invoice-(?:webhook|reconciliation)(?:-receipt)?\\.json"), "Unexpected v2 fresh artifact"); hashes.put(name, hash(run, name));
        } } return hashes;
    }
    private Path parent(String id) { return state.resolve("invoice-team-follow-up-v2-trials/" + id); }
    private Path prefix(String id) { return state.resolve("invoice-team-follow-up-v2-prefix-runs/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid v2 fresh reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var base = InvoiceSingleAgentV2.checkDefinition(bundle); var implementations = new EnumMap<>(base.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-follow-up-v2-prefix-check", base.origin(), base.model(), digest("unreviewed-invoice-v2-follow-up-check"), implementations, base.workloads(), 1, base.limits());
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        CaseInputs.require(args.length >= 4, "Invoice inputs and independently retained identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceFollowUpRunnerV2(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid invoice follow-up check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 7, "Invalid invoice follow-up registration arguments"); Path path = Path.of(args[4]).toAbsolutePath(); var tree = record(path.getParent(), path.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid invoice follow-up plan shape"); System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.COORDINATED_TEAM))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid invoice follow-up action arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid invoice follow-up action");
        }
        System.out.println("Status: " + result.get("status").getAsString() + "; whole team comparison: PENDING; quality review: NOT_PERFORMED");
        if (!active(result)) System.out.println("Usage: " + JSON.toJson(result.getAsJsonObject("stageTrace").get("usage")));
        System.out.println("Stage report: " + adapter.directory(id).resolve("result.json"));
    }
}
