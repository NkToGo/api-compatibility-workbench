package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.util.*;

/** Case-restricted version of the captured-evidence decision contract. */
final class CaseInvestigationDecision {
    static final String FUNCTION = "case_investigation_decision";
    static final String INSTRUCTIONS = BundleInvestigationDecision.INSTRUCTIONS
            .replace("Investigate BOTH registered consumer migration cases in one adaptive context.",
                    "Investigate ONLY your registered consumer migration case in one adaptive context.")
            .replace("Finish with exactly one dossier per case,", "Finish with exactly one dossier for your case,") + """
            Other workers' evidence, messages and findings are unavailable. Do not infer them.
            decisionsRemaining describes the shared call pool before this round, not a private allocation.
            Each active worker gets one decision per round; unused opportunities remain in the shared pool.
            """;
    private CaseInvestigationDecision() {}
    static String protocol(String caseId) {
        BundleInvestigationDecision.aliases(caseId);
        return caseId.equals(CaseInputs.CASE_ID) ? "customer-captured-v1" : "subscription-captured-v1";
    }
    static boolean accepts(String protocol) { return Set.of("customer-captured-v1", "subscription-captured-v1").contains(protocol == null ? "" : protocol); }
    static JsonObject schema(String protocol) {
        CaseInputs.require(accepts(protocol), "Unsupported case decision protocol");
        return BundleInvestigationDecision.schema(List.of(protocol.equals("customer-captured-v1") ? CaseInputs.CASE_ID : SubscriptionPeriodCase.CASE_ID));
    }
    static JsonObject parse(String raw, String caseId) { return BundleInvestigationDecision.parse(raw, Set.of(caseId)); }
}
