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
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Deterministic invoice arm; every disclosed page goes through the common read-only runtime. */
public final class InvoiceDiagnostics {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String PARTICIPANT = "invoice-diagnostics", CASE = InvoiceRenewalFixtures.CASE_ID;
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    public InvoiceDiagnostics(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, CoordinationRuntime.forInvoice(state, inputs, expected));
    }
    InvoiceDiagnostics(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("invoice-diagnostics"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        return new CoordinationRuntime.Policy(1, 100, new ContentDigest(RepairVerification.harness()), digest("invoice-no-model-prompts-v1"),
                digest(ComparisonEvidence.INVOICE_TOOLS), digest("invoice-mandatory-stratum-round-robin-alias-sha256-complete-prefix-v1"),
                "complete-fixed-invoice-dossier", List.of(new CoordinationRuntime.Participant(PARTICIPANT, Set.of(CASE), Set.of())));
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.DETERMINISTIC, "Invoice diagnostics require the deterministic arm");
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
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete invoice diagnostic result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed invoice diagnostic implementation; use a new trial");
                String reason = "NONE"; var status = CoordinationTrial.Status.COMPLETED;
                try {
                    var metadata = runtime.describe(id, PARTICIPANT, "metadata", CASE).getAsJsonObject("page"); retain(run, "metadata.json", metadata);
                    var catalog = new ArrayList<JsonObject>(); int offset = 0, catalogPages = 0;
                    do {
                        var page = runtime.catalog(id, PARTICIPANT, "catalog-" + ++catalogPages, CASE, offset, 40).getAsJsonObject("page");
                        retain(run, "catalog-" + catalogPages + ".json", page); page.getAsJsonArray("artifacts").forEach(row -> catalog.add(row.getAsJsonObject()));
                        offset = page.get("nextOffset").getAsInt();
                    } while (offset < metadata.get("artifactCount").getAsInt());
                    var selection = InvoiceDiagnosticRules.select(catalog, spec.policy().maxReads() - catalogPages - 1); retain(run, "selection.json", selection);
                    var contents = new LinkedHashMap<String, String>(); int number = 0;
                    for (var item : selection.getAsJsonArray("selected")) {
                        var selected = item.getAsJsonObject(); String alias = selected.get("alias").getAsString(); var text = new StringBuilder(); offset = 0;
                        do {
                            var page = runtime.readPage(id, PARTICIPANT, "text-" + ++number, CASE, alias, offset, 4000).getAsJsonObject("page");
                            retain(run, "text-" + number + ".json", page); text.append(page.get("content").getAsString()); offset = page.get("nextOffset").getAsInt();
                        } while (offset < selected.get("totalCharacters").getAsInt());
                        contents.put(alias, text.toString());
                    }
                    var dossier = InvoiceDiagnosticRules.diagnose(metadata, selection, contents);
                    dossier.addProperty("authority", "deterministic-recorded-facts-not-semantic-acceptance");
                    runtime.start(id); retain(run, "dossier.json", dossier); runtime.start(id);
                } catch (CoordinationRuntime.Stop e) {
                    if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id));
                    reason = e.getMessage(); status = reason.equals("TRIAL_REVOKED") ? CoordinationTrial.Status.CANCELLED : CoordinationTrial.Status.FAILED;
                }
                publish(run, "terminal.json", Map.of("status", status, "stopReason", reason, "artifactSha256", artifacts(run)));
            }
            var terminal = record(run, "terminal.json"); var trace = runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString()));
            publish(run, "result.json", result(run, spec, trace)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete invoice diagnostics");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived invoice diagnostics"); return result;
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.get("status").equals(trace.get("status"))
                && terminal.get("artifactSha256").equals(JSON.toJsonTree(artifacts(run))), "Changed selected diagnostic artifacts");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json")); CoordinationTrial.CaseOutput output;
        if (!exists(run, "dossier.json")) output = new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), CoordinationTrial.Response.NOT_PROVIDED, null, null);
        else {
            var dossier = record(run, "dossier.json"); CaseInputs.require(dossier.get("caseId").getAsString().equals(CASE) && dossier.get("bundleIdentity").getAsString().equals(spec.bundleId()), "Changed diagnostic input binding");
            output = new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), CoordinationTrial.Response.valueOf(dossier.get("response").getAsString()), new ContentDigest(hash(run, "dossier.json")), null);
        }
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), null,
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")),
                JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), List.of(output));
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "stopReason", terminal.get("stopReason").getAsString(),
                "trial", trial, "qualityReview", "NOT_PERFORMED", "authority", "invoice-diagnostics-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json")), "Changed invoice diagnostic registration");
        runtime.snapshot(spec.runId()); var host = record(state.resolve("invoice-comparison-trials/" + spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.DETERMINISTIC, "Changed diagnostic runtime binding"); return spec;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", "metadata.json", "selection.json", "dossier.json").contains(name)
                        || name.matches("(?:catalog|text)-[1-9][0-9]{0,2}\\.json"), "Unexpected diagnostic artifact"); hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private String runtimeHash(String id, String name) throws IOException { return hash(state.resolve("invoice-comparison-trials/" + id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid diagnostic trial reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean exists(Path root, String name) { return Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, name)); }
    private static JsonObject record(Path root, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static void retain(Path root, String name, Object value) throws IOException {
        if (exists(root, name)) CaseInputs.require(record(root, name).equals(json(value)), "Changed resumed diagnostic action"); else publish(root, name, value);
    }
    private static void publish(Path root, String name, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); CaseInputs.require(!exists(root, name) && bytes.length <= 1048576, "Existing or oversized diagnostic artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-invoice-adapter-check-" + arm.name()));
        implementations.put(Arm.DETERMINISTIC, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-diagnostics-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-invoice-adapter-check"), implementations,
                List.of(new Workload("invoice-joint", List.of(new CaseTask(CASE, bundle, ExpectedResponse.ANSWER, List.of("joint-invoice-evidence"))))), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { System.out.println(JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        CaseInputs.require(args.length >= 4, "Invoice input directory and independent expected identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceDiagnostics(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid diagnostic check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.DETERMINISTIC)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 7, "Invalid diagnostic registration arguments"); Path planPath = Path.of(args[4]).toAbsolutePath();
                var tree = record(planPath.getParent(), planPath.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid diagnostic comparison plan shape");
                System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.DETERMINISTIC))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid diagnostic trial arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid invoice diagnostic action");
        }
        if (result.has("status")) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Usage: " + JSON.toJson(trial.get("usage"))); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
