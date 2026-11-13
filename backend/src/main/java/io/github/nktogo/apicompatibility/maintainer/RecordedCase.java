package io.github.nktogo.apicompatibility.maintainer;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.nktogo.apicompatibility.compatibility.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** An allowlisted projection of a supplied report, not authenticated execution evidence. */
public final class RecordedCase {
    static final int MAX_BYTES = 1024 * 1024;
    static final String CASE_ID = "stripe-customer-sources-2020-08-27";
    static final String EVIDENCE_KIND = "isolated-consumer-methods-with-synthetic-fixtures";
    static final List<String> SCENARIOS = List.of("modern-method-survives-omission",
            "empty-account-survives-omission", "legacy-method-not-lost",
            "present-legacy-collection-control", "confirmed-removed-legacy-method");
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public record Variant(String id, InputSnapshot inputs, List<ExecutionObservation> observations) {
        public Variant { observations = List.copyOf(observations); }
    }
    public record RepairSummary(RepairEvaluation.Conclusion conclusion,
                                List<RepairEvaluation.Scenario> scenarios, List<String> boundaryIssues) {
        public RepairSummary { scenarios = List.copyOf(scenarios); boundaryIssues = List.copyOf(boundaryIssues); }
        public long count(RepairEvaluation.Outcome outcome) {
            return scenarios.stream().filter(s -> s.outcome() == outcome).count();
        }
    }
    public record Summary(int formatVersion, String caseId, String evidenceKind, String recordedAt,
                          String reportSha256, String authority, boolean currentInputsVerified,
                          boolean retainedArtifactsVerified, boolean executionAuthenticated,
                          ComparisonAssessment upgrade, ComparisonAssessment historicalPatch,
                          RepairSummary repair, List<Variant> variants) {
        public Summary { variants = List.copyOf(variants); }
    }

    private RecordedCase() {}

    public static Summary load(Path selectedReport) throws IOException {
        Path file = selectedReport.toAbsolutePath().normalize();
        // Local storage must remain trusted while opening the snapshot. No report-supplied paths are followed.
        for (Path part = file; part != null; part = part.getParent()) {
            var attributes = Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            require(!attributes.isSymbolicLink() && !attributes.isOther(), "Linked or special report path");
        }
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Report must be a regular file");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = Channels.newInputStream(channel)) {
            return decode(stream.readNBytes(MAX_BYTES + 1));
        }
    }

    static Summary decode(byte[] bytes) throws IOException {
        require(bytes.length > 0 && bytes.length <= MAX_BYTES, "Report exceeds the byte limit");
        JsonObject report;
        try (var reader = new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString()))) {
            reader.setStrictness(Strictness.STRICT);
            report = object(element(reader, 0));
            require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing report content");
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid recorded report", e); }
        require(report.has("formatVersion") && report.get("formatVersion").isJsonPrimitive()
                && report.getAsJsonPrimitive("formatVersion").isNumber()
                && report.get("formatVersion").getAsString().equals("1"), "Unsupported report format");
        require(text(report, "caseId").equals(CASE_ID), "Unsupported case");
        require(text(report, "evidenceKind").equals(EVIDENCE_KIND), "Unsupported evidence kind");
        String recordedAt = Instant.parse(text(report, "completedAt")).toString();
        var assessments = object(report.get("assessments"));
        var upgrade = definition(object(assessments.get("target")).get("definition"));
        var patch = definition(object(assessments.get("upstream-reference")).get("definition"));
        require(upgrade.baseline().equals(patch.baseline())
                && upgrade.requiredScenarioIds().equals(patch.requiredScenarioIds()), "Different declared baselines");
        var observations = report.has("observations") ? object(report.get("observations")) : new JsonObject();
        require(Set.of("baseline", "target", "upstream-reference").containsAll(observations.keySet()),
                "Unknown observation variant");
        var baseline = observations(observations, "baseline");
        var target = observations(observations, "target");
        var repaired = observations(observations, "upstream-reference");
        var original = CompatibilityComparison.compare(upgrade, baseline, target);
        var historical = CompatibilityComparison.compare(patch, baseline, repaired);
        var repair = RepairEvaluation.evaluate(new RepairEvaluation.Definition(upgrade, patch.target()), baseline, target, repaired);
        return new Summary(1, CASE_ID, EVIDENCE_KIND, recordedAt, sha256(bytes), "SUPPLIED_RECORDED_REPORT",
                false, false, false, original, historical,
                new RepairSummary(repair.conclusion(), repair.scenarios(), repair.boundaryIssues()), List.of(
                new Variant("baseline", upgrade.baseline(), baseline),
                new Variant("target", upgrade.target(), target),
                new Variant("upstream-reference", patch.target(), repaired)));
    }

    private static ComparisonDefinition definition(JsonElement value) {
        var o = object(value); fields(o, "baseline", "target", "requiredScenarioIds");
        var before = snapshot(o.get("baseline")); var after = snapshot(o.get("target"));
        require(before.caseId().equals(CASE_ID) && after.caseId().equals(CASE_ID)
                && before.providerId().equals("stripe") && after.providerId().equals("stripe")
                && before.providerVersion().equals("2019-12-03") && after.providerVersion().equals("2020-08-27"),
                "Unsupported declared transition");
        require(JSON.toJsonTree(SCENARIOS).equals(o.get("requiredScenarioIds")), "Unsupported declared scenarios");
        return new ComparisonDefinition(before, after, SCENARIOS);
    }

    private static List<ExecutionObservation> observations(JsonObject parent, String key) {
        if (!parent.has(key)) return List.of();
        require(parent.get(key).isJsonArray(), "Invalid observation list");
        var array = parent.getAsJsonArray(key); require(array.size() <= 100, "Too many observations");
        var result = new ArrayList<ExecutionObservation>();
        for (var value : array) {
            var o = object(value); fields(o, "scenarioId", "inputs", "disposition", "assertionOutcome");
            result.add(new ExecutionObservation(token(o, "scenarioId"), snapshot(o.get("inputs")),
                    ExecutionObservation.Disposition.valueOf(text(o, "disposition")),
                    ExecutionObservation.AssertionOutcome.valueOf(text(o, "assertionOutcome"))));
        }
        return List.copyOf(result);
    }

    private static InputSnapshot snapshot(JsonElement value) {
        var o = object(value); fields(o, "caseId", "providerId", "providerVersion", "source", "configuration",
                "expectations", "fixtures", "harness", "environment");
        for (String key : List.of("caseId", "providerId", "providerVersion")) token(o, key);
        for (String key : List.of("source", "configuration", "expectations", "fixtures", "harness", "environment")) {
            var digest = object(o.get(key)); fields(digest, "sha256"); new ContentDigest(text(digest, "sha256"));
        }
        return JSON.fromJson(o, InputSnapshot.class);
    }

    private static String token(JsonObject o, String key) {
        String value = text(o, key); require(value.matches("[a-z0-9][a-z0-9.-]{0,95}"), "Invalid metadata token"); return value;
    }
    private static String text(JsonObject o, String key) {
        var value = o.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Missing text field");
        String result = value.getAsString(); require(result.length() <= 128, "Text field exceeds limit"); return result;
    }
    private static JsonObject object(JsonElement value) {
        require(value != null && value.isJsonObject(), "Expected an object"); return value.getAsJsonObject();
    }
    private static void fields(JsonObject o, String... fields) {
        require(o.keySet().equals(Set.of(fields)), "Unexpected record fields");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static JsonElement element(JsonReader reader, int depth) throws IOException {
        require(depth <= 32, "Report nesting exceeded");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var o = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName(); require(!o.has(key) && o.size() < 2000, "Duplicate or excessive fields");
                    o.add(key, element(reader, depth + 1));
                }
                reader.endObject(); yield o;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var a = new JsonArray();
                while (reader.hasNext()) { require(a.size() < 2000, "Array limit exceeded"); a.add(element(reader, depth + 1)); }
                reader.endArray(); yield a;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Invalid JSON value");
        };
    }
}
