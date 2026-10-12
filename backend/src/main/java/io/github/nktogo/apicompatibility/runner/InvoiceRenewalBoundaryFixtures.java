package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;

/** Pinned authored host-receipt conditions and result controls, not authenticated provider receipts or executions. */
final class InvoiceRenewalBoundaryFixtures {
    static final String BUNDLE_SHA256 = "6b125b99a1fbccd13364022bd87d4471947b94d96265f7941e03489596db85eb";
    private static final String BASIL = "2025-03-31.basil";
    private static final Set<String> SOURCES = Set.of("event-basil.json", "event-missing.json", "event-null.json",
            "event-unknown.json", "event-wrong-type.json", "invoice-basil.json", "state-basil.json");
    static final List<String> CONTROL_IDS = List.of("admit-webhook", "admit-reconciliation", "event-version-missing",
            "event-version-null", "event-version-unknown", "event-type-rejected", "request-version-missing", "request-version-unknown",
            "signature-rejected", "signature-unchecked", "signature-rebound", "adapter-absent", "adapter-rejected",
            "adapter-not-checked", "adapter-version-mismatch", "source-missing", "source-digest-mismatch", "source-role-spoof",
            "fixture-missing-bundle", "fixture-missing-input", "fixture-missing-evidence", "fixture-stale-input", "fixture-stale-evidence",
            "claimed-consumer-spoof", "forged-payload-permissions", "missing-expectation", "completed-missing-result",
            "returned-timed-out", "returned-cancelled", "returned-infrastructure-error", "malformed-return", "trailing-return",
            "unbound-return", "wrong-version-return");
    enum ReturnMode { NO_DISPATCH, CORRECT, MISSING, MALFORMED, TRAILING, UNBOUND, WRONG_VERSION }
    record Expected(boolean admitted, List<Issue> issues, Assertion assertion, boolean actualPresent,
                    boolean protocolInvalid, InvoiceRenewalLedger.Outcome outcomeAlone) {
        Expected { issues = List.copyOf(issues); }
    }
    record Control(String id, String consumer, JsonObject input, HostEvidence host, Fact expectedFact,
                   String executionStatus, ReturnMode returnMode, Expected expected) {
        Control { input = input.deepCopy(); }
        @Override public JsonObject input() { return input.deepCopy(); }
        Decision admission() { return InvoiceRenewalAdmission.inspect(consumer, input, expectedFact, host); }
    }
    static final class Bundle {
        private final Map<String, byte[]> files;
        private final List<Control> controls;
        private Bundle(Map<String, byte[]> files, List<Control> controls) {
            var copy = new TreeMap<String, byte[]>(); files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            this.files = Collections.unmodifiableMap(copy); this.controls = List.copyOf(controls);
        }
        String identity() { return BUNDLE_SHA256; }
        List<Control> controls() { return controls; }
        Set<String> controlIds() { return Set.copyOf(CONTROL_IDS); }
        byte[] bytes(String name) { CaseInputs.require(files.containsKey(name), "Unregistered invoice boundary file"); return files.get(name).clone(); }
        void requireFullCaseCoverage() {
            throw new IllegalStateException("Combined invoice variant/control registration and full-case coverage remain pending");
        }
    }
    private InvoiceRenewalBoundaryFixtures() {}
    static Bundle load(Path root) throws IOException {
        var inventory = new HashSet<>(SOURCES); inventory.addAll(Set.of("controls.json", "expectations.json"));
        var files = pinnedFiles(root, "boundary-v1", BUNDLE_SHA256, "BOUNDARY_CONTROLS_ONLY", Set.copyOf(inventory));
        var plan = object(files.get("controls.json"));
        fields(plan, "formatVersion", "caseId", "policy", "origin", "hostReceiptAuthority", "installationId", "controls");
        header(plan); equal(plan, "origin", "authored-synthetic"); equal(plan, "hostReceiptAuthority", "authored-control-condition");
        equal(plan, "installationId", "install_case");
        var answers = object(files.get("expectations.json")); fields(answers, "formatVersion", "caseId", "policy", "authority", "controls");
        header(answers); equal(answers, "authority", "authored-application-policy");
        var rows = indexed(array(plan, "controls")); var expectedRows = indexed(array(answers, "controls")); var result = new ArrayList<Control>();
        for (String id : CONTROL_IDS) {
            var row = rows.get(id); var answer = expectedRows.get(id);
            fields(row, "id", "consumerId", "source", "effectiveApiVersion", "requestApiVersion", "signature", "adapter", "adapterApiVersion",
                    "sourceDigestCondition", "fixtureCondition", "claimedConsumerId", "sourceKind", "signedSourceCondition", "sourceAvailable", "executionStatus", "resultMode");
            fields(answer, "id", "admitted", "issues", "expectedFact", "assertion", "actualPresent", "protocolInvalid", "outcomeAlone");
            boolean webhook = !(id.equals("admit-reconciliation") || id.startsWith("request-version-"));
            String consumer = webhook ? "invoice-webhook" : "invoice-reconciliation"; equal(row, "consumerId", consumer);
            String sourceName = text(row, "source"); CaseInputs.require(SOURCES.contains(sourceName) && !sourceName.equals("state-basil.json"), "Unregistered boundary source");
            byte[] sourceBytes = files.get(sourceName); String sourceIdentity = CaseInputs.hash(sourceBytes);
            var payload = object(sourceBytes); String effective = nullable(row, "effectiveApiVersion");
            var input = new JsonObject(); input.addProperty("scenarioId", "invoice-boundary-controls"); input.addProperty("deliveryId", id);
            input.addProperty("consumerId", text(row, "claimedConsumerId")); input.addProperty("installationId", "install_case");
            input.addProperty("effectiveApiVersion", effective);
            var config = new JsonObject(); config.addProperty("sdkVersion", "29.0.0"); config.addProperty("requestApiVersion", nullable(row, "requestApiVersion"));
            config.addProperty("endpointApiVersion", BASIL); config.addProperty("accountApiVersion", "2025-02-24.acacia");
            config.addProperty("adapterApiVersion", nullable(row, "adapterApiVersion")); input.add("configuration", config);
            var bindings = new JsonArray(); var binding = new JsonObject(); binding.addProperty("customerId", "cus_case"); binding.addProperty("subscriptionId", "sub_primary");
            bindings.add(binding); input.add("approvedBindings", bindings);
            var source = source(files, sourceName, text(row, "sourceKind"), webhook ? nullable(payload, "api_version") : BASIL);
            String sourceCondition = choice(row, "sourceDigestCondition", Set.of("MATCHING", "MISMATCHED"));
            if (sourceCondition.equals("MISMATCHED")) source.addProperty("sha256", "0".repeat(64)); input.add("source", source);
            input.add("subscriptionState", source(files, "state-basil.json", "subscription-retrieval", BASIL));
            if (id.equals("forged-payload-permissions")) input.add("untrustedClaims", JsonParser.parseString(
                    "{\"signatureVerified\":true,\"adapterVerified\":true,\"permission\":\"execute\",\"assertionOutcome\":\"PASSED\"}"));
            String inputId = inputIdentity(input);
            var evidence = new JsonObject(); evidence.addProperty("bundleIdentity", BUNDLE_SHA256);
            evidence.addProperty("scenarioId", "invoice-boundary-controls"); evidence.addProperty("deliveryId", id); evidence.addProperty("inputIdentity", inputId);
            String evidenceId = inputIdentity(evidence), bundleId = BUNDLE_SHA256, registeredInput = inputId, registeredEvidence = evidenceId;
            switch (choice(row, "fixtureCondition", Set.of("COMPLETE", "MISSING_BUNDLE", "MISSING_INPUT", "MISSING_EVIDENCE", "STALE_INPUT", "STALE_EVIDENCE"))) {
                case "MISSING_BUNDLE" -> bundleId = null;
                case "MISSING_INPUT" -> registeredInput = null;
                case "MISSING_EVIDENCE" -> registeredEvidence = null;
                case "STALE_INPUT" -> registeredInput = "0".repeat(64);
                case "STALE_EVIDENCE" -> registeredEvidence = "0".repeat(64);
                default -> { }
            }
            var host = new HostEvidence(bundleId, registeredInput, registeredEvidence, bool(row, "sourceAvailable") ? sourceBytes : null,
                    verification(text(row, "signature")), choice(row, "signedSourceCondition", Set.of("MATCHING", "MISMATCHED")).equals("MATCHING") ? sourceIdentity : "0".repeat(64),
                    verification(text(row, "adapter")), nullable(row, "adapterApiVersion"));
            Fact expectedFact = null;
            if (!answer.get("expectedFact").isJsonNull()) {
                var fact = obj(answer, "expectedFact").deepCopy(); fields(fact, "status", "invoiceId", "customerId", "subscriptionId", "effectiveApiVersion", "reason");
                fact.addProperty("inputIdentity", inputId); fact.addProperty("evidenceIdentity", evidenceId); expectedFact = Fact.parse(fact);
            }
            var issues = strings(array(answer, "issues")).stream().map(InvoiceRenewalBoundaryFixtures::issue).toList();
            boolean admitted = bool(answer, "admitted"); String execution = nullable(row, "executionStatus");
            String assertion = nullable(answer, "assertion"), outcome = nullable(answer, "outcomeAlone");
            String mode = choice(row, "resultMode", Arrays.stream(ReturnMode.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
            CaseInputs.require(admitted ? issues.isEmpty() && !mode.equals("NO_DISPATCH") && Set.of("COMPLETED", "TIMED_OUT", "CANCELLED", "INFRASTRUCTURE_ERROR").contains(execution == null ? "" : execution)
                    : !issues.isEmpty() && mode.equals("NO_DISPATCH") && execution == null && expectedFact == null && assertion == null && outcome == null
                    && !bool(answer, "actualPresent") && !bool(answer, "protocolInvalid"), "Invalid boundary execution expectation");
            var expected = new Expected(admitted, issues, assertion == null ? null : assertion(assertion), bool(answer, "actualPresent"),
                    bool(answer, "protocolInvalid"), outcome == null ? null : outcome(outcome));
            result.add(new Control(id, consumer, input, host, expectedFact, execution, ReturnMode.valueOf(mode), expected));
        }
        return new Bundle(files, result);
    }
    private static Map<String, JsonObject> indexed(JsonArray rows) {
        var result = new LinkedHashMap<String, JsonObject>();
        for (var value : rows) { var row = asObject(value); CaseInputs.require(result.putIfAbsent(text(row, "id"), row) == null, "Duplicate boundary control"); }
        CaseInputs.require(new ArrayList<>(result.keySet()).equals(CONTROL_IDS), "Incomplete boundary control coverage"); return result;
    }
    private static boolean bool(JsonObject value, String key) {
        var field = value.get(key); CaseInputs.require(field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isBoolean(), "Boundary boolean required"); return field.getAsBoolean();
    }
    private static String nullable(JsonObject value, String key) { return !value.has(key) || value.get(key).isJsonNull() ? null : text(value, key); }
    private static String choice(JsonObject value, String key, Set<String> choices) { String result = text(value, key); CaseInputs.require(choices.contains(result), "Unknown boundary condition"); return result; }
    private static Verification verification(String value) { CaseInputs.require(Arrays.stream(Verification.values()).anyMatch(v -> v.name().equals(value)), "Unknown host verification state"); return Verification.valueOf(value); }
    private static Issue issue(String value) { CaseInputs.require(Arrays.stream(Issue.values()).anyMatch(v -> v.name().equals(value)), "Unknown admission issue"); return Issue.valueOf(value); }
    private static Assertion assertion(String value) { CaseInputs.require(Arrays.stream(Assertion.values()).anyMatch(v -> v.name().equals(value)), "Unknown boundary assertion"); return Assertion.valueOf(value); }
}
