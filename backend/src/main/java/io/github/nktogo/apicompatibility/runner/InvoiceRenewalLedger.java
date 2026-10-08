package io.github.nktogo.apicompatibility.runner;

import java.util.*;

/** Pure ordered host policy, not a durable database, concurrency implementation or release approval. */
final class InvoiceRenewalLedger {
    enum Action { RECORD_PAID_RENEWAL }
    enum Outcome { APPENDED, DUPLICATE, CONFLICT, NO_EFFECT }
    record Key(String installationId, String invoiceId) {
        Key { InvoiceRenewalOracle.identifier(installationId); InvoiceRenewalOracle.identifier(invoiceId); }
    }
    record Effect(String customerId, String subscriptionId, Action action) {
        Effect {
            InvoiceRenewalOracle.identifier(customerId); InvoiceRenewalOracle.identifier(subscriptionId);
            CaseInputs.require(action == Action.RECORD_PAID_RENEWAL, "Unsupported invoice effect");
        }
    }
    record Receipt(int sequence, InvoiceRenewalOracle.Observation observation, Outcome outcome) {}
    record Snapshot(Map<Key, Effect> initialEffects, Map<Key, Effect> effects, List<Receipt> receipts) {
        Snapshot {
            initialEffects = Map.copyOf(initialEffects); effects = Map.copyOf(effects); receipts = List.copyOf(receipts);
        }
    }

    private final String installationId;
    private final Map<Key, Effect> initialEffects, effects = new LinkedHashMap<>();
    private final List<Receipt> receipts = new ArrayList<>();

    InvoiceRenewalLedger(String installationId, Map<String, Effect> initialState) {
        InvoiceRenewalOracle.identifier(installationId); this.installationId = installationId;
        Objects.requireNonNull(initialState).forEach((invoice, effect) ->
                effects.put(new Key(installationId, invoice), Objects.requireNonNull(effect)));
        initialEffects = Map.copyOf(effects);
    }

    synchronized Receipt accept(InvoiceRenewalOracle.Observation observation) {
        Objects.requireNonNull(observation);
        Outcome outcome = Outcome.NO_EFFECT;
        var fact = observation.actual();
        // Preserve behavior of an incorrect but evaluable fact: a failed assertion is not erased
        // by substituting the expected value. Unbound/incomplete exchanges cannot supply effects.
        if (observation.assertion() != InvoiceRenewalOracle.Assertion.NOT_EVALUATED && observation.identitiesMatch()
                && fact.status() == InvoiceRenewalOracle.Status.RESOLVED) {
            var key = new Key(installationId, fact.invoiceId());
            var value = new Effect(fact.customerId(), fact.subscriptionId(), Action.RECORD_PAID_RENEWAL);
            var existing = effects.get(key);
            if (existing == null) { effects.put(key, value); outcome = Outcome.APPENDED; }
            else outcome = existing.equals(value) ? Outcome.DUPLICATE : Outcome.CONFLICT;
        }
        var receipt = new Receipt(receipts.size() + 1, observation, outcome);
        receipts.add(receipt); return receipt;
    }

    synchronized Snapshot snapshot() { return new Snapshot(initialEffects, effects, receipts); }
}
