package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;

/** Separately pinned association variants. Registration is not consumer execution evidence. */
final class InvoiceRenewalAssociationFixtures {
    static final String BUNDLE_SHA256 = "991c268eb208ba25a1704ed7583e85d00cfa5165400f522eadd6a5f3ffff0940";
    private record Variant(String id, String family, String train) {
        String version() { return train.equals("acacia") ? "2025-02-24.acacia" : "2025-03-31.basil"; }
        boolean multiple() { return family.equals("two-subscriptions"); }
        List<String> path(String role) { return multiple() ? List.of(role + "-primary", role + "-secondary") : List.of(role); }
    }
    private static final List<Variant> VARIANTS = variants();
    private static final Set<String> FILES = inventory();
    private InvoiceRenewalAssociationFixtures() {}

    static final class Bundle {
        private final String installation;
        private final Map<String, byte[]> files;
        private final List<Scenario> scenarios;
        private Bundle(String installation, Map<String, byte[]> files, List<Scenario> scenarios) {
            this.installation = installation;
            var copy = new TreeMap<String, byte[]>(); files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            this.files = Collections.unmodifiableMap(copy); this.scenarios = List.copyOf(scenarios);
        }
        String identity() { return BUNDLE_SHA256; }
        String installationId() { return installation; }
        List<Scenario> scenarios() { return scenarios; }
        Set<String> familyIds() { return Set.of("expanded-association", "two-subscriptions", "standalone-invoice", "quote-parent"); }
        byte[] bytes(String name) { CaseInputs.require(files.containsKey(name), "Unregistered fixture file"); return files.get(name).clone(); }
        void requireFullCaseCoverage() {
            throw new IllegalStateException("Invoice fixture coverage incomplete: refusal, conflict and admission/execution controls are not registered");
        }
    }

    static Bundle load(Path caseRoot) throws IOException {
        var files = pinnedFiles(caseRoot, "association-v1", BUNDLE_SHA256, "ASSOCIATION_VARIANTS_ONLY", FILES);
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
        CaseInputs.require(keys.equals(Set.of("cus_case:sub_primary", "cus_case:sub_secondary")), "Association binding coverage mismatch");
        var expectations = object(files.get("expectations.json"));
        fields(expectations, "formatVersion", "caseId", "policy", "authority", "scenarios");
        header(expectations); equal(expectations, "authority", "authored-application-policy");
        var rows = indexed(array(inputs, "scenarios")); var answers = indexed(array(expectations, "scenarios"));
        CaseInputs.require(rows.keySet().equals(answers.keySet()), "Association expectation coverage mismatch");
        var used = new HashSet<String>(); var scenarios = new ArrayList<Scenario>();
        for (var variant : VARIANTS) {
            var row = rows.get(variant.id()); var answer = answers.get(variant.id());
            fields(row, "id", "familyId", "initialEffects", "deliveries"); equal(row, "familyId", variant.family());
            fields(answer, "id", "facts", "sequences");
            var initial = effects(array(row, "initialEffects"));
            CaseInputs.require(initial.isEmpty(), "Unexpected association initial ledger");
            var facts = obj(answer, "facts"); var deliveries = new LinkedHashMap<String, Delivery>();
            var expectedIds = new LinkedHashSet<>(variant.path("webhook")); expectedIds.addAll(variant.path("reconciliation"));
            for (var value : array(row, "deliveries")) {
                var delivery = asObject(value);
                fields(delivery, "id", "consumerId", "effectiveApiVersion", "source", "subscriptionState", "configuration");
                String id = text(delivery, "id"), consumer = text(delivery, "consumerId");
                CaseInputs.require(expectedIds.contains(id), "Unexpected association delivery");
                boolean webhook = variant.path("webhook").contains(id);
                equal(delivery, "consumerId", webhook ? "invoice-webhook" : "invoice-reconciliation");
                equal(delivery, "effectiveApiVersion", variant.version());
                var config = obj(delivery, "configuration"); configuration(config, variant.version(), webhook);
                equal(config, "endpointApiVersion", variant.version()); equal(config, "accountApiVersion", "2025-02-24.acacia");
                String sourceName = variant.id() + "-" + id + ".json";
                String stateName = variant.multiple() ? "state-list-" + variant.train() + (webhook ? "-forward.json" : "-reverse.json")
                        : "state-primary-" + variant.train() + ".json";
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
                var evidence = new JsonObject(); evidence.addProperty("bundleIdentity", BUNDLE_SHA256);
                evidence.addProperty("scenarioId", variant.id()); evidence.addProperty("deliveryId", id); evidence.addProperty("inputIdentity", inputIdentity);
                var fact = obj(facts, id).deepCopy();
                fields(fact, "status", "invoiceId", "customerId", "subscriptionId", "effectiveApiVersion", "reason");
                fact.addProperty("inputIdentity", inputIdentity); fact.addProperty("evidenceIdentity", InvoiceRenewalOracle.inputIdentity(evidence));
                var expected = InvoiceRenewalOracle.Fact.parse(fact);
                CaseInputs.require(variant.version().equals(expected.effectiveApiVersion()), "Association expected version mismatch");
                CaseInputs.require(deliveries.putIfAbsent(id, new Delivery(id, consumer, input, expected)) == null, "Duplicate association delivery");
            }
            CaseInputs.require(new ArrayList<>(deliveries.keySet()).equals(new ArrayList<>(expectedIds))
                    && facts.keySet().equals(expectedIds), "Incomplete association path coverage");
            scenarios.add(new Scenario(variant.id(), initial, deliveries, sequences(answer, variant)));
        }
        var sources = new HashSet<>(FILES); sources.removeAll(Set.of("scenarios.json", "expectations.json"));
        CaseInputs.require(used.equals(sources), "Unreferenced association source");
        return new Bundle(installation, files, scenarios);
    }

    private static List<Sequence> sequences(JsonObject answer, Variant variant) {
        var webhook = variant.path("webhook"); var reconciliation = variant.path("reconciliation");
        var orders = Map.of("webhook-alone", webhook, "reconciliation-alone", reconciliation,
                "webhook-first", joined(webhook, reconciliation), "reconciliation-first", joined(reconciliation, webhook));
        var names = new HashSet<String>(); var result = new ArrayList<Sequence>();
        for (var value : array(answer, "sequences")) {
            var row = asObject(value); fields(row, "id", "deliveries", "outcomes", "finalEffects");
            String id = text(row, "id"); List<String> order = strings(array(row, "deliveries"));
            CaseInputs.require(names.add(id) && orders.containsKey(id) && orders.get(id).equals(order), "Invalid association arrival coverage");
            var outcomes = strings(array(row, "outcomes")).stream().map(InvoiceRenewalFixtures::outcome).toList();
            CaseInputs.require(outcomes.size() == order.size(), "Missing association ledger expectations");
            result.add(new Sequence(id, order, outcomes, effects(array(row, "finalEffects"))));
        }
        CaseInputs.require(names.equals(orders.keySet()), "Incomplete association arrival coverage"); return List.copyOf(result);
    }
    private static List<String> joined(List<String> first, List<String> second) {
        var result = new ArrayList<>(first); result.addAll(second); return List.copyOf(result);
    }
    private static Map<String, JsonObject> indexed(JsonArray rows) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var value : rows) {
            var row = asObject(value); String id = text(row, "id");
            CaseInputs.require(result.putIfAbsent(id, row) == null, "Duplicate association scenario");
        }
        CaseInputs.require(new ArrayList<>(result.keySet()).equals(VARIANTS.stream().map(Variant::id).toList()), "Incomplete association variant coverage");
        return result;
    }
    private static List<Variant> variants() {
        var result = new ArrayList<Variant>();
        for (String train : List.of("acacia", "basil"))
            for (String name : List.of("expanded-webhook", "expanded-reconciliation", "two-subscriptions", "standalone-invoice", "quote-parent"))
                result.add(new Variant(name + "-" + train, name.startsWith("expanded-") ? "expanded-association" : name, train));
        return List.copyOf(result);
    }
    private static Set<String> inventory() {
        var result = new HashSet<>(Set.of("scenarios.json", "expectations.json"));
        for (var variant : VARIANTS) {
            for (String role : List.of("webhook", "reconciliation")) for (String id : variant.path(role))
                result.add(variant.id() + "-" + id + ".json");
            result.add("state-primary-" + variant.train() + ".json");
            result.add("state-list-" + variant.train() + "-forward.json"); result.add("state-list-" + variant.train() + "-reverse.json");
        }
        return Set.copyOf(result);
    }
}
