package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Executes reviewed compiled controls over synthetic projections; never loads candidate code. */
public final class SubscriptionPeriodCase {
    static final String CASE_ID = "stripe-subscription-periods-2025-07-30";
    enum Strategy { ORIGINAL, FIRST_ITEM, MINIMUM, MAXIMUM, BOUND_ITEM }
    private SubscriptionPeriodCase() {}

    static JsonObject consume(JsonObject subscription, String binding, Strategy strategy) {
        try {
            if (binding == null || binding.isBlank() || !subscription.get("object").getAsString().equals("subscription")
                    || !subscription.get("status").getAsString().equals("active")) return deferred();
            var items = subscription.getAsJsonObject("items");
            if (!items.get("object").getAsString().equals("list") || !items.get("has_more").isJsonPrimitive() || !items.getAsJsonPrimitive("has_more").isBoolean()
                    || items.get("has_more").getAsBoolean()) return deferred();
            var data = items.getAsJsonArray("data"); var matches = new ArrayList<JsonObject>();
            for (var item : data) if (binding.equals(item.getAsJsonObject().get("id").getAsString())) matches.add(item.getAsJsonObject());
            if (matches.size() != 1) return deferred();
            long end = switch (strategy) {
                case ORIGINAL -> timestamp(subscription);
                case FIRST_ITEM -> timestamp(data.get(0).getAsJsonObject());
                case BOUND_ITEM -> timestamp(matches.getFirst());
                case MINIMUM -> data.asList().stream().mapToLong(value -> timestamp(value.getAsJsonObject())).min().orElseThrow();
                case MAXIMUM -> data.asList().stream().mapToLong(value -> timestamp(value.getAsJsonObject())).max().orElseThrow();
            };
            return CaseInputs.JSON.toJsonTree(Map.of("status", "RESOLVED", "periodEnd", end)).getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ArithmeticException | ClassCastException e) { return deferred(); }
    }
    private static long timestamp(JsonObject object) {
        var value = object.get("current_period_end");
        CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Numeric timestamp required");
        long end = value.getAsBigDecimal().longValueExact(); CaseInputs.require(end > 0, "Positive timestamp required"); return end;
    }
    private static JsonObject deferred() { return CaseInputs.JSON.toJsonTree(Map.of("status", "DEFERRED")).getAsJsonObject(); }

    public static Path run(Path caseRoot, Path outputRoot) throws IOException {
        var files = load(caseRoot);
        var fixtures = CaseInputs.object(files.get("fixtures.json"));
        var expected = CaseInputs.object(files.get("expectations.json"));
        CaseInputs.require(CASE_ID.equals(fixtures.get("caseId").getAsString()) && CASE_ID.equals(expected.get("caseId").getAsString())
                && fixtures.get("formatVersion").getAsInt() == 1 && expected.get("formatVersion").getAsInt() == 1
                && fixtures.get("origin").getAsString().equals("authored-synthetic")
                && expected.get("policy").getAsString().equals("bound-item-renewal-v1"), "Unsupported period case");
        var versions = fixtures.getAsJsonObject("apiVersions");
        CaseInputs.require(versions.get("baseline").getAsString().equals("2025-02-24.acacia")
                && versions.get("target").getAsString().equals("2025-07-30.basil"), "Unsupported version mapping");
        var scenarios = indexed(fixtures); var expectations = indexed(expected);
        CaseInputs.require(!scenarios.isEmpty() && scenarios.keySet().equals(expectations.keySet()), "Scenario coverage mismatch");
        String harness = RepairVerification.harness();
        var snapshots = new TreeMap<String, InputSnapshot>();
        var observations = new TreeMap<String, List<ExecutionObservation>>();
        var outcomes = new TreeMap<String, Object>();
        var variants = new LinkedHashMap<String, Strategy>(); variants.put("baseline", Strategy.ORIGINAL); variants.put("target", Strategy.ORIGINAL);
        for (Strategy strategy : Strategy.values()) if (strategy != Strategy.ORIGINAL) variants.put(strategy.name().toLowerCase(Locale.ROOT), strategy);
        for (var variant : variants.entrySet()) {
            String side = variant.getKey().equals("baseline") ? "baseline" : "target";
            var snapshot = new InputSnapshot(CASE_ID, "stripe", versions.get(side).getAsString(),
                    digest(CaseInputs.identity(new TreeMap<>(Map.of("compiledControl", harness, "entryPoint", variant.getValue().name())))),
                    digest(CaseInputs.identity("bound-item-renewal-v1")), digest(CaseInputs.hash(files.get("expectations.json"))),
                    digest(CaseInputs.hash(files.get("fixtures.json"))), digest(harness), digest(CaseInputs.identity(System.getProperty("java.runtime.version"))));
            snapshots.put(variant.getKey(), snapshot); var rows = new ArrayList<ExecutionObservation>(); var values = new TreeMap<String, Object>();
            for (var scenario : scenarios.entrySet()) {
                var binding = scenario.getValue().get("binding");
                var actual = consume(scenario.getValue().getAsJsonObject(side), binding.isJsonNull() ? null : binding.getAsString(), variant.getValue());
                var required = expectations.get(scenario.getKey()).getAsJsonObject("expected");
                CaseInputs.require(required.keySet().equals(required.get("status").getAsString().equals("DEFERRED")
                        ? Set.of("status") : Set.of("status", "periodEnd")), "Invalid expected result");
                CaseInputs.require(Set.of("DEFERRED", "RESOLVED").contains(required.get("status").getAsString()), "Invalid expected status");
                if (required.has("periodEnd")) { var check = new JsonObject(); check.add("current_period_end", required.get("periodEnd")); timestamp(check); }
                values.put(scenario.getKey(), Map.of("actual", actual, "expected", required));
                rows.add(new ExecutionObservation(scenario.getKey(), snapshot, ExecutionObservation.Disposition.COMPLETED,
                        actual.equals(required) ? ExecutionObservation.AssertionOutcome.PASSED : ExecutionObservation.AssertionOutcome.FAILED));
            }
            observations.put(variant.getKey(), rows); outcomes.put(variant.getKey(), values);
        }
        var transition = new ComparisonDefinition(snapshots.get("baseline"), snapshots.get("target"), List.copyOf(scenarios.keySet()));
        var repairs = new TreeMap<String, RepairEvaluation.Assessment>();
        for (String variant : variants.keySet()) if (!Set.of("baseline", "target").contains(variant))
            repairs.put(variant, RepairEvaluation.evaluate(new RepairEvaluation.Definition(transition, snapshots.get(variant)),
                    observations.get("baseline"), observations.get("target"), observations.get(variant)));
        Files.createDirectories(outputRoot); Path output = Files.createDirectory(outputRoot.resolve(UUID.randomUUID().toString()));
        Path inputs = Files.createDirectory(output.resolve("inputs"));
        for (var entry : files.entrySet()) Files.write(inputs.resolve(entry.getKey()), entry.getValue(), StandardOpenOption.CREATE_NEW);
        DurableExecution.publish(output.resolve("report.json"), Map.of("formatVersion", 1, "caseId", CASE_ID,
                "authority", "authored-compiled-controls-over-synthetic-projections", "inputs", CaseInputs.inventory(inputs),
                "snapshots", snapshots, "observations", observations, "outcomes", outcomes,
                "originalComparison", CompatibilityComparison.compare(transition, observations.get("baseline"), observations.get("target")), "repairs", repairs));
        return output.resolve("report.json");
    }
    private static ContentDigest digest(String hash) { return new ContentDigest(hash); }
    private static Map<String, JsonObject> indexed(JsonObject document) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var value : document.getAsJsonArray("scenarios")) {
            var row = value.getAsJsonObject(); String id = row.get("id").getAsString();
            CaseInputs.require(id.matches("[a-z0-9-]+") && result.putIfAbsent(id, row) == null, "Invalid scenario identity");
        }
        return result;
    }
    static Map<String, byte[]> load(Path root) throws IOException {
        var result = new TreeMap<String, byte[]>(); byte[] index = read(root.resolve("integrity.json"));
        var hashes = CaseInputs.object(index);
        CaseInputs.require(hashes.keySet().equals(Set.of("fixtures.json", "expectations.json", "README.md")), "Unexpected period inventory");
        for (var entry : hashes.entrySet()) {
            byte[] bytes = read(root.resolve(entry.getKey()));
            CaseInputs.require(CaseInputs.hash(bytes).equals(entry.getValue().getAsString()), "Period case input changed: " + entry.getKey());
            result.put(entry.getKey(), bytes);
        }
        result.put("integrity.json", index); return result;
    }
    private static byte[] read(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 262144, "Invalid period input");
        return Files.readAllBytes(path);
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 2, "Usage: SubscriptionPeriodCase CASE OUTPUT");
        Path report = run(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Report: " + report.toAbsolutePath());
        var result = CaseInputs.object(Files.readAllBytes(report));
        for (var entry : result.getAsJsonObject("repairs").entrySet())
            System.out.println(entry.getKey() + ": " + entry.getValue().getAsJsonObject().get("conclusion").getAsString());
    }
}
