package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure final-review wire contract; not admitted by any existing runtime or broker. */
final class InvoiceReconciliationDecisionV2 {
    static final String PROTOCOL = "invoice-final-reconciliation-v2", FUNCTION = "invoice_reconciliation_decision_v2";
    static final String INSTRUCTIONS = InvoiceReconciliationDecision.INSTRUCTIONS.replace(
            "Select one catalog, read_text or finish action.", "Select one catalog, catalog_prefix, read_text or finish action.") + """
            publicTask holds current maintainer requirements. captureMetadata describes historical capture,
            not present tools or new execution. Prefix lookup is literal case-sensitive startsWith over
            the same captured inventory; use nextOffset after filtering. Catalogues are not citable text.
            Each original and fresh outcome retains its own readiness, disposition and source identity.
            Fresh answers stay bound to the selected question and anchors. Neither an ANSWERED label nor
            question completion proves relevance, truth, conflict resolution or a migration's safety.
            Direct read-N citations must belong to your own readiness.citableTextPageIds; earlier read-N
            labels are lineage only. Cited ANSWERED claims covering both fixed roles provide a structural
            alternative to your own complete JOINT readiness and a cited own text page. They do not make
            those earlier pages your own. Abstention still requires every supplied reference to be known.
            Unavailable selection differs from a completed review selecting no questions. Preserve both,
            every original/fresh outcome, every claim and every UNRESOLVED possible conflict. Never select
            a winning attempt or silently replace an earlier failure with a later answer.
            teamStage is the cumulative final-stage shared remainder, not a fresh pool or permission.
            No dispatch, delegation, automatic conflict closure, grading or release approval is available.
            """;
    private InvoiceReconciliationDecisionV2() {}
    static JsonObject schema() {
        var schema = InvoiceReconciliationDecision.schema();
        var actions = InvoiceDecisionV2.schema().getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf");
        for (var action : actions) if (action.getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("action")
                .getAsJsonArray("enum").get(0).getAsString().equals("catalog_prefix"))
            schema.getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf").add(action.deepCopy());
        return schema;
    }
    static JsonObject parse(String raw) {
        CaseInputs.require(raw != null && raw.length() <= 32768, "Invalid invoice v2 reconciliation size");
        try (var reader = new JsonReader(new StringReader(raw))) {
            reader.setStrictness(Strictness.STRICT); var envelope = BundleInvestigationDecision.element(reader, 0).getAsJsonObject();
            CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT && envelope.keySet().equals(Set.of("decision"))
                    && envelope.get("decision").isJsonObject(), "Invalid invoice v2 reconciliation envelope");
            return new JsonPrimitive("catalog_prefix").equals(envelope.getAsJsonObject("decision").get("action"))
                    ? InvoiceDecisionV2.parse(raw) : InvoiceReconciliationDecision.parse(raw);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid invoice v2 reconciliation decision", e); }
    }
    static String contractIdentity() {
        var value = new JsonObject(); value.addProperty("protocol", PROTOCOL); value.addProperty("function", FUNCTION);
        value.addProperty("instructions", INSTRUCTIONS); value.addProperty("publicTask", InvoiceDecisionV2.PUBLIC_TASK);
        value.addProperty("contextPolicy", InvoiceTeamReconciliationV2.CONTEXT); value.addProperty("sourcePolicy", InvoiceTeamReconciliationV2.SOURCE);
        value.addProperty("reviewContract", InvoiceTeamDecisionV2.contractIdentity());
        var contracts = new JsonObject();
        for (String worker : InvoiceWorkerDecision.WORKERS) contracts.addProperty(worker, InvoiceFollowUpDecisionV2.contractIdentity(worker));
        value.add("freshContracts", contracts); value.addProperty("budgetPolicy", CoordinationTeamBudget.POLICY);
        value.addProperty("readinessPolicy", InvoiceReadiness.POLICY); value.addProperty("navigationPolicy", InvoiceComparisonInputs.PREFIX_POLICY);
        value.add("schema", schema()); return CaseInputs.hash(InvoiceDecisionV2.canonical(value).getBytes(StandardCharsets.UTF_8));
    }
}
