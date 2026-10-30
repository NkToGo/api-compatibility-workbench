package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact host policy for the three v2 investigation scopes; no adapter loop or semantic acceptance. */
final class InvoiceV2Admission {
    private static final String SELECTION = "invoice-v2-advisory-contract";
    private InvoiceV2Admission() {}
    static CoordinationRuntime.Policy policy(int parallelCalls, int maxReads, List<CoordinationRuntime.Participant> participants) throws IOException {
        var policy = new CoordinationRuntime.Policy(parallelCalls, maxReads, new ContentDigest(RepairVerification.harness()),
                promptIdentity(), digest(ComparisonEvidence.INVOICE_V2_TOOLS), digest(InvoiceContextV2.POLICY), SELECTION, participants);
        validate(policy); return policy;
    }
    static void validate(CoordinationRuntime.Policy policy) {
        CaseInputs.require(policy.maxReads() <= 100 && policy.promptIdentity().equals(promptIdentity())
                && policy.toolPolicyIdentity().equals(digest(ComparisonEvidence.INVOICE_V2_TOOLS))
                && policy.contextPolicyIdentity().equals(digest(InvoiceContextV2.POLICY)) && policy.finalSelection().equals(SELECTION)
                && policy.participants().stream().allMatch(p -> p.cases().equals(Set.of(InvoiceRenewalFixtures.CASE_ID))
                && p.protocols().size() == 1 && p.protocols().stream().allMatch(InvoiceDecisionV2::accepts)),
                "Invoice v2 policy does not match the admitted contract");
    }
    static void validateDefinition(CoordinationExperiment.Definition definition) {
        var limits = definition.limits();
        CaseInputs.require(limits.modelCalls() <= 60 && limits.elapsedMillis() <= 1200000
                && limits.inputBytesPerCall() <= 64000 && limits.outputTokensPerCall() <= 2048, "Invoice v2 definition exceeds admitted ceilings");
    }
    private static ContentDigest promptIdentity() {
        var contracts = new JsonObject();
        for (var scope : InvoiceReadiness.Scope.values()) contracts.addProperty(InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope));
        return digest(InvoiceDecisionV2.canonical(contracts));
    }
    private static ContentDigest digest(String text) { return new ContentDigest(CaseInputs.hash(text.getBytes(StandardCharsets.UTF_8))); }
}
