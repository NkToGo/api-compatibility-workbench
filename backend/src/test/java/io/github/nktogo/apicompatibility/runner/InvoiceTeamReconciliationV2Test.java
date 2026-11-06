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
import static io.github.nktogo.apicompatibility.runner.InvoiceTeamReconciliationV2.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTeamReconciliationV2Test {
    @TempDir static Path root;
    private static InvoiceComparisonInputs.Bundle inputs, ready;
    private static final String RECON = "invoice-reconciliation", WEBHOOK = "invoice-webhook", COORDINATOR = "invoice-coordinator", FINAL = "invoice-final-reviewer";
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), root.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        inputs = InvoiceComparisonInputs.capture(plan, run, root.resolve("inputs"));
        Path directory = root.resolve("authored-ready-inputs"); InvoiceComparisonInputs.capture(plan, run, directory);
        // Authored access-gate fixture, not newly observed source execution or model quality.
        var manifest = CaseInputs.object(Files.readAllBytes(directory.resolve("manifest.json"))); manifest.addProperty("sourceExecutionCoverageSatisfied", true);
        byte[] bytes = CaseInputs.JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8); Files.write(directory.resolve("manifest.json"), bytes);
        var identity = new ContentDigest(CaseInputs.hash(bytes)); Files.writeString(directory.resolve("receipt.json"), json(Map.of("manifestSha256", identity.sha256())).toString());
        ready = InvoiceComparisonInputs.open(directory, identity);
    }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject page(InvoiceComparisonInputs.Bundle bundle, String participant, String id, String kind, String alias, int offset, int limit) {
        JsonObject value = switch (kind) {
            case "describe" -> bundle.describe(); case "catalog" -> bundle.catalog(offset, limit);
            case "catalog_prefix" -> bundle.catalogPrefix(alias, offset, limit); case "read_text" -> bundle.read(alias, offset, limit);
            default -> throw new IllegalArgumentException("Unknown fixture read");
        };
        return json(Map.of("participant", participant, "operationId", id, "caseId", InvoiceRenewalFixtures.CASE_ID,
                "bundleIdentity", bundle.identity().sha256(), "pageRequest", new ComparisonEvidence.PageRequest(kind, InvoiceRenewalFixtures.CASE_ID, alias, offset, limit),
                "page", value, "contentTrust", "untrusted-data"));
    }
    private static JsonObject bootstrap(InvoiceComparisonInputs.Bundle bundle, String participant) { return page(bundle, participant, "metadata", "describe", "", 0, 0); }
    private static JsonObject text(InvoiceComparisonInputs.Bundle bundle, String participant, int id, String alias) { return page(bundle, participant, "read-" + id, "read_text", alias, 0, 4000); }
    private static JsonObject finish(String response, String... citations) {
        return json(Map.of("action", "finish", "response", response, "summary", "Authored final advisory summary", "summaryCitations", List.of(citations),
                "findings", citations.length == 0 ? List.of() : List.of(Map.of("kind", "LIMITATION", "statement", "Authored cited limitation", "citations", List.of(citations)))));
    }
    private static WorkerResult original(InvoiceComparisonInputs.Bundle bundle, String worker, boolean answered) {
        var scope = InvoiceFollowUpDecisionV2.scope(worker); var reads = answered ? complete(bundle, worker, List.of(InvoiceWorkerDecision.baseline(worker)), worker) : List.of(text(bundle, worker, 1, "task"));
        return new WorkerResult(worker, bundle.identity(), InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope), InvoiceDecisionV2.taskIdentity(),
                WorkerStatus.COMPLETED, "NONE", finish(answered ? "ANSWERED" : "ABSTAINED", "read-1"), bootstrap(bundle, worker), reads);
    }
    private static List<WorkerResult> originals(InvoiceComparisonInputs.Bundle bundle) { return List.of(original(bundle, RECON, false), original(bundle, WEBHOOK, false)); }
    private static JsonObject question(String worker) { return json(Map.of("workerId", worker, "question", "Which recorded facts support this role?", "citations", List.of(worker.equals(RECON) ? "claim-1" : "claim-3"))); }
    private static ReviewResult review(InvoiceComparisonInputs.Bundle bundle, JsonObject... questions) {
        var decision = json(Map.of("action", "finish", "summary", "Preserve uncertainty", "citations", List.of("worker-1", "worker-2"), "followUps", List.of(questions),
                "conflicts", List.of(Map.of("claimIds", List.of("claim-1", "claim-3"), "statement", "Authored possible conflict with unestablished scope"))));
        return new ReviewResult(WorkerStatus.COMPLETED, "NONE", decision, bootstrap(bundle, COORDINATOR), List.of());
    }
    private static JsonObject fresh(InvoiceComparisonInputs.Bundle bundle, List<WorkerResult> originals, ReviewResult review, String worker, boolean answered) {
        var follow = new InvoiceFollowUpV2(bundle, originals, COORDINATOR, review.decision(), review.bootstrap(), review.reads());
        String participant = InvoiceFollowUpDecisionV2.participant(worker);
        var reads = answered ? complete(bundle, participant, List.of(InvoiceWorkerDecision.baseline(worker)), worker) : List.of(text(bundle, participant, 1, "task"));
        var decision = finish(answered ? "ANSWERED" : "ABSTAINED", "read-1"); var metadata = bootstrap(bundle, participant);
        return json(Map.of("workerId", worker, "participantId", participant, "status", "COMPLETED", "stopReason", "NONE", "questionBinding", follow.question(worker),
                "reads", reads, "bootstrap", metadata, "decision", decision, "accepted", follow.accept(worker, decision, metadata, reads)));
    }
    private static JsonArray array(JsonObject... entries) { var result = new JsonArray(); for (var entry : entries) result.add(entry); return result; }
    private static InvoiceTeamReconciliationV2 contract(ReviewResult review, JsonArray fresh) { return new InvoiceTeamReconciliationV2(inputs, FINAL, originals(inputs), COORDINATOR, review, fresh); }
    private static InvoiceTeamReconciliationV2 contract() { var r = review(inputs, question(WEBHOOK)); return contract(r, array(fresh(inputs, originals(inputs), r, WEBHOOK, false))); }
    private static JsonObject accept(InvoiceTeamReconciliationV2 contract, JsonObject decision) { return contract.accept(decision, bootstrap(inputs, FINAL), List.of()); }
    private static JsonObject context(InvoiceTeamReconciliationV2 contract, List<JsonObject> reads, CoordinationTeamBudget.Reservations reserved, int bytes) {
        return JsonParser.parseString(contract.context(bootstrap(inputs, FINAL), reads, CoordinationTeamBudget.forCeiling(60), reserved, bytes)).getAsJsonObject();
    }
    private static List<JsonObject> complete(InvoiceComparisonInputs.Bundle bundle, String participant, List<String> baselines, String worker) {
        var aliases = new ArrayList<>(List.of("task", "build-configuration")); aliases.addAll(baselines);
        String role = InvoiceWorkerDecision.role(worker);
        aliases.add(bundle.catalogPrefix("business/", 0, 40).getAsJsonArray("artifacts").asList().stream().map(v -> v.getAsJsonObject().get("alias").getAsString()).filter(a -> a.endsWith("/" + role)).findFirst().orElseThrow());
        aliases.add(bundle.catalogPrefix("order/", 0, 1).getAsJsonArray("artifacts").get(0).getAsJsonObject().get("alias").getAsString());
        var reads = new ArrayList<JsonObject>();
        for (String alias : aliases) { int offset = 0;
            do { var read = page(bundle, participant, "read-" + (reads.size() + 1), "read_text", alias, offset, 4000); reads.add(read); offset = read.getAsJsonObject("page").get("nextOffset").getAsInt(); }
            while (offset < bundle.read(alias, 0, 4000).get("totalCharacters").getAsInt());
        }
        return reads;
    }
    @Test void retainsAllClaimsQuestionBindingsAndUnresolvedConflictsAfterFinalAdvice() {
        var value = accept(contract(), finish("ABSTAINED", "claim-1", "freshclaim-1")); var history = value.getAsJsonObject("history");
        assertEquals(4, history.getAsJsonArray("originalClaims").size()); assertEquals(2, history.getAsJsonArray("freshClaims").size());
        assertEquals("UNRESOLVED", history.getAsJsonArray("possibleConflicts").get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("NOT_PERFORMED", value.get("qualityReview").getAsString()); assertFalse(value.has("trial"));
        assertEquals("ABSTAINED", history.getAsJsonArray("workers").get(1).getAsJsonObject().get("response").getAsString());
        assertEquals("follow-up-1", history.getAsJsonArray("freshWorkers").get(0).getAsJsonObject().getAsJsonObject("questionBinding").getAsJsonObject("delegatedQuestion").get("proposalId").getAsString());
    }
    @Test void missingExtraDuplicateOrReorderedFreshOutcomesAreRejected() {
        var r = review(inputs, question(WEBHOOK), question(RECON)); var recon = fresh(inputs, originals(inputs), r, RECON, false); var webhook = fresh(inputs, originals(inputs), r, WEBHOOK, false);
        assertThrows(RuntimeException.class, () -> contract(r, array(recon))); assertThrows(RuntimeException.class, () -> contract(r, array(recon, recon)));
        assertThrows(RuntimeException.class, () -> contract(r, array(webhook, recon))); assertThrows(RuntimeException.class, () -> contract(r, array(recon, webhook, webhook)));
        assertEquals(2, contract(r, array(recon, webhook)).evidence().getAsJsonArray("freshWorkers").size());
    }
    @Test void changedQuestionTargetSelectionOrAcceptedFinishCannotBeLaundered() {
        var r = review(inputs, question(WEBHOOK)); var row = fresh(inputs, originals(inputs), r, WEBHOOK, false);
        for (String field : List.of("workerId", "participantId", "questionBinding", "accepted", "decision")) {
            var changed = row.deepCopy();
            if (changed.get(field).isJsonObject()) changed.getAsJsonObject(field).addProperty("invented", "change"); else changed.addProperty(field, "foreign");
            assertThrows(RuntimeException.class, () -> contract(r, array(changed)), field);
        }
        var changed = row.deepCopy(); changed.addProperty("extra", true); assertThrows(RuntimeException.class, () -> contract(r, array(changed)));
        assertThrows(RuntimeException.class, () -> contract(review(inputs), array(row)));
    }
    @Test void stoppedAndUnavailableSourcesRemainDistinctFromKnownEmptySelection() {
        var stopped = new ReviewResult(WorkerStatus.UNKNOWN_REMOTE_OUTCOME, "MODEL_UNKNOWN_REMOTE_OUTCOME", null, null, List.of());
        var unavailable = contract(stopped, array()).evidence(); var empty = contract(review(inputs), array()).evidence();
        assertTrue(unavailable.get("selectedFollowUps").isJsonNull()); assertTrue(empty.getAsJsonArray("selectedFollowUps").isEmpty());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", unavailable.getAsJsonObject("coordinatorReview").get("status").getAsString());
        assertThrows(RuntimeException.class, () -> contract(stopped, array(new JsonObject())));
    }
    @Test void failedCancelledAndUnknownFreshOutcomesCannotMasqueradeAsAbstentions() {
        var r = review(inputs, question(WEBHOOK));
        for (var status : List.of(WorkerStatus.FAILED, WorkerStatus.CANCELLED, WorkerStatus.UNKNOWN_REMOTE_OUTCOME)) {
            var row = fresh(inputs, originals(inputs), r, WEBHOOK, false); row.addProperty("status", status.name()); row.addProperty("stopReason", "SOURCE_STOPPED");
            assertThrows(RuntimeException.class, () -> contract(r, array(row)));
            row.add("decision", JsonNull.INSTANCE); row.add("accepted", JsonNull.INSTANCE);
            var outcome = contract(r, array(row)).evidence().getAsJsonArray("freshWorkers").get(0).getAsJsonObject();
            assertEquals(status.name(), outcome.get("status").getAsString()); assertEquals("NOT_PROVIDED", outcome.get("response").getAsString());
        }
    }
    @Test void sourceDispositionAndOwnedReadValidationApplyEvenToStoppedSources() {
        assertThrows(RuntimeException.class, () -> new ReviewResult(WorkerStatus.FAILED, "NONE", null, null, List.of()));
        assertThrows(RuntimeException.class, () -> new ReviewResult(WorkerStatus.COMPLETED, "NONE", null, null, List.of()));
        assertThrows(RuntimeException.class, () -> new ReviewResult(WorkerStatus.FAILED, "STOPPED", null, null, List.of(text(inputs, COORDINATOR, 1, "task"))));
        var stopped = new ReviewResult(WorkerStatus.FAILED, "STOPPED", null, bootstrap(inputs, WEBHOOK), List.of());
        assertThrows(RuntimeException.class, () -> contract(stopped, array()));
        var r = review(inputs, question(WEBHOOK)); var row = fresh(inputs, originals(inputs), r, WEBHOOK, false);
        row.addProperty("status", "FAILED"); row.addProperty("stopReason", "STOPPED"); row.add("decision", JsonNull.INSTANCE); row.add("accepted", JsonNull.INSTANCE);
        row.add("bootstrap", bootstrap(inputs, WEBHOOK)); assertThrows(RuntimeException.class, () -> contract(r, array(row)));
    }
    @Test void roleParticipantAliasesAndForeignInputHistoriesAreRejected() {
        for (String id : List.of(RECON, WEBHOOK, COORDINATOR, "fresh-" + RECON, "fresh-" + WEBHOOK))
            assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliationV2(inputs, id, originals(inputs), COORDINATOR, review(inputs), array()));
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliationV2(inputs, FINAL, originals(ready), COORDINATOR, review(inputs), array()));
        assertThrows(RuntimeException.class, () -> new InvoiceTeamReconciliationV2(inputs, FINAL, originals(inputs), "fresh-" + WEBHOOK, review(inputs), array()));
    }
    @Test void inventedAndWorkerLocalReferencesAreRejectedEvenForAbstention() {
        for (String id : List.of("claim-999", "freshclaim-999", "worker-999", "freshworker-999", "read-1"))
            assertThrows(RuntimeException.class, () -> accept(contract(), finish("ABSTAINED", id)), id);
        assertDoesNotThrow(() -> accept(contract(), finish("ABSTAINED", "worker-1", "freshworker-1")));
    }
    @Test void onlyFinalReviewersOwnTextPagesAreDirectCitations() {
        var contract = contract(); var own = text(inputs, FINAL, 1, "build-configuration");
        var accepted = contract.accept(finish("ABSTAINED", "read-1"), bootstrap(inputs, FINAL), List.of(own));
        assertEquals(CaseInputs.hash(InvoiceDecisionV2.canonical(own).getBytes(StandardCharsets.UTF_8)), accepted.getAsJsonObject("referenceIdentities").get("read-1").getAsString());
        assertThrows(RuntimeException.class, () -> contract.accept(finish("ABSTAINED", "read-1"), bootstrap(inputs, FINAL), List.of(text(inputs, WEBHOOK, 1, "task"))));
        var changed = own.deepCopy(); changed.getAsJsonObject("page").addProperty("content", "Changed evidence");
        assertThrows(RuntimeException.class, () -> contract.accept(finish("ABSTAINED", "read-1"), bootstrap(inputs, FINAL), List.of(changed)));
        for (String kind : List.of("catalog", "catalog_prefix")) assertThrows(RuntimeException.class, () -> contract.accept(finish("ABSTAINED", "read-1"), bootstrap(inputs, FINAL), List.of(page(inputs, FINAL, "read-1", kind, "", 0, 40))));
    }
    @Test void outcomeLabelsAndAbstainedClaimsCannotSatisfyBothRoleAnswerGuard() {
        assertThrows(RuntimeException.class, () -> accept(contract(), finish("ANSWERED", "worker-1", "worker-2", "freshworker-1")));
        assertThrows(RuntimeException.class, () -> accept(contract(), finish("ANSWERED", "claim-1", "claim-3", "freshclaim-1")));
    }
    @Test void authoredAnsweredOriginalOrFreshClaimsCanCoverBothRolesWithoutInheritedOwnReadiness() {
        for (boolean freshAnswers : List.of(false, true)) {
            var originals = freshAnswers ? originals(ready) : List.of(original(ready, RECON, true), original(ready, WEBHOOK, true));
            var r = freshAnswers ? review(ready, question(RECON), question(WEBHOOK)) : review(ready);
            var fresh = freshAnswers ? array(fresh(ready, originals, r, RECON, true), fresh(ready, originals, r, WEBHOOK, true)) : array();
            var contract = new InvoiceTeamReconciliationV2(ready, FINAL, originals, COORDINATOR, r, fresh);
            var value = contract.accept(finish("ANSWERED", freshAnswers ? "freshclaim-1" : "claim-1", freshAnswers ? "freshclaim-3" : "claim-3"), bootstrap(ready, FINAL), List.of());
            assertFalse(value.getAsJsonObject("readiness").get("structuralAccessReady").getAsBoolean());
            assertEquals("UNRESOLVED", value.getAsJsonObject("history").getAsJsonArray("possibleConflicts").get(0).getAsJsonObject().get("state").getAsString());
            assertEquals(freshAnswers ? "ABSTAINED" : "ANSWERED", value.getAsJsonObject("history").getAsJsonArray("workers").get(0).getAsJsonObject().get("response").getAsString());
        }
    }
    @Test void oneAnsweredRoleIsInsufficientButMixedOriginalFreshRolesCanCoverBoth() {
        var originals = List.of(original(ready, RECON, true), original(ready, WEBHOOK, false)); var r = review(ready, question(WEBHOOK));
        var contract = new InvoiceTeamReconciliationV2(ready, FINAL, originals, COORDINATOR, r, array(fresh(ready, originals, r, WEBHOOK, true)));
        assertThrows(RuntimeException.class, () -> contract.accept(finish("ANSWERED", "claim-1", "claim-3"), bootstrap(ready, FINAL), List.of()));
        assertDoesNotThrow(() -> contract.accept(finish("ANSWERED", "claim-1", "freshclaim-1"), bootstrap(ready, FINAL), List.of()));
    }
    @Test void authoredCompleteOwnJointReadinessIsAnAlternativeButNeedsOwnCitationAndSourceCoverage() {
        var reads = complete(ready, FINAL, List.of(InvoiceWorkerDecision.baseline(RECON), InvoiceWorkerDecision.baseline(WEBHOOK)), RECON);
        var contract = new InvoiceTeamReconciliationV2(ready, FINAL, originals(ready), COORDINATOR, review(ready), array());
        assertDoesNotThrow(() -> contract.accept(finish("ANSWERED", "read-1"), bootstrap(ready, FINAL), reads));
        assertThrows(RuntimeException.class, () -> contract.accept(finish("ANSWERED", "claim-1"), bootstrap(ready, FINAL), reads));
        var incomplete = complete(inputs, FINAL, List.of(InvoiceWorkerDecision.baseline(RECON), InvoiceWorkerDecision.baseline(WEBHOOK)), RECON);
        assertThrows(RuntimeException.class, () -> contract(review(inputs), array()).accept(finish("ANSWERED", "read-1"), bootstrap(inputs, FINAL), incomplete));
    }
    @Test void cumulativeFinalStageCarriesUnusedCallsWithoutResetOrFreeFinish() {
        var context = context(contract(), List.of(), new CoordinationTeamBudget.Reservations(2, 1, 1, 0), 64000);
        assertEquals(56, context.getAsJsonObject("teamStage").get("callsRemainingInReconciliationStage").getAsInt());
        assertEquals(56, context.getAsJsonObject("scheduling").get("sharedCallsRemainingBeforeRound").getAsInt());
        assertTrue(context.getAsJsonObject("scheduling").get("finishConsumesDecision").getAsBoolean());
        assertEquals("CALL_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> context(contract(), List.of(), new CoordinationTeamBudget.Reservations(24, 6, 24, 6), 64000)).getMessage());
        assertThrows(RuntimeException.class, () -> context(contract(), List.of(), new CoordinationTeamBudget.Reservations(25, 0, 0, 0), 64000));
        assertThrows(RuntimeException.class, () -> contract().context(bootstrap(inputs, FINAL), List.of(), new CoordinationTeamBudget.Allocation(2, 1, 56, 1), new CoordinationTeamBudget.Reservations(0, 0, 0, 0), 64000));
    }
    @Test void contextTrimsOnlyRecentContentsKeepingAllHistoryAndOwnMetadata() {
        var reads = List.of(text(inputs, FINAL, 1, "task"), text(inputs, FINAL, 2, "build-configuration")); var reserved = new CoordinationTeamBudget.Reservations(24, 6, 24, 0);
        var full = context(contract(), reads, reserved, 64000); var thin = full.deepCopy(); thin.add("recentPages", new JsonArray());
        int bytes = InvoiceDecisionV2.canonical(thin).getBytes(StandardCharsets.UTF_8).length; var trimmed = context(contract(), reads, reserved, bytes);
        assertTrue(trimmed.getAsJsonArray("recentPages").isEmpty()); assertEquals(2, trimmed.getAsJsonArray("readMetadata").size()); assertEquals(full.get("history"), trimmed.get("history"));
        assertEquals("CONTEXT_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> context(contract(), reads, reserved, bytes - 1)).getMessage());
        assertThrows(RuntimeException.class, () -> context(contract(), reads, reserved, 64001));
    }
    @Test void callerAndReturnedObjectsCannotMutateSourceLineageOrContractIdentity() {
        var r = review(inputs, question(WEBHOOK)); var fresh = array(fresh(inputs, originals(inputs), r, WEBHOOK, false)); var contract = contract(r, fresh); var expected = contract.evidence();
        r.decision().addProperty("summary", "Mutated"); fresh.get(0).getAsJsonObject().addProperty("status", "FAILED"); contract.evidence().addProperty("contentTrust", "trusted");
        var accepted = accept(contract, finish("ABSTAINED")); accepted.getAsJsonObject("history").addProperty("mutated", true);
        assertEquals(expected, contract.evidence()); assertEquals(InvoiceReconciliationDecisionV2.contractIdentity(), accept(contract, finish("ABSTAINED")).get("contractIdentity").getAsString());
    }
    @Test void originalUnknownRemainsUnknownAfterAValidFreshAnswer() {
        var originals = new ArrayList<>(originals(ready)); originals.set(1, new WorkerResult(WEBHOOK, ready.identity(), InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK),
                InvoiceDecisionV2.contractIdentity(InvoiceReadiness.Scope.WEBHOOK), InvoiceDecisionV2.taskIdentity(), WorkerStatus.UNKNOWN_REMOTE_OUTCOME, "MODEL_UNKNOWN_REMOTE_OUTCOME", null, null, List.of()));
        var r = review(ready, question(WEBHOOK)); // Unknown outcomes have no claim-3; anchor the outcome instead.
        var d = r.decision(); d.getAsJsonArray("followUps").get(0).getAsJsonObject().add("citations", JSON.toJsonTree(List.of("worker-2"))); d.add("conflicts", new JsonArray());
        r = new ReviewResult(WorkerStatus.COMPLETED, "NONE", d, r.bootstrap(), r.reads());
        var contract = new InvoiceTeamReconciliationV2(ready, FINAL, originals, COORDINATOR, r, array(fresh(ready, originals, r, WEBHOOK, true)));
        var history = contract.evidence(); assertEquals("UNKNOWN_REMOTE_OUTCOME", history.getAsJsonArray("workers").get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("ANSWERED", history.getAsJsonArray("freshWorkers").get(0).getAsJsonObject().get("response").getAsString());
    }
}
