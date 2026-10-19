package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalBoundaryFixtures.Control;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalOracle.*;

/** Ordered host policy over captured runtime controls. No consumer dispatch or JSON observation import. */
final class InvoiceBoundaryCoverage {
    enum Outcome { VERIFIED, FAILED, NOT_EVALUATED, CANCELLED, NOT_ATTEMPTED }
    record Sequence(Control control, String placement, Control healthy, List<Control> order) {
        Sequence { order = List.copyOf(order); }
        String key() { return control.id() + "/" + placement; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("key", key()); json.addProperty("controlId", control.id()); json.addProperty("placement", placement);
            var ids = new JsonArray(); order.forEach(c -> ids.add(c.id())); json.add("order", ids); return json;
        }
        String expectedOutcome(int position) {
            if (order.get(position) == control) {
                if (!control.expected().admitted()) return "BLOCKED";
                String alone = control.expected().outcomeAlone().name(); return placement.equals("healthy-first") && alone.equals("APPENDED") ? "DUPLICATE" : alone;
            }
            return placement.equals("healthy-last") && control.expected().outcomeAlone() == InvoiceRenewalLedger.Outcome.APPENDED ? "DUPLICATE" : "APPENDED";
        }
        Map<InvoiceRenewalLedger.Key, InvoiceRenewalLedger.Effect> expectedEffects() {
            if (placement.equals("alone") && control.expected().outcomeAlone() != InvoiceRenewalLedger.Outcome.APPENDED) return Map.of();
            var fact = healthy.expectedFact(); return Map.of(new InvoiceRenewalLedger.Key(installation(control), fact.invoiceId()),
                    new InvoiceRenewalLedger.Effect(fact.customerId(), fact.subscriptionId(), InvoiceRenewalLedger.Action.RECORD_PAID_RENEWAL));
        }
    }
    static final class Plan {
        private final InvoiceRenewalRegistry.Registration fixtures;
        private final InvoiceBoundaryAdmissionCoverage.Plan admission;
        private final InvoiceBoundaryExecutionCoverage.Plan execution;
        private final List<Sequence> sequences;
        private Plan(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds) {
            this.fixtures = fixtures; admission = InvoiceBoundaryAdmissionCoverage.from(fixtures, builds); execution = InvoiceBoundaryExecutionCoverage.from(fixtures, builds);
            var controls = fixtures.boundary().controls(); var indexed = new HashMap<String, Control>(); controls.forEach(c -> indexed.put(c.id(), c));
            var placements = fixtures.inventory().getAsJsonObject("boundary").getAsJsonArray("placements").asList().stream().map(JsonElement::getAsString).toList();
            CaseInputs.require(placements.equals(List.of("alone", "healthy-first", "healthy-last")), "Unregistered boundary placements");
            var selected = new ArrayList<Sequence>();
            for (var control : controls) for (String placement : placements) {
                var healthy = indexed.get(control.consumer().equals("invoice-webhook") ? "admit-reconciliation" : "admit-webhook");
                var order = switch (placement) { case "alone" -> List.of(control); case "healthy-first" -> List.of(healthy, control); default -> List.of(control, healthy); };
                selected.add(new Sequence(control, placement, healthy, order));
            }
            CaseInputs.require(selected.size() == 102 && selected.stream().map(Sequence::key).distinct().count() == 102, "Incomplete boundary placement plan"); sequences = List.copyOf(selected);
        }
        InvoiceBoundaryAdmissionCoverage.Plan admission() { return admission; }
        InvoiceBoundaryExecutionCoverage.Plan execution() { return execution; }
        List<Sequence> sequences() { return sequences; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("formatVersion", 1); json.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            json.addProperty("authority", "host-ledger-replay-of-runtime-controls"); json.addProperty("coverageStage", "BOUNDARY_PLACEMENTS_ONLY");
            json.addProperty("registryIdentity", fixtures.identity()); json.addProperty("boundaryCorpusIdentity", fixtures.boundary().identity());
            json.addProperty("admissionPlanIdentity", InvoiceOrderedCoverage.identity(admission.metadata())); json.addProperty("executionPlanIdentity", InvoiceOrderedCoverage.identity(execution.metadata()));
            json.addProperty("declaredControls", 34); json.addProperty("declaredPlacements", 102); json.addProperty("declaredPositions", sequences.stream().mapToInt(s -> s.order.size()).sum());
            json.addProperty("consumerDispatchesDuringReplay", 0); var rows = new JsonArray(); sequences.forEach(s -> rows.add(s.metadata())); json.add("sequences", rows); return json;
        }
    }
    static Plan load(Path caseRoot) throws IOException {
        var fixtures = InvoiceRenewalRegistry.load(caseRoot); return from(fixtures, InvoiceConsumerBuilds.load(caseRoot));
    }
    static Plan from(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds) {
        Objects.requireNonNull(fixtures).requireFullCaseCoverage(); return new Plan(fixtures, Objects.requireNonNull(builds));
    }
    record Position(Outcome outcome, JsonObject evidence) {
        Position { evidence = evidence.deepCopy(); }
        @Override public JsonObject evidence() { return evidence.deepCopy(); }
    }
    /** Pure policy check; only evaluate() admits complete typed run/plan inventories. */
    static Position observe(Control control, Observation observation, InvoiceRenewalLedger ledger, String expectedOutcome) {
        var row = position(control, expectedOutcome); row.add("observation", observation.json());
        if (!control.consumer().equals(observation.consumerId()) || !Objects.equals(control.expectedFact(), observation.expected())) {
            row.addProperty("bindingIssue", "OBSERVATION_SCOPE_MISMATCH"); return positioned(row, Outcome.NOT_EVALUATED);
        }
        var receipt = ledger.accept(observation); var json = new JsonObject(); json.addProperty("sequence", receipt.sequence()); json.addProperty("outcome", receipt.outcome().name());
        row.add("ledgerReceipt", json); row.addProperty("actualOutcome", receipt.outcome().name());
        var expectedActual = control.expectedFact().json();
        if (control.returnMode() == InvoiceRenewalBoundaryFixtures.ReturnMode.UNBOUND) expectedActual.addProperty("inputIdentity", "0".repeat(64));
        if (control.returnMode() == InvoiceRenewalBoundaryFixtures.ReturnMode.WRONG_VERSION) expectedActual.addProperty("effectiveApiVersion", "2025-02-24.acacia");
        boolean matches = observation.assertion() == control.expected().assertion() && observation.protocolInvalid() == control.expected().protocolInvalid()
                && Objects.equals(observation.actual(), control.expected().actualPresent() ? Fact.parse(expectedActual) : null)
                && receipt.outcome().name().equals(expectedOutcome);
        return positioned(row, matches ? Outcome.VERIFIED : Outcome.FAILED);
    }
    private static JsonObject position(Control control, String expected) {
        var row = new JsonObject(); row.addProperty("controlId", control.id()); row.addProperty("consumerId", control.consumer());
        row.addProperty("originalInputIdentity", InvoiceRenewalOracle.inputIdentity(control.input())); row.addProperty("expectedOutcome", expected);
        for (String field : List.of("actualOutcome", "admission", "observation", "ledgerReceipt")) row.add(field, JsonNull.INSTANCE); return row;
    }
    private static Position positioned(JsonObject row, Outcome outcome) { row.addProperty("outcome", outcome.name()); return new Position(outcome, row); }
    static final class SequenceResult {
        private final Sequence sequence;
        private final Outcome outcome;
        private final JsonObject json;
        private SequenceResult(Sequence sequence, Outcome outcome, JsonObject json) { this.sequence = sequence; this.outcome = outcome; this.json = json.deepCopy(); }
        Outcome outcome() { return outcome; }
        JsonObject json() { return json.deepCopy(); }
    }
    static final class Run {
        private final Plan plan;
        private final InvoiceBoundaryAdmissionCoverage.Run admission;
        private final InvoiceBoundaryExecutionCoverage.Run execution;
        private final List<SequenceResult> sequences;
        private final boolean stagesVerified;
        private final JsonObject report;
        private Run(Plan plan, InvoiceBoundaryAdmissionCoverage.Run admission, InvoiceBoundaryExecutionCoverage.Run execution, JsonObject metadata, List<SequenceResult> sequences, boolean stagesVerified) {
            this.plan = plan; this.admission = admission; this.execution = execution;
            this.sequences = List.copyOf(sequences); this.stagesVerified = stagesVerified; report = metadata.deepCopy(); report.remove("sequences");
            var counts = new JsonObject(); for (var outcome : Outcome.values()) counts.addProperty(outcome.name(), sequences.stream().filter(s -> s.outcome == outcome).count()); report.add("outcomes", counts);
            var rows = new JsonArray(); sequences.forEach(s -> rows.add(s.json())); report.add("sequences", rows);
            long verified = sequences.stream().map(s -> s.sequence.control.id()).distinct().filter(id -> sequences.stream().filter(s -> s.sequence.control.id().equals(id)).allMatch(s -> s.outcome == Outcome.VERIFIED)).count();
            report.addProperty("boundaryControlsVerified", verified); report.addProperty("boundaryPlacementsVerified", sequences.stream().filter(s -> s.outcome == Outcome.VERIFIED).count());
            report.addProperty("boundaryCoverageSatisfied", boundaryCoverageSatisfied()); report.addProperty("fullCaseCoverageSatisfied", false);
        }
        List<SequenceResult> sequences() { return sequences; }
        boolean belongsTo(Plan plan, InvoiceBoundaryAdmissionCoverage.Run admission, InvoiceBoundaryExecutionCoverage.Run execution) {
            return this.plan == plan && this.admission == admission && this.execution == execution;
        }
        JsonObject json() { return report.deepCopy(); }
        boolean boundaryCoverageSatisfied() { return stagesVerified && sequences.size() == 102 && sequences.stream().allMatch(s -> s.outcome == Outcome.VERIFIED); }
        void requireFullCaseCoverage() { throw new IllegalStateException("Joint business, ordered and boundary capture remains separate work"); }
    }
    private InvoiceBoundaryCoverage() {}
    static Run evaluate(Plan plan, InvoiceBoundaryAdmissionCoverage.Run admission, InvoiceBoundaryExecutionCoverage.Run execution, Path directory, BooleanSupplier stopped) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(stopped);
        CaseInputs.require(admission != null && execution != null && admission.belongsTo(plan.admission) && execution.belongsTo(plan.execution), "Original typed boundary runs and plans required");
        CaseInputs.require(admission.json().get("imageId").equals(execution.json().get("imageId")), "Boundary run images differ");
        var admitted = new HashMap<String, InvoiceBoundaryAdmissionCoverage.ControlResult>();
        for (var result : admission.controls()) CaseInputs.require(plan.admission.entries().contains(result.entry()) && admitted.put(result.entry().id(), result) == null, "Unregistered admission result");
        var executed = new HashMap<String, InvoiceBoundaryExecutionCoverage.ControlResult>();
        for (var result : execution.controls()) CaseInputs.require(plan.execution.entries().contains(result.entry()) && executed.put(result.entry().id(), result) == null, "Unregistered execution result");
        CaseInputs.require(admitted.size() == 26 && executed.size() == 10, "Incomplete boundary result inventory");
        Path output = directory.toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
        var metadata = plan.metadata(); metadata.addProperty("admissionEvidenceIdentity", InvoiceOrderedCoverage.identity(admission.json())); metadata.addProperty("executionEvidenceIdentity", InvoiceOrderedCoverage.identity(execution.json()));
        metadata.add("imageId", admission.json().get("imageId")); metadata.addProperty("admissionCoverageSatisfied", admission.admissionCoverageSatisfied()); metadata.addProperty("executionCoverageSatisfied", execution.executionCoverageSatisfied());
        metadata.addProperty("replayHarnessIdentity", harnessIdentity()); InvoiceBusinessCoverage.write(output.resolve("plan.json"), metadata);
        var results = new ArrayList<SequenceResult>(); boolean aborted = false;
        for (var sequence : plan.sequences) {
            boolean notStarted = aborted || stopped.getAsBoolean(); var result = replay(sequence, admitted, executed, stopped, notStarted);
            results.add(result); InvoiceBusinessCoverage.write(output.resolve(String.format(Locale.ROOT, "%03d.json", results.size())), result.json()); aborted |= notStarted || result.outcome == Outcome.CANCELLED;
        }
        var run = new Run(plan, admission, execution, metadata, results, admission.admissionCoverageSatisfied() && execution.executionCoverageSatisfied()); InvoiceBusinessCoverage.write(output.resolve("report.json"), run.json()); return run;
    }
    private static SequenceResult replay(Sequence sequence, Map<String, InvoiceBoundaryAdmissionCoverage.ControlResult> admitted, Map<String, InvoiceBoundaryExecutionCoverage.ControlResult> executed, BooleanSupplier stopped, boolean notStarted) {
        var ledger = new InvoiceRenewalLedger(installation(sequence.control), Map.of()); var rows = new JsonArray(); var outcomes = new ArrayList<Outcome>(); boolean cancelled = false;
        for (int i = 0; i < sequence.order.size(); i++) {
            var control = sequence.order.get(i); String expected = sequence.expectedOutcome(i); Position result;
            if (notStarted || cancelled) result = positioned(position(control, expected), Outcome.NOT_ATTEMPTED);
            else if (stopped.getAsBoolean()) { cancelled = true; result = positioned(position(control, expected), Outcome.NOT_ATTEMPTED); }
            else result = apply(control, admitted.get(control.id()), executed.get(control.id()), ledger, expected);
            var row = result.evidence(); row.addProperty("position", i + 1); rows.add(row); outcomes.add(result.outcome());
        }
        var snapshot = ledger.snapshot(); boolean effectsMatch = snapshot.effects().equals(sequence.expectedEffects());
        Outcome outcome = notStarted ? Outcome.NOT_ATTEMPTED : cancelled ? Outcome.CANCELLED : outcomes.contains(Outcome.FAILED) ? Outcome.FAILED
                : outcomes.stream().anyMatch(o -> o != Outcome.VERIFIED) ? Outcome.NOT_EVALUATED : effectsMatch ? Outcome.VERIFIED : Outcome.FAILED;
        var json = sequence.metadata(); json.addProperty("outcome", outcome.name()); json.add("positions", rows);
        json.add("initialEffects", effects(snapshot.initialEffects())); json.add("actualFinalEffects", effects(snapshot.effects())); json.add("expectedFinalEffects", effects(sequence.expectedEffects()));
        json.addProperty("finalEffectsMatch", effectsMatch); json.addProperty("ledgerReceipts", snapshot.receipts().size()); return new SequenceResult(sequence, outcome, json);
    }
    private static Position apply(Control control, InvoiceBoundaryAdmissionCoverage.ControlResult admission, InvoiceBoundaryExecutionCoverage.ControlResult execution, InvoiceRenewalLedger ledger, String expected) {
        Position result; boolean sourceVerified;
        if (!control.expected().admitted()) {
            var row = position(control, expected); var decision = admission.decision();
            boolean bound = decision != null && decision.consumerId().equals(control.consumer()) && decision.input().equals(admission.entry().input());
            sourceVerified = admission.outcome() == InvoiceBoundaryAdmissionCoverage.Outcome.VERIFIED && bound && !decision.admitted();
            if (decision != null && !bound) row.addProperty("bindingIssue", "ADMISSION_SCOPE_MISMATCH");
            if (decision != null) { row.add("admission", decision.json()); row.addProperty("actualOutcome", decision.admitted() ? "UNEXPECTED_ADMISSION" : "BLOCKED"); }
            row.addProperty("admissionStageOutcome", admission.outcome().name()); row.addProperty("runtimeInputIdentity", InvoiceRenewalOracle.inputIdentity(admission.entry().input()));
            row.addProperty("runtimeEvidenceIdentity", admission.entry().host().evidenceIdentity());
            result = positioned(row, sourceVerified && expected.equals("BLOCKED") ? Outcome.VERIFIED : admission.outcome() == InvoiceBoundaryAdmissionCoverage.Outcome.FAILED ? Outcome.FAILED : Outcome.NOT_EVALUATED);
        } else {
            var observed = execution.observation(); sourceVerified = execution.outcome() == InvoiceBoundaryExecutionCoverage.Outcome.VERIFIED;
            if (admission != null) sourceVerified &= admission.outcome() == InvoiceBoundaryAdmissionCoverage.Outcome.VERIFIED && admission.decision() != null && admission.decision().admitted()
                    && admission.decision().consumerId().equals(control.consumer()) && admission.decision().input().equals(admission.entry().input());
            result = observed == null ? positioned(position(control, expected), execution.outcome() == InvoiceBoundaryExecutionCoverage.Outcome.FAILED ? Outcome.FAILED : Outcome.NOT_EVALUATED) : observe(control, observed, ledger, expected);
            var row = result.evidence(); row.addProperty("executionStageOutcome", execution.outcome().name());
            if (admission != null) { row.addProperty("admissionStageOutcome", admission.outcome().name()); if (admission.decision() != null) row.add("admission", admission.decision().json()); }
            if (!sourceVerified) { row.addProperty("sourceIssue", "UPSTREAM_CONTROL_NOT_VERIFIED"); result = positioned(row, result.outcome() == Outcome.FAILED || execution.outcome() == InvoiceBoundaryExecutionCoverage.Outcome.FAILED || admission != null && admission.outcome() == InvoiceBoundaryAdmissionCoverage.Outcome.FAILED ? Outcome.FAILED : Outcome.NOT_EVALUATED); }
            else result = positioned(row, result.outcome());
        }
        return result;
    }
    private static String installation(Control control) { return control.input().get("installationId").getAsString(); }
    private static JsonArray effects(Map<InvoiceRenewalLedger.Key, InvoiceRenewalLedger.Effect> values) {
        var rows = new JsonArray(); values.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().installationId() + "/" + e.getKey().invoiceId())).forEach(e -> {
            var row = new JsonObject(); row.addProperty("installationId", e.getKey().installationId()); row.addProperty("invoiceId", e.getKey().invoiceId());
            row.addProperty("customerId", e.getValue().customerId()); row.addProperty("subscriptionId", e.getValue().subscriptionId()); row.addProperty("action", e.getValue().action().name()); rows.add(row);
        }); return rows;
    }
    private static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>(); for (Class<?> type : List.of(InvoiceBoundaryCoverage.class, InvoiceBoundaryCoverageMain.class, InvoiceBoundaryAdmissionCoverage.class, InvoiceBoundaryExecutionCoverage.class, InvoiceOrderedCoverage.class, InvoiceBusinessCoverage.class, InvoiceRenewalLedger.class, InvoiceRenewalOracle.class, InvoiceRenewalRegistry.class, InvoiceRenewalBoundaryFixtures.class, CaseInputs.class, ConsumerTasks.class)) InvoiceSandbox.captureClass(type, inventory); return CaseInputs.identity(inventory);
    }
}
