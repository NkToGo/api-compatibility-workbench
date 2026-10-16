package io.github.nktogo.apicompatibility.runner;

import java.nio.file.*;
import java.time.Duration;

/** Fixed synthetic business execution followed by ordered host-ledger replay. */
public final class InvoiceOrderedCoverageMain {
    private InvoiceOrderedCoverageMain() {}
    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length == 3, "Expected registered case root, immutable image ID and new output directory");
            var plan = InvoiceOrderedCoverage.load(Path.of(args[0]));
            RepairSandbox.command(args[1], "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName());
            Path output = Path.of(args[2]).toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
            System.out.println("Executing 100 business deliveries, then replaying 188 host-ledger orders; output: " + output);
            var business = InvoiceBusinessCoverage.run(plan.business(), args[1], output.resolve("business"), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
            var ordered = InvoiceOrderedCoverage.evaluate(plan, business, output.resolve("ordered"), () -> Thread.currentThread().isInterrupted());
            System.out.println("Ordered coverage report: " + output.resolve("ordered/report.json"));
            System.out.println("Business coverage satisfied: " + business.businessCoverageSatisfied() + "; ordered coverage satisfied: " + ordered.orderedCoverageSatisfied());
            if (!business.businessCoverageSatisfied() || !ordered.orderedCoverageSatisfied()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice ordered coverage could not complete; retain any written records."); System.exit(1); }
    }
}
