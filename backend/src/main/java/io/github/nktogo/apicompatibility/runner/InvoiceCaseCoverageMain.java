package io.github.nktogo.apicompatibility.runner;

import java.nio.file.Path;
import java.time.Duration;

public final class InvoiceCaseCoverageMain {
    private InvoiceCaseCoverageMain() {}
    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 3, "Expected registered case root, immutable image ID and new output directory");
            var plan = InvoiceCaseCoverage.load(Path.of(args[0]));
            System.out.println("Capturing all invoice business, ordered and boundary evidence under one shared plan.");
            var run = InvoiceCaseCoverage.run(plan, args[1], Path.of(args[2]), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            System.out.println("Case capture report: " + Path.of(args[2]).resolve("report.json").toAbsolutePath().normalize());
            System.out.println("Full-case execution coverage satisfied: " + run.fullCaseExecutionCoverageSatisfied() + "; comparison and semantic acceptance remain pending.");
            if (!run.fullCaseExecutionCoverageSatisfied()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice case capture could not complete; retain any written records."); System.exit(1); }
    }
}
