package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure v2 coordinator wire contract; defining it grants no model or follow-up access. */
final class InvoiceTeamDecisionV2 {
    static final String PROTOCOL = "invoice-team-review-v2", FUNCTION = "invoice_team_review_decision_v2";
    static final String INSTRUCTIONS = InvoiceTeamDecision.INSTRUCTIONS.replace(
            "Choose one catalog, read_text or finish action.", "Choose one catalog, catalog_prefix, read_text or finish action.") + """
            The separately versioned publicTask contains the current maintainer questions. captureMetadata
            describes historical capture, not present adapters or model activity. Prefix lookup is literal,
            case-sensitive startsWith over the same inventory; offsets apply after filtering. Empty prefix
            selects the inventory. Prefix/flat catalogue IDs cannot support claims or questions.
            Each worker outcome includes its own structural readiness when captured reads are available.
            These access checks are not semantic grades or proof of a correct answer. Worker page IDs in
            original claims are lineage only; cite claim-N or worker-N to refer to original statements.
            Your own readiness concerns your inspected pages, not your workers' pages. It is not a
            requirement to repeat every worker read before proposing an anchored question. Only your own
            inspected text pages are directly citable as read-N. All original outcomes/claims remain visible.
            teamStage and scheduling describe the remaining coordinator-stage shared allowance, including
            forward carry, not a fresh trial pool or private turn guarantee. Finishing costs a decision;
            no free finalization, future turn, follow-up dispatch or permission is promised. Preserve missing
            and uncertain work. Selection remains inert data; there is no automatic delegation or acceptance.
            """;
    private InvoiceTeamDecisionV2() {}
    static JsonObject schema() {
        var schema = InvoiceTeamDecision.schema().deepCopy();
        var prefix = InvoiceDecisionV2.schema().getAsJsonObject("properties").getAsJsonObject("decision")
                .getAsJsonArray("anyOf").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(v -> v.getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum").get(0).getAsString().equals("catalog_prefix"))
                .findFirst().orElseThrow();
        schema.getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf").add(prefix.deepCopy());
        return schema;
    }
    static JsonObject parse(String raw) {
        CaseInputs.require(raw != null && raw.length() <= 32768, "Invalid invoice v2 review decision size");
        try (var reader = new JsonReader(new StringReader(raw))) {
            reader.setStrictness(Strictness.STRICT); var envelope = BundleInvestigationDecision.element(reader, 0).getAsJsonObject();
            CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT && envelope.keySet().equals(Set.of("decision"))
                    && envelope.get("decision").isJsonObject(), "Invalid invoice v2 review envelope");
            var d = envelope.getAsJsonObject("decision");
            return new JsonPrimitive("catalog_prefix").equals(d.get("action")) ? InvoiceDecisionV2.parse(raw) : InvoiceTeamDecision.parse(raw);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid invoice v2 review decision", e); }
    }
    static String contractIdentity() {
        var value = new JsonObject(); value.addProperty("protocol", PROTOCOL); value.addProperty("function", FUNCTION);
        value.addProperty("instructions", INSTRUCTIONS); value.addProperty("publicTask", InvoiceDecisionV2.PUBLIC_TASK);
        value.addProperty("contextPolicy", InvoiceTeamReviewV2.CONTEXT); value.addProperty("selectionPolicy", InvoiceTeamReviewV2.SELECTION);
        value.addProperty("readinessPolicy", InvoiceReadiness.POLICY); value.addProperty("navigationPolicy", InvoiceComparisonInputs.PREFIX_POLICY);
        value.addProperty("budgetPolicy", CoordinationTeamBudget.POLICY); value.add("schema", schema());
        var workers = new JsonObject();
        for (var scope : List.of(InvoiceReadiness.Scope.WEBHOOK, InvoiceReadiness.Scope.RECONCILIATION))
            workers.addProperty(InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope));
        value.add("workerContracts", workers);
        return CaseInputs.hash(InvoiceDecisionV2.canonical(value).getBytes(StandardCharsets.UTF_8));
    }
}
