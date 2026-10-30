package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;

/** Structural finish gate over a parsed decision and host-derived own readiness, never semantic grading. */
final class InvoiceFinishV2 {
    private InvoiceFinishV2() {}
    static boolean supported(InvoiceReadiness.Subject subject, JsonObject decision, InvoiceReadiness.Snapshot readiness) {
        CaseInputs.require("finish".equals(decision.get("action").getAsString()), "Expected parsed invoice finish");
        if (!subject.equals(readiness.subject())) return false;
        var ids = Set.copyOf(readiness.citableTextPageIds());
        if (!cited(decision.getAsJsonArray("summaryCitations"), ids)) return false;
        for (var value : decision.getAsJsonArray("findings")) if (!cited(value.getAsJsonObject().getAsJsonArray("citations"), ids)) return false;
        return decision.get("response").getAsString().equals("ABSTAINED") || readiness.structuralAccessReady()
                && !decision.getAsJsonArray("summaryCitations").isEmpty() && !decision.getAsJsonArray("findings").isEmpty();
    }
    private static boolean cited(JsonArray citations, Set<String> ids) {
        for (var id : citations) if (!ids.contains(id.getAsString())) return false; return true;
    }
}
