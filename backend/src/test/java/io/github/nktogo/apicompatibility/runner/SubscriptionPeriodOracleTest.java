package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionPeriodOracleTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static final String RESOLVED = "{\"status\":\"RESOLVED\",\"periodEnd\":1769904000}";
    private static SubscriptionPeriodOracle oracle() {
        return new SubscriptionPeriodOracle(json("{\"object\":\"subscription\"}"), new JsonPrimitive("si_core"), json(RESOLVED));
    }
    private static JsonObject returned(String result) { return json("{\"op\":\"return\",\"result\":" + result + "}"); }

    @Test void protocolPreservesEveryAuthoredControlOutcome() throws Exception {
        var root = Path.of(System.getProperty("workbench.repository"), "cases", SubscriptionPeriodCase.CASE_ID);
        var files = SubscriptionPeriodCase.load(root);
        var fixtures = CaseInputs.object(files.get("fixtures.json")).getAsJsonArray("scenarios");
        var expectations = new HashMap<String, JsonObject>();
        for (var row : CaseInputs.object(files.get("expectations.json")).getAsJsonArray("scenarios"))
            expectations.put(row.getAsJsonObject().get("id").getAsString(), row.getAsJsonObject().getAsJsonObject("expected"));
        for (var strategy : SubscriptionPeriodCase.Strategy.values()) {
            int passed = 0;
            for (var row : fixtures) {
                var scenario = row.getAsJsonObject();
                var oracle = new SubscriptionPeriodOracle(scenario.getAsJsonObject("target"), scenario.get("binding"), expectations.get(scenario.get("id").getAsString()));
                var input = oracle.request(json("{\"op\":\"read\"}")).getAsJsonObject();
                assertEquals(Set.of("subscription", "binding"), input.keySet());
                var binding = input.get("binding");
                var value = SubscriptionPeriodCase.consume(input.getAsJsonObject("subscription"), binding.isJsonNull() ? null : binding.getAsString(), strategy);
                oracle.request(returned(value.toString()));
                if (oracle.result("COMPLETED").get("assertionOutcome").getAsString().equals("PASSED")) passed++;
            }
            assertEquals(switch (strategy) { case ORIGINAL -> 4; case FIRST_ITEM, MAXIMUM -> 6; case MINIMUM -> 7; case BOUND_ITEM -> 8; }, passed);
        }
    }

    @Test void malformedOrForgedOutputPermanentlyInvalidatesObservation() {
        for (String result : List.of("{\"status\":\"PASSED\"}", "{\"status\":\"DEFERRED\",\"periodEnd\":1}",
                "{\"status\":\"RESOLVED\",\"periodEnd\":\"1769904000\"}", "{\"status\":\"RESOLVED\",\"periodEnd\":1.5}",
                "{\"status\":\"RESOLVED\",\"periodEnd\":-1}", "{\"status\":\"RESOLVED\",\"periodEnd\":null}")) {
            var oracle = oracle(); oracle.request(json("{\"op\":\"read\"}"));
            assertThrows(RuntimeException.class, () -> oracle.request(returned(result)));
            assertThrows(RuntimeException.class, () -> oracle.request(returned(RESOLVED)));
            assertEquals("NOT_EVALUATED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
        }
    }

    @Test void incompleteExecutionAndTrailingMessagesCannotPass() {
        for (String status : List.of("TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")) {
            var oracle = oracle(); oracle.request(json("{\"op\":\"read\"}")); oracle.request(returned(RESOLVED));
            assertEquals("NOT_EVALUATED", oracle.result(status).get("assertionOutcome").getAsString());
        }
        var oracle = oracle(); oracle.request(json("{\"op\":\"read\"}")); oracle.request(returned(RESOLVED));
        assertThrows(RuntimeException.class, () -> oracle.request(returned(RESOLVED)));
        assertEquals("NOT_EVALUATED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
        assertEquals("NOT_EVALUATED", oracle().result("COMPLETED").get("assertionOutcome").getAsString());
    }

    @Test void orderAndExactFieldsAreEnforced() {
        for (String message : List.of("{\"op\":\"read\",\"assertionOutcome\":\"PASSED\"}", "{\"op\":\"write\"}", "{\"op\":1}"))
            assertThrows(RuntimeException.class, () -> oracle().request(json(message)));
        assertThrows(RuntimeException.class, () -> oracle().request(returned(RESOLVED)));
        var oracle = oracle(); oracle.request(json("{\"op\":\"read\"}"));
        assertThrows(RuntimeException.class, () -> oracle.request(json("{\"op\":\"read\"}")));
    }

    @Test void callersCannotMutateExpectationsOrRetainedResults() {
        var expected = json(RESOLVED); var oracle = new SubscriptionPeriodOracle(json("{}"), JsonNull.INSTANCE, expected);
        expected.addProperty("periodEnd", 1);
        oracle.request(json("{\"op\":\"read\"}")); var message = returned(RESOLVED); oracle.request(message);
        message.getAsJsonObject("result").addProperty("periodEnd", 2);
        var report = oracle.result("COMPLETED"); report.getAsJsonObject("actual").addProperty("periodEnd", 3);
        assertEquals("PASSED", oracle.result("COMPLETED").get("assertionOutcome").getAsString());
        assertEquals(1769904000L, oracle.result("COMPLETED").getAsJsonObject("actual").get("periodEnd").getAsLong());
    }
}
