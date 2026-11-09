package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Versioned task binding for the unchanged deterministic invoice rules; no model capability. */
public final class InvoiceDiagnosticsV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    static final String PARTICIPANT = "invoice-diagnostics-v2";
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID;
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String publicTaskSha256, String runtimeSpecSha256) {}
    public InvoiceDiagnosticsV2(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, CoordinationRuntime.forInvoiceNavigation(state, inputs, expected));
    }
    InvoiceDiagnosticsV2(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("invoice-diagnostics-v2"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException { return policy(new ContentDigest(RepairVerification.harness())); }
    private static CoordinationRuntime.Policy policy(ContentDigest adapter) {
        return new CoordinationRuntime.Policy(1, 100, adapter, digest("invoice-deterministic-v2-task:" + InvoiceDecisionV2.taskIdentity()),
                digest(ComparisonEvidence.INVOICE_NAVIGATION_TOOLS), digest("invoice-mandatory-stratum-round-robin-alias-sha256-complete-prefix-v1"),
                "invoice-v2-fixed-recorded-facts-not-semantic-acceptance", List.of(new CoordinationRuntime.Participant(PARTICIPANT, Set.of(CASE), Set.of())));
    }
    private static JsonObject task() {
        return json(Map.of("publicTaskSha256", InvoiceDecisionV2.taskIdentity(), "task", InvoiceDecisionV2.PUBLIC_TASK,
                "authority", "host-public-task-not-evidence-or-permission"));
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.DETERMINISTIC, "Invoice v2 diagnostics require the deterministic arm");
        InvoiceV2Admission.validateDefinition(definition); var policy = policy();
        String id = runtime.register(bundle, definition, slot, policy); Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", new Spec(2, id, bundle, definition, slot, policy, InvoiceDecisionV2.taskIdentity(), runtimeHash(id, "spec.json")));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json"))); publish(run, "task.json", task()); return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) return json(Map.of("status", "ACTIVE", "runId", id));
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete v2 diagnostic result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            audit(run, spec);
            if (!exists(run, "terminal.json")) {
                String reason = "NONE"; var status = CoordinationTrial.Status.COMPLETED;
                // A published, reconstructible dossier is completed work, even if closure was interrupted.
                if (!exists(run, "dossier.json")) {
                    CaseInputs.require(spec.policy().equals(policy()), "Changed invoice v2 diagnostic build; register a new trial");
                    try {
                        var dossier = new Transcript(run, spec, true).walk();
                        runtime.start(id); retain(run, "dossier.json", dossier);
                    } catch (CoordinationRuntime.Stop e) {
                        if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id));
                        reason = e.getMessage(); status = reason.equals("TRIAL_REVOKED") ? CoordinationTrial.Status.CANCELLED : CoordinationTrial.Status.FAILED;
                    }
                }
                audit(run, spec);
                publish(run, "terminal.json", Map.of("status", status, "stopReason", reason, "artifactSha256", artifacts(run)));
            }
            var terminal = record(run, "terminal.json"); var trace = runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString()));
            publish(run, "result.json", result(run, spec, trace)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete v2 diagnostics");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived v2 diagnostics"); return result;
    }
    private int audit(Path run, Spec spec) throws IOException {
        var transcript = new Transcript(run, spec, false); var derived = transcript.walk();
        var actual = artifacts(run).keySet().stream().filter(n -> n.equals("metadata.json") || n.matches("(?:catalog|text)-[1-9][0-9]{0,2}\\.json")).collect(java.util.stream.Collectors.toSet());
        CaseInputs.require(actual.equals(transcript.visited), "Non-prefix diagnostic page inventory");
        CaseInputs.require(!exists(run, "selection.json") || transcript.selected, "Unbound diagnostic selection");
        if (exists(run, "dossier.json")) CaseInputs.require(derived != null && derived.equals(record(run, "dossier.json")), "Changed reconstructed v2 diagnostic dossier");
        return transcript.visited.size();
    }
    private final class Transcript {
        private final Path run; private final Spec spec; private final boolean executing;
        private final Set<String> visited = new HashSet<>(); private boolean selected;
        Transcript(Path run, Spec spec, boolean executing) { this.run = run; this.spec = spec; this.executing = executing; }
        JsonObject page(String operation, String filename, String kind, String alias, int offset, int limit) throws IOException {
            if (!executing && !exists(run, filename)) return null;
            var request = new ComparisonEvidence.PageRequest(kind, CASE, alias, offset, limit); JsonObject value;
            if (executing) {
                value = switch (kind) {
                    case "describe" -> runtime.describe(spec.runId(), PARTICIPANT, operation, CASE);
                    case "catalog" -> runtime.catalog(spec.runId(), PARTICIPANT, operation, CASE, offset, limit);
                    case "read_text" -> runtime.readPage(spec.runId(), PARTICIPANT, operation, CASE, alias, offset, limit);
                    default -> throw new IllegalArgumentException("Unsupported diagnostic read");
                };
                retain(run, filename, value);
            } else value = record(run, filename);
            var expected = value.deepCopy(); expected.addProperty("participant", PARTICIPANT); expected.addProperty("operationId", operation);
            expected.addProperty("caseId", CASE); expected.addProperty("bundleIdentity", spec.bundleId()); expected.add("pageRequest", JSON.toJsonTree(request));
            expected.addProperty("contentTrust", "untrusted-data");
            CaseInputs.require(value.equals(expected) && value.equals(record(parent(spec.runId()), "reads/" + digest(PARTICIPANT + ":" + operation).sha256() + ".json")), "Changed owned diagnostic read");
            visited.add(filename); return value.getAsJsonObject("page");
        }
        JsonObject walk() throws IOException {
            var metadata = page("metadata", "metadata.json", "describe", "", 0, 0); if (metadata == null) return null;
            var catalog = new ArrayList<JsonObject>(); int offset = 0, catalogPages = 0;
            do {
                String op = "catalog-" + ++catalogPages; var page = page(op, op + ".json", "catalog", "", offset, 40); if (page == null) return null;
                page.getAsJsonArray("artifacts").forEach(row -> catalog.add(row.getAsJsonObject())); offset = page.get("nextOffset").getAsInt();
            } while (offset < metadata.get("artifactCount").getAsInt());
            var selection = InvoiceDiagnosticRules.select(catalog, spec.policy().maxReads() - catalogPages - 1);
            if (executing) retain(run, "selection.json", selection);
            else { if (!exists(run, "selection.json")) return null; CaseInputs.require(record(run, "selection.json").equals(selection), "Changed fixed diagnostic selection"); }
            selected = true; var contents = new LinkedHashMap<String, String>(); int number = 0;
            for (var item : selection.getAsJsonArray("selected")) {
                var row = item.getAsJsonObject(); String alias = row.get("alias").getAsString(); var text = new StringBuilder(); offset = 0;
                do {
                    String op = "text-" + ++number; var page = page(op, op + ".json", "read_text", alias, offset, 4000); if (page == null) return null;
                    text.append(page.get("content").getAsString()); offset = page.get("nextOffset").getAsInt();
                } while (offset < row.get("totalCharacters").getAsInt());
                contents.put(alias, text.toString());
            }
            var trace = runtime.snapshot(spec.runId()); var usage = JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class);
            CaseInputs.require(usage.toolCalls() == visited.size() && usage.reservedCalls() == 0 && usage.caseExecutions() == 0, "Unexpected deterministic runtime work");
            var dossier = InvoiceDiagnosticRules.diagnose(metadata, selection, contents); dossier.addProperty("formatVersion", 2);
            dossier.addProperty("publicTaskSha256", spec.publicTaskSha256()); dossier.addProperty("authority", "deterministic-recorded-facts-not-semantic-acceptance"); return dossier;
        }
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        int retainedReads = audit(run, spec); var terminal = record(run, "terminal.json"); boolean complete = exists(run, "dossier.json");
        CaseInputs.require(terminal.get("status").equals(trace.get("status")) && terminal.get("artifactSha256").equals(JSON.toJsonTree(artifacts(run)))
                && (terminal.get("status").getAsString().equals("COMPLETED") == complete), "Changed v2 diagnostic terminal inventory");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json"));
        var output = new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), complete
                ? CoordinationTrial.Response.valueOf(record(run, "dossier.json").get("response").getAsString()) : CoordinationTrial.Response.NOT_PROVIDED,
                complete ? new ContentDigest(hash(run, "dossier.json")) : null, null);
        var usage = JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class);
        CaseInputs.require(usage.reservedCalls() == 0 && usage.caseExecutions() == 0 && usage.toolCalls() >= retainedReads, "Unexpected diagnostic accounting");
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), null,
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")), usage, trace.get("elapsedMillis").getAsLong(), List.of(output));
        return json(Map.of("formatVersion", 2, "runId", spec.runId(), "artifactSha256", hashes, "stopReason", terminal.get("stopReason").getAsString(),
                "trial", trial, "qualityReview", "NOT_PERFORMED", "publicTaskSha256", spec.publicTaskSha256(), "readAccounting",
                Map.of("retainedOwnedPages", retainedReads, "runtimeReads", usage.toolCalls(), "unpublishedRuntimeReads", usage.toolCalls() - retainedReads),
                "authority", "invoice-v2-diagnostics-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 2 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json"))
                && spec.publicTaskSha256().equals(InvoiceDecisionV2.taskIdentity()) && record(run, "task.json").equals(task())
                && spec.policy().equals(policy(spec.policy().adapterIdentity())) && spec.slot().arm() == Arm.DETERMINISTIC, "Changed v2 diagnostic registration");
        InvoiceV2Admission.validateDefinition(spec.definition()); runtime.snapshot(spec.runId()); var host = record(parent(spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())), "Changed v2 diagnostic runtime binding"); return spec;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", "task.json", "metadata.json", "selection.json", "dossier.json").contains(name)
                        || name.matches("(?:catalog|text)-[1-9][0-9]{0,2}\\.json"), "Unexpected v2 diagnostic artifact"); hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private Path parent(String id) { return state.resolve("invoice-navigation-trials/" + id); }
    private String runtimeHash(String id, String name) throws IOException { return hash(parent(id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid v2 diagnostic trial reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-invoice-v2-adapter-check-" + arm.name()));
        implementations.put(Arm.DETERMINISTIC, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-diagnostics-v2-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-invoice-v2-adapter-check"), implementations,
                List.of(new Workload("invoice-joint", List.of(new CaseTask(CASE, bundle, ExpectedResponse.ANSWER, List.of("joint-invoice-evidence"))))), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { var policy = policy(); System.out.println(JSON.toJson(Map.of("policy", policy, "implementationIdentity", CoordinationRuntime.implementationIdentity(policy)))); return; }
        CaseInputs.require(args.length >= 4, "Invoice input directory and independent expected identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceDiagnosticsV2(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid v2 diagnostic check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 7, "Invalid v2 diagnostic registration arguments"); Path path = Path.of(args[4]).toAbsolutePath();
                var tree = record(path.getParent(), path.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid v2 diagnostic comparison plan shape");
                System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.DETERMINISTIC))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid v2 diagnostic trial arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid invoice v2 diagnostic action");
        }
        if (result.has("status")) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Usage: " + JSON.toJson(trial.get("usage"))); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
