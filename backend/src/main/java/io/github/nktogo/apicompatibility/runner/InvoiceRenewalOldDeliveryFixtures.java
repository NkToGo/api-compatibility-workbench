package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

/** Authored old-event registration; a declared adapter is not an execution grant or verified implementation. */
final class InvoiceRenewalOldDeliveryFixtures {
    static final String BUNDLE_SHA256 = "566d590d793fcdb09e8809cd9306732b32fd677ad805803d58f022aa2179a2cb";
    private static final String ACACIA = "2025-02-24.acacia", BASIL = "2025-03-31.basil";
    private static final List<String> VARIANTS = List.of("legacy-adapter-declared", "legacy-adapter-absent");
    private static final Set<String> FILES = Set.of("scenarios.json", "expectations.json", "event-acacia.json",
            "invoice-basil.json", "state-basil.json");
    enum StepOutcome { APPENDED, DUPLICATE, BLOCKED_MISSING_ADAPTER }

    record Delivery(String id, String consumerId, JsonObject input, String evidenceIdentity,
                    Fact expected, Reason blockingReason) {
        Delivery {
            input = input.deepCopy();
            CaseInputs.require((expected == null && blockingReason == Reason.UNSUPPORTED_ADAPTER)
                    || (expected != null && blockingReason == null), "Invalid old-delivery expectation boundary");
        }
        @Override public JsonObject input() { return input.deepCopy(); }
        String inputIdentity() { return InvoiceRenewalOracle.inputIdentity(input); }
        boolean blocked() { return blockingReason != null; }
        InvoiceRenewalOracle oracle() {
            CaseInputs.require(!blocked(), "Missing legacy adapter: no consumer oracle or execution observation");
            return new InvoiceRenewalOracle(consumerId, input, expected);
        }
    }
    record Sequence(String id, List<String> deliveries, List<StepOutcome> outcomes,
                    Map<String, InvoiceRenewalLedger.Effect> finalEffects) {
        Sequence { deliveries = List.copyOf(deliveries); outcomes = List.copyOf(outcomes); finalEffects = Map.copyOf(finalEffects); }
    }
    record Scenario(String id, Map<String, Delivery> deliveries, List<Sequence> sequences) {
        Scenario { deliveries = Map.copyOf(deliveries); sequences = List.copyOf(sequences); }
        boolean requiresBlockedPathReview() { return deliveries.values().stream().anyMatch(Delivery::blocked); }
    }
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
        Set<String> familyIds() { return Set.of("old-delivery-after-upgrade"); }
        List<Scenario> scenarios() { return scenarios; }
        byte[] bytes(String name) { CaseInputs.require(files.containsKey(name), "Unregistered old-delivery file"); return files.get(name).clone(); }
        void requireFullCaseCoverage() {
            throw new IllegalStateException("Invoice coverage incomplete: combined registration and admission/execution controls remain pending");
        }
    }
    private InvoiceRenewalOldDeliveryFixtures() {}

    static Bundle load(Path root) throws IOException {
        var files = pinnedFiles(root, "old-delivery-v1", BUNDLE_SHA256, "OLD_DELIVERY_VARIANTS_ONLY", FILES);
        var inputs = object(files.get("scenarios.json"));
        fields(inputs, "formatVersion", "caseId", "policy", "origin", "installationId", "approvedBindings", "deliveryContext", "scenarios");
        header(inputs); equal(inputs, "origin", "authored-synthetic"); equal(inputs, "installationId", "install_case");
        var bindings = array(inputs, "approvedBindings"); var bindingKeys = new HashSet<String>();
        for (var value : bindings) {
            var binding = asObject(value); fields(binding, "customerId", "subscriptionId");
            String customer = text(binding, "customerId"), subscription = text(binding, "subscriptionId");
            identifier(customer); identifier(subscription);
            CaseInputs.require(bindingKeys.add(customer + ":" + subscription), "Duplicate old-delivery binding");
        }
        CaseInputs.require(bindingKeys.equals(Set.of("cus_case:sub_primary", "cus_case:sub_secondary")), "Old-delivery binding mismatch");
        var context = obj(inputs, "deliveryContext");
        // These are authored times. Neither a runtime clock nor provider receipt establishes them.
        CaseInputs.require(context.equals(JsonParser.parseString("{\"previousEndpointApiVersion\":\"2025-02-24.acacia\","
                + "\"upgradedEndpointApiVersion\":\"2025-03-31.basil\",\"declaredUpgradeAt\":1743465600,\"declaredDeliveryAt\":1743469200}")),
                "Old-delivery context changed");
        var answers = object(files.get("expectations.json"));
        fields(answers, "formatVersion", "caseId", "policy", "authority", "scenarios");
        header(answers); equal(answers, "authority", "authored-application-policy");
        var rows = indexed(array(inputs, "scenarios")); var expectations = indexed(array(answers, "scenarios"));
        var scenarios = new ArrayList<Scenario>();
        for (String scenarioId : VARIANTS) {
            boolean declared = scenarioId.equals("legacy-adapter-declared");
            var row = rows.get(scenarioId); var answer = expectations.get(scenarioId);
            fields(row, "id", "familyId", "initialEffects", "deliveries"); equal(row, "familyId", "old-delivery-after-upgrade");
            CaseInputs.require(array(row, "initialEffects").isEmpty(), "Unexpected old-delivery initial effects");
            fields(answer, "id", "blockedDeliveries", "facts", "sequences");
            var blocked = obj(answer, "blockedDeliveries");
            CaseInputs.require(blocked.keySet().equals(declared ? Set.of() : Set.of("webhook")), "Old-delivery blocked coverage mismatch");
            if (!declared) equal(blocked, "webhook", "UNSUPPORTED_ADAPTER");
            var facts = obj(answer, "facts"); fields(facts, "webhook", "reconciliation");
            var deliveries = new LinkedHashMap<String, Delivery>();
            for (var value : array(row, "deliveries")) {
                var delivery = asObject(value);
                fields(delivery, "id", "consumerId", "effectiveApiVersion", "source", "subscriptionState", "configuration");
                String id = text(delivery, "id"); CaseInputs.require(Set.of("webhook", "reconciliation").contains(id), "Unknown old-delivery path");
                boolean webhook = id.equals("webhook"), absent = webhook && !declared;
                String consumer = "invoice-" + id, version = webhook ? ACACIA : BASIL;
                equal(delivery, "consumerId", consumer); equal(delivery, "effectiveApiVersion", version);
                var config = obj(delivery, "configuration");
                fields(config, "sdkVersion", "requestApiVersion", "endpointApiVersion", "accountApiVersion", "adapterApiVersion");
                equal(config, "sdkVersion", "29.0.0"); equal(config, "requestApiVersion", BASIL);
                equal(config, "endpointApiVersion", BASIL); equal(config, "accountApiVersion", ACACIA);
                if (webhook && declared) equal(config, "adapterApiVersion", ACACIA);
                else CaseInputs.require(config.get("adapterApiVersion").isJsonNull(), "Unexpected old-delivery adapter");
                String sourceName = webhook ? "event-acacia.json" : "invoice-basil.json";
                equal(delivery, "source", sourceName); equal(delivery, "subscriptionState", "state-basil.json");
                if (webhook) {
                    var event = object(files.get(sourceName)); equal(event, "api_version", ACACIA); equal(event, "type", "invoice.paid");
                    CaseInputs.require(event.get("created").equals(new JsonPrimitive(1743462000L)), "Old event creation changed");
                }
                var input = new JsonObject(); input.addProperty("scenarioId", scenarioId); input.addProperty("familyId", "old-delivery-after-upgrade");
                input.addProperty("deliveryId", id); input.addProperty("consumerId", consumer); input.addProperty("installationId", "install_case");
                input.addProperty("effectiveApiVersion", version); input.add("configuration", config.deepCopy());
                input.add("approvedBindings", bindings.deepCopy()); input.add("deliveryContext", context.deepCopy());
                input.add("source", source(files, sourceName, webhook ? "event-snapshot" : "invoice-retrieval", version));
                input.add("subscriptionState", source(files, "state-basil.json", "subscription-retrieval", BASIL));
                String inputIdentity = InvoiceRenewalOracle.inputIdentity(input);
                var evidence = new JsonObject(); evidence.addProperty("bundleIdentity", BUNDLE_SHA256);
                evidence.addProperty("scenarioId", scenarioId); evidence.addProperty("deliveryId", id); evidence.addProperty("inputIdentity", inputIdentity);
                String evidenceIdentity = InvoiceRenewalOracle.inputIdentity(evidence); Fact expected = null;
                if (absent) CaseInputs.require(facts.get(id).isJsonNull(), "Blocked input cannot declare a consumer fact");
                else {
                    var fact = obj(facts, id).deepCopy();
                    fields(fact, "status", "invoiceId", "customerId", "subscriptionId", "effectiveApiVersion", "reason");
                    fact.addProperty("inputIdentity", inputIdentity); fact.addProperty("evidenceIdentity", evidenceIdentity);
                    expected = Fact.parse(fact);
                    CaseInputs.require(version.equals(expected.effectiveApiVersion()), "Old-delivery expected version mismatch");
                }
                CaseInputs.require(deliveries.putIfAbsent(id, new Delivery(id, consumer, input, evidenceIdentity, expected,
                        absent ? Reason.UNSUPPORTED_ADAPTER : null)) == null, "Duplicate old-delivery path");
            }
            CaseInputs.require(new ArrayList<>(deliveries.keySet()).equals(List.of("webhook", "reconciliation")), "Old-delivery path coverage mismatch");
            scenarios.add(new Scenario(scenarioId, deliveries, sequences(answer, declared)));
        }
        return new Bundle("install_case", files, scenarios);
    }
    private static Map<String, JsonObject> indexed(JsonArray rows) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var value : rows) {
            var row = asObject(value); String id = text(row, "id");
            CaseInputs.require(result.putIfAbsent(id, row) == null, "Duplicate old-delivery scenario");
        }
        CaseInputs.require(new ArrayList<>(result.keySet()).equals(VARIANTS), "Old-delivery scenario coverage mismatch"); return result;
    }
    private static List<Sequence> sequences(JsonObject answer, boolean declared) {
        var orders = Map.of("webhook-alone", List.of("webhook"), "reconciliation-alone", List.of("reconciliation"),
                "webhook-first", List.of("webhook", "reconciliation"), "reconciliation-first", List.of("reconciliation", "webhook"));
        var result = new ArrayList<Sequence>(); var names = new HashSet<String>();
        for (var value : array(answer, "sequences")) {
            var row = asObject(value); fields(row, "id", "deliveries", "outcomes", "finalEffects");
            String id = text(row, "id"); var order = strings(array(row, "deliveries"));
            CaseInputs.require(names.add(id) && order.equals(orders.get(id)), "Old-delivery arrival coverage mismatch");
            var outcomes = strings(array(row, "outcomes")).stream().map(StepOutcome::valueOf).toList();
            CaseInputs.require(outcomes.size() == order.size(), "Incomplete old-delivery outcomes");
            for (int i = 0; i < order.size(); i++) CaseInputs.require(
                    (outcomes.get(i) == StepOutcome.BLOCKED_MISSING_ADAPTER) == (!declared && order.get(i).equals("webhook")),
                    "Old-delivery blocked outcome mismatch");
            result.add(new Sequence(id, order, outcomes, effects(array(row, "finalEffects"))));
        }
        CaseInputs.require(names.equals(orders.keySet()), "Incomplete old-delivery arrival coverage"); return List.copyOf(result);
    }
}
