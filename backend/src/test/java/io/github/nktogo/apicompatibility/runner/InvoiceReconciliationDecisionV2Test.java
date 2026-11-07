package io.github.nktogo.apicompatibility.runner;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceReconciliationDecisionV2Test {
    @Test void strictDecisionParserAddsPrefixNavigationWithoutAddingAuthority() {
        String prefix = "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"business/\",\"offset\":0,\"limit\":40}}";
        assertEquals("catalog_prefix", InvoiceReconciliationDecisionV2.parse(prefix).get("action").getAsString());
        for (String invalid : List.of(prefix + "{}", prefix.replace("40", "41"), prefix.replace("business/", "../"),
                prefix.replace("\"offset\":0", "\"offset\":0,\"grant\":\"execute\""), prefix.replace("\"offset\":0", "\"offset\":0,\"offset\":1"),
                "{\"decision\":{\"action\":\"delegate\"}}", "{\"decision\":{\"action\":\"finish\"},\"extra\":true}"))
            assertThrows(RuntimeException.class, () -> InvoiceReconciliationDecisionV2.parse(invalid));
    }
    @Test void finalDossierKeepsNamespacedReferencesAndRejectsDuplicateOrUnknownFields() {
        String finish = "{\"decision\":{\"action\":\"finish\",\"response\":\"ABSTAINED\",\"summary\":\"Partial advice\",\"summaryCitations\":[\"claim-1\",\"freshclaim-1\",\"worker-2\",\"freshworker-1\",\"read-1\"],\"findings\":[]}}";
        assertEquals("freshclaim-1", InvoiceReconciliationDecisionV2.parse(finish).getAsJsonArray("summaryCitations").get(1).getAsString());
        for (String invalid : List.of(finish.replace("claim-1", "anchor-1"), finish.replace("freshclaim-1", "claim-1"), finish.replace("\"findings\":[]", "\"findings\":[],\"conflictsResolved\":true")))
            assertThrows(RuntimeException.class, () -> InvoiceReconciliationDecisionV2.parse(invalid));
    }
    @Test void versionedContractIsStableAndSchemaReturnsAreIndependent() {
        String identity = InvoiceReconciliationDecisionV2.contractIdentity(); assertEquals(64, identity.length());
        assertNotEquals(InvoiceTeamDecisionV2.PROTOCOL, InvoiceReconciliationDecisionV2.PROTOCOL);
        assertNotEquals(InvoiceReconciliationDecision.PROTOCOL, InvoiceReconciliationDecisionV2.PROTOCOL);
        var schema = InvoiceReconciliationDecisionV2.schema(); schema.addProperty("grant", "all");
        assertFalse(InvoiceReconciliationDecisionV2.schema().has("grant")); assertEquals(identity, InvoiceReconciliationDecisionV2.contractIdentity());
    }
    @Test void finalProtocolNeedsExplicitAdmissionAndOlderTeamPoliciesStayClosed() throws Exception {
        assertEquals(InvoiceReconciliationDecisionV2.PROTOCOL, new ModelCallBroker.Request("gpt-test", "input", 2048, InvoiceReconciliationDecisionV2.PROTOCOL).protocol());
        var participant = new CoordinationRuntime.Participant("invoice-final-reviewer", Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(InvoiceReconciliationDecisionV2.PROTOCOL));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.invoiceFollowUpV2Policy(2, 100, List.of(participant)));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.invoiceTeamReviewV2Policy(2, 100, List.of(participant)));
    }
}
