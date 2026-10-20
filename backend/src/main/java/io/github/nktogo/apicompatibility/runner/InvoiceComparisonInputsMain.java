package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.file.*;
import java.time.Duration;

public final class InvoiceComparisonInputsMain {
    private InvoiceComparisonInputsMain() {}
    public static void main(String[] args) {
        try {
            InvoiceComparisonInputs.Bundle bundle;
            if (args.length == 3 && args[0].equals("inspect")) bundle = InvoiceComparisonInputs.open(Path.of(args[1]), new ContentDigest(args[2]));
            else {
                CaseInputs.require(args.length == 4 && args[0].equals("capture"), "Expected capture CASE IMAGE NEW_DIRECTORY or inspect BUNDLE EXPECTED_IDENTITY");
                var plan = InvoiceCaseCoverage.load(Path.of(args[1])); Path root = Path.of(args[3]).toAbsolutePath().normalize();
                ConsumerTasks.directory(root.getParent()); Files.createDirectory(root); ConsumerTasks.directory(root);
                var run = InvoiceCaseCoverage.run(plan, args[2], root.resolve("coverage"), Duration.ofSeconds(30), () -> Thread.currentThread().isInterrupted());
                bundle = InvoiceComparisonInputs.capture(plan, run, root.resolve("inputs"));
                System.out.println("Comparison input directory: " + root.resolve("inputs"));
            }
            var metadata = bundle.describe(); System.out.println("Comparison input identity: " + bundle.identity().sha256());
            System.out.println("Captured artifacts: " + metadata.get("artifactCount").getAsInt());
            System.out.println("Source execution coverage satisfied: " + metadata.get("sourceExecutionCoverageSatisfied").getAsBoolean() + "; comparison adapters and semantic acceptance remain pending.");
            if (!metadata.get("sourceExecutionCoverageSatisfied").getAsBoolean()) System.exit(2);
        } catch (Exception e) { System.err.println("Invoice comparison input operation could not complete; retain any written records."); System.exit(1); }
    }
}
