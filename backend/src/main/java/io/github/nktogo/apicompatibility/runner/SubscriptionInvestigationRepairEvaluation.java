package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Fresh retained-evidence investigations, one-shot candidates and protected subscription verification. */
public final class SubscriptionInvestigationRepairEvaluation {
    private static final String ROOT = "subscription-investigation-repair-evaluations";
    private final Path state;
    private final Flow flow;
    interface Flow {
        String agent(String context, String model) throws IOException;
        JsonObject investigate(String agent) throws IOException;
        String candidate(String agent, String model) throws IOException;
        JsonObject generate(String candidate) throws IOException;
        String verification(String candidate, String image) throws IOException;
        JsonObject verify(String verification) throws Exception;
    }
    public SubscriptionInvestigationRepairEvaluation(Path state) throws IOException { this(state, backend(state)); }
    SubscriptionInvestigationRepairEvaluation(Path state, Flow flow) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.flow = flow;
        Files.createDirectories(this.state.resolve(ROOT));
    }
    private static Flow backend(Path state) throws IOException {
        var investigator = new ConsumerInvestigator(state); var candidates = new SubscriptionCandidates(state); var verifier = new RepairVerification(state);
        return new Flow() {
            public String agent(String context, String model) throws IOException { return investigator.create(context, model); }
            public JsonObject investigate(String agent) throws IOException { return investigator.run(agent); }
            public String candidate(String agent, String model) throws IOException { return candidates.createFromInvestigation(agent, model); }
            public JsonObject generate(String candidate) throws IOException { return candidates.generate(candidate); }
            public String verification(String candidate, String image) throws IOException { return candidates.verification(candidate, image); }
            public JsonObject verify(String verification) throws Exception { return verifier.execute(verification); }
        };
    }
    public String create(String evidenceId, String model, String image, int repetitions) throws IOException {
        CaseInputs.require(repetitions >= 1 && repetitions <= 5 && image.matches("sha256:[0-9a-f]{64}"), "Invalid evaluation bounds");
        new ModelCallBroker.Request(model, "check", 2048, "investigation-v2");
        new ModelCallBroker.Request(model, "check", 4096, "subscription-repair-v1");
        Path evidence = child("verification", evidenceId); RepairVerification.retained(evidence);
        var tools = new SubscriptionInvestigationTools(state); var contexts = new JsonArray();
        var evidenceArtifacts = inventory(evidence);
        for (int i = 0; i < repetitions; i++) {
            String context = tools.register(evidenceId); var description = tools.invoke(context, "describe_case", new JsonObject());
            CaseInputs.require(description.get("ok").getAsBoolean(), "Invalid registered context");
            contexts.add(json(Map.of("contextId", context, "description", description, "artifacts", inventory(context(context)))));
        }
        CaseInputs.require(evidenceArtifacts.equals(inventory(evidence)), "Evidence changed during registration");
        String id = "evaluation-" + UUID.randomUUID(); Path directory = Files.createDirectory(directory(id));
        DurableExecution.publish(directory.resolve("suite.json"), Map.of("formatVersion", 1, "evaluationId", id,
                "verificationId", evidenceId, "evidenceArtifacts", evidenceArtifacts, "model", model, "imageId", image,
                "repetitions", repetitions, "contexts", contexts, "harnessSha256", RepairVerification.harness()));
        return id;
    }
    public JsonObject run(String id) throws Exception {
        Path directory = directory(id); CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing evaluation");
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return active(id);
            var suite = read(directory.resolve("suite.json"));
            CaseInputs.require(id.equals(suite.get("evaluationId").getAsString()) && suite.get("formatVersion").getAsInt() == 1, "Evaluation identity mismatch");
            validateInputs(suite);
            if (Files.exists(directory.resolve("result.json"))) {
                var result = read(directory.resolve("result.json"));
                CaseInputs.require(result.get("suiteArtifacts").equals(CaseInputs.JSON.toJsonTree(inventory(directory))), "Evaluation artifacts changed");
                for (var item : result.getAsJsonArray("trials")) {
                    var row = item.getAsJsonObject();
                    for (String kind : List.of("agent", "candidate", "verification")) if (row.has(kind + "Id"))
                        CaseInputs.require(row.get(kind + "Artifacts").equals(CaseInputs.JSON.toJsonTree(inventory(child(kind, row.get(kind + "Id").getAsString())))), "Child evidence changed");
                    for (var entry : row.getAsJsonObject("modelCallArtifacts").entrySet())
                        CaseInputs.require(entry.getValue().equals(CaseInputs.JSON.toJsonTree(inventory(child("model", entry.getKey())))), "Model evidence changed");
                }
                return result;
            }
            String seal = hash(directory.resolve("suite.json")); Path started = directory.resolve("started.json");
            if (Files.exists(started)) CaseInputs.require(seal.equals(read(started).get("suiteSha256").getAsString()), "Started evaluation changed");
            else DurableExecution.publish(started, Map.of("suiteSha256", seal));
            CaseInputs.require(RepairVerification.harness().equals(suite.get("harnessSha256").getAsString()), "Evaluation build changed; recover children separately");
            var rows = new JsonArray(); long input = 0, output = 0; int calls = 0;
            for (int trial = 1; trial <= suite.get("repetitions").getAsInt(); trial++) {
                var context = suite.getAsJsonArray("contexts").get(trial - 1).getAsJsonObject(); String contextId = context.get("contextId").getAsString();
                validateInputs(suite);
                String agent = mapped(directory, "agent", trial, () -> flow.agent(contextId, suite.get("model").getAsString()));
                var session = read(registration("agent", agent));
                CaseInputs.require(agent.equals(session.get("agentId").getAsString()) && contextId.equals(session.get("contextId").getAsString())
                        && suite.get("model").equals(session.get("model")) && context.get("description").equals(session.get("case"))
                        && "investigation-v2".equals(session.get("protocol").getAsString()) && session.get("maxDecisions").getAsInt() == ConsumerInvestigator.MAX_DECISIONS,
                        "Mapped investigation changed");
                var investigation = flow.investigate(agent); if (isActive(investigation)) return active(id);
                validateInputs(suite);
                var row = json(Map.of("trial", trial, "contextId", contextId, "investigationStatus", investigation.get("status")));
                String candidate = null, verification = null; JsonObject generated = null;
                if ("INVESTIGATED".equals(investigation.get("status").getAsString())) {
                    candidate = mapped(directory, "candidate", trial, () -> flow.candidate(agent, suite.get("model").getAsString()));
                    var registration = read(registration("candidate", candidate)); var handoff = registration.getAsJsonObject("investigation");
                    CaseInputs.require(agent.equals(handoff.get("agentId").getAsString()) && contextId.equals(handoff.get("contextId").getAsString())
                            && suite.get("verificationId").equals(registration.get("verificationId")), "Mapped candidate changed");
                    var request = read(child("model", registration.get("modelCallId").getAsString()).resolve("request.json"));
                    CaseInputs.require(suite.get("model").equals(request.get("model")) && "subscription-repair-v1".equals(request.get("protocol").getAsString())
                            && request.get("maxOutputTokens").getAsInt() == 4096, "Mapped generation request changed");
                    generated = flow.generate(candidate); if (isActive(generated)) return active(id);
                    validateInputs(suite); JsonObject verified = null;
                    if ("CANDIDATE_READY".equals(generated.get("status").getAsString())) {
                        String ready = candidate;
                        verification = mapped(directory, "verification", trial, () -> flow.verification(ready, suite.get("imageId").getAsString()));
                        var spec = read(registration("verification", verification));
                        CaseInputs.require(verification.equals(spec.get("verificationId").getAsString()) && candidate.equals(spec.get("candidateId").getAsString())
                                && suite.get("imageId").equals(spec.get("imageId")) && "subscription-period-v1".equals(spec.get("protocol").getAsString())
                                && hash(child("candidate", candidate).resolve("result.json")).equals(spec.get("candidateManifestSha256").getAsString()), "Mapped verification changed");
                        verified = flow.verify(verification); if (isActive(verified)) return active(id);
                    }
                    for (var entry : SubscriptionRepairEvaluation.score(generated, verified).entrySet()) row.add(entry.getKey(), entry.getValue());
                } else row.addProperty("outcome", "ABSTAINED".equals(investigation.get("status").getAsString()) ? "INVESTIGATION_ABSTAINED" : "INVESTIGATION_FAILED");
                validateInputs(suite);
                retain(row, "agent", agent); if (candidate != null) retain(row, "candidate", candidate); if (verification != null) retain(row, "verification", verification);
                row.add("investigationInputTokens", investigation.get("inputTokens")); row.add("investigationOutputTokens", investigation.get("outputTokens"));
                input += investigation.get("inputTokens").getAsLong(); output += investigation.get("outputTokens").getAsLong();
                if (generated != null && generated.has("usage")) {
                    row.add("generationUsage", generated.get("usage")); input += generated.getAsJsonObject("usage").get("inputTokens").getAsLong();
                    output += generated.getAsJsonObject("usage").get("outputTokens").getAsLong();
                }
                var modelArtifacts = new TreeMap<String, Map<String, String>>();
                try (var files = Files.list(child("agent", agent))) {
                    for (Path file : files.filter(p -> p.getFileName().toString().matches("call-[0-9]+\\.json")).toList()) {
                        String call = read(file).get("callId").getAsString();
                        CaseInputs.require(!modelArtifacts.containsKey(call), "Duplicate investigation request");
                        modelArtifacts.put(call, inventory(child("model", call)));
                    }
                }
                CaseInputs.require(modelArtifacts.size() <= ConsumerInvestigator.MAX_DECISIONS, "Investigation request budget exceeded");
                if (candidate != null) {
                    String call = read(registration("candidate", candidate)).get("modelCallId").getAsString();
                    CaseInputs.require(!modelArtifacts.containsKey(call), "Duplicate generation request"); modelArtifacts.put(call, inventory(child("model", call)));
                }
                row.add("modelCallArtifacts", CaseInputs.JSON.toJsonTree(modelArtifacts)); row.addProperty("registeredModelCalls", modelArtifacts.size());
                calls += modelArtifacts.size(); rows.add(row);
            }
            var counts = new TreeMap<String, Integer>(); for (var row : rows) counts.merge(row.getAsJsonObject().get("outcome").getAsString(), 1, Integer::sum);
            var result = json(Map.of("formatVersion", 1, "evaluationId", id, "status", "COMPLETED", "trials", rows,
                    "counts", counts, "inputTokens", input, "outputTokens", output, "registeredModelCalls", calls, "suiteArtifacts", inventory(directory)));
            result.addProperty("semanticReview", "PENDING");
            result.addProperty("scope", "Fresh investigations and one-shot repairs on one known synthetic case; protected scenario outcomes do not establish semantic support or general accuracy");
            result.addProperty("usageScope", "Registered requests and completed-response tokens; uncertain dispatches may incur unrecorded charges");
            DurableExecution.publish(directory.resolve("result.json"), result); return result;
        }
    }
    private void validateInputs(JsonObject suite) throws IOException {
        int count = suite.get("repetitions").getAsInt();
        CaseInputs.require(count >= 1 && count <= 5 && suite.getAsJsonArray("contexts").size() == count
                && suite.get("imageId").getAsString().matches("sha256:[0-9a-f]{64}"), "Invalid trial configuration");
        Path evidence = child("verification", suite.get("verificationId").getAsString()); RepairVerification.retained(evidence);
        CaseInputs.require(suite.get("evidenceArtifacts").equals(CaseInputs.JSON.toJsonTree(inventory(evidence))), "Original evidence changed");
        var seen = new HashSet<String>(); var tools = new SubscriptionInvestigationTools(state);
        for (var item : suite.getAsJsonArray("contexts")) {
            var context = item.getAsJsonObject(); String id = context.get("contextId").getAsString();
            CaseInputs.require(seen.add(id) && context.get("artifacts").equals(CaseInputs.JSON.toJsonTree(inventory(context(id))))
                    && context.get("description").equals(tools.invoke(id, "describe_case", new JsonObject()))
                    && suite.get("verificationId").equals(read(context(id).resolve("registration.json")).get("verificationId")), "Context changed");
        }
    }
    private String mapped(Path directory, String kind, int trial, Register registration) throws IOException {
        Path mapping = directory.resolve(kind + "-" + trial + ".json");
        if (Files.exists(mapping)) {
            var stored = read(mapping); String id = stored.get(kind + "Id").getAsString();
            CaseInputs.require(hash(registration(kind, id)).equals(stored.get("registrationSha256").getAsString()), "Mapped registration changed"); return id;
        }
        String id = registration.create();
        // Registration is inert; every request or worker is mapped before dispatch.
        DurableExecution.publish(mapping, Map.of(kind + "Id", id, "registrationSha256", hash(registration(kind, id)))); return id;
    }
    private void retain(JsonObject row, String kind, String id) throws IOException {
        row.addProperty(kind + "Id", id); row.add(kind + "Artifacts", CaseInputs.JSON.toJsonTree(inventory(child(kind, id))));
    }
    private Path registration(String kind, String id) { return child(kind, id).resolve(switch (kind) { case "agent" -> "session.json"; case "candidate" -> "registration.json"; case "verification" -> "spec.json"; default -> throw new IllegalArgumentException("Unknown registration"); }); }
    private Path child(String kind, String id) {
        valid(id, kind); return state.resolve(switch (kind) { case "agent" -> "agents"; case "candidate" -> "subscription-candidates"; case "verification" -> "verifications"; case "model" -> "model-calls"; default -> throw new IllegalArgumentException("Unknown stage"); }).resolve(id);
    }
    private Path context(String id) { valid(id, "subscription-investigation"); return state.resolve("subscription-investigations").resolve(id); }
    private Path directory(String id) { valid(id, "evaluation"); return state.resolve(ROOT).resolve(id); }
    private static void valid(String id, String prefix) { CaseInputs.require(id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid stage identity"); }
    private static boolean isActive(JsonObject result) { return "ACTIVE".equals(result.get("status").getAsString()); }
    private static JsonObject active(String id) { return json(Map.of("status", "ACTIVE", "evaluationId", id)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(bytes(path)); }
    private static String hash(Path path) throws IOException { return CaseInputs.hash(bytes(path)); }
    private static byte[] bytes(Path path) throws IOException { CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1048576, "Invalid trial artifact"); return Files.readAllBytes(path); }
    private static Map<String, String> inventory(Path directory) throws IOException {
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing artifact directory"); var hashes = new TreeMap<String, String>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.toList()) {
                CaseInputs.require(!Files.isSymbolicLink(path), "Linked trial artifact");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || path.getFileName().toString().equals("owner.lock")) continue;
                if (path.equals(directory.resolve("result.json")) && directory.getParent().getFileName().toString().equals(ROOT)) continue;
                hashes.put(directory.relativize(path).toString().replace('\\', '/'), hash(path));
            }
        }
        return hashes;
    }
    private interface Register { String create() throws IOException; }
    public static void main(String[] args) throws Exception {
        CaseInputs.require(args.length == 3 || args.length == 6, "Usage: SubscriptionInvestigationRepairEvaluation start STATE EVIDENCE MODEL IMAGE REPETITIONS | resume STATE ID");
        var evaluation = new SubscriptionInvestigationRepairEvaluation(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 6) id = evaluation.create(args[2], args[3], args[4], Integer.parseInt(args[5]));
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid evaluation command"); id = args[2]; }
        System.out.println("Evaluation: " + id); var result = evaluation.run(id); System.out.println("Status: " + result.get("status").getAsString());
        if (result.has("counts")) System.out.println("Outcomes: " + result.get("counts"));
        System.out.println("Result: " + evaluation.directory(id).resolve("result.json"));
    }
}
