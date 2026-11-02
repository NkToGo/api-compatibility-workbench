package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate host admission for original v2 role workers and coordinator review; no stage dispatch. */
final class InvoiceTeamV2Admission {
    private static final String SELECTION = "invoice-team-v2-advisory-contract";
    private InvoiceTeamV2Admission() {}
    static CoordinationRuntime.Policy policy(int parallelCalls, int maxReads, List<CoordinationRuntime.Participant> participants) throws IOException {
        var policy = new CoordinationRuntime.Policy(parallelCalls, maxReads, new ContentDigest(RepairVerification.harness()),
                promptIdentity(), digest(ComparisonEvidence.INVOICE_TEAM_V2_TOOLS), digest(InvoiceTeamReviewV2.CONTEXT), SELECTION, participants);
        validate(policy); return policy;
    }
    static void validate(CoordinationRuntime.Policy policy) {
        CaseInputs.require(policy.maxReads() <= 100 && policy.promptIdentity().equals(promptIdentity())
                && policy.toolPolicyIdentity().equals(digest(ComparisonEvidence.INVOICE_TEAM_V2_TOOLS))
                && policy.contextPolicyIdentity().equals(digest(InvoiceTeamReviewV2.CONTEXT)) && policy.finalSelection().equals(SELECTION)
                && policy.participants().stream().allMatch(p -> p.cases().equals(Set.of(InvoiceRenewalFixtures.CASE_ID))
                && p.protocols().size() == 1 && p.protocols().stream().allMatch(InvoiceTeamV2Admission::accepts)),
                "Invoice team v2 policy does not match the admitted contract");
    }
    private static boolean accepts(String protocol) {
        return InvoiceTeamDecisionV2.PROTOCOL.equals(protocol)
                || InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK).equals(protocol)
                || InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.RECONCILIATION).equals(protocol);
    }
    private static ContentDigest promptIdentity() {
        var contracts = new JsonObject(); contracts.addProperty(InvoiceTeamDecisionV2.PROTOCOL, InvoiceTeamDecisionV2.contractIdentity());
        for (var scope : List.of(InvoiceReadiness.Scope.WEBHOOK, InvoiceReadiness.Scope.RECONCILIATION))
            contracts.addProperty(InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope));
        return digest(InvoiceDecisionV2.canonical(contracts));
    }
    private static ContentDigest digest(String text) { return new ContentDigest(CaseInputs.hash(text.getBytes(StandardCharsets.UTF_8))); }
}
