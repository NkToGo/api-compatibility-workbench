package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host-owned service state. Worker messages request operations; they never supply assertions. */
final class RepairOracle implements WorkerOracle {
    private final JsonObject fixture, expected;
    private final String side, api;
    private final Set<String> records = new TreeSet<>(), deleted = new TreeSet<>(), unsafe = new TreeSet<>(), observed = new HashSet<>();
    private final List<JsonObject> events = new ArrayList<>();
    private List<String> returned = List.of();
    private boolean methodsRead, sourcesRead, terminal, completed, invalid;
    private int calls;

    RepairOracle(JsonObject fixture, JsonObject expected, String side, String api) {
        this.fixture = fixture.deepCopy(); this.expected = expected.deepCopy(); this.side = side; this.api = api;
        records.addAll(CaseInputs.strings(fixture, "initialLocalMethodIds"));
    }

    public synchronized JsonElement request(JsonObject request) {
        try {
            CaseInputs.require(!terminal && !invalid && ++calls <= 100, "Protocol closed or budget exceeded");
            String op = text(request, "op");
            Set<String> fields = switch (op) {
                case "list" -> Set.of("op");
                case "provider" -> Set.of("op", "method", "url", "version", "params");
                case "create", "sync", "delete" -> Set.of("op", "id");
                case "return" -> Set.of("op", "ids");
                case "exception" -> Set.of("op", "kind");
                default -> throw new IllegalArgumentException("Unsupported operation");
            };
            CaseInputs.require(request.keySet().equals(fields), "Unexpected protocol fields");
            JsonElement response = new JsonObject();
            switch (op) {
                case "list" -> response = CaseInputs.JSON.toJsonTree(records);
                case "provider" -> response = provider(request);
                case "create", "sync" -> {
                    String id = text(request, "id");
                    CaseInputs.require(observed.contains(id) && (op.equals("create") || records.contains(id)), "Unobserved service object");
                    records.add(id);
                }
                case "delete" -> {
                    String localId = text(request, "id");
                    String id = records.stream().filter(value -> UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().equals(localId))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown local object"));
                    if (!methodsRead || !sourcesRead) unsafe.add(id);
                    records.remove(id); deleted.add(id);
                }
                case "return" -> { returned = ids(request.getAsJsonArray("ids")); terminal = true; completed = true; }
                case "exception" -> {
                    CaseInputs.require(text(request, "kind").equals("NullPointerException"), "Unsupported consumer failure");
                    terminal = true;
                }
                default -> throw new IllegalArgumentException("Unsupported operation");
            }
            events.add(request.deepCopy());
            return response;
        } catch (RuntimeException e) { invalid = true; throw e; }
    }

    private JsonObject provider(JsonObject request) {
        CaseInputs.require(text(request, "method").equals("GET") && text(request, "version").equals(api), "Unexpected provider method/version");
        String url = text(request, "url"); JsonObject body;
        if (url.equals("https://api.stripe.com/v1/payment_methods")) {
            CaseInputs.require(request.get("params").equals(CaseInputs.JSON.toJsonTree(Map.of("customer", "cus_curated", "type", "card"))), "Unexpected query");
            body = fixture.getAsJsonObject("paymentMethodListResponse"); collection(body, "payment_method"); methodsRead = true;
        } else if (url.equals("https://api.stripe.com/v1/customers/cus_curated/sources")) {
            CaseInputs.require(request.getAsJsonObject("params").isEmpty(), "Unsupported source-list query");
            body = fixture.getAsJsonObject("sourceListResponse");
            collection(body, "card"); sourcesRead = true;
        } else {
            CaseInputs.require(url.equals("https://api.stripe.com/v1/customers/cus_curated") && request.getAsJsonObject("params").isEmpty(), "Unsupported provider endpoint");
            body = fixture.getAsJsonObject(side).getAsJsonObject("customerResponse");
            CaseInputs.require(text(body, "object").equals("customer") && text(body, "id").equals("cus_curated"), "Invalid customer fixture");
            if (body.has("sources")) { collection(body.getAsJsonObject("sources"), "card"); sourcesRead = true; }
        }
        return body.deepCopy();
    }
    private void collection(JsonObject body, String type) {
        CaseInputs.require(text(body, "object").equals("list") && body.getAsJsonPrimitive("has_more").isBoolean()
                && !body.get("has_more").getAsBoolean(), "Unsupported provider collection");
        for (var item : body.getAsJsonArray("data")) {
            var value = item.getAsJsonObject(); CaseInputs.require(text(value, "object").equals(type), "Unexpected collection object");
            observed.add(text(value, "id"));
        }
    }
    public synchronized JsonObject result(String executionStatus) {
        boolean usable = executionStatus.equals("COMPLETED") && terminal && !invalid;
        var result = CaseInputs.JSON.toJsonTree(Map.of("status", usable ? "COMPLETED" : "UNRESOLVED", "completed", completed,
                "finalMethodIds", records, "returnedMethodIds", returned, "deactivatedMethodIds", deleted,
                "unsafeDeactivationIds", unsafe, "events", events, "protocolCalls", calls)).getAsJsonObject();
        result.addProperty("assertionOutcome", usable ? (StripeCaseRunner.matches(result, expected) ? "PASSED" : "FAILED") : "NOT_EVALUATED");
        return result;
    }
    private static List<String> ids(JsonArray array) {
        CaseInputs.require(array.size() <= 100, "Too many return values");
        var result = new ArrayList<String>();
        for (var value : array) {
            CaseInputs.require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().matches("[a-zA-Z0-9_-]{1,80}"), "Invalid return value");
            result.add(value.getAsString());
        }
        CaseInputs.require(new HashSet<>(result).size() == result.size(), "Duplicate return value");
        return result.stream().sorted().toList();
    }
    private static String text(JsonObject object, String name) {
        var value = object.get(name);
        CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().length() <= 200, "Invalid protocol text");
        return value.getAsString();
    }
}
