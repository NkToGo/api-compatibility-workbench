package io.github.nktogo.apicompatibility.runner;

import java.nio.file.Path;
import java.time.Duration;

/** Fixed synthetic admission controls using actual verifiers; no business execution. */
public final class InvoiceBoundaryAdmissionCoverageMain {
    private InvoiceBoundaryAdmissionCoverageMain() {}
    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 3, "Expected registered case root, immutable image ID and new output directory");
            var plan = InvoiceBoundaryAdmissionCoverage.load(Path.of(args[0]));
            System.out.println("Checking 24 rejection controls and two healthy admissions; no business consumers are dispatched.");
            var run = InvoiceBoundaryAdmissionCoverage.run(plan, args[1], Path.of(args[2]), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            System.out.println("Admission report: " + Path.of(args[2]).resolve("report.json"));
            System.out.println("Admission coverage satisfied: " + run.admissionCoverageSatisfied() + "; full boundary execution remains pending.");
            if (!run.admissionCoverageSatisfied()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice boundary admission coverage could not complete; retain any written records."); System.exit(1); }
    }
}
