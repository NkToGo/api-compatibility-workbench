package io.github.nktogo.apicompatibility.runner;

import java.nio.file.Path;
import java.time.Duration;

/** Fixed synthetic consumer execution controls, including expected incomplete and failed assertions. */
public final class InvoiceBoundaryExecutionCoverageMain {
    private InvoiceBoundaryExecutionCoverageMain() {}
    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 3, "Expected registered case root, immutable image ID and new output directory");
            var plan = InvoiceBoundaryExecutionCoverage.load(Path.of(args[0]));
            System.out.println("Executing eight consumer fault controls and two healthy controls; coverage is not compatibility approval.");
            var run = InvoiceBoundaryExecutionCoverage.run(plan, args[1], Path.of(args[2]), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            System.out.println("Execution control report: " + Path.of(args[2]).resolve("report.json"));
            System.out.println("Execution control coverage satisfied: " + run.executionCoverageSatisfied() + "; ordered boundary coverage remains pending.");
            if (!run.executionCoverageSatisfied()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice execution control coverage could not complete; retain any written records."); System.exit(1); }
    }
}
