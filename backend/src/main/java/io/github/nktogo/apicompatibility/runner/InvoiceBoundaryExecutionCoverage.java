package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalAdmission.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

/** Fixed consumer/transport fault controls, not candidate execution or migration acceptance. */
final class InvoiceBoundaryExecutionCoverage {
    enum Outcome { VERIFIED, FAILED, NOT_EVALUATED, CANCELLED, NOT_ATTEMPTED }
    enum Mode { HEALTHY, MISSING, TIMEOUT_AFTER_RETURN, CANCEL_AFTER_RETURN, EXIT_AFTER_RETURN, MALFORMED, TRAILING, UNBOUND, WRONG_VERSION }
    record Expectation(String transport, String disposition, Assertion assertion, boolean actual, boolean invalid, boolean identitiesMatch) {
        JsonObject json() {
            var json = new JsonObject(); json.addProperty("transportStatus", transport); json.addProperty("executionDisposition", disposition);
            json.addProperty("assertionOutcome", assertion.name()); json.addProperty("actualPresent", actual);
            json.addProperty("protocolInvalid", invalid); json.addProperty("identitiesMatch", identitiesMatch); return json;
        }
    }
    static final class Entry {
        private final InvoiceRenewalBoundaryFixtures.Control control;
        private final Mode mode;
        private final Map<String, String> sources;
        private final String originalSource, controlledSource;
        private Entry(InvoiceRenewalBoundaryFixtures.Control control, InvoiceConsumerBuilds.Registration builds) {
            this.control = control; mode = switch (control.id()) {
                case "admit-webhook", "admit-reconciliation" -> Mode.HEALTHY;
                case "completed-missing-result" -> Mode.MISSING;
                case "returned-timed-out" -> Mode.TIMEOUT_AFTER_RETURN;
                case "returned-cancelled" -> Mode.CANCEL_AFTER_RETURN;
                case "returned-infrastructure-error" -> Mode.EXIT_AFTER_RETURN;
                case "malformed-return" -> Mode.MALFORMED; case "trailing-return" -> Mode.TRAILING;
                case "unbound-return" -> Mode.UNBOUND; case "wrong-version-return" -> Mode.WRONG_VERSION;
                default -> throw new IllegalArgumentException("Unregistered boundary execution control");
            };
            originalSource = builds.sourceIdentity(buildId()); var captured = new TreeMap<>(builds.workerSources(buildId()));
            if (mode != Mode.HEALTHY) captured.put("InvoiceWebhookConsumer.java", wrap(captured.get("InvoiceWebhookConsumer.java"), mode));
            sources = Collections.unmodifiableMap(captured); var inventory = new TreeMap<String, String>();
            sources.forEach((name, source) -> inventory.put(name, CaseInputs.hash(source.getBytes(StandardCharsets.UTF_8)))); controlledSource = CaseInputs.identity(inventory);
        }
        String id() { return control.id(); }
        Mode mode() { return mode; }
        String consumer() { return control.consumer(); }
        boolean webhook() { return consumer().equals("invoice-webhook"); }
        String buildId() { return webhook() ? "webhook-basil-source" : "reconciliation-basil-source"; }
        JsonObject input() { return control.input(); }
        Fact expectedFact() { return control.expectedFact(); }
        Map<String, String> sources() { return sources; }
        Expectation expected() {
            return switch (mode) {
                case HEALTHY -> new Expectation("COMPLETED", "COMPLETED", Assertion.PASSED, true, false, true);
                case MISSING -> new Expectation("COMPLETED", "COMPLETED", Assertion.NOT_EVALUATED, false, false, false);
                case TIMEOUT_AFTER_RETURN -> new Expectation("TIMED_OUT", "TIMED_OUT", Assertion.NOT_EVALUATED, true, false, true);
                case CANCEL_AFTER_RETURN -> new Expectation("CANCELLED", "CANCELLED", Assertion.NOT_EVALUATED, true, false, true);
                case EXIT_AFTER_RETURN -> new Expectation("WORKER_FAILED", "INFRASTRUCTURE_ERROR", Assertion.NOT_EVALUATED, true, false, true);
                case MALFORMED -> new Expectation("PROTOCOL_ERROR", "INFRASTRUCTURE_ERROR", Assertion.NOT_EVALUATED, false, true, false);
                case TRAILING -> new Expectation("PROTOCOL_ERROR", "INFRASTRUCTURE_ERROR", Assertion.NOT_EVALUATED, true, true, true);
                case UNBOUND -> new Expectation("COMPLETED", "COMPLETED", Assertion.FAILED, true, false, false);
                case WRONG_VERSION -> new Expectation("COMPLETED", "COMPLETED", Assertion.FAILED, true, false, true);
            };
        }
        JsonObject metadata() {
            var row = new JsonObject(); row.addProperty("controlId", id()); row.addProperty("consumerId", consumer());
            row.addProperty("mode", mode.name()); row.addProperty("originalBuildId", buildId()); row.addProperty("originalSourceIdentity", originalSource);
            row.addProperty("controlledSourceIdentity", controlledSource); row.addProperty("inputIdentity", expectedFact().inputIdentity());
            row.addProperty("evidenceIdentity", expectedFact().evidenceIdentity()); row.addProperty("sourceIdentity", CaseInputs.hash(control.host().sourceBytes()));
            row.add("expectedRuntime", expected().json()); return row;
        }
        private Fact expectedActual() {
            if (!expected().actual()) return null;
            var json = expectedFact().json();
            if (mode == Mode.UNBOUND) json.addProperty("inputIdentity", "0".repeat(64));
            if (mode == Mode.WRONG_VERSION) json.addProperty("effectiveApiVersion", "2025-02-24.acacia");
            return Fact.parse(json);
        }
    }
    static final class Plan {
        private final InvoiceRenewalRegistry.Registration fixtures;
        private final InvoiceConsumerBuilds.Registration builds;
        private final List<Entry> entries;
        private Plan(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds) {
            this.fixtures = fixtures; this.builds = builds;
            entries = fixtures.boundary().controls().stream().filter(c -> c.expected().admitted()).map(c -> new Entry(c, builds)).toList();
            CaseInputs.require(entries.size() == 10 && entries.stream().filter(e -> e.mode == Mode.HEALTHY).count() == 2, "Incomplete admitted boundary inventory");
        }
        List<Entry> entries() { return entries; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("formatVersion", 1); json.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            json.addProperty("authority", "synthetic-consumer-execution-controls"); json.addProperty("coverageStage", "BOUNDARY_EXECUTION_ONLY");
            json.addProperty("registryIdentity", fixtures.identity()); json.addProperty("boundaryCorpusIdentity", fixtures.boundary().identity());
            json.addProperty("buildRegistrationIdentity", builds.identity()); var rows = new JsonArray(); entries.forEach(e -> rows.add(e.metadata())); json.add("controls", rows); return json;
        }
    }
    static Plan load(Path caseRoot) throws IOException {
        var fixtures = InvoiceRenewalRegistry.load(caseRoot); fixtures.requireFullCaseCoverage(); return from(fixtures, InvoiceConsumerBuilds.load(caseRoot));
    }
    static Plan from(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds) {
        Objects.requireNonNull(fixtures).requireFullCaseCoverage(); return new Plan(fixtures, Objects.requireNonNull(builds));
    }
    static final class ControlResult {
        private final Entry entry;
        private final Outcome outcome;
        private final JsonObject evidence;
        private final InvoiceConsumerExecution execution;
        private ControlResult(Entry entry, Outcome outcome, JsonObject evidence, InvoiceConsumerExecution execution) {
            this.entry = entry; this.outcome = outcome; this.evidence = evidence.deepCopy(); this.execution = execution; this.evidence.addProperty("outcome", outcome.name());
        }
        Entry entry() { return entry; }
        Outcome outcome() { return outcome; }
        JsonObject json() { return evidence.deepCopy(); }
        Observation observation() { return execution == null ? null : execution.observation(); }
    }
    static final class Run {
        private final Plan plan;
        private final List<ControlResult> controls;
        private final JsonObject report;
        private Run(Plan plan, List<ControlResult> controls, JsonObject metadata) {
            this.plan = plan;
            this.controls = List.copyOf(controls); report = metadata.deepCopy(); report.remove("controls");
            var counts = new JsonObject(); for (var outcome : Outcome.values()) counts.addProperty(outcome.name(), controls.stream().filter(c -> c.outcome == outcome).count()); report.add("outcomes", counts);
            var rows = new JsonArray(); controls.forEach(c -> rows.add(c.json())); report.add("controls", rows);
            report.addProperty("executionCoverageSatisfied", executionCoverageSatisfied());
            report.addProperty("executionControlsVerified", controls.stream().filter(c -> c.entry.mode != Mode.HEALTHY && c.outcome == Outcome.VERIFIED).count());
            report.addProperty("boundaryControlsVerified", 0); report.addProperty("boundaryPlacementsVerified", 0); report.addProperty("fullCaseCoverageSatisfied", false);
        }
        List<ControlResult> controls() { return controls; }
        boolean belongsTo(Plan plan) { return this.plan == plan; }
        JsonObject json() { return report.deepCopy(); }
        boolean executionCoverageSatisfied() { return controls.size() == 10 && controls.stream().allMatch(c -> c.outcome == Outcome.VERIFIED); }
        void requireFullCaseCoverage() { throw new IllegalStateException("Combined boundary controls and ordered placements remain pending"); }
    }
    private InvoiceBoundaryExecutionCoverage() {}
    static Run run(Plan plan, String image, Path directory, Duration deadline, BooleanSupplier stopped) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(stopped); RepairSandbox.command(image, name(), InvoiceWorkerMain.class.getName());
        CaseInputs.require(deadline.toMillis() > 0 && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid boundary execution deadline");
        Path output = directory.toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
        var metadata = plan.metadata(); metadata.addProperty("imageId", image); metadata.addProperty("deadlineMillisPerStage", deadline.toMillis()); metadata.addProperty("coverageHarnessIdentity", harnessIdentity());
        InvoiceBusinessCoverage.write(output.resolve("plan.json"), metadata); var results = new ArrayList<ControlResult>(); boolean aborted = false;
        try (var signer = new FixtureSigner()) {
            for (var entry : plan.entries) {
                var result = aborted || stopped.getAsBoolean() ? retained(entry, Outcome.NOT_ATTEMPTED, entry.metadata(), null)
                        : execute(plan, entry, signer, image, deadline, stopped, metadata.get("coverageHarnessIdentity").getAsString());
                results.add(result); InvoiceBusinessCoverage.write(output.resolve(String.format(Locale.ROOT, "%03d.json", results.size())), result.json());
                aborted |= result.outcome == Outcome.CANCELLED || result.evidence.has("stageFailure");
            }
        }
        var run = new Run(plan, results, metadata); InvoiceBusinessCoverage.write(output.resolve("report.json"), run.json()); return run;
    }
    private static ControlResult execute(Plan plan, Entry entry, FixtureSigner signer, String image, Duration deadline, BooleanSupplier stopped, String harness) {
        var row = entry.metadata(); row.addProperty("phase", "AUTHENTICATION");
        InvoiceConsumerExecution execution = null;
        try {
            var input = entry.input(); var captured = entry.control.host(); byte[] bytes = captured.sourceBytes();
            var host = new HostEvidence(captured.bundleIdentity(), captured.inputIdentity(), captured.evidenceIdentity(), bytes, Verification.NOT_CHECKED, null, Verification.NOT_CHECKED, null);
            Decision admission;
            if (entry.webhook()) {
                var scope = new InvoiceWebhookSignatures.Scope(input.get("installationId").getAsString(), "boundary_execution_endpoint", "ephemeral_fixture_keys");
                var signature = signer.authenticate(scope, bytes); row.add("signatureVerification", signature.json());
                if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, null);
                row.addProperty("phase", "ADAPTER"); var adapter = InvoiceWebhookAdapters.verify(plan.builds, bytes, "29.0.0", "2025-03-31.basil", scope, signature, image, name(), deadline, stopped);
                row.add("adapterVerification", adapter.json());
                if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, null);
                if (adapter.outcome() == InvoiceWebhookAdapters.Outcome.NOT_EVALUATED) { row.addProperty("stageFailure", "ADAPTER_INCOMPLETE"); return retained(entry, Outcome.NOT_EVALUATED, row, null); }
                admission = inspectVerifiedWebhook(input, entry.expectedFact(), host, scope, signature, adapter);
            } else admission = inspect(entry.consumer(), input, entry.expectedFact(), host);
            row.add("admission", admission.json()); row.addProperty("phase", "ADMISSION");
            if (!admission.admitted()) return retained(entry, Outcome.FAILED, row, null);
            if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, null);
            var startup = startup(plan, entry, admission); var protocol = new TrackedProtocol(admission.oracle());
            BooleanSupplier stop = () -> stopped.getAsBoolean() || entry.mode == Mode.CANCEL_AFTER_RETURN && protocol.returned();
            row.addProperty("phase", "EXECUTION");
            var evidence = RepairSandbox.runProtocol(image, name(), startup, InvoiceWorkerMain.class.getName(), protocol, deadline, stop);
            evidence.addProperty("imageId", image); evidence.addProperty("coverageHarnessIdentity", harness);
            evidence.addProperty("controlledSourceIdentity", entry.controlledSource); evidence.addProperty("dependenciesIdentity", plan.builds.dependenciesIdentity());
            evidence.addProperty("configurationIdentity", plan.builds.configurationIdentity(entry.buildId()));
            evidence.addProperty("environmentIdentity", CaseInputs.identity(Map.of("imageId", image, "policy", "invoice-boundary-execution-v1", "mode", entry.mode.name(),
                    "deadlineMillis", deadline.toMillis(), "arguments", RepairSandbox.command(image, "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName()))));
            execution = new InvoiceConsumerExecution(evidence, protocol.observation()); row.add("execution", execution.evidence());
            if (stopped.getAsBoolean()) return retained(entry, Outcome.CANCELLED, row, execution);
            if (!evidence.get("containerRemoved").getAsBoolean() || evidence.get("executionStatus").getAsString().equals("INFRASTRUCTURE_ERROR")) {
                row.addProperty("stageFailure", "WORKER_INFRASTRUCTURE_ERROR"); return retained(entry, Outcome.NOT_EVALUATED, row, execution);
            }
            row.addProperty("phase", "FINISHED"); return retained(entry, matches(entry, execution) ? Outcome.VERIFIED : Outcome.FAILED, row, execution);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt(); row.addProperty("stageFailure", "HOST_STAGE_ERROR"); return retained(entry, Outcome.NOT_EVALUATED, row, execution);
        }
    }
    static JsonObject startup(Plan plan, Entry entry, Decision admission) {
        CaseInputs.require(plan.entries.contains(entry) && entry.input().equals(admission.input()), "Boundary execution input not captured");
        CaseInputs.require(!entry.webhook() || admission.observedWebhookVerified(), "Actual webhook verifier receipts required");
        var startup = InvoiceSandbox.startup(plan.builds, entry.buildId(), admission); startup.add("sources", CaseInputs.JSON.toJsonTree(entry.sources)); return startup;
    }
    private static boolean matches(Entry entry, InvoiceConsumerExecution execution) {
        var observation = execution.observation(); var expected = entry.expected(); var evidence = execution.evidence();
        return evidence.get("transportStatus").getAsString().equals(expected.transport())
                && observation.executionStatus().equals(expected.disposition()) && observation.assertion() == expected.assertion()
                && observation.protocolInvalid() == expected.invalid() && observation.identitiesMatch() == expected.identitiesMatch()
                && Objects.equals(observation.actual(), entry.expectedActual())
                && evidence.get("compilation").isJsonObject() && evidence.getAsJsonObject("compilation").get("disposition").getAsString().equals("SUCCEEDED")
                && (entry.mode != Mode.EXIT_AFTER_RETURN || evidence.get("workerExitCode").getAsInt() == 23);
    }
    private static ControlResult retained(Entry entry, Outcome outcome, JsonObject row, InvoiceConsumerExecution execution) {
        for (String field : List.of("signatureVerification", "adapterVerification", "admission", "execution")) if (!row.has(field)) row.add(field, JsonNull.INSTANCE);
        if (!row.has("phase")) row.addProperty("phase", "NOT_STARTED"); return new ControlResult(entry, outcome, row, execution);
    }
    /** The real consumer computes the fact first. Only this fixed wrapper changes its transport behavior. */
    private static String wrap(String source, Mode mode) {
        String marker = "public static JsonObject consume(JsonObject exchange) {";
        CaseInputs.require(source != null && source.indexOf(marker) >= 0 && source.indexOf(marker) == source.lastIndexOf(marker), "Unregistered control source shape");
        String captured = source.replace(marker, "private static JsonObject capturedConsume(JsonObject exchange) {");
        String action = switch (mode) {
            case MISSING -> "System.exit(0); return fact;";
            case MALFORMED -> "fact.addProperty(\"assertionOutcome\", \"PASSED\"); return fact;";
            case TRAILING -> "boundaryReturn(fact); return fact;";
            case TIMEOUT_AFTER_RETURN, CANCEL_AFTER_RETURN -> "boundaryReturn(fact); while (true) { java.util.concurrent.locks.LockSupport.parkNanos(100000000L); }";
            case EXIT_AFTER_RETURN -> "boundaryReturn(fact); System.exit(23); return fact;";
            case UNBOUND -> "fact.addProperty(\"inputIdentity\", \"0\".repeat(64)); return fact;";
            case WRONG_VERSION -> "fact.addProperty(\"effectiveApiVersion\", \"2025-02-24.acacia\"); return fact;";
            case HEALTHY -> throw new IllegalArgumentException("Healthy source requires no wrapper");
        };
        String wrapper = "public static JsonObject consume(JsonObject exchange) { var fact = capturedConsume(exchange); " + action + " }\n";
        if (Set.of(Mode.TRAILING, Mode.TIMEOUT_AFTER_RETURN, Mode.CANCEL_AFTER_RETURN, Mode.EXIT_AFTER_RETURN).contains(mode)) wrapper += """
            private static void boundaryReturn(JsonObject fact) {
                var message = new JsonObject(); message.addProperty("op", "return"); message.add("result", fact);
                System.out.println(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(message)); System.out.flush();
                try {
                    if (!java.util.Arrays.equals(System.in.readNBytes(3), new byte[] {123, 125, 10})) throw new IllegalStateException("Invalid acknowledgement");
                } catch (java.io.IOException e) { throw new IllegalStateException("Missing acknowledgement"); }
            }
            """;
        int end = captured.lastIndexOf('}'); return captured.substring(0, end) + wrapper + captured.substring(end);
    }
    static final class TrackedProtocol implements WorkerOracle {
        private final InvoiceSandbox.Protocol delegate;
        private final AtomicBoolean returned = new AtomicBoolean();
        TrackedProtocol(InvoiceRenewalOracle oracle) { delegate = new InvoiceSandbox.Protocol(oracle); }
        public JsonElement request(JsonObject request) {
            var response = delegate.request(request);
            if (request.has("op") && request.get("op").getAsString().equals("return")) returned.set(true);
            return response;
        }
        boolean returned() { return returned.get(); }
        Observation observation() { return delegate.observation(); }
        public JsonObject result(String status) { return delegate.result(status); }
    }
    private static String name() { return "acw-" + UUID.randomUUID(); }
    private static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>(); inventory.put("protectedExecution", InvoiceSandbox.harnessIdentity());
        for (Class<?> type : List.of(InvoiceBoundaryExecutionCoverage.class, InvoiceBoundaryExecutionCoverageMain.class, InvoiceBusinessCoverage.class, InvoiceRenewalRegistry.class, InvoiceRenewalBoundaryFixtures.class, ConsumerTasks.class)) InvoiceSandbox.captureClass(type, inventory);
        return CaseInputs.identity(inventory);
    }
    private static final class FixtureSigner implements AutoCloseable {
        private final byte[] secret = new byte[32];
        private FixtureSigner() { new SecureRandom().nextBytes(secret); }
        InvoiceWebhookSignatures.Receipt authenticate(InvoiceWebhookSignatures.Scope scope, byte[] bytes) throws Exception {
            long time = Instant.now().getEpochSecond(); var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((time + ".").getBytes(StandardCharsets.US_ASCII)); byte[] signature = mac.doFinal(bytes);
            String header = "t=" + time + ",v1=" + HexFormat.of().formatHex(signature); Arrays.fill(signature, (byte) 0);
            return InvoiceWebhookSignatures.verify(scope, bytes, header, List.of(new InvoiceWebhookSignatures.SigningKey("ephemeral_execution_key", secret)), Clock.systemUTC());
        }
        @Override public void close() { Arrays.fill(secret, (byte) 0); }
    }
}
