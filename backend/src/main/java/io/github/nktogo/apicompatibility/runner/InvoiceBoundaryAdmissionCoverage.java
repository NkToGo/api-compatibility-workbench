package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;

/** Actual verifier/admission controls. No business consumer, oracle or ledger is dispatched here. */
final class InvoiceBoundaryAdmissionCoverage {
    enum Outcome { VERIFIED, FAILED, NOT_EVALUATED, CANCELLED, NOT_ATTEMPTED }
    enum SignatureAction { AUTHENTICATE, WRONG_SIGNATURE, OMIT, REBIND }
    enum AdapterAction { VERIFY, OMIT, SDK_SCHEMA_REFUSAL }
    record Expectation(boolean admitted, List<Issue> issues) {
        Expectation { issues = List.copyOf(issues); }
        JsonObject json() {
            var json = new JsonObject(); json.addProperty("admitted", admitted);
            var values = new JsonArray(); issues.forEach(i -> values.add(i.name())); json.add("issues", values); return json;
        }
    }
    static final class Entry {
        private final InvoiceRenewalBoundaryFixtures.Control original;
        private final JsonObject input;
        private final HostEvidence host;
        private final Expectation expected;
        private final SignatureAction signatureAction;
        private final AdapterAction adapterAction;
        private Entry(InvoiceRenewalBoundaryFixtures.Control original, Expectation expected) {
            this.original = original; this.expected = expected; input = original.input();
            signatureAction = switch (original.id()) {
                case "signature-rejected", "forged-payload-permissions" -> SignatureAction.WRONG_SIGNATURE;
                case "signature-unchecked" -> SignatureAction.OMIT;
                case "signature-rebound" -> SignatureAction.REBIND;
                default -> SignatureAction.AUTHENTICATE;
            };
            adapterAction = switch (original.id()) {
                case "adapter-not-checked" -> AdapterAction.OMIT;
                case "adapter-rejected" -> AdapterAction.SDK_SCHEMA_REFUSAL;
                default -> AdapterAction.VERIFY;
            };
            var captured = original.host(); byte[] bytes = captured.sourceBytes();
            String inputIdentity = captured.inputIdentity(), evidenceIdentity = captured.evidenceIdentity();
            if (adapterAction == AdapterAction.SDK_SCHEMA_REFUSAL) {
                // The pinned control only declared REJECTED. Derive and identify a real SDK type error instead.
                var payload = InvoiceRenewalFixtures.object(Objects.requireNonNull(bytes));
                var wrongType = new JsonObject(); wrongType.addProperty("invalid", "number-shape");
                payload.getAsJsonObject("data").getAsJsonObject("object").add("amount_due", wrongType);
                bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                input.getAsJsonObject("source").add("payload", payload);
                input.getAsJsonObject("source").addProperty("sha256", CaseInputs.hash(bytes));
                inputIdentity = InvoiceRenewalOracle.inputIdentity(input);
                var binding = new JsonObject(); binding.addProperty("bundleIdentity", captured.bundleIdentity());
                binding.addProperty("scenarioId", input.get("scenarioId").getAsString());
                binding.addProperty("deliveryId", original.id()); binding.addProperty("inputIdentity", inputIdentity);
                evidenceIdentity = InvoiceRenewalOracle.inputIdentity(binding);
            }
            // Authored verifier flags are deliberately discarded, including the historical signed-source hash.
            host = new HostEvidence(captured.bundleIdentity(), inputIdentity, evidenceIdentity, bytes,
                    Verification.NOT_CHECKED, null, Verification.NOT_CHECKED, null);
        }
        String id() { return original.id(); }
        String consumer() { return original.consumer(); }
        boolean webhook() { return consumer().equals("invoice-webhook"); }
        Expectation expected() { return expected; }
        JsonObject input() { return input.deepCopy(); }
        HostEvidence host() { return host; }
        JsonObject metadata() {
            var row = new JsonObject(); row.addProperty("controlId", id()); row.addProperty("consumerId", consumer());
            row.addProperty("originalInputIdentity", InvoiceRenewalOracle.inputIdentity(original.input()));
            row.addProperty("runtimeInputIdentity", InvoiceRenewalOracle.inputIdentity(input));
            row.addProperty("registeredInputIdentity", host.inputIdentity()); row.addProperty("evidenceIdentity", host.evidenceIdentity());
            row.addProperty("sourceIdentity", host.sourceBytes() == null ? null : CaseInputs.hash(host.sourceBytes()));
            row.addProperty("signatureAction", webhook() ? signatureAction.name() : "NOT_APPLICABLE");
            row.addProperty("adapterAction", webhook() ? adapterAction.name() : "NOT_APPLICABLE");
            row.addProperty("derivedSdkSchemaFault", adapterAction == AdapterAction.SDK_SCHEMA_REFUSAL);
            row.add("expectedAdmission", expected.json()); return row;
        }
    }
    static final class Plan {
        private final InvoiceRenewalRegistry.Registration fixtures;
        private final InvoiceConsumerBuilds.Registration builds;
        private final List<Entry> entries;
        private final List<String> pending;
        private Plan(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds) {
            this.fixtures = fixtures; this.builds = builds; var expectations = expectations(); var selected = new ArrayList<Entry>();
            var pending = new ArrayList<String>();
            for (var control : fixtures.boundary().controls()) {
                var expected = expectations.remove(control.id());
                if (expected == null) pending.add(control.id()); else selected.add(new Entry(control, expected));
            }
            CaseInputs.require(expectations.isEmpty() && selected.size() == 26 && pending.size() == 8, "Incomplete boundary admission plan");
            CaseInputs.require(selected.stream().filter(e -> e.expected.admitted()).count() == 2, "Incomplete healthy admission controls");
            entries = List.copyOf(selected); this.pending = List.copyOf(pending);
        }
        List<Entry> entries() { return entries; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("formatVersion", 1); json.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            json.addProperty("authority", "synthetic-runtime-admission-controls"); json.addProperty("coverageStage", "BOUNDARY_ADMISSION_ONLY");
            json.addProperty("registryIdentity", fixtures.identity()); json.addProperty("boundaryCorpusIdentity", fixtures.boundary().identity());
            json.addProperty("buildRegistrationIdentity", builds.identity());
            var rows = new JsonArray(); entries.forEach(e -> rows.add(e.metadata())); json.add("controls", rows);
            var remaining = new JsonArray(); pending.forEach(remaining::add); json.add("pendingExecutionControls", remaining); return json;
        }
    }
    static Plan load(Path caseRoot) throws IOException {
        var fixtures = InvoiceRenewalRegistry.load(caseRoot); fixtures.requireFullCaseCoverage();
        return new Plan(fixtures, InvoiceConsumerBuilds.load(caseRoot));
    }
    static final class ControlResult {
        private final Outcome outcome;
        private final Decision decision;
        private final JsonObject evidence;
        private ControlResult(Outcome outcome, Decision decision, JsonObject evidence) {
            this.outcome = outcome; this.decision = decision; this.evidence = evidence.deepCopy();
            this.evidence.addProperty("outcome", outcome.name());
        }
        Outcome outcome() { return outcome; }
        Decision decision() { return decision; }
        JsonObject json() { return evidence.deepCopy(); }
    }
    static final class Run {
        private final List<ControlResult> controls;
        private final JsonObject report;
        private Run(Plan plan, List<ControlResult> controls, JsonObject metadata) {
            this.controls = List.copyOf(controls); report = metadata.deepCopy(); report.remove("controls");
            var counts = new JsonObject(); for (var outcome : Outcome.values()) counts.addProperty(outcome.name(), controls.stream().filter(c -> c.outcome == outcome).count());
            report.add("outcomes", counts); var rows = new JsonArray(); controls.forEach(c -> rows.add(c.json())); report.add("controls", rows);
            report.addProperty("admissionCoverageSatisfied", admissionCoverageSatisfied());
            report.addProperty("rejectionControlsVerified", plan.entries.stream().filter(e -> !e.expected.admitted()).filter(e -> controls.get(plan.entries.indexOf(e)).outcome == Outcome.VERIFIED).count());
            report.addProperty("businessConsumerDispatches", 0); report.addProperty("ledgerReceipts", 0);
            report.addProperty("boundaryControlsVerified", 0); report.addProperty("boundaryPlacementsVerified", 0);
            report.addProperty("fullCaseCoverageSatisfied", false);
        }
        List<ControlResult> controls() { return controls; }
        JsonObject json() { return report.deepCopy(); }
        boolean admissionCoverageSatisfied() { return controls.size() == 26 && controls.stream().allMatch(c -> c.outcome == Outcome.VERIFIED); }
        void requireFullCaseCoverage() { throw new IllegalStateException("Boundary consumer execution and ordered placements remain pending"); }
    }
    private InvoiceBoundaryAdmissionCoverage() {}
    static Run run(Plan plan, String image, Path directory, Duration deadline, BooleanSupplier stopped) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(stopped);
        RepairSandbox.command(image, name(), InvoiceEventAdapterWorker.class.getName());
        CaseInputs.require(deadline.toMillis() > 0 && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid boundary admission deadline");
        Path output = directory.toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
        var metadata = plan.metadata(); metadata.addProperty("imageId", image); metadata.addProperty("deadlineMillisPerAdapter", deadline.toMillis());
        metadata.addProperty("coverageHarnessIdentity", harnessIdentity()); InvoiceBusinessCoverage.write(output.resolve("plan.json"), metadata);
        var results = new ArrayList<ControlResult>(); boolean aborted = false;
        try (var signer = new FixtureSigner()) {
            for (var entry : plan.entries) {
                var result = aborted || stopped.getAsBoolean() ? retained(entry, Outcome.NOT_ATTEMPTED, null, entry.metadata())
                        : execute(plan, entry, signer, image, deadline, stopped);
                results.add(result); InvoiceBusinessCoverage.write(output.resolve(String.format(Locale.ROOT, "%03d.json", results.size())), result.json());
                aborted |= result.outcome == Outcome.CANCELLED || result.evidence.has("stageFailure");
            }
        }
        var result = new Run(plan, results, metadata); InvoiceBusinessCoverage.write(output.resolve("report.json"), result.json()); return result;
    }
    private static ControlResult execute(Plan plan, Entry entry, FixtureSigner signer, String image, Duration deadline, BooleanSupplier stopped) {
        var row = entry.metadata(); row.addProperty("phase", "AUTHENTICATION");
        try {
            Decision decision; boolean incomplete = false;
            if (entry.webhook()) {
                var scope = new InvoiceWebhookSignatures.Scope(entry.input.get("installationId").getAsString(), "boundary_fixture_endpoint", "ephemeral_fixture_keys");
                var signature = signer.authenticate(scope, entry.host.sourceBytes(), entry.signatureAction);
                row.add("signatureVerification", signature == null ? JsonNull.INSTANCE : signature.json());
                if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, null, row);
                row.addProperty("phase", "ADAPTER"); InvoiceWebhookAdapters.Receipt adapter = null;
                // A different source's valid signature cannot authorize even the fixed parser for these bytes.
                byte[] bytes = entry.host.sourceBytes();
                if (entry.adapterAction != AdapterAction.OMIT && bytes != null && signature != null && signature.verified()
                        && CaseInputs.hash(bytes).equals(signature.sourceIdentity())) {
                    var configuration = entry.input.getAsJsonObject("configuration"); var selected = configuration.get("adapterApiVersion");
                    adapter = InvoiceWebhookAdapters.verify(plan.builds, bytes, configuration.get("sdkVersion").getAsString(),
                            selected.isJsonNull() ? null : selected.getAsString(), scope, signature, image, name(), deadline, stopped);
                    incomplete = adapter.outcome() == InvoiceWebhookAdapters.Outcome.NOT_EVALUATED;
                    if (adapter.json().has("executionStatus") && adapter.json().get("executionStatus").getAsString().equals("INFRASTRUCTURE_ERROR"))
                        row.addProperty("stageFailure", "ADAPTER_INFRASTRUCTURE_ERROR");
                }
                row.add("adapterVerification", adapter == null ? JsonNull.INSTANCE : adapter.json());
                decision = inspectVerifiedWebhook(entry.input(), entry.original.expectedFact(), entry.host, scope, signature, adapter);
            } else decision = inspect(entry.consumer(), entry.input(), entry.original.expectedFact(), entry.host);
            row.addProperty("phase", "ADMISSION"); row.add("admission", decision.json());
            if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, decision, row);
            if (incomplete) return retained(entry, Outcome.NOT_EVALUATED, decision, row);
            boolean matches = decision.admitted() == entry.expected.admitted() && decision.issues().equals(entry.expected.issues())
                    && (!decision.admitted() || !entry.webhook() || decision.observedWebhookVerified());
            row.addProperty("phase", "FINISHED"); return retained(entry, matches ? Outcome.VERIFIED : Outcome.FAILED, decision, row);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            row.addProperty("stageFailure", "HOST_STAGE_ERROR"); return retained(entry, Outcome.NOT_EVALUATED, null, row);
        }
    }
    private static ControlResult retained(Entry entry, Outcome outcome, Decision decision, JsonObject row) {
        for (String field : List.of("signatureVerification", "adapterVerification", "admission")) if (!row.has(field)) row.add(field, JsonNull.INSTANCE);
        if (!row.has("phase")) row.addProperty("phase", "NOT_STARTED");
        row.addProperty("controlId", entry.id()); row.addProperty("businessConsumerDispatched", false); row.add("consumerObservation", JsonNull.INSTANCE);
        return new ControlResult(outcome, decision, row);
    }
    /** Independent runtime expectations; never calculate answers by calling the gate under test. */
    private static Map<String, Expectation> expectations() {
        var result = new LinkedHashMap<String, Expectation>();
        expect(result, "admit-webhook"); expect(result, "admit-reconciliation");
        expect(result, "event-version-missing", Issue.MISSING_EVENT_VERSION, Issue.ADAPTER_REJECTED);
        expect(result, "event-version-null", Issue.MISSING_EVENT_VERSION, Issue.ADAPTER_REJECTED);
        expect(result, "event-version-unknown", Issue.UNKNOWN_EVENT_VERSION, Issue.ADAPTER_REJECTED);
        expect(result, "event-type-rejected", Issue.REJECTED_EVENT_TYPE, Issue.ADAPTER_REJECTED);
        expect(result, "request-version-missing", Issue.MISSING_REQUEST_VERSION); expect(result, "request-version-unknown", Issue.UNKNOWN_REQUEST_VERSION);
        expect(result, "signature-rejected", Issue.SIGNATURE_REJECTED, Issue.ADAPTER_NOT_VERIFIED);
        expect(result, "signature-unchecked", Issue.SIGNATURE_NOT_VERIFIED, Issue.ADAPTER_NOT_VERIFIED);
        expect(result, "signature-rebound", Issue.SIGNATURE_SOURCE_MISMATCH, Issue.ADAPTER_NOT_VERIFIED);
        expect(result, "adapter-absent", Issue.ADAPTER_ABSENT); expect(result, "adapter-rejected", Issue.ADAPTER_REJECTED);
        expect(result, "adapter-not-checked", Issue.ADAPTER_NOT_VERIFIED); expect(result, "adapter-version-mismatch", Issue.ADAPTER_REJECTED);
        expect(result, "source-missing", Issue.MISSING_SOURCE, Issue.SIGNATURE_REJECTED, Issue.ADAPTER_NOT_VERIFIED);
        expect(result, "source-digest-mismatch", Issue.SOURCE_DIGEST_MISMATCH); expect(result, "source-role-spoof", Issue.SOURCE_ROLE_MISMATCH);
        for (String id : List.of("fixture-missing-bundle", "fixture-missing-input", "fixture-missing-evidence")) expect(result, id, Issue.INCOMPLETE_FIXTURE_IDENTITY);
        expect(result, "fixture-stale-input", Issue.INPUT_IDENTITY_MISMATCH); expect(result, "fixture-stale-evidence", Issue.EVIDENCE_IDENTITY_MISMATCH);
        expect(result, "claimed-consumer-spoof", Issue.CONSUMER_BINDING_MISMATCH);
        expect(result, "forged-payload-permissions", Issue.SIGNATURE_REJECTED, Issue.ADAPTER_NOT_VERIFIED);
        expect(result, "missing-expectation", Issue.MISSING_EXPECTATION); return result;
    }
    private static void expect(Map<String, Expectation> values, String id, Issue... issues) { values.put(id, new Expectation(issues.length == 0, List.of(issues))); }
    private static String name() { return "acw-" + UUID.randomUUID(); }
    private static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>(); inventory.put("protectedExecution", InvoiceSandbox.harnessIdentity());
        for (Class<?> type : List.of(InvoiceBoundaryAdmissionCoverage.class, InvoiceBoundaryAdmissionCoverageMain.class, InvoiceBusinessCoverage.class, InvoiceRenewalRegistry.class, InvoiceRenewalBoundaryFixtures.class))
            InvoiceSandbox.captureClass(type, inventory);
        return CaseInputs.identity(inventory);
    }
    /** Random synthetic fixture key, never persisted and never obtained from application configuration. */
    private static final class FixtureSigner implements AutoCloseable {
        private final byte[] secret = new byte[32];
        private FixtureSigner() { new SecureRandom().nextBytes(secret); }
        InvoiceWebhookSignatures.Receipt authenticate(InvoiceWebhookSignatures.Scope scope, byte[] bytes, SignatureAction action) throws Exception {
            if (action == SignatureAction.OMIT) return null;
            byte[] source = bytes;
            if (action == SignatureAction.REBIND) { source = Arrays.copyOf(Objects.requireNonNull(bytes), bytes.length + 1); source[bytes.length] = ' '; }
            long time = Instant.now().getEpochSecond(); var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((time + ".").getBytes(StandardCharsets.US_ASCII)); byte[] signature = mac.doFinal(source == null ? new byte[0] : source);
            if (action == SignatureAction.WRONG_SIGNATURE) signature[0] ^= 1;
            String header = "t=" + time + ",v1=" + HexFormat.of().formatHex(signature); Arrays.fill(signature, (byte) 0);
            return InvoiceWebhookSignatures.verify(scope, source, header, List.of(new InvoiceWebhookSignatures.SigningKey("ephemeral_boundary_key", secret)), Clock.systemUTC());
        }
        @Override public void close() { Arrays.fill(secret, (byte) 0); }
    }
}
