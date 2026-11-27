package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.*;
import io.github.nktogo.apicompatibility.runner.SubscriptionRepositoryVerification.RepositoryInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static io.github.nktogo.apicompatibility.runner.SubscriptionRepositoryVerification.BASELINE_API_VERSION;
import static io.github.nktogo.apicompatibility.runner.SubscriptionRepositoryVerification.TARGET_API_VERSION;

/** Durable single-host verification of one captured candidate; interrupted attempts are never replayed. */
public final class RepairVerification {
    interface Sandbox {
        JsonObject run(String image, String name, String source, String sdk, RepairOracle oracle, Duration deadline,
                       java.util.function.BooleanSupplier stopped) throws Exception;
        void clean(String name, String image) throws Exception;
        default JsonObject subscription(String image, String name, String source, SubscriptionPeriodOracle oracle,
                                        Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
            throw new UnsupportedOperationException("Subscription sandbox unavailable");
        }
    }
    private final Path state;
    private final Sandbox sandbox;
    public RepairVerification(Path state) throws IOException {
        this(state, new Sandbox() {
            public JsonObject run(String image, String name, String source, String sdk, RepairOracle oracle, Duration deadline,
                                  java.util.function.BooleanSupplier stopped) throws Exception {
                return RepairSandbox.run(image, name, source, sdk, oracle, deadline, stopped);
            }
            public void clean(String name, String image) throws Exception { DockerCaseExecutor.removeOwned(name, image); }
            public JsonObject subscription(String image, String name, String source, SubscriptionPeriodOracle oracle,
                                           Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
                return RepairSandbox.runSubscription(image, name, source, oracle, deadline, stopped);
            }
        });
    }
    RepairVerification(Path state, Sandbox sandbox) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.sandbox = sandbox;
        Files.createDirectories(this.state.resolve("verifications"));
    }
    public String create(String candidateId, Path caseRoot, Path dependencies, String image) throws IOException {
        var ready = new RepairCandidates(state).generate(candidateId);
        CaseInputs.require(ready.get("status").getAsString().equals("CANDIDATE_READY"), "Ready candidate required");
        Path candidate = state.resolve("candidates").resolve(candidateId);
        var registration = read(candidate.resolve("registration.json"));
        var tools = new InvestigationTools(state); String context = registration.get("contextId").getAsString();
        var described = tools.invoke(context, "describe_case", new JsonObject());
        CaseInputs.require(described.get("ok").getAsBoolean(), "Registered context unavailable");
        var inputs = new CaseInputs(caseRoot, dependencies);
        var hashes = described.getAsJsonObject("value").getAsJsonObject("inputs");
        for (var entry : InvestigationTools.INPUTS.entrySet())
            CaseInputs.require(CaseInputs.hash(inputs.files.get(entry.getValue())).equals(hashes.get(entry.getKey()).getAsString()), "Case differs from candidate context");
        var receipt = tools.invoke(context, "read_run", json(Map.of("requestId", registration.get("agentId").getAsString())));
        CaseInputs.require(receipt.get("ok").getAsBoolean() && receipt.getAsJsonObject("value").get("receiptSha256").equals(registration.get("receiptSha256")), "Candidate receipt is stale");
        String source = Files.readString(candidate.resolve("checkout/consumer/baseline.java.txt"));
        // Reconstruct the bounded proposal, rather than trusting only an editable artifact manifest.
        String original = new String(inputs.files.get("consumer/baseline.java.txt"), StandardCharsets.UTF_8);
        CaseInputs.require(source.equals(RepairProposal.apply(original, RepairProposal.parse(Files.readString(candidate.resolve("proposal.json"))))), "Candidate does not match proposal");
        return register(inputs, source, candidateId, CaseInputs.hash(Files.readAllBytes(candidate.resolve("result.json"))), image);
    }
    String register(CaseInputs inputs, String source, String candidateId, String candidateManifest, String image) throws IOException {
        CaseInputs.require(source.length() <= 32000 && image.matches("sha256:[0-9a-f]{64}"), "Invalid verification input");
        String id = "verification-" + UUID.randomUUID(); Path directory = Files.createDirectory(state.resolve("verifications").resolve(id));
        Path captured = Files.createDirectory(directory.resolve("inputs"));
        Files.write(captured.resolve("baseline.txt"), inputs.files.get("consumer/baseline.java.txt"));
        Files.writeString(captured.resolve("candidate.txt"), source, StandardCharsets.UTF_8);
        Files.write(captured.resolve("fixtures.json"), inputs.files.get("fixtures.json"));
        Files.write(captured.resolve("expectations.json"), inputs.files.get("expectations.json"));
        var jobs = new ArrayList<Map<String, String>>();
        for (String variant : List.of("baseline", "target", "candidate"))
            for (String scenario : inputs.scenarios.keySet()) jobs.add(Map.of("variant", variant, "scenarioId", scenario, "containerName", "acw-" + UUID.randomUUID()));
        Files.createDirectory(directory.resolve("jobs"));
        var spec = new TreeMap<String, Object>();
        spec.put("formatVersion", 1); spec.put("verificationId", id); spec.put("imageId", image);
        spec.put("candidateId", candidateId); spec.put("candidateManifestSha256", candidateManifest);
        spec.put("inputs", CaseInputs.inventory(captured)); spec.put("jobs", jobs); spec.put("harnessSha256", harness());
        spec.put("deadlineSeconds", 60); spec.put("createdAt", Instant.now().toString());
        DurableExecution.publish(directory.resolve("spec.json"), spec);
        authorization(directory, image).issue(Duration.ofMinutes(30));
        return id;
    }
    public String registerSubscription(Path caseRoot, String baseline, String candidate, String image) throws IOException {
        return registerSubscription(caseRoot, baseline, candidate, image, "supplied-source", null);
    }
    String registerSubscription(Path caseRoot, String baseline, String candidate, String image, String candidateId, String manifest) throws IOException {
        return registerSubscription(caseRoot, baseline, candidate, image, candidateId, manifest, null);
    }

    String registerRepositorySubscription(Path caseRoot, String baseline, String candidate, String image, RepositoryInput input)
            throws IOException {
        input.verifyBytes(baseline.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
        return registerSubscription(caseRoot, baseline, candidate, image, SubscriptionRepositoryVerification.CANDIDATE_ID, null, input);
    }

    private String registerSubscription(Path caseRoot, String baseline, String candidate, String image, String candidateId,
                                        String manifest, RepositoryInput repositoryInput) throws IOException {
        CaseInputs.require(!baseline.isBlank() && !candidate.isBlank() && baseline.length() <= 32000 && candidate.length() <= 32000
                && image.matches("sha256:[0-9a-f]{64}"), "Invalid subscription verification input");
        var files = SubscriptionPeriodCase.load(caseRoot);
        var fixtures = CaseInputs.object(files.get("fixtures.json"));
        var expected = CaseInputs.object(files.get("expectations.json"));
        CaseInputs.require(SubscriptionPeriodCase.CASE_ID.equals(fixtures.get("caseId").getAsString())
                && SubscriptionPeriodCase.CASE_ID.equals(expected.get("caseId").getAsString())
                && "bound-item-renewal-v1".equals(expected.get("policy").getAsString())
                && fixtures.get("formatVersion").getAsInt() == 1 && expected.get("formatVersion").getAsInt() == 1
                && BASELINE_API_VERSION.equals(fixtures.getAsJsonObject("apiVersions").get("baseline").getAsString())
                && TARGET_API_VERSION.equals(fixtures.getAsJsonObject("apiVersions").get("target").getAsString()), "Unsupported subscription case");
        var scenarios = indexed(fixtures); var expectations = indexed(expected);
        CaseInputs.require(!scenarios.isEmpty() && scenarios.keySet().equals(expectations.keySet()), "Scenario coverage mismatch");
        for (var scenario : scenarios.entrySet()) for (String side : List.of("baseline", "target"))
            new SubscriptionPeriodOracle(scenario.getValue().getAsJsonObject(side), scenario.getValue().get("binding"),
                    expectations.get(scenario.getKey()).getAsJsonObject("expected"));
        String id = "verification-" + UUID.randomUUID(); Path directory = Files.createDirectory(state.resolve("verifications").resolve(id));
        Path captured = Files.createDirectory(directory.resolve("inputs"));
        for (var file : files.entrySet()) Files.write(captured.resolve(file.getKey()), file.getValue());
        Files.writeString(captured.resolve("baseline.txt"), baseline, StandardCharsets.UTF_8);
        Files.writeString(captured.resolve("candidate.txt"), candidate, StandardCharsets.UTF_8);
        var jobs = new ArrayList<Map<String, String>>();
        for (String variant : List.of("baseline", "target", "candidate"))
            for (String scenario : scenarios.keySet()) jobs.add(Map.of("variant", variant, "scenarioId", scenario, "containerName", "acw-" + UUID.randomUUID()));
        Files.createDirectory(directory.resolve("jobs"));
        var spec = new TreeMap<String, Object>();
        spec.put("formatVersion", 1); spec.put("verificationId", id); spec.put("imageId", image);
        spec.put("caseId", SubscriptionPeriodCase.CASE_ID); spec.put("protocol", "subscription-period-v1");
        spec.put("candidateId", candidateId); if (manifest != null) spec.put("candidateManifestSha256", manifest);
        if (repositoryInput != null) spec.put("repositoryInput", repositoryInput);
        spec.put("inputs", CaseInputs.inventory(captured));
        spec.put("jobs", jobs); spec.put("harnessSha256", harness()); spec.put("deadlineSeconds", 60);
        spec.put("createdAt", Instant.now().toString());
        DurableExecution.publish(directory.resolve("spec.json"), spec);
        authorization(directory, image).issue(Duration.ofMinutes(30));
        return id;
    }
    public JsonObject execute(String id) throws Exception {
        Path directory = directory(id); var spec = read(directory.resolve("spec.json"));
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("status", "ACTIVE"));
            if (Files.exists(directory.resolve("result.json"))) return retained(directory);
            if (Files.exists(directory.resolve("started.json"))) return recover(directory, spec);
            verifyInputs(directory, spec);
            CaseInputs.require(harness().equals(spec.get("harnessSha256").getAsString()), "Verifier build changed; register a new attempt");
            DurableExecution.publish(directory.resolve("started.json"), Map.of("startedAt", Instant.now().toString()));
            String image = spec.get("imageId").getAsString();
            var guard = new ExecutionGuard(() -> Files.exists(directory.resolve("cancel.request")), authorization(directory, image));
            var fixtures = indexed(read(directory.resolve("inputs/fixtures.json")));
            var expectations = indexed(read(directory.resolve("inputs/expectations.json")));
            var observations = new LinkedHashMap<String, List<ExecutionObservation>>();
            for (String variant : List.of("baseline", "target", "candidate")) observations.put(variant, new ArrayList<>());
            for (var value : spec.getAsJsonArray("jobs")) {
                if (guard.getAsBoolean()) return finish(directory, spec, guard.stopStatus(), null);
                var job = value.getAsJsonObject(); String variant = job.get("variant").getAsString(), scenario = job.get("scenarioId").getAsString();
                String sdk = variant.equals("baseline") ? "17.4.0" : "20.33.0", api = variant.equals("baseline") ? "2019-12-03" : "2020-08-27";
                String source = Files.readString(directory.resolve("inputs/" + (variant.equals("candidate") ? "candidate" : "baseline") + ".txt"));
                // The registration fixes names before any Docker action; recovery can always find owned workers.
                JsonObject result;
                if (subscription(spec)) {
                    var fixture = fixtures.get(scenario);
                    var oracle = new SubscriptionPeriodOracle(fixture.getAsJsonObject(variant.equals("baseline") ? "baseline" : "target"),
                            fixture.get("binding"), expectations.get(scenario).getAsJsonObject("expected"));
                    result = sandbox.subscription(image, job.get("containerName").getAsString(), source, oracle,
                            Duration.ofSeconds(spec.get("deadlineSeconds").getAsLong()), guard);
                } else {
                    var oracle = new RepairOracle(fixtures.get(scenario), expectations.get(scenario), variant.equals("baseline") ? "baseline" : "target", api);
                    result = sandbox.run(image, job.get("containerName").getAsString(), source, sdk, oracle,
                            Duration.ofSeconds(spec.get("deadlineSeconds").getAsLong()), guard);
                }
                result.addProperty("variant", variant); result.addProperty("scenarioId", scenario);
                DurableExecution.publish(directory.resolve("jobs/" + variant + "-" + scenario + ".json"), result);
                CaseInputs.require(result.get("containerRemoved").getAsBoolean(), "Worker cleanup incomplete");
                var assertion = ExecutionObservation.AssertionOutcome.valueOf(result.get("assertionOutcome").getAsString());
                var disposition = assertion != ExecutionObservation.AssertionOutcome.NOT_EVALUATED ? ExecutionObservation.Disposition.COMPLETED
                        : switch (result.get("executionStatus").getAsString()) {
                            case "TIMED_OUT" -> ExecutionObservation.Disposition.TIMED_OUT;
                            case "CANCELLED" -> ExecutionObservation.Disposition.CANCELLED;
                            default -> ExecutionObservation.Disposition.INFRASTRUCTURE_ERROR;
                        };
                observations.get(variant).add(new ExecutionObservation(scenario, snapshot(spec, variant),
                        disposition, assertion));
            }
            verifyInputs(directory, spec);
            if (guard.getAsBoolean()) return finish(directory, spec, guard.stopStatus(), null);
            var definition = definition(spec, List.copyOf(fixtures.keySet()));
            var evaluation = RepairEvaluation.evaluate(definition, observations.get("baseline"), observations.get("target"), observations.get("candidate"));
            DurableExecution.publish(directory.resolve("observations.json"), observations);
            return finish(directory, spec, "COMPLETED", evaluation);
        }
    }
    public void revoke(String id) throws IOException {
        Path directory = directory(id); authorization(directory, read(directory.resolve("spec.json")).get("imageId").getAsString()).revoke();
    }
    public JsonObject cancel(String id) throws Exception {
        Path directory = directory(id);
        try { Files.writeString(directory.resolve("cancel.request"), "", StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ignored) { }
        return execute(id);
    }
    private JsonObject recover(Path directory, JsonObject spec) throws Exception {
        for (var job : spec.getAsJsonArray("jobs")) sandbox.clean(job.getAsJsonObject().get("containerName").getAsString(), spec.get("imageId").getAsString());
        return finish(directory, spec, "INTERRUPTED", null);
    }
    private static JsonObject finish(Path directory, JsonObject spec, String status, RepairEvaluation.Assessment evaluation) throws IOException {
        var result = json(Map.of("formatVersion", 1, "verificationId", spec.get("verificationId"), "candidateId", spec.get("candidateId"),
                "status", status, "authority", "host-recorded-synthetic-service-behavior", "finishedAt", Instant.now().toString(),
                "specSha256", CaseInputs.hash(Files.readAllBytes(directory.resolve("spec.json")))));
        if (evaluation != null) result.add("evaluation", CaseInputs.JSON.toJsonTree(evaluation));
        result.addProperty("authorizationDecision", authorization(directory, spec.get("imageId").getAsString()).decision().name());
        var hashes = new TreeMap<String, String>(CaseInputs.inventory(directory.resolve("jobs")));
        result.add("jobArtifacts", CaseInputs.JSON.toJsonTree(hashes));
        if (Files.exists(directory.resolve("observations.json"))) result.addProperty("observationsSha256", CaseInputs.hash(Files.readAllBytes(directory.resolve("observations.json"))));
        DurableExecution.publish(directory.resolve("result.json"), result); return result;
    }
    private record Retained(JsonObject result, JsonObject spec, byte[] observations, String receiptSha256, RepositoryInput repositoryInput) {}
    public record SubscriptionRepairEvidence(String verificationId, String receiptSha256, String baseSourceSha256,
                                             String repairSourceSha256, RepairEvaluation.Conclusion conclusion) {}
    public enum VerificationStatus { COMPLETED, CANCELLED, AUTHORIZATION_DENIED, INTERRUPTED }
    public record RecordedOutcome(VerificationStatus status, String authorizationDecision, Instant finishedAt) {}
    record SubscriptionReview(SubscriptionRepairEvidence evidence, RecordedOutcome outcome, RepairEvaluation.Assessment assessment,
                              RepositoryInput repositoryInput) {}

    static JsonObject retained(Path directory) throws IOException { return loadRetained(directory).result(); }

    private static Retained loadRetained(Path directory) throws IOException {
        byte[] receipt = recordBytes(directory.resolve("result.json"));
        var result = CaseInputs.object(receipt);
        byte[] registration = recordBytes(directory.resolve("spec.json"));
        CaseInputs.require(result.get("specSha256").getAsString().equals(CaseInputs.hash(registration)), "Verification registration changed");
        var spec = CaseInputs.object(registration);
        var repositoryInput = verifyInputs(directory, spec);
        CaseInputs.require(result.get("jobArtifacts").equals(CaseInputs.JSON.toJsonTree(CaseInputs.inventory(directory.resolve("jobs")))), "Verification evidence changed");
        byte[] observations = null;
        if (result.has("observationsSha256")) {
            observations = recordBytes(directory.resolve("observations.json"));
            CaseInputs.require(result.get("observationsSha256").getAsString().equals(CaseInputs.hash(observations)), "Observations changed");
        }
        return new Retained(result, spec, observations, CaseInputs.hash(receipt), repositoryInput);
    }

    static SubscriptionReview subscriptionRepairReview(Path state, String id) throws IOException {
        Path root = state.toAbsolutePath().normalize().resolve("verifications");
        Path directory = verificationDirectory(root, id);
        ConsumerTasks.directory(root);
        ConsumerTasks.directory(directory);
        ConsumerTasks.directory(directory.resolve("inputs"));
        ConsumerTasks.directory(directory.resolve("jobs"));
        var retained = loadRetained(directory);
        var result = retained.result();
        var spec = retained.spec();
        CaseInputs.require(id.equals(result.get("verificationId").getAsString()) && id.equals(spec.get("verificationId").getAsString())
                && new JsonPrimitive(1).equals(result.get("formatVersion")) && new JsonPrimitive(1).equals(spec.get("formatVersion"))
                && "host-recorded-synthetic-service-behavior".equals(result.get("authority").getAsString())
                && spec.get("candidateId").equals(result.get("candidateId")), "Unexpected verification identity");
        var status = VerificationStatus.valueOf(result.get("status").getAsString());
        var authorization = ExecutionAuthorization.Decision.valueOf(result.get("authorizationDecision").getAsString());
        var outcome = new RecordedOutcome(status, authorization.name(), Instant.parse(result.get("finishedAt").getAsString()));
        if (!subscription(spec) || status != VerificationStatus.COMPLETED || authorization != ExecutionAuthorization.Decision.ALLOWED) {
            return new SubscriptionReview(new SubscriptionRepairEvidence(id, retained.receiptSha256(), null, null, null),
                    outcome, null, retained.repositoryInput());
        }
        CaseInputs.require(retained.observations() != null, "Missing verification observations");
        byte[] fixtures = recordBytes(directory.resolve("inputs/fixtures.json"));
        CaseInputs.require(CaseInputs.hash(fixtures).equals(spec.getAsJsonObject("inputs").get("fixtures.json").getAsString()),
                "Verification fixtures changed");
        var definition = definition(spec, List.copyOf(indexed(CaseInputs.object(fixtures)).keySet()));
        var observations = CaseInputs.object(retained.observations());
        CaseInputs.require(observations.keySet().equals(Set.of("baseline", "target", "candidate")), "Invalid observations");
        var assessment = RepairEvaluation.evaluate(definition, observations(observations, "baseline"),
                observations(observations, "target"), observations(observations, "candidate"));
        CaseInputs.require(CaseInputs.JSON.toJsonTree(assessment).equals(result.get("evaluation")), "Verification conclusion changed");
        var evidence = new SubscriptionRepairEvidence(id, retained.receiptSha256(), definition.transition().target().source().sha256(),
                definition.repairedTarget().source().sha256(), assessment.conclusion());
        return new SubscriptionReview(evidence, outcome, assessment, retained.repositoryInput());
    }

    private static RepairEvaluation.Definition definition(JsonObject spec, List<String> scenarios) {
        return new RepairEvaluation.Definition(new ComparisonDefinition(snapshot(spec, "baseline"), snapshot(spec, "target"), scenarios),
                snapshot(spec, "candidate"));
    }

    private static List<ExecutionObservation> observations(JsonObject data, String side) {
        var values = data.getAsJsonArray(side);
        var parsed = CaseInputs.JSON.fromJson(values, ExecutionObservation[].class);
        CaseInputs.require(values.equals(CaseInputs.JSON.toJsonTree(parsed)), "Invalid observation fields");
        return List.of(parsed);
    }
    private static InputSnapshot snapshot(JsonObject spec, String variant) {
        var hashes = spec.getAsJsonObject("inputs");
        if (subscription(spec)) return new InputSnapshot(SubscriptionPeriodCase.CASE_ID, "stripe",
                variant.equals("baseline") ? BASELINE_API_VERSION : TARGET_API_VERSION,
                new ContentDigest(hashes.get(variant.equals("candidate") ? "candidate.txt" : "baseline.txt").getAsString()),
                new ContentDigest(subscriptionConfiguration(spec)),
                new ContentDigest(hashes.get("expectations.json").getAsString()), new ContentDigest(hashes.get("fixtures.json").getAsString()),
                new ContentDigest(spec.get("harnessSha256").getAsString()),
                new ContentDigest(CaseInputs.identity(new TreeMap<>(Map.of("imageId", spec.get("imageId"), "boundary", "repair-container-v1")))));
        return new InputSnapshot(CaseInputs.CASE_ID, "stripe", variant.equals("baseline") ? "2019-12-03" : "2020-08-27",
                new ContentDigest(hashes.get(variant.equals("candidate") ? "candidate.txt" : "baseline.txt").getAsString()),
                new ContentDigest(CaseInputs.identity(new TreeMap<>(Map.of("sdkSha256", CaseInputs.EXECUTABLES.get(variant.equals("baseline") ? "stripe-java-17.4.0.jar" : "stripe-java-20.33.0.jar"), "protocol", "host-service-v1")))),
                new ContentDigest(hashes.get("expectations.json").getAsString()), new ContentDigest(hashes.get("fixtures.json").getAsString()),
                new ContentDigest(spec.get("harnessSha256").getAsString()), new ContentDigest(CaseInputs.identity(new TreeMap<>(Map.of("imageId", spec.get("imageId"), "boundary", "repair-container-v1")))));
    }
    static String harness() throws IOException {
        try {
            Path code = Path.of(RepairVerification.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return CaseInputs.identity(new TreeMap<>(Map.of("code", Files.isDirectory(code) ? CaseInputs.identity(CaseInputs.inventory(code)) : CaseInputs.hash(Files.readAllBytes(code)),
                    "json", CaseInputs.hash(Files.readAllBytes(gson)), "java", System.getProperty("java.runtime.version"))));
        } catch (java.net.URISyntaxException e) { throw new IOException(e); }
    }
    private static ExecutionAuthorization authorization(Path directory, String image) {
        return new ExecutionAuthorization(directory, directory.getFileName().toString(), image, "repair-verification", Clock.systemUTC());
    }
    private Path directory(String id) {
        return verificationDirectory(state.resolve("verifications"), id);
    }
    private static Path verificationDirectory(Path root, String id) {
        CaseInputs.require(id != null && id.matches("verification-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid verification ID");
        return root.resolve(id);
    }
    private static String subscriptionConfiguration(JsonObject spec) {
        String configuration = "bound-item-renewal-v1/subscription-period-v1";
        return spec.has("repositoryInput") ? CaseInputs.identity(List.of(configuration, spec.get("repositoryInput")))
                : CaseInputs.identity(configuration);
    }

    private static RepositoryInput verifyInputs(Path directory, JsonObject spec) throws IOException {
        CaseInputs.require(spec.get("inputs").equals(CaseInputs.JSON.toJsonTree(CaseInputs.inventory(directory.resolve("inputs")))), "Verification inputs changed");
        boolean bound = SubscriptionRepositoryVerification.CANDIDATE_ID.equals(spec.get("candidateId").getAsString());
        CaseInputs.require(bound == spec.has("repositoryInput"), "Missing or unexpected repository input");
        if (!bound) return null;
        CaseInputs.require(subscription(spec), "Repository input requires the subscription protocol");
        return SubscriptionRepositoryVerification.retained(spec.getAsJsonObject("repositoryInput"), recordBytes(directory.resolve("inputs/baseline.txt")),
                recordBytes(directory.resolve("inputs/candidate.txt")));
    }
    private static Map<String, JsonObject> indexed(JsonObject data) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var item : data.getAsJsonArray("scenarios")) {
            var value = item.getAsJsonObject(); String id = value.get("id").getAsString();
            CaseInputs.require(id.matches("[a-z0-9-]+") && result.putIfAbsent(id, value) == null, "Invalid scenario identity");
        }
        return result;
    }
    private static boolean subscription(JsonObject spec) {
        if (!spec.has("protocol")) return false;
        CaseInputs.require("subscription-period-v1".equals(spec.get("protocol").getAsString())
                && SubscriptionPeriodCase.CASE_ID.equals(spec.get("caseId").getAsString()), "Unsupported verification protocol");
        return true;
    }
    private static JsonObject read(Path path) throws IOException {
        return CaseInputs.object(recordBytes(path));
    }
    private static byte[] recordBytes(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Invalid verification record");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = stream.readNBytes(1048576);
            CaseInputs.require(bytes.length < 1048576, "Verification record exceeds limit");
            return bytes;
        }
    }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    public static void main(String[] args) throws Exception {
        CaseInputs.require(args.length == 3 || args.length == 6, "Usage: RepairVerification start STATE CANDIDATE CASE DEPENDENCIES IMAGE | resume/cancel/revoke STATE ID");
        var verifier = new RepairVerification(Path.of(args[1])); String id;
        if (args[0].equals("subscription") && args.length == 6)
            id = verifier.registerSubscription(Path.of(args[4]), sourceFile(Path.of(args[2])), sourceFile(Path.of(args[3])), args[5]);
        else if (args[0].equals("start") && args.length == 6) id = verifier.create(args[2], Path.of(args[3]), Path.of(args[4]), args[5]);
        else { CaseInputs.require(args.length == 3 && Set.of("resume", "cancel", "revoke").contains(args[0]), "Invalid verification command"); id = args[2]; }
        System.out.println("Verification: " + id);
        if (args[0].equals("revoke")) verifier.revoke(id);
        var result = args[0].equals("cancel") ? verifier.cancel(id) : verifier.execute(id);
        System.out.println("Status: " + result.get("status").getAsString());
        if (result.has("evaluation")) System.out.println("Evaluation: " + result.getAsJsonObject("evaluation").get("conclusion").getAsString());
        System.out.println("Result: " + verifier.directory(id).resolve("result.json"));
    }
    private static String sourceFile(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 128000, "Invalid source file");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
