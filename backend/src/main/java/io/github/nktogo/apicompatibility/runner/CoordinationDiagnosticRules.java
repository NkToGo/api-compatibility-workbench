package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed record/projection diagnostics. Never executes source, fetches data or judges its own quality. */
final class CoordinationDiagnosticRules {
    private CoordinationDiagnosticRules() {}

    static JsonObject diagnose(String caseId, Map<String, byte[]> inputs) {
        var hashes = new TreeMap<String, String>(); inputs.forEach((alias, bytes) -> hashes.put(alias, CaseInputs.hash(bytes)));
        var result = json(Map.of("formatVersion", 1, "caseId", caseId, "inputSha256", hashes,
                "scope", "declared-synthetic-scenarios-not-general-compatibility-or-failure-causation"));
        var unresolved = new ArrayList<String>();
        int supported = 0;
        try {
            var expectations = document(inputs, "expectations", caseId); var fixtures = document(inputs, "fixtures", caseId);
            var expected = scenarios(expectations); var projected = scenarios(fixtures);
            CaseInputs.require(!expected.isEmpty() && expected.keySet().equals(projected.keySet()), "Scenario inventory mismatch");
            try {
                var execution = object(inputs, "execution");
                CaseInputs.require("COMPLETED".equals(text(execution, "status")) && "recorded-execution".equals(text(execution, "authority")), "Unavailable observations");
                var observations = execution.getAsJsonObject("observations");
                Set<String> variants = caseId.equals(CaseInputs.CASE_ID) ? Set.of("baseline", "target", "upstream-reference") : Set.of("baseline", "target");
                CaseInputs.require(observations != null && variants.containsAll(observations.keySet()), "Unsupported observation variant");
                var reports = new TreeMap<String, Object>();
                var baseline = rows(observations, "baseline", expected.keySet());
                for (String variant : new TreeSet<>(variants)) {
                    var actual = rows(observations, variant, expected.keySet());
                    var regressions = new ArrayList<String>(); var passing = new ArrayList<String>();
                    for (String scenario : expected.keySet()) {
                        if (passed(baseline.values.get(scenario)) && failed(actual.values.get(scenario))) regressions.add(scenario);
                        if (passed(baseline.values.get(scenario)) && passed(actual.values.get(scenario))) passing.add(scenario);
                    }
                    boolean complete = baseline.issues.isEmpty() && actual.issues.isEmpty() && baseline.values.values().stream().allMatch(CoordinationDiagnosticRules::passed)
                            && actual.values.values().stream().allMatch(v -> passed(v) || failed(v));
                    String conclusion = !complete ? "UNRESOLVED" : regressions.isEmpty() ? "NO_REGRESSION_OBSERVED" : "REGRESSION_OBSERVED";
                    var issues = new TreeSet<>(baseline.issues); issues.addAll(actual.issues);
                    baseline.values.forEach((id, row) -> { if (!passed(row)) issues.add("baseline-not-passed:" + id); });
                    reports.put(variant, Map.of("conclusion", conclusion, "observations", actual.retained, "regressedScenarioIds", regressions,
                            "passingScenarioIds", passing, "coverageIssues", issues, "evidence", List.of(cite(hashes, "execution", "/observations/" + variant), cite(hashes, "expectations", "/scenarios"))));
                    if (!actual.values.isEmpty()) supported++;
                }
                result.add("executionDiagnostics", CaseInputs.JSON.toJsonTree(reports));
            } catch (RuntimeException e) { unresolved.add("unsupported-or-missing-execution-shape"); }
            try {
                result.add("projectionDiagnostics", CaseInputs.JSON.toJsonTree(caseId.equals(CaseInputs.CASE_ID)
                        ? customer(expected, projected, expectations, hashes) : subscription(expected, projected, expectations, hashes)));
                supported++;
            } catch (RuntimeException e) { unresolved.add("unsupported-or-inconsistent-projection-policy-shape"); }
        } catch (RuntimeException e) { unresolved.add("unsupported-or-inconsistent-scenario-inventory"); }
        // Source presence/hashes are retained, but no Java parser or semantic proof is claimed.
        unresolved.add("source-control-flow-and-failure-causation-not-established-by-these-rules");
        unresolved.add("provider-documentation-and-general-migration-support-not-verified");
        result.addProperty("response", supported == 0 ? "ABSTAINED" : "ANSWERED");
        result.add("unresolved", CaseInputs.JSON.toJsonTree(unresolved));
        result.addProperty("qualityReview", "NOT_PERFORMED");
        return result;
    }

    private record Rows(Map<String, JsonObject> values, List<JsonObject> retained, Set<String> issues) {}
    private static Rows rows(JsonObject observations, String variant, Set<String> scenarios) {
        var values = new TreeMap<String, JsonObject>(); var retained = new ArrayList<JsonObject>(); var issues = new TreeSet<String>();
        var duplicate = new HashSet<String>();
        if (!observations.has(variant)) issues.add("missing-variant:" + variant);
        else if (!observations.get(variant).isJsonArray() || observations.getAsJsonArray(variant).size() > 128) issues.add("unsupported-variant-shape:" + variant);
        else for (var element : observations.getAsJsonArray(variant)) {
            try {
                var row = element.getAsJsonObject();
                CaseInputs.require(row.keySet().equals(Set.of("scenarioId", "disposition", "assertionOutcome")), "Unsupported observation shape");
                String id = identifier(text(row, "scenarioId")), disposition = text(row, "disposition"), outcome = text(row, "assertionOutcome");
                CaseInputs.require(Set.of("COMPLETED", "TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR").contains(disposition)
                        && Set.of("PASSED", "FAILED", "NOT_EVALUATED").contains(outcome)
                        && (disposition.equals("COMPLETED") || outcome.equals("NOT_EVALUATED")), "Invalid observation");
                retained.add(row.deepCopy());
                if (!scenarios.contains(id)) issues.add("unexpected-scenario:" + variant + ":" + id);
                else if (values.putIfAbsent(id, row) != null) { duplicate.add(id); issues.add("duplicate-scenario:" + variant + ":" + id); }
            } catch (RuntimeException e) {
                issues.add("unsupported-observation-shape:" + variant);
                // A malformed retry cannot leave a previously accepted row for the same declared ID unambiguous.
                if (element.isJsonObject() && element.getAsJsonObject().has("scenarioId")) {
                    var id = element.getAsJsonObject().get("scenarioId");
                    if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() && scenarios.contains(id.getAsString())) duplicate.add(id.getAsString());
                }
            }
        }
        duplicate.forEach(values::remove);
        for (String id : scenarios) {
            if (!values.containsKey(id)) issues.add("missing-unambiguous-observation:" + variant + ":" + id);
            else if (!passed(values.get(id)) && !failed(values.get(id))) issues.add("unevaluated-observation:" + variant + ":" + id);
        }
        return new Rows(values, retained, issues);
    }
    private static boolean passed(JsonObject row) { return row != null && "COMPLETED".equals(text(row, "disposition")) && "PASSED".equals(text(row, "assertionOutcome")); }
    private static boolean failed(JsonObject row) { return row != null && "COMPLETED".equals(text(row, "disposition")) && "FAILED".equals(text(row, "assertionOutcome")); }

    private static Object customer(Map<String, JsonObject> expected, Map<String, JsonObject> fixtures, JsonObject policy, Map<String, String> hashes) {
        boolean required = bool(policy, "requireEvidenceBeforeDeactivation"); var rows = new ArrayList<Object>();
        for (var entry : fixtures.entrySet()) {
            var fixture = entry.getValue(); var customer = fixture.getAsJsonObject("target").getAsJsonObject("customerResponse");
            var initial = ids(fixture.getAsJsonArray("initialLocalMethodIds"));
            var sources = fixture.getAsJsonObject("sourceListResponse"); var methods = fixture.getAsJsonObject("paymentMethodListResponse");
            var present = listed(sources); present.addAll(listed(methods));
            boolean complete = !bool(sources, "has_more") && !bool(methods, "has_more");
            long knownPresent = initial.stream().filter(present::contains).count();
            var expectation = expected.get(entry.getKey()); bool(expectation, "mustComplete");
            var deactivate = ids(expectation.getAsJsonArray("deactivatedMethodIds")); ids(expectation.getAsJsonArray("finalMethodIds"));
            rows.add(Map.of("scenarioId", entry.getKey(), "embeddedSources", !customer.has("sources") ? "OMITTED" : customer.get("sources").isJsonNull() ? "NULL" : "PRESENT",
                    "separateListsComplete", complete, "initialMethodsObservedPresent", knownPresent,
                    "initialMethodsEstablishedAbsentInProjection", complete ? initial.size() - knownPresent : 0,
                    "initialMethodsUnresolvedInProjection", complete ? 0 : initial.size() - knownPresent,
                    "expectedDeactivations", deactivate.size(), "expectedDeactivationsObservedPresent", deactivate.stream().filter(present::contains).count(),
                    "evidence", List.of(cite(hashes, "fixtures", pointer(fixtures, entry.getKey())), cite(hashes, "expectations", pointer(expected, entry.getKey())))));
        }
        return Map.of("applicationRequiresEvidenceBeforeDeactivation", required, "omissionAloneEstablishesAbsence", false,
                "scope", "presence-and-completeness-of-declared-synthetic-lists-not-real-provider-retrieval", "scenarios", rows,
                "policyEvidence", cite(hashes, "expectations", "/requireEvidenceBeforeDeactivation"));
    }
    private static Object subscription(Map<String, JsonObject> expected, Map<String, JsonObject> fixtures, JsonObject policy, Map<String, String> hashes) {
        CaseInputs.require("bound-item-renewal-v1".equals(text(policy, "policy")), "Unsupported application policy"); var rows = new ArrayList<Object>();
        for (var entry : fixtures.entrySet()) {
            var fixture = entry.getValue(); var target = fixture.getAsJsonObject("target"); var items = target.getAsJsonObject("items");
            CaseInputs.require("subscription".equals(text(target, "object")) && "list".equals(text(items, "object")), "Unsupported projection");
            var binding = fixture.get("binding"); CaseInputs.require(binding != null && (binding.isJsonNull() || binding.isJsonPrimitive() && binding.getAsJsonPrimitive().isString()), "Invalid binding");
            String selected = binding.isJsonNull() ? null : binding.getAsString(); var data = items.getAsJsonArray("data"); var matches = new ArrayList<JsonObject>(); var ends = new ArrayList<Long>();
            for (var item : data) { var row = item.getAsJsonObject(); if (Objects.equals(selected, text(row, "id"))) matches.add(row); ends.add(timestamp(row, "current_period_end")); }
            boolean configured = selected != null && !selected.isBlank(), complete = !bool(items, "has_more");
            boolean eligible = configured && complete && "active".equals(text(target, "status")) && matches.size() == 1;
            var expectation = expected.get(entry.getKey()).getAsJsonObject("expected"); String status = text(expectation, "status");
            CaseInputs.require(expectation.keySet().equals(status.equals("DEFERRED") ? Set.of("status") : Set.of("status", "periodEnd"))
                    && Set.of("DEFERRED", "RESOLVED").contains(status), "Unsupported expectation");
            var shortcuts = new TreeMap<String, String>(); String selectedResult = "NOT_EVALUATED";
            if (eligible && status.equals("RESOLVED")) {
                long end = timestamp(expectation, "periodEnd");
                selectedResult = timestamp(matches.getFirst(), "current_period_end") == end ? "MATCHES_DECLARED_DATE" : "DIFFERS_FROM_DECLARED_DATE";
                shortcuts.put("first-item", ends.getFirst() == end ? "MATCHES_DECLARED_DATE" : "DIFFERS_FROM_DECLARED_DATE");
                shortcuts.put("minimum", Collections.min(ends) == end ? "MATCHES_DECLARED_DATE" : "DIFFERS_FROM_DECLARED_DATE");
                shortcuts.put("maximum", Collections.max(ends) == end ? "MATCHES_DECLARED_DATE" : "DIFFERS_FROM_DECLARED_DATE");
            }
            rows.add(Map.of("scenarioId", entry.getKey(), "configuredBinding", configured, "matchingItems", matches.size(), "completeItems", complete,
                    "selectionPreconditionsMet", eligible, "declaredExpectation", status,
                    "boundItemProjection", selectedResult, "shortcutProjections", shortcuts,
                    "evidence", List.of(cite(hashes, "fixtures", pointer(fixtures, entry.getKey())), cite(hashes, "expectations", pointer(expected, entry.getKey())))));
        }
        return Map.of("applicationPolicy", "bound-item-renewal-v1", "scope", "declared-renewal-date-policy-not-paid-through-access-or-universal-provider-entitlement",
                "passingDeferredCheckEstablishesDate", false, "scenarios", rows, "policyEvidence", cite(hashes, "expectations", "/policy"));
    }
    private static Set<String> listed(JsonObject list) {
        CaseInputs.require("list".equals(text(list, "object")), "Unsupported list"); var ids = new HashSet<String>();
        CaseInputs.require(list.getAsJsonArray("data").size() <= 128, "Oversized projection list");
        for (var row : list.getAsJsonArray("data")) CaseInputs.require(ids.add(text(row.getAsJsonObject(), "id")), "Duplicate listed identity");
        bool(list, "has_more"); return ids;
    }
    private static Set<String> ids(JsonArray array) { var ids = new HashSet<String>(); for (var value : array) { CaseInputs.require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && ids.add(value.getAsString()), "Invalid identity list"); } return ids; }
    private static Map<String, JsonObject> scenarios(JsonObject document) {
        var rows = new LinkedHashMap<String, JsonObject>();
        for (var value : document.getAsJsonArray("scenarios")) { var row = value.getAsJsonObject(); CaseInputs.require(rows.size() < 32 && rows.putIfAbsent(identifier(text(row, "id")), row) == null, "Invalid scenario inventory"); }
        return rows;
    }
    private static String pointer(Map<String, JsonObject> rows, String id) { return "/scenarios/" + new ArrayList<>(rows.keySet()).indexOf(id); }
    private static Object cite(Map<String, String> hashes, String alias, String pointer) { return Map.of("inputId", alias, "sha256", hashes.get(alias), "pointer", pointer); }
    private static JsonObject document(Map<String, byte[]> inputs, String alias, String caseId) {
        var value = object(inputs, alias); CaseInputs.require(value.get("formatVersion").getAsBigDecimal().intValueExact() == 1 && caseId.equals(text(value, "caseId")), "Unsupported case document"); return value;
    }
    private static JsonObject object(Map<String, byte[]> inputs, String alias) {
        String text = new String(Objects.requireNonNull(inputs.get(alias)), StandardCharsets.UTF_8);
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT); validate(reader, 0);
            CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing input");
        } catch (IOException e) { throw new IllegalArgumentException("Unsupported JSON input", e); }
        return JsonParser.parseString(text).getAsJsonObject();
    }
    private static void validate(JsonReader reader, int depth) throws IOException {
        CaseInputs.require(depth <= 32, "Input nesting limit");
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject(); var names = new HashSet<String>();
            while (reader.hasNext()) { CaseInputs.require(names.size() < 1024 && names.add(reader.nextName()), "Duplicate or excessive JSON fields"); validate(reader, depth + 1); }
            reader.endObject();
        } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray(); int size = 0;
            while (reader.hasNext()) { CaseInputs.require(size++ < 1024, "JSON array limit"); validate(reader, depth + 1); }
            reader.endArray();
        } else reader.skipValue();
    }
    private static String text(JsonObject object, String key) { var value = object.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "String required"); return value.getAsString(); }
    private static boolean bool(JsonObject object, String key) { var value = object.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Boolean required"); return value.getAsBoolean(); }
    private static long timestamp(JsonObject object, String key) { var value = object.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Numeric date required"); long end = value.getAsBigDecimal().longValueExact(); CaseInputs.require(end > 0, "Positive date required"); return end; }
    private static String identifier(String id) { CaseInputs.require(id.matches("[a-z][a-z0-9-]{0,99}"), "Invalid scenario identifier"); return id; }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
