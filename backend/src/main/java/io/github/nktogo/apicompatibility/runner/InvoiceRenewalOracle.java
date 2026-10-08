package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host-owned normalized-result assertions; neither parses Stripe payloads nor executes consumers. */
final class InvoiceRenewalOracle implements WorkerOracle {
    static final Set<String> VERSIONS = Set.of("2025-02-24.acacia", "2025-03-31.basil");
    private static final Gson INPUT_JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    enum Status { RESOLVED, NON_SUBSCRIPTION, UNRESOLVED }
    enum Reason {
        MISSING_ASSOCIATION, INVALID_ASSOCIATION, UNKNOWN_VERSION, UNAPPROVED_BINDING,
        STATE_UNAVAILABLE, STATE_FAILED, STATE_NOT_ACTIVE, STATE_BINDING_MISMATCH,
        NOT_PAID, UNSUPPORTED_ADAPTER
    }
    enum Assertion { PASSED, FAILED, NOT_EVALUATED }

    record Fact(Status status, String invoiceId, String customerId, String subscriptionId,
                String effectiveApiVersion, String inputIdentity, String evidenceIdentity, Reason reason) {
        Fact {
            Objects.requireNonNull(status);
            identifier(invoiceId);
            if (customerId != null) identifier(customerId);
            if (subscriptionId != null) identifier(subscriptionId);
            digest(inputIdentity); digest(evidenceIdentity);
            CaseInputs.require(effectiveApiVersion == null || VERSIONS.contains(effectiveApiVersion), "Unsupported fact version");
            CaseInputs.require(subscriptionId == null || customerId != null, "Subscription requires customer identity");
            switch (status) {
                case RESOLVED -> CaseInputs.require(customerId != null && subscriptionId != null
                        && effectiveApiVersion != null && reason == null, "Incomplete resolved fact");
                case NON_SUBSCRIPTION -> CaseInputs.require(customerId != null && subscriptionId == null
                        && effectiveApiVersion != null && reason == null, "Invalid non-subscription fact");
                case UNRESOLVED -> CaseInputs.require(reason != null, "Unresolved fact requires a reason");
            }
        }

        JsonObject json() {
            var value = new JsonObject();
            value.addProperty("status", status.name()); value.addProperty("invoiceId", invoiceId);
            value.addProperty("customerId", customerId); value.addProperty("subscriptionId", subscriptionId);
            value.addProperty("effectiveApiVersion", effectiveApiVersion);
            value.addProperty("inputIdentity", inputIdentity); value.addProperty("evidenceIdentity", evidenceIdentity);
            value.addProperty("reason", reason == null ? null : reason.name());
            return value;
        }

        static Fact parse(JsonObject value) {
            CaseInputs.require(value != null && value.keySet().equals(Set.of("status", "invoiceId", "customerId",
                    "subscriptionId", "effectiveApiVersion", "inputIdentity", "evidenceIdentity", "reason")), "Invalid fact fields");
            String status = string(value, "status", false), reason = string(value, "reason", true);
            CaseInputs.require(Arrays.stream(Status.values()).anyMatch(s -> s.name().equals(status)), "Invalid invoice status");
            CaseInputs.require(reason == null || Arrays.stream(Reason.values()).anyMatch(r -> r.name().equals(reason)), "Invalid invoice reason");
            return new Fact(Status.valueOf(status), string(value, "invoiceId", false),
                    string(value, "customerId", true), string(value, "subscriptionId", true),
                    string(value, "effectiveApiVersion", true), string(value, "inputIdentity", false),
                    string(value, "evidenceIdentity", false), reason == null ? null : Reason.valueOf(reason));
        }
    }

    /** Constructed only by a sealed host oracle, never from a consumer-supplied verdict. */
    static final class Observation {
        private final String consumerId, executionStatus;
        private final Fact expected, actual;
        private final boolean protocolInvalid;
        private final Assertion assertion;

        private Observation(String consumerId, String executionStatus, Fact expected, Fact actual, boolean invalid) {
            this.consumerId = consumerId; this.executionStatus = executionStatus;
            this.expected = expected; this.actual = actual; protocolInvalid = invalid;
            assertion = !"COMPLETED".equals(executionStatus) || actual == null || invalid
                    ? Assertion.NOT_EVALUATED : actual.equals(expected) ? Assertion.PASSED : Assertion.FAILED;
        }

        String consumerId() { return consumerId; }
        String executionStatus() { return executionStatus; }
        Fact expected() { return expected; }
        Fact actual() { return actual; }
        boolean protocolInvalid() { return protocolInvalid; }
        Assertion assertion() { return assertion; }
        boolean identitiesMatch() {
            return actual != null && actual.inputIdentity().equals(expected.inputIdentity())
                    && actual.evidenceIdentity().equals(expected.evidenceIdentity());
        }

        JsonObject json() {
            var value = new JsonObject();
            value.addProperty("consumerId", consumerId); value.addProperty("executionStatus", executionStatus);
            value.addProperty("protocolInvalid", protocolInvalid); value.addProperty("assertionOutcome", assertion.name());
            value.addProperty("identitiesMatch", identitiesMatch());
            value.add("expected", expected.json());
            value.add("actual", actual == null ? JsonNull.INSTANCE : actual.json());
            return value;
        }
    }

    private final String consumerId;
    private final JsonObject input;
    private final Fact expected;
    private Fact returned;
    private boolean read, invalid;
    private Observation sealed;

    InvoiceRenewalOracle(String consumerId, JsonObject input, Fact expected) {
        CaseInputs.require(Set.of("invoice-webhook", "invoice-reconciliation").contains(consumerId), "Unknown invoice consumer");
        CaseInputs.require(input != null, "Missing registered input");
        this.expected = Objects.requireNonNull(expected);
        this.consumerId = consumerId; this.input = input.deepCopy();
        CaseInputs.require(inputIdentity(this.input).equals(expected.inputIdentity()), "Input identity does not match expectation");
    }

    public synchronized JsonElement request(JsonObject request) {
        // A terminal observation is immutable even if a caller attempts a late protocol message.
        CaseInputs.require(sealed == null, "Invoice protocol sealed");
        try {
            CaseInputs.require(!invalid && returned == null && request != null, "Invoice protocol closed");
            switch (string(request, "op", false)) {
                case "read" -> {
                    CaseInputs.require(!read && request.keySet().equals(Set.of("op")), "Invalid invoice input read");
                    read = true;
                    var response = new JsonObject(); response.addProperty("formatVersion", 1);
                    response.add("input", input.deepCopy()); response.addProperty("inputIdentity", expected.inputIdentity());
                    response.addProperty("evidenceIdentity", expected.evidenceIdentity());
                    return response;
                }
                case "return" -> {
                    CaseInputs.require(read && request.keySet().equals(Set.of("op", "result"))
                            && request.get("result").isJsonObject(), "Invalid invoice return");
                    returned = Fact.parse(request.getAsJsonObject("result"));
                    return new JsonObject();
                }
                default -> throw new IllegalArgumentException("Unsupported invoice operation");
            }
        } catch (RuntimeException e) { invalid = true; throw e; }
    }

    synchronized Observation observation(String executionStatus) {
        CaseInputs.require(Set.of("COMPLETED", "TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")
                .contains(executionStatus), "Unknown execution status");
        if (sealed == null) sealed = new Observation(consumerId, executionStatus, expected, returned, invalid);
        CaseInputs.require(sealed.executionStatus().equals(executionStatus), "Conflicting terminal execution status");
        return sealed;
    }

    public synchronized JsonObject result(String executionStatus) { return observation(executionStatus).json(); }

    static String inputIdentity(JsonObject input) {
        return CaseInputs.hash(INPUT_JSON.toJson(Objects.requireNonNull(input)).getBytes(StandardCharsets.UTF_8));
    }

    static void identifier(String value) {
        CaseInputs.require(value != null && value.matches("[A-Za-z0-9_-]{1,128}"), "Invalid invoice-case identifier");
    }
    private static void digest(String value) {
        CaseInputs.require(value != null && value.matches("[0-9a-f]{64}"), "Invalid invoice evidence identity");
    }
    private static String string(JsonObject value, String key, boolean nullable) {
        var element = value.get(key);
        CaseInputs.require(element != null, "Missing invoice field");
        if (nullable && element.isJsonNull()) return null;
        CaseInputs.require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(), "Invalid invoice field type");
        return element.getAsString();
    }
}
