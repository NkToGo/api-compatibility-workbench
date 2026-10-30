package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.runner.InvoiceReadiness.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceContextV2Test {
    @TempDir static Path captureRoot;
    @TempDir Path state;
    private static InvoiceComparisonInputs.Bundle bundle;
    private static Path inputDirectory;
    private static Subject subject;
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), captureRoot.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        inputDirectory = captureRoot.resolve("inputs"); bundle = InvoiceComparisonInputs.capture(plan, run, inputDirectory);
        subject = new Subject("invoice-investigator", bundle.identity(), Scope.JOINT);
    }
    private static JsonObject record(Subject owner, String id, String kind, String alias, int offset, int limit) {
        JsonObject page = switch (kind) {
            case "describe" -> bundle.describe();
            case "catalog" -> bundle.catalog(offset, limit);
            case "catalog_prefix" -> bundle.catalogPrefix(alias, offset, limit);
            case "read_text" -> bundle.read(alias, offset, limit);
            default -> throw new IllegalArgumentException("Unknown test page");
        };
        return CaseInputs.JSON.toJsonTree(Map.of("participant", owner.participantId(), "operationId", id,
                "caseId", InvoiceRenewalFixtures.CASE_ID, "bundleIdentity", owner.inputIdentity().sha256(),
                "pageRequest", new ComparisonEvidence.PageRequest(kind, InvoiceRenewalFixtures.CASE_ID, alias, offset, limit),
                "page", page, "contentTrust", "untrusted-data")).getAsJsonObject();
    }
    private static JsonObject bootstrap(Subject owner) { return record(owner, "metadata", "describe", "", 0, 0); }
    private static JsonObject text(int id, String alias) { return record(subject, "read-" + id, "read_text", alias, 0, 4000); }
    private static String build(List<JsonObject> reads, int limit) {
        return InvoiceContextV2.build(subject, bundle, bootstrap(subject), reads, new InvoiceContextV2.Scheduling(2, 2), limit);
    }
    private static JsonObject context(List<JsonObject> reads) { return JsonParser.parseString(build(reads, 64000)).getAsJsonObject(); }
    @Test void publicTaskAndHistoricalCaptureAreExplicitlySeparate() {
        var result = context(List.of()); var capture = result.getAsJsonObject("captureMetadata");
        assertFalse(capture.get("comparisonAdaptersAvailable").getAsBoolean()); assertEquals(0, capture.get("modelCalls").getAsInt());
        assertEquals("historical-capture-not-current-runtime-capabilities", result.get("captureMetadataAuthority").getAsString());
        assertEquals(InvoiceDecisionV2.PUBLIC_TASK, result.getAsJsonObject("publicTask").get("content").getAsString());
        assertEquals(InvoiceDecisionV2.taskIdentity(), result.getAsJsonObject("publicTask").get("sha256").getAsString());
        assertEquals(InvoiceDecisionV2.contractIdentity(Scope.JOINT), result.get("contractIdentity").getAsString());
        assertFalse(result.has("availableTools")); assertFalse(result.has("permission"));
        assertEquals("NOT_PERFORMED", result.get("qualityReview").getAsString());
        assertFalse(result.getAsJsonObject("readiness").get("structuralAccessReady").getAsBoolean());
    }
    @Test void cataloguePagesStayNonCitableWhileOwnTextPagesAreCitable() {
        var result = context(List.of(record(subject, "read-1", "catalog", "", 0, 40),
                record(subject, "read-2", "catalog_prefix", "source/", 0, 40), text(3, "task")));
        var ready = result.getAsJsonObject("readiness");
        assertEquals(JsonParser.parseString("[\"read-3\"]"), ready.get("citableTextPageIds"));
        assertEquals(JsonParser.parseString("[\"read-1\",\"read-2\"]"), ready.get("nonCitableCataloguePageIds"));
        var metadata = result.getAsJsonArray("readMetadata");
        assertEquals("source/", metadata.get(1).getAsJsonObject().get("prefix").getAsString());
        for (var value : metadata) { assertFalse(value.getAsJsonObject().has("artifacts")); assertFalse(value.getAsJsonObject().has("content")); }
        assertEquals(3, result.getAsJsonArray("recentPages").size());
    }
    @Test void roleContextsUseTheSameQuestionsButOnlyTheirOwnStructuralSourceRequirement() {
        for (var scope : List.of(Scope.WEBHOOK, Scope.RECONCILIATION)) {
            var owner = new Subject("role-worker", bundle.identity(), scope);
            var result = JsonParser.parseString(InvoiceContextV2.build(owner, bundle, bootstrap(owner), List.of(), new InvoiceContextV2.Scheduling(12, 2), 64000)).getAsJsonObject();
            assertEquals(InvoiceDecisionV2.taskIdentity(), result.getAsJsonObject("publicTask").get("sha256").getAsString());
            assertEquals(3, result.getAsJsonObject("readiness").getAsJsonArray("requiredReads").size());
            assertEquals(InvoiceDecisionV2.protocol(scope), result.get("protocol").getAsString());
        }
    }
    @Test void requiredReadCoverageDoesNotEraseAnIncompleteExecutionCapture() {
        var aliases = new ArrayList<>(InvoiceInvestigationDecision.REQUIRED); Collections.sort(aliases);
        var pages = new ArrayList<JsonObject>(); int id = 0;
        for (String alias : aliases) {
            int offset = 0; JsonObject page;
            do { page = record(subject, "read-" + ++id, "read_text", alias, offset, 4000); pages.add(page);
                offset = page.getAsJsonObject("page").get("nextOffset").getAsInt(); }
            while (offset < page.getAsJsonObject("page").get("totalCharacters").getAsInt());
        }
        var readiness = context(pages).getAsJsonObject("readiness");
        readiness.getAsJsonArray("requiredReads").forEach(p -> assertEquals("COMPLETE", p.getAsJsonObject().get("state").getAsString()));
        assertFalse(readiness.get("sourceExecutionCoverageSatisfied").getAsBoolean()); assertFalse(readiness.get("structuralAccessReady").getAsBoolean());
    }
    @Test void changedOwnerInputOrCaseCannotSupplyReadinessOrCitations() {
        for (String field : List.of("participant", "bundleIdentity", "caseId", "contentTrust")) {
            var page = text(1, "task"); page.addProperty(field, "changed");
            assertThrows(IllegalArgumentException.class, () -> context(List.of(page)));
        }
        var other = new Subject(subject.participantId(), new ContentDigest("f".repeat(64)), Scope.JOINT);
        assertThrows(IllegalArgumentException.class, () -> InvoiceContextV2.build(other, bundle, bootstrap(other), List.of(), new InvoiceContextV2.Scheduling(1, 1), 64000));
    }
    @Test void bootstrapCannotLieAboutCaptureCoverageOrCurrentAvailability() {
        var page = bootstrap(subject); page.getAsJsonObject("page").addProperty("sourceExecutionCoverageSatisfied", true);
        assertThrows(IllegalArgumentException.class, () -> InvoiceContextV2.build(subject, bundle, page, List.of(), new InvoiceContextV2.Scheduling(1, 1), 64000));
        assertThrows(IllegalArgumentException.class, () -> InvoiceContextV2.build(subject, bundle, text(1, "task"), List.of(), new InvoiceContextV2.Scheduling(1, 1), 64000));
        assertThrows(IllegalArgumentException.class, () -> context(List.of(bootstrap(subject))));
    }
    @Test void alteredPayloadHashOffsetsOrAliasAreRejectedAgainstCaptureBytes() {
        for (String field : List.of("content", "sha256", "alias", "offset", "nextOffset", "totalCharacters")) {
            var page = text(1, "task"); var content = page.getAsJsonObject("page");
            if (field.endsWith("Offset") || field.equals("offset") || field.equals("totalCharacters")) content.addProperty(field, -1);
            else content.addProperty(field, "changed");
            assertThrows(IllegalArgumentException.class, () -> context(List.of(page)));
        }
        var page = text(1, "task"); page.getAsJsonObject("pageRequest").addProperty("alias", "build-configuration");
        assertThrows(IllegalArgumentException.class, () -> context(List.of(page)));
    }
    @Test void catalogueRowsAndPrefixRequestAreVerifiedNotTrustedFromModelData() {
        var page = record(subject, "read-1", "catalog_prefix", "source/", 0, 40);
        page.getAsJsonObject("page").getAsJsonArray("artifacts").get(0).getAsJsonObject().addProperty("alias", "invented");
        assertThrows(IllegalArgumentException.class, () -> context(List.of(page)));
        var other = record(subject, "read-1", "catalog_prefix", "source/", 0, 40);
        other.getAsJsonObject("pageRequest").addProperty("alias", "order/");
        assertThrows(IllegalArgumentException.class, () -> context(List.of(other)));
    }
    @Test void malformedWrapperExtraPermissionAndExpandedQueriesAreRejected() {
        var extra = text(1, "task"); extra.addProperty("permission", "all");
        var query = text(1, "task"); query.getAsJsonObject("pageRequest").addProperty("execute", true);
        var fraction = text(1, "task"); fraction.getAsJsonObject("pageRequest").addProperty("offset", 0.5);
        var unavailable = text(1, "task"); unavailable.getAsJsonObject("pageRequest").addProperty("kind", "execute");
        for (var page : List.of(extra, query, fraction, unavailable)) assertThrows(IllegalArgumentException.class, () -> context(List.of(page)));
    }
    @Test void readOrderDuplicatesAndReadCeilingCannotSilentlyDropMetadata() {
        assertThrows(IllegalArgumentException.class, () -> context(List.of(text(1, "task"), text(1, "task"))));
        assertThrows(IllegalArgumentException.class, () -> context(List.of(text(2, "task"), text(1, "task"))));
        assertThrows(IllegalArgumentException.class, () -> context(Collections.nCopies(101, text(1, "task"))));
        assertEquals(2, context(List.of(text(1, "task"), text(3, "task"))).getAsJsonArray("readMetadata").size());
    }
    @Test void contextsAreDetachedAndRetainOnlyLatestSixPageContents() {
        var pages = new ArrayList<JsonObject>(); for (int i = 1; i <= 8; i++) pages.add(text(i, "task"));
        String before = CaseInputs.JSON.toJson(pages); var result = context(pages);
        assertEquals(8, result.getAsJsonArray("readMetadata").size()); var recent = result.getAsJsonArray("recentPages");
        assertEquals(6, recent.size()); assertEquals("read-3", recent.get(0).getAsJsonObject().get("pageId").getAsString());
        assertEquals(before, CaseInputs.JSON.toJson(pages));
        recent.get(0).getAsJsonObject().addProperty("content", "changed"); assertEquals(before, CaseInputs.JSON.toJson(pages));
    }
    @Test void byteTrimmingRemovesOldContentsWithoutLosingMetadataOrReadiness() {
        var pages = new ArrayList<JsonObject>(); for (int i = 1; i <= 6; i++) pages.add(text(i, "task"));
        String full = build(pages, 64000); int limit = full.getBytes(StandardCharsets.UTF_8).length - 1;
        String bounded = build(pages, limit); var result = JsonParser.parseString(bounded).getAsJsonObject();
        assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= limit); assertEquals(5, result.getAsJsonArray("recentPages").size());
        assertEquals("read-2", result.getAsJsonArray("recentPages").get(0).getAsJsonObject().get("pageId").getAsString());
        assertEquals(6, result.getAsJsonArray("readMetadata").size());
        assertEquals(6, result.getAsJsonObject("readiness").getAsJsonArray("citableTextPageIds").size());
        assertThrows(CoordinationRuntime.Stop.class, () -> build(pages, 1));
        for (int invalid : List.of(0, 64001)) assertThrows(IllegalArgumentException.class, () -> build(pages, invalid));
    }
    @Test void sharedCallsAreNotMisrepresentedAsEachWorkersRemainingTurns() {
        var last = new InvoiceContextV2.Scheduling(2, 2).toJson();
        assertEquals(2, last.get("currentRoundReservations").getAsInt());
        assertEquals(1, last.get("currentParticipantScheduledDecisions").getAsInt());
        assertEquals(0, last.get("futureFullRoundsIfPeersStayActive").getAsInt());
        assertTrue(last.get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertEquals("NONE", last.get("laterSchedulingIfPeersStayActive").getAsString());
        var partial = new InvoiceContextV2.Scheduling(3, 2).toJson();
        assertEquals(0, partial.get("futureFullRoundsIfPeersStayActive").getAsInt());
        assertEquals(1, partial.get("futurePartialRoundReservations").getAsInt());
        assertFalse(partial.get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
        assertEquals("PARTIAL_ROUND_UNASSIGNED", partial.get("laterSchedulingIfPeersStayActive").getAsString());
        assertFalse(partial.get("futureTurnGuaranteed").getAsBoolean());
        assertEquals(11, new InvoiceContextV2.Scheduling(24, 2).toJson().get("futureFullRoundsIfPeersStayActive").getAsInt());
        assertEquals(11, new InvoiceContextV2.Scheduling(12, 1).toJson().get("futureFullRoundsIfPeersStayActive").getAsInt());
    }
    @Test void schedulingRefusesUnavailableCallsAndUnsupportedConcurrency() {
        for (int calls : List.of(0, -1, 61)) assertThrows(IllegalArgumentException.class, () -> new InvoiceContextV2.Scheduling(calls, 1));
        for (int active : List.of(0, -1, 3)) assertThrows(IllegalArgumentException.class, () -> new InvoiceContextV2.Scheduling(10, active));
        assertTrue(new InvoiceContextV2.Scheduling(1, 2).toJson().get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean());
    }
    @Test void actualReadOnlyRuntimeWrappersFitTheContractWithoutEnablingModelAccess() throws Exception {
        var runtime = CoordinationRuntime.forInvoiceNavigation(state, inputDirectory, bundle.identity(), new CoordinationRuntimeTest.MutableClock());
        var policy = CoordinationRuntime.invoiceNavigationReadPolicy(3, List.of(subject.participantId()));
        var definition = InvoiceComparisonRuntimeMain.definition(bundle.identity(), policy);
        String id = runtime.register(bundle.identity().sha256(), definition,
                new io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.Slot("invoice-joint", 1,
                        io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.Arm.DETERMINISTIC), policy);
        var metadata = runtime.describe(id, subject.participantId(), "metadata", InvoiceRenewalFixtures.CASE_ID);
        var page = runtime.catalogPrefix(id, subject.participantId(), "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40);
        var result = JsonParser.parseString(InvoiceContextV2.build(subject, bundle, metadata, List.of(page), new InvoiceContextV2.Scheduling(1, 1), 64000)).getAsJsonObject();
        assertEquals(1, result.getAsJsonArray("readMetadata").size());
        assertEquals(2, runtime.snapshot(id).getAsJsonObject("usage").get("toolCalls").getAsInt());
        assertTrue(policy.participants().stream().allMatch(p -> p.protocols().isEmpty()));
    }
}
