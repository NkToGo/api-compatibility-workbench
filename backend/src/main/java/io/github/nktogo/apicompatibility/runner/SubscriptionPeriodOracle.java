package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.util.*;

/** Host-owned assertions for one subscription projection; accepts no worker verdicts. */
final class SubscriptionPeriodOracle implements WorkerOracle {
    private final JsonObject input, expected;
    private final List<JsonObject> events = new ArrayList<>();
    private JsonObject returned;
    private boolean read, invalid;

    SubscriptionPeriodOracle(JsonObject subscription, JsonElement binding, JsonObject expected) {
        validateResult(expected);
        CaseInputs.require(subscription != null && binding != null && (binding.isJsonNull()
                || binding.isJsonPrimitive() && binding.getAsJsonPrimitive().isString()), "Invalid subscription input");
        input = new JsonObject(); input.add("subscription", subscription.deepCopy()); input.add("binding", binding.deepCopy());
        this.expected = expected.deepCopy();
    }

    public synchronized JsonElement request(JsonObject request) {
        try {
            CaseInputs.require(!invalid && returned == null && request != null, "Protocol closed");
            var op = request.get("op");
            CaseInputs.require(op != null && op.isJsonPrimitive() && op.getAsJsonPrimitive().isString(), "Invalid operation");
            JsonElement response;
            switch (op.getAsString()) {
                case "read" -> {
                    CaseInputs.require(!read && request.keySet().equals(Set.of("op")), "Invalid input read");
                    read = true; response = input.deepCopy();
                }
                case "return" -> {
                    CaseInputs.require(read && request.keySet().equals(Set.of("op", "result"))
                            && request.get("result").isJsonObject(), "Invalid return");
                    validateResult(request.getAsJsonObject("result"));
                    returned = request.getAsJsonObject("result").deepCopy(); response = new JsonObject();
                }
                default -> throw new IllegalArgumentException("Unsupported operation");
            }
            events.add(request.deepCopy()); return response;
        } catch (RuntimeException e) { invalid = true; throw e; }
    }

    public synchronized JsonObject result(String executionStatus) {
        boolean usable = "COMPLETED".equals(executionStatus) && returned != null && !invalid;
        var result = new JsonObject();
        result.addProperty("status", usable ? "COMPLETED" : "UNRESOLVED");
        result.addProperty("assertionOutcome", usable ? (returned.equals(expected) ? "PASSED" : "FAILED") : "NOT_EVALUATED");
        result.add("actual", returned == null ? JsonNull.INSTANCE : returned.deepCopy());
        result.add("events", CaseInputs.JSON.toJsonTree(events));
        return result;
    }

    private static void validateResult(JsonObject value) {
        CaseInputs.require(value != null, "Missing result");
        var status = value.get("status");
        CaseInputs.require(status != null && status.isJsonPrimitive() && status.getAsJsonPrimitive().isString(), "Invalid result status");
        if (status.getAsString().equals("DEFERRED")) {
            CaseInputs.require(value.keySet().equals(Set.of("status")), "Unexpected deferred fields");
        } else {
            CaseInputs.require(status.getAsString().equals("RESOLVED") && value.keySet().equals(Set.of("status", "periodEnd")), "Unexpected resolved fields");
            var end = value.get("periodEnd");
            CaseInputs.require(end.isJsonPrimitive() && end.getAsJsonPrimitive().isNumber()
                    && end.getAsBigDecimal().longValueExact() > 0, "Invalid period end");
        }
    }
}
