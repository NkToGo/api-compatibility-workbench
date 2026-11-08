package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;
import io.github.nktogo.apicompatibility.runner.InvoiceTeamRunnerV2.Spec;

/** Durable full v2 team execution and unreviewed comparison export under one fixed-six trial. */
public final class InvoiceTeamComparisonV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID, FINAL = "invoice-final-reviewer", COORDINATOR = "invoice-coordinator";
    private final Path state, root;
    private final InvoiceComparisonInputs.Bundle inputs;
    private final CoordinationRuntime runtime;
    public InvoiceTeamComparisonV2(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, InvoiceComparisonInputs.open(inputs, expected), CoordinationRuntime.forInvoiceReconciliationV2(state, inputs, expected));
    }
    InvoiceTeamComparisonV2(Path state, InvoiceComparisonInputs.Bundle inputs, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.inputs = Objects.requireNonNull(inputs); this.runtime = runtime;
        root = this.state.resolve("invoice-team-comparisons-v2"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException { return InvoiceTeamRunnerV2.reconciliationPolicy(); }
    private InvoiceFollowUpRunnerV2 stages() throws IOException { return InvoiceFollowUpRunnerV2.forReconciliation(state, inputs, runtime); }
    private InvoiceTeamRunnerV2 actions() throws IOException { return InvoiceTeamRunnerV2.forReconciliation(state, inputs, runtime); }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        String id = stages().register(bundle, definition, slot); Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", record(fresh(id), "spec.json")); publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec)), "Changed incomplete v2 team result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed v2 full-team implementation; register a new trial");
                var stages = stages(); var source = exists(run, "source.json") ? stages.retained(id) : stages.run(id); if (active(source)) return source;
                retain(run, "source.json", Map.of("resultSha256", hash(fresh(id), "result.json")));
                var contract = contract(spec, source); retain(run, "evidence.json", contract.evidence());
                var prior = JSON.fromJson(source.get("stageReservations"), CoordinationTeamBudget.Reservations.class);
                if (!exists(run, "reconcile-turn-1.json") && !exists(run, "reconciliation.json"))
                    CaseInputs.require(runtime.snapshot(id).getAsJsonObject("usage").get("reservedCalls").getAsInt() == prior.total(), "Unexpected reservations before final v2 stage");
                if (!reconcile(run, spec, contract, prior, exists(run, "reconciliation.json"))) return json(Map.of("status", "ACTIVE", "runId", id));
                var outcome = record(run, "reconciliation.json");
                if (!outcome.get("accepted").isJsonNull()) retain(run, "dossier.json", dossier(spec, outcome.getAsJsonObject("accepted")));
                publish(run, "terminal.json", terminal(run, spec));
            }
            var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.equals(terminal(run, spec)), "Changed terminal v2 team publication");
            try { runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString())); }
            catch (CoordinationRuntime.Stop e) { if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id)); throw e; }
            publish(run, "result.json", result(run, spec)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private InvoiceTeamReconciliationV2 contract(Spec spec, JsonObject source) throws IOException {
        var originalSource = source.getAsJsonObject("originalsAndReview"); var review = originalSource.getAsJsonObject("coordinatorReview");
        var originals = originalSource.getAsJsonArray("workers").asList().stream().map(v -> InvoiceTeamReviewV2.retainedWorker(v.getAsJsonObject())).toList();
        var decisions = new ArrayList<JsonObject>();
        for (String file : originalSource.getAsJsonObject("artifactSha256").keySet()) if (file.matches("event-[1-9][0-9]{0,2}-invoice-coordinator\\.json")) {
            var event = record(prefix(spec.runId()), file);
            if (!event.has("stopReason") && event.has("decision") && event.getAsJsonObject("decision").get("action").getAsString().equals("finish")) decisions.add(event.getAsJsonObject("decision"));
        }
        boolean completed = review.get("status").getAsString().equals("COMPLETED");
        CaseInputs.require(decisions.size() == (completed ? 1 : 0), "Changed authenticated coordinator v2 finish");
        var metadata = exists(prefix(spec.runId()), "metadata-" + COORDINATOR + ".json") ? record(prefix(spec.runId()), "metadata-" + COORDINATOR + ".json") : null;
        var history = new InvoiceTeamReconciliationV2.ReviewResult(InvoiceTeamReviewV2.WorkerStatus.valueOf(review.get("status").getAsString()), review.get("stopReason").getAsString(),
                completed ? decisions.getFirst() : null, metadata, review.getAsJsonArray("pages").asList().stream().map(JsonElement::getAsJsonObject).toList());
        return new InvoiceTeamReconciliationV2(inputs, FINAL, originals, COORDINATOR, history, source.getAsJsonObject("fresh").getAsJsonArray("workers"));
    }
    private boolean reconcile(Path run, Spec spec, InvoiceTeamReconciliationV2 contract, CoordinationTeamBudget.Reservations prior, boolean recordedOnly) throws IOException {
        if (recordedOnly) preflight(run, record(run, "reconciliation.json"));
        var pages = new ArrayList<JsonObject>(); int used = 0; String status = "FAILED", reason = "STAGE_CALL_BUDGET_EXHAUSTED"; JsonObject decision = null;
        var allocation = CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()); var actions = actions();
        for (int turn = 1; ; turn++) {
            var reserved = reserved(prior, used); int remaining = allocation.remaining(CoordinationTeamBudget.Stage.RECONCILIATION, reserved); if (remaining == 0) break;
            String step = "reconcile-turn-" + turn + ".json";
            if (recordedOnly) CaseInputs.require(exists(run, step) && exists(run, "event-" + turn + "-" + FINAL + "-receipt.json"), "Missing sealed final v2 event");
            retain(run, step, Map.of("turn", turn, "remaining", remaining, "evidenceSha256", hash(run, "evidence.json"), "sourceSha256", hash(run, "source.json"), "specSha256", hash(run, "spec.json")));
            var event = actions.action(run, spec, FINAL, turn, step, () -> request(run, spec, contract, pages, reserved), InvoiceReconciliationDecisionV2::parse,
                    (d, bootstrap) -> contract.accept(d, bootstrap, pages), new InvoiceReadiness.Subject(FINAL, inputs.identity(), InvoiceReadiness.Scope.JOINT), pages);
            if (active(event)) return false; if (event.get("callReserved").getAsBoolean()) used++;
            if (event.has("stopReason")) { status = event.get("status").getAsString(); reason = event.get("stopReason").getAsString(); break; }
            if (event.has("page")) pages.add(event.getAsJsonObject("page")); else { decision = event.getAsJsonObject("decision"); status = "COMPLETED"; reason = "NONE"; break; }
        }
        var metadata = exists(run, "metadata-" + FINAL + ".json") ? record(run, "metadata-" + FINAL + ".json") : null;
        if (metadata != null) actions.verifyMetadata(spec, FINAL, metadata);
        var outcome = json(Map.of("status", status, "stopReason", reason, "reservedCalls", used, "pages", pages, "sourceSha256", hash(run, "source.json"), "evidenceSha256", hash(run, "evidence.json"), "finalArtifactSha256", finalArtifacts(run)));
        outcome.add("bootstrap", metadata == null ? JsonNull.INSTANCE : metadata); outcome.add("decision", decision == null ? JsonNull.INSTANCE : decision);
        outcome.add("accepted", decision == null ? JsonNull.INSTANCE : contract.accept(decision, metadata, pages));
        if (exists(run, "reconciliation.json")) outcome.add("runtimeArtifactSha256", record(run, "reconciliation.json").get("runtimeArtifactSha256"));
        else outcome.add("runtimeArtifactSha256", runtime.snapshot(spec.runId()).get("artifactSha256"));
        retain(run, "reconciliation.json", outcome); retain(run, "reconciliation-receipt.json", Map.of("outcomeSha256", hash(run, "reconciliation.json"), "specSha256", hash(run, "spec.json"))); return true;
    }
    private ModelCallBroker.Request request(Path run, Spec spec, InvoiceTeamReconciliationV2 contract, List<JsonObject> pages, CoordinationTeamBudget.Reservations reserved) {
        try { return new ModelCallBroker.Request(spec.definition().model(), contract.context(record(run, "metadata-" + FINAL + ".json"), pages,
                CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()), reserved, spec.definition().limits().inputBytesPerCall()), spec.definition().limits().outputTokensPerCall(), InvoiceReconciliationDecisionV2.PROTOCOL); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static CoordinationTeamBudget.Reservations reserved(CoordinationTeamBudget.Reservations prior, int used) {
        return new CoordinationTeamBudget.Reservations(prior.investigation(), prior.review(), prior.followUp(), used);
    }
    private void preflight(Path run, JsonObject outcome) throws IOException {
        CaseInputs.require(outcome.get("finalArtifactSha256").equals(JSON.toJsonTree(finalArtifacts(run))), "Changed sealed final v2 artifacts");
        historical(outcome.getAsJsonObject("runtimeArtifactSha256"), runtime.snapshot(run.getFileName().toString()).getAsJsonObject("artifactSha256"));
    }
    private JsonObject terminal(Path run, Spec spec) throws IOException {
        var source = stages().retained(spec.runId());
        CaseInputs.require(record(run, "source.json").equals(json(Map.of("resultSha256", hash(fresh(spec.runId()), "result.json")))), "Changed final v2 source pointer");
        var contract = contract(spec, source); CaseInputs.require(record(run, "evidence.json").equals(contract.evidence()), "Changed final v2 source evidence");
        var prior = JSON.fromJson(source.get("stageReservations"), CoordinationTeamBudget.Reservations.class);
        CaseInputs.require(record(run, "reconciliation-receipt.json").equals(json(Map.of("outcomeSha256", hash(run, "reconciliation.json"), "specSha256", hash(run, "spec.json")))), "Missing or changed final v2 outcome receipt");
        CaseInputs.require(reconcile(run, spec, contract, prior, true), "Incomplete sealed final v2 history"); var outcome = record(run, "reconciliation.json");
        CaseInputs.require(record(run, "reconciliation-receipt.json").equals(json(Map.of("outcomeSha256", hash(run, "reconciliation.json"), "specSha256", hash(run, "spec.json")))), "Changed final v2 outcome receipt");
        if (!outcome.get("accepted").isJsonNull()) CaseInputs.require(record(run, "dossier.json").equals(dossier(spec, outcome.getAsJsonObject("accepted"))), "Changed final v2 dossier");
        else CaseInputs.require(!exists(run, "dossier.json"), "Stopped final v2 stage cannot provide a dossier");
        var counts = reserved(prior, outcome.get("reservedCalls").getAsInt());
        CoordinationTeamBudget.forCeiling(spec.definition().limits().modelCalls()).remaining(CoordinationTeamBudget.Stage.RECONCILIATION, counts);
        CaseInputs.require(runtime.snapshot(spec.runId()).getAsJsonObject("usage").get("reservedCalls").getAsInt() == counts.total(), "Unmatched inclusive v2 team reservations");
        var statuses = new ArrayList<String>(); var originalSource = source.getAsJsonObject("originalsAndReview");
        originalSource.getAsJsonArray("workers").forEach(w -> statuses.add(w.getAsJsonObject().get("status").getAsString()));
        statuses.add(originalSource.getAsJsonObject("coordinatorReview").get("status").getAsString());
        source.getAsJsonObject("fresh").getAsJsonArray("workers").forEach(w -> statuses.add(w.getAsJsonObject().get("status").getAsString())); statuses.add(outcome.get("status").getAsString());
        String status = statuses.contains("UNKNOWN_REMOTE_OUTCOME") ? "UNKNOWN_REMOTE_OUTCOME" : statuses.contains("CANCELLED") ? "CANCELLED" : statuses.stream().allMatch("COMPLETED"::equals) ? "COMPLETED" : "FAILED";
        return json(Map.of("status", status, "stageReservations", counts, "sourceResultIdentity", new ContentDigest(hash(fresh(spec.runId()), "result.json")), "artifactSha256", artifacts(run)));
    }
    private static JsonObject dossier(Spec spec, JsonObject accepted) {
        var dossier = accepted.deepCopy(); dossier.addProperty("caseId", CASE); dossier.addProperty("bundleIdentity", spec.bundleId());
        dossier.addProperty("authority", "final-v2-model-advice-not-semantic-acceptance-or-release-approval"); return dossier;
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var value = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))) && value.equals(result(run, spec)), "Changed or incomplete full v2 team result"); return value;
    }
    private JsonObject result(Path run, Spec spec) throws IOException {
        var trace = runtime.retained(spec.runId()); var terminal = record(run, "terminal.json");
        CaseInputs.require(terminal.get("status").equals(trace.get("status")) && terminal.equals(terminal(run, spec)), "Changed terminal v2 runtime binding");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json"));
        var output = exists(run, "dossier.json") ? new CoordinationTrial.CaseOutput(CASE, inputs.identity(), CoordinationTrial.Response.valueOf(record(run, "dossier.json").getAsJsonObject("decision").get("response").getAsString()), new ContentDigest(hash(run, "dossier.json")), null)
                : new CoordinationTrial.CaseOutput(CASE, inputs.identity(), CoordinationTrial.Response.NOT_PROVIDED, null, null);
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(), CoordinationTrial.Status.valueOf(trace.get("status").getAsString()),
                new ContentDigest(hash(parent(spec.runId()), "result.json")), JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), List.of(output));
        return json(Map.of("formatVersion", 2, "runId", spec.runId(), "trial", trial, "artifactSha256", hashes, "stageReservations", terminal.get("stageReservations"), "evidence", record(run, "evidence.json"),
                "reconciliation", record(run, "reconciliation.json"), "sourceResultIdentity", terminal.get("sourceResultIdentity"), "qualityReview", "NOT_PERFORMED", "authority", "full-v2-team-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 2 && spec.runId().equals(run.getFileName().toString()) && inputs.identity().sha256().equals(spec.bundleId())
                && InvoiceDecisionV2.taskIdentity().equals(spec.publicTaskSha256()) && tree.equals(record(fresh(spec.runId()), "spec.json")) && tree.equals(record(prefix(spec.runId()), "spec.json"))
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(hash(parent(spec.runId()), "spec.json"))
                && spec.slot().arm() == Arm.COORDINATED_TEAM && spec.policy().parallelCalls() == 2 && spec.policy().maxReads() == 100 && spec.policy().participants().equals(policy().participants()), "Changed full v2 registration or fixed membership");
        runtime.snapshot(spec.runId()); var files = artifacts(run);
        if (files.keySet().stream().anyMatch(n -> n.startsWith("reconcile-turn-") || n.startsWith("metadata-") || n.startsWith("request-") || n.startsWith("event-") || n.startsWith("reconciliation") || n.equals("dossier.json") || n.equals("evidence.json")) || exists(run, "terminal.json"))
            CaseInputs.require(exists(run, "source.json") && exists(run, "evidence.json"), "Missing published final v2 source barrier");
        if (exists(run, "reconciliation-receipt.json")) CaseInputs.require(exists(run, "reconciliation.json"), "Missing sealed final v2 outcome");
        return spec;
    }
    private static void historical(JsonObject old, JsonObject current) {
        CaseInputs.require(old != null && current != null, "Missing final v2 historical inventory");
        for (var entry : old.entrySet()) CaseInputs.require(entry.getValue().equals(current.get(entry.getKey())), "Changed sealed final v2 runtime artifact");
    }
    private static Map<String, String> finalArtifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); for (var entry : artifacts(run).entrySet()) if (entry.getKey().startsWith("reconcile-turn-") || entry.getKey().startsWith("metadata-") || entry.getKey().startsWith("request-") || entry.getKey().startsWith("event-")) hashes.put(entry.getKey(), entry.getValue()); return hashes;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>(); try (var paths = Files.list(run)) { for (var path : paths.toList()) {
            String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
            CaseInputs.require(Set.of("spec.json", "registration.json", "source.json", "evidence.json", "reconciliation.json", "reconciliation-receipt.json", "dossier.json", "metadata-invoice-final-reviewer.json").contains(name)
                    || name.matches("reconcile-turn-[1-9][0-9]{0,2}\\.json") || name.matches("(?:request|event)-[1-9][0-9]{0,2}-invoice-final-reviewer(?:-receipt)?\\.json"), "Unexpected full v2 team artifact"); hashes.put(name, hash(run, name));
        } } return hashes;
    }
    private Path parent(String id) { return state.resolve("invoice-team-reconciliation-v2-trials/" + id); }
    private Path prefix(String id) { return state.resolve("invoice-team-reconciliation-v2-prefix-runs/" + id); }
    private Path fresh(String id) { return state.resolve("invoice-team-reconciliation-v2-fresh-runs/" + id); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid full v2 reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var base = InvoiceSingleAgentV2.checkDefinition(bundle); var implementations = new EnumMap<>(base.implementations()); implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("full-invoice-team-v2-check", base.origin(), base.model(), digest("unreviewed-full-invoice-team-v2-check"), implementations, base.workloads(), 1, base.limits());
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        CaseInputs.require(args.length >= 4, "Invoice inputs and independently retained identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceTeamComparisonV2(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid full v2 check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> { CaseInputs.require(args.length == 7, "Invalid full v2 registration arguments"); Path path = Path.of(args[4]).toAbsolutePath(); var tree = record(path.getParent(), path.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid full v2 plan shape"); System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.COORDINATED_TEAM))); return; }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid full v2 action arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid full v2 action");
        }
        if (active(result)) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        System.out.println("Status: " + result.getAsJsonObject("trial").get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Usage: " + JSON.toJson(result.getAsJsonObject("trial").get("usage"))); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
