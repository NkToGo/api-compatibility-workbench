package io.github.nktogo.apicompatibility.runner;

import java.nio.file.Path;
import java.time.Duration;

/** Fixed synthetic workload entry point; no credentials or arbitrary source selection. */
public final class InvoiceBusinessCoverageMain {
    private InvoiceBusinessCoverageMain() {}
    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 3, "Expected registered case root, immutable image ID and new output directory");
            var plan = InvoiceBusinessCoverage.load(Path.of(args[0])); Path output = Path.of(args[2]);
            System.out.println("Running 100 registered business deliveries; output: " + output.toAbsolutePath());
            var run = InvoiceBusinessCoverage.run(plan, args[1], output, Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            System.out.println("Business coverage report: " + output.toAbsolutePath().resolve("report.json"));
            System.out.println("Registered deliveries: " + run.deliveries().size() + "; business coverage satisfied: " + run.businessCoverageSatisfied());
            if (!run.businessCoverageSatisfied()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice business coverage could not complete; retain any written records."); System.exit(1); }
    }
}
