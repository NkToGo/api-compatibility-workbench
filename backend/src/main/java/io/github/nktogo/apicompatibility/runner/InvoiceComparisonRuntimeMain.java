package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Read-boundary check, not a comparison-arm execution or semantic acceptance command. */
public final class InvoiceComparisonRuntimeMain {
    private InvoiceComparisonRuntimeMain() {}
    static Definition definition(ContentDigest bundle, CoordinationRuntime.Policy policy) {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class);
        for (var arm : Arm.values()) implementations.put(arm, digest("unrun-invoice-runtime-check-" + arm.name()));
        implementations.put(Arm.DETERMINISTIC, CoordinationRuntime.implementationIdentity(policy));
        return new Definition("invoice-read-boundary-check", Origin.SUPPLIED_RECORDS, "gpt-unset", digest("unreviewed-invoice-runtime-check"), implementations,
                List.of(new Workload("invoice-joint", List.of(new CaseTask(InvoiceRenewalFixtures.CASE_ID, bundle, ExpectedResponse.ANSWER, List.of("read-boundary-only"))))),
                1, new Limits(60, 1200000, 64000, 2048));
    }
    private static ContentDigest digest(String text) { return new ContentDigest(CaseInputs.hash(text.getBytes(StandardCharsets.UTF_8))); }
    public static void main(String[] args) throws Exception {
        CaseInputs.require(args.length == 4 && args[0].equals("check"), "Usage: check STATE BUNDLE_DIRECTORY EXPECTED_IDENTITY");
        var identity = new ContentDigest(args[3]); var runtime = CoordinationRuntime.forInvoice(Path.of(args[1]), Path.of(args[2]), identity);
        var policy = CoordinationRuntime.invoiceReadPolicy(4, List.of("reader"));
        String id = runtime.register(identity.sha256(), definition(identity, policy), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), policy);
        var description = runtime.describe(id, "reader", "describe-1", InvoiceRenewalFixtures.CASE_ID).getAsJsonObject("page");
        runtime.catalog(id, "reader", "catalog-1", InvoiceRenewalFixtures.CASE_ID, 0, 40);
        runtime.readPage(id, "reader", "task-1", InvoiceRenewalFixtures.CASE_ID, "task", 0, 4000);
        var trace = runtime.finish(id, CoordinationTrial.Status.COMPLETED);
        System.out.println(CaseInputs.JSON.toJson(Map.of("runId", id, "bundleIdentity", identity.sha256(), "status", trace.get("status").getAsString(),
                "usage", trace.get("usage"), "sourceExecutionCoverageSatisfied", description.get("sourceExecutionCoverageSatisfied").getAsBoolean(),
                "comparisonAdapterExecuted", false, "semanticAcceptanceEstablished", false)));
    }
}
