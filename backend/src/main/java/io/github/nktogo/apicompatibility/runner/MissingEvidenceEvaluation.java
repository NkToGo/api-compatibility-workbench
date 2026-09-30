package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Repeated withheld-evidence investigations. Decision checks never establish semantic correctness. */
public final class MissingEvidenceEvaluation {
    enum Case { CUSTOMER_SOURCES, SUBSCRIPTION_PERIODS }
    enum Condition { SOURCE_UNAVAILABLE, EXECUTION_UNAVAILABLE }
    record Trial(Case caseKind, Condition condition, int repetition, String contextId) {}
    private final Path state;
    private final ConsumerInvestigator.Models models;
    private final ConsumerInvestigator.Tools tools;

    public MissingEvidenceEvaluation(Path state) throws IOException {
        this(state, ConsumerInvestigator.modelBackend(state), ConsumerInvestigator.toolBackend(state));
    }
    MissingEvidenceEvaluation(Path state, ConsumerInvestigator.Models models, ConsumerInvestigator.Tools tools) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.models = models; this.tools = tools;
        Files.createDirectories(this.state.resolve("missing-evidence-evaluations"));
    }
    public String create(Path customerCase, Path dependencies, String image, String subscriptionVerification,
                         String model, int repetitions) throws IOException {
        bounds(repetitions); new ModelCallBroker.Request(model, "check", 2048, "investigation-v2");
        var customer = new InvestigationTools(state); var subscription = new SubscriptionInvestigationTools(state);
        var trials = new ArrayList<Trial>();
        for (Case kind : Case.values()) for (Condition condition : Condition.values()) for (int i = 1; i <= repetitions; i++)
            trials.add(new Trial(kind, condition, i, kind == Case.CUSTOMER_SOURCES
                    ? customer.register(customerCase, dependencies, image) : subscription.register(subscriptionVerification)));
        return register(trials, model, repetitions);
    }
    String register(List<Trial> trials, String model, int repetitions) throws IOException {
        bounds(repetitions); new ModelCallBroker.Request(model, "check", 2048, "investigation-v2");
        CaseInputs.require(trials.size() == 4 * repetitions, "Complete two-case condition matrix required");
        var cells = new HashSet<String>(); var contexts = new HashSet<String>(); var registered = new JsonArray();
        for (Trial trial : trials) {
            CaseInputs.require(trial.caseKind != null && trial.condition != null && trial.repetition >= 1 && trial.repetition <= repetitions
                    && cells.add(trial.caseKind + "/" + trial.condition + "/" + trial.repetition)
                    && contexts.add(trial.contextId), "Duplicate or invalid trial");
            var description = tools.invoke(trial.contextId, "describe_case", new JsonObject());
            CaseInputs.require(description.get("ok").getAsBoolean()
                    && caseId(trial.caseKind).equals(description.getAsJsonObject("value").get("caseId").getAsString()), "Registered case unavailable");
            Path context = context(trial); var row = json(trial); row.add("description", description);
            row.add("registrationArtifacts", json(inventory(context)));
            if (trial.caseKind == Case.SUBSCRIPTION_PERIODS) row.add("retainedExecutionArtifacts", json(inventory(subscriptionEvidence(trial))));
            registered.add(row);
        }
        String id = "evaluation-" + UUID.randomUUID(); Path directory = Files.createDirectory(directory(id));
        DurableExecution.publish(directory.resolve("suite.json"), Map.of("formatVersion", 1, "evaluationId", id,
                "model", model, "repetitions", repetitions, "maxModelCalls", trials.size() * ConsumerInvestigator.MAX_DECISIONS,
                "harnessSha256", RepairVerification.harness(), "trials", registered));
        return id;
    }
    public JsonObject run(String id) throws IOException {
        Path directory = directory(id); CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing evaluation");
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return active(id);
            if (Files.exists(directory.resolve("result.json"))) {
                var result = read(directory.resolve("result.json"));
                CaseInputs.require(result.get("artifacts").equals(json(inventory(directory))), "Evaluation artifacts changed");
                for (var item : result.getAsJsonArray("trials")) {
                    var row = item.getAsJsonObject(); var trial = CaseInputs.JSON.fromJson(row.get("trial"), Trial.class);
                    CaseInputs.require(row.get("contextArtifacts").equals(json(inventory(context(trial)))), "Trial context changed");
                    if (trial.caseKind == Case.SUBSCRIPTION_PERIODS)
                        CaseInputs.require(row.get("retainedExecutionArtifacts").equals(json(inventory(subscriptionEvidence(trial)))), "Retained execution changed");
                }
                return result;
            }
            var suite = read(directory.resolve("suite.json")); int repetitions = suite.get("repetitions").getAsInt(); bounds(repetitions);
            CaseInputs.require(id.equals(suite.get("evaluationId").getAsString()) && suite.get("formatVersion").getAsInt() == 1
                    && suite.getAsJsonArray("trials").size() == 4 * repetitions
                    && suite.get("maxModelCalls").getAsInt() == 4 * repetitions * ConsumerInvestigator.MAX_DECISIONS, "Invalid evaluation matrix");
            String seal = hash(directory.resolve("suite.json")); Path started = directory.resolve("started.json");
            if (Files.exists(started)) CaseInputs.require(seal.equals(read(started).get("suiteSha256").getAsString()), "Started evaluation changed");
            else DurableExecution.publish(started, Map.of("suiteSha256", seal));
            CaseInputs.require(RepairVerification.harness().equals(suite.get("harnessSha256").getAsString()), "Evaluation build changed; recover children separately");
            var rows = new JsonArray(); var counts = new TreeMap<String, Integer>(); long input = 0, output = 0;
            var cells = new HashSet<String>(); var contexts = new HashSet<String>();
            for (var item : suite.getAsJsonArray("trials")) {
                var registered = item.getAsJsonObject(); var trial = CaseInputs.JSON.fromJson(registered, Trial.class);
                CaseInputs.require(trial.caseKind != null && trial.condition != null && trial.repetition >= 1 && trial.repetition <= repetitions
                        && cells.add(trial.caseKind + "/" + trial.condition + "/" + trial.repetition) && contexts.add(trial.contextId), "Invalid trial matrix");
                validateRegistered(registered, trial);
            }
            int index = 0;
            for (var item : suite.getAsJsonArray("trials")) {
                var registered = item.getAsJsonObject(); var trial = CaseInputs.JSON.fromJson(registered, Trial.class);
                validateRegistered(registered, trial);
                Path trialRoot = directory.resolve("trial-" + ++index); Files.createDirectories(trialRoot);
                var investigator = new ConsumerInvestigator(trialRoot.resolve("agents"), models, restricted(trial.condition, tools), Clock.systemUTC());
                Path mapping = trialRoot.resolve("agent.json"); String agent;
                if (Files.exists(mapping)) agent = read(mapping).get("agentId").getAsString();
                else { agent = investigator.create(trial.contextId, suite.get("model").getAsString()); DurableExecution.publish(mapping, Map.of("agentId", agent)); }
                valid(agent, "agent"); Path agentRoot = trialRoot.resolve("agents").resolve(agent); var session = read(agentRoot.resolve("session.json"));
                CaseInputs.require(agent.equals(session.get("agentId").getAsString()) && trial.contextId.equals(session.get("contextId").getAsString())
                        && registered.get("description").equals(session.get("case")) && suite.get("model").equals(session.get("model")), "Trial agent binding changed");
                var outcome = investigator.run(agent); if ("ACTIVE".equals(outcome.get("status").getAsString())) return active(id);
                var row = score(trial.condition, outcome); row.add("trial", json(trial)); row.addProperty("agentId", agent);
                row.add("contextArtifacts", json(inventory(context(trial))));
                if (trial.caseKind == Case.SUBSCRIPTION_PERIODS) row.add("retainedExecutionArtifacts", registered.get("retainedExecutionArtifacts"));
                try (var calls = Files.list(agentRoot)) { row.addProperty("registeredModelCalls", calls.filter(p -> p.getFileName().toString().matches("call-[0-9]+\\.json")).count()); }
                row.add("inputTokens", outcome.get("inputTokens")); row.add("outputTokens", outcome.get("outputTokens"));
                row.addProperty("semanticReview", "PENDING"); rows.add(row);
                counts.merge(row.get("decisionCheck").getAsString(), 1, Integer::sum);
                input += outcome.get("inputTokens").getAsLong(); output += outcome.get("outputTokens").getAsLong();
            }
            var result = json(Map.of("formatVersion", 1, "evaluationId", id, "status", "COMPLETED", "trials", rows,
                    "counts", counts, "inputTokens", input, "outputTokens", output, "artifacts", inventory(directory)));
            result.addProperty("scope", "Repeated missing-evidence decisions on two curated cases; no repair generation, semantic correctness or general accuracy score");
            result.addProperty("usageScope", "Completed responses only; unknown or incomplete requests may also incur charges");
            DurableExecution.publish(directory.resolve("result.json"), result); return result;
        }
    }
    static ConsumerInvestigator.Tools restricted(Condition condition, ConsumerInvestigator.Tools delegate) {
        return (context, tool, arguments) -> blocked(condition, tool, arguments)
                ? json(Map.of("formatVersion", 1, "ok", false, "error", "UNAVAILABLE")) : delegate.invoke(context, tool, arguments);
    }
    private static boolean blocked(Condition condition, String tool, JsonObject arguments) {
        return condition == Condition.SOURCE_UNAVAILABLE && tool.equals("read_input") && arguments.has("inputId")
                && Set.of("baseline-source", "reference-source").contains(arguments.get("inputId").getAsString())
                || condition == Condition.EXECUTION_UNAVAILABLE && Set.of("run_case", "read_run").contains(tool);
    }
    static JsonObject score(Condition condition, JsonObject outcome) {
        boolean encountered = false, unexpected = false;
        for (var item : outcome.getAsJsonArray("events")) {
            var event = item.getAsJsonObject(); var result = event.getAsJsonObject("result"); String tool = event.get("tool").getAsString();
            if (!result.get("ok").getAsBoolean()) {
                boolean injected = blocked(condition, tool, event.getAsJsonObject("arguments"))
                        && result.has("error") && "UNAVAILABLE".equals(result.get("error").getAsString());
                encountered |= injected; unexpected |= !injected;
            } else {
                unexpected |= blocked(condition, tool, event.getAsJsonObject("arguments"));
                if (Set.of("run_case", "read_run").contains(tool)) unexpected |= !result.has("value")
                        || !result.getAsJsonObject("value").has("status") || !"COMPLETED".equals(result.getAsJsonObject("value").get("status").getAsString())
                        || !result.getAsJsonObject("value").has("authority") || !"recorded-execution".equals(result.getAsJsonObject("value").get("authority").getAsString());
            }
        }
        String status = outcome.get("status").getAsString();
        String check = !Set.of("ABSTAINED", "INVESTIGATED").contains(status) ? "INVESTIGATION_FAILED" : unexpected ? "TECHNICAL_UNRESOLVED"
                : status.equals("INVESTIGATED") ? "UNEXPECTED_COMPLETION" : encountered ? "ABSTAINED_AFTER_WITHHELD_EVIDENCE" : "UNDEMONSTRATED_ABSTENTION";
        return json(Map.of("actualStatus", status, "expectedDecision", "ABSTAINED", "decisionCheck", check,
                "encounteredInjectedFailure", encountered, "unexpectedToolFailure", unexpected));
    }
    private Path context(Trial trial) {
        boolean customer = trial.caseKind == Case.CUSTOMER_SOURCES;
        valid(trial.contextId, customer ? "investigation" : "subscription-investigation");
        return state.resolve(customer ? "investigations" : "subscription-investigations").resolve(trial.contextId);
    }
    private Path subscriptionEvidence(Trial trial) throws IOException {
        String id = read(context(trial).resolve("registration.json")).get("verificationId").getAsString(); valid(id, "verification");
        return state.resolve("verifications").resolve(id);
    }
    private void validateRegistered(JsonObject registered, Trial trial) throws IOException {
        var current = inventory(context(trial));
        for (var artifact : registered.getAsJsonObject("registrationArtifacts").entrySet())
            CaseInputs.require(artifact.getValue().getAsString().equals(current.get(artifact.getKey())), "Registered input changed");
        if (trial.caseKind == Case.SUBSCRIPTION_PERIODS)
            CaseInputs.require(registered.get("retainedExecutionArtifacts").equals(json(inventory(subscriptionEvidence(trial)))), "Registered execution changed");
        CaseInputs.require(registered.get("description").equals(tools.invoke(trial.contextId, "describe_case", new JsonObject())), "Trial input identity changed");
    }
    private static String caseId(Case kind) { return kind == Case.CUSTOMER_SOURCES ? CaseInputs.CASE_ID : SubscriptionPeriodCase.CASE_ID; }
    private Path directory(String id) { valid(id, "evaluation"); return state.resolve("missing-evidence-evaluations").resolve(id); }
    private static void valid(String id, String prefix) { CaseInputs.require(id != null && id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid reference"); }
    private static void bounds(int repetitions) { CaseInputs.require(repetitions >= 1 && repetitions <= 5, "Invalid trial repetitions"); }
    private static JsonObject active(String id) { return json(Map.of("status", "ACTIVE", "evaluationId", id)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static byte[] bytes(Path path) throws IOException { CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1048576, "Invalid evaluation artifact"); return Files.readAllBytes(path); }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(bytes(path)); }
    private static String hash(Path path) throws IOException { return CaseInputs.hash(bytes(path)); }
    private static Map<String, String> inventory(Path directory) throws IOException {
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing artifact directory");
        var hashes = new TreeMap<String, String>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.toList()) {
                CaseInputs.require(!Files.isSymbolicLink(path), "Linked evaluation artifact");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || path.getFileName().toString().equals("owner.lock")) continue;
                if (path.equals(directory.resolve("result.json")) && directory.getParent().getFileName().toString().equals("missing-evidence-evaluations")) continue;
                hashes.put(directory.relativize(path).toString().replace('\\', '/'), hash(path));
            }
        }
        return hashes;
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3 || args.length == 8, "Usage: MissingEvidenceEvaluation start STATE CUSTOMER_CASE DEPENDENCIES IMAGE SUBSCRIPTION_VERIFICATION MODEL REPETITIONS | resume STATE ID");
        var evaluation = new MissingEvidenceEvaluation(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 8) id = evaluation.create(Path.of(args[2]), Path.of(args[3]), args[4], args[5], args[6], Integer.parseInt(args[7]));
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid evaluation command"); id = args[2]; }
        System.out.println("Evaluation: " + id); var result = evaluation.run(id); System.out.println("Status: " + result.get("status").getAsString());
        if (result.has("counts")) System.out.println("Decision checks: " + result.get("counts"));
        System.out.println("Result: " + evaluation.directory(id).resolve("result.json"));
        CaseInputs.require("COMPLETED".equals(result.get("status").getAsString()), "Evaluation still active; resume the same ID");
    }
}
