package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only investigation of one retained subscription execution; never dispatches a worker. */
public final class SubscriptionInvestigationTools {
    static final Map<String, String> INPUTS = Map.of("baseline-source", "baseline.txt",
            "provenance", "README.md", "expectations", "expectations.json", "fixtures", "fixtures.json");
    private final Path state;
    public SubscriptionInvestigationTools(Path state) throws IOException {
        this.state = state.toAbsolutePath().normalize();
        Files.createDirectories(this.state.resolve("subscription-investigations"));
    }
    public String register(String verificationId) throws IOException {
        Path evidence = evidence(verificationId); validateEvidence(evidence);
        String id = "subscription-investigation-" + UUID.randomUUID();
        Path directory = Files.createDirectory(directory(id));
        DurableExecution.publish(directory.resolve("registration.json"), Map.of("formatVersion", 1,
                "contextId", id, "verificationId", verificationId, "resultSha256", hash(evidence.resolve("result.json"))));
        return id;
    }
    public JsonObject invoke(String context, String tool, JsonObject arguments) {
        try {
            var registration = read(directory(context).resolve("registration.json"));
            CaseInputs.require(registration.get("formatVersion").getAsInt() == 1
                    && context.equals(registration.get("contextId").getAsString()), "Invalid subscription context");
            Path evidence = evidence(registration.get("verificationId").getAsString());
            try {
                validateEvidence(evidence);
                CaseInputs.require(hash(evidence.resolve("result.json")).equals(registration.get("resultSha256").getAsString()), "Changed evidence");
            } catch (IllegalArgumentException e) { return error("INPUT_IDENTITY_MISMATCH"); }
            Object value = switch (tool) {
                case "describe_case" -> {
                    keys(arguments);
                    var inputs = new TreeMap<String, String>();
                    for (var entry : INPUTS.entrySet()) inputs.put(entry.getKey(), hash(evidence.resolve("inputs").resolve(entry.getValue())));
                    yield Map.of("caseId", SubscriptionPeriodCase.CASE_ID, "inputs", inputs,
                            "verificationId", registration.get("verificationId").getAsString(), "maxTestRuns", 0,
                            "executionMode", "retained-only",
                            "limitations", "Synthetic subscription case. run_case and read_run both read the registered completed evidence; neither starts execution. provenance contains application policy and source references. Prior candidate source and results are not exposed.");
                }
                case "read_input" -> {
                    keys(arguments, "inputId", "offset", "maxCharacters");
                    String input = text(arguments, "inputId");
                    int offset = integer(arguments, "offset"), max = integer(arguments, "maxCharacters");
                    CaseInputs.require(INPUTS.containsKey(input) && offset >= 0 && max >= 1 && max <= 4000, "Invalid page");
                    byte[] bytes = bytes(evidence.resolve("inputs").resolve(INPUTS.get(input)));
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    CaseInputs.require(offset <= content.length(), "Invalid offset"); int end = Math.min(content.length(), offset + max);
                    yield Map.of("inputId", input, "offset", offset, "nextOffset", end, "totalCharacters", content.length(),
                            "content", content.substring(offset, end), "sha256", CaseInputs.hash(bytes), "contentTrust", "untrusted-data");
                }
                case "run_case", "read_run" -> {
                    keys(arguments, "requestId");
                    CaseInputs.require(text(arguments, "requestId").matches("[a-zA-Z0-9_-]{1,80}"), "Invalid request");
                    var observations = read(evidence.resolve("observations.json"));
                    var rows = new TreeMap<String, Object>();
                    for (String side : List.of("baseline", "target")) {
                        var summaries = new ArrayList<Object>();
                        for (var item : observations.getAsJsonArray(side)) {
                            var row = item.getAsJsonObject(); summaries.add(Map.of("scenarioId", row.get("scenarioId"),
                                    "disposition", row.get("disposition"), "assertionOutcome", row.get("assertionOutcome")));
                        }
                        rows.put(side, summaries);
                    }
                    yield Map.of("authority", "recorded-execution", "status", "COMPLETED", "executionMode", "retained-only",
                            "verificationId", registration.get("verificationId"), "resultSha256", registration.get("resultSha256"), "observations", rows);
                }
                default -> throw new IllegalArgumentException("Unsupported tool");
            };
            return json(Map.of("formatVersion", 1, "ok", true, "value", value));
        } catch (IllegalArgumentException | NullPointerException e) { return error("INVALID_REQUEST"); }
        catch (IOException e) { return error("UNAVAILABLE"); }
    }
    private static void validateEvidence(Path directory) throws IOException {
        var result = RepairVerification.retained(directory); var spec = read(directory.resolve("spec.json"));
        CaseInputs.require("COMPLETED".equals(result.get("status").getAsString())
                && spec.has("protocol") && "subscription-period-v1".equals(spec.get("protocol").getAsString())
                && SubscriptionPeriodCase.CASE_ID.equals(spec.get("caseId").getAsString())
                && result.has("observationsSha256"), "Completed subscription evidence required");
    }
    private Path directory(String id) { valid(id, "subscription-investigation"); return state.resolve("subscription-investigations").resolve(id); }
    private Path evidence(String id) { valid(id, "verification"); return state.resolve("verifications").resolve(id); }
    private static void valid(String id, String prefix) { CaseInputs.require(id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid reference"); }
    private static byte[] bytes(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing or linked artifact");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(262145); CaseInputs.require(bytes.length <= 262144, "Artifact too large"); return bytes;
        }
    }
    private static String hash(Path path) throws IOException { return CaseInputs.hash(bytes(path)); }
    private static JsonObject read(Path path) throws IOException { return CaseInputs.object(bytes(path)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject error(String code) { return json(Map.of("formatVersion", 1, "ok", false, "error", code)); }
    private static void keys(JsonObject args, String... keys) { CaseInputs.require(args.keySet().equals(Set.of(keys)), "Unexpected arguments"); }
    private static String text(JsonObject args, String key) {
        CaseInputs.require(args.get(key).isJsonPrimitive() && args.getAsJsonPrimitive(key).isString(), "String required"); return args.get(key).getAsString();
    }
    private static int integer(JsonObject args, String key) {
        CaseInputs.require(args.get(key).isJsonPrimitive() && args.getAsJsonPrimitive(key).isNumber(), "Integer required");
        try { return args.get(key).getAsBigDecimal().intValueExact(); } catch (ArithmeticException e) { throw new IllegalArgumentException("Integer required"); }
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 2, "Usage: SubscriptionInvestigationTools STATE VERIFICATION");
        System.out.println(new SubscriptionInvestigationTools(Path.of(args[0])).register(args[1]));
    }
}
