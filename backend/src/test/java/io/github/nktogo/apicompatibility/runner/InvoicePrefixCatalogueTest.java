package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoicePrefixCatalogueTest {
    @TempDir Path root;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private record Capture(Path directory, InvoiceComparisonInputs.Bundle bundle) {}
    private record Fixture(Capture capture, CoordinationRuntime runtime, CoordinationRuntime.Policy policy, String id) {}
    private Capture capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root()); Path source = Files.createDirectory(root.resolve("source"));
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), source.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        Path directory = source.resolve("inputs"); return new Capture(directory, InvoiceComparisonInputs.capture(plan, run, directory));
    }
    private Fixture fixture(int reads) throws Exception {
        var c = capture(); var runtime = CoordinationRuntime.forInvoiceNavigation(root, c.directory(), c.bundle().identity(), clock);
        var policy = CoordinationRuntime.invoiceNavigationReadPolicy(reads, List.of("root", "worker"));
        String id = runtime.register(c.bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(c.bundle().identity(), policy),
                new Slot("invoice-joint", 1, Arm.DETERMINISTIC), policy); return new Fixture(c, runtime, policy, id);
    }
    private static int reads(JsonObject trace) { return trace.getAsJsonObject("usage").get("toolCalls").getAsInt(); }
    private static JsonObject page(Fixture f, String participant, String operation, String prefix) throws Exception {
        return f.runtime().catalogPrefix(f.id(), participant, operation, InvoiceRenewalFixtures.CASE_ID, prefix, 0, 40);
    }
    private Path trial(Fixture f) { return root.resolve("invoice-navigation-trials/" + f.id()); }
    private static List<String> aliases(JsonObject page) {
        var result = new ArrayList<String>(); page.getAsJsonArray("artifacts").forEach(a -> result.add(a.getAsJsonObject().get("alias").getAsString())); return result;
    }

    @Test void prefixRowsMatchTheSameCapturedInventoryWithoutRevealingContents() throws Exception {
        var c = capture(); var result = c.bundle().catalogPrefix("source/", 0, 40);
        assertEquals(15, result.get("matchingArtifacts").getAsInt()); assertEquals(444, result.get("inventoryArtifacts").getAsInt());
        assertEquals(c.bundle().identity().sha256(), result.get("bundleIdentity").getAsString());
        assertEquals("invoice-prefix-catalogue-v1", result.get("policy").getAsString());
        var expected = InvoiceComparisonInputsTest.aliases(c.bundle()).stream().filter(a -> a.startsWith("source/")).toList();
        assertEquals(expected, aliases(result));
        for (var item : result.getAsJsonArray("artifacts")) {
            var row = item.getAsJsonObject(); assertEquals(Set.of("alias", "sha256", "totalCharacters"), row.keySet());
            var text = c.bundle().read(row.get("alias").getAsString(), 0, 4000);
            assertEquals(text.get("sha256"), row.get("sha256")); assertEquals(text.get("totalCharacters"), row.get("totalCharacters"));
        }
    }
    @Test void filteredOffsetsTraverseAllMatchesInStableLexicalOrder() throws Exception {
        var bundle = capture().bundle(); var selected = new ArrayList<String>(); int offset = 0;
        do { var next = bundle.catalogPrefix("order/", offset, 40); selected.addAll(aliases(next)); offset = next.get("nextOffset").getAsInt(); }
        while (offset < 188);
        assertEquals(188, selected.size()); assertEquals(188, new HashSet<>(selected).size());
        assertEquals(selected.stream().sorted().toList(), selected);
        assertEquals(InvoiceComparisonInputsTest.aliases(bundle).stream().filter(a -> a.startsWith("order/")).toList(), selected);
        assertTrue(aliases(bundle.catalogPrefix("order/", 188, 40)).isEmpty());
    }
    @Test void emptyPrefixKeepsLegacyCatalogueRowsAndFilteredQueriesAreLiteral() throws Exception {
        var bundle = capture().bundle();
        assertEquals(bundle.catalog(40, 40).get("artifacts"), bundle.catalogPrefix("", 40, 40).get("artifacts"));
        assertEquals(4, bundle.catalogPrefix("source/webhook-bas", 0, 40).get("matchingArtifacts").getAsInt());
        assertEquals(2, bundle.catalogPrefix("source/webhook-baseline/", 0, 40).get("matchingArtifacts").getAsInt());
        for (String prefix : List.of("webhook", "SOURCE/", "expected", "rubric", "unknown/")) {
            var result = bundle.catalogPrefix(prefix, 0, 40); assertEquals(0, result.get("matchingArtifacts").getAsInt()); assertTrue(aliases(result).isEmpty());
        }
    }
    @Test void invalidPrefixesOffsetsAndPageSizesCannotSelectOtherFiles() throws Exception {
        var bundle = capture().bundle();
        for (String prefix : Arrays.asList(null, "../", "/source", "source//", "source/../", "source\\", "source/*", "C:/data", "source\n", "x".repeat(301)))
            assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix(prefix, 0, 40));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix("source/", -1, 40));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix("source/", 16, 40));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix("unknown/", 1, 40));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix("", Integer.MAX_VALUE, 40));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix("", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalogPrefix("", 0, 41));
    }
    @Test void metadataIsBoundedAndCannotDiscloseProtectedGradesOrClaimEvidence() throws Exception {
        var result = capture().bundle().catalogPrefix("business/", 0, 40);
        assertEquals(40, result.getAsJsonArray("artifacts").size()); assertEquals(100, result.get("matchingArtifacts").getAsInt());
        assertTrue(result.toString().getBytes(StandardCharsets.UTF_8).length <= 32768);
        assertEquals("catalogue-metadata-not-content-evidence", result.get("authority").getAsString());
        InvoiceComparisonInputsTest.noGrading(result); assertFalse(result.has("content"));
    }
    @Test void prefixDescribeCatalogueAndTextReadsShareTheSameBudgetAcrossRestart() throws Exception {
        var f = fixture(4); f.runtime().describe(f.id(), "root", "metadata", InvoiceRenewalFixtures.CASE_ID);
        var first = page(f, "worker", "lookup", "source/");
        f.runtime().catalog(f.id(), "root", "catalog", InvoiceRenewalFixtures.CASE_ID, 0, 40);
        f.runtime().readPage(f.id(), "worker", "text", InvoiceRenewalFixtures.CASE_ID, "task", 0, 100);
        var reopened = CoordinationRuntime.forInvoiceNavigation(root, f.capture().directory(), f.capture().bundle().identity(), clock);
        assertEquals(first, reopened.catalogPrefix(f.id(), "worker", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        assertEquals(4, reads(reopened.snapshot(f.id())));
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> page(f, "root", "new", "source/")).getMessage());
    }
    @Test void aRetriedOperationCannotChangePrefixOffsetLimitOrKind() throws Exception {
        var f = fixture(10); var first = page(f, "root", "lookup", "source/");
        assertThrows(IllegalArgumentException.class, () -> page(f, "root", "lookup", "business/"));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().catalogPrefix(f.id(), "root", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 1, 40));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().catalogPrefix(f.id(), "root", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 20));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().catalog(f.id(), "root", "lookup", InvoiceRenewalFixtures.CASE_ID, 0, 40));
        assertEquals(first, page(f, "root", "lookup", "source/")); assertEquals(1, reads(f.runtime().snapshot(f.id())));
    }
    @Test void emptyMatchesStillConsumeOneReadAndBindTheExactPrefix() throws Exception {
        var f = fixture(1); var empty = page(f, "root", "empty", "rubric");
        assertTrue(aliases(empty.getAsJsonObject("page")).isEmpty());
        assertEquals(empty, page(f, "root", "empty", "rubric")); assertEquals(1, reads(f.runtime().snapshot(f.id())));
        assertThrows(IllegalArgumentException.class, () -> page(f, "root", "empty", "expected"));
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> page(f, "root", "another-empty", "expected")).getMessage());
    }
    @Test void oldAndNewPoliciesRequireTheirExplicitHostBoundaryAndSeparateStores() throws Exception {
        var f = fixture(10); var old = CoordinationRuntime.forInvoice(root, f.capture().directory(), f.capture().bundle().identity(), clock);
        var oldPolicy = CoordinationRuntime.invoiceReadPolicy(10, List.of("root", "worker"));
        assertNotEquals(oldPolicy.toolPolicyIdentity(), f.policy().toolPolicyIdentity());
        assertThrows(IllegalArgumentException.class, () -> old.register(f.capture().bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(f.capture().bundle().identity(), f.policy()), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), f.policy()));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().register(f.capture().bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(f.capture().bundle().identity(), oldPolicy), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), oldPolicy));
        assertThrows(RuntimeException.class, () -> old.snapshot(f.id()));
    }
    @Test void navigationCannotRegisterModelProtocolsOrDispatchModelCalls() throws Exception {
        var f = fixture(10); var p = f.policy();
        var expanded = new CoordinationRuntime.Policy(p.parallelCalls(), p.maxReads(), p.adapterIdentity(), p.promptIdentity(), p.toolPolicyIdentity(), p.contextPolicyIdentity(), p.finalSelection(),
                List.of(new CoordinationRuntime.Participant("root", Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(InvoiceInvestigationDecision.PROTOCOL))));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().register(f.capture().bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(f.capture().bundle().identity(), expanded), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), expanded));
        var request = new ModelCallBroker.Request("gpt-unset", "untrusted input", 2048, InvoiceInvestigationDecision.PROTOCOL);
        assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "root", "call", request));
        assertEquals(0, f.runtime().snapshot(f.id()).getAsJsonObject("usage").get("reservedCalls").getAsInt());
    }
    @Test void deadlinesAndRevocationAlsoBlockCachedPrefixRetries() throws Exception {
        var f = fixture(2); page(f, "root", "lookup", "source/"); clock.now = clock.now.plus(Duration.ofMinutes(21));
        assertEquals("TRIAL_EXPIRED", assertThrows(CoordinationRuntime.Stop.class, () -> page(f, "root", "lookup", "source/")).getMessage());
        f.runtime().revoke(f.id());
        assertEquals("TRIAL_REVOKED", assertThrows(CoordinationRuntime.Stop.class, () -> page(f, "root", "lookup", "source/")).getMessage());
        assertEquals(1, reads(f.runtime().finish(f.id(), CoordinationTrial.Status.CANCELLED)));
    }
    @Test void retainedPrefixOrRowTamperingAndIncompletePublicationAreRejected() throws Exception {
        var f = fixture(10); page(f, "root", "lookup", "source/"); Path file;
        try (var paths = Files.list(trial(f).resolve("reads"))) { file = paths.findFirst().orElseThrow(); }
        byte[] original = Files.readAllBytes(file); var changed = CaseInputs.object(original);
        changed.getAsJsonObject("pageRequest").addProperty("alias", "business/"); Files.writeString(file, changed.toString());
        assertThrows(IllegalArgumentException.class, () -> f.runtime().snapshot(f.id())); Files.write(file, original);
        changed = CaseInputs.object(original); changed.getAsJsonObject("page").getAsJsonArray("artifacts").get(0).getAsJsonObject().addProperty("totalCharacters", 0);
        Files.writeString(file, changed.toString()); assertThrows(IllegalArgumentException.class, () -> f.runtime().snapshot(f.id())); Files.write(file, original);
        Files.writeString(file.resolveSibling(file.getFileName() + ".pending"), "{}");
        assertThrows(RuntimeException.class, () -> f.runtime().snapshot(f.id()));
    }
    @Test void concurrentParticipantsCannotSpendOneReadTwice() throws Exception {
        var f = fixture(1); var ready = new CyclicBarrier(2); var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (String participant : List.of("root", "worker")) futures.add(executor.submit(() -> {
                ready.await(10, TimeUnit.SECONDS);
                try { page(f, participant, "lookup", "source/"); return true; }
                catch (CoordinationRuntime.Stop e) { assertEquals("READ_BUDGET_EXHAUSTED", e.getMessage()); return false; }
            }));
            assertEquals(1, (futures.get(0).get(20, TimeUnit.SECONDS) ? 1 : 0) + (futures.get(1).get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, reads(f.runtime().snapshot(f.id())));
        } finally { executor.shutdownNow(); }
    }
    @Test void copyingAnOldRegistrationIntoTheNewNamespaceCannotExpandItsToolGrant() throws Exception {
        var c = capture(); var old = CoordinationRuntime.forInvoice(root, c.directory(), c.bundle().identity(), clock);
        var p = CoordinationRuntime.invoiceReadPolicy(10, List.of("root"));
        String id = old.register(c.bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(c.bundle().identity(), p), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), p);
        var navigation = CoordinationRuntime.forInvoiceNavigation(root, c.directory(), c.bundle().identity(), clock);
        Path target = Files.createDirectory(root.resolve("invoice-navigation-trials/" + id));
        Files.copy(root.resolve("invoice-comparison-trials/" + id + "/spec.json"), target.resolve("spec.json"));
        Files.copy(root.resolve("invoice-comparison-trials/" + id + "/grant.json"), target.resolve("grant.json"));
        assertThrows(IllegalArgumentException.class, () -> navigation.catalogPrefix(id, "root", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        assertFalse(Files.exists(target.resolve("started.json")));
    }
    @Test void completedNavigationRetainsAccountingAndDoesNotEstablishAnswerQuality() throws Exception {
        var f = fixture(2); page(f, "root", "lookup", "source/"); var result = f.runtime().finish(f.id(), CoordinationTrial.Status.COMPLETED);
        assertEquals("runtime-accounting-not-quality-review", result.get("authority").getAsString());
        assertEquals(0, result.getAsJsonObject("usage").get("caseExecutions").getAsInt());
        var reopened = CoordinationRuntime.forInvoiceNavigation(root, f.capture().directory(), f.capture().bundle().identity(), clock);
        assertEquals(result, reopened.retained(f.id()));
        assertEquals("TRIAL_FINISHED", assertThrows(CoordinationRuntime.Stop.class, () -> page(f, "root", "lookup", "source/")).getMessage());
    }
    @Test void aHandBuiltPolicyCannotRaiseTheNavigationReadCeiling() throws Exception {
        var f = fixture(10); var p = f.policy();
        assertThrows(IllegalArgumentException.class, () -> CoordinationRuntime.invoiceNavigationReadPolicy(101, List.of("root")));
        var expanded = new CoordinationRuntime.Policy(p.parallelCalls(), 101, p.adapterIdentity(), p.promptIdentity(), p.toolPolicyIdentity(), p.contextPolicyIdentity(), p.finalSelection(), p.participants());
        assertThrows(IllegalArgumentException.class, () -> f.runtime().register(f.capture().bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(f.capture().bundle().identity(), expanded), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), expanded));
    }
    @Test void changedBuildReferencesAllowInspectionButCannotRegisterOrStartNewReads() throws Exception {
        var f = fixture(10); var p = f.policy();
        var stale = new CoordinationRuntime.Policy(p.parallelCalls(), p.maxReads(), new ContentDigest("0".repeat(64)), p.promptIdentity(),
                p.toolPolicyIdentity(), p.contextPolicyIdentity(), p.finalSelection(), p.participants());
        var definition = InvoiceComparisonRuntimeMain.definition(f.capture().bundle().identity(), stale);
        assertThrows(IllegalArgumentException.class, () -> f.runtime().register(f.capture().bundle().identity().sha256(), definition, new Slot("invoice-joint", 1, Arm.DETERMINISTIC), stale));
        Path file = trial(f).resolve("spec.json"); var spec = CaseInputs.object(Files.readAllBytes(file));
        spec.add("policy", CaseInputs.JSON.toJsonTree(stale)); spec.add("definition", CaseInputs.JSON.toJsonTree(definition));
        Files.writeString(file, spec.toString());
        Files.writeString(trial(f).resolve("grant.json"), CaseInputs.JSON.toJson(Map.of("permission", "registered-coordination-trial", "specSha256", CaseInputs.hash(Files.readAllBytes(file)))));
        var reopened = CoordinationRuntime.forInvoiceNavigation(root, f.capture().directory(), f.capture().bundle().identity(), clock);
        assertEquals(0, reads(reopened.snapshot(f.id())));
        assertThrows(IllegalArgumentException.class, () -> reopened.catalogPrefix(f.id(), "root", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        assertFalse(Files.exists(trial(f).resolve("started.json"))); assertEquals(0, reads(reopened.snapshot(f.id())));
    }
    @Test void versionOneFactoriesAndDecisionSchemaStillRefusePrefixActions() throws Exception {
        var c = capture(); var policy = CoordinationRuntime.invoiceReadPolicy(10, List.of("root"));
        var old = CoordinationRuntime.forInvoice(root, c.directory(), c.bundle().identity(), clock);
        String id = old.register(c.bundle().identity().sha256(), InvoiceComparisonRuntimeMain.definition(c.bundle().identity(), policy), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), policy);
        assertThrows(IllegalArgumentException.class, () -> old.catalogPrefix(id, "root", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        var oldInvestigation = CoordinationRuntime.forInvoiceInvestigation(root, c.directory(), c.bundle().identity(), clock, (s, p) -> { throw new AssertionError("No broker expected"); });
        assertThrows(IllegalArgumentException.class, () -> oldInvestigation.catalogPrefix(id, "root", "lookup", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        assertThrows(IllegalArgumentException.class, () -> InvoiceInvestigationDecision.parse("{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"source/\",\"offset\":0,\"limit\":40}}"));
        assertEquals(0, reads(old.snapshot(id)));
    }
    @Test void queriesStayWithinTheRegisteredParticipantCaseAndInput() throws Exception {
        var f = fixture(10);
        assertThrows(IllegalArgumentException.class, () -> page(f, "stranger", "lookup", "source/"));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().catalogPrefix(f.id(), "root", "lookup", CaseInputs.CASE_ID, "source/", 0, 40));
        assertThrows(IllegalArgumentException.class, () -> CoordinationRuntime.forInvoiceNavigation(root, f.capture().directory(), new ContentDigest("0".repeat(64))));
        assertEquals(0, reads(f.runtime().snapshot(f.id())));
        var first = page(f, "root", "lookup", "source/"); var second = page(f, "worker", "lookup", "source/");
        assertEquals(first.get("page"), second.get("page")); assertEquals(2, reads(f.runtime().snapshot(f.id())));
    }
    @Test void returnedRowsAndLaterDiskChangesCannotModifyAnOpenCapturedView() throws Exception {
        var f = fixture(10); var returned = page(f, "root", "lookup", "source/"); var original = returned.deepCopy();
        returned.getAsJsonObject("page").getAsJsonArray("artifacts").remove(0);
        Files.writeString(f.capture().directory().resolve("0001.txt"), "changed after capture");
        assertEquals(original, page(f, "root", "lookup", "source/"));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.forInvoiceNavigation(root, f.capture().directory(), f.capture().bundle().identity(), clock));
    }
}
