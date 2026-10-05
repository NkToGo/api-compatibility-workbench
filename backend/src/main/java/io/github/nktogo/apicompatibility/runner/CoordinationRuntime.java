package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Trusted-host boundary for future comparison adapters, not an agent or semantic evaluator. */
public final class CoordinationRuntime {
    private static final String UUID = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final Set<String> PROTOCOLS = Set.of("investigation-v2", "coordination-v2", "reconciliation-v1", BundleInvestigationDecision.PROTOCOL, "customer-captured-v1", "subscription-captured-v1", CapturedTeamDecision.PROTOCOL, CapturedReconciliationDecision.PROTOCOL);
    private final Path root;
    private final CoordinationInputs inputs;
    private final Clock clock;
    private final Brokers brokers;
    public record Participant(String id, Set<String> cases, Set<String> protocols) {
        public Participant {
            identifier(id); cases = Collections.unmodifiableSet(new TreeSet<>(cases)); protocols = Collections.unmodifiableSet(new TreeSet<>(protocols));
            CaseInputs.require(!cases.isEmpty() && cases.size() <= 2 && PROTOCOLS.containsAll(protocols), "Invalid participant scope");
        }
    }
    public record Policy(int parallelCalls, int maxReads, ContentDigest adapterIdentity, ContentDigest promptIdentity, ContentDigest toolPolicyIdentity,
                         ContentDigest contextPolicyIdentity, String finalSelection, List<Participant> participants) {
        public Policy {
            CaseInputs.require(parallelCalls >= 1 && parallelCalls <= 2 && maxReads >= 1 && maxReads <= 500, "Invalid trial operation limits");
            Objects.requireNonNull(adapterIdentity); Objects.requireNonNull(promptIdentity); Objects.requireNonNull(toolPolicyIdentity); Objects.requireNonNull(contextPolicyIdentity); identifier(finalSelection);
            participants = List.copyOf(participants); CaseInputs.require(!participants.isEmpty() && participants.size() <= 12
                    && participants.stream().map(Participant::id).distinct().count() == participants.size(), "Invalid trial participants");
        }
    }
    private record Spec(int formatVersion, String runId, String registeredAt, String bundleId, String inputReceiptSha256, Definition definition, Slot slot, Policy policy) {}
    public CoordinationRuntime(Path state) throws IOException { this(state, Clock.systemUTC(), CoordinationRuntime::broker); }
    CoordinationRuntime(Path state, Clock clock, Brokers brokers) throws IOException {
        root = state.toAbsolutePath().normalize().resolve("coordination-trials"); Files.createDirectories(root); ConsumerTasks.directory(root);
        inputs = new CoordinationInputs(state); this.clock = clock; this.brokers = brokers;
    }
    /** Binds declared adapter/prompt/tool/context references and every stage scope/limit to the arm identity. */
    public static ContentDigest implementationIdentity(Policy policy) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeUTF("coordination-runtime-policy-v1");
            out.writeInt(policy.parallelCalls()); out.writeInt(policy.maxReads()); out.writeUTF(policy.adapterIdentity().sha256()); out.writeUTF(policy.promptIdentity().sha256());
            out.writeUTF(policy.toolPolicyIdentity().sha256()); out.writeUTF(policy.contextPolicyIdentity().sha256()); out.writeUTF(policy.finalSelection()); out.writeInt(policy.participants().size());
            for (var participant : policy.participants()) {
                out.writeUTF(participant.id()); out.writeInt(participant.cases().size()); for (String caseId : participant.cases()) out.writeUTF(caseId);
                out.writeInt(participant.protocols().size()); for (String protocol : participant.protocols()) out.writeUTF(protocol);
            }
            return new ContentDigest(CaseInputs.hash(bytes.toByteArray()));
        } catch (IOException e) { throw new IllegalStateException("Cannot encode trial policy", e); }
    }
    public String register(String bundleId, Definition definition, Slot slot, Policy policy) throws IOException {
        Objects.requireNonNull(definition); Objects.requireNonNull(slot); Objects.requireNonNull(policy);
        CaseInputs.require(definition.origin() == Origin.SUPPLIED_RECORDS, "Synthetic comparison examples cannot authorize runtime work");
        var workload = definition.workloads().stream().filter(w -> w.workloadId().equals(slot.workloadId())).findFirst().orElseThrow();
        CaseInputs.require(slot.repetition() <= definition.repetitions(), "Slot outside registered comparison");
        CaseInputs.require(implementationIdentity(policy).equals(definition.implementations().get(slot.arm())), "Trial policy does not match declared arm implementation");
        var identities = inputs.identities(bundleId); var declared = new TreeMap<String, ContentDigest>(); workload.cases().forEach(c -> declared.put(c.caseId(), c.inputIdentity()));
        CaseInputs.require(identities.equals(declared) && policy.participants().stream().allMatch(p -> identities.keySet().containsAll(p.cases())), "Trial input scope mismatch");
        CaseInputs.require(slot.arm() != Arm.DETERMINISTIC || policy.participants().stream().allMatch(p -> p.protocols().isEmpty()), "Deterministic arm cannot receive model access");
        new ModelCallBroker.Request(definition.model(), "validate", definition.limits().outputTokensPerCall());
        String id = "trial-" + java.util.UUID.randomUUID(); Path run = Files.createDirectory(root.resolve(id));
        for (String name : List.of("calls", "reads", "broker")) Files.createDirectory(run.resolve(name));
        DurableExecution.publish(run.resolve("spec.json"), new Spec(1, id, clock.instant().toString(), bundleId, inputs.receiptHash(bundleId), definition, slot, policy));
        DurableExecution.publish(run.resolve("grant.json"), Map.of("permission", "registered-coordination-trial", "specSha256", hash(run, "spec.json")));
        return id;
    }
    /** Starts the whole-bundle clock once, before any participant operation. Registration is not timed execution. */
    public void start(String id) throws IOException {
        Path run = directory(id);
        try (var lease = LocalFileLease.acquire(run.resolve("budget.lock"))) {
            Objects.requireNonNull(lease); var spec = spec(run); requireOpen(run);
            if (!exists(run, "started.json")) {
                Instant now = clock.instant(); DurableExecution.publish(run.resolve("started.json"), Map.of("runId", id, "specSha256", hash(run, "spec.json"),
                        "startedAt", now.toString(), "deadline", now.plusMillis(spec.definition().limits().elapsedMillis()).toString()));
            }
            admit(run, spec);
        }
    }
    public JsonObject call(String id, String participantId, String operationId, ModelCallBroker.Request request) throws IOException {
        identifier(operationId); start(id); Path run = directory(id); var spec = spec(run); var participant = participant(spec, participantId);
        CaseInputs.require(spec.slot().arm() != Arm.DETERMINISTIC && request.protocol() != null && participant.protocols().contains(request.protocol())
                && spec.definition().model().equals(request.model()) && request.maxOutputTokens() <= spec.definition().limits().outputTokensPerCall()
                && request.input().getBytes(StandardCharsets.UTF_8).length <= spec.definition().limits().inputBytesPerCall(), "Request outside trial scope");
        // Holding a lane through registration and transport bounds concurrency across stages and processes.
        var lane = lane(run, spec.policy().parallelCalls()); if (lane == null) return json(Map.of("status", "ACTIVE"));
        try (lane) {
            admit(run, spec); var broker = brokers.create(run.resolve("broker"), () -> allowed(run, spec));
            String key = key(participantId, operationId), reservationPath = "calls/" + key + ".json", mappingPath = "calls/" + key + "-mapping.json"; String call;
            var reservation = json(Map.of("participant", participantId, "operationId", operationId, "requestSha256", CaseInputs.hash(request.body()), "specSha256", hash(run, "spec.json")));
            try (var lease = LocalFileLease.acquire(run.resolve("budget.lock"))) {
                Objects.requireNonNull(lease); admit(run, spec);
                if (exists(run, reservationPath)) {
                    CaseInputs.require(record(run, reservationPath).equals(reservation), "Changed reserved trial request");
                    if (!exists(run, mappingPath)) throw new Stop("CALL_REGISTRATION_UNCERTAIN");
                    call = mapped(run, reservationPath, mappingPath);
                } else {
                    if (reservations(run).size() >= spec.definition().limits().modelCalls()) throw new Stop("CALL_BUDGET_EXHAUSTED");
                    DurableExecution.publish(run.resolve(reservationPath), reservation);
                    // Any interruption after this durable reservation consumes the slot. Registration is never guessed/replayed.
                    call = broker.register(request, lifetime(run)); validCall(call);
                    DurableExecution.publish(run.resolve(mappingPath), Map.of("callId", call, "reservationSha256", hash(run, reservationPath)));
                }
                verifyRequest(run, spec, call, reservation);
            }
            var response = broker.execute(call); admit(run, spec);
            if (response.get("status").getAsString().equals("COMPLETED") && response.get("outputTokens").getAsLong() > spec.definition().limits().outputTokensPerCall()) throw new Stop("OUTPUT_LIMIT_EXCEEDED");
            return response.deepCopy();
        }
    }
    /** A scoped read over the shared captured evidence. No model-supplied path or external execution is accepted. */
    public JsonObject read(String id, String participantId, String operationId, String caseId, String alias) throws IOException {
        identifier(operationId); start(id); Path run = directory(id); var spec = spec(run);
        CaseInputs.require(participant(spec, participantId).cases().contains(caseId), "Case outside participant scope");
        try (var lease = LocalFileLease.acquire(run.resolve("budget.lock"))) {
            Objects.requireNonNull(lease); admit(run, spec); byte[] bytes = inputs.read(spec.bundleId(), caseId, alias);
            var value = json(Map.of("participant", participantId, "operationId", operationId, "caseId", caseId, "inputId", alias,
                    "sha256", CaseInputs.hash(bytes), "content", new String(bytes, StandardCharsets.UTF_8), "contentTrust", "untrusted-data"));
            CaseInputs.require(CaseInputs.JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length <= 1048576, "Trial read record too large");
            String path = "reads/" + key(participantId, operationId) + ".json";
            if (exists(run, path)) CaseInputs.require(record(run, path).equals(value), "Changed retained read operation");
            else {
                if (readPaths(run).size() >= spec.policy().maxReads()) throw new Stop("READ_BUDGET_EXHAUSTED");
                DurableExecution.publish(run.resolve(path), value);
            }
            admit(run, spec); return value;
        }
    }
    public void revoke(String id) throws IOException {
        Path run = directory(id); spec(run);
        try { Files.write(run.resolve("revoked"), new byte[0], StandardOpenOption.CREATE_NEW); } catch (FileAlreadyExistsException ignored) {}
        // The parent callback also stops in-flight transport; no permission is renewed here.
    }
    /** Read-only accounting for one stable operation, including an unmapped reservation. */
    boolean reserved(String id, String participantId, String operationId) throws IOException {
        identifier(operationId); Path run = directory(id); var spec = spec(run); participant(spec, participantId);
        try (var lease = LocalFileLease.acquire(run.resolve("budget.lock"))) {
            Objects.requireNonNull(lease); String path = "calls/" + key(participantId, operationId) + ".json";
            if (!exists(run, path)) return false;
            var value = record(run, path); CaseInputs.require(value.get("participant").getAsString().equals(participantId)
                    && value.get("operationId").getAsString().equals(operationId) && value.get("specSha256").getAsString().equals(hash(run, "spec.json")), "Changed operation reservation"); return true;
        }
    }
    public JsonObject snapshot(String id) throws IOException {
        Path run = directory(id);
        try (var lease = LocalFileLease.acquire(run.resolve("budget.lock"))) {
            Objects.requireNonNull(lease); return exists(run, "result.json") ? retained(run) : trace(run, spec(run));
        }
    }
    public JsonObject finish(String id, CoordinationTrial.Status status) throws IOException {
        CaseInputs.require(status != null && status != CoordinationTrial.Status.PENDING, "Terminal trial status required"); Path run = directory(id); var spec = spec(run);
        var lanes = new ArrayList<LocalFileLease>();
        try {
            for (int i = 1; i <= spec.policy().parallelCalls(); i++) {
                var lane = LocalFileLease.tryAcquire(run.resolve("lane-" + i + ".lock"));
                if (lane == null) throw new Stop("RUN_ACTIVE"); lanes.add(lane);
            }
            try (var lease = LocalFileLease.acquire(run.resolve("budget.lock"))) {
                Objects.requireNonNull(lease); if (exists(run, "result.json")) return retained(run);
                // Reconcile mapped unfinished journals without dispatch. Unmapped registrations remain uncertain.
                var broker = brokers.create(run.resolve("broker"), () -> false);
                for (String reservation : reservations(run)) {
                    String mapping = reservation.substring(0, reservation.length() - 5) + "-mapping.json";
                    if (!exists(run, mapping)) continue;
                    String call = mapped(run, reservation, mapping); verifyRequest(run, spec, call, record(run, reservation));
                    if (!exists(run, "broker/" + call + "/result.json") && "ACTIVE".equals(broker.recover(call).get("status").getAsString())) throw new Stop("RUN_ACTIVE");
                }
                var result = trace(run, spec); result.addProperty("status", status.name()); result.addProperty("finishedAt", clock.instant().toString());
                DurableExecution.publish(run.resolve("result.json"), result);
                DurableExecution.publish(run.resolve("receipt.json"), Map.of("resultSha256", hash(run, "result.json"))); return result;
            }
        } finally { for (var lane : lanes) lane.close(); }
    }
    public JsonObject retained(String id) throws IOException { return retained(directory(id)); }
    private JsonObject retained(Path run) throws IOException {
        var result = record(run, "result.json"); CaseInputs.require(record(run, "receipt.json").equals(json(Map.of("resultSha256", hash(run, "result.json")))), "Changed or incomplete trial result");
        var spec = spec(run); CaseInputs.require(result.get("specSha256").getAsString().equals(hash(run, "spec.json")), "Changed finished trial specification");
        CaseInputs.require(inputs.receiptHash(spec.bundleId()).equals(spec.inputReceiptSha256()), "Changed finished trial inputs");
        for (var entry : result.getAsJsonObject("artifactSha256").entrySet()) CaseInputs.require(entry.getValue().getAsString().equals(hash(run, entry.getKey())), "Changed retained trial trace");
        return result;
    }
    private JsonObject trace(Path run, Spec spec) throws IOException {
        CaseInputs.require(record(run, "grant.json").equals(json(Map.of("permission", "registered-coordination-trial", "specSha256", hash(run, "spec.json")))), "Changed trace admission grant");
        CaseInputs.require(inputs.receiptHash(spec.bundleId()).equals(spec.inputReceiptSha256()), "Changed trace inputs");
        int registered = 0, completed = 0, notDispatched = 0, unresolved = 0; long input = 0, output = 0; var inventory = new TreeMap<String, String>();
        inventory.put("spec.json", hash(run, "spec.json")); inventory.put("grant.json", hash(run, "grant.json"));
        var slots = reservations(run); var calls = new HashSet<String>(); var resourceIssues = new ArrayList<String>();
        CaseInputs.require(slots.size() <= spec.definition().limits().modelCalls(), "Changed trial call budget");
        for (String path : slots) {
            var reservation = record(run, path); participant(spec, reservation.get("participant").getAsString());
            CaseInputs.require(path.equals("calls/" + key(reservation.get("participant").getAsString(), reservation.get("operationId").getAsString()) + ".json")
                    && hash(run, "spec.json").equals(reservation.get("specSha256").getAsString()), "Changed trial reservation");
            inventory.put(path, hash(run, path)); String mapping = path.substring(0, path.length() - 5) + "-mapping.json";
            if (!exists(run, mapping)) { unresolved++; continue; }
            String call = mapped(run, path, mapping); CaseInputs.require(calls.add(call), "Reused broker call in trial"); registered++; inventory.put(mapping, hash(run, mapping));
            verifyRequest(run, spec, call, reservation); String requestPath = "broker/" + call + "/request.json"; inventory.put(requestPath, hash(run, requestPath));
            String resultPath = "broker/" + call + "/result.json";
            if (!exists(run, resultPath)) { unresolved++; continue; }
            var result = record(run, resultPath); inventory.put(resultPath, hash(run, resultPath));
            CaseInputs.require(call.equals(result.get("callId").getAsString()), "Changed broker outcome identity");
            if ("COMPLETED".equals(result.get("status").getAsString())) {
                long in = result.get("inputTokens").getAsLong(), out = result.get("outputTokens").getAsLong();
                CaseInputs.require(in >= 0 && out >= 0, "Invalid retained token inventory"); completed++; input = Math.addExact(input, in); output = Math.addExact(output, out);
                if (out > spec.definition().limits().outputTokensPerCall()) resourceIssues.add("OUTPUT_LIMIT_EXCEEDED:" + call);
            } else if (result.has("remoteOutcome") && "NOT_DISPATCHED".equals(result.get("remoteOutcome").getAsString())) notDispatched++;
            else unresolved++;
        }
        var reads = readPaths(run); CaseInputs.require(reads.size() <= spec.policy().maxReads(), "Changed read budget");
        for (String path : reads) {
            var read = record(run, path); CaseInputs.require(participant(spec, read.get("participant").getAsString()).cases().contains(read.get("caseId").getAsString())
                    && path.equals("reads/" + key(read.get("participant").getAsString(), read.get("operationId").getAsString()) + ".json")
                    && read.get("sha256").getAsString().equals(CaseInputs.hash(inputs.read(spec.bundleId(), read.get("caseId").getAsString(), read.get("inputId").getAsString())))
                    && read.get("sha256").getAsString().equals(CaseInputs.hash(read.get("content").getAsString().getBytes(StandardCharsets.UTF_8))), "Changed retained read"); inventory.put(path, hash(run, path));
        }
        long elapsed = 0; if (exists(run, "started.json")) { inventory.put("started.json", hash(run, "started.json")); elapsed = Math.max(0, Duration.between(started(run), clock.instant()).toMillis()); }
        if (exists(run, "revoked")) inventory.put("revoked", hash(run, "revoked"));
        // Include orphan registration artifacts as forensic facts without guessing their outcome or charging invented token totals.
        ConsumerTasks.directory(run.resolve("broker"));
        try (var directories = Files.list(run.resolve("broker"))) {
            for (Path brokerDirectory : directories.toList()) {
                validCall(brokerDirectory.getFileName().toString()); ConsumerTasks.directory(brokerDirectory);
                try (var artifacts = Files.list(brokerDirectory)) {
                    for (Path path : artifacts.toList()) {
                        String name = path.getFileName().toString();
                        CaseInputs.require(Set.of("request.json", "grant.json", "started.json", "result.json", "revoked", "owner.lock",
                                "request.json.pending", "grant.json.pending", "started.json.pending", "result.json.pending").contains(name), "Unexpected broker artifact");
                        if (!name.equals("owner.lock")) { String relative = "broker/" + brokerDirectory.getFileName() + "/" + name; inventory.put(relative, hash(run, relative)); }
                    }
                }
            }
        }
        var result = json(Map.of("formatVersion", 1, "runId", spec.runId(), "specSha256", hash(run, "spec.json"), "definitionIdentity", identity(spec.definition()),
                "slot", spec.slot(), "usage", new CoordinationTrial.Usage(slots.size(), registered, completed, notDispatched, unresolved, input, output, reads.size(), 0),
                "elapsedMillis", elapsed, "artifactSha256", inventory, "authority", "runtime-accounting-not-quality-review"));
        result.add("resourceIssues", CaseInputs.JSON.toJsonTree(resourceIssues)); return result;
    }
    private void admit(Path run, Spec spec) throws IOException {
        requireOpen(run);
        if (exists(run, "revoked")) throw new Stop("TRIAL_REVOKED");
        var grant = record(run, "grant.json"); CaseInputs.require(grant.equals(json(Map.of("permission", "registered-coordination-trial", "specSha256", hash(run, "spec.json")))), "Changed trial admission grant");
        CaseInputs.require(inputs.receiptHash(spec.bundleId()).equals(spec.inputReceiptSha256()), "Changed trial inputs");
        Instant started = started(run), now = clock.instant();
        if (now.isBefore(started) || !now.isBefore(started.plusMillis(spec.definition().limits().elapsedMillis()))) throw new Stop("TRIAL_EXPIRED");
    }
    private boolean allowed(Path run, Spec spec) { try { admit(run, spec); return true; } catch (IOException | RuntimeException e) { return false; } }
    private static void requireOpen(Path run) { if (exists(run, "result.json")) throw new Stop("TRIAL_FINISHED"); }
    private Instant started(Path run) throws IOException {
        var spec = spec(run); var start = record(run, "started.json"); Instant at = Instant.parse(start.get("startedAt").getAsString());
        CaseInputs.require(!at.isBefore(Instant.parse(spec.registeredAt())) && start.get("runId").getAsString().equals(spec.runId()) && start.get("specSha256").getAsString().equals(hash(run, "spec.json"))
                && at.plusMillis(spec.definition().limits().elapsedMillis()).equals(Instant.parse(start.get("deadline").getAsString())), "Changed trial clock"); return at;
    }
    private Duration lifetime(Path run) throws IOException {
        Duration remaining = Duration.between(clock.instant(), started(run).plusMillis(spec(run).definition().limits().elapsedMillis()));
        if (remaining.isNegative() || remaining.isZero()) throw new Stop("TRIAL_EXPIRED"); return remaining.compareTo(Duration.ofMinutes(5)) < 0 ? remaining : Duration.ofMinutes(5);
    }
    private Spec spec(Path run) throws IOException {
        var tree = record(run, "spec.json"); var spec = CaseInputs.JSON.fromJson(tree, Spec.class);
        CaseInputs.require(tree.equals(CaseInputs.JSON.toJsonTree(spec)) && spec.formatVersion() == 1 && run.getFileName().toString().equals(spec.runId())
                && spec.definition().origin() == Origin.SUPPLIED_RECORDS && spec.slot().repetition() <= spec.definition().repetitions()
                && implementationIdentity(spec.policy()).equals(spec.definition().implementations().get(spec.slot().arm())), "Invalid trial specification");
        var workload = spec.definition().workloads().stream().filter(w -> w.workloadId().equals(spec.slot().workloadId())).findFirst().orElseThrow();
        var cases = new HashSet<String>(); workload.cases().forEach(c -> cases.add(c.caseId()));
        CaseInputs.require(spec.policy().participants().stream().allMatch(p -> cases.containsAll(p.cases()))
                && (spec.slot().arm() != Arm.DETERMINISTIC || spec.policy().participants().stream().allMatch(p -> p.protocols().isEmpty())), "Invalid retained trial scope");
        return spec;
    }
    private static Participant participant(Spec spec, String id) { return spec.policy().participants().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown trial participant")); }
    private static LocalFileLease lane(Path run, int count) throws IOException {
        for (int i = 1; i <= count; i++) { var lease = LocalFileLease.tryAcquire(run.resolve("lane-" + i + ".lock")); if (lease != null) return lease; } return null;
    }
    private static void verifyRequest(Path run, Spec spec, String call, JsonObject reservation) throws IOException {
        var request = CaseInputs.JSON.fromJson(record(run, "broker/" + call + "/request.json"), ModelCallBroker.Request.class);
        CaseInputs.require(CaseInputs.hash(request.body()).equals(reservation.get("requestSha256").getAsString())
                && spec.definition().model().equals(request.model()) && request.protocol() != null
                && participant(spec, reservation.get("participant").getAsString()).protocols().contains(request.protocol())
                && spec.slot().arm() != Arm.DETERMINISTIC && request.maxOutputTokens() <= spec.definition().limits().outputTokensPerCall()
                && request.input().getBytes(StandardCharsets.UTF_8).length <= spec.definition().limits().inputBytesPerCall(), "Changed mapped broker request");
    }
    private static String mapped(Path run, String reservation, String mapping) throws IOException {
        var record = record(run, mapping); CaseInputs.require(record.get("reservationSha256").getAsString().equals(hash(run, reservation)), "Changed trial call mapping");
        String call = record.get("callId").getAsString(); validCall(call); return call;
    }
    private static List<String> reservations(Path run) throws IOException { return paths(run, "calls", true); }
    private static List<String> readPaths(Path run) throws IOException { return paths(run, "reads", false); }
    private static List<String> paths(Path run, String child, boolean mappings) throws IOException {
        ConsumerTasks.directory(run.resolve(child)); try (var stream = Files.list(run.resolve(child))) {
            var names = stream.map(p -> p.getFileName().toString()).sorted().toList();
            CaseInputs.require(names.size() <= (mappings ? 2000 : 1000) && names.stream().allMatch(n -> n.matches("[0-9a-f]{64}" + (mappings ? "(?:-mapping)?" : "") + "\\.json(?:\\.pending)?")), "Unexpected trial ledger artifact");
            // Pending reservations are uncertainty, never permission to replace an operation.
            CaseInputs.require(names.stream().noneMatch(n -> n.endsWith(".pending")), "Incomplete trial ledger publication");
            if (mappings) CaseInputs.require(names.stream().filter(n -> n.endsWith("-mapping.json")).allMatch(n -> names.contains(n.replace("-mapping.json", ".json"))), "Orphan trial call mapping");
            return names.stream().filter(n -> !n.endsWith("-mapping.json")).map(n -> child + "/" + n).toList();
        }
    }
    private Path directory(String id) { CaseInputs.require(id != null && id.matches("trial-" + UUID), "Invalid trial reference"); ConsumerTasks.directory(root); Path run = root.resolve(id); ConsumerTasks.directory(run); return run; }
    private static String key(String participant, String operation) { identifier(participant); identifier(operation); return CaseInputs.hash((participant + ":" + operation).getBytes(StandardCharsets.UTF_8)); }
    private static void identifier(String value) { CaseInputs.require(value != null && value.matches("[a-z][a-z0-9-]{0,59}"), "Invalid operation identifier"); }
    private static void validCall(String call) { CaseInputs.require(call != null && call.matches("model-" + UUID), "Invalid trial broker reference"); }
    private static boolean exists(Path root, String path) { return Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS); }
    private static JsonObject record(Path root, String path) throws IOException { return CaseInputs.object(ConsumerTasks.bytes(root, path)); }
    private static String hash(Path root, String path) throws IOException { return CaseInputs.hash(ConsumerTasks.bytes(root, path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static Broker broker(Path root, BooleanSupplier permitted) throws IOException {
        var broker = new ModelCallBroker(root, () -> System.getenv("OPENAI_API_KEY"), new ModelHttpTransport(), Clock.systemUTC(), permitted);
        return new Broker() {
            public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException { return broker.register(request, lifetime); }
            public JsonObject execute(String call) throws IOException { String status = broker.execute(call); return status.equals("ACTIVE") ? json(Map.of("status", status)) : broker.outcome(call); }
            public JsonObject recover(String call) throws IOException { String status = broker.recover(call); return status.equals("ACTIVE") ? json(Map.of("status", status)) : broker.outcome(call); }
        };
    }
    interface Brokers { Broker create(Path root, BooleanSupplier permitted) throws IOException; }
    interface Broker { String register(ModelCallBroker.Request request, Duration lifetime) throws IOException; JsonObject execute(String call) throws IOException; JsonObject recover(String call) throws IOException; }
    public static final class Stop extends IllegalStateException { private static final long serialVersionUID = 1L; Stop(String status) { super(status); } }
}
