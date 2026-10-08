package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

/** Authored normalized test inputs, not the future versioned Stripe fixture registry. */
final class InvoiceRenewalTestInputs {
    static final String ACACIA = "2025-02-24.acacia", BASIL = "2025-03-31.basil";
    static final String EVIDENCE = "a".repeat(64);
    private InvoiceRenewalTestInputs() {}
    static JsonObject input(String delivery) {
        var input = new JsonObject(); input.addProperty("deliveryId", delivery); return input;
    }
    static Fact fact(JsonObject input, Status status, String invoice, String subscription, String version, Reason reason) {
        return new Fact(status, invoice, "cus_case", subscription, version, inputIdentity(input), EVIDENCE, reason);
    }
    static Fact resolved(JsonObject input, String subscription, String version) {
        return fact(input, Status.RESOLVED, "in_primary", subscription, version, null);
    }
    static JsonObject operation(String op) {
        var value = new JsonObject(); value.addProperty("op", op); return value;
    }
    static JsonObject returned(JsonObject fact) {
        var value = operation("return"); value.add("result", fact); return value;
    }
    static Observation observe(String consumer, JsonObject input, Fact expected, Fact actual, String execution) {
        var oracle = new InvoiceRenewalOracle(consumer, input, expected);
        oracle.request(operation("read"));
        if (actual != null) oracle.request(returned(actual.json()));
        return oracle.observation(execution);
    }
    static Observation resolvedObservation(String consumer, String delivery, String subscription, String version) {
        var input = input(delivery); var fact = resolved(input, subscription, version);
        return observe(consumer, input, fact, fact, "COMPLETED");
    }
}
