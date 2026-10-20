package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceComparisonInputsTest {
    @TempDir Path temporary;
    private record Captured(InvoiceCaseCoverage.Plan plan, InvoiceCaseCoverage.Run run, Path directory, InvoiceComparisonInputs.Bundle bundle) {}
    private Captured captured() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), temporary.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        Path output = temporary.resolve("inputs"); return new Captured(plan, run, output, InvoiceComparisonInputs.capture(plan, run, output));
    }
    static List<String> aliases(InvoiceComparisonInputs.Bundle bundle) {
        var result = new ArrayList<String>(); int offset = 0;
        do { var page = bundle.catalog(offset, 40); for (var value : page.getAsJsonArray("artifacts")) result.add(value.getAsJsonObject().get("alias").getAsString()); offset = page.get("nextOffset").getAsInt(); }
        while (offset < bundle.describe().get("artifactCount").getAsInt()); return result;
    }
    static String content(InvoiceComparisonInputs.Bundle bundle, String alias) {
        var text = new StringBuilder(); int offset = 0;
        do { var page = bundle.read(alias, offset, 4000); text.append(page.get("content").getAsString()); offset = page.get("nextOffset").getAsInt(); if (offset == page.get("totalCharacters").getAsInt()) break; }
        while (true); return text.toString();
    }
    static void noGrading(JsonElement value) {
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("input")) { assertEquals("untrusted-authored-synthetic", value.getAsJsonObject().get("inputAuthority").getAsString()); continue; }
            assertFalse(Set.of("expected", "expectedRuntime", "expectedOutcome", "expectedFinalEffects", "expectedCompilation", "expectedBlock", "assertionOutcome", "identitiesMatch", "finalEffectsMatch", "businessDeliveryOutcome", "executionStageOutcome", "admissionStageOutcome").contains(entry.getKey()), "Protected field: " + entry.getKey()); noGrading(entry.getValue());
        } else if (value.isJsonArray()) for (var row : value.getAsJsonArray()) noGrading(row);
    }
    @Test void completeInventoryHasOnlyCapturedSourcesInputsAndObservedEvidence() throws Exception {
        var c = captured(); var bundle = c.bundle(); var aliases = aliases(bundle); assertEquals(444, aliases.size());
        assertEquals(100, aliases.stream().filter(a -> a.startsWith("business/")).count()); assertEquals(188, aliases.stream().filter(a -> a.startsWith("order/")).count());
        assertEquals(26, aliases.stream().filter(a -> a.startsWith("admission/")).count()); assertEquals(10, aliases.stream().filter(a -> a.startsWith("execution/")).count());
        assertEquals(102, aliases.stream().filter(a -> a.startsWith("boundary/")).count()); assertEquals(15, aliases.stream().filter(a -> a.startsWith("source/")).count());
        assertFalse(bundle.describe().get("sourceExecutionCoverageSatisfied").getAsBoolean()); assertFalse(bundle.describe().get("comparisonAdaptersAvailable").getAsBoolean());
        for (String alias : aliases) if (!alias.startsWith("source/")) {
            try { noGrading(JsonParser.parseString(content(bundle, alias))); }
            catch (AssertionError e) { throw new AssertionError("Protected field in disclosure alias: " + alias, e); }
        }
        assertEquals(InvoiceOrderedCoverage.identity(c.run().json()), bundle.describe().get("sourceCaptureIdentity").getAsString());
        assertFalse(aliases.stream().anyMatch(a -> a.contains("oracle") || a.contains("expectations") || a.contains("rubric")));
        try (var files = Files.list(c.directory())) { assertEquals(446, files.count()); }
        var absent = JsonParser.parseString(content(bundle, aliases.stream().filter(a -> a.startsWith("business/")).findFirst().orElseThrow())).getAsJsonObject();
        assertEquals("NOT_ATTEMPTED", absent.get("availability").getAsString()); assertTrue(absent.get("observation").isJsonNull());
        var forged = JsonParser.parseString(content(bundle, "admission/forged-payload-permissions")).getAsJsonObject();
        assertEquals("PASSED", forged.getAsJsonObject("input").getAsJsonObject("untrustedClaims").get("assertionOutcome").getAsString());
        assertFalse(forged.has("assertionOutcome")); assertEquals("NOT_ATTEMPTED", forged.get("availability").getAsString());
    }
    @Test void originalPlanMembershipIsRequiredBeforeAnyDisclosureDirectoryIsCreated() throws Exception {
        var c = captured(); Path wrong = temporary.resolve("wrong");
        assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.capture(InvoiceCaseCoverage.load(InvoiceSandboxTest.root()), c.run(), wrong)); assertFalse(Files.exists(wrong));
        assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.capture(c.plan(), null, wrong)); assertFalse(Files.exists(wrong));
    }
    @Test void independentViewsReconstructTheSameBoundBytesThroughPagedReads() throws Exception {
        var c = captured(); var reopened = InvoiceComparisonInputs.open(c.directory(), c.bundle().identity());
        assertEquals(c.bundle().identity(), reopened.identity()); assertEquals(aliases(c.bundle()), aliases(reopened));
        for (String alias : aliases(c.bundle())) {
            String first = content(c.bundle(), alias), second = content(reopened, alias); assertEquals(CaseInputs.hash(first.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CaseInputs.hash(second.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        var description = c.bundle().describe(); description.addProperty("sourceExecutionCoverageSatisfied", true); c.bundle().catalog(0, 40).getAsJsonArray("artifacts").remove(0);
        assertFalse(c.bundle().describe().get("sourceExecutionCoverageSatisfied").getAsBoolean()); assertEquals(40, c.bundle().catalog(0, 40).getAsJsonArray("artifacts").size());
    }
    @Test void catalogAndReadLimitsRejectTraversalUnknownAliasesAndInvalidOffsets() throws Exception {
        var bundle = captured().bundle(); String alias = aliases(bundle).getFirst();
        for (String rejected : Arrays.asList(null, "../manifest.json", "receipt.json", "C:/secrets", "task/../../task", "unknown", "business")) assertThrows(IllegalArgumentException.class, () -> bundle.read(rejected, 0, 4000));
        assertThrows(IllegalArgumentException.class, () -> bundle.read(alias, -1, 1)); assertThrows(IllegalArgumentException.class, () -> bundle.read(alias, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> bundle.read(alias, 0, 4001)); assertThrows(IllegalArgumentException.class, () -> bundle.read(alias, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> bundle.catalog(0, 41)); assertThrows(IllegalArgumentException.class, () -> bundle.catalog(-1, 1)); assertThrows(IllegalArgumentException.class, () -> bundle.catalog(445, 1));
    }
    @Test void changedDiskBytesCannotChangeAnOpenViewAndFailReopen() throws Exception {
        var c = captured(); String before = content(c.bundle(), aliases(c.bundle()).getFirst()); Files.writeString(c.directory().resolve("0001.txt"), "changed");
        assertEquals(before, content(c.bundle(), aliases(c.bundle()).getFirst())); assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), c.bundle().identity()));
    }
    @Test void rewritingTheManifestAndReceiptCannotChangeTheExpectedExternalIdentity() throws Exception {
        var c = captured(); var original = c.bundle().identity(); Path manifest = c.directory().resolve("manifest.json"); var data = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        data.addProperty("sourceExecutionCoverageSatisfied", true); Files.writeString(manifest, data.toString()); var receipt = new JsonObject(); receipt.addProperty("manifestSha256", CaseInputs.hash(Files.readAllBytes(manifest))); Files.writeString(c.directory().resolve("receipt.json"), receipt.toString());
        assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), original)); assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), new ContentDigest("0".repeat(64))));
        String duplicate = data.toString(); duplicate = duplicate.substring(0, duplicate.length() - 1) + ",\"policy\":\"duplicate\"}"; Files.writeString(manifest, duplicate);
        String changed = CaseInputs.hash(Files.readAllBytes(manifest)); receipt.addProperty("manifestSha256", changed); Files.writeString(c.directory().resolve("receipt.json"), receipt.toString());
        assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), new ContentDigest(changed)));
        for (boolean invalidVersion : List.of(false, true)) {
            data.addProperty("sourceExecutionCoverageSatisfied", "true"); if (invalidVersion) { data.addProperty("sourceExecutionCoverageSatisfied", true); data.addProperty("formatVersion", 1.5); }
            Files.writeString(manifest, data.toString()); String invalid = CaseInputs.hash(Files.readAllBytes(manifest)); receipt.addProperty("manifestSha256", invalid); Files.writeString(c.directory().resolve("receipt.json"), receipt.toString());
            assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), new ContentDigest(invalid)));
        }
    }
    @Test void incompleteOrExtraArtifactsCannotBeOpenedOrOverwriteHistory() throws Exception {
        var c = captured(); byte[] manifest = Files.readAllBytes(c.directory().resolve("manifest.json"));
        assertThrows(FileAlreadyExistsException.class, () -> InvoiceComparisonInputs.capture(c.plan(), c.run(), c.directory())); assertArrayEquals(manifest, Files.readAllBytes(c.directory().resolve("manifest.json")));
        Files.writeString(c.directory().resolve("extra.txt"), "unregistered"); assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), c.bundle().identity()));
        Files.delete(c.directory().resolve("extra.txt"));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            Path file = c.directory().resolve("0001.txt"), backup = temporary.resolve("saved.txt"); Files.copy(file, backup); Files.delete(file); Files.createSymbolicLink(file, backup);
            assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), c.bundle().identity())); Files.delete(file); Files.copy(backup, file);
        }
        Files.delete(c.directory().resolve("receipt.json")); assertThrows(IllegalArgumentException.class, () -> InvoiceComparisonInputs.open(c.directory(), c.bundle().identity()));
    }
    @Test void capturedProviderReferencesAndConsumerBytesStayImmutable() throws Exception {
        var c = captured(); byte[] reference = c.plan().provenance(); reference[0] ^= 1; assertFalse(Arrays.equals(reference, c.plan().provenance()));
        for (var build : c.plan().builds().builds()) for (var source : c.plan().builds().workerSources(build.id()).entrySet()) assertEquals(source.getValue(), content(c.bundle(), "source/" + build.id() + "/" + source.getKey()));
        var provider = JsonParser.parseString(content(c.bundle(), "provider-references")).getAsJsonObject(); assertFalse(provider.has("sourcesRetrieved")); assertTrue(provider.getAsJsonArray("sources").size() > 0);
    }
}
