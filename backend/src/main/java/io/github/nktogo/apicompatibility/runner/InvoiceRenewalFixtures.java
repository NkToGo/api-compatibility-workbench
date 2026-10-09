package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Host registration of authored inputs and separately held answers; never executes consumers. */
final class InvoiceRenewalFixtures {
    static final String CASE_ID = "stripe-invoice-subscription-2025-03-31";
    static final String POLICY = "paid-renewal-v1";
    static final String BUNDLE_SHA256 = "9696c4ef9321c0dc61f41e843f29bd93082121cdba876e6085f70b984629a0d1";
    private static final int MAX_BYTES = 262144;
    private static final Set<String> FILES = Set.of("event-acacia.json", "event-basil.json",
            "invoice-acacia.json", "invoice-basil.json", "state-acacia.json", "state-basil.json",
            "scenarios.json", "expectations.json");
    private static final List<String> ROLLOUTS = List.of("legacy-agreement", "target-agreement", "request-first", "event-first");
    private static final List<String> REQUIRED_SCENARIOS = List.of("legacy-agreement", "target-agreement", "request-first",
            "event-first", "expanded-association", "two-subscriptions", "standalone-invoice", "quote-parent",
            "missing-association", "contradictory-parent", "duplicate-delivery", "conflicting-association",
            "unavailable-state", "not-paid", "old-delivery-after-upgrade");
    private static final Map<String, List<String>> ORDERS = Map.of(
            "webhook-alone", List.of("webhook"), "reconciliation-alone", List.of("reconciliation"),
            "webhook-first", List.of("webhook", "reconciliation"), "reconciliation-first", List.of("reconciliation", "webhook"));
    private InvoiceRenewalFixtures() {}

    record Delivery(String id, String consumerId, JsonObject input, InvoiceRenewalOracle.Fact expected) {
        Delivery { input = input.deepCopy(); Objects.requireNonNull(expected); }
        @Override public JsonObject input() { return input.deepCopy(); }
        InvoiceRenewalOracle oracle() { return new InvoiceRenewalOracle(consumerId, input, expected); }
    }
    record Sequence(String id, List<String> deliveries, List<InvoiceRenewalLedger.Outcome> outcomes,
                    Map<String, InvoiceRenewalLedger.Effect> finalEffects) {
        Sequence { deliveries = List.copyOf(deliveries); outcomes = List.copyOf(outcomes); finalEffects = Map.copyOf(finalEffects); }
    }
    record Scenario(String id, Map<String, InvoiceRenewalLedger.Effect> initialEffects,
                    Map<String, Delivery> deliveries, List<Sequence> sequences) {
        Scenario { initialEffects = Map.copyOf(initialEffects); deliveries = Map.copyOf(deliveries); sequences = List.copyOf(sequences); }
    }
    static final class Bundle {
        private final String installationId;
        private final Map<String, byte[]> files;
        private final List<Scenario> scenarios;
        private Bundle(String installationId, Map<String, byte[]> files, List<Scenario> scenarios) {
            this.installationId = installationId;
            var copy = new TreeMap<String, byte[]>(); files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            this.files = Collections.unmodifiableMap(copy); this.scenarios = List.copyOf(scenarios);
        }
        String installationId() { return installationId; }
        String identity() { return BUNDLE_SHA256; }
        List<Scenario> scenarios() { return scenarios; }
        byte[] bytes(String name) {
            CaseInputs.require(files.containsKey(name), "Unregistered fixture file"); return files.get(name).clone();
        }
        List<String> missingScenarioIds() { return REQUIRED_SCENARIOS.stream().filter(id -> !ROLLOUTS.contains(id)).toList(); }
        void requireFullCaseCoverage() {
            throw new IllegalStateException("Invoice fixture coverage incomplete: edge variants and admission/execution controls are not registered");
        }
    }

    static Bundle load(Path caseRoot) throws IOException {
        return registration(pinnedFiles(caseRoot, "rollout-v1", BUNDLE_SHA256, "ROLLOUT_VECTORS_ONLY", FILES));
    }

    /** Shared host byte boundary. The caller supplies the compiled pin and exact flat inventory. */
    static Map<String, byte[]> pinnedFiles(Path caseRoot, String corpus, String pin, String stage, Set<String> inventory) throws IOException {
        Path root = caseRoot.toAbsolutePath().normalize(); plainDirectories(root);
        Path fixtureRoot = root.resolve("fixtures").resolve(corpus); plainDirectories(fixtureRoot);
        byte[] manifestBytes = read(fixtureRoot.resolve("bundle.json"));
        CaseInputs.require(pin.equals(CaseInputs.hash(manifestBytes)), "Invoice fixture registration changed");
        var manifest = object(manifestBytes);
        fields(manifest, "formatVersion", "caseId", "policy", "origin", "coverageStage", "contractIdentity", "files");
        header(manifest); equal(manifest, "origin", "authored-synthetic"); equal(manifest, "coverageStage", stage);
        verifyContract(root, text(manifest, "contractIdentity"));
        var hashes = obj(manifest, "files"); CaseInputs.require(hashes.keySet().equals(inventory), "Unexpected fixture inventory");
        var names = new HashSet<>(inventory); names.add("bundle.json");
        try (var entries = Files.newDirectoryStream(fixtureRoot)) {
            for (Path file : entries) CaseInputs.require(names.remove(file.getFileName().toString()), "Unregistered fixture entry");
        }
        CaseInputs.require(names.isEmpty(), "Incomplete fixture inventory");
        var files = new TreeMap<String, byte[]>(); files.put("bundle.json", manifestBytes);
        for (String name : inventory) {
            byte[] bytes = read(fixtureRoot.resolve(name));
            CaseInputs.require(CaseInputs.hash(bytes).equals(text(hashes, name)), "Invoice fixture bytes changed");
            object(bytes); files.put(name, bytes);
        }
        return Collections.unmodifiableMap(files);
    }

    private static void verifyContract(Path root, String identity) throws IOException {
        byte[] bytes = read(root.resolve("contract-integrity.json"));
        CaseInputs.require(CaseInputs.hash(bytes).equals(identity), "Invoice contract identity changed");
        var contract = object(bytes); fields(contract, "formatVersion", "caseId", "contractRevision", "status", "files");
        version(contract); equal(contract, "caseId", CASE_ID); equal(contract, "contractRevision", POLICY); equal(contract, "status", "CONTRACT_ONLY");
        var required = new HashSet<>(Set.of("README.md", "business-oracle.md", "provenance.json"));
        for (var value : array(contract, "files")) {
            var file = asObject(value); fields(file, "path", "sha256"); String name = text(file, "path");
            CaseInputs.require(required.remove(name), "Unexpected contract file");
            CaseInputs.require(CaseInputs.hash(read(root.resolve(name))).equals(text(file, "sha256")), "Invoice contract bytes changed");
        }
        CaseInputs.require(required.isEmpty(), "Incomplete invoice contract");
    }

    private static Bundle registration(Map<String, byte[]> files) {
        var inputs = object(files.get("scenarios.json"));
        fields(inputs, "formatVersion", "caseId", "policy", "origin", "installationId", "approvedBindings", "scenarios");
        header(inputs); equal(inputs, "origin", "authored-synthetic"); String installation = text(inputs, "installationId");
        InvoiceRenewalOracle.identifier(installation);
        var bindings = array(inputs, "approvedBindings"); var bindingKeys = new HashSet<String>();
        for (var value : bindings) {
            var binding = asObject(value); fields(binding, "customerId", "subscriptionId");
            String customer = text(binding, "customerId"), subscription = text(binding, "subscriptionId");
            InvoiceRenewalOracle.identifier(customer); InvoiceRenewalOracle.identifier(subscription);
            CaseInputs.require(bindingKeys.add(customer + ":" + subscription), "Duplicate approved binding");
        }
        CaseInputs.require(!bindings.isEmpty(), "Missing approved bindings");
        var expectations = object(files.get("expectations.json"));
        fields(expectations, "formatVersion", "caseId", "policy", "authority", "scenarios");
        header(expectations); equal(expectations, "authority", "authored-application-policy");
        var inputRows = indexed(array(inputs, "scenarios"));
        var expectedRows = indexed(array(expectations, "scenarios"));
        CaseInputs.require(new ArrayList<>(inputRows.keySet()).equals(ROLLOUTS)
                && inputRows.keySet().equals(expectedRows.keySet()), "Invoice rollout coverage mismatch");
        var scenarios = new ArrayList<Scenario>(); var usedSources = new HashSet<String>();
        for (String id : ROLLOUTS) {
            var row = inputRows.get(id);
            var answers = expectedRows.get(id);
            fields(row, "id", "initialEffects", "deliveries"); fields(answers, "id", "facts", "sequences");
            var initial = effects(array(row, "initialEffects"));
            var deliveries = new LinkedHashMap<String, Delivery>(); var facts = obj(answers, "facts");
            for (var value : array(row, "deliveries")) {
                var delivery = asObject(value);
                fields(delivery, "id", "consumerId", "effectiveApiVersion", "source", "subscriptionState", "configuration");
                String name = text(delivery, "id"), consumer = text(delivery, "consumerId"), apiVersion = text(delivery, "effectiveApiVersion");
                CaseInputs.require((name.equals("webhook") && consumer.equals("invoice-webhook"))
                        || (name.equals("reconciliation") && consumer.equals("invoice-reconciliation")), "Invalid invoice delivery role");
                CaseInputs.require(InvoiceRenewalOracle.VERSIONS.contains(apiVersion), "Unregistered invoice API version");
                boolean webhook = consumer.equals("invoice-webhook");
                var configuration = obj(delivery, "configuration"); configuration(configuration, apiVersion, webhook);
                String sourceName = text(delivery, "source"), stateName = text(delivery, "subscriptionState");
                String train = apiVersion.endsWith(".acacia") ? "acacia" : "basil";
                CaseInputs.require(sourceName.equals((webhook ? "event-" : "invoice-") + train + ".json")
                        && stateName.equals("state-" + train + ".json"), "Fixture version/source mapping mismatch");
                usedSources.add(sourceName); usedSources.add(stateName);
                var source = object(files.get(sourceName));
                if (webhook) { equal(source, "api_version", apiVersion); equal(source, "type", "invoice.paid"); }
                var input = new JsonObject(); input.addProperty("scenarioId", id); input.addProperty("deliveryId", name);
                input.addProperty("consumerId", consumer); input.addProperty("installationId", installation);
                input.addProperty("effectiveApiVersion", apiVersion); input.add("configuration", configuration.deepCopy());
                input.add("approvedBindings", bindings.deepCopy());
                input.add("source", source(files, sourceName, webhook ? "event-snapshot" : "invoice-retrieval", apiVersion));
                input.add("subscriptionState", source(files, stateName, "subscription-retrieval", apiVersion));
                var evidence = new JsonObject(); evidence.addProperty("bundleIdentity", BUNDLE_SHA256);
                evidence.addProperty("scenarioId", id); evidence.addProperty("deliveryId", name);
                evidence.addProperty("inputIdentity", InvoiceRenewalOracle.inputIdentity(input));
                String evidenceId = InvoiceRenewalOracle.inputIdentity(evidence);
                var fact = obj(facts, name).deepCopy();
                fields(fact, "status", "invoiceId", "customerId", "subscriptionId", "effectiveApiVersion", "reason");
                fact.addProperty("inputIdentity", InvoiceRenewalOracle.inputIdentity(input)); fact.addProperty("evidenceIdentity", evidenceId);
                var expected = InvoiceRenewalOracle.Fact.parse(fact);
                CaseInputs.require(expected.effectiveApiVersion().equals(apiVersion), "Expected invoice version mismatch");
                CaseInputs.require(deliveries.putIfAbsent(name, new Delivery(name, consumer, input, expected)) == null, "Duplicate invoice delivery");
            }
            CaseInputs.require(deliveries.keySet().equals(Set.of("webhook", "reconciliation")) && facts.keySet().equals(deliveries.keySet()), "Incomplete path expectations");
            validateVector(id, deliveries);
            var sequences = new ArrayList<Sequence>(); var orders = new HashSet<String>();
            for (var value : array(answers, "sequences")) {
                var sequence = asObject(value); fields(sequence, "id", "deliveries", "outcomes", "finalEffects");
                String name = text(sequence, "id"); List<String> order = strings(array(sequence, "deliveries"));
                CaseInputs.require(ORDERS.containsKey(name) && orders.add(name) && ORDERS.get(name).equals(order), "Invalid invoice arrival coverage");
                var outcomes = strings(array(sequence, "outcomes")).stream().map(InvoiceRenewalFixtures::outcome).toList();
                CaseInputs.require(outcomes.size() == order.size(), "Missing ordered ledger expectations");
                sequences.add(new Sequence(name, order, outcomes, effects(array(sequence, "finalEffects"))));
            }
            CaseInputs.require(orders.equals(ORDERS.keySet()), "Incomplete invoice arrival coverage");
            scenarios.add(new Scenario(id, initial, deliveries, sequences));
        }
        CaseInputs.require(usedSources.equals(Set.of("event-acacia.json", "event-basil.json", "invoice-acacia.json",
                "invoice-basil.json", "state-acacia.json", "state-basil.json")), "Unreferenced invoice fixture");
        return new Bundle(installation, files, scenarios);
    }

    private static void validateVector(String id, Map<String, Delivery> deliveries) {
        boolean webhookLegacy = Set.of("legacy-agreement", "request-first").contains(id);
        boolean requestLegacy = Set.of("legacy-agreement", "event-first").contains(id);
        CaseInputs.require(deliveries.get("webhook").expected().effectiveApiVersion().endsWith(webhookLegacy ? ".acacia" : ".basil")
                && deliveries.get("reconciliation").expected().effectiveApiVersion().endsWith(requestLegacy ? ".acacia" : ".basil"), "Invalid rollout version vector");
    }
    static void configuration(JsonObject value, String version, boolean webhook) {
        fields(value, "sdkVersion", "requestApiVersion", "endpointApiVersion", "accountApiVersion", "adapterApiVersion");
        equal(value, "sdkVersion", version.endsWith(".acacia") ? "28.4.0" : "29.0.0"); equal(value, "requestApiVersion", version);
        CaseInputs.require(InvoiceRenewalOracle.VERSIONS.contains(text(value, "endpointApiVersion"))
                && InvoiceRenewalOracle.VERSIONS.contains(text(value, "accountApiVersion")), "Unregistered configuration version");
        if (webhook) equal(value, "adapterApiVersion", version);
        else CaseInputs.require(value.get("adapterApiVersion").isJsonNull(), "Unexpected request adapter");
    }
    static JsonObject source(Map<String, byte[]> files, String name, String kind, String version) {
        var value = new JsonObject(); value.addProperty("file", name); value.addProperty("sha256", CaseInputs.hash(files.get(name)));
        value.addProperty("kind", kind); value.addProperty("apiVersion", version); value.add("payload", object(files.get(name))); return value;
    }
    static Map<String, InvoiceRenewalLedger.Effect> effects(JsonArray values) {
        var result = new LinkedHashMap<String, InvoiceRenewalLedger.Effect>();
        for (var value : values) {
            var effect = asObject(value); fields(effect, "invoiceId", "customerId", "subscriptionId", "action");
            String invoice = text(effect, "invoiceId"); InvoiceRenewalOracle.identifier(invoice); equal(effect, "action", "RECORD_PAID_RENEWAL");
            CaseInputs.require(result.putIfAbsent(invoice, new InvoiceRenewalLedger.Effect(text(effect, "customerId"),
                    text(effect, "subscriptionId"), InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL)) == null, "Duplicate invoice effect");
        }
        return result;
    }
    static InvoiceRenewalLedger.Outcome outcome(String value) {
        CaseInputs.require(Arrays.stream(InvoiceRenewalLedger.Outcome.values()).anyMatch(v -> v.name().equals(value)), "Invalid ledger outcome");
        return InvoiceRenewalLedger.Outcome.valueOf(value);
    }
    private static Map<String, JsonObject> indexed(JsonArray rows) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var value : rows) {
            var row = asObject(value); String id = text(row, "id");
            CaseInputs.require(REQUIRED_SCENARIOS.contains(id) && result.putIfAbsent(id, row) == null, "Invalid invoice scenario identity");
        }
        return result;
    }

    private static void plainDirectories(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            CaseInputs.require(attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther(), "Linked or invalid fixture directory");
        }
    }
    private static byte[] read(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        CaseInputs.require(attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther()
                && attributes.size() <= MAX_BYTES, "Linked or oversized fixture file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            CaseInputs.require(bytes.length <= MAX_BYTES, "Fixture byte limit exceeded"); return bytes;
        }
    }
    static JsonObject object(byte[] bytes) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(json))) {
                reader.setStrictness(Strictness.STRICT); validate(reader, 0);
                CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing fixture input");
            }
            return asObject(JsonParser.parseString(json));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid invoice fixture JSON");
        }
    }
    private static void validate(JsonReader reader, int depth) throws IOException {
        CaseInputs.require(depth <= 24, "Fixture nesting limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var names = new HashSet<String>();
                while (reader.hasNext()) { CaseInputs.require(names.size() < 256 && names.add(reader.nextName()), "Duplicate fixture field"); validate(reader, depth + 1); }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); int count = 0;
                while (reader.hasNext()) { CaseInputs.require(count++ < 256, "Fixture array limit"); validate(reader, depth + 1); }
                reader.endArray();
            }
            case STRING, NUMBER, BOOLEAN, NULL -> reader.skipValue();
            default -> throw new IllegalArgumentException("Invalid fixture token");
        }
    }
    static void header(JsonObject value) { version(value); equal(value, "caseId", CASE_ID); equal(value, "policy", POLICY); }
    private static void version(JsonObject value) {
        var version = value.get("formatVersion");
        CaseInputs.require(version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
                && version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0, "Invalid fixture format");
    }
    static void fields(JsonObject value, String... keys) { CaseInputs.require(value.keySet().equals(Set.of(keys)), "Invalid fixture fields"); }
    static void equal(JsonObject value, String key, String expected) { CaseInputs.require(expected.equals(text(value, key)), "Invalid fixture metadata"); }
    static String text(JsonObject value, String key) {
        var field = value.get(key); CaseInputs.require(field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString(), "Invalid fixture string"); return field.getAsString();
    }
    static JsonObject asObject(JsonElement value) { CaseInputs.require(value != null && value.isJsonObject(), "Fixture object required"); return value.getAsJsonObject(); }
    static JsonObject obj(JsonObject value, String key) { return asObject(value.get(key)); }
    static JsonArray array(JsonObject value, String key) { var field = value.get(key); CaseInputs.require(field != null && field.isJsonArray(), "Fixture array required"); return field.getAsJsonArray(); }
    static List<String> strings(JsonArray values) {
        var result = new ArrayList<String>();
        for (var value : values) { CaseInputs.require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Fixture string required"); result.add(value.getAsString()); }
        return result;
    }
}
