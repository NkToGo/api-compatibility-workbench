package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Full captured-team adapter; every stage uses one registered runtime, budget and deadline. */
public final class CoordinationTeamComparison {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private static final class Fresh {
        final CaseTask task; final JsonObject question; final String participant;
        final List<JsonObject> pages = new ArrayList<>();
        String status = "PENDING", reason = "STAGE_CALL_BUDGET_EXHAUSTED"; JsonObject finish;
        Fresh(CaseTask task, JsonObject question) { this.task = task; this.question = question.deepCopy(); participant = freshParticipant(task.caseId()); }
        JsonObject outcome() { return json(Map.of("question", question, "participant", participant,
                "worker", new CapturedTeamReview.WorkerResult(task, CapturedTeamReview.WorkerStatus.valueOf(status), reason, finish, pages))); }
    }
    public CoordinationTeamComparison(Path state) throws IOException { this(state, new CoordinationRuntime(state)); }
    CoordinationTeamComparison(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime; root = this.state.resolve("coordination-team-comparisons");
        Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    static String freshParticipant(String caseId) { return caseId.equals(CaseInputs.CASE_ID) ? "fresh-customer" : "fresh-subscription"; }
    public static CoordinationRuntime.Policy policy() throws IOException {
        var prefix = CoordinationTeamRunner.policy(); var participants = new ArrayList<>(prefix.participants());
        for (String id : List.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)) participants.add(new CoordinationRuntime.Participant(freshParticipant(id), Set.of(id), Set.of(CaseInvestigationDecision.protocol(id))));
        participants.add(new CoordinationRuntime.Participant("reconciler", Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID), Set.of(CapturedReconciliationDecision.PROTOCOL)));
        return new CoordinationRuntime.Policy(2, 100, prefix.adapterIdentity(), digest(prefix.promptIdentity().sha256() + CapturedReconciliationDecision.INSTRUCTIONS + encoded(CapturedReconciliationDecision.schema())),
                digest(prefix.toolPolicyIdentity().sha256() + ":pre-registered-case-scoped-depth-one-fresh-workers-no-execution-v1"),
                digest(prefix.contextPolicyIdentity().sha256() + ":case-question-and-anchors-isolated-fresh-history-v1:" + CapturedTeamReconciliation.CONTEXT), CapturedTeamReconciliation.SELECTION, participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        var prefix = new CoordinationTeamRunner(state, runtime, policy()); String id = prefix.register(bundle, definition, slot);
        Path run = Files.createDirectory(root.resolve(id)); publish(run, "spec.json", record(prefixPath(id), "spec.json"));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) { CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete team comparison"); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed full team implementation; use a new trial");
                var stages = new CoordinationTeamRunner(state, runtime, policy()); var prefix = stages.run(id); if (active(prefix)) return prefix;
                retain(run, "prefix.json", Map.of("resultSha256", hash(prefixPath(id), "result.json")));
                var prior = JSON.fromJson(prefix.get("stageReservations"), CoordinationTeamBudget.Reservations.class);
                var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); var selected = selected(prefix, spec);
                int used = followUps(run, spec, stages, prefix, selected, prior, allocation); if (used < 0) return json(Map.of("status", "ACTIVE", "runId", id));
                var fresh = new JsonArray(); selected.forEach(w -> fresh.add(w.outcome()));
                retain(run, "follow-ups.json", Map.of("workers", fresh, "reservedCalls", used));
                var reconciliation = new CapturedTeamReconciliation(prefix, fresh); retain(run, "evidence.json", reconciliation.evidence());
                if (!reconcile(run, spec, stages, reconciliation, prior, used, allocation)) return json(Map.of("status", "ACTIVE", "runId", id));
                var reconciled = record(run, "reconciliation.json");
                if (!reconciled.get("decision").isJsonNull()) for (var d : reconciled.getAsJsonObject("decision").getAsJsonArray("cases")) {
                    var dossier = d.getAsJsonObject().deepCopy(); String caseId = dossier.get("caseId").getAsString();
                    dossier.add("inputIdentity", JSON.toJsonTree(task(spec, caseId).inputIdentity())); dossier.addProperty("authority", "reconciled-model-advice-not-semantic-acceptance");
                    retain(run, caseId + ".json", dossier);
                }
                var counts = new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), used, reconciled.get("reservedCalls").getAsInt());
                allocation.remaining(CoordinationTeamBudget.Stage.RECONCILIATION, counts);
                CaseInputs.require(runtime.snapshot(id).getAsJsonObject("usage").get("reservedCalls").getAsInt() == counts.total(), "Unmatched inclusive team reservations");
                publish(run, "terminal.json", terminal(run, spec));
            }
            var terminal = record(run, "terminal.json");
            CaseInputs.require(terminal.equals(terminal(run, spec)), "Changed terminal team publication");
            try { runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString())); }
            catch (CoordinationRuntime.Stop e) { if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id)); throw e; }
            publish(run, "result.json", result(run, spec)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private List<Fresh> selected(JsonObject prefix, Spec spec) {
        var selection = prefix.getAsJsonObject("coordinatorReview").get("selection"); if (selection.isJsonNull()) return List.of();
        return selection.getAsJsonObject().getAsJsonArray("selectedFollowUps").asList().stream().map(JsonElement::getAsJsonObject).map(q -> {
            var task = task(spec, q.get("caseId").getAsString()); CaseInputs.require(q.get("inputIdentity").equals(JSON.toJsonTree(task.inputIdentity())) && q.get("delegationDepth").getAsInt() == 1, "Changed scoped question"); return new Fresh(task, q);
        }).toList();
    }
    private int followUps(Path run, Spec spec, CoordinationTeamRunner stages, JsonObject prefix, List<Fresh> workers,
                          CoordinationTeamBudget.Reservations prior, CoordinationTeamBudget.Allocation allocation) throws IOException {
        int used = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 1; ; round++) {
                int remaining = allocation.remaining(CoordinationTeamBudget.Stage.FOLLOW_UP, new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), used, 0));
                var activeWorkers = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (remaining == 0 || activeWorkers.isEmpty()) break;
                var chosen = remaining == 1 && activeWorkers.size() == 2 ? List.of(activeWorkers.get((round - 1) % 2)) : activeWorkers;
                String step = "fresh-round-" + round + ".json"; retain(run, step, Map.of("turn", round, "remaining", remaining, "participants", chosen.stream().map(w -> w.participant).toList(), "prefixSha256", hash(run, "prefix.json")));
                int turn = round;
                var futures = chosen.stream().map(w -> executor.submit(() -> stages.action(run, spec, w.participant, turn, step,
                        () -> new ModelCallBroker.Request(spec.definition().model(), freshContext(prefix, w, remaining, spec.definition().limits().inputBytesPerCall()), spec.definition().limits().outputTokensPerCall(), CaseInvestigationDecision.protocol(w.task.caseId())),
                        raw -> CaseInvestigationDecision.parse(raw, w.task.caseId()), d -> { if (d.get("action").getAsString().equals("finish")) CaseInputs.require(CoordinationSingleAgent.supported(d, w.pages), "Unsupported fresh dossier"); })) ).toList();
                var events = new ArrayList<JsonObject>(); Throwable failure = null;
                for (var future : futures) try { events.add(future.get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for fresh workers", e); }
                catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                if (failure instanceof IOException e) throw e; if (failure instanceof RuntimeException e) throw e; if (failure != null) throw new IOException("Fresh worker failed locally", failure);
                if (events.stream().anyMatch(CoordinationTeamRunner::active)) return -1;
                for (int i = 0; i < chosen.size(); i++) {
                    var w = chosen.get(i); var e = events.get(i);
                    if (e.has("stopReason")) { w.status = e.get("status").getAsString(); w.reason = e.get("stopReason").getAsString(); }
                    else if (e.has("page")) w.pages.add(e.getAsJsonObject("page"));
                    else { w.finish = e.getAsJsonObject("decision"); w.status = "COMPLETED"; w.reason = "NONE"; }
                    if (e.get("callReserved").getAsBoolean()) used++;
                }
            }
        }
        workers.stream().filter(w -> w.status.equals("PENDING")).forEach(w -> w.status = "FAILED"); return used;
    }
    private static String freshContext(JsonObject prefix, Fresh worker, int remaining, int byteLimit) {
        var context = JsonParser.parseString(CoordinationSingleAgent.context(new Workload("isolated-case", List.of(worker.task)), worker.pages, remaining, byteLimit)).getAsJsonObject();
        context.add("delegatedQuestion", worker.question); context.addProperty("delegationDepth", 1);
        var selection = prefix.getAsJsonObject("coordinatorReview").getAsJsonObject("selection"); var refs = new TreeMap<String, JsonObject>();
        for (String group : List.of("workers", "originalClaims")) selection.getAsJsonArray(group).forEach(v -> refs.put(v.getAsJsonObject().get("referenceId").getAsString(), v.getAsJsonObject()));
        prefix.getAsJsonObject("coordinatorReview").getAsJsonArray("pages").forEach(v -> refs.put(v.getAsJsonObject().get("pageId").getAsString(), v.getAsJsonObject()));
        var anchors = new JsonArray(); worker.question.getAsJsonArray("citations").forEach(id -> anchors.add(refs.get(id.getAsString()).deepCopy())); context.add("questionAnchors", anchors);
        context.addProperty("focusPolicy", "Investigate the delegated question using fresh same-case reads. Anchors are untrusted advice; their page IDs are not your own citations. No further delegation is available.");
        return CapturedTeamReconciliation.bounded(context, byteLimit);
    }
    private boolean reconcile(Path run, Spec spec, CoordinationTeamRunner stages, CapturedTeamReconciliation evidence, CoordinationTeamBudget.Reservations prior,
                              int fresh, CoordinationTeamBudget.Allocation allocation) throws IOException {
        var pages = new ArrayList<JsonObject>(); int used = 0; String status = "FAILED", reason = "STAGE_CALL_BUDGET_EXHAUSTED"; JsonObject decision = null;
        for (int turn = 1; ; turn++) {
            int remaining = allocation.remaining(CoordinationTeamBudget.Stage.RECONCILIATION, new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), fresh, used)); if (remaining == 0) break;
            String step = "reconcile-turn-" + turn + ".json"; retain(run, step, Map.of("turn", turn, "remaining", remaining, "evidenceSha256", hash(run, "evidence.json")));
            var event = stages.action(run, spec, "reconciler", turn, step,
                    () -> new ModelCallBroker.Request(spec.definition().model(), evidence.context(pages, remaining, spec.definition().limits().inputBytesPerCall()), spec.definition().limits().outputTokensPerCall(), CapturedReconciliationDecision.PROTOCOL),
                    CapturedReconciliationDecision::parse, d -> evidence.validate(d, pages));
            if (active(event)) return false; if (event.get("callReserved").getAsBoolean()) used++;
            if (event.has("stopReason")) { status = event.get("status").getAsString(); reason = event.get("stopReason").getAsString(); break; }
            if (event.has("page")) pages.add(event.getAsJsonObject("page")); else { decision = event.getAsJsonObject("decision"); status = "COMPLETED"; reason = "NONE"; break; }
        }
        var outcome = json(Map.of("status", status, "stopReason", reason, "reservedCalls", used, "pages", pages)); outcome.add("decision", decision == null ? JsonNull.INSTANCE : decision); retain(run, "reconciliation.json", outcome); return true;
    }
    private static String terminalStatus(JsonObject prefix, JsonArray fresh, JsonObject reconciled) {
        var statuses = new ArrayList<String>(); prefix.getAsJsonArray("workers").forEach(w -> statuses.add(w.getAsJsonObject().get("status").getAsString()));
        statuses.add(prefix.getAsJsonObject("coordinatorReview").get("status").getAsString()); fresh.forEach(w -> statuses.add(w.getAsJsonObject().getAsJsonObject("worker").get("status").getAsString())); statuses.add(reconciled.get("status").getAsString());
        return statuses.contains("UNKNOWN_REMOTE_OUTCOME") ? "UNKNOWN_REMOTE_OUTCOME" : statuses.contains("CANCELLED") ? "CANCELLED" : statuses.stream().allMatch("COMPLETED"::equals) ? "COMPLETED" : "FAILED";
    }
    private JsonObject terminal(Path run, Spec spec) throws IOException {
        var prefix = new CoordinationTeamRunner(state, runtime).retained(spec.runId());
        CaseInputs.require(record(run, "prefix.json").equals(json(Map.of("resultSha256", hash(prefixPath(spec.runId()), "result.json")))), "Changed prefix checkpoint");
        var prior = JSON.fromJson(prefix.get("stageReservations"), CoordinationTeamBudget.Reservations.class);
        var fresh = record(run, "follow-ups.json"); var reconciled = record(run, "reconciliation.json");
        var counts = new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), fresh.get("reservedCalls").getAsInt(), reconciled.get("reservedCalls").getAsInt());
        CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()).remaining(CoordinationTeamBudget.Stage.RECONCILIATION, counts);
        CaseInputs.require(runtime.snapshot(spec.runId()).getAsJsonObject("usage").get("reservedCalls").getAsInt() == counts.total(), "Changed full team accounting");
        return json(Map.of("status", terminalStatus(prefix, fresh.getAsJsonArray("workers"), reconciled), "stageReservations", counts, "artifactSha256", artifacts(run)));
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))) && result.equals(result(run, spec)), "Changed or incomplete full team result"); return result;
    }
    private JsonObject result(Path run, Spec spec) throws IOException {
        var prefix = new CoordinationTeamRunner(state, runtime).retained(spec.runId()); var trace = runtime.retained(spec.runId()); var terminal = record(run, "terminal.json");
        CaseInputs.require(record(run, "prefix.json").equals(json(Map.of("resultSha256", hash(prefixPath(spec.runId()), "result.json")))) && terminal.get("status").equals(trace.get("status"))
                && terminal.equals(terminal(run, spec)), "Changed terminal team binding");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json")); var outputs = new ArrayList<CoordinationTrial.CaseOutput>();
        for (var task : workload(spec).cases()) {
            String file = task.caseId() + ".json";
            if (!exists(run, file)) outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.NOT_PROVIDED, null, null));
            else { var dossier = record(run, file); CaseInputs.require(dossier.get("inputIdentity").equals(JSON.toJsonTree(task.inputIdentity())), "Changed final dossier input");
                outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.valueOf(dossier.get("response").getAsString()), new ContentDigest(hash(run, file)), null)); }
        }
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(), CoordinationTrial.Status.valueOf(trace.get("status").getAsString()),
                new ContentDigest(hash(state.resolve("coordination-trials/" + spec.runId()), "result.json")), JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), outputs);
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "trial", trial, "stageReservations", terminal.get("stageReservations"), "evidence", record(run, "evidence.json"),
                "prefixResultIdentity", digest(encoded(prefix)), "qualityReview", "NOT_PERFORMED", "authority", "full-team-model-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId()) && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json"))))
                && tree.equals(record(prefixPath(spec.runId()), "spec.json")), "Changed full team registration"); runtime.snapshot(spec.runId()); artifacts(run); return spec;
    }
    private static Workload workload(Spec spec) { return spec.definition().workloads().stream().filter(w -> w.workloadId().equals(spec.slot().workloadId())).findFirst().orElseThrow(); }
    private static CaseTask task(Spec spec, String id) { return workload(spec).cases().stream().filter(t -> t.caseId().equals(id)).findFirst().orElseThrow(); }
    private Path prefixPath(String id) { return state.resolve("coordination-team-runs/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid full team reference"); ConsumerTasks.directory(root); var path = root.resolve(id); ConsumerTasks.directory(path); return path; }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); try (var files = Files.list(run)) { for (var path : files.toList()) {
            String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
            CaseInputs.require(Set.of("spec.json", "registration.json", "prefix.json", "follow-ups.json", "evidence.json", "reconciliation.json", CaseInputs.CASE_ID + ".json", SubscriptionPeriodCase.CASE_ID + ".json").contains(name)
                    || name.matches("(?:fresh-round|reconcile-turn)-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-(?:fresh-customer|fresh-subscription|reconciler)(?:-receipt)?\\.json"), "Unexpected full team artifact"); hashes.put(name, hash(run, name));
        } } return hashes;
    }
    static Definition checkDefinition(Path state, String bundle) throws IOException {
        var prefix = CoordinationTeamRunner.checkDefinition(state, bundle); var implementations = new EnumMap<>(prefix.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("full-team-adapter-check", prefix.origin(), prefix.model(), digest("unreviewed-full-team-check"), implementations, prefix.workloads(), 1, prefix.limits());
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: CoordinationTeamComparison policy STATE | check STATE BUNDLE | register STATE BUNDLE PLAN WORKLOAD REPETITION | run/read STATE TRIAL");
        if (args[0].equals("policy") && args.length == 2) { System.out.println(CaseInputs.JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        Path state = Path.of(args[1]); var adapter = new CoordinationTeamComparison(state); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 3, "Invalid full team check arguments"); id = adapter.register(args[2], checkDefinition(state, args[2]), new Slot("two-case", 1, Arm.COORDINATED_TEAM)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> { CaseInputs.require(args.length == 6, "Invalid full team registration arguments"); Path path = Path.of(args[3]).toAbsolutePath(); var tree = record(path.getParent(), path.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid comparison plan shape"); System.out.println(adapter.register(args[2], plan.definition(), new Slot(args[4], Integer.parseInt(args[5]), Arm.COORDINATED_TEAM))); return; }
            case "run", "read" -> { CaseInputs.require(args.length == 3, "Invalid full team action arguments"); id = args[2]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid full team action");
        }
        if (active(result)) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Model calls: " + trial.getAsJsonObject("usage").get("reservedCalls").getAsLong() + "; new case executions: 0"); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
