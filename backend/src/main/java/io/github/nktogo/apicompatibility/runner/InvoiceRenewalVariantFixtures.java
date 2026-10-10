package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;

/** Shared registration for compiled invoice variant catalogs, never consumer execution evidence. */
final class InvoiceRenewalVariantFixtures {
    enum Layout { SINGLE, TWO_INVOICES, REPEATED, SEEDED_CONFLICT, FRESH_CONFLICT }
    record Variant(String id, String family, String train, Layout layout) {
        Variant(String id, String family, String train) {
            this(id, family, train, family.equals("two-subscriptions") ? Layout.TWO_INVOICES : Layout.SINGLE);
        }
        Variant {
            InvoiceRenewalOracle.identifier(id); InvoiceRenewalOracle.identifier(family);
            CaseInputs.require(Set.of("acacia", "basil").contains(train), "Unregistered fixture train");
            Objects.requireNonNull(layout);
            CaseInputs.require(switch (layout) {
                case SINGLE -> !Set.of("two-subscriptions", "duplicate-delivery", "conflicting-association").contains(family);
                case TWO_INVOICES -> family.equals("two-subscriptions");
                case REPEATED -> family.equals("duplicate-delivery");
                case SEEDED_CONFLICT, FRESH_CONFLICT -> family.equals("conflicting-association");
            }, "Invoice variant layout/family mismatch");
        }
        String version() { return train.equals("acacia") ? "2025-02-24.acacia" : "2025-03-31.basil"; }
        boolean multiple() { return layout == Layout.TWO_INVOICES; }
        List<String> path(String role) {
            CaseInputs.require(Set.of("webhook", "reconciliation").contains(role), "Unregistered invoice path");
            if (layout == Layout.REPEATED) return role.equals("webhook")
                    ? List.of("webhook", "webhook", "webhook-alternate") : List.of("reconciliation", "reconciliation");
            return multiple() ? List.of(role + "-primary", role + "-secondary") : List.of(role);
        }
        String stateName(String role) {
            boolean webhook = role.equals("webhook");
            if (multiple()) return "state-list-" + train + (webhook ? "-forward.json" : "-reverse.json");
            boolean secondary = layout == Layout.SEEDED_CONFLICT || (layout == Layout.FRESH_CONFLICT && !webhook);
            return "state-" + (secondary ? "secondary-" : "primary-") + train + ".json";
        }
        Map<String, InvoiceRenewalLedger.Effect> initialEffects() {
            return layout == Layout.SEEDED_CONFLICT ? Map.of("in_primary", new InvoiceRenewalLedger.Effect(
                    "cus_case", "sub_primary", InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL)) : Map.of();
        }
    }
    private InvoiceRenewalVariantFixtures() {}

    static final class Bundle {
        private final String installation, identity;
        private final Set<String> families;
        private final Map<String, byte[]> files;
        private final List<Scenario> scenarios;
        private Bundle(String installation, String identity, Set<String> families, Map<String, byte[]> files, List<Scenario> scenarios) {
            this.installation = installation; this.identity = identity; this.families = Set.copyOf(families);
            var copy = new TreeMap<String, byte[]>(); files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            this.files = Collections.unmodifiableMap(copy); this.scenarios = List.copyOf(scenarios);
        }
        String identity() { return identity; }
        String installationId() { return installation; }
        List<Scenario> scenarios() { return scenarios; }
        Set<String> familyIds() { return families; }
        byte[] bytes(String name) { CaseInputs.require(files.containsKey(name), "Unregistered fixture file"); return files.get(name).clone(); }
        void requireFullCaseCoverage() {
            throw new IllegalStateException("Invoice fixture coverage incomplete: required families and admission/execution controls are not all registered");
        }
    }

    static Bundle load(Path caseRoot, String corpus, String pin, String stage, List<Variant> variants) throws IOException {
        variants = List.copyOf(variants);
        CaseInputs.require(!variants.isEmpty(), "Missing invoice variant catalog");
        var inventory = inventory(variants);
        var files = pinnedFiles(caseRoot, corpus, pin, stage, inventory);
        var inputs = object(files.get("scenarios.json"));
        fields(inputs, "formatVersion", "caseId", "policy", "origin", "installationId", "approvedBindings", "scenarios");
        header(inputs); equal(inputs, "origin", "authored-synthetic"); String installation = text(inputs, "installationId");
        InvoiceRenewalOracle.identifier(installation);
        var bindings = array(inputs, "approvedBindings");
        var keys = new HashSet<String>();
        for (var value : bindings) {
            var binding = asObject(value); fields(binding, "customerId", "subscriptionId");
            String customer = text(binding, "customerId"), subscription = text(binding, "subscriptionId");
            InvoiceRenewalOracle.identifier(customer); InvoiceRenewalOracle.identifier(subscription);
            CaseInputs.require(keys.add(customer + ":" + subscription), "Duplicate approved binding");
        }
        CaseInputs.require(keys.equals(Set.of("cus_case:sub_primary", "cus_case:sub_secondary")), "Invoice variant binding coverage mismatch");
        var expectations = object(files.get("expectations.json"));
        fields(expectations, "formatVersion", "caseId", "policy", "authority", "scenarios");
        header(expectations); equal(expectations, "authority", "authored-application-policy");
        var rows = indexed(array(inputs, "scenarios"), variants); var answers = indexed(array(expectations, "scenarios"), variants);
        CaseInputs.require(rows.keySet().equals(answers.keySet()), "Invoice variant expectation coverage mismatch");
        var used = new HashSet<String>(); var scenarios = new ArrayList<Scenario>();
        for (var variant : variants) {
            var row = rows.get(variant.id()); var answer = answers.get(variant.id());
            fields(row, "id", "familyId", "initialEffects", "deliveries"); equal(row, "familyId", variant.family());
            fields(answer, "id", "facts", "sequences");
            var initial = effects(array(row, "initialEffects"));
            CaseInputs.require(initial.equals(variant.initialEffects()), "Unexpected invoice variant initial ledger");
            var facts = obj(answer, "facts"); var deliveries = new LinkedHashMap<String, Delivery>();
            var expectedIds = new LinkedHashSet<>(variant.path("webhook")); expectedIds.addAll(variant.path("reconciliation"));
            for (var value : array(row, "deliveries")) {
                var delivery = asObject(value);
                fields(delivery, "id", "consumerId", "effectiveApiVersion", "source", "subscriptionState", "configuration");
                String id = text(delivery, "id"), consumer = text(delivery, "consumerId");
                CaseInputs.require(expectedIds.contains(id), "Unexpected invoice variant delivery");
                boolean webhook = variant.path("webhook").contains(id);
                equal(delivery, "consumerId", webhook ? "invoice-webhook" : "invoice-reconciliation");
                equal(delivery, "effectiveApiVersion", variant.version());
                var config = obj(delivery, "configuration"); configuration(config, variant.version(), webhook);
                equal(config, "endpointApiVersion", variant.version()); equal(config, "accountApiVersion", "2025-02-24.acacia");
                String sourceName = variant.id() + "-" + id + ".json";
                String stateName = variant.stateName(webhook ? "webhook" : "reconciliation");
                equal(delivery, "source", sourceName); equal(delivery, "subscriptionState", stateName);
                used.add(sourceName); used.add(stateName);
                if (webhook) {
                    var event = object(files.get(sourceName)); equal(event, "api_version", variant.version()); equal(event, "type", "invoice.paid");
                }
                var input = new JsonObject(); input.addProperty("scenarioId", variant.id()); input.addProperty("familyId", variant.family());
                input.addProperty("deliveryId", id); input.addProperty("consumerId", consumer); input.addProperty("installationId", installation);
                input.addProperty("effectiveApiVersion", variant.version()); input.add("configuration", config.deepCopy());
                input.add("approvedBindings", bindings.deepCopy());
                input.add("source", source(files, sourceName, webhook ? "event-snapshot" : "invoice-retrieval", variant.version()));
                input.add("subscriptionState", source(files, stateName, variant.multiple() ? "subscription-state-list" : "subscription-retrieval", variant.version()));
                String inputIdentity = InvoiceRenewalOracle.inputIdentity(input);
                var evidence = new JsonObject(); evidence.addProperty("bundleIdentity", pin);
                evidence.addProperty("scenarioId", variant.id()); evidence.addProperty("deliveryId", id); evidence.addProperty("inputIdentity", inputIdentity);
                var fact = obj(facts, id).deepCopy();
                fields(fact, "status", "invoiceId", "customerId", "subscriptionId", "effectiveApiVersion", "reason");
                fact.addProperty("inputIdentity", inputIdentity); fact.addProperty("evidenceIdentity", InvoiceRenewalOracle.inputIdentity(evidence));
                var expected = InvoiceRenewalOracle.Fact.parse(fact);
                CaseInputs.require(variant.version().equals(expected.effectiveApiVersion()), "Invoice variant expected version mismatch");
                CaseInputs.require(deliveries.putIfAbsent(id, new Delivery(id, consumer, input, expected)) == null, "Duplicate invoice variant delivery");
            }
            CaseInputs.require(new ArrayList<>(deliveries.keySet()).equals(new ArrayList<>(expectedIds))
                    && facts.keySet().equals(expectedIds), "Incomplete invoice variant path coverage");
            scenarios.add(new Scenario(variant.id(), initial, deliveries, sequences(answer, variant)));
        }
        var sources = new HashSet<>(inventory); sources.removeAll(Set.of("scenarios.json", "expectations.json"));
        CaseInputs.require(used.equals(sources), "Unreferenced invoice variant source");
        return new Bundle(installation, pin, variants.stream().map(Variant::family).collect(java.util.stream.Collectors.toSet()), files, scenarios);
    }

    private static List<Sequence> sequences(JsonObject answer, Variant variant) {
        var webhook = variant.path("webhook"); var reconciliation = variant.path("reconciliation");
        var orders = Map.of("webhook-alone", webhook, "reconciliation-alone", reconciliation,
                "webhook-first", joined(webhook, reconciliation), "reconciliation-first", joined(reconciliation, webhook));
        var names = new HashSet<String>(); var result = new ArrayList<Sequence>();
        for (var value : array(answer, "sequences")) {
            var row = asObject(value); fields(row, "id", "deliveries", "outcomes", "finalEffects");
            String id = text(row, "id"); List<String> order = strings(array(row, "deliveries"));
            CaseInputs.require(names.add(id) && orders.containsKey(id) && orders.get(id).equals(order), "Invalid invoice variant arrival coverage");
            var outcomes = strings(array(row, "outcomes")).stream().map(InvoiceRenewalFixtures::outcome).toList();
            CaseInputs.require(outcomes.size() == order.size(), "Missing invoice variant ledger expectations");
            result.add(new Sequence(id, order, outcomes, effects(array(row, "finalEffects"))));
        }
        CaseInputs.require(names.equals(orders.keySet()), "Incomplete invoice variant arrival coverage"); return List.copyOf(result);
    }
    private static List<String> joined(List<String> first, List<String> second) {
        var result = new ArrayList<>(first); result.addAll(second); return List.copyOf(result);
    }
    private static Map<String, JsonObject> indexed(JsonArray rows, List<Variant> variants) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var value : rows) {
            var row = asObject(value); String id = text(row, "id");
            CaseInputs.require(result.putIfAbsent(id, row) == null, "Duplicate invoice variant scenario");
        }
        CaseInputs.require(new ArrayList<>(result.keySet()).equals(variants.stream().map(Variant::id).toList()), "Incomplete invoice variant coverage");
        return result;
    }
    private static Set<String> inventory(List<Variant> variants) {
        var result = new HashSet<>(Set.of("scenarios.json", "expectations.json"));
        for (var variant : variants) {
            for (String role : List.of("webhook", "reconciliation")) {
                for (String id : variant.path(role)) result.add(variant.id() + "-" + id + ".json");
                result.add(variant.stateName(role));
            }
        }
        return Set.copyOf(result);
    }
}
