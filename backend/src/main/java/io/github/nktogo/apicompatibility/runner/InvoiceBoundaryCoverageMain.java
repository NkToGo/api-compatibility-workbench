package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;

/** Capture both runtime stages once, then replay every registered boundary placement. */
public final class InvoiceBoundaryCoverageMain {
    private InvoiceBoundaryCoverageMain() {}
    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 3, "Expected registered case root, immutable image ID and new output directory");
            var plan = InvoiceBoundaryCoverage.load(Path.of(args[0])); RepairSandbox.command(args[1], "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName());
            Path output = Path.of(args[2]).toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
            System.out.println("Capturing admission/execution controls, then replaying 102 boundary placements; no consumer dispatch during replay.");
            var admission = InvoiceBoundaryAdmissionCoverage.run(plan.admission(), args[1], output.resolve("admission"), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            var execution = InvoiceBoundaryExecutionCoverage.run(plan.execution(), args[1], output.resolve("execution"), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            var replay = InvoiceBoundaryCoverage.evaluate(plan, admission, execution, output.resolve("placements"), () -> Thread.currentThread().isInterrupted());
            System.out.println("Boundary coverage report: " + output.resolve("placements/report.json"));
            System.out.println("Boundary coverage satisfied: " + replay.boundaryCoverageSatisfied() + "; joint full-case capture remains pending.");
            if (!replay.boundaryCoverageSatisfied()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice boundary coverage could not complete; retain any written records."); System.exit(1); }
    }
}
