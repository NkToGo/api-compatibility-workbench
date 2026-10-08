package io.github.nktogo.apicompatibility.runner;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalLedger.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalTestInputs.*;

class InvoiceRenewalLedgerTest {
    private static InvoiceRenewalLedger empty() { return new InvoiceRenewalLedger("install_case", Map.of()); }
    private static Effect effect(String subscription) { return new Effect("cus_case", subscription, Action.RECORD_PAID_RENEWAL); }
    private static Key key(String invoice) { return new Key("install_case", invoice); }
    private static Observation webhook(String delivery, String subscription, String version) {
        return resolvedObservation("invoice-webhook", delivery, subscription, version);
    }
    private static Observation reconciliation(String delivery, String subscription, String version) {
        return resolvedObservation("invoice-reconciliation", delivery, subscription, version);
    }

    @Test void eachPathAloneAndBothOrdersProduceOneEffectUnderEveryVersionVector() {
        for (String eventVersion : List.of(ACACIA, BASIL)) for (String requestVersion : List.of(ACACIA, BASIL)) {
            var webhook = webhook("evt_one", "sub_primary", eventVersion);
            var reconciliation = reconciliation("request_one", "sub_primary", requestVersion);
            for (var order : List.of(List.of(webhook), List.of(reconciliation),
                    List.of(webhook, reconciliation), List.of(reconciliation, webhook))) {
                var ledger = empty(); order.forEach(ledger::accept);
                assertEquals(Map.of(key("in_primary"), effect("sub_primary")), ledger.snapshot().effects());
                assertEquals(Outcome.APPENDED, ledger.snapshot().receipts().getFirst().outcome());
                if (order.size() == 2) assertEquals(Outcome.DUPLICATE, ledger.snapshot().receipts().getLast().outcome());
                assertEquals(order.stream().map(Observation::actual).toList(),
                        ledger.snapshot().receipts().stream().map(r -> r.observation().actual()).toList());
            }
        }
    }

    @Test void duplicateDeliveriesRetainEachAttemptAndBothSources() {
        var one = webhook("evt_one", "sub_primary", ACACIA);
        var two = webhook("evt_two", "sub_primary", ACACIA);
        var job = reconciliation("request_one", "sub_primary", BASIL);
        for (var order : List.of(List.of(one, one, two, job, job), List.of(job, job, two, one, one))) {
            var ledger = empty(); order.forEach(ledger::accept); var snapshot = ledger.snapshot();
            assertEquals(1, snapshot.effects().size()); assertEquals(5, snapshot.receipts().size());
            assertEquals(List.of(1, 2, 3, 4, 5), snapshot.receipts().stream().map(Receipt::sequence).toList());
            assertEquals(1L, snapshot.receipts().stream().filter(r -> r.outcome() == Outcome.APPENDED).count());
            assertEquals(4L, snapshot.receipts().stream().filter(r -> r.outcome() == Outcome.DUPLICATE).count());
            assertEquals(order, snapshot.receipts().stream().map(Receipt::observation).toList());
            assertNotEquals(one.actual().inputIdentity(), two.actual().inputIdentity());
        }
    }

    @Test void sameCustomerDoesNotCollapseTwoInvoiceSubscriptionPairs() {
        var primaryInput = input("evt_primary"); var secondaryInput = input("evt_secondary");
        var primary = resolved(primaryInput, "sub_primary", BASIL);
        var secondary = fact(secondaryInput, Status.RESOLVED, "in_secondary", "sub_secondary", BASIL, null);
        var one = observe("invoice-webhook", primaryInput, primary, primary, "COMPLETED");
        var two = observe("invoice-reconciliation", secondaryInput, secondary, secondary, "COMPLETED");
        for (var order : List.of(List.of(one, two), List.of(two, one))) {
            var ledger = empty(); order.forEach(ledger::accept);
            assertEquals(Map.of(key("in_primary"), effect("sub_primary"), key("in_secondary"), effect("sub_secondary")), ledger.snapshot().effects());
            assertTrue(ledger.snapshot().receipts().stream().allMatch(r -> r.outcome() == Outcome.APPENDED));
        }
    }

    @Test void explicitNonSubscriptionAndEveryRefusalPreservePreexistingEffects() {
        for (var status : List.of(Status.NON_SUBSCRIPTION, Status.UNRESOLVED)) {
            var reasons = status == Status.UNRESOLVED ? Arrays.asList(Reason.values()) : Collections.<Reason>singletonList(null);
            for (var reason : reasons) {
                var ledger = new InvoiceRenewalLedger("install_case", Map.of("in_primary", effect("sub_primary")));
                var input = input("evt_refusal");
                var fact = fact(input, status, "in_primary", null, reason == Reason.UNKNOWN_VERSION ? null : BASIL, reason);
                var observation = observe("invoice-webhook", input, fact, fact, "COMPLETED");
                var receipt = ledger.accept(observation);
                assertEquals(Assertion.PASSED, receipt.observation().assertion()); assertEquals(Outcome.NO_EFFECT, receipt.outcome());
                assertEquals(Map.of(key("in_primary"), effect("sub_primary")), ledger.snapshot().effects());
                assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
            }
        }
    }

    @Test void existingAssociationConflictPreservesTheOriginalInBothOrders() {
        var one = webhook("evt_conflict", "sub_secondary", BASIL);
        var two = reconciliation("request_conflict", "sub_secondary", BASIL);
        for (var order : List.of(List.of(one, two), List.of(two, one))) {
            var ledger = new InvoiceRenewalLedger("install_case", Map.of("in_primary", effect("sub_primary")));
            order.forEach(ledger::accept); var snapshot = ledger.snapshot();
            assertEquals(Map.of(key("in_primary"), effect("sub_primary")), snapshot.effects());
            assertTrue(snapshot.receipts().stream().allMatch(r -> r.outcome() == Outcome.CONFLICT
                    && r.observation().assertion() == Assertion.PASSED));
        }
    }

    @Test void emptyLedgerDisagreementRetainsOrderDependenceWithoutAnArbitraryWinner() {
        var one = webhook("evt_primary", "sub_primary", ACACIA);
        var two = reconciliation("request_secondary", "sub_secondary", BASIL);
        for (var order : List.of(List.of(one, two), List.of(two, one))) {
            var ledger = empty(); order.forEach(ledger::accept); var snapshot = ledger.snapshot();
            assertEquals(Outcome.APPENDED, snapshot.receipts().getFirst().outcome());
            assertEquals(Outcome.CONFLICT, snapshot.receipts().getLast().outcome());
            assertEquals(effect(order.getFirst().actual().subscriptionId()), snapshot.effects().get(key("in_primary")));
            assertEquals(2, snapshot.receipts().size());
        }
    }

    @Test void failedBehavioralAssertionsStillExposeWrongEffectsAndIntermediateConflicts() {
        var input = input("evt_wrong"); var expected = resolved(input, "sub_primary", ACACIA);
        var wrong = resolved(input, "sub_secondary", ACACIA);
        var failed = observe("invoice-webhook", input, expected, wrong, "COMPLETED");
        var good = reconciliation("request_primary", "sub_primary", BASIL);
        for (var order : List.of(List.of(failed, good), List.of(good, failed))) {
            var ledger = empty(); order.forEach(ledger::accept); var snapshot = ledger.snapshot();
            assertEquals(Assertion.FAILED, failed.assertion());
            assertEquals(Outcome.CONFLICT, snapshot.receipts().getLast().outcome());
            assertEquals(effect(order.getFirst().actual().subscriptionId()), snapshot.effects().get(key("in_primary")));
            assertEquals(wrong, snapshot.receipts().stream().filter(r -> r.observation() == failed).findFirst().orElseThrow().observation().actual());
        }
    }

    @Test void correctFinalEffectCannotHideWrongVersionOrFalseStandaloneOnOtherPath() {
        var input = input("evt_wrong"); var expected = resolved(input, "sub_primary", BASIL);
        var good = reconciliation("request_primary", "sub_primary", BASIL);
        for (var wrong : List.of(resolved(input, "sub_primary", ACACIA),
                fact(input, Status.NON_SUBSCRIPTION, "in_primary", null, BASIL, null))) {
            var failed = observe("invoice-webhook", input, expected, wrong, "COMPLETED");
            for (var order : List.of(List.of(failed, good), List.of(good, failed))) {
                var ledger = empty(); order.forEach(ledger::accept);
                assertEquals(Map.of(key("in_primary"), effect("sub_primary")), ledger.snapshot().effects());
                assertTrue(ledger.snapshot().receipts().stream().anyMatch(r -> r.observation().assertion() == Assertion.FAILED));
            }
        }
    }

    @Test void unboundResultsCannotSupplyAnEffectEvenWithValidBusinessIds() {
        var input = input("evt_wrong"); var expected = resolved(input, "sub_primary", BASIL);
        for (String field : List.of("inputIdentity", "evidenceIdentity")) {
            var json = expected.json(); json.addProperty(field, "b".repeat(64));
            var observation = observe("invoice-webhook", input, expected, Fact.parse(json), "COMPLETED");
            var ledger = empty(); var receipt = ledger.accept(observation);
            assertEquals(Outcome.NO_EFFECT, receipt.outcome()); assertFalse(observation.identitiesMatch());
            assertEquals(Assertion.FAILED, observation.assertion()); assertTrue(ledger.snapshot().effects().isEmpty());
        }
    }

    @Test void missingFailedAndInvalidExchangesRetainNoEffectAndNotEvaluated() {
        var input = input("evt_one"); var expected = resolved(input, "sub_primary", BASIL);
        var observations = new ArrayList<Observation>();
        observations.add(observe("invoice-webhook", input, expected, null, "COMPLETED"));
        for (String execution : List.of("TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR"))
            observations.add(observe("invoice-webhook", input, expected, expected, execution));
        var malformed = new InvoiceRenewalOracle("invoice-webhook", input, expected);
        malformed.request(operation("read")); malformed.request(returned(expected.json()));
        assertThrows(RuntimeException.class, () -> malformed.request(returned(expected.json())));
        observations.add(malformed.observation("COMPLETED"));
        var ledger = new InvoiceRenewalLedger("install_case", Map.of("in_primary", effect("sub_primary")));
        for (var observation : observations) {
            var receipt = ledger.accept(observation);
            assertEquals(Assertion.NOT_EVALUATED, receipt.observation().assertion()); assertEquals(Outcome.NO_EFFECT, receipt.outcome());
        }
        assertEquals(5, ledger.snapshot().receipts().size());
        assertEquals(ledger.snapshot().initialEffects(), ledger.snapshot().effects());
        assertNull(ledger.snapshot().receipts().getFirst().observation().actual());
    }

    @Test void installationKeyComesFromHostAndInitialStateAndSnapshotsAreImmutable() {
        var initial = new HashMap<String, Effect>(); initial.put("in_primary", effect("sub_primary"));
        var ledger = new InvoiceRenewalLedger("install_other", initial); initial.clear();
        var before = ledger.snapshot();
        var conflict = ledger.accept(webhook("evt_conflict", "sub_secondary", BASIL));
        assertEquals(Outcome.CONFLICT, conflict.outcome());
        assertEquals(Map.of(new Key("install_other", "in_primary"), effect("sub_primary")), ledger.snapshot().effects());
        assertTrue(before.receipts().isEmpty()); assertEquals(1, ledger.snapshot().receipts().size());
        assertThrows(UnsupportedOperationException.class, () -> before.effects().clear());
        assertThrows(UnsupportedOperationException.class, () -> before.initialEffects().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.snapshot().receipts().clear());
    }

    @Test void malformedInitialStateAndEffectActionsAreRejected() {
        assertThrows(RuntimeException.class, () -> new InvoiceRenewalLedger("", Map.of()));
        assertThrows(RuntimeException.class, () -> new InvoiceRenewalLedger("install_case", Map.of("invalid invoice", effect("sub_primary"))));
        assertThrows(RuntimeException.class, () -> new Effect("cus_case", "sub_primary", null));
        assertThrows(RuntimeException.class, () -> empty().accept(null));
    }
}
