package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceComparisonRuntimeTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private record Fixture(Path directory, ContentDigest identity, CoordinationRuntime runtime, CoordinationRuntime.Policy policy, String id) {}
    private Fixture fixture(int reads) throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        Path source = root.resolve("source"); Files.createDirectories(source);
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), source.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        Path directory = source.resolve("inputs"); var bundle = InvoiceComparisonInputs.capture(plan, run, directory);
        var runtime = CoordinationRuntime.forInvoice(root, directory, bundle.identity(), clock);
        var policy = CoordinationRuntime.invoiceReadPolicy(reads, List.of("root", "worker"));
        String id = runtime.register(bundle.identity().sha256(), InvoiceComparisonRuntimeMain.definition(bundle.identity(), policy), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), policy);
        return new Fixture(directory, bundle.identity(), runtime, policy, id);
    }
    private Path trial(Fixture f) { return root.resolve("invoice-comparison-trials/" + f.id()); }
    private static int reads(JsonObject trace) { return trace.getAsJsonObject("usage").get("toolCalls").getAsInt(); }
    private static JsonObject text(Fixture f, String participant, String operation, String alias, int offset, int limit) throws Exception {
        return f.runtime().readPage(f.id(), participant, operation, InvoiceRenewalFixtures.CASE_ID, alias, offset, limit);
    }

    @Test void sameBundleAndParticipantScopesAreBoundBeforeAnyWorkAndLegacyNamespaceStaysSeparate() throws Exception {
        var f = fixture(10); assertFalse(Files.exists(trial(f).resolve("started.json")));
        assertEquals(0, reads(f.runtime().snapshot(f.id())));
        assertEquals(f.identity().sha256(), text(f, "root", "read-1", "task", 0, 4000).get("bundleIdentity").getAsString());
        assertEquals(text(f, "root", "read-2", "task", 0, 4000).get("page"), text(f, "worker", "read-2", "task", 0, 4000).get("page"));
        assertThrows(RuntimeException.class, () -> new CoordinationRuntime(root).snapshot(f.id()));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().register("0".repeat(64), InvoiceComparisonRuntimeMain.definition(f.identity(), f.policy()), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), f.policy()));
        assertThrows(IllegalArgumentException.class, () -> CoordinationRuntime.forInvoice(root, f.directory(), new ContentDigest("0".repeat(64))));
    }

    @Test void catalogAndTextPagesShareOneBudgetAndExactRetriesSurviveRestart() throws Exception {
        var f = fixture(2);
        var first = f.runtime().catalog(f.id(), "root", "catalog-1", InvoiceRenewalFixtures.CASE_ID, 0, 40);
        assertEquals(40, first.getAsJsonObject("page").getAsJsonArray("artifacts").size());
        var page = text(f, "worker", "text-1", "task", 0, 20);
        assertEquals(20, page.getAsJsonObject("page").get("content").getAsString().length());
        var restarted = CoordinationRuntime.forInvoice(root, f.directory(), f.identity(), clock);
        assertEquals(first, restarted.catalog(f.id(), "root", "catalog-1", InvoiceRenewalFixtures.CASE_ID, 0, 40));
        assertEquals(page, restarted.readPage(f.id(), "worker", "text-1", InvoiceRenewalFixtures.CASE_ID, "task", 0, 20));
        assertEquals(2, reads(restarted.snapshot(f.id())));
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> text(f, "root", "new-read", "task", 0, 20)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> text(f, "worker", "text-1", "task", 1, 20));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().catalog(f.id(), "worker", "text-1", InvoiceRenewalFixtures.CASE_ID, 0, 20));
        assertEquals(2, reads(restarted.snapshot(f.id())));
    }

    @Test void metadataKeepsIncompleteExecutionSeparateFromSuccessfulToolUseAndSharesTheBudget() throws Exception {
        var f = fixture(1); var description = f.runtime().describe(f.id(), "root", "metadata", InvoiceRenewalFixtures.CASE_ID);
        assertEquals(f.identity().sha256(), description.getAsJsonObject("page").get("bundleIdentity").getAsString());
        assertFalse(description.getAsJsonObject("page").get("sourceExecutionCoverageSatisfied").getAsBoolean());
        assertFalse(description.getAsJsonObject("page").get("semanticAcceptanceEstablished").getAsBoolean());
        assertEquals(description, CoordinationRuntime.forInvoice(root, f.directory(), f.identity(), clock).describe(f.id(), "root", "metadata", InvoiceRenewalFixtures.CASE_ID));
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> text(f, "worker", "new-read", "task", 0, 20)).getMessage());
        assertEquals(1, reads(f.runtime().snapshot(f.id())));
    }

    @Test void rejectedReadsCannotExpandScopeOrConsumeTheSharedReadAllowance() throws Exception {
        var f = fixture(10);
        assertThrows(RuntimeException.class, () -> text(f, "stranger", "read-1", "task", 0, 20));
        assertThrows(RuntimeException.class, () -> f.runtime().readPage(f.id(), "root", "read-1", CaseInputs.CASE_ID, "task", 0, 20));
        for (String alias : List.of("../grant.json", "manifest.json", "receipt.json", "expected", "unknown"))
            assertThrows(RuntimeException.class, () -> text(f, "root", "read-1", alias, 0, 20));
        assertThrows(RuntimeException.class, () -> text(f, "root", "read-1", "task", 0, 4001));
        assertThrows(RuntimeException.class, () -> text(f, "root", "read-1", "task", -1, 20));
        assertThrows(RuntimeException.class, () -> f.runtime().catalog(f.id(), "root", "read-1", InvoiceRenewalFixtures.CASE_ID, 0, 41));
        assertThrows(RuntimeException.class, () -> f.runtime().read(f.id(), "root", "read-1", InvoiceRenewalFixtures.CASE_ID, "task"));
        assertEquals(0, reads(f.runtime().snapshot(f.id())));
    }

    @Test void modelCallsAndModelEnabledRegistrationsRemainUnavailable() throws Exception {
        var f = fixture(10);
        var request = new ModelCallBroker.Request("gpt-unset", "untrusted input", 2048, "investigation-v2");
        assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "root", "model-1", request));
        var p = f.policy(); var expanded = new CoordinationRuntime.Policy(1, 10, p.adapterIdentity(), p.promptIdentity(), p.toolPolicyIdentity(), p.contextPolicyIdentity(), p.finalSelection(),
                List.of(new CoordinationRuntime.Participant("root", Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of("investigation-v2"))));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().register(f.identity().sha256(), InvoiceComparisonRuntimeMain.definition(f.identity(), expanded), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), expanded));
        assertEquals(0, f.runtime().snapshot(f.id()).getAsJsonObject("usage").get("reservedCalls").getAsInt());
        try (var paths = Files.list(trial(f).resolve("broker"))) { assertEquals(0, paths.count()); }
    }

    @Test void wholeTrialDeadlineAndRevocationSurviveNewRuntimeInstances() throws Exception {
        var f = fixture(10); text(f, "root", "first", "task", 0, 20);
        clock.now = clock.now.plus(Duration.ofMinutes(21));
        var restarted = CoordinationRuntime.forInvoice(root, f.directory(), f.identity(), clock);
        assertEquals("TRIAL_EXPIRED", assertThrows(CoordinationRuntime.Stop.class, () -> restarted.catalog(f.id(), "worker", "late", InvoiceRenewalFixtures.CASE_ID, 0, 40)).getMessage());
        assertEquals(1, reads(restarted.snapshot(f.id())));
        restarted.revoke(f.id());
        assertEquals("TRIAL_REVOKED", assertThrows(CoordinationRuntime.Stop.class, () -> text(f, "root", "first", "task", 0, 20)).getMessage());
        assertEquals("CANCELLED", restarted.finish(f.id(), CoordinationTrial.Status.CANCELLED).get("status").getAsString());
    }

    @Test void returnedPagesAndLaterDiskEditsCannotChangeTheOpenSnapshot() throws Exception {
        var f = fixture(10); var first = text(f, "root", "first", "task", 0, 4000);
        String original = first.getAsJsonObject("page").get("content").getAsString(); first.getAsJsonObject("page").addProperty("content", "changed");
        Files.writeString(f.directory().resolve("0001.txt"), "changed on disk");
        assertEquals(original, text(f, "root", "first", "task", 0, 4000).getAsJsonObject("page").get("content").getAsString());
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.forInvoice(root, f.directory(), f.identity(), clock));
    }

    @Test void recordedPageTamperingAndIncompleteLedgerPublicationStayVisible() throws Exception {
        var f = fixture(10); text(f, "root", "first", "task", 0, 20);
        Path record; try (var paths = Files.list(trial(f).resolve("reads"))) { record = paths.findFirst().orElseThrow(); }
        byte[] original = Files.readAllBytes(record); var changed = JsonParser.parseString(new String(original, StandardCharsets.UTF_8)).getAsJsonObject();
        changed.getAsJsonObject("page").addProperty("content", "invented"); Files.writeString(record, changed.toString());
        assertThrows(RuntimeException.class, () -> f.runtime().snapshot(f.id())); Files.write(record, original);
        Path pending = record.resolveSibling(record.getFileName() + ".pending"); Files.writeString(pending, "{}");
        assertThrows(RuntimeException.class, () -> f.runtime().snapshot(f.id()));
        assertThrows(RuntimeException.class, () -> f.runtime().finish(f.id(), CoordinationTrial.Status.FAILED));
        assertTrue(Files.exists(pending)); assertFalse(Files.exists(trial(f).resolve("receipt.json")));
    }

    @Test void finalizationRetainsZeroCallAccountingAndBlocksFurtherParticipantWork() throws Exception {
        var f = fixture(10); var page = text(f, "root", "forged", "admission/forged-payload-permissions", 0, 4000);
        assertEquals("untrusted-data", page.get("contentTrust").getAsString());
        assertTrue(page.getAsJsonObject("page").get("content").getAsString().contains("PASSED"));
        var result = f.runtime().finish(f.id(), CoordinationTrial.Status.COMPLETED);
        assertEquals(result, f.runtime().finish(f.id(), CoordinationTrial.Status.FAILED));
        assertEquals(0, result.getAsJsonObject("usage").get("caseExecutions").getAsInt());
        assertEquals(0, result.getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertEquals("runtime-accounting-not-quality-review", result.get("authority").getAsString());
        assertEquals("TRIAL_FINISHED", assertThrows(CoordinationRuntime.Stop.class, () -> text(f, "root", "later", "task", 0, 20)).getMessage());
        assertEquals(result, CoordinationRuntime.forInvoice(root, f.directory(), f.identity(), clock).retained(f.id()));
    }

    @Test void concurrentParticipantsCannotExceedTheOneSharedReadBudget() throws Exception {
        var f = fixture(1); var ready = new CyclicBarrier(2); var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (String participant : List.of("root", "worker")) futures.add(executor.submit(() -> {
                ready.await(10, TimeUnit.SECONDS);
                try { text(f, participant, "first", "task", 0, 20); return true; }
                catch (CoordinationRuntime.Stop e) { assertEquals("READ_BUDGET_EXHAUSTED", e.getMessage()); return false; }
            }));
            assertEquals(1, (futures.get(0).get(20, TimeUnit.SECONDS) ? 1 : 0) + (futures.get(1).get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, reads(f.runtime().snapshot(f.id())));
        } finally { executor.shutdownNow(); }
    }

    @Test void commandChecksReadToolsWithoutDispatchingAnAdapterOrPrintingEvidenceContent() throws Exception {
        var f = fixture(10); Path output = root.resolve("command.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                InvoiceComparisonRuntimeMain.class.getName(), "check", root.resolve("command-state").toString(), f.directory().toString(), f.identity().sha256())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
            var summary = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
            assertFalse(summary.get("comparisonAdapterExecuted").getAsBoolean()); assertFalse(summary.get("semanticAcceptanceEstablished").getAsBoolean());
            assertEquals(3, summary.getAsJsonObject("usage").get("toolCalls").getAsInt()); assertFalse(summary.has("content"));
            assertFalse(summary.get("sourceExecutionCoverageSatisfied").getAsBoolean());
        } finally { process.destroyForcibly(); }
    }
}
