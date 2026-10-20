package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed, bounded observation diagnostics; no protected expectations or source execution. */
final class InvoiceDiagnosticRules {
    private InvoiceDiagnosticRules() {}
    static final List<String> REQUIRED = List.of("task", "build-configuration",
            "source/webhook-baseline/InvoiceWebhookConsumer.java", "source/reconciliation-baseline/InvoiceReconciliationConsumer.java");

    /** Selects a prefix of a fixed stratum round-robin, never skips a costly file to pick a cheaper one. */
    static JsonObject select(List<JsonObject> catalog, int pageBudget) {
        CaseInputs.require(pageBudget >= 0 && catalog.size() <= 768, "Invalid diagnostic selection bounds");
        var available = new TreeMap<String, JsonObject>(); var groups = new TreeMap<String, List<JsonObject>>();
        for (var row : catalog) {
            String alias = text(row, "alias"); new io.github.nktogo.apicompatibility.compatibility.ContentDigest(text(row, "sha256"));
            CaseInputs.require(integer(row, "totalCharacters") >= 0 && integer(row, "totalCharacters") <= 1048576
                    && available.putIfAbsent(alias, row.deepCopy()) == null, "Invalid diagnostic catalog");
        }
        var ordered = new ArrayList<JsonObject>(); for (String alias : REQUIRED) if (available.containsKey(alias)) ordered.add(available.get(alias));
        for (var entry : available.entrySet()) if (!REQUIRED.contains(entry.getKey())) groups.computeIfAbsent(stratum(entry.getKey()), ignored -> new ArrayList<>()).add(entry.getValue());
        var comparator = Comparator.comparing((JsonObject row) -> hash(text(row, "alias"))).thenComparing(row -> text(row, "alias"));
        groups.values().forEach(rows -> rows.sort(comparator));
        for (int round = 0; ; round++) {
            boolean added = false; for (var rows : groups.values()) if (round < rows.size()) { ordered.add(rows.get(round)); added = true; }
            if (!added) break;
        }
        var selected = new ArrayList<JsonObject>(); int used = 0; String next = "NONE";
        for (var row : ordered) {
            int pages = Math.max(1, (integer(row, "totalCharacters") + 3999) / 4000);
            if (pages > pageBudget - used) { next = text(row, "alias"); break; }
            selected.add(row); used += pages;
        }
        var counts = new TreeMap<String, Object>();
        for (var row : catalog) counts.put(stratum(text(row, "alias")), Map.of(
                "available", catalog.stream().filter(r -> stratum(text(r, "alias")).equals(stratum(text(row, "alias")))).count(),
                "selected", selected.stream().filter(r -> stratum(text(r, "alias")).equals(stratum(text(row, "alias")))).count()));
        return json(Map.of("policy", "mandatory-then-stratum-round-robin-alias-sha256-prefix-v1", "availableArtifacts", catalog.size(),
                "selected", selected, "textPages", used, "textPageBudget", pageBudget, "unreadArtifacts", catalog.size() - selected.size(),
                "firstBudgetExcludedAlias", next, "strata", counts));
    }
    private static String stratum(String alias) {
        var p = alias.split("/");
        if ((p[0].equals("business") || p[0].equals("order")) && p.length == 4) return p[0] + "/" + p[1] + "/" + p[3];
        if (p[0].equals("boundary") && p.length == 3) return p[0] + "/" + p[2];
        return p[0];
    }
    static JsonObject diagnose(JsonObject metadata, JsonObject selection, Map<String, String> inputs) {
        var rows = new JsonArray(); int supported = 0; boolean business = false, order = false;
        var inventory = new TreeMap<String, Object>();
        for (var value : selection.getAsJsonArray("selected")) {
            var entry = value.getAsJsonObject(); String alias = text(entry, "alias"), content = Objects.requireNonNull(inputs.get(alias));
            CaseInputs.require(content.length() == integer(entry, "totalCharacters") && hash(content).equals(text(entry, "sha256")), "Changed diagnostic input");
            inventory.put(alias, Map.of("sha256", text(entry, "sha256"), "characters", content.length()));
            String category = alias.split("/")[0]; if (!Set.of("business", "admission", "execution", "order", "boundary").contains(category)) continue;
            var row = json(Map.of("alias", alias, "sha256", text(entry, "sha256"), "pointer", "", "scope", "selected-recorded-facts-only"));
            try {
                var document = object(content); boolean sequence = category.equals("order") || category.equals("boundary");
                row.add("facts", sequence ? sequence(document) : observed(document)); row.addProperty("status", "SUPPORTED");
                supported++; business |= category.equals("business"); order |= category.equals("order");
            } catch (RuntimeException e) { row.addProperty("status", "UNSUPPORTED"); row.addProperty("issue", "unsupported-or-incomplete-record-shape"); }
            rows.add(row);
        }
        boolean coverage = bool(metadata, "sourceExecutionCoverageSatisfied");
        return json(Map.of("formatVersion", 1, "caseId", InvoiceRenewalFixtures.CASE_ID, "bundleIdentity", text(metadata, "bundleIdentity"),
                "response", coverage && business && order && inputs.keySet().containsAll(REQUIRED) ? "ANSWERED" : "ABSTAINED",
                "sourceExecutionCoverageSatisfied", coverage, "supportedRecords", supported, "selection", selection, "inputInventory", inventory,
                "records", rows, "unresolved", List.of("unread-artifacts-not-assessed", "source-control-flow-and-failure-causation-not-established",
                        "provider-references-not-retrieved", "protected-business-correctness-and-semantic-acceptance-not-established")));
    }
    private static JsonObject sequence(JsonObject source) {
        int initial = array(source, "initialEffects").size(), actual = array(source, "actualFinalEffects").size();
        var positions = array(source, "positions"); var details = new JsonArray(); var outcomes = new TreeMap<String, Integer>();
        int incomplete = 0, unsupported = 0, receipts = 0; var issues = new TreeSet<String>();
        for (int i = 0; i < positions.size(); i++) {
            var detail = json(Map.of("pointer", "/positions/" + i));
            try {
                var position = positions.get(i).getAsJsonObject(); var facts = observed(position); detail.add("facts", facts);
                if (integer(position, "position") != i + 1) issues.add("noncontiguous-position-index");
                String availability = text(position, "availability");
                if (Set.of("NOT_ATTEMPTED", "NO_OBSERVATION_RECORDED").contains(availability)
                        || (availability.equals("OBSERVATION_RECORDED") && (!facts.has("executionStatus")
                        || !facts.get("executionStatus").getAsString().equals("COMPLETED") || facts.get("protocolInvalid").getAsBoolean()
                        || !facts.get("actualFactPresent").getAsBoolean()))) incomplete++;
                var receipt = position.get("ledgerReceipt");
                if (receipt != null && !receipt.isJsonNull()) {
                    var r = receipt.getAsJsonObject(); String outcome = enumeration(r, "outcome", Set.of("APPENDED", "DUPLICATE", "CONFLICT", "NO_EFFECT"));
                    receipts++; if (integer(r, "sequence") != receipts) issues.add("noncontiguous-receipt-sequence");
                    outcomes.merge(outcome, 1, Integer::sum); facts.addProperty("ledgerOutcome", outcome);
                    if (!availability.equals("OBSERVATION_RECORDED")) issues.add("receipt-without-recorded-observation");
                }
                detail.addProperty("status", "SUPPORTED");
            } catch (RuntimeException e) { unsupported++; detail.addProperty("status", "UNSUPPORTED"); detail.addProperty("issue", "unsupported-or-incomplete-position-shape"); }
            details.add(detail);
        }
        return json(Map.of("initialEffectCount", initial, "actualFinalEffectCount", actual, "effectCountDelta", actual - initial,
                "positions", details, "incompletePositions", incomplete, "unsupportedPositions", unsupported, "recordedReceiptOutcomes", outcomes,
                "issues", issues, "businessCorrectnessEstablished", false));
    }
    private static JsonObject observed(JsonObject source) {
        String availability = enumeration(source, "availability", Set.of("NOT_ATTEMPTED", "NO_OBSERVATION_RECORDED", "ADMISSION_BLOCKED", "OBSERVATION_RECORDED"));
        var result = json(Map.of("availability", availability)); var issues = new TreeSet<String>();
        var admission = source.get("admission");
        if (admission != null && !admission.isJsonNull()) {
            boolean admitted = bool(admission.getAsJsonObject(), "admitted"); result.addProperty("admitted", admitted);
            if (!admitted && availability.equals("OBSERVATION_RECORDED")) issues.add("observation-despite-recorded-admission-block");
            if (admitted && availability.equals("ADMISSION_BLOCKED")) issues.add("availability-admission-disagreement");
        } else if (availability.equals("ADMISSION_BLOCKED")) issues.add("missing-admission-record");
        var observation = source.get("observation");
        if (observation != null && !observation.isJsonNull()) {
            var o = observation.getAsJsonObject(); result.addProperty("executionStatus", enumeration(o, "executionStatus", Set.of("COMPLETED", "TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR")));
            result.addProperty("protocolInvalid", bool(o, "protocolInvalid"));
            if (!availability.equals("OBSERVATION_RECORDED")) issues.add("availability-observation-disagreement");
            var actual = o.get("actual"); result.addProperty("actualFactPresent", actual != null && !actual.isJsonNull());
            if (actual != null && !actual.isJsonNull()) {
                var fact = actual.getAsJsonObject(); result.addProperty("reportedStatus", enumeration(fact, "status", Set.of("RESOLVED", "NON_SUBSCRIPTION", "UNRESOLVED")));
                compare(result, "inputIdentityMatchesRecord", fact, "inputIdentity", source, source.has("runtimeInputIdentity") ? "runtimeInputIdentity" : "inputIdentity");
                compare(result, "evidenceIdentityMatchesRecord", fact, "evidenceIdentity", source, source.has("runtimeEvidenceIdentity") ? "runtimeEvidenceIdentity" : "evidenceIdentity");
                if (source.has("input")) compare(result, "reportedVersionMatchesInput", fact, "effectiveApiVersion", source.getAsJsonObject("input"), "effectiveApiVersion");
            }
        } else if (availability.equals("OBSERVATION_RECORDED")) issues.add("missing-recorded-observation");
        var execution = source.get("execution");
        if (execution != null && !execution.isJsonNull()) {
            result.addProperty("transportStatus", enumeration(execution.getAsJsonObject(), "transportStatus", Set.of("COMPLETED", "TIMED_OUT", "CANCELLED", "PROTOCOL_ERROR", "WORKER_FAILED", "INFRASTRUCTURE_ERROR")));
            result.addProperty("containerRemoved", bool(execution.getAsJsonObject(), "containerRemoved"));
        }
        result.add("issues", CaseInputs.JSON.toJsonTree(issues)); return result;
    }
    private static void compare(JsonObject output, String key, JsonObject actual, String actualKey, JsonObject source, String sourceKey) {
        if (source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            String reference = text(source, sourceKey); var value = actual.get(actualKey);
            output.addProperty(key, value != null && !value.isJsonNull() && text(actual, actualKey).equals(reference));
        }
    }
    private static JsonObject object(String content) {
        CaseInputs.require(content.getBytes(StandardCharsets.UTF_8).length <= 1048576, "Diagnostic document too large");
        try (var reader = new JsonReader(new StringReader(content))) {
            reader.setStrictness(Strictness.STRICT); validate(reader, 0); CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing diagnostic data");
        } catch (IOException e) { throw new IllegalArgumentException("Unsupported diagnostic JSON", e); }
        return JsonParser.parseString(content).getAsJsonObject();
    }
    private static void validate(JsonReader reader, int depth) throws IOException {
        CaseInputs.require(depth <= 32, "Diagnostic nesting limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> { reader.beginObject(); var names = new HashSet<String>(); while (reader.hasNext()) { CaseInputs.require(names.size() < 128 && names.add(reader.nextName()), "Duplicate diagnostic field"); validate(reader, depth + 1); } reader.endObject(); }
            case BEGIN_ARRAY -> { reader.beginArray(); int count = 0; while (reader.hasNext()) { CaseInputs.require(count++ < 1024, "Diagnostic array limit"); validate(reader, depth + 1); } reader.endArray(); }
            case STRING, NUMBER, BOOLEAN, NULL -> reader.skipValue();
            default -> throw new IllegalArgumentException("Invalid diagnostic JSON");
        }
    }
    private static JsonArray array(JsonObject source, String key) { return Objects.requireNonNull(source.getAsJsonArray(key)); }
    private static String enumeration(JsonObject source, String key, Set<String> values) { String value = text(source, key); CaseInputs.require(values.contains(value), "Unsupported diagnostic enum"); return value; }
    private static String text(JsonObject source, String key) { var value = source.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Diagnostic string required"); return value.getAsString(); }
    private static boolean bool(JsonObject source, String key) { var value = source.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Diagnostic boolean required"); return value.getAsBoolean(); }
    private static int integer(JsonObject source, String key) { var value = source.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Diagnostic integer required"); return value.getAsBigDecimal().intValueExact(); }
    private static String hash(String text) { return CaseInputs.hash(text.getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
}
