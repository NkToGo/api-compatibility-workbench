package io.github.nktogo.apicompatibility.runner;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceFollowUpDecisionV2Test {
    @Test void fixedFreshRolesHaveDistinctContractsAndDoNotAliasOriginalOrV1Protocols() {
        assertNotEquals(InvoiceFollowUpDecisionV2.contractIdentity("invoice-webhook"), InvoiceFollowUpDecisionV2.contractIdentity("invoice-reconciliation"));
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            String protocol = InvoiceFollowUpDecisionV2.protocol(worker); assertTrue(InvoiceFollowUpDecisionV2.accepts(protocol));
            assertFalse(InvoiceDecisionV2.accepts(protocol)); assertFalse(InvoiceFollowUpDecision.accepts(protocol));
            assertEquals("fresh-" + worker, InvoiceFollowUpDecisionV2.participant(worker));
            assertEquals(worker, InvoiceFollowUpDecisionV2.worker(protocol));
            assertEquals(64, InvoiceFollowUpDecisionV2.contractIdentity(worker).length());
        }
        assertFalse(InvoiceFollowUpDecisionV2.accepts(null)); assertFalse(InvoiceFollowUpDecisionV2.accepts(InvoiceTeamDecisionV2.PROTOCOL));
        assertThrows(RuntimeException.class, () -> InvoiceFollowUpDecisionV2.protocol("new-agent"));
    }
    @Test void strictFreshDecisionsKeepPrefixBoundsAndRejectDelegationOrGrantFields() {
        String prefix = "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"business/\",\"offset\":0,\"limit\":40}}";
        assertEquals("catalog_prefix", InvoiceFollowUpDecisionV2.parse(prefix).get("action").getAsString());
        for (String invalid : List.of(prefix + "{}", prefix.replace("40", "41"), prefix.replace("business/", "../"),
                "{\"decision\":{\"action\":\"delegate\",\"workerId\":\"other\"}}", prefix.replace("\"offset\":0", "\"offset\":0,\"permission\":\"execute\"")))
            assertThrows(RuntimeException.class, () -> InvoiceFollowUpDecisionV2.parse(invalid));
        var schema = InvoiceFollowUpDecisionV2.schema(); schema.addProperty("grant", "all"); assertFalse(InvoiceFollowUpDecisionV2.schema().has("grant"));
    }
    @Test void existingRuntimePoliciesStillRejectFreshProtocolsAfterBrokerAdmission() {
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            String protocol = InvoiceFollowUpDecisionV2.protocol(worker);
            assertEquals(protocol, new ModelCallBroker.Request("gpt-test", "input", 2048, protocol).protocol());
            assertThrows(RuntimeException.class, () -> CoordinationRuntime.invoiceTeamReviewV2Policy(2, 100, List.of(new CoordinationRuntime.Participant(
                    InvoiceFollowUpDecisionV2.participant(worker), Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(protocol)))));
        }
    }
}
