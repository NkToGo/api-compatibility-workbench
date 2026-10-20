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

/** One adaptive invoice context; common runtime owns every read and model reservation. */
public final class InvoiceSingleAgent {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String PARTICIPANT = "invoice-single-agent", CASE = InvoiceRenewalFixtures.CASE_ID;
    private final Path state, root;
    private final CoordinationRuntime runtime;
    private record Spec(int formatVersion, String runId, String bundleId, Definition definition, Slot slot,
                        CoordinationRuntime.Policy policy, String runtimeSpecSha256) {}
    public InvoiceSingleAgent(Path state, Path inputs, ContentDigest expected) throws IOException {
        this(state, CoordinationRuntime.forInvoiceInvestigation(state, inputs, expected));
    }
    InvoiceSingleAgent(Path state, CoordinationRuntime runtime) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.runtime = runtime;
        root = this.state.resolve("invoice-single-agent"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public static CoordinationRuntime.Policy policy() throws IOException {
        return new CoordinationRuntime.Policy(1, 100, new ContentDigest(RepairVerification.harness()),
                digest(InvoiceInvestigationDecision.INSTRUCTIONS + JSON.toJson(canonical(InvoiceInvestigationDecision.schema()))),
                digest(ComparisonEvidence.INVOICE_TOOLS), digest("invoice-joint-all-read-metadata-latest-six-contents-oldest-first-byte-trim-v1"),
                "complete-final-invoice-dossier", List.of(new CoordinationRuntime.Participant(PARTICIPANT, Set.of(CASE), Set.of(InvoiceInvestigationDecision.PROTOCOL))));
    }
    public String register(String bundle, Definition definition, Slot slot) throws IOException {
        CaseInputs.require(slot.arm() == Arm.SINGLE_AGENT, "Invoice investigator requires its own arm");
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
                    CaseInputs.require(record(run, "result.json").equals(result(run, spec, runtime.retained(id))), "Changed incomplete invoice result");
                    publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json")));
                }
                return retained(id);
            }
            if (!exists(run, "terminal.json")) {
                CaseInputs.require(spec.policy().equals(policy()), "Changed invoice implementation; use a new trial");
                String reason = "CALL_BUDGET_EXHAUSTED"; var status = CoordinationTrial.Status.FAILED; var pages = new ArrayList<JsonObject>();
                try {
                    var metadata = runtime.describe(id, PARTICIPANT, "metadata", CASE).getAsJsonObject("page"); retain(run, "metadata.json", metadata);
                    for (int turn = 1; turn <= spec.definition().limits().modelCalls(); turn++) {
                        var request = new ModelCallBroker.Request(spec.definition().model(), context(metadata, pages,
                                spec.definition().limits().modelCalls() - turn + 1, spec.definition().limits().inputBytesPerCall()),
                                spec.definition().limits().outputTokensPerCall(), InvoiceInvestigationDecision.PROTOCOL);
                        retain(run, "request-" + turn + ".json", request);
                        var response = runtime.call(id, PARTICIPANT, "decision-" + turn, request); String modelStatus = response.get("status").getAsString();
                        if (modelStatus.equals("ACTIVE")) return json(Map.of("status", "ACTIVE", "runId", id));
                        if (!modelStatus.equals("COMPLETED")) { reason = "MODEL_" + modelStatus; break; }
                        JsonObject decision;
                        try { decision = InvoiceInvestigationDecision.parse(response.get("outputText").getAsString()); }
                        catch (RuntimeException e) { reason = "INVALID_MODEL_DECISION"; break; }
                        if (!decision.get("action").getAsString().equals("finish")) {
                            JsonObject page;
                            try { page = page(spec, turn, decision); }
                            catch (IllegalArgumentException e) { reason = "INVALID_EVIDENCE_READ"; break; }
                            retain(run, "event-" + turn + ".json", Map.of("turn", turn, "decision", decision, "page", page)); pages.add(page);
                        } else {
                            if (!supported(decision, pages, metadata)) { reason = "UNSUPPORTED_DOSSIER"; break; }
                            retain(run, "event-" + turn + ".json", Map.of("turn", turn, "decision", decision)); runtime.start(id);
                            var dossier = decision.deepCopy(); dossier.remove("action"); dossier.addProperty("caseId", CASE);
                            dossier.addProperty("bundleIdentity", spec.bundleId()); dossier.addProperty("selectedDecision", "event-" + turn);
                            dossier.addProperty("sourceExecutionCoverageSatisfied", metadata.get("sourceExecutionCoverageSatisfied").getAsBoolean());
                            dossier.addProperty("authority", "model-advice-not-semantic-acceptance"); retain(run, "dossier.json", dossier);
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
            publish(run, "result.json", result(run, spec, trace)); publish(run, "receipt.json", Map.of("resultSha256", hash(run, "result.json"))); return retained(id);
        }
    }
    private JsonObject page(Spec spec, int turn, JsonObject decision) throws IOException {
        String kind = decision.get("action").getAsString(), operation = "read-" + turn;
        var value = kind.equals("catalog") ? runtime.catalog(spec.runId(), PARTICIPANT, operation, CASE, decision.get("offset").getAsInt(), decision.get("limit").getAsInt())
                : runtime.readPage(spec.runId(), PARTICIPANT, operation, CASE, decision.get("alias").getAsString(), decision.get("offset").getAsInt(), decision.get("maxCharacters").getAsInt());
        var page = value.getAsJsonObject("page").deepCopy(); page.addProperty("pageId", operation); page.addProperty("kind", kind);
        page.addProperty("contentTrust", "untrusted-data"); return page;
    }
    static String context(JsonObject description, List<JsonObject> pages, int remaining, int byteLimit) {
        var metadata = new JsonArray(); var content = new JsonArray();
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i).deepCopy(); page.remove("content"); page.remove("artifacts"); metadata.add(page);
            if (i >= pages.size() - 6) content.add(pages.get(i));
        }
        var envelope = json(Map.of("case", description, "decisionsRemaining", remaining, "readMetadata", metadata, "recentPages", content,
                "availableTools", List.of("catalog", "read_text", "finish"), "qualityReview", "NOT_PERFORMED"));
        content = envelope.getAsJsonArray("recentPages"); String input = JSON.toJson(canonical(envelope));
        while (input.getBytes(StandardCharsets.UTF_8).length > byteLimit && !content.isEmpty()) { content.remove(0); input = JSON.toJson(canonical(envelope)); }
        if (input.getBytes(StandardCharsets.UTF_8).length > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED"); return input;
    }
    static boolean supported(JsonObject decision, List<JsonObject> pages, JsonObject metadata) {
        var readIds = new HashSet<String>(); pages.stream().filter(p -> p.get("kind").getAsString().equals("read_text")).forEach(p -> readIds.add(p.get("pageId").getAsString()));
        if (!cited(decision.getAsJsonArray("summaryCitations"), readIds)) return false;
        for (var item : decision.getAsJsonArray("findings")) if (!cited(item.getAsJsonObject().getAsJsonArray("citations"), readIds)) return false;
        if (!decision.get("response").getAsString().equals("ANSWERED")) return true;
        return metadata.get("sourceExecutionCoverageSatisfied").getAsBoolean() && !decision.getAsJsonArray("summaryCitations").isEmpty()
                && !decision.getAsJsonArray("findings").isEmpty() && InvoiceInvestigationDecision.REQUIRED.stream().allMatch(alias -> complete(pages, alias))
                && pages.stream().anyMatch(p -> p.has("alias") && p.get("alias").getAsString().startsWith("business/") && complete(pages, p.get("alias").getAsString()))
                && pages.stream().anyMatch(p -> p.has("alias") && p.get("alias").getAsString().startsWith("order/") && complete(pages, p.get("alias").getAsString()));
    }
    private static boolean cited(JsonArray citations, Set<String> reads) { for (var id : citations) if (!reads.contains(id.getAsString())) return false; return true; }
    static boolean complete(List<JsonObject> pages, String alias) {
        var selected = pages.stream().filter(p -> p.get("kind").getAsString().equals("read_text") && alias.equals(p.get("alias").getAsString()))
                .sorted(Comparator.comparingInt(p -> p.get("offset").getAsInt())).toList();
        if (selected.isEmpty()) return false; int end = 0, total = selected.getFirst().get("totalCharacters").getAsInt();
        for (var page : selected) { if (page.get("totalCharacters").getAsInt() != total || page.get("offset").getAsInt() > end) return false; end = Math.max(end, page.get("nextOffset").getAsInt()); }
        return end == total;
    }
    public JsonObject retained(String id) throws IOException {
        Path run = directory(id); var spec = spec(run); var result = record(run, "result.json");
        CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete invoice result");
        CaseInputs.require(result.equals(result(run, spec, runtime.retained(id))), "Changed derived invoice record"); return result;
    }
    private JsonObject result(Path run, Spec spec, JsonObject trace) throws IOException {
        var terminal = record(run, "terminal.json"); CaseInputs.require(terminal.get("status").equals(trace.get("status"))
                && terminal.get("artifactSha256").equals(JSON.toJsonTree(artifacts(run))), "Changed selected invoice artifacts");
        var hashes = artifacts(run); hashes.put("terminal.json", hash(run, "terminal.json")); CoordinationTrial.CaseOutput output;
        if (!exists(run, "dossier.json")) output = new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), CoordinationTrial.Response.NOT_PROVIDED, null, null);
        else {
            var dossier = record(run, "dossier.json"); CaseInputs.require(dossier.get("caseId").getAsString().equals(CASE) && dossier.get("bundleIdentity").getAsString().equals(spec.bundleId()), "Changed invoice dossier input binding");
            output = new CoordinationTrial.CaseOutput(CASE, new ContentDigest(spec.bundleId()), CoordinationTrial.Response.valueOf(dossier.get("response").getAsString()), new ContentDigest(hash(run, "dossier.json")), null);
        }
        var trial = new CoordinationTrial(spec.slot(), spec.runId(), identity(spec.definition()), CoordinationRuntime.implementationIdentity(spec.policy()), spec.definition().model(),
                CoordinationTrial.Status.valueOf(trace.get("status").getAsString()), new ContentDigest(runtimeHash(spec.runId(), "result.json")),
                JSON.fromJson(trace.get("usage"), CoordinationTrial.Usage.class), trace.get("elapsedMillis").getAsLong(), List.of(output));
        return json(Map.of("formatVersion", 1, "runId", spec.runId(), "artifactSha256", hashes, "stopReason", terminal.get("stopReason").getAsString(),
                "trial", trial, "qualityReview", "NOT_PERFORMED", "authority", "invoice-advice-not-semantic-acceptance-or-routing"));
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && record(run, "registration.json").equals(json(Map.of("specSha256", hash(run, "spec.json")))) && spec.runtimeSpecSha256().equals(runtimeHash(spec.runId(), "spec.json")), "Changed invoice registration");
        runtime.snapshot(spec.runId()); var host = record(state.resolve("invoice-comparison-trials/" + spec.runId()), "spec.json");
        CaseInputs.require(host.get("bundleId").getAsString().equals(spec.bundleId()) && host.get("definition").equals(JSON.toJsonTree(spec.definition()))
                && host.get("slot").equals(JSON.toJsonTree(spec.slot())) && host.get("policy").equals(JSON.toJsonTree(spec.policy())) && spec.slot().arm() == Arm.SINGLE_AGENT, "Changed invoice runtime binding"); return spec;
    }
    private static Map<String, String> artifacts(Path run) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.list(run)) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString(); if (Set.of("owner.lock", "terminal.json", "result.json", "receipt.json").contains(name)) continue;
                CaseInputs.require(Set.of("spec.json", "registration.json", "metadata.json", "dossier.json").contains(name)
                        || name.matches("(?:request|event)-[1-9][0-9]{0,2}\\.json"), "Unexpected invoice investigator artifact"); hashes.put(name, hash(run, name));
            }
        }
        return hashes;
    }
    private String runtimeHash(String id, String name) throws IOException { return hash(state.resolve("invoice-comparison-trials/" + id), name); }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice trial reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static boolean exists(Path root, String name) { return Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, name)); }
    private static JsonObject record(Path root, String name) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) { var sorted = new JsonObject(); value.getAsJsonObject().keySet().stream().sorted().forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key)))); return sorted; }
        if (value.isJsonArray()) { var array = new JsonArray(); value.getAsJsonArray().forEach(v -> array.add(canonical(v))); return array; } return value;
    }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static void retain(Path root, String name, Object value) throws IOException {
        if (exists(root, name)) CaseInputs.require(record(root, name).equals(json(value)), "Changed resumed invoice action"); else publish(root, name, value);
    }
    private static void publish(Path root, String name, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); CaseInputs.require(!exists(root, name) && bytes.length <= 1048576, "Existing or oversized invoice artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    static Definition checkDefinition(ContentDigest bundle) throws IOException {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) implementations.put(arm, digest("unrun-invoice-adapter-check-" + arm.name()));
        implementations.put(Arm.SINGLE_AGENT, CoordinationRuntime.implementationIdentity(policy()));
        return new Definition("invoice-single-agent-check", Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", digest("unreviewed-invoice-adapter-check"), implementations,
                List.of(new Workload("invoice-joint", List.of(new CaseTask(CASE, bundle, ExpectedResponse.ANSWER, List.of("joint-invoice-evidence"))))), 1, new Limits(60, 1200000, 64000, 2048));
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: policy STATE | check STATE INPUTS EXPECTED | register STATE INPUTS EXPECTED PLAN WORKLOAD REPETITION | run/read STATE INPUTS EXPECTED TRIAL");
        if (args[0].equals("policy") && args.length == 2) { System.out.println(JSON.toJson(Map.of("policy", policy(), "implementationIdentity", CoordinationRuntime.implementationIdentity(policy())))); return; }
        CaseInputs.require(args.length >= 4, "Invoice input directory and independent expected identity required"); var expected = new ContentDigest(args[3]);
        var adapter = new InvoiceSingleAgent(Path.of(args[1]), Path.of(args[2]), expected); String id; JsonObject result;
        switch (args[0]) {
            case "check" -> { CaseInputs.require(args.length == 4, "Invalid invoice check arguments"); id = adapter.register(expected.sha256(), checkDefinition(expected), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT)); System.out.println("Trial: " + id); result = adapter.run(id); }
            case "register" -> {
                CaseInputs.require(args.length == 7, "Invalid invoice registration arguments"); Path planPath = Path.of(args[4]).toAbsolutePath();
                var tree = record(planPath.getParent(), planPath.getFileName().toString()); var plan = JSON.fromJson(tree, CoordinationComparisonReport.Plan.class);
                CaseInputs.require(tree.equals(JSON.toJsonTree(plan)), "Invalid invoice comparison plan shape");
                System.out.println(adapter.register(expected.sha256(), plan.definition(), new Slot(args[5], Integer.parseInt(args[6]), Arm.SINGLE_AGENT))); return;
            }
            case "run", "read" -> { CaseInputs.require(args.length == 5, "Invalid invoice trial arguments"); id = args[4]; result = args[0].equals("run") ? adapter.run(id) : adapter.retained(id); }
            default -> throw new IllegalArgumentException("Invalid invoice investigator action");
        }
        if (result.has("status")) { System.out.println("Status: ACTIVE; resume the existing trial"); return; }
        var trial = result.getAsJsonObject("trial"); System.out.println("Status: " + trial.get("status").getAsString() + "; quality review: NOT_PERFORMED");
        System.out.println("Usage: " + JSON.toJson(trial.get("usage"))); System.out.println("Report: " + adapter.directory(id).resolve("result.json"));
    }
}
