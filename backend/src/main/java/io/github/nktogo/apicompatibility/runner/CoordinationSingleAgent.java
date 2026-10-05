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

/** One adaptive context for both cases; every action passes through the shared host runtime. */
public final class CoordinationSingleAgent {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String PARTICIPANT = "single-agent";
    private static final int CONTENT_WINDOW = 6;
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    public CoordinationSingleAgent(Path state) throws IOException { this(state, new CoordinationRuntime(state)); }
    CoordinationSingleAgent(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("coordination-single-agent"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        return new CoordinationRuntime.Policy(1, 100, new ContentDigest(RepairVerification.harness()),
                digest(BundleInvestigationDecision.INSTRUCTIONS + JSON.toJson(canonical(BundleInvestigationDecision.schema()))),
                digest("captured-only-utf16-pages-4000-same-case-citations-complete-required-reads-v1"),
                digest("single-both-cases-all-read-metadata-latest-six-contents-oldest-first-byte-trim-v1"), "complete-final-two-case-dossier",
                List.of(new CoordinationRuntime.Participant(PARTICIPANT, Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID), Set.of(BundleInvestigationDecision.PROTOCOL))));
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.SINGLE_AGENT, "Single agent requires its own arm");
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
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete single-agent result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed single-agent implementation; use a new trial");
                String reason = "CALL_BUDGET_EXHAUSTED"; var status = CoordinationTrial.Status.FAILED; var pages = new ArrayList<JsonObject>();
                try {
                    runtime.start(id);
                    for (int turn = 1; turn <= spec.definition().limits().modelCalls(); turn++) {
                        String input = context(workload(spec), pages, spec.definition().limits().modelCalls() - turn + 1, spec.definition().limits().inputBytesPerCall());
                        var request = new ModelCallBroker.Request(spec.definition().model(), input, spec.definition().limits().outputTokensPerCall(), BundleInvestigationDecision.PROTOCOL);
                        retain(run, "request-" + turn + ".json", request);
                        var response = runtime.call(id, PARTICIPANT, "decision-" + turn, request);
                        String modelStatus = response.get("status").getAsString();
                        if (modelStatus.equals("ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id));
                        if (!modelStatus.equals("COMPLETED")) { reason = "MODEL_" + modelStatus; break; }
                        JsonObject decision;
                        try { decision = BundleInvestigationDecision.parse(response.get("outputText").getAsString()); }
                        catch (RuntimeException e) { reason = "INVALID_MODEL_DECISION"; break; }
                        if (decision.get("action").getAsString().equals("read_evidence")) {
                            JsonObject page;
                            try { page = page(spec, turn, decision); }
                            catch (IllegalArgumentException e) { reason = "INVALID_EVIDENCE_READ"; break; }
                            retain(run, "event-" + turn + ".json", Map.of("turn", turn, "decision", decision, "page", page)); pages.add(page);
                        } else {
                            if (!supported(decision, pages)) { reason = "UNSUPPORTED_DOSSIER"; break; }
                            retain(run, "event-" + turn + ".json", Map.of("turn", turn, "decision", decision));
                            runtime.start(id);
                            for (var item : decision.getAsJsonArray("cases")) {
                                var dossier = item.getAsJsonObject().deepCopy(); String caseId = dossier.get("caseId").getAsString();
                                dossier.add("inputIdentity", JSON.toJsonTree(workload(spec).cases().stream().filter(t -> t.caseId().equals(caseId)).findFirst().orElseThrow().inputIdentity()));
                                dossier.addProperty("selectedDecision", "event-" + turn); dossier.addProperty("authority", "model-advice-not-semantic-acceptance");
                                retain(run, caseId + ".json", dossier);
                            }
                            runtime.start(id); reason = "NONE"; status = CoordinationTrial.Status.COMPLETED; break;
                        }
                    }
                } catch (CoordinationRuntime.Stop e) {
                    if (e.getMessage().equals("RUN_ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id));
                    reason = e.getMessage(); status = reason.equals("TRIAL_REVOKED") ? CoordinationTrial.Status.CANCELLED : CoordinationTrial.Status.FAILED;
                }
                if (status != CoordinationTrial.Status.CANCELLED && runtime.snapshot(id).getAsJsonObject("usage").get("unresolvedUsageSlots").getAsLong() > 0)
                    status = CoordinationTrial.Status.UNKNOWN_REMOTE_OUTCOME;
                publish(run, "terminal.json", Map.of("status", status, "stopReason", reason, "artifactSha256", artifacts(run)));
            }
            var terminal = record(run, "terminal.json"); var trace = runtime.finish(id, CoordinationTrial.Status.valueOf(terminal.get("status").getAsString()));
            publish(run, "result.json", result(run, spec, trace)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
            return retained(id);
        }
    }
    private JsonObject page(Spec spec, int turn, JsonObject decision) throws IOException {
        String caseId = decision.get("caseId").getAsString(), alias = decision.get("inputId").getAsString();
        var value = runtime.read(spec.runId(), PARTICIPANT, "read-" + turn, caseId, alias); String full = value.get("content").getAsString();
        int offset = decision.get("offset").getAsInt(); CaseInputs.require(offset <= full.length(), "Page offset beyond captured input");
        int end = Math.min(full.length(), offset + decision.get("maxCharacters").getAsInt()); String content = full.substring(offset, end);
        return json(Map.of("pageId", "read-" + turn, "caseId", caseId, "inputId", alias, "artifactSha256", value.get("sha256").getAsString(),
                "pageSha256", CaseInputs.hash(content.getBytes(StandardCharsets.UTF_8)), "offset", offset, "nextOffset", end, "totalCharacters", full.length(),
                "content", content, "contentTrust", "untrusted-data"));
    }
    /** One shared context, with deterministic disclosure. No generated summaries or hidden extra model calls. */
    static String context(Workload workload, List<JsonObject> pages, int remaining, int byteLimit) {
        var metadata = new JsonArray(); var content = new JsonArray();
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i).deepCopy(); page.remove("content"); metadata.add(page);
            if (i >= pages.size() - CONTENT_WINDOW) content.add(pages.get(i));
        }
        var tasks = workload.cases().stream().map(t -> Map.of("caseId", t.caseId(), "inputIdentity", t.inputIdentity(), "availableInputs", BundleInvestigationDecision.aliases(t.caseId()))).toList();
        var envelope = json(Map.of("cases", tasks, "decisionsRemaining", remaining, "readMetadata", metadata, "recentPages", content,
                "evidenceScope", "captured-curated-cases-not-general-provider-certification", "qualityReview", "NOT_PERFORMED"));
        content = envelope.getAsJsonArray("recentPages");
        String input = JSON.toJson(canonical(envelope));
        while (input.getBytes(StandardCharsets.UTF_8).length > byteLimit && !content.isEmpty()) { content.remove(0); input = JSON.toJson(canonical(envelope)); }
        if (input.getBytes(StandardCharsets.UTF_8).length > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED");
        return input;
    }
    static boolean supported(JsonObject decision, List<JsonObject> pages) {
        var byId = new HashMap<String, JsonObject>(); pages.forEach(p -> byId.put(p.get("pageId").getAsString(), p));
        for (var item : decision.getAsJsonArray("cases")) {
            var dossier = item.getAsJsonObject(); String caseId = dossier.get("caseId").getAsString();
            if (!citationsMatch(dossier.getAsJsonArray("summaryCitations"), caseId, byId)) return false;
            for (var value : dossier.getAsJsonArray("findings")) if (!citationsMatch(value.getAsJsonObject().getAsJsonArray("citations"), caseId, byId)) return false;
            if (dossier.get("response").getAsString().equals("ANSWERED") && (dossier.getAsJsonArray("summaryCitations").isEmpty()
                    || dossier.getAsJsonArray("findings").isEmpty() || !complete(pages, caseId, "baseline-source") || !complete(pages, caseId, "execution"))) return false;
        }
        return true;
    }
    private static boolean citationsMatch(JsonArray citations, String caseId, Map<String, JsonObject> pages) {
        for (var id : citations) if (!pages.containsKey(id.getAsString()) || !caseId.equals(pages.get(id.getAsString()).get("caseId").getAsString())) return false;
        return true;
    }
    static boolean complete(List<JsonObject> pages, String caseId, String alias) {
        var selected = pages.stream().filter(p -> caseId.equals(p.get("caseId").getAsString()) && alias.equals(p.get("inputId").getAsString()))
                .sorted(Comparator.comparingInt(p -> p.get("offset").getAsInt())).toList();
        if (selected.isEmpty()) return false; int end = 0, total = selected.get(0).get("totalCharacters").getAsInt();
        for (var page : selected) { if (page.get("offset").getAsInt() > end) return false; end = Math.max(end, page.get("nextOffset").getAsInt()); }
        return end == total;
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete single-agent result");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived single-agent record"); return result;
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.get("status").equals(trace.get("status"))
                && terminal.get("artifactSha256").equals(JSON.toJsonTree(artifacts(run))), "Changed selected single-agent artifacts");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json")); var outputs = new ArrayList<CoordinationTrial.CaseOutput>();
        for (var task : workload(spec).cases()) {
            String name = task.caseId() + ".json";
            if (!exists(run, name)) outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.NOT_PROVIDED, null, null));
            else {
                var dossier = record(run, name); CaseInputs.require(dossier.get("caseId").getAsString().equals(task.caseId()) && dossier.get("inputIdentity").equals(JSON.toJsonTree(task.inputIdentity())), "Changed dossier input binding");
                outputs.add(new CoordinationTrial.CaseOutput(task.caseId(), task.inputIdentity(), CoordinationTrial.Response.valueOf(dossier.get("response").getAsString()), new ContentDigest(hash(run, name)), null));
            }
        }
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(),
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")),
                JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), outputs);
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "stopReason", terminal.get("stopReason").getAsString(),
                "trial", trial, "qualityReview", "NOT_PERFORMED", "authority", "single-agent-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json")), "Changed single-agent registration");
        runtime.snapshot(spec.runId()); var host = record(state.resolve("coordination-trials/" + spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.SINGLE_AGENT, "Changed single-agent runtime binding"); return spec;
    }
    private static Workload workload(Spec spec) { return spec.definition().workloads().stream().filter(w -> w.workloadId().equals(spec.slot().workloadId())).findFirst().orElseThrow(); }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString();
                if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", CaseInputs.CASE_ID + ".json", SubscriptionPeriodCase.CASE_ID + ".json").contains(name)
                        || name.matches("(?:request|event)-[1-9][0-9]{0,2}\\.json"), "Unexpected single-agent artifact"); hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private String runtimeHash(String id, String name) throws IOException { return hash(state.resolve("coordination-trials/" + id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid single-agent reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean exists(Path root, String name) { return Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, name)); }
    private static JsonObject record(Path root, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(k -> sorted.add(k, canonical(value.getAsJsonObject().get(k)))); return sorted;
        }
        if (value.isJsonArray()) { var array = new JsonArray(); value.getAsJsonArray().forEach(v -> array.add(canonical(v))); return array; }
        return value;
    }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static void retain(Path root, String name, Object value) throws IOException {
        if (exists(root, name)) CaseInputs.require(record(root, name).equals(json(value)), "Changed resumed single-agent action"); else publish(root, name, value);
    }
    private static void publish(Path root, String name, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); CaseInputs.require(!exists(root, name) && bytes.length <= 1048576, "Existing or oversized single-agent artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(Path state, String bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-adapter-check-placeholder-" + arm.name()));
        implementations.put(Arm.SINGLE_AGENT, CoordinationRuntime.implementationIdentity(policy()));
        var tasks = new CoordinationInputs(state).identities(bundle).entrySet().stream().map(e -> new CaseTask(e.getKey(), e.getValue(), ExpectedResponse.ANSWER, List.of("observed-regressions"))).toList();
        return new Definition("single-agent-adapter-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-adapter-check"), implementations,
                List.of(new Workload("two-case", tasks)), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: CoordinationSingleAgent policy STATE | check STATE BUNDLE | register STATE BUNDLE PLAN WORKLOAD REPETITION | run/read STATE TRIAL");
        if (args[0].equals("policy") && args.length == 2) { System.out.println(JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        Path state = Path.of(args[1]); var adapter = new CoordinationSingleAgent(state); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 3, "Invalid adapter check arguments"); id = adapter.register(args[2], checkDefinition(state, args[2]), new Slot("two-case", 1, Arm.SINGLE_AGENT)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 6, "Invalid registration arguments"); Path planPath = Path.of(args[3]).toAbsolutePath();
                var tree = record(planPath.getParent(), planPath.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid comparison plan shape"); System.out.println(adapter.register(args[2], plan.definition(), new Slot(args[4], Integer.parseInt(args[5]), Arm.SINGLE_AGENT))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 3, "Invalid adapter action arguments"); id = args[2]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid single-agent action");
        }
        if (result.has("status")) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Model calls: " + trial.getAsJsonObject("usage").get("reservedCalls").getAsLong() + "; new case executions: " + trial.getAsJsonObject("usage").get("caseExecutions").getAsLong());
        System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
