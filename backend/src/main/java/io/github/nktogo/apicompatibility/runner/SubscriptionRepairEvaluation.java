package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Repeated one-shot trials on one known case, not held-out accuracy or an abstention correctness judge. */
public final class SubscriptionRepairEvaluation {
    private final Path state;
    private final SubscriptionCandidates candidates;
    private final RepairVerification verifier;
    public SubscriptionRepairEvaluation(Path state) throws IOException {
        this(state, new SubscriptionCandidates(state), new RepairVerification(state));
    }
    SubscriptionRepairEvaluation(Path state, SubscriptionCandidates candidates, RepairVerification verifier) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.candidates = candidates; this.verifier = verifier;
        Files.createDirectories(this.state.resolve("repair-evaluations"));
    }
    public String create(String evidenceId, String model, String image, int repetitions) throws IOException {
        CaseInputs.require(repetitions >= 1 && repetitions <= 5 && image.matches("sha256:[0-9a-f]{64}"), "Invalid evaluation bounds");
        new ModelCallBroker.Request(model, "check", 4096, "subscription-repair-v1");
        Path evidence = verification(evidenceId); var retained = RepairVerification.retained(evidence);
        var spec = read(evidence.resolve("spec.json"));
        CaseInputs.require("COMPLETED".equals(retained.get("status").getAsString())
                && spec.has("protocol") && "subscription-period-v1".equals(spec.get("protocol").getAsString()), "Completed subscription evidence required");
        String id = "evaluation-" + UUID.randomUUID(); Path directory = Files.createDirectory(directory(id));
        DurableExecution.publish(directory.resolve("suite.json"), Map.of("formatVersion", 1, "evaluationId", id,
                "verificationId", evidenceId, "evidenceSha256", hash(evidence.resolve("result.json")),
                "model", model, "imageId", image, "repetitions", repetitions, "harnessSha256", RepairVerification.harness()));
        return id;
    }
    public JsonObject run(String id) throws Exception {
        Path directory = directory(id);
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing evaluation");
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("status", "ACTIVE", "evaluationId", id));
            var suite = read(directory.resolve("suite.json"));
            CaseInputs.require(id.equals(suite.get("evaluationId").getAsString()) && suite.get("formatVersion").getAsInt() == 1, "Evaluation identity mismatch");
            var evidence = verification(suite.get("verificationId").getAsString());
            RepairVerification.retained(evidence);
            CaseInputs.require(hash(evidence.resolve("result.json")).equals(suite.get("evidenceSha256").getAsString()), "Original evidence changed");
            if (Files.exists(directory.resolve("result.json"))) {
                var result = read(directory.resolve("result.json")); verify(directory, result); return result;
            }
            Path started = directory.resolve("started.json"); String suiteHash = hash(directory.resolve("suite.json"));
            if (Files.exists(started)) CaseInputs.require(suiteHash.equals(read(started).get("suiteSha256").getAsString()), "Started evaluation changed");
            else DurableExecution.publish(started, Map.of("suiteSha256", suiteHash));
            CaseInputs.require(RepairVerification.harness().equals(suite.get("harnessSha256").getAsString()), "Evaluation build changed; recover child attempts separately");
            int repetitions = suite.get("repetitions").getAsInt(); CaseInputs.require(repetitions >= 1 && repetitions <= 5, "Invalid trial count");
            var rows = new JsonArray(); long inputTokens = 0, outputTokens = 0;
            for (int trial = 1; trial <= repetitions; trial++) {
                Path mapping = directory.resolve("trial-" + trial + ".json"); String candidate;
                if (Files.exists(mapping)) candidate = read(mapping).get("candidateId").getAsString();
                else {
                    candidate = candidates.create(suite.get("verificationId").getAsString(), suite.get("model").getAsString());
                    // No model dispatch occurs before this durable mapping.
                    DurableExecution.publish(mapping, Map.of("candidateId", candidate));
                }
                var generated = candidates.generate(candidate);
                if ("ACTIVE".equals(generated.get("status").getAsString())) return json(Map.of("status", "ACTIVE", "evaluationId", id));
                JsonObject verified = null; String verification = null;
                if ("CANDIDATE_READY".equals(generated.get("status").getAsString())) {
                    Path execution = directory.resolve("verification-" + trial + ".json");
                    if (Files.exists(execution)) verification = read(execution).get("verificationId").getAsString();
                    else {
                        verification = candidates.verification(candidate, suite.get("imageId").getAsString());
                        // Registration is inert; an interrupted mapping cannot orphan a running worker.
                        DurableExecution.publish(execution, Map.of("verificationId", verification));
                    }
                    verified = verifier.execute(verification);
                    if ("ACTIVE".equals(verified.get("status").getAsString())) return json(Map.of("status", "ACTIVE", "evaluationId", id));
                }
                var row = score(generated, verified); row.addProperty("trial", trial); row.addProperty("candidateId", candidate);
                row.addProperty("candidateResultSha256", hash(candidate(candidate).resolve("result.json")));
                if (verification != null) {
                    row.addProperty("verificationId", verification);
                    row.addProperty("verificationResultSha256", hash(verification(verification).resolve("result.json")));
                }
                if (generated.has("usage")) {
                    row.add("usage", generated.get("usage"));
                    inputTokens += generated.getAsJsonObject("usage").get("inputTokens").getAsLong();
                    outputTokens += generated.getAsJsonObject("usage").get("outputTokens").getAsLong();
                }
                rows.add(row);
            }
            var counts = new TreeMap<String, Integer>();
            for (var row : rows) counts.merge(row.getAsJsonObject().get("outcome").getAsString(), 1, Integer::sum);
            var result = json(Map.of("formatVersion", 1, "evaluationId", id, "status", "COMPLETED", "trials", rows,
                    "counts", counts, "inputTokens", inputTokens, "outputTokens", outputTokens,
                    "artifacts", inventory(directory)));
            result.addProperty("scope", "Repeated one-shot attempts on one known synthetic case; expectations are visible, abstention correctness and general accuracy are not scored");
            result.addProperty("usageScope", "Completed responses with recorded usage only; uncertain requests may incur unrecorded charges");
            DurableExecution.publish(directory.resolve("result.json"), result); return result;
        }
    }
    static JsonObject score(JsonObject candidate, JsonObject verification) {
        String status = candidate.get("status").getAsString(); String outcome;
        if (status.equals("ABSTAINED")) outcome = "ABSTAINED";
        else if (status.equals("INVALID_PROPOSAL")) outcome = "INVALID_PROPOSAL";
        else if (!status.equals("CANDIDATE_READY")) outcome = "GENERATION_FAILED";
        else if (verification == null || !"COMPLETED".equals(verification.get("status").getAsString()) || !verification.has("evaluation")) outcome = "VERIFICATION_INCOMPLETE";
        else outcome = switch (verification.getAsJsonObject("evaluation").get("conclusion").getAsString()) {
            case "ALL_OBSERVED_REGRESSIONS_FIXED" -> "OBSERVED_REGRESSIONS_FIXED";
            case "REGRESSIONS_REMAIN" -> "REGRESSIONS_REMAIN";
            default -> "VERIFICATION_UNRESOLVED";
        };
        var row = json(Map.of("generationStatus", status, "outcome", outcome));
        if (verification != null) {
            row.add("verificationStatus", verification.get("status"));
            if (verification.has("evaluation")) {
                var evaluation = verification.getAsJsonObject("evaluation"); row.add("repairConclusion", evaluation.get("conclusion"));
                if (evaluation.has("scenarios")) row.add("scenarioOutcomes", evaluation.get("scenarios"));
            }
        }
        return row;
    }
    private void verify(Path directory, JsonObject result) throws IOException {
        var inventory = inventory(directory);
        CaseInputs.require(result.get("artifacts").equals(CaseInputs.JSON.toJsonTree(inventory)), "Evaluation artifacts changed");
        for (var value : result.getAsJsonArray("trials")) {
            var row = value.getAsJsonObject(); Path candidate = candidate(row.get("candidateId").getAsString());
            CaseInputs.require(hash(candidate.resolve("result.json")).equals(row.get("candidateResultSha256").getAsString()), "Candidate result changed");
            candidates.generate(row.get("candidateId").getAsString()); // Result existence/hash checked first: no remote dispatch.
            if (row.has("verificationId")) {
                Path verification = verification(row.get("verificationId").getAsString()); RepairVerification.retained(verification);
                CaseInputs.require(hash(verification.resolve("result.json")).equals(row.get("verificationResultSha256").getAsString()), "Verification result changed");
            }
        }
    }
    private Path candidate(String id) { valid(id, "candidate"); return state.resolve("subscription-candidates").resolve(id); }
    private Path verification(String id) { valid(id, "verification"); return state.resolve("verifications").resolve(id); }
    private Path directory(String id) { valid(id, "evaluation"); return state.resolve("repair-evaluations").resolve(id); }
    private static void valid(String id, String prefix) { CaseInputs.require(id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid evaluation reference"); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static Map<String, String> inventory(Path directory) throws IOException {
        var result = new TreeMap<String, String>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (Set.of("owner.lock", "result.json").contains(name)) continue;
                CaseInputs.require(Set.of("suite.json", "started.json").contains(name) || name.matches("(trial|verification)-[1-5]\\.json"), "Unexpected evaluation artifact");
                result.put(name, hash(path));
            }
        }
        return result;
    }
    private static byte[] bytes(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1048576, "Invalid evaluation file"); return Files.readAllBytes(path);
    }
    private static String hash(Path path) throws IOException { return CaseInputs.hash(bytes(path)); }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(bytes(path)); }
    public static void main(String[] args) throws Exception {
        CaseInputs.require(args.length == 3 || args.length == 6, "Usage: SubscriptionRepairEvaluation start STATE EVIDENCE MODEL IMAGE REPETITIONS | resume STATE ID");
        var evaluation = new SubscriptionRepairEvaluation(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 6) id = evaluation.create(args[2], args[3], args[4], Integer.parseInt(args[5]));
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid evaluation command"); id = args[2]; }
        System.out.println("Evaluation: " + id); var result = evaluation.run(id);
        System.out.println("Status: " + result.get("status").getAsString());
        if (result.has("counts")) System.out.println("Outcomes: " + result.get("counts"));
        System.out.println("Result: " + evaluation.directory(id).resolve("result.json"));
    }
}
