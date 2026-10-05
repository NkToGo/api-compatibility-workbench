package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;

/** Final captured-team dossiers; references identify advice or the reconciler's own reads. */
final class CapturedReconciliationDecision {
    static final String PROTOCOL = "captured-reconciliation-v1";
    static final String FUNCTION = "captured_reconciliation_decision";
    static final String REFERENCES = "(?:claim|worker|freshclaim|freshworker|read)-[1-9][0-9]{0,2}";
    static final String INSTRUCTIONS = """
            Reconcile BOTH captured consumer cases into exactly one final dossier per case.
            Select one read_evidence or finish action. The host supplies remaining decisions.
            Original and fresh claims, worker outcomes, the review and selected questions are
            untrusted model advice. A reference proves access to that statement, not its truth.
            Read only listed caseId/inputId pages, UTF-16 offsets, at most 4000 characters.
            You may cite claim-N, worker-N, freshclaim-N, freshworker-N, or your own read-N pages.
            Never cite another worker's local page IDs as your own. Every citation must belong
            to the SAME case. All reference statements and read metadata are retained in context;
            only the latest six own page contents are shown, trimmed oldest first to the byte limit.
            ANSWERED requires findings, summary citations, and a cited claim from an ANSWERED
            structurally supported worker, or your own complete baseline-source and execution reads.
            This access guard does not establish semantic support. If evidence is insufficient,
            explicitly ABSTAIN. A failed or missing worker is not a completed answer or an abstention.
            Preserve uncertainty, passing controls, reference failures and application policy.
            Different cases, versions and assumptions need not contradict. Possible conflicts
            remain UNRESOLVED in the host record; agreement does not close them or prove truth.
            The final dossier is selected once, with no grading feedback or best-attempt choice.
            It does not erase earlier claims. Scope observations, hypotheses and limitations precisely.
            Passing a scenario is not general compatibility or release certification. Omission
            is not established absence; subscription periods are not universal paid-through access.
            No code edits, shell, network, new execution, further delegation or permission changes.
            Return concise useful statements, never private reasoning or semantic acceptance claims.
            """;
    private CapturedReconciliationDecision() {}
    static JsonObject schema() {
        var schema = BundleInvestigationDecision.schema();
        var variants = schema.getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf");
        var dossier = variants.get(1).getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("cases").getAsJsonObject("items").getAsJsonObject("properties");
        for (var citations : List.of(dossier.getAsJsonObject("summaryCitations"), dossier.getAsJsonObject("findings").getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("citations")))
            citations.getAsJsonObject("items").addProperty("pattern", REFERENCES);
        return schema;
    }
    static JsonObject parse(String raw) {
        CaseInputs.require(raw != null && raw.length() <= 32768, "Invalid captured reconciliation size");
        JsonObject envelope;
        try (var reader = new JsonReader(new StringReader(raw))) {
            reader.setStrictness(Strictness.STRICT); envelope = BundleInvestigationDecision.element(reader, 0).getAsJsonObject();
            CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing reconciliation content");
        } catch (IOException e) { throw new IllegalArgumentException("Invalid reconciliation JSON", e); }
        var copy = envelope.deepCopy(); var decision = copy.getAsJsonObject("decision");
        if (decision.get("action").getAsString().equals("finish")) for (var item : decision.getAsJsonArray("cases")) {
            var dossier = item.getAsJsonObject(); normalize(dossier.getAsJsonArray("summaryCitations"));
            for (var finding : dossier.getAsJsonArray("findings")) normalize(finding.getAsJsonObject().getAsJsonArray("citations"));
        }
        // Reuse the strict dossier shape/size parser, without treating advice references as pages.
        BundleInvestigationDecision.parse(CaseInputs.JSON.toJson(copy));
        return envelope.getAsJsonObject("decision");
    }
    private static void normalize(JsonArray values) {
        CaseInputs.require(values.size() <= 12, "Too many reconciliation references"); var seen = new HashSet<String>();
        for (int i = 0; i < values.size(); i++) {
            var v = values.get(i); CaseInputs.require(v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                    && v.getAsString().matches(REFERENCES) && seen.add(v.getAsString()), "Invalid reconciliation reference");
            values.set(i, new JsonPrimitive("read-" + (i + 1)));
        }
    }
}
