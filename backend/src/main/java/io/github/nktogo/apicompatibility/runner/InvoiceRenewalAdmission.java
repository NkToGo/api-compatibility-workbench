package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

/** Host-owned consistency gate. Verifier receipts are supplied by the host, never inferred from payload claims. */
final class InvoiceRenewalAdmission {
    enum Verification { VERIFIED, REJECTED, NOT_CHECKED, ABSENT, NOT_APPLICABLE }
    enum Issue {
        INCOMPLETE_INPUT_SCOPE, INCOMPLETE_FIXTURE_IDENTITY, INPUT_IDENTITY_MISMATCH, EVIDENCE_IDENTITY_MISMATCH,
        CONSUMER_BINDING_MISMATCH, MISSING_SOURCE, INVALID_SOURCE, SOURCE_DIGEST_MISMATCH,
        SOURCE_PAYLOAD_MISMATCH, SOURCE_ROLE_MISMATCH, SIGNATURE_REJECTED, SIGNATURE_NOT_VERIFIED, SIGNATURE_SCOPE_MISMATCH,
        SIGNATURE_SOURCE_MISMATCH, MISSING_EVENT_VERSION, UNKNOWN_EVENT_VERSION, REJECTED_EVENT_TYPE,
        MISSING_REQUEST_VERSION, UNKNOWN_REQUEST_VERSION, VERSION_BINDING_MISMATCH,
        ADAPTER_ABSENT, ADAPTER_REJECTED, ADAPTER_NOT_VERIFIED, ADAPTER_VERSION_MISMATCH,
        MISSING_EXPECTATION, EXPECTATION_BINDING_MISMATCH
    }
    record HostEvidence(String bundleIdentity, String inputIdentity, String evidenceIdentity, byte[] sourceBytes,
                        Verification signature, String signedSourceIdentity, Verification adapter, String adapterVersion) {
        HostEvidence {
            sourceBytes = sourceBytes == null ? null : sourceBytes.clone();
            Objects.requireNonNull(signature); Objects.requireNonNull(adapter);
        }
        @Override public byte[] sourceBytes() { return sourceBytes == null ? null : sourceBytes.clone(); }
    }
    static final class Decision {
        private final String consumer;
        private final JsonObject input;
        private final Fact expected;
        private final List<Issue> issues;
        private final JsonObject signatureEvidence;
        private Decision(String consumer, JsonObject input, Fact expected, Collection<Issue> issues) {
            this(consumer, input, expected, issues, null);
        }
        private Decision(String consumer, JsonObject input, Fact expected, Collection<Issue> issues, JsonObject signatureEvidence) {
            this.consumer = consumer; this.input = input.deepCopy(); this.expected = expected; this.issues = List.copyOf(issues);
            this.signatureEvidence = signatureEvidence == null ? null : signatureEvidence.deepCopy();
        }
        boolean admitted() { return issues.isEmpty(); }
        List<Issue> issues() { return issues; }
        String consumerId() { return consumer; }
        String effectiveApiVersion() { return text(input, "effectiveApiVersion"); }
        String sdkVersion() { return text(object(input, "configuration"), "sdkVersion"); }
        JsonObject signatureEvidence() { return signatureEvidence == null ? null : signatureEvidence.deepCopy(); }
        InvoiceRenewalOracle oracle() {
            CaseInputs.require(admitted(), "Invoice input blocked before consumer execution");
            return new InvoiceRenewalOracle(consumer, input, expected);
        }
        JsonObject json() {
            var result = new JsonObject(); result.addProperty("admitted", admitted());
            var values = new JsonArray(); issues.forEach(i -> values.add(i.name())); result.add("issues", values); return result;
        }
    }
    private InvoiceRenewalAdmission() {}

    /** Actual signature authority replaces the authored signature fields, but adapter states remain supplied controls. */
    static Decision inspectWebhook(JsonObject input, Fact expected, HostEvidence host,
                                   InvoiceWebhookSignatures.Scope scope, InvoiceWebhookSignatures.Receipt signature) {
        Objects.requireNonNull(scope); Objects.requireNonNull(host); Objects.requireNonNull(input);
        var actual = new HostEvidence(host.bundleIdentity(), host.inputIdentity(), host.evidenceIdentity(), host.sourceBytes(),
                signature == null ? Verification.NOT_CHECKED : signature.verified() ? Verification.VERIFIED : Verification.REJECTED,
                signature == null ? null : signature.sourceIdentity(), host.adapter(), host.adapterVersion());
        var checked = inspect("invoice-webhook", input, expected, actual); var issues = new LinkedHashSet<>(checked.issues());
        if (!scope.installationId().equals(text(input, "installationId")) || signature != null && !signature.matchesScope(scope))
            issues.add(Issue.SIGNATURE_SCOPE_MISMATCH);
        return new Decision("invoice-webhook", input, expected, issues, signature == null ? null : signature.json());
    }

    static Decision inspect(String consumer, JsonObject input, Fact expected, HostEvidence host) {
        CaseInputs.require(Set.of("invoice-webhook", "invoice-reconciliation").contains(consumer), "Unknown host invoice consumer");
        Objects.requireNonNull(input); Objects.requireNonNull(host);
        var issues = new LinkedHashSet<Issue>(); boolean webhook = consumer.equals("invoice-webhook");
        for (String field : List.of("scenarioId", "deliveryId", "installationId")) {
            String value = text(input, field);
            if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) issues.add(Issue.INCOMPLETE_INPUT_SCOPE);
        }
        String identity = inputIdentity(input);
        if (!digest(host.bundleIdentity()) || !digest(host.inputIdentity()) || !digest(host.evidenceIdentity()))
            issues.add(Issue.INCOMPLETE_FIXTURE_IDENTITY);
        else {
            if (!identity.equals(host.inputIdentity())) issues.add(Issue.INPUT_IDENTITY_MISMATCH);
            var evidence = new JsonObject(); evidence.addProperty("bundleIdentity", host.bundleIdentity());
            evidence.addProperty("scenarioId", text(input, "scenarioId")); evidence.addProperty("deliveryId", text(input, "deliveryId"));
            evidence.addProperty("inputIdentity", identity);
            if (!inputIdentity(evidence).equals(host.evidenceIdentity())) issues.add(Issue.EVIDENCE_IDENTITY_MISMATCH);
        }
        if (!consumer.equals(text(input, "consumerId"))) issues.add(Issue.CONSUMER_BINDING_MISMATCH);
        var source = object(input, "source"); var configuration = object(input, "configuration");
        JsonObject payload = source == null ? null : object(source, "payload");
        String actualSourceIdentity = null;
        byte[] bytes = host.sourceBytes();
        if (bytes == null || source == null) issues.add(Issue.MISSING_SOURCE);
        else if (bytes.length > 256 * 1024) issues.add(Issue.INVALID_SOURCE);
        else {
            actualSourceIdentity = CaseInputs.hash(bytes);
            if (!actualSourceIdentity.equals(text(source, "sha256"))) issues.add(Issue.SOURCE_DIGEST_MISMATCH);
            try {
                if (!InvoiceRenewalFixtures.object(bytes).equals(payload)) issues.add(Issue.SOURCE_PAYLOAD_MISMATCH);
            } catch (IllegalArgumentException e) { issues.add(Issue.INVALID_SOURCE); }
        }
        if (source != null && !(webhook ? "event-snapshot" : "invoice-retrieval").equals(text(source, "kind")))
            issues.add(Issue.SOURCE_ROLE_MISMATCH);
        String version;
        if (webhook) {
            if (host.signature() == Verification.REJECTED) issues.add(Issue.SIGNATURE_REJECTED);
            else if (host.signature() != Verification.VERIFIED) issues.add(Issue.SIGNATURE_NOT_VERIFIED);
            if (host.signature() == Verification.VERIFIED && actualSourceIdentity != null
                    && !actualSourceIdentity.equals(host.signedSourceIdentity())) issues.add(Issue.SIGNATURE_SOURCE_MISMATCH);
            version = text(payload, "api_version");
            if (version == null) issues.add(Issue.MISSING_EVENT_VERSION);
            else if (!VERSIONS.contains(version)) issues.add(Issue.UNKNOWN_EVENT_VERSION);
            if (!"invoice.paid".equals(text(payload, "type"))) issues.add(Issue.REJECTED_EVENT_TYPE);
            switch (host.adapter()) {
                case ABSENT -> issues.add(Issue.ADAPTER_ABSENT);
                case REJECTED -> issues.add(Issue.ADAPTER_REJECTED);
                case VERIFIED -> {
                    if (VERSIONS.contains(version == null ? "" : version)
                            && (!version.equals(host.adapterVersion()) || !version.equals(text(configuration, "adapterApiVersion"))))
                        issues.add(Issue.ADAPTER_VERSION_MISMATCH);
                }
                default -> issues.add(Issue.ADAPTER_NOT_VERIFIED);
            }
        } else {
            version = text(configuration, "requestApiVersion");
            if (version == null) issues.add(Issue.MISSING_REQUEST_VERSION);
            else if (!VERSIONS.contains(version)) issues.add(Issue.UNKNOWN_REQUEST_VERSION);
        }
        if (version != null && VERSIONS.contains(version)
                && (!version.equals(text(input, "effectiveApiVersion")) || !version.equals(text(source, "apiVersion"))))
            issues.add(Issue.VERSION_BINDING_MISMATCH);
        if (issues.isEmpty()) {
            if (expected == null) issues.add(Issue.MISSING_EXPECTATION);
            else if (!identity.equals(expected.inputIdentity()) || !host.evidenceIdentity().equals(expected.evidenceIdentity())
                    || !Objects.equals(version, expected.effectiveApiVersion())) issues.add(Issue.EXPECTATION_BINDING_MISMATCH);
        }
        return new Decision(consumer, input, expected, issues);
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static JsonObject object(JsonObject value, String key) {
        var item = value == null ? null : value.get(key); return item != null && item.isJsonObject() ? item.getAsJsonObject() : null;
    }
    private static String text(JsonObject value, String key) {
        var item = value == null ? null : value.get(key);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : null;
    }
}
