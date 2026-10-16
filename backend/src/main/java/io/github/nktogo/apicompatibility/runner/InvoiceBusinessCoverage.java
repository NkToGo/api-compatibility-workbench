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

/** Distinct registered business deliveries, not ordered trials, live deliveries or migration approval. */
final class InvoiceBusinessCoverage {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    enum Outcome { VERIFIED, EXPECTED_BLOCK, UNEXPECTED_BLOCK, FAILED, NOT_EVALUATED, CANCELLED, NOT_ATTEMPTED }
    record Entry(String corpus, String corpusIdentity, String scenario, String delivery, String consumer,
                 JsonObject input, String evidenceIdentity, InvoiceRenewalOracle.Fact expected) {
        Entry { input = input.deepCopy(); }
        @Override public JsonObject input() { return input.deepCopy(); }
        @Override public String toString() { return "Invoice business delivery [" + key() + "]"; }
        String key() { return corpus + "/" + scenario + "/" + delivery; }
        boolean webhook() { return consumer.equals("invoice-webhook"); }
        boolean expectedBlock() { return expected == null; }
        String buildId() {
            var configuration = input.getAsJsonObject("configuration"); String sdk = configuration.get("sdkVersion").getAsString();
            String version = input.get("effectiveApiVersion").getAsString();
            if (webhook() && sdk.equals("29.0.0") && version.equals(InvoiceVersionedConsumerBuilds.ACACIA)) return InvoiceVersionedConsumerBuilds.BUILD_ID;
            CaseInputs.require(Objects.equals(InvoiceWebhookAdapters.api(sdk), version), "Unsupported registered business route");
            return (webhook() ? "webhook" : "reconciliation") + (sdk.equals("28.4.0") ? "-baseline" : "-basil-source");
        }
        JsonObject metadata() {
            var row = new JsonObject(); row.addProperty("key", key()); row.addProperty("corpusIdentity", corpusIdentity);
            row.addProperty("corpusId", corpus); row.addProperty("scenarioId", scenario); row.addProperty("deliveryId", delivery);
            row.addProperty("familyId", input.has("familyId") ? input.get("familyId").getAsString() : corpus.equals("rollout-v1") ? scenario : "old-delivery-after-upgrade");
            row.addProperty("consumerId", consumer); row.addProperty("inputIdentity", InvoiceRenewalOracle.inputIdentity(input));
            row.addProperty("evidenceIdentity", evidenceIdentity); row.addProperty("sourceIdentity", input.getAsJsonObject("source").get("sha256").getAsString());
            row.addProperty("buildId", buildId()); row.addProperty("expectedBlock", expectedBlock()); return row;
        }
    }
    static final class Plan {
        private final InvoiceRenewalRegistry.Registration fixtures;
        private final InvoiceConsumerBuilds.Registration builds;
        private final InvoiceVersionedConsumerBuilds.Registration versioned;
        private final List<Entry> entries;
        private Plan(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds, InvoiceVersionedConsumerBuilds.Registration versioned) {
            this.fixtures = fixtures; this.builds = builds; this.versioned = versioned; var entries = new ArrayList<Entry>();
            for (var corpus : fixtures.businessCorpora().values()) for (var scenario : corpus.scenarios())
                for (var delivery : new TreeMap<>(scenario.deliveries()).values()) entries.add(new Entry(corpus.id(), corpus.identity(), scenario.id(), delivery.id(), delivery.consumerId(), delivery.input(), delivery.expected().evidenceIdentity(), delivery.expected()));
            for (var scenario : fixtures.oldDelivery().scenarios()) for (var delivery : new TreeMap<>(scenario.deliveries()).values())
                entries.add(new Entry("old-delivery-v1", fixtures.oldDelivery().identity(), scenario.id(), delivery.id(), delivery.consumerId(), delivery.input(), delivery.evidenceIdentity(), delivery.expected()));
            CaseInputs.require(entries.size() == 100 && entries.stream().map(Entry::key).distinct().count() == 100, "Incomplete business delivery plan");
            CaseInputs.require(entries.stream().filter(Entry::expectedBlock).count() == 1, "Unexpected business block inventory");
            this.entries = List.copyOf(entries); this.entries.forEach(Entry::buildId);
        }
        List<Entry> entries() { return entries; }
        InvoiceRenewalRegistry.Registration fixtures() { return fixtures; }
        byte[] source(Entry entry) { CaseInputs.require(entries.contains(entry), "Unregistered business delivery"); return fixtures.bytes(entry.corpus(), entry.input.getAsJsonObject("source").get("file").getAsString()); }
        JsonObject metadata() {
            var result = new JsonObject(); result.addProperty("formatVersion", 1); result.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            result.addProperty("authority", "synthetic-fixture-execution"); result.addProperty("coverageStage", "BUSINESS_DELIVERIES_ONLY");
            result.addProperty("registryIdentity", fixtures.identity()); result.addProperty("buildRegistrationIdentity", builds.identity()); result.addProperty("versionedRegistrationIdentity", versioned.identity());
            var rows = new JsonArray(); entries.forEach(e -> rows.add(e.metadata())); result.add("deliveries", rows); return result;
        }
    }
    static Plan load(Path caseRoot) throws IOException {
        var registry = InvoiceRenewalRegistry.load(caseRoot); registry.requireFullCaseCoverage();
        return new Plan(registry, InvoiceConsumerBuilds.load(caseRoot), InvoiceVersionedConsumerBuilds.load(caseRoot));
    }
    static final class DeliveryResult {
        private final Entry entry;
        private final Outcome outcome;
        private final JsonObject evidence;
        private final InvoiceConsumerExecution execution;
        private DeliveryResult(Entry entry, Outcome outcome, JsonObject evidence, InvoiceConsumerExecution execution) {
            this.entry = entry; this.outcome = outcome; this.evidence = evidence.deepCopy(); this.execution = execution;
        }
        Entry entry() { return entry; }
        Outcome outcome() { return outcome; }
        JsonObject json() { return evidence.deepCopy(); }
        InvoiceRenewalOracle.Observation observation() { return execution == null ? null : execution.observation(); }
    }
    static final class Run {
        private final Plan plan;
        private final List<DeliveryResult> deliveries;
        private final JsonObject report;
        private Run(Plan plan, List<DeliveryResult> deliveries, String image, Duration deadline, String harness) {
            this.plan = plan;
            this.deliveries = List.copyOf(deliveries); report = plan.metadata(); report.remove("deliveries");
            report.addProperty("imageId", image); report.addProperty("deadlineMillisPerStage", deadline.toMillis());
            report.addProperty("coverageHarnessIdentity", harness);
            var counts = new JsonObject(); for (var outcome : Outcome.values()) counts.addProperty(outcome.name(), deliveries.stream().filter(d -> d.outcome == outcome).count());
            report.add("outcomes", counts); var rows = new JsonArray(); deliveries.forEach(d -> rows.add(d.json())); report.add("deliveries", rows);
            report.addProperty("businessCoverageSatisfied", businessCoverageSatisfied()); report.addProperty("orderedSequencesVerified", 0); report.addProperty("boundaryControlsVerified", 0); report.addProperty("fullCaseCoverageSatisfied", false);
        }
        List<DeliveryResult> deliveries() { return deliveries; }
        boolean belongsTo(Plan plan) { return this.plan == plan; }
        JsonObject json() { return report.deepCopy(); }
        boolean businessCoverageSatisfied() { return deliveries.size() == 100 && deliveries.stream().allMatch(d -> d.outcome == Outcome.VERIFIED || d.outcome == Outcome.EXPECTED_BLOCK); }
        void requireFullCaseCoverage() { throw new IllegalStateException("Ordered business execution and boundary controls remain pending"); }
    }
    private InvoiceBusinessCoverage() {}
    static Run run(Plan plan, String image, Path directory, Duration deadline, BooleanSupplier stopped) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(stopped);
        RepairSandbox.command(image, name(), InvoiceWorkerMain.class.getName());
        CaseInputs.require(deadline.toMillis() > 0 && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid business stage deadline");
        Path output = directory.toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
        String harness = harnessIdentity(); var metadata = plan.metadata(); metadata.addProperty("imageId", image); metadata.addProperty("deadlineMillisPerStage", deadline.toMillis()); metadata.addProperty("coverageHarnessIdentity", harness); write(output.resolve("plan.json"), metadata);
        var results = new ArrayList<DeliveryResult>(); boolean aborted = false;
        try (var signer = new FixtureSigner()) {
            for (var entry : plan.entries) {
                DeliveryResult result = aborted || stopped.getAsBoolean() ? retained(entry, Outcome.NOT_ATTEMPTED, entry.metadata(), null)
                        : execute(plan, entry, signer, image, deadline, stopped);
                results.add(result); write(output.resolve(String.format(Locale.ROOT, "%03d.json", results.size())), result.json());
                aborted |= result.outcome == Outcome.CANCELLED || result.evidence.has("stageFailure");
            }
        }
        var result = new Run(plan, results, image, deadline, harness); write(output.resolve("report.json"), result.json()); return result;
    }
    private static DeliveryResult execute(Plan plan, Entry entry, FixtureSigner signer, String image, Duration deadline, BooleanSupplier stopped) {
        var row = entry.metadata(); row.add("execution", JsonNull.INSTANCE); row.addProperty("phase", "AUTHENTICATION");
        try {
            byte[] bytes = plan.source(entry); var input = entry.input(); var configuration = input.getAsJsonObject("configuration");
            var host = new HostEvidence(entry.corpusIdentity, InvoiceRenewalOracle.inputIdentity(input), entry.evidenceIdentity, bytes,
                    Verification.NOT_APPLICABLE, null, Verification.NOT_APPLICABLE, null);
            Decision admission;
            if (entry.webhook()) {
                var scope = new InvoiceWebhookSignatures.Scope(input.get("installationId").getAsString(), "fixture_endpoint", "ephemeral_fixture_keys");
                var signature = signer.authenticate(scope, bytes); row.add("signatureVerification", signature.json()); row.addProperty("phase", "ADAPTER");
                if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, null);
                var declared = configuration.get("adapterApiVersion"); String version = declared.isJsonNull() ? null : declared.getAsString();
                var adapter = InvoiceWebhookAdapters.verify(plan.builds, bytes, configuration.get("sdkVersion").getAsString(), version, scope, signature, image, name(), deadline, stopped);
                row.add("adapterVerification", adapter.json()); if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, null);
                if (adapter.outcome() == InvoiceWebhookAdapters.Outcome.NOT_EVALUATED) {
                    if (adapter.json().has("executionStatus") && adapter.json().get("executionStatus").getAsString().equals("INFRASTRUCTURE_ERROR")) row.addProperty("stageFailure", "ADAPTER_INFRASTRUCTURE_ERROR");
                    return retained(entry, Outcome.NOT_EVALUATED, row, null);
                }
                admission = inspectVerifiedWebhook(input, entry.expected, host, scope, signature, adapter);
            } else admission = inspect(entry.consumer, input, entry.expected, host);
            row.addProperty("phase", "ADMISSION"); row.add("admission", admission.json());
            if (!admission.admitted()) {
                boolean expected = entry.expectedBlock() && admission.issues().equals(List.of(Issue.ADAPTER_ABSENT));
                return retained(entry, expected ? Outcome.EXPECTED_BLOCK : Outcome.UNEXPECTED_BLOCK, row, null);
            }
            if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, null);
            row.addProperty("phase", "EXECUTION"); String build = entry.buildId();
            var execution = build.equals(InvoiceVersionedConsumerBuilds.BUILD_ID)
                    ? InvoiceVersionedSandbox.runObserved(plan.versioned, admission, image, name(), deadline, stopped)
                    : InvoiceSandbox.runObserved(plan.builds, build, admission, image, name(), deadline, stopped);
            row.add("execution", execution.evidence()); row.addProperty("phase", "FINISHED");
            if (execution.evidence().get("executionStatus").getAsString().equals("INFRASTRUCTURE_ERROR")) row.addProperty("stageFailure", "WORKER_INFRASTRUCTURE_ERROR");
            Outcome outcome = switch (execution.observation().assertion()) { case PASSED -> Outcome.VERIFIED; case FAILED -> Outcome.FAILED; case NOT_EVALUATED -> Outcome.NOT_EVALUATED; };
            if (execution.observation().executionStatus().equals("CANCELLED")) outcome = Outcome.CANCELLED;
            return retained(entry, outcome, row, execution);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            row.addProperty("stageFailure", "HOST_STAGE_ERROR"); return retained(entry, Outcome.NOT_EVALUATED, row, null);
        }
    }
    private static DeliveryResult retained(Entry entry, Outcome outcome, JsonObject evidence, InvoiceConsumerExecution execution) {
        if (!evidence.has("execution")) evidence.add("execution", JsonNull.INSTANCE);
        if (!evidence.has("phase")) evidence.addProperty("phase", "NOT_STARTED");
        evidence.addProperty("outcome", outcome.name()); return new DeliveryResult(entry, outcome, evidence, execution);
    }
    private static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>(); inventory.put("originalExecution", InvoiceSandbox.harnessIdentity()); inventory.put("versionedExecution", InvoiceVersionedSandbox.harnessIdentity());
        for (Class<?> type : List.of(InvoiceBusinessCoverage.class, InvoiceBusinessCoverageMain.class, ConsumerTasks.class)) InvoiceSandbox.captureClass(type, inventory);
        return CaseInputs.identity(inventory);
    }
    static void write(Path path, JsonObject evidence) throws IOException {
        Files.writeString(path, JSON.toJson(evidence) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static String name() { return "acw-" + UUID.randomUUID(); }
    /** Ephemeral synthetic fixture authentication, never a live Stripe signing credential. */
    private static final class FixtureSigner implements AutoCloseable {
        private final byte[] secret = new byte[32];
        private FixtureSigner() { new SecureRandom().nextBytes(secret); }
        InvoiceWebhookSignatures.Receipt authenticate(InvoiceWebhookSignatures.Scope scope, byte[] bytes) throws Exception {
            long time = Instant.now().getEpochSecond(); var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((time + ".").getBytes(StandardCharsets.US_ASCII)); String header = "t=" + time + ",v1=" + HexFormat.of().formatHex(mac.doFinal(bytes));
            return InvoiceWebhookSignatures.verify(scope, bytes, header, List.of(new InvoiceWebhookSignatures.SigningKey("ephemeral_fixture_key", secret)), Clock.systemUTC());
        }
        @Override public void close() { Arrays.fill(secret, (byte) 0); }
    }
}
