package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Deterministic comparison arm using only the common retained-evidence runtime. */
public final class CoordinationDiagnostics {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}

    public CoordinationDiagnostics(Path state) throws IOException { this(state, new CoordinationRuntime(state)); }
    CoordinationDiagnostics(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("coordination-diagnostics"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    /** The exact compiled application, JSON library and JDK are bound before work starts. */
    public static CoordinationRuntime.Policy policy() throws IOException {
        return new CoordinationRuntime.Policy(1, 100, new ContentDigest(RepairVerification.harness()), digest("no-model-prompts-v1"),
                digest("shared-captured-aliases-read-only-v1"), digest("fixed-all-inputs-once-per-case-v1"), "complete-diagnostic-dossier",
                List.of(new CoordinationRuntime.Participant("diagnostics", Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID), Set.of())));
    }
    public String register(String bundleId, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.DETERMINISTIC, "Diagnostics require the deterministic arm");
        var policy = policy(); String id = runtime.register(bundleId, definition, slot, policy);
        Path run = Files.createDirectory(root.resolve(id));
        publish(run, "spec.json", new Spec(1, id, bundleId, definition, slot, policy, runtimeHash(id, "spec.json")));
        publish(run, "registration.json", Map.of("specSha256", hash(run, "spec.json")));
        return id;
    }
    public JsonObject run(String id) throws IOException {
        Path run = directory(id); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        if (lease == null) throw new CoordinationRuntime.Stop("RUN_ACTIVE");
        try (lease) {
            var spec = spec(run);
            if (exists(run, "result.json")) {
                if (!exists(run, "receipt.json")) {
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete diagnostic result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            String stopReason = "NONE";
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed deterministic implementation; use a new trial");
                try {
                    runtime.start(id);
                    int index = 0;
                    for (var task : workload(spec).cases()) {
                        String output = task.caseId() + ".json";
                        var aliases = new TreeSet<>(task.caseId().equals(CaseInputs.CASE_ID) ? InvestigationTools.INPUTS.keySet() : SubscriptionInvestigationTools.INPUTS.keySet());
                        aliases.addAll(Set.of("description", "execution")); var artifacts = new TreeMap<String, byte[]>();
                        for (String alias : aliases) {
                            var read = runtime.read(id, "diagnostics", "case-" + index + "-" + alias, task.caseId(), alias);
                            artifacts.put(alias, read.get("content").getAsString().getBytes(StandardCharsets.UTF_8));
                        }
                        var diagnostic = CoordinationDiagnosticRules.diagnose(task.caseId(), artifacts);
                        diagnostic.add("inputIdentity", JSON.toJsonTree(task.inputIdentity()));
                        runtime.start(id); // Computation cannot publish a newly accepted output after its whole-trial deadline.
                        if (exists(run, output)) CaseInputs.require(record(run, output).equals(diagnostic), "Changed partial diagnostics");
                        else publish(run, output, diagnostic);
                        index++;
                    }
                    runtime.start(id);
                } catch (CoordinationRuntime.Stop e) { stopReason = e.getMessage(); }
                publish(run, "terminal.json", Map.of("status", stopReason.equals("NONE") ? "COMPLETED" : stopReason.equals("TRIAL_REVOKED") ? "CANCELLED" : "FAILED",
                        "stopReason", stopReason, "outputSha256", outputHashes(run, spec)));
            }
            var terminal = record(run, "terminal.json");
            var trace = runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString()));
            CaseInputs.require(terminal.get("status").equals(trace.get("status")), "Changed deterministic terminal disposition");
            var result = result(run, spec, trace);
            publish(run, "result.json", result); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
            return retained(id);
        }
    }
    /** Read-only historical inspection and an unreviewed comparison record; no new participant operation. */
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete diagnostic result");
        for (var entry : result.getAsJsonObject("artifactSha256").entrySet()) CaseInputs.require(entry.getValue().getAsString().equals(hash(run, entry.getKey())), "Changed diagnostic artifact");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived diagnostic record");
        return result;
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        CaseInputs.require(record(run, "terminal.json").get("status").equals(trace.get("status")), "Changed retained terminal disposition");
        CaseInputs.require(record(run, "terminal.json").get("outputSha256").equals(JSON.toJsonTree(outputHashes(run, spec))), "Changed selected terminal outputs");
        var hashes = new TreeMap<String, String>();
        for (String name : List.of("spec.json", "registration.json", "terminal.json")) hashes.put(name, hash(run, name));
        var outputs = new ArrayList<CoordinationTrial.CaseOutput>();
        for (var task : workload(spec).cases()) {
            String file = task.caseId() + ".json";
            if (exists(run, file)) {
                var value = record(run, file); String sha = hash(run, file); hashes.put(file, sha);
                CaseInputs.require(task.caseId().equals(value.get("caseId").getAsString()) && JSON.toJsonTree(task.inputIdentity()).equals(value.get("inputIdentity")), "Changed diagnostic input binding");
                var response = CoordinationTrial.Response.valueOf(value.get("response").getAsString());
                CaseInputs.require(response != CoordinationTrial.Response.NOT_PROVIDED, "Invalid diagnostic disposition");
                outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), response, new ContentDigest(sha), null));
            } else outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.NOT_PROVIDED, null, null));
        }
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), null,
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")),
                JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), outputs);
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "stopReason", record(run, "terminal.json").get("stopReason").getAsString(),
                "trial", trial, "qualityReview", "NOT_PERFORMED", "authority", "deterministic-diagnostics-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json"))))
                && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json")), "Changed diagnostic registration");
        runtime.snapshot(spec.runId()); // Verify host grant, captured inputs and trace integrity.
        var host = CaseInputs.object(ConsumerTasks.bytes(state.resolve("coordination-trials/" + spec.runId()), "spec.json"));
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy()))
                && spec.slot().arm() == Arm.DETERMINISTIC, "Changed runtime binding");
        return spec;
    }
    private static Workload workload(Spec spec) { return spec.definition().workloads().stream().filter(w -> w.workloadId().equals(spec.slot().workloadId())).findFirst().orElseThrow(); }
    private static Map<String, String> outputHashes(Path run, Spec spec) throws IOException {
        var hashes = new TreeMap<String, String>();
        for (var task : workload(spec).cases()) { String name = task.caseId() + ".json"; if (exists(run, name)) hashes.put(name, hash(run, name)); }
        return hashes;
    }
    private String runtimeHash(String id, String name) throws IOException { return hash(state.resolve("coordination-trials/" + id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid diagnostic trial reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean exists(Path root, String name) { return Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, name)); }
    private static JsonObject record(Path root, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static void publish(Path root, String name, Object value) throws IOException {
        CaseInputs.require(!exists(root, name) && JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length <= 1048576, "Existing or oversized diagnostic artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = ByteBuffer.wrap((JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
        }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(Path state, String bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class);
        for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-adapter-check-placeholder-" + arm.name()));
        implementations.put(Arm.DETERMINISTIC, CoordinationRuntime.implementationIdentity(policy()));
        var tasks = new CoordinationInputs(state).identities(bundle).entrySet().stream().map(e -> new CaseTask(e.getKey(), e.getValue(), ExpectedResponse.ANSWER, List.of("observed-regressions"))).toList();
        return new Definition("offline-diagnostic-adapter-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-adapter-check"), implementations,
                List.of(new Workload("two-case", tasks)), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: CoordinationDiagnostics policy STATE | check STATE BUNDLE | register STATE BUNDLE PLAN WORKLOAD REPETITION | run/read STATE TRIAL");
        Path state = Path.of(args[1]);
        if (args[0].equals("policy") && args.length == 2) { System.out.println(JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        var diagnostics = new CoordinationDiagnostics(state); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 3, "Invalid check arguments"); id = diagnostics.register(args[2], checkDefinition(state, args[2]), new Slot("two-case", 1, Arm.DETERMINISTIC)); System.out.println("Trial: " + id); result = diagnostics.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 6, "Invalid registration arguments"); Path planPath = Path.of(args[3]).toAbsolutePath();
                var tree = record(planPath.getParent(), planPath.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid comparison plan shape");
                System.out.println(diagnostics.register(args[2], plan.definition(), new Slot(args[4], Integer.parseInt(args[5]), Arm.DETERMINISTIC))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 3, "Invalid diagnostic action arguments"); id = args[2]; result = args[0].equals("run") ? diagnostics.run(id) : diagnostics.retained(id); }
            default -> throw new IllegalArgumentException("Invalid diagnostic action");
        }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Model calls: " + trial.getAsJsonObject("usage").get("reservedCalls").getAsLong() + "; new case executions: " + trial.getAsJsonObject("usage").get("caseExecutions").getAsLong());
        System.out.println("Report: " + diagnostics.directory(id).resolve("result.json"));
    }
}
