package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One synthetic case capture. Component coverage is not compatibility or semantic acceptance. */
final class InvoiceCaseCoverage {
    static final class Plan {
        private final InvoiceBusinessCoverage.Plan business;
        private final InvoiceOrderedCoverage.Plan ordered;
        private final InvoiceBoundaryCoverage.Plan boundary;
        private Plan(InvoiceRenewalRegistry.Registration fixtures, InvoiceConsumerBuilds.Registration builds, InvoiceVersionedConsumerBuilds.Registration versioned) {
            business = InvoiceBusinessCoverage.from(fixtures, builds, versioned);
            ordered = InvoiceOrderedCoverage.from(business); boundary = InvoiceBoundaryCoverage.from(fixtures, builds);
        }
        InvoiceBusinessCoverage.Plan business() { return business; }
        InvoiceOrderedCoverage.Plan ordered() { return ordered; }
        InvoiceBoundaryCoverage.Plan boundary() { return boundary; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("formatVersion", 1); json.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            json.addProperty("authority", "shared-synthetic-case-execution"); json.addProperty("coverageStage", "FULL_CASE_EXECUTION_CAPTURE");
            json.addProperty("registryIdentity", business.fixtures().identity());
            json.addProperty("buildRegistrationIdentity", business.metadata().get("buildRegistrationIdentity").getAsString());
            json.addProperty("versionedRegistrationIdentity", business.metadata().get("versionedRegistrationIdentity").getAsString());
            var components = new JsonObject(); components.add("business", business.metadata()); components.add("ordered", ordered.metadata());
            components.add("admission", boundary.admission().metadata()); components.add("execution", boundary.execution().metadata());
            components.add("boundary", boundary.metadata()); json.add("components", components);
            json.addProperty("declaredBusinessVariants", 47); json.addProperty("declaredBusinessDeliveries", 100);
            json.addProperty("declaredBusinessOrders", 188); json.addProperty("declaredBusinessPositions", 312);
            json.addProperty("declaredBoundaryControls", 34); json.addProperty("declaredBoundaryPlacements", 102); json.addProperty("declaredBoundaryPositions", 170);
            json.addProperty("consumerDispatchesDuringReplay", 0); return json;
        }
    }
    static Plan load(Path caseRoot) throws IOException {
        var fixtures = InvoiceRenewalRegistry.load(caseRoot); fixtures.requireFullCaseCoverage();
        var builds = InvoiceConsumerBuilds.load(caseRoot);
        return new Plan(fixtures, builds, InvoiceVersionedConsumerBuilds.load(caseRoot, builds));
    }
    static final class Run {
        private final Plan plan;
        private final InvoiceBusinessCoverage.Run business;
        private final InvoiceOrderedCoverage.Run ordered;
        private final InvoiceBoundaryAdmissionCoverage.Run admission;
        private final InvoiceBoundaryExecutionCoverage.Run execution;
        private final InvoiceBoundaryCoverage.Run boundary;
        private final boolean stopped;
        private final JsonObject report;
        private Run(Plan plan, InvoiceBusinessCoverage.Run business, InvoiceOrderedCoverage.Run ordered,
                    InvoiceBoundaryAdmissionCoverage.Run admission, InvoiceBoundaryExecutionCoverage.Run execution,
                    InvoiceBoundaryCoverage.Run boundary, boolean stopped) throws IOException {
            this.plan = plan; this.business = business; this.ordered = ordered; this.admission = admission;
            this.execution = execution; this.boundary = boundary; this.stopped = stopped;
            report = new JsonObject(); report.addProperty("formatVersion", 1); report.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            report.addProperty("authority", "shared-synthetic-case-execution"); report.addProperty("coverageStage", "FULL_CASE_EXECUTION_CAPTURE");
            report.addProperty("capturePlanIdentity", InvoiceOrderedCoverage.identity(plan.metadata()));
            report.addProperty("captureHarnessIdentity", harnessIdentity()); report.add("imageId", business.json().get("imageId"));
            report.addProperty("registryIdentity", plan.business.fixtures().identity()); report.addProperty("dispatchStopped", stopped);
            var components = new JsonObject();
            components.add("business", component(plan.business.metadata(), business.json(), business.businessCoverageSatisfied(), "business"));
            components.add("ordered", component(plan.ordered.metadata(), ordered.json(), ordered.orderedCoverageSatisfied(), "ordered"));
            components.add("admission", component(plan.boundary.admission().metadata(), admission.json(), admission.admissionCoverageSatisfied(), "admission"));
            components.add("execution", component(plan.boundary.execution().metadata(), execution.json(), execution.executionCoverageSatisfied(), "execution"));
            components.add("boundary", component(plan.boundary.metadata(), boundary.json(), boundary.boundaryCoverageSatisfied(), "boundary"));
            report.add("components", components); report.addProperty("fullCaseExecutionCoverageSatisfied", fullCaseExecutionCoverageSatisfied());
            report.addProperty("comparisonAdaptersAvailable", false); report.addProperty("semanticAcceptanceEstablished", false);
            report.addProperty("migrationApproved", false); report.addProperty("consumerDispatchesDuringReplay", 0);
        }
        InvoiceBusinessCoverage.Run business() { return business; }
        InvoiceOrderedCoverage.Run ordered() { return ordered; }
        InvoiceBoundaryAdmissionCoverage.Run admission() { return admission; }
        InvoiceBoundaryExecutionCoverage.Run execution() { return execution; }
        InvoiceBoundaryCoverage.Run boundary() { return boundary; }
        boolean belongsTo(Plan plan) { return this.plan == plan; }
        JsonObject json() { return report.deepCopy(); }
        boolean fullCaseExecutionCoverageSatisfied() {
            return !stopped && business.businessCoverageSatisfied() && ordered.orderedCoverageSatisfied()
                    && admission.admissionCoverageSatisfied() && execution.executionCoverageSatisfied() && boundary.boundaryCoverageSatisfied();
        }
        void requireFullCaseExecutionCoverage() { CaseInputs.require(fullCaseExecutionCoverageSatisfied(), "Incomplete synthetic full-case execution capture"); }
    }
    private InvoiceCaseCoverage() {}
    /** No JSON importer: original plan, source runs and replay references are required together. */
    static Run assemble(Plan plan, InvoiceBusinessCoverage.Run business, InvoiceOrderedCoverage.Run ordered,
                        InvoiceBoundaryAdmissionCoverage.Run admission, InvoiceBoundaryExecutionCoverage.Run execution,
                        InvoiceBoundaryCoverage.Run boundary, boolean stopped) throws IOException {
        Objects.requireNonNull(plan);
        CaseInputs.require(business != null && ordered != null && admission != null && execution != null && boundary != null,
                "Every original typed component run is required");
        CaseInputs.require(business.belongsTo(plan.business) && ordered.belongsTo(plan.ordered, business)
                && admission.belongsTo(plan.boundary.admission()) && execution.belongsTo(plan.boundary.execution())
                && boundary.belongsTo(plan.boundary, admission, execution), "Original shared capture plans and source runs required");
        var image = business.json().get("imageId");
        for (var json : List.of(ordered.json(), admission.json(), execution.json(), boundary.json()))
            CaseInputs.require(image.equals(json.get("imageId")), "Shared capture images differ");
        return new Run(plan, business, ordered, admission, execution, boundary, stopped);
    }
    static Run run(Plan plan, String image, Path directory, Duration deadline, BooleanSupplier stopped) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(stopped); Objects.requireNonNull(deadline);
        RepairSandbox.command(image, "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName());
        CaseInputs.require(deadline.toMillis() > 0 && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid case stage deadline");
        Path output = directory.toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
        var metadata = plan.metadata(); metadata.addProperty("imageId", image); metadata.addProperty("deadlineMillisPerStage", deadline.toMillis());
        metadata.addProperty("captureHarnessIdentity", harnessIdentity()); InvoiceBusinessCoverage.write(output.resolve("plan.json"), metadata);
        var halted = new AtomicBoolean(); BooleanSupplier guarded = () -> { if (stopped.getAsBoolean()) halted.set(true); return halted.get(); };
        var business = InvoiceBusinessCoverage.run(plan.business, image, output.resolve("business"), deadline, guarded);
        haltOnFailure(business.json().getAsJsonArray("deliveries"), halted);
        var ordered = InvoiceOrderedCoverage.evaluate(plan.ordered, business, output.resolve("ordered"), guarded);
        var admission = InvoiceBoundaryAdmissionCoverage.run(plan.boundary.admission(), image, output.resolve("admission"), deadline, guarded);
        haltOnFailure(admission.json().getAsJsonArray("controls"), halted);
        var execution = InvoiceBoundaryExecutionCoverage.run(plan.boundary.execution(), image, output.resolve("execution"), deadline, guarded);
        haltOnFailure(execution.json().getAsJsonArray("controls"), halted);
        var boundary = InvoiceBoundaryCoverage.evaluate(plan.boundary, admission, execution, output.resolve("boundary"), guarded);
        var run = assemble(plan, business, ordered, admission, execution, boundary, halted.get());
        InvoiceBusinessCoverage.write(output.resolve("report.json"), run.json()); return run;
    }
    private static void haltOnFailure(JsonArray rows, AtomicBoolean halted) {
        for (var value : rows) {
            var row = value.getAsJsonObject(); if (row.has("stageFailure") || row.get("outcome").getAsString().equals("CANCELLED")) halted.set(true);
        }
    }
    private static JsonObject component(JsonObject plan, JsonObject evidence, boolean satisfied, String directory) {
        var json = new JsonObject(); json.addProperty("planIdentity", InvoiceOrderedCoverage.identity(plan));
        json.addProperty("evidenceIdentity", InvoiceOrderedCoverage.identity(evidence)); json.addProperty("coverageSatisfied", satisfied);
        json.addProperty("journalDirectory", directory); json.add("outcomes", evidence.getAsJsonObject("outcomes").deepCopy()); return json;
    }
    private static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>();
        for (Class<?> type : List.of(InvoiceCaseCoverage.class, InvoiceCaseCoverageMain.class, InvoiceBusinessCoverage.class, InvoiceOrderedCoverage.class,
                InvoiceBoundaryAdmissionCoverage.class, InvoiceBoundaryExecutionCoverage.class, InvoiceBoundaryCoverage.class,
                InvoiceRenewalRegistry.class, InvoiceConsumerBuilds.class, InvoiceVersionedConsumerBuilds.class, CaseInputs.class, ConsumerTasks.class))
            InvoiceSandbox.captureClass(type, inventory);
        return CaseInputs.identity(inventory);
    }
}
