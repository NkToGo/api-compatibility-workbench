package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.runner.InvoiceTeamReviewV2.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTeamReviewV2Test {
    @TempDir static Path root;
    private static InvoiceComparisonInputs.Bundle inputs;
    private static final String RECON = "invoice-reconciliation", WEBHOOK = "invoice-webhook", COORDINATOR = "invoice-coordinator-v2";
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        inputs = InvoiceComparisonInputs.capture(plan, run, root.resolve("inputs"));
    }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject record(String participant, String id, String kind, String alias, int offset, int limit) {
        JsonObject page = switch (kind) {
            case "describe" -> inputs.describe();
            case "catalog" -> inputs.catalog(offset, limit);
            case "catalog_prefix" -> inputs.catalogPrefix(alias, offset, limit);
            case "read_text" -> inputs.read(alias, offset, limit);
            default -> throw new IllegalArgumentException("Unknown fixture read");
        };
        return json(Map.of("participant", participant, "operationId", id, "caseId", InvoiceRenewalFixtures.CASE_ID,
                "bundleIdentity", inputs.identity().sha256(), "pageRequest", new ComparisonEvidence.PageRequest(kind, InvoiceRenewalFixtures.CASE_ID, alias, offset, limit),
                "page", page, "contentTrust", "untrusted-data"));
    }
    private static JsonObject bootstrap(String participant) { return record(participant, "metadata", "describe", "", 0, 0); }
    private static JsonObject text(String participant, int id, String alias) { return record(participant, "read-" + id, "read_text", alias, 0, 4000); }
    private static JsonObject finish(String summary) {
        return json(Map.of("action", "finish", "response", "ABSTAINED", "summary", summary, "summaryCitations", List.of("read-1"),
                "findings", List.of(Map.of("kind", "LIMITATION", "statement", "Retained worker limitation", "citations", List.of("read-1")))));
    }
    private static WorkerResult workerResult(String worker, ContentDigest identity, WorkerStatus status, String reason, JsonObject finish, JsonObject bootstrap, List<JsonObject> reads) {
        var scope = worker.equals(WEBHOOK) ? InvoiceReadiness.Scope.WEBHOOK : InvoiceReadiness.Scope.RECONCILIATION;
        return new WorkerResult(worker, identity, InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope), InvoiceDecisionV2.taskIdentity(), status, reason, finish, bootstrap, reads);
    }
    private static WorkerResult completed(String worker) {
        return workerResult(worker, inputs.identity(), WorkerStatus.COMPLETED, "NONE", finish("Original " + worker),
                bootstrap(worker), List.of(text(worker, 1, "task")));
    }
    private static InvoiceTeamReviewV2 review() { return new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(completed(RECON), completed(WEBHOOK))); }
    private static JsonObject question(String worker, String... refs) { return json(Map.of("workerId", worker, "question", "Which captured evidence establishes the scope?", "citations", List.of(refs))); }
    private static JsonObject conflict(String... ids) { return json(Map.of("claimIds", List.of(ids), "statement", "These claims may use different assumptions.")); }
    private static JsonObject decision(List<JsonObject> questions, List<JsonObject> conflicts, String... citations) {
        return json(Map.of("action", "finish", "summary", "Keep original outcomes and inspect the declared uncertainty.",
                "citations", citations.length == 0 ? List.of("worker-1") : List.of(citations), "followUps", questions, "conflicts", conflicts));
    }
    private static JsonObject select(InvoiceTeamReviewV2 review, JsonObject decision, List<JsonObject> reads) { return review.select(decision, bootstrap(COORDINATOR), reads); }
    private static JsonObject context(InvoiceTeamReviewV2 review, List<JsonObject> reads, int limit) {
        return JsonParser.parseString(review.context(bootstrap(COORDINATOR), reads, CoordinationTeamBudget.forCeiling(60),
                new CoordinationTeamBudget.Reservations(24, 0, 0, 0), limit)).getAsJsonObject();
    }
    @Test void allOriginalClaimsAndOutcomesRemainVisibleWithSeparateReadinessAndTaskIdentity() {
        var context = context(review(), List.of(), 64000);
        assertEquals(4, context.getAsJsonArray("originalClaims").size()); assertEquals(2, context.getAsJsonArray("workers").size());
        assertEquals(InvoiceTeamDecisionV2.PROTOCOL, context.get("protocol").getAsString());
        assertEquals(InvoiceTeamDecisionV2.contractIdentity(), context.get("contractIdentity").getAsString());
        assertEquals(InvoiceDecisionV2.taskIdentity(), context.getAsJsonObject("publicTask").get("sha256").getAsString());
        assertEquals(COORDINATOR, context.getAsJsonObject("readiness").getAsJsonObject("subject").get("participantId").getAsString());
        for (var value : context.getAsJsonArray("workers")) {
            var worker = value.getAsJsonObject(); assertEquals(worker.get("workerId"), worker.getAsJsonObject("readiness").getAsJsonObject("subject").get("participantId"));
            assertEquals("ABSTAINED", worker.get("response").getAsString()); assertFalse(worker.has("reads"));
        }
        for (var claim : context.getAsJsonArray("originalClaims")) {
            assertFalse(claim.getAsJsonObject().has("readiness")); assertEquals(JsonParser.parseString("[\"read-1\"]"), claim.getAsJsonObject().get("workerPageIds"));
        }
        assertEquals("NOT_PERFORMED", context.get("qualityReview").getAsString());
    }
    @Test void everyStoppedDispositionRemainsExplicitWithoutFabricatedClaims() {
        for (var status : List.of(WorkerStatus.FAILED, WorkerStatus.CANCELLED, WorkerStatus.UNKNOWN_REMOTE_OUTCOME)) {
            var stopped = workerResult(RECON, inputs.identity(), status, "MODEL_STOPPED", null, null, List.of());
            var review = new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(completed(WEBHOOK), stopped));
            var result = select(review, decision(List.of(question(RECON, "worker-1")), List.of()), List.of());
            assertEquals(2, result.getAsJsonArray("originalClaims").size());
            var first = result.getAsJsonArray("workers").get(0).getAsJsonObject();
            assertEquals(status.name(), first.get("status").getAsString()); assertEquals("NOT_PROVIDED", first.get("response").getAsString());
            assertTrue(first.get("readiness").isJsonNull()); assertEquals(1, result.getAsJsonArray("selectedFollowUps").size());
        }
    }
    @Test void questionsAreSortedInertAndAnchoredToTheirOriginalTargetRole() {
        var result = select(review(), decision(List.of(question(WEBHOOK, "claim-3", "claim-1"), question(RECON, "worker-1", "claim-3")),
                List.of(conflict("claim-1", "claim-3"))), List.of());
        var selected = result.getAsJsonArray("selectedFollowUps"); assertEquals(RECON, selected.get(0).getAsJsonObject().get("workerId").getAsString());
        assertEquals(WEBHOOK, selected.get(1).getAsJsonObject().get("workerId").getAsString());
        for (var proposal : selected) {
            assertEquals(1, proposal.getAsJsonObject().get("delegationDepth").getAsInt());
            assertEquals("selected-advisory-question-not-grant-or-evidence", proposal.getAsJsonObject().get("authority").getAsString());
            assertFalse(proposal.getAsJsonObject().has("permission"));
        }
        var conflict = result.getAsJsonArray("possibleConflicts").get(0).getAsJsonObject();
        assertEquals("UNRESOLVED", conflict.get("state").getAsString()); assertEquals("SAME_CASE_DIFFERENT_ROLES_SCOPE_UNESTABLISHED", conflict.get("comparability").getAsString());
        assertEquals(InvoiceDecisionV2.taskIdentity(), result.get("publicTaskSha256").getAsString());
    }
    @Test void siblingPagesAndCatalogueIdsCannotBecomeCoordinatorEvidenceOrTargetAnchors() {
        var ownText = text(COORDINATOR, 1, "task");
        for (String ref : List.of("read-1", "claim-99")) assertThrows(RuntimeException.class,
                () -> select(review(), decision(List.of(), List.of(), ref), List.of()));
        for (String ref : List.of("read-1", "worker-2", "claim-3")) assertThrows(RuntimeException.class,
                () -> select(review(), decision(List.of(question(RECON, ref)), List.of()), List.of(ownText)));
        var prefix = record(COORDINATOR, "read-1", "catalog_prefix", "source/", 0, 40);
        var flat = record(COORDINATOR, "read-1", "catalog", "", 0, 40);
        for (var catalogue : List.of(prefix, flat)) assertThrows(RuntimeException.class,
                () -> select(review(), decision(List.of(), List.of(), "read-1"), List.of(catalogue)));
    }
    @Test void ownTextReferencesBindCapturedWrapperValuesAndPrefixDiscoveryStaysMetadata() {
        var reads = List.of(record(COORDINATOR, "read-1", "catalog_prefix", "source/", 0, 40), text(COORDINATOR, 2, "task"));
        var result = select(review(), decision(List.of(question(RECON, "claim-1", "read-2")), List.of(), "read-2"), reads);
        assertFalse(result.getAsJsonObject("referenceIdentities").has("read-1"));
        assertEquals(CaseInputs.hash(InvoiceDecisionV2.canonical(reads.get(1)).getBytes(StandardCharsets.UTF_8)), result.getAsJsonObject("referenceIdentities").get("read-2").getAsString());
        var context = context(review(), reads, 64000); assertEquals(2, context.getAsJsonArray("readMetadata").size());
        assertEquals(JsonParser.parseString("[\"read-2\"]"), context.getAsJsonObject("readiness").get("citableTextPageIds"));
    }
    @Test void workerAndCoordinatorWrappersRejectForeignScopeChangedBytesAndExtraFeedback() {
        var badWorker = text(RECON, 1, "task"); badWorker.addProperty("participant", WEBHOOK);
        var worker = workerResult(RECON, inputs.identity(), WorkerStatus.COMPLETED, "NONE", finish("claim"), bootstrap(RECON), List.of(badWorker));
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(worker, completed(WEBHOOK))));
        var original = text(COORDINATOR, 1, "task");
        for (String field : List.of("participant", "bundleIdentity", "caseId", "contentTrust", "operationId")) {
            var changed = original.deepCopy(); changed.addProperty(field, "foreign");
            assertThrows(RuntimeException.class, () -> context(review(), List.of(changed), 64000));
        }
        var changed = original.deepCopy(); changed.getAsJsonObject("page").addProperty("content", "Changed evidence");
        assertThrows(RuntimeException.class, () -> select(review(), decision(List.of(), List.of()), List.of(changed)));
        var extra = bootstrap(COORDINATOR); extra.getAsJsonObject("page").addProperty("hidden-grade", "secret-grade");
        assertThrows(RuntimeException.class, () -> review().context(extra, List.of(), CoordinationTeamBudget.forCeiling(60), new CoordinationTeamBudget.Reservations(24, 0, 0, 0), 64000));
    }
    @Test void importedWorkersRequireBothRolesExactInputAndValidCompletedOrStoppedShapes() {
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(completed(RECON))));
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(completed(RECON), completed(RECON))));
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReviewV2(RECON, inputs, List.of(completed(RECON), completed(WEBHOOK))));
        var foreign = workerResult(RECON, new ContentDigest("a".repeat(64)), WorkerStatus.FAILED, "STOPPED", null, null, List.of());
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(foreign, completed(WEBHOOK))));
        assertThrows(RuntimeException.class, () -> workerResult(RECON, inputs.identity(), WorkerStatus.COMPLETED, "FAILED", finish("claim"), bootstrap(RECON), List.of()));
        assertThrows(RuntimeException.class, () -> workerResult(RECON, inputs.identity(), WorkerStatus.FAILED, "FAILED", finish("claim"), bootstrap(RECON), List.of()));
        assertThrows(RuntimeException.class, () -> workerResult(RECON, inputs.identity(), WorkerStatus.FAILED, "NONE", null, null, List.of()));
        assertThrows(RuntimeException.class, () -> workerResult(RECON, inputs.identity(), WorkerStatus.FAILED, "STOPPED", null, null, List.of(text(RECON, 1, "task"))));
        assertThrows(RuntimeException.class, () -> workerResult(RECON, inputs.identity(), WorkerStatus.FAILED, "STOPPED", null, bootstrap(RECON), Collections.nCopies(101, text(RECON, 1, "task"))));
    }
    @Test void completedAnswersAndAbstentionsRequireActualOwnedTextSupport() {
        var answer = finish("Unsupported answered dossier"); answer.addProperty("response", "ANSWERED");
        for (var d : List.of(answer, finish("Unseen citation"))) {
            var worker = workerResult(RECON, inputs.identity(), WorkerStatus.COMPLETED, "NONE", d, bootstrap(RECON), List.of());
            assertThrows(RuntimeException.class, () -> new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(worker, completed(WEBHOOK))));
        }
    }
    @Test void importedHistoriesRequireExactV2RoleAndPublicTaskAndExplicitMissingFields() {
        for (String protocol : List.of(InvoiceWorkerDecision.RECONCILIATION, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK)))
            assertThrows(RuntimeException.class, () -> new WorkerResult(RECON, inputs.identity(), protocol, InvoiceDecisionV2.contractIdentity(InvoiceReadiness.Scope.RECONCILIATION), InvoiceDecisionV2.taskIdentity(),
                    WorkerStatus.FAILED, "STOPPED", null, null, List.of()));
        assertThrows(RuntimeException.class, () -> new WorkerResult(RECON, inputs.identity(), InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.RECONCILIATION),
                InvoiceDecisionV2.contractIdentity(InvoiceReadiness.Scope.RECONCILIATION), "a".repeat(64), WorkerStatus.FAILED, "STOPPED", null, null, List.of()));
        assertThrows(RuntimeException.class, () -> new WorkerResult(RECON, inputs.identity(), InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.RECONCILIATION),
                InvoiceDecisionV2.contractIdentity(InvoiceReadiness.Scope.WEBHOOK), InvoiceDecisionV2.taskIdentity(), WorkerStatus.FAILED, "STOPPED", null, null, List.of()));
        var stopped = workerResult(RECON, inputs.identity(), WorkerStatus.FAILED, "STOPPED", null, null, List.of());
        var serializer = new GsonBuilder().serializeNulls().create(); var retained = serializer.toJsonTree(stopped).getAsJsonObject();
        assertEquals(retained, serializer.toJsonTree(retainedWorker(retained)));
        for (String field : List.of("protocol", "contractIdentity", "publicTaskSha256", "finishDecision", "bootstrap")) {
            var missing = retained.deepCopy(); missing.remove(field); assertThrows(RuntimeException.class, () -> retainedWorker(missing));
        }
    }
    @Test void originalReportIdentityIncludesStoppedStateAndPrefixReadHistoryWithoutDisclosingRawPages() {
        var hashes = new HashSet<String>();
        for (var status : List.of(WorkerStatus.FAILED, WorkerStatus.CANCELLED, WorkerStatus.UNKNOWN_REMOTE_OUTCOME)) {
            for (String prefix : List.of("source/", "business/")) {
                var worker = workerResult(RECON, inputs.identity(), status, "STOPPED", null, bootstrap(RECON),
                        List.of(record(RECON, "read-1", "catalog_prefix", prefix, 0, 40)));
                var context = context(new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(worker, completed(WEBHOOK))), List.of(), 64000);
                assertTrue(hashes.add(context.getAsJsonArray("workers").get(0).getAsJsonObject().get("reportIdentity").getAsString()));
                assertFalse(context.toString().contains("\"pageRequest\""));
            }
        }
    }
    @Test void cumulativeReviewAllowanceCarriesForwardButNeverCreatesNewTrialOrFinalizationCalls() {
        var review = review(); var allocation = CoordinationTeamBudget.forCeiling(60);
        var context = JsonParser.parseString(review.context(bootstrap(COORDINATOR), List.of(), allocation, new CoordinationTeamBudget.Reservations(11, 4, 0, 0), 64000)).getAsJsonObject();
        assertEquals(15, context.getAsJsonObject("teamStage").get("callsRemainingInReviewStage").getAsInt());
        assertEquals(15, context.getAsJsonObject("scheduling").get("sharedCallsRemainingBeforeRound").getAsInt());
        var last = JsonParser.parseString(review.context(bootstrap(COORDINATOR), List.of(), allocation, new CoordinationTeamBudget.Reservations(24, 5, 0, 0), 64000)).getAsJsonObject().getAsJsonObject("scheduling");
        assertTrue(last.get("lastScheduledDecisionCertainIfPeersStayActive").getAsBoolean()); assertTrue(last.get("finishConsumesDecision").getAsBoolean()); assertFalse(last.get("futureTurnGuaranteed").getAsBoolean());
        assertEquals("CALL_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class,
                () -> review.context(bootstrap(COORDINATOR), List.of(), allocation, new CoordinationTeamBudget.Reservations(24, 6, 0, 0), 64000)).getMessage());
        assertThrows(RuntimeException.class, () -> review.context(bootstrap(COORDINATOR), List.of(), allocation, new CoordinationTeamBudget.Reservations(24, 7, 0, 0), 64000));
        assertThrows(RuntimeException.class, () -> review.context(bootstrap(COORDINATOR), List.of(), allocation, new CoordinationTeamBudget.Reservations(24, 0, 1, 0), 64000));
        assertThrows(RuntimeException.class, () -> review.context(bootstrap(COORDINATOR), List.of(), CoordinationTeamBudget.forCeiling(100), new CoordinationTeamBudget.Reservations(0, 0, 0, 0), 64000));
        assertThrows(RuntimeException.class, () -> review.context(bootstrap(COORDINATOR), List.of(), new CoordinationTeamBudget.Allocation(24, 12, 18, 6), new CoordinationTeamBudget.Reservations(0, 0, 0, 0), 64000));
    }
    @Test void byteTrimmingKeepsAllOriginalClaimsReadMetadataAndReadinessOrExplicitlyStops() {
        var reads = new ArrayList<JsonObject>(); for (int i = 1; i <= 7; i++) reads.add(text(COORDINATOR, i, "task"));
        var full = context(review(), reads, 64000); int limit = InvoiceDecisionV2.canonical(full).getBytes(StandardCharsets.UTF_8).length - 1;
        var trimmed = context(review(), reads, limit);
        assertTrue(InvoiceDecisionV2.canonical(trimmed).getBytes(StandardCharsets.UTF_8).length <= limit);
        assertEquals(full.get("workers"), trimmed.get("workers")); assertEquals(full.get("originalClaims"), trimmed.get("originalClaims"));
        assertEquals(full.get("readiness"), trimmed.get("readiness")); assertEquals(7, trimmed.getAsJsonArray("readMetadata").size());
        assertEquals(6, full.getAsJsonArray("recentPages").size()); assertTrue(trimmed.getAsJsonArray("recentPages").size() < 6);
        assertEquals("CONTEXT_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> context(review(), reads, 1)).getMessage());
    }
    @Test void allFourteenLargeClaimsRemainVisibleWithoutRepeatedWorkerReadiness() {
        var originals = new ArrayList<WorkerResult>();
        for (String worker : InvoiceWorkerDecision.WORKERS) {
            var d = finish("é".repeat(1000)); var findings = new JsonArray();
            for (int i = 0; i < 6; i++) findings.add(json(Map.of("kind", "LIMITATION", "statement", "é".repeat(1000), "citations", List.of("read-1"))));
            d.add("findings", findings); originals.add(workerResult(worker, inputs.identity(), WorkerStatus.COMPLETED, "NONE", d, bootstrap(worker), List.of(text(worker, 1, "task"))));
        }
        var review = new InvoiceTeamReviewV2(COORDINATOR, inputs, originals); var context = context(review, List.of(), 64000);
        assertEquals(14, context.getAsJsonArray("originalClaims").size()); assertEquals(14, select(review, decision(List.of(), List.of()), List.of()).getAsJsonArray("originalClaims").size());
        assertThrows(CoordinationRuntime.Stop.class, () -> context(review, List.of(), 10000));
    }
    @Test void defensiveCopiesAndRetainedShapeChecksPreserveExactOriginalStatements() {
        var d = finish("Original statement"); var boot = bootstrap(RECON); var page = text(RECON, 1, "task");
        var worker = workerResult(RECON, inputs.identity(), WorkerStatus.COMPLETED, "NONE", d, boot, List.of(page));
        var review = new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(worker, completed(WEBHOOK))); var before = select(review, decision(List.of(), List.of()), List.of());
        d.addProperty("summary", "changed"); boot.addProperty("participant", "changed"); page.addProperty("participant", "changed");
        worker.finishDecision().addProperty("summary", "changed"); worker.bootstrap().addProperty("participant", "changed"); worker.reads().getFirst().addProperty("participant", "changed");
        var external = select(review, decision(List.of(), List.of()), List.of()); external.getAsJsonArray("originalClaims").get(0).getAsJsonObject().addProperty("statement", "changed");
        assertEquals(before, select(review, decision(List.of(), List.of()), List.of()));
        var retained = json(worker); assertEquals(retained, json(retainedWorker(retained)));
        retained.addProperty("permission", "dispatch"); assertThrows(RuntimeException.class, () -> retainedWorker(retained));
        assertEquals(context(review(), List.of(), 64000), context(new InvoiceTeamReviewV2(COORDINATOR, inputs, List.of(completed(WEBHOOK), completed(RECON))), List.of(), 64000));
    }
    @Test void conflictAndTargetSelectionCannotBeTurnedIntoPermissionOrResolution() {
        var same = select(review(), decision(List.of(), List.of(conflict("claim-1", "claim-2"))), List.of()).getAsJsonArray("possibleConflicts").get(0).getAsJsonObject();
        assertEquals("SAME_ROLE_SCOPE_UNESTABLISHED", same.get("comparability").getAsString()); assertEquals("UNRESOLVED", same.get("state").getAsString());
        assertThrows(RuntimeException.class, () -> select(review(), decision(List.of(), List.of(conflict("claim-1", "claim-99"))), List.of()));
        assertThrows(RuntimeException.class, () -> select(review(), json(Map.of("action", "delegate")), List.of()));
        assertEquals(0, select(review(), decision(List.of(), List.of()), List.of()).getAsJsonArray("selectedFollowUps").size());
    }
}
