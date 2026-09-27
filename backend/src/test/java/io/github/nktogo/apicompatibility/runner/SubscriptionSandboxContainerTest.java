package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "workbench.repair.image", matches = "sha256:[0-9a-f]{64}")
class SubscriptionSandboxContainerTest {
    static String source(String expression) {
        return """
            import com.google.gson.*;
            public class PeriodConsumer {
              public static JsonObject consume(JsonObject subscription, String binding) {
                try {
                  if (binding == null || binding.isBlank() || !subscription.get("status").getAsString().equals("active")) throw new IllegalArgumentException();
                  var items = subscription.getAsJsonObject("items");
                  if (items.get("has_more").getAsBoolean()) throw new IllegalArgumentException();
                  JsonObject selected = null;
                  for (var item : items.getAsJsonArray("data")) {
                    var row = item.getAsJsonObject();
                    if (binding.equals(row.get("id").getAsString())) {
                      if (selected != null) throw new IllegalArgumentException();
                      selected = row;
                    }
                  }
                  if (selected == null) throw new IllegalArgumentException();
                  long end = %s;
                  if (end <= 0) throw new IllegalArgumentException();
                  var value = new JsonObject(); value.addProperty("status", "RESOLVED"); value.addProperty("periodEnd", end); return value;
                } catch (RuntimeException e) {
                  var value = new JsonObject(); value.addProperty("status", "DEFERRED"); return value;
                }
              }
            }
            """.formatted(expression);
    }
    static JsonObject run(String source, String side, JsonObject scenario, JsonObject expected, Duration deadline,
                          java.util.function.BooleanSupplier stopped) throws Exception {
        return RepairSandbox.runSubscription(System.getProperty("workbench.repair.image"), "acw-" + UUID.randomUUID(), source,
                new SubscriptionPeriodOracle(scenario.getAsJsonObject(side), scenario.get("binding"), expected), deadline, stopped);
    }
    static Map<String, byte[]> inputs() throws Exception {
        return SubscriptionPeriodCase.load(Path.of(System.getProperty("workbench.repository"), "cases", SubscriptionPeriodCase.CASE_ID));
    }
    @Test void compiledControlsRetainBaselineRegressionsAndSemanticRepairResults() throws Exception {
        var files = inputs(); var scenarios = CaseInputs.object(files.get("fixtures.json")).getAsJsonArray("scenarios");
        var expectations = CaseInputs.object(files.get("expectations.json")).getAsJsonArray("scenarios");
        var expressions = Map.of("baseline", "subscription.get(\"current_period_end\").getAsLong()",
                "target", "subscription.get(\"current_period_end\").getAsLong()",
                "first", "items.getAsJsonArray(\"data\").get(0).getAsJsonObject().get(\"current_period_end\").getAsLong()",
                "bound", "selected.get(\"current_period_end\").getAsLong()");
        Path output = Path.of(System.getProperty("workbench.repository"), "backend/target/subscription-container-tests"); Files.createDirectories(output);
        for (var variant : expressions.entrySet()) {
            int passed = 0;
            for (int i = 0; i < scenarios.size(); i++) {
                var scenario = scenarios.get(i).getAsJsonObject();
                assertEquals(scenario.get("id"), expectations.get(i).getAsJsonObject().get("id"));
                var result = run(source(variant.getValue()), variant.getKey().equals("baseline") ? "baseline" : "target", scenario,
                        expectations.get(i).getAsJsonObject().getAsJsonObject("expected"), Duration.ofSeconds(30), () -> false);
                CaseInputs.write(output.resolve(variant.getKey() + "-" + scenario.get("id").getAsString() + ".json"), result);
                assertEquals("COMPLETED", result.get("executionStatus").getAsString(), result.toString());
                assertTrue(result.get("containerRemoved").getAsBoolean());
                if (result.get("assertionOutcome").getAsString().equals("PASSED")) passed++;
            }
            assertEquals(Map.of("baseline", 8, "target", 4, "first", 6, "bound", 8).get(variant.getKey()), passed);
        }
    }
    @Test void invalidCodeForgedVerdictsAndHungConsumersCannotPass() throws Exception {
        var files = inputs(); var scenario = CaseInputs.object(files.get("fixtures.json")).getAsJsonArray("scenarios").get(0).getAsJsonObject();
        var expected = CaseInputs.object(files.get("expectations.json")).getAsJsonArray("scenarios").get(0).getAsJsonObject().getAsJsonObject("expected");
        for (String source : List.of("invalid Java", source("selected.get(\"current_period_end\").getAsLong()")
                .replace("try {", "try { System.out.println(\"{\\\"op\\\":\\\"PASSED\\\"}\");"),
                "public class PeriodConsumer { public static com.google.gson.JsonObject consume(com.google.gson.JsonObject s, String b) { while(true) {} } }")) {
            var result = run(source, "target", scenario, expected, Duration.ofSeconds(5), () -> false);
            assertEquals("NOT_EVALUATED", result.get("assertionOutcome").getAsString()); assertTrue(result.get("containerRemoved").getAsBoolean());
        }
    }
    @Test void subscriptionWorkerCannotReadExpectationsAndHonorsCancellation() throws Exception {
        var files = inputs(); var scenario = CaseInputs.object(files.get("fixtures.json")).getAsJsonArray("scenarios").get(0).getAsJsonObject();
        var expected = CaseInputs.object(files.get("expectations.json")).getAsJsonArray("scenarios").get(0).getAsJsonObject().getAsJsonObject("expected");
        String safe = source("selected.get(\"current_period_end\").getAsLong()").replace("try {", "try { if (java.nio.file.Files.exists(java.nio.file.Path.of(\"/opt/workbench/case/expectations.json\")) || System.getenv().containsKey(\"OPENAI_API_KEY\")) throw new AssertionError();");
        assertEquals("PASSED", run(safe, "target", scenario, expected, Duration.ofSeconds(30), () -> false).get("assertionOutcome").getAsString());
        long stop = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var cancelled = run("public class PeriodConsumer { public static com.google.gson.JsonObject consume(com.google.gson.JsonObject s, String b) { while(true) {} } }",
                "target", scenario, expected, Duration.ofSeconds(30), () -> System.nanoTime() >= stop);
        assertEquals("CANCELLED", cancelled.get("executionStatus").getAsString()); assertTrue(cancelled.get("containerRemoved").getAsBoolean());
    }
}
