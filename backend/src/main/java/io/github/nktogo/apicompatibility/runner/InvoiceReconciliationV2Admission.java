package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit host admission for original, review, fresh and final v2 roles; no stage dispatch. */
final class InvoiceReconciliationV2Admission {
    private static final String SELECTION = "invoice-team-reconciliation-v2-advisory-contract";
    private InvoiceReconciliationV2Admission() {}
    static CoordinationRuntime.Policy policy(int parallelCalls, int maxReads, List<CoordinationRuntime.Participant> participants) throws IOException {
        var policy = new CoordinationRuntime.Policy(parallelCalls, maxReads, new ContentDigest(RepairVerification.harness()),
                promptIdentity(), digest(ComparisonEvidence.INVOICE_RECONCILIATION_V2_TOOLS), contextIdentity(), SELECTION, participants);
        validate(policy); return policy;
    }
    static void validate(CoordinationRuntime.Policy policy) {
        CaseInputs.require(policy.maxReads() <= 100 && policy.promptIdentity().equals(promptIdentity())
                && policy.toolPolicyIdentity().equals(digest(ComparisonEvidence.INVOICE_RECONCILIATION_V2_TOOLS))
                && policy.contextPolicyIdentity().equals(contextIdentity()) && policy.finalSelection().equals(SELECTION)
                && policy.participants().stream().allMatch(p -> p.cases().equals(Set.of(InvoiceRenewalFixtures.CASE_ID))
                && p.protocols().size() == 1 && p.protocols().stream().allMatch(InvoiceReconciliationV2Admission::accepts)),
                "Invoice final reconciliation v2 policy does not match the admitted contract");
    }
    private static boolean accepts(String protocol) {
        return InvoiceReconciliationDecisionV2.PROTOCOL.equals(protocol) || InvoiceFollowUpDecisionV2.accepts(protocol) || InvoiceTeamDecisionV2.PROTOCOL.equals(protocol)
                || InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK).equals(protocol)
                || InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.RECONCILIATION).equals(protocol);
    }
    private static ContentDigest promptIdentity() {
        var contracts = new JsonObject(); contracts.addProperty(InvoiceReconciliationDecisionV2.PROTOCOL, InvoiceReconciliationDecisionV2.contractIdentity());
        contracts.addProperty(InvoiceTeamDecisionV2.PROTOCOL, InvoiceTeamDecisionV2.contractIdentity());
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var scope = InvoiceFollowUpDecisionV2.scope(worker);
            contracts.addProperty(InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope));
            contracts.addProperty(InvoiceFollowUpDecisionV2.protocol(worker), InvoiceFollowUpDecisionV2.contractIdentity(worker));
        }
        return digest(InvoiceDecisionV2.canonical(contracts));
    }
    private static ContentDigest contextIdentity() {
        var contexts = new JsonObject(); contexts.addProperty("workers", InvoiceContextV2.POLICY);
        contexts.addProperty("review", InvoiceTeamReviewV2.CONTEXT); contexts.addProperty("followUp", InvoiceFollowUpV2.CONTEXT);
        contexts.addProperty("source", InvoiceFollowUpV2.SOURCE);
        contexts.addProperty("reconciliation", InvoiceTeamReconciliationV2.CONTEXT); contexts.addProperty("reconciliationSource", InvoiceTeamReconciliationV2.SOURCE); contexts.addProperty("budget", CoordinationTeamBudget.POLICY);
        return digest(InvoiceDecisionV2.canonical(contexts));
    }
    private static ContentDigest digest(String value) { return new ContentDigest(CaseInputs.hash(value.getBytes(StandardCharsets.UTF_8))); }
}
