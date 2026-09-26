package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionPeriodCaseTest {
    @TempDir Path temporary;
    private Path caseRoot() { return Path.of(System.getProperty("workbench.repository"), "cases", SubscriptionPeriodCase.CASE_ID); }
    @Test void actualControlsExposeSemanticRepairFailuresWithoutChangingExpectations() throws Exception {
        Path report = SubscriptionPeriodCase.run(caseRoot(), Path.of(System.getProperty("workbench.repository"), "backend/target/subscription-period-case-reports"));
        var result = CaseInputs.object(Files.readAllBytes(report));
        var observations = result.getAsJsonObject("observations");
        for (var entry : Map.of("baseline", 8L, "target", 4L, "first_item", 6L, "minimum", 7L, "maximum", 6L, "bound_item", 8L).entrySet()) {
            var rows = observations.getAsJsonArray(entry.getKey()); assertEquals(8, rows.size());
            assertEquals(entry.getValue(), rows.asList().stream().filter(row -> row.getAsJsonObject().get("assertionOutcome").getAsString().equals("PASSED")).count(), entry.getKey());
        }
        assertEquals("REGRESSION_OBSERVED", result.getAsJsonObject("originalComparison").get("conclusion").getAsString());
        var repairs = result.getAsJsonObject("repairs");
        assertEquals("ALL_OBSERVED_REGRESSIONS_FIXED", repairs.getAsJsonObject("bound_item").get("conclusion").getAsString());
        for (String strategy : List.of("first_item", "minimum", "maximum"))
            assertEquals("REGRESSIONS_REMAIN", repairs.getAsJsonObject(strategy).get("conclusion").getAsString());
        assertEquals("DEFERRED", result.getAsJsonObject("outcomes").getAsJsonObject("bound_item")
                .getAsJsonObject("missing-binding").getAsJsonObject("actual").get("status").getAsString());
        assertArrayEquals(Files.readAllBytes(caseRoot().resolve("expectations.json")), Files.readAllBytes(report.getParent().resolve("inputs/expectations.json")));
    }
    @Test void incompleteOrMalformedPeriodCannotBeInterpretedAsZeroOrRoundedTimestamp() throws Exception {
        var fixtures = CaseInputs.object(SubscriptionPeriodCase.load(caseRoot()).get("fixtures.json"));
        var original = fixtures.getAsJsonArray("scenarios").get(0).getAsJsonObject().getAsJsonObject("target");
        for (JsonElement invalid : List.of(JsonNull.INSTANCE, new JsonPrimitive("1769904000"), new JsonPrimitive(1.5), new JsonPrimitive(-1))) {
            var target = original.deepCopy(); target.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().add("current_period_end", invalid);
            assertEquals("DEFERRED", SubscriptionPeriodCase.consume(target, "si_core", SubscriptionPeriodCase.Strategy.BOUND_ITEM).get("status").getAsString());
        }
        var inactive = original.deepCopy(); inactive.addProperty("status", "past_due");
        assertEquals("DEFERRED", SubscriptionPeriodCase.consume(inactive, "si_core", SubscriptionPeriodCase.Strategy.BOUND_ITEM).get("status").getAsString());
    }
    @Test void modifiedCaseInputIsRejectedBeforeWritingAReport() throws Exception {
        Path copy = Files.createDirectory(temporary.resolve("case"));
        for (String name : List.of("fixtures.json", "expectations.json", "README.md", "integrity.json")) Files.copy(caseRoot().resolve(name), copy.resolve(name));
        Files.writeString(copy.resolve("expectations.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> SubscriptionPeriodCase.run(copy, temporary.resolve("reports")));
        assertFalse(Files.exists(temporary.resolve("reports")));
    }
}
