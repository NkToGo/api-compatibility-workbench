package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;
import io.github.nktogo.apicompatibility.runner.InvoiceTeamRunner.Spec;

/** Full invoice team adapter; every stage shares one pre-registered runtime and deadline. */
public final class InvoiceTeamComparison {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID, RECONCILER = "invoice-final-reconciler";
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private static final class Fresh {
        final JsonObject question; final String worker, participant; final ContentDigest input;
        final List<JsonObject> pages = new ArrayList<>(); JsonObject metadata, finish;
        String status = "PENDING", reason = "STAGE_CALL_BUDGET_EXHAUSTED";
        Fresh(JsonObject question) {
            this.question = question.deepCopy(); worker = question.get("workerId").getAsString(); participant = InvoiceTeamReconciliation.freshParticipant(worker);
            input = JSON.fromJson(question.get("inputIdentity"), ContentDigest.class);
        }
        JsonObject outcome() { return json(Map.of("question", question, "participant", participant,
                "worker", new InvoiceTeamReview.WorkerResult(worker, input, InvoiceTeamReview.WorkerStatus.valueOf(status), reason, finish, metadata, pages))); }
    }
    public InvoiceTeamComparison(Path state, Path inputs, ContentDigest expected) throws IOException { this(state, CoordinationRuntime.forInvoiceInvestigation(state, inputs, expected)); }
    InvoiceTeamComparison(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime; root = this.state.resolve("invoice-team-comparisons");
        Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        var prefix = InvoiceTeamRunner.policy(); var participants = new ArrayList<>(prefix.participants());
        for (String worker : InvoiceWorkerDecision.WORKERS) participants.add(new CoordinationRuntime.Participant(InvoiceTeamReconciliation.freshParticipant(worker), Set.of(CASE), Set.of(InvoiceFollowUpDecision.protocol(worker))));
        participants.add(new CoordinationRuntime.Participant(RECONCILER, Set.of(CASE), Set.of(InvoiceReconciliationDecision.PROTOCOL)));
        return new CoordinationRuntime.Policy(2, 100, prefix.adapterIdentity(), digest(prefix.promptIdentity().sha256()
                + InvoiceFollowUpDecision.instructions(InvoiceFollowUpDecision.RECONCILIATION) + InvoiceFollowUpDecision.instructions(InvoiceFollowUpDecision.WEBHOOK)
                + InvoiceReconciliationDecision.INSTRUCTIONS + encoded(InvoiceReconciliationDecision.schema())), prefix.toolPolicyIdentity(),
                digest(prefix.contextPolicyIdentity().sha256() + ":fixed-depth-one-role-questions-own-fresh-reads-v1:" + InvoiceTeamReconciliation.CONTEXT), InvoiceTeamReconciliation.SELECTION, participants);
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        String id = new InvoiceTeamRunner(state, runtime, policy()).register(bundle, definition, slot);
        Path run = Files.createDirectory(root.resolve(id)); publish(run, "spec.json", record(prefixPath(id), "spec.json"));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete invoice team comparison");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed full invoice implementation; use a new trial");
                var stages = new InvoiceTeamRunner(state, runtime, policy()); var prefix = stages.run(id); if (active(prefix)) return prefix;
                retain(run, "prefix.json", Map.of("resultSha256", hash(prefixPath(id), "result.json")));
                var prior = JSON.fromJson(prefix.get("stageReservations"), CoordinationTeamBudget.Reservations.class);
                var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); var selected = selected(prefix);
                int used = followUps(run, spec, stages, prefix, selected, prior, allocation); if (used < 0) return json(Map.of("status", "ACTIVE", "runId", id));
                var fresh = new JsonArray(); selected.forEach(w -> fresh.add(w.outcome())); retain(run, "follow-ups.json", Map.of("workers", fresh, "reservedCalls", used));
                var reconciliation = new InvoiceTeamReconciliation(prefix, fresh); retain(run, "evidence.json", reconciliation.evidence());
                if (!reconcile(run, spec, stages, reconciliation, prior, used, allocation)) return json(Map.of("status", "ACTIVE", "runId", id));
                var reconciled = record(run, "reconciliation.json");
                if (!reconciled.get("decision").isJsonNull()) retain(run, "dossier.json", dossier(spec, reconciled.getAsJsonObject("decision")));
                publish(run, "terminal.json", terminal(run, spec));
            }
            var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.equals(terminal(run, spec)), "Changed terminal invoice team publication");
            try { runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString())); }
            catch (CoordinationRuntime.Stop e) { if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id)); throw e; }
            publish(run, "result.json", result(run, spec)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private static List<Fresh> selected(JsonObject prefix) {
        var selection = InvoiceTeamReconciliation.selection(prefix); return selection == null ? List.of()
                : selection.getAsJsonArray("selectedFollowUps").asList().stream().map(JsonElement::getAsJsonObject).map(Fresh::new).toList();
    }
    private int followUps(Path run, Spec spec, InvoiceTeamRunner stages, JsonObject prefix, List<Fresh> workers,
                          CoordinationTeamBudget.Reservations prior, CoordinationTeamBudget.Allocation allocation) throws IOException {
        int used = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 1; ; round++) {
                int remaining = allocation.remaining(CoordinationTeamBudget.Stage.FOLLOW_UP, new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), used, 0));
                var activeWorkers = workers.stream().filter(w -> w.status.equals("PENDING")).toList(); if (remaining == 0 || activeWorkers.isEmpty()) break;
                var chosen = remaining == 1 && activeWorkers.size() == 2 ? List.of(activeWorkers.get((round - 1) % 2)) : activeWorkers;
                String step = "fresh-round-" + round + ".json"; retain(run, step, Map.of("turn", round, "remaining", remaining, "participants", chosen.stream().map(w -> w.participant).toList(), "prefixSha256", hash(run, "prefix.json")));
                int turn = round;
                for (var w : chosen) if (exists(run, "metadata-" + w.participant + ".json")) {
                    w.metadata = record(run, "metadata-" + w.participant + ".json"); stages.verifyMetadata(spec, w.participant, w.metadata);
                }
                var futures = chosen.stream().map(w -> executor.submit(() -> stages.action(run, spec, w.participant, turn, step,
                        () -> freshRequest(run, spec, stages, prefix, w, remaining), InvoiceInvestigationDecision::parse,
                        d -> { if (d.get("action").getAsString().equals("finish")) CaseInputs.require(InvoiceWorkerDecision.supported(w.worker, d, w.pages, w.metadata), "Unsupported fresh invoice dossier"); }))).toList();
                var events = new ArrayList<JsonObject>(); Throwable failure = null;
                // Drain all submitted siblings before releasing either ownership or context histories.
                for (var future : futures) try { events.add(future.get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for fresh invoice workers", e); }
                catch (ExecutionException e) { if (failure == null) failure = e.getCause(); }
                if (failure instanceof UncheckedIOException e) throw e.getCause(); if (failure instanceof IOException e) throw e;
                if (failure instanceof RuntimeException e) throw e; if (failure != null) throw new IOException("Fresh invoice worker failed locally", failure);
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
    private ModelCallBroker.Request freshRequest(Path run, Spec spec, InvoiceTeamRunner stages, JsonObject prefix, Fresh w, int remaining) {
        if (w.metadata == null) try {
            w.metadata = runtime.describe(spec.runId(), w.participant, "metadata", CASE).getAsJsonObject("page");
            retain(run, "metadata-" + w.participant + ".json", w.metadata); stages.verifyMetadata(spec, w.participant, w.metadata);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return new ModelCallBroker.Request(spec.definition().model(), InvoiceTeamReconciliation.freshContext(prefix, w.question, w.metadata, w.pages, remaining, spec.definition().limits().inputBytesPerCall()),
                spec.definition().limits().outputTokensPerCall(), InvoiceFollowUpDecision.protocol(w.worker));
    }
    private boolean reconcile(Path run, Spec spec, InvoiceTeamRunner stages, InvoiceTeamReconciliation evidence,
                              CoordinationTeamBudget.Reservations prior, int fresh, CoordinationTeamBudget.Allocation allocation) throws IOException {
        var pages = new ArrayList<JsonObject>(); int used = 0; String status = "FAILED", reason = "STAGE_CALL_BUDGET_EXHAUSTED"; JsonObject decision = null;
        for (int turn = 1; ; turn++) {
            int remaining = allocation.remaining(CoordinationTeamBudget.Stage.RECONCILIATION, new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), fresh, used)); if (remaining == 0) break;
            String step = "reconcile-turn-" + turn + ".json"; retain(run, step, Map.of("turn", turn, "remaining", remaining, "evidenceSha256", hash(run, "evidence.json")));
            var event = stages.action(run, spec, RECONCILER, turn, step,
                    () -> new ModelCallBroker.Request(spec.definition().model(), evidence.context(pages, remaining, spec.definition().limits().inputBytesPerCall()), spec.definition().limits().outputTokensPerCall(), InvoiceReconciliationDecision.PROTOCOL),
                    InvoiceReconciliationDecision::parse, d -> evidence.validate(d, pages));
            if (active(event)) return false; if (event.get("callReserved").getAsBoolean()) used++;
            if (event.has("stopReason")) { status = event.get("status").getAsString(); reason = event.get("stopReason").getAsString(); break; }
            if (event.has("page")) pages.add(event.getAsJsonObject("page")); else { decision = event.getAsJsonObject("decision"); status = "COMPLETED"; reason = "NONE"; break; }
        }
        var outcome = json(Map.of("status", status, "stopReason", reason, "reservedCalls", used, "pages", pages)); outcome.add("decision", decision == null ? JsonNull.INSTANCE : decision);
        retain(run, "reconciliation.json", outcome); return true;
    }
    private static JsonObject dossier(Spec spec, JsonObject decision) {
        var value = decision.deepCopy(); value.addProperty("caseId", CASE); value.addProperty("bundleIdentity", spec.bundleId());
        value.addProperty("authority", "reconciled-invoice-model-advice-not-semantic-acceptance"); return value;
    }
    private JsonObject terminal(Path run, Spec spec) throws IOException {
        var prefix = new InvoiceTeamRunner(state, runtime).retained(spec.runId());
        CaseInputs.require(record(run, "prefix.json").equals(json(Map.of("resultSha256", hash(prefixPath(spec.runId()), "result.json")))), "Changed invoice prefix checkpoint");
        var prior = JSON.fromJson(prefix.get("stageReservations"), CoordinationTeamBudget.Reservations.class); var fresh = record(run, "follow-ups.json"); var reconciled = record(run, "reconciliation.json");
        var evidence = new InvoiceTeamReconciliation(prefix, fresh.getAsJsonArray("workers")); CaseInputs.require(record(run, "evidence.json").equals(evidence.evidence()), "Changed invoice reconciliation lineage");
        var pages = reconciled.getAsJsonArray("pages").asList().stream().map(JsonElement::getAsJsonObject).toList(); InvoiceTeamReview.validatePages(pages);
        if (reconciled.get("status").getAsString().equals("COMPLETED")) {
            CaseInputs.require(!reconciled.get("decision").isJsonNull() && reconciled.get("stopReason").getAsString().equals("NONE"), "Missing invoice reconciliation decision");
            evidence.validate(reconciled.getAsJsonObject("decision"), pages); CaseInputs.require(record(run, "dossier.json").equals(dossier(spec, reconciled.getAsJsonObject("decision"))), "Changed final invoice dossier");
        } else CaseInputs.require(reconciled.get("decision").isJsonNull() && !exists(run, "dossier.json"), "Stopped invoice reconciliation cannot provide a dossier");
        var counts = new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), fresh.get("reservedCalls").getAsInt(), reconciled.get("reservedCalls").getAsInt());
        CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()).remaining(CoordinationTeamBudget.Stage.RECONCILIATION, counts);
        CaseInputs.require(runtime.snapshot(spec.runId()).getAsJsonObject("usage").get("reservedCalls").getAsInt() == counts.total(), "Unmatched inclusive invoice team reservations");
        var statuses = new ArrayList<String>(); prefix.getAsJsonArray("workers").forEach(w -> statuses.add(w.getAsJsonObject().get("status").getAsString()));
        statuses.add(prefix.getAsJsonObject("coordinatorReview").get("status").getAsString()); fresh.getAsJsonArray("workers").forEach(w -> statuses.add(w.getAsJsonObject().getAsJsonObject("worker").get("status").getAsString())); statuses.add(reconciled.get("status").getAsString());
        String status = statuses.contains("UNKNOWN_REMOTE_OUTCOME") ? "UNKNOWN_REMOTE_OUTCOME" : statuses.contains("CANCELLED") ? "CANCELLED" : statuses.stream().allMatch("COMPLETED"::equals) ? "COMPLETED" : "FAILED";
        return json(Map.of("status", status, "stageReservations", counts, "artifactSha256", artifacts(run)));
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))) && result.equals(result(run, spec)), "Changed or incomplete full invoice team result"); return result;
    }
    private JsonObject result(Path run, Spec spec) throws IOException {
        var trace = runtime.retained(spec.runId()); var terminal = record(run, "terminal.json");
        CaseInputs.require(terminal.get("status").equals(trace.get("status")) && terminal.equals(terminal(run, spec)), "Changed terminal invoice team binding");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json"));
        var output = exists(run, "dossier.json") ? new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), CoordinationTrial.Response.valueOf(record(run, "dossier.json").get("response").getAsString()), new ContentDigest(hash(run, "dossier.json")), null)
                : new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), CoordinationTrial.Response.NOT_PROVIDED, null, null);
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(), CoordinationTrial.Status.valueOf(trace.get("status").getAsString()),
                new ContentDigest(hash(parent(spec.runId()), "result.json")), JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), List.of(output));
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "trial", trial, "stageReservations", terminal.get("stageReservations"),
                "evidence", record(run, "evidence.json"), "prefixResultIdentity", new ContentDigest(hash(prefixPath(spec.runId()), "result.json")), "qualityReview", "NOT_PERFORMED", "authority", "full-invoice-team-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && tree.equals(record(prefixPath(spec.runId()), "spec.json"))
                && spec.runtimeSpecSha256().equals(hash(parent(spec.runId()), "spec.json")), "Changed full invoice registration");
        runtime.snapshot(spec.runId()); artifacts(run); return spec;
    }
    private Path parent(String id) { return state.resolve("invoice-comparison-trials/" + id); }
    private Path prefixPath(String id) { return state.resolve("invoice-team-runs/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid full invoice reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); try (var files = Files.list(run)) { for (var path : files.toList()) {
            String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
            CaseInputs.require(Set.of("spec.json", "registration.json", "prefix.json", "follow-ups.json", "evidence.json", "reconciliation.json", "dossier.json", "metadata-fresh-invoice-webhook.json", "metadata-fresh-invoice-reconciliation.json").contains(name)
                    || name.matches("(?:fresh-round|reconcile-turn)-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-(?:fresh-invoice-(?:webhook|reconciliation)|invoice-final-reconciler)(?:-receipt)?\\.json"), "Unexpected full invoice artifact"); hashes.put(name, hash(run, name));
        } } return hashes;
    }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var base = InvoiceSingleAgent.checkDefinition(bundle); var implementations = new EnumMap<>(base.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("full-invoice-team-adapter-check", base.origin(), base.model(), digest("unreviewed-full-invoice-team-check"), implementations, base.workloads(), 1, base.limits());
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(CaseInputs.JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        CaseInputs.require(args.length >= 4, "Invoice inputs and independently retained identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceTeamComparison(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid full invoice check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> { CaseInputs.require(args.length == 7, "Invalid full invoice registration arguments"); Path path = Path.of(args[4]).toAbsolutePath(); var tree = record(path.getParent(), path.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid invoice plan shape"); System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.COORDINATED_TEAM))); return; }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid full invoice action arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid full invoice action");
        }
        if (active(result)) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        System.out.println("Status: " + result.getAsJsonObject("trial").get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Usage: " + CaseInputs.JSON.toJson(result.getAsJsonObject("trial").get("usage"))); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
