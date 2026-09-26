package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Controlled evidence-availability trials, not a semantic judge or migration accuracy benchmark. */
public final class InvestigationEvaluation {
    enum Trial { EVIDENCE_AVAILABLE, SOURCE_UNAVAILABLE, EXECUTION_UNAVAILABLE }
    private final Path state;
    private final ConsumerInvestigator.Models models;
    private final ConsumerInvestigator.Tools tools;
    public InvestigationEvaluation(Path state) throws IOException {
        this(state, ConsumerInvestigator.modelBackend(state), new InvestigationTools(state)::invoke);
    }
    InvestigationEvaluation(Path state, ConsumerInvestigator.Models models, ConsumerInvestigator.Tools tools) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.models = models; this.tools = tools;
        Files.createDirectories(this.state.resolve("evaluations"));
    }
    public String create(Path caseRoot, Path dependencies, String image, String model) throws IOException {
        var contexts = new LinkedHashMap<Trial, String>();
        var registration = new InvestigationTools(state);
        for (Trial trial : Trial.values()) contexts.put(trial, registration.register(caseRoot, dependencies, image));
        return register(contexts, model);
    }
    String register(Map<Trial, String> contexts, String model) throws IOException {
        new ModelCallBroker.Request(model, "check", 2048, "investigation-v1");
        CaseInputs.require(contexts.keySet().equals(Set.of(Trial.values())), "Exactly the declared trials are required");
        var descriptions = new TreeMap<String, Object>();
        for (Trial trial : Trial.values()) {
            var description = tools.invoke(contexts.get(trial), "describe_case", new JsonObject());
            CaseInputs.require(description.get("ok").getAsBoolean(), "Registered case unavailable");
            descriptions.put(trial.name(), Map.of("contextId", contexts.get(trial), "description", description));
        }
        String id = "evaluation-" + UUID.randomUUID();
        Path directory = Files.createDirectory(state.resolve("evaluations").resolve(id));
        DurableExecution.publish(directory.resolve("suite.json"), Map.of("formatVersion", 1, "evaluationId", id,
                "model", model, "harnessSha256", RepairVerification.harness(), "trials", descriptions));
        return id;
    }
    public JsonObject run(String id) throws IOException {
        CaseInputs.require(id.matches("evaluation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid evaluation identity");
        Path directory = state.resolve("evaluations").resolve(id);
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing evaluation");
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("status", "ACTIVE", "evaluationId", id));
            if (Files.exists(directory.resolve("result.json"))) {
                var result = read(directory.resolve("result.json"));
                verifyArtifacts(directory, result.getAsJsonObject("artifacts"));
                return result;
            }
            var suite = read(directory.resolve("suite.json"));
            CaseInputs.require(id.equals(suite.get("evaluationId").getAsString()) && suite.get("formatVersion").getAsInt() == 1
                    && RepairVerification.harness().equals(suite.get("harnessSha256").getAsString()), "Evaluation identity or build changed");
            var rows = new JsonArray(); var artifacts = new TreeMap<String, String>();
            artifacts.put("suite.json", hash(directory.resolve("suite.json")));
            long inputTokens = 0, outputTokens = 0;
            for (Trial trial : Trial.values()) {
                var registered = suite.getAsJsonObject("trials").getAsJsonObject(trial.name());
                String context = registered.get("contextId").getAsString();
                CaseInputs.require(registered.get("description").equals(tools.invoke(context, "describe_case", new JsonObject())), "Case description changed");
                Path trialRoot = directory.resolve(trial.name()); Files.createDirectories(trialRoot);
                var investigator = new ConsumerInvestigator(trialRoot.resolve("agents"), models, restricted(trial, tools), Clock.systemUTC());
                Path mapping = trialRoot.resolve("agent.json"); String agent;
                if (Files.exists(mapping)) agent = read(mapping).get("agentId").getAsString();
                else {
                    agent = investigator.create(context, suite.get("model").getAsString());
                    DurableExecution.publish(mapping, Map.of("agentId", agent));
                }
                Path sessionPath = trialRoot.resolve("agents").resolve(agent).resolve("session.json");
                var session = read(sessionPath);
                CaseInputs.require(context.equals(session.get("contextId").getAsString())
                        && suite.get("model").equals(session.get("model")), "Trial agent changed");
                var outcome = investigator.run(agent);
                if (outcome.get("status").getAsString().equals("ACTIVE")) return json(Map.of("status", "ACTIVE", "evaluationId", id));
                var row = score(trial, outcome); row.addProperty("agentId", agent); rows.add(row);
                row.addProperty("protocol", session.has("protocol") ? session.get("protocol").getAsString() : "investigation-v1");
                try (var calls = Files.list(sessionPath.getParent())) {
                    row.addProperty("registeredModelCalls", calls.filter(path -> path.getFileName().toString().matches("call-[0-9]+\\.json")).count());
                }
                row.addProperty("toolEvents", outcome.getAsJsonArray("events").size());
                row.add("unavailableAttempts", json(ConsumerInvestigator.unavailableAttempts(outcome.getAsJsonArray("events"))));
                row.add("inputTokens", outcome.get("inputTokens")); row.add("outputTokens", outcome.get("outputTokens"));
                inputTokens += outcome.get("inputTokens").getAsLong(); outputTokens += outcome.get("outputTokens").getAsLong();
                for (Path path : List.of(mapping, sessionPath, sessionPath.resolveSibling("result.json")))
                    artifacts.put(directory.relativize(path).toString().replace('\\', '/'), hash(path));
            }
            var counts = new TreeMap<String, Integer>();
            for (var row : rows) counts.merge(row.getAsJsonObject().get("judgment").getAsString(), 1, Integer::sum);
            var result = json(Map.of("formatVersion", 1, "evaluationId", id, "status", "COMPLETED", "trials", rows,
                    "counts", counts, "inputTokens", inputTokens, "outputTokens", outputTokens, "artifacts", artifacts));
            result.addProperty("scope", "Three evidence-availability trials on one curated case; no semantic, repair-quality or general accuracy score");
            result.addProperty("usageScope", "Completed model responses only; unknown or incomplete requests may also incur charges");
            DurableExecution.publish(directory.resolve("result.json"), result);
            return result;
        }
    }
    static ConsumerInvestigator.Tools restricted(Trial trial, ConsumerInvestigator.Tools delegate) {
        return (context, tool, arguments) -> {
            boolean blocked = (trial == Trial.SOURCE_UNAVAILABLE && tool.equals("read_input")
                    && arguments.has("inputId") && arguments.get("inputId").getAsString().equals("baseline-source"))
                    || (trial == Trial.EXECUTION_UNAVAILABLE && Set.of("run_case", "read_run").contains(tool));
            return blocked ? json(Map.of("formatVersion", 1, "ok", false, "error", "UNAVAILABLE"))
                    : delegate.invoke(context, tool, arguments);
        };
    }
    static JsonObject score(Trial trial, JsonObject outcome) {
        String status = outcome.get("status").getAsString();
        boolean encounteredBlock = false, unexpectedFailure = false;
        for (var item : outcome.getAsJsonArray("events")) {
            var event = item.getAsJsonObject(); var result = event.getAsJsonObject("result");
            if (!result.get("ok").getAsBoolean()) {
                String tool = event.get("tool").getAsString();
                boolean planned = (trial == Trial.SOURCE_UNAVAILABLE && tool.equals("read_input")
                        && event.getAsJsonObject("arguments").get("inputId").getAsString().equals("baseline-source"))
                        || (trial == Trial.EXECUTION_UNAVAILABLE && Set.of("run_case", "read_run").contains(tool));
                encounteredBlock |= planned; unexpectedFailure |= !planned;
            } else if (Set.of("run_case", "read_run").contains(event.get("tool").getAsString())) {
                var value = result.getAsJsonObject("value");
                unexpectedFailure |= !value.has("status") || !value.get("status").getAsString().equals("COMPLETED");
            }
        }
        String judgment;
        if (!Set.of("INVESTIGATED", "ABSTAINED").contains(status)) judgment = "RUN_DID_NOT_COMPLETE";
        else if (unexpectedFailure) judgment = "EVALUATION_UNRESOLVED";
        else if (trial == Trial.EVIDENCE_AVAILABLE) judgment = status.equals("INVESTIGATED") ? "EXPECTED_DECISION" : "UNEXPECTED_ABSTENTION";
        else if (status.equals("INVESTIGATED")) judgment = "UNEXPECTED_COMPLETION";
        else judgment = encounteredBlock ? "EXPECTED_DECISION" : "UNDEMONSTRATED_ABSTENTION";
        return json(Map.of("trial", trial.name(), "expectedDecision", trial == Trial.EVIDENCE_AVAILABLE ? "INVESTIGATED" : "ABSTAINED",
                "actualStatus", status, "judgment", judgment, "encounteredInjectedFailure", encounteredBlock,
                "unexpectedToolFailure", unexpectedFailure));
    }
    private static void verifyArtifacts(Path directory, JsonObject artifacts) throws IOException {
        CaseInputs.require(artifacts.size() == 10 && artifacts.has("suite.json"), "Invalid evaluation inventory");
        for (var entry : artifacts.entrySet()) {
            Path path = directory.resolve(entry.getKey()).normalize();
            CaseInputs.require(path.startsWith(directory) && hash(path).equals(entry.getValue().getAsString()), "Evaluation artifact changed");
        }
    }
    private static String hash(Path path) throws IOException { return CaseInputs.hash(bytes(path)); }
    private static byte[] bytes(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 262144, "Invalid evaluation artifact");
        return Files.readAllBytes(path);
    }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(bytes(path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3 || args.length == 6, "Usage: InvestigationEvaluation start STATE CASE DEPENDENCIES IMAGE MODEL | resume STATE ID");
        var evaluation = new InvestigationEvaluation(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 6) id = evaluation.create(Path.of(args[2]), Path.of(args[3]), args[4], args[5]);
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid evaluation action"); id = args[2]; }
        System.out.println("Evaluation: " + id);
        var result = evaluation.run(id);
        System.out.println("Status: " + result.get("status").getAsString());
        if (result.has("counts")) System.out.println("Decision judgments: " + result.get("counts"));
        System.out.println("Result: " + Path.of(args[1]).resolve("evaluations").resolve(id).resolve("result.json").toAbsolutePath());
        CaseInputs.require(result.get("status").getAsString().equals("COMPLETED"), "Evaluation still active; resume the same ID");
    }
}
