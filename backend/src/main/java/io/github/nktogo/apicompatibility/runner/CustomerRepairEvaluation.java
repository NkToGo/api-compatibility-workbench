package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Fresh bounded investigations and one-shot repairs on the known customer-sources case. */
public final class CustomerRepairEvaluation {
    private final Path state;
    private final Flow flow;
    interface Flow {
        String agent(String context, String model) throws IOException;
        JsonObject investigate(String agent) throws IOException;
        String candidate(String agent, String model, String sdkEvidence) throws IOException;
        JsonObject generate(String candidate) throws IOException;
        String verification(String candidate, Path caseRoot, Path dependencies, String image) throws IOException;
        JsonObject verify(String verification) throws Exception;
    }
    public CustomerRepairEvaluation(Path state) throws IOException { this(state, backend(state)); }
    CustomerRepairEvaluation(Path state, Flow flow) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.flow = flow;
        Files.createDirectories(this.state.resolve("customer-repair-evaluations"));
    }
    private static Flow backend(Path state) throws IOException {
        var investigator = new ConsumerInvestigator(state); var candidates = new RepairCandidates(state); var verifier = new RepairVerification(state);
        return new Flow() {
            public String agent(String context, String model) throws IOException { return investigator.create(context, model); }
            public JsonObject investigate(String agent) throws IOException { return investigator.run(agent); }
            public String candidate(String agent, String model, String sdk) throws IOException { return candidates.create(agent, model, sdk); }
            public JsonObject generate(String candidate) throws IOException { return candidates.generate(candidate); }
            public String verification(String candidate, Path root, Path dependencies, String image) throws IOException { return verifier.create(candidate, root, dependencies, image); }
            public JsonObject verify(String verification) throws Exception { return verifier.execute(verification); }
        };
    }
    public String create(Path caseRoot, Path dependencies, String caseImage, String repairImage, String model, int repetitions) throws IOException {
        CaseInputs.require(repetitions >= 1 && repetitions <= 5 && image(caseImage) && image(repairImage), "Invalid evaluation bounds");
        new ModelCallBroker.Request(model, "check", 4096, "repair-v1");
        var inputs = new CaseInputs(caseRoot, dependencies); var tools = new InvestigationTools(state); var contexts = new JsonArray();
        String sdk = RepairCandidates.sdkEvidence(dependencies.resolve("stripe-java-20.33.0.jar"));
        for (int i = 0; i < repetitions; i++) {
            String context = tools.register(caseRoot, dependencies, caseImage);
            contexts.add(json(Map.of("contextId", context, "description", tools.invoke(context, "describe_case", new JsonObject()))));
        }
        String id = "evaluation-" + UUID.randomUUID(); Path directory = Files.createDirectory(directory(id));
        var suite = json(Map.of("formatVersion", 1, "evaluationId", id, "model", model, "repairImage", repairImage,
                "repetitions", repetitions, "contexts", contexts, "harnessSha256", RepairVerification.harness(),
                "caseSha256", identity(inputs), "sdkEvidence", sdk));
        suite.addProperty("caseRoot", caseRoot.toAbsolutePath().normalize().toString());
        suite.addProperty("dependencies", dependencies.toAbsolutePath().normalize().toString());
        DurableExecution.publish(directory.resolve("suite.json"), suite); return id;
    }
    public JsonObject run(String id) throws Exception {
        Path directory = directory(id); CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing evaluation");
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return active(id);
            var suite = read(directory.resolve("suite.json"));
            CaseInputs.require(id.equals(suite.get("evaluationId").getAsString()) && suite.get("formatVersion").getAsInt() == 1, "Evaluation identity mismatch");
            if (Files.exists(directory.resolve("result.json"))) {
                var result = read(directory.resolve("result.json"));
                CaseInputs.require(result.get("suiteArtifacts").equals(CaseInputs.JSON.toJsonTree(inventory(directory))), "Evaluation artifacts changed");
                for (var item : result.getAsJsonArray("trials")) {
                    var row = item.getAsJsonObject();
                    for (String kind : List.of("agent", "candidate", "verification")) if (row.has(kind + "Id")) {
                        Path child = child(kind, row.get(kind + "Id").getAsString());
                        CaseInputs.require(row.get(kind + "Artifacts").equals(CaseInputs.JSON.toJsonTree(inventory(child))), "Child evidence changed");
                    }
                }
                return result;
            }
            String seal = hash(directory.resolve("suite.json")); Path started = directory.resolve("started.json");
            if (Files.exists(started)) CaseInputs.require(seal.equals(read(started).get("suiteSha256").getAsString()), "Started evaluation changed");
            else DurableExecution.publish(started, Map.of("suiteSha256", seal));
            CaseInputs.require(RepairVerification.harness().equals(suite.get("harnessSha256").getAsString()), "Evaluation build changed; recover children separately");
            Path caseRoot = Path.of(suite.get("caseRoot").getAsString()), dependencies = Path.of(suite.get("dependencies").getAsString());
            CaseInputs.require(identity(new CaseInputs(caseRoot, dependencies)).equals(suite.get("caseSha256").getAsString()), "Case inputs changed");
            int repetitions = suite.get("repetitions").getAsInt();
            CaseInputs.require(repetitions >= 1 && repetitions <= 5 && suite.getAsJsonArray("contexts").size() == repetitions, "Invalid trial count");
            var rows = new JsonArray(); long input = 0, output = 0;
            for (int i = 1; i <= repetitions; i++) {
                var context = suite.getAsJsonArray("contexts").get(i - 1).getAsJsonObject(); String contextId = context.get("contextId").getAsString();
                CaseInputs.require(context.get("description").equals(new InvestigationTools(state).invoke(contextId, "describe_case", new JsonObject())), "Context changed");
                String agent = mapped(directory, "agent", i, () -> flow.agent(contextId, suite.get("model").getAsString()));
                var session = read(child("agent", agent).resolve("session.json"));
                CaseInputs.require(contextId.equals(session.get("contextId").getAsString()) && suite.get("model").equals(session.get("model")), "Mapped investigation changed");
                var investigation = flow.investigate(agent); if (isActive(investigation)) return active(id);
                var row = json(Map.of("trial", i, "investigationStatus", investigation.get("status")));
                String candidate = null, verification = null; JsonObject generated = null, verified = null;
                if ("INVESTIGATED".equals(investigation.get("status").getAsString())) {
                    candidate = mapped(directory, "candidate", i, () -> flow.candidate(agent, suite.get("model").getAsString(), suite.get("sdkEvidence").getAsString()));
                    var registration = read(child("candidate", candidate).resolve("registration.json"));
                    CaseInputs.require(agent.equals(registration.get("agentId").getAsString()) && contextId.equals(registration.get("contextId").getAsString()), "Mapped candidate changed");
                    generated = flow.generate(candidate); if (isActive(generated)) return active(id);
                    if ("CANDIDATE_READY".equals(generated.get("status").getAsString())) {
                        String ready = candidate;
                        verification = mapped(directory, "verification", i, () -> flow.verification(ready, caseRoot, dependencies, suite.get("repairImage").getAsString()));
                        var specification = read(child("verification", verification).resolve("spec.json"));
                        CaseInputs.require(candidate.equals(specification.get("candidateId").getAsString()) && suite.get("repairImage").equals(specification.get("imageId")), "Mapped verification changed");
                        verified = flow.verify(verification); if (isActive(verified)) return active(id);
                    }
                    var score = SubscriptionRepairEvaluation.score(generated, verified);
                    for (var entry : score.entrySet()) row.add(entry.getKey(), entry.getValue());
                } else row.addProperty("outcome", "ABSTAINED".equals(investigation.get("status").getAsString()) ? "INVESTIGATION_ABSTAINED" : "INVESTIGATION_FAILED");
                retain(row, "agent", agent); if (candidate != null) retain(row, "candidate", candidate); if (verification != null) retain(row, "verification", verification);
                row.add("investigationInputTokens", investigation.get("inputTokens")); row.add("investigationOutputTokens", investigation.get("outputTokens"));
                input += investigation.get("inputTokens").getAsLong(); output += investigation.get("outputTokens").getAsLong();
                if (generated != null && generated.has("usage")) {
                    row.add("generationUsage", generated.get("usage")); input += generated.getAsJsonObject("usage").get("inputTokens").getAsLong();
                    output += generated.getAsJsonObject("usage").get("outputTokens").getAsLong();
                }
                rows.add(row);
            }
            var counts = new TreeMap<String, Integer>(); for (var row : rows) counts.merge(row.getAsJsonObject().get("outcome").getAsString(), 1, Integer::sum);
            var result = json(Map.of("formatVersion", 1, "evaluationId", id, "status", "COMPLETED", "trials", rows,
                    "counts", counts, "inputTokens", input, "outputTokens", output, "suiteArtifacts", inventory(directory)));
            result.addProperty("scope", "Repeated fresh investigations and one-shot repairs on one known synthetic case; no semantic or abstention-correctness score");
            result.addProperty("usageScope", "Completed model responses only; uncertain requests may incur unrecorded charges");
            DurableExecution.publish(directory.resolve("result.json"), result); return result;
        }
    }
    private String mapped(Path directory, String kind, int trial, Register registration) throws IOException {
        Path path = directory.resolve(kind + "-" + trial + ".json");
        if (Files.exists(path)) { String id = read(path).get(kind + "Id").getAsString(); child(kind, id); return id; }
        String id = registration.create(); child(kind, id);
        // Registration is inert. Mapping precedes every remote or container dispatch.
        DurableExecution.publish(path, Map.of(kind + "Id", id)); return id;
    }
    private void retain(JsonObject row, String kind, String id) throws IOException {
        row.addProperty(kind + "Id", id); row.add(kind + "Artifacts", CaseInputs.JSON.toJsonTree(inventory(child(kind, id))));
    }
    private Path child(String kind, String id) {
        valid(id, kind); return state.resolve(switch (kind) { case "agent" -> "agents"; case "candidate" -> "candidates"; case "verification" -> "verifications"; default -> throw new IllegalArgumentException("Unknown stage"); }).resolve(id);
    }
    private Path directory(String id) { valid(id, "evaluation"); return state.resolve("customer-repair-evaluations").resolve(id); }
    private static boolean image(String id) { return id.matches("sha256:[0-9a-f]{64}"); }
    private static void valid(String id, String prefix) { CaseInputs.require(id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid stage identity"); }
    private static String identity(CaseInputs inputs) { var hashes = new TreeMap<String, String>(); inputs.files.forEach((name, bytes) -> hashes.put(name, CaseInputs.hash(bytes))); return CaseInputs.identity(hashes); }
    private static boolean isActive(JsonObject result) { return "ACTIVE".equals(result.get("status").getAsString()); }
    private static JsonObject active(String id) { return json(Map.of("status", "ACTIVE", "evaluationId", id)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(bytes(path)); }
    private static String hash(Path path) throws IOException { return CaseInputs.hash(bytes(path)); }
    private static byte[] bytes(Path path) throws IOException { CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1048576, "Invalid trial artifact"); return Files.readAllBytes(path); }
    private static Map<String, String> inventory(Path directory) throws IOException {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.toList()) {
                CaseInputs.require(!Files.isSymbolicLink(path), "Linked trial artifact");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || path.getFileName().toString().equals("owner.lock")) continue;
                if (path.equals(directory.resolve("result.json")) && directory.getParent().getFileName().toString().equals("customer-repair-evaluations")) continue;
                hashes.put(directory.relativize(path).toString().replace('\\', '/'), hash(path));
            }
        }
        return hashes;
    }
    private interface Register { String create() throws IOException; }
    public static void main(String[] args) throws Exception {
        CaseInputs.require(args.length == 3 || args.length == 8, "Usage: CustomerRepairEvaluation start STATE CASE DEPENDENCIES CASE_IMAGE REPAIR_IMAGE MODEL REPETITIONS | resume STATE ID");
        var evaluation = new CustomerRepairEvaluation(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 8) id = evaluation.create(Path.of(args[2]), Path.of(args[3]), args[4], args[5], args[6], Integer.parseInt(args[7]));
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid evaluation command"); id = args[2]; }
        System.out.println("Evaluation: " + id); var result = evaluation.run(id); System.out.println("Status: " + result.get("status").getAsString());
        if (result.has("counts")) System.out.println("Outcomes: " + result.get("counts"));
        System.out.println("Result: " + evaluation.directory(id).resolve("result.json"));
    }
}
