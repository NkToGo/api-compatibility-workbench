package io.github.nktogo.apicompatibility.evaluation;

import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.runner.CoordinationComparisonReport;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationTrial.*;
import static org.junit.jupiter.api.Assertions.*;

class CoordinationComparisonTest {
    private static final Definition PLAN = CoordinationComparisonReport.examplePlan().definition();
    private static ContentDigest digest(char value) { return new ContentDigest(String.valueOf(value).repeat(64)); }
    private static Usage usage(long calls) { return new Usage(calls, calls, calls, 0, 0, calls * 100, calls * 10, 3, 1); }
    private static CaseOutput answer(CaseTask task) {
        var judgments = new TreeMap<String, Judgment>(); task.criteria().forEach(c -> judgments.put(c, Judgment.SATISFIED));
        return new CaseOutput(task.caseId(), task.inputIdentity(), task.expectedResponse() == ExpectedResponse.ANSWER ? Response.ANSWERED : Response.ABSTAINED,
                digest('a'), new Review(digest('a'), digest('b'), true, 0, judgments));
    }
    private static CoordinationTrial trial(Definition plan, int repetition, Arm arm) {
        return new CoordinationTrial(new Slot(plan.workloads().getFirst().workloadId(), repetition, arm), "run-" + arm.ordinal() + "-" + repetition,
                identity(plan), plan.implementations().get(arm), arm == Arm.DETERMINISTIC ? null : plan.model(), Status.COMPLETED, digest("0123456789abcdef".charAt((repetition - 1) * 4 + arm.ordinal() + 1)),
                usage(arm == Arm.DETERMINISTIC ? 0 : 10), 100, plan.workloads().getFirst().cases().stream().map(CoordinationComparisonTest::answer).toList());
    }
    private static List<CoordinationTrial> all(Definition plan) {
        var rows = new ArrayList<CoordinationTrial>(); for (int i = 1; i <= plan.repetitions(); i++) for (Arm arm : Arm.values()) rows.add(trial(plan, i, arm)); return rows;
    }
    private static CoordinationTrial replace(CoordinationTrial t, List<CaseOutput> cases, Status status, Usage usage, long elapsed) {
        return new CoordinationTrial(t.slot(), t.runId(), t.definitionIdentity(), t.implementationIdentity(), t.model(), status, t.traceIdentity(), usage, elapsed, cases);
    }
    private static CoordinationComparison.Assessment changed(CoordinationTrial trial) { var records = all(PLAN); records.set(3, trial); return CoordinationComparison.evaluate(PLAN, records); }
    private static CoordinationComparison.Row changedRow(CoordinationTrial trial) { return changed(trial).rows().get(3); }
    private static CaseOutput reviewed(CaseOutput c, boolean audit, int unsupported, Map<String, Judgment> judgments) {
        return new CaseOutput(c.caseId(), c.inputIdentity(), c.response(), c.outputIdentity(), new Review(c.outputIdentity(), digest('b'), audit, unsupported, judgments));
    }
    @Test void emptyMeasurementRetainsAllPlannedSlotsWithoutAWinningArm() {
        var assessment = CoordinationComparison.evaluate(PLAN, List.of());
        assertEquals(CoordinationComparison.Conclusion.UNRESOLVED, assessment.conclusion()); assertEquals(12, assessment.rows().size());
        for (Arm arm : Arm.values()) { var metrics = assessment.metrics().get(arm); assertEquals(3, metrics.plannedBundles()); assertEquals(3, metrics.missingSlots()); assertEquals(0, metrics.comparableAcceptedBundles()); }
        assertEquals(6, assessment.pairs().size()); assertTrue(assessment.pairs().stream().allMatch(p -> p.unestablishedBlocks() == 3));
        assertEquals("NOT_ESTABLISHED", assessment.routingDecision());
    }
    @Test void completePairedRecordsDescribeQualityAndUsageWithoutInventingRoutingOrTruth() {
        var assessment = CoordinationComparison.evaluate(PLAN, all(PLAN));
        assertEquals(CoordinationComparison.Conclusion.RECORDS_COMPLETE, assessment.conclusion());
        var team = assessment.metrics().get(Arm.COORDINATED_TEAM); assertEquals(3, team.comparableAcceptedBundles()); assertEquals(6, team.reviewedAcceptedCasesForUnambiguousSlots());
        assertEquals(3000, team.reportedInputTokensForUnambiguousSlots()); assertEquals(30, team.reportedReservedCallsForUnambiguousSlots());
        assertEquals(0, assessment.metrics().get(Arm.DETERMINISTIC).reportedInputTokensForUnambiguousSlots());
        assertTrue(assessment.pairs().stream().allMatch(p -> p.comparableBlocks() == 3 && p.bothAcceptedBundle() == 3 && p.equalAcceptedCases() == 3));
        assertEquals("supplied-comparison-records-not-authenticated", assessment.authority()); assertEquals("NOT_ESTABLISHED", assessment.routingDecision());
    }
    @Test void materialUnsupportedExplanationBlocksCreditDespiteCompletedExecutionAndSatisfiedCriteria() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM); var outputs = new ArrayList<>(t.cases()); var c = outputs.getFirst();
        outputs.set(0, reviewed(c, true, 1, c.review().judgments())); var assessment = changed(replace(t, outputs, t.status(), t.usage(), 100)); var row = assessment.rows().get(3);
        assertTrue(row.comparable()); assertFalse(row.acceptedBundle()); assertEquals(1, row.acceptedCases()); assertEquals(3, row.cases().getFirst().satisfiedCriteria());
        assertTrue(row.cases().getFirst().issues().contains("MATERIAL_UNSUPPORTED_CLAIM")); assertEquals(3, assessment.metrics().get(Arm.COORDINATED_TEAM).plannedBundles());
        assertEquals(2, assessment.metrics().get(Arm.COORDINATED_TEAM).comparableAcceptedBundles());
        assertTrue(assessment.pairs().stream().filter(p -> p.right() == Arm.COORDINATED_TEAM).allMatch(p -> p.leftOnlyAcceptedBundle() == 1));
    }
    @Test void pendingAuditMissingCriteriaAndUnresolvedJudgmentsCannotEarnFullCredit() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM); var c = t.cases().getFirst(); var judgments = new TreeMap<>(c.review().judgments());
        for (Review review : List.of(new Review(c.outputIdentity(), digest('b'), false, 0, judgments),
                new Review(c.outputIdentity(), digest('b'), true, 0, Map.of()),
                new Review(c.outputIdentity(), digest('b'), true, 0, Map.of(PLAN.workloads().getFirst().cases().getFirst().criteria().getFirst(), Judgment.UNRESOLVED)))) {
            var cases = new ArrayList<>(t.cases()); cases.set(0, new CaseOutput(c.caseId(), c.inputIdentity(), c.response(), c.outputIdentity(), review));
            var row = changedRow(replace(t, cases, t.status(), t.usage(), 100)); assertFalse(row.comparable()); assertFalse(row.acceptedBundle()); assertEquals(1, row.acceptedCases());
        }
    }
    @Test void changedDefinitionImplementationModelInputsAndReviewedOutputAreNotComparable() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM); var c = t.cases().getFirst();
        var variants = List.of(new CoordinationTrial(t.slot(), t.runId(), digest('d'), t.implementationIdentity(), t.model(), t.status(), t.traceIdentity(), t.usage(), 100, t.cases()),
                new CoordinationTrial(t.slot(), t.runId(), t.definitionIdentity(), digest('d'), t.model(), t.status(), t.traceIdentity(), t.usage(), 100, t.cases()),
                new CoordinationTrial(t.slot(), t.runId(), t.definitionIdentity(), t.implementationIdentity(), "gpt-other", t.status(), t.traceIdentity(), t.usage(), 100, t.cases()),
                replace(t, List.of(new CaseOutput(c.caseId(), digest('d'), c.response(), c.outputIdentity(), c.review()), t.cases().get(1)), t.status(), t.usage(), 100),
                replace(t, List.of(new CaseOutput(c.caseId(), c.inputIdentity(), c.response(), digest('d'), c.review()), t.cases().get(1)), t.status(), t.usage(), 100));
        for (var variant : variants) { var row = changedRow(variant); assertFalse(row.comparable()); assertFalse(row.acceptedBundle()); assertEquals(variant, row.records().getFirst()); }
    }
    @Test void missingUnexpectedAndDuplicatedCaseAnswersPreserveUncertaintyAndOtherKnownAnswers() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM);
        var missing = changedRow(replace(t, List.of(t.cases().getFirst()), t.status(), t.usage(), 100)); assertFalse(missing.comparable()); assertEquals(1, missing.acceptedCases());
        var cases = new ArrayList<>(t.cases()); cases.add(t.cases().getFirst());
        var duplicate = changedRow(replace(t, cases, t.status(), t.usage(), 100)); assertTrue(duplicate.cases().getFirst().issues().contains("AMBIGUOUS_CASE_OUTPUT")); assertEquals(1, duplicate.acceptedCases());
        cases = new ArrayList<>(t.cases()); cases.add(new CaseOutput("foreign-case", digest('a'), Response.NOT_PROVIDED, null, null));
        var extra = changedRow(replace(t, cases, t.status(), t.usage(), 100)); assertFalse(extra.comparable()); assertFalse(extra.acceptedBundle()); assertTrue(extra.issues().contains("UNEXPECTED_CASE_OUTPUT"));
    }
    @Test void duplicateAndUnexpectedTrialsStayInTheReportWithoutSelectingTheBestRetry() {
        var records = all(PLAN); records.add(records.get(3));
        var duplicate = CoordinationComparison.evaluate(PLAN, records); var row = duplicate.rows().get(3);
        assertEquals(2, row.records().size()); assertFalse(row.comparable()); assertEquals(1, duplicate.metrics().get(Arm.COORDINATED_TEAM).ambiguousSlots());
        assertEquals(2000, duplicate.metrics().get(Arm.COORDINATED_TEAM).reportedInputTokensForUnambiguousSlots());
        var original = records.getFirst(); records = all(PLAN);
        var extra = new CoordinationTrial(new Slot("unplanned-workload", 1, Arm.DETERMINISTIC), "extra-run", original.definitionIdentity(), original.implementationIdentity(), null,
                original.status(), original.traceIdentity(), original.usage(), 100, original.cases()); records.add(extra);
        var result = CoordinationComparison.evaluate(PLAN, records); assertEquals(List.of(extra), result.unexpectedRecords()); assertEquals(12, result.rows().size()); assertEquals(CoordinationComparison.Conclusion.UNRESOLVED, result.conclusion());
    }
    @Test void reusedRunIdsCannotBeCountedTwiceAcrossDifferentSlots() {
        var records = all(PLAN); var t = records.get(7); var reused = new CoordinationTrial(t.slot(), records.get(3).runId(), t.definitionIdentity(), t.implementationIdentity(), t.model(), t.status(), t.traceIdentity(), t.usage(), 100, t.cases());
        records.set(7, reused); var result = CoordinationComparison.evaluate(PLAN, records); var metrics = result.metrics().get(Arm.COORDINATED_TEAM);
        assertEquals(2, metrics.ambiguousSlots()); assertEquals(1000, metrics.reportedInputTokensForUnambiguousSlots()); assertEquals(2, metrics.reviewedAcceptedCasesForUnambiguousSlots());
        assertEquals(2, result.pairs().get(2).unestablishedBlocks());
    }
    @Test void reusedExecutionTracesDoNotBecomeIndependentRepetitionsUnderDifferentNames() {
        var records = all(PLAN); var t = records.get(7); records.set(7, new CoordinationTrial(t.slot(), t.runId(), t.definitionIdentity(), t.implementationIdentity(), t.model(), t.status(), records.get(3).traceIdentity(), t.usage(), 100, t.cases()));
        var result = CoordinationComparison.evaluate(PLAN, records); assertTrue(result.rows().get(3).issues().contains("REUSED_TRACE_IDENTITY"));
        assertEquals(2, result.metrics().get(Arm.COORDINATED_TEAM).ambiguousSlots()); assertEquals(1, result.metrics().get(Arm.COORDINATED_TEAM).comparableBundles());
    }
    @Test void terminalFailuresStayInTheDenominatorAndPartialReviewedAnswersSurvive() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM);
        for (Status status : List.of(Status.FAILED, Status.CANCELLED)) {
            var result = changed(replace(t, List.of(), status, t.usage(), 100)); var row = result.rows().get(3);
            assertTrue(row.comparable()); assertFalse(row.acceptedBundle()); assertEquals(0, row.acceptedCases()); assertEquals(1, result.metrics().get(Arm.COORDINATED_TEAM).failedBundles());
            assertEquals(CoordinationComparison.Conclusion.RECORDS_COMPLETE, result.conclusion());
        }
        var partial = changedRow(replace(t, List.of(t.cases().getFirst()), Status.FAILED, t.usage(), 100)); assertTrue(partial.comparable()); assertEquals(1, partial.acceptedCases()); assertFalse(partial.acceptedBundle());
    }
    @Test void uncertainUsageAndPendingCallsAreNotFreeSuccessfulRuns() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM);
        for (var usage : List.of(new Usage(1, 1, 0, 0, 1, 0, 0, 0, 0), new Usage(1, 1, 0, 0, 0, 0, 0, 0, 0))) {
            var result = changed(replace(t, t.cases(), Status.UNKNOWN_REMOTE_OUTCOME, usage, 100)); var row = result.rows().get(3);
            assertFalse(row.comparable()); assertEquals(2, row.acceptedCases()); assertFalse(row.acceptedBundle()); assertEquals(1, result.metrics().get(Arm.COORDINATED_TEAM).incompleteUsageSlots());
        }
        assertFalse(changedRow(replace(t, t.cases(), Status.PENDING, t.usage(), 100)).comparable());
    }
    @Test void resourceViolationsBlockFairComparisonButDoNotEraseReviewedQuality() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM);
        for (var row : List.of(changedRow(replace(t, t.cases(), t.status(), usage(61), 100)), changedRow(replace(t, t.cases(), t.status(), t.usage(), 1200001)))) {
            assertTrue(row.issues().contains("BUDGET_EXCEEDED")); assertFalse(row.comparable()); assertTrue(row.acceptedBundle()); assertEquals(2, row.acceptedCases());
        }
        var deterministic = trial(PLAN, 1, Arm.DETERMINISTIC); var records = all(PLAN); records.set(0, replace(deterministic, deterministic.cases(), deterministic.status(), usage(1), 100));
        assertTrue(CoordinationComparison.evaluate(PLAN, records).rows().getFirst().issues().contains("MODEL_USE_IN_DETERMINISTIC_ARM"));
    }
    @Test void knownUnsatisfiedCriteriaAndWrongResponseDispositionAreNegativeMeasurementsNotMissingTrials() {
        var t = trial(PLAN, 1, Arm.COORDINATED_TEAM); var cases = new ArrayList<>(t.cases()); var c = cases.getFirst(); var judgments = new TreeMap<>(c.review().judgments()); judgments.replaceAll((key, value) -> Judgment.NOT_SATISFIED);
        cases.set(0, reviewed(c, true, 0, judgments)); var negative = changedRow(replace(t, cases, t.status(), t.usage(), 100)); assertTrue(negative.comparable()); assertEquals(1, negative.acceptedCases());
        cases.set(0, new CaseOutput(c.caseId(), c.inputIdentity(), Response.ABSTAINED, c.outputIdentity(), c.review()));
        var abstained = changedRow(replace(t, cases, t.status(), t.usage(), 100)); assertTrue(abstained.comparable()); assertFalse(abstained.cases().getFirst().accepted());
        cases.set(0, new CaseOutput(c.caseId(), c.inputIdentity(), Response.NOT_PROVIDED, null, null));
        var emptyAnswer = changedRow(replace(t, cases, t.status(), t.usage(), 100)); assertTrue(emptyAnswer.comparable()); assertFalse(emptyAnswer.acceptedBundle()); assertEquals(1, emptyAnswer.acceptedCases());
    }
    @Test void definitionIdentityIncludesLimitsRubricModelWorkloadAndImplementationAndRejectsStaleReuse() {
        var changed = new Definition(PLAN.experimentId(), PLAN.origin(), PLAN.model(), PLAN.rubricIdentity(), PLAN.implementations(), PLAN.workloads(), 2, PLAN.limits());
        assertNotEquals(identity(PLAN), identity(changed)); assertFalse(CoordinationComparison.evaluate(PLAN, all(PLAN)).appliesTo(changed));
        var versions = new EnumMap<>(PLAN.implementations()); var tasks = new ArrayList<>(PLAN.workloads().getFirst().cases());
        var frozen = new Definition("frozen-plan", PLAN.origin(), PLAN.model(), PLAN.rubricIdentity(), versions, List.of(new Workload("workload", tasks)), 3, PLAN.limits());
        var before = identity(frozen); versions.put(Arm.SINGLE_AGENT, digest('f')); tasks.clear(); assertEquals(before, identity(frozen));
        assertThrows(UnsupportedOperationException.class, () -> frozen.implementations().clear());
        var reordered = new EnumMap<Arm, ContentDigest>(Arm.class); for (Arm arm : Arm.values()) reordered.put(arm, PLAN.implementations().get(arm));
        assertEquals(identity(PLAN), identity(new Definition(PLAN.experimentId(), PLAN.origin(), PLAN.model(), PLAN.rubricIdentity(), reordered, PLAN.workloads(), 3, PLAN.limits())));
    }
    @Test void invalidCallAccountingNegativeNumbersAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Usage(1, 2, 1, 0, 0, 10, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Usage(1, 1, 0, 0, 0, 10, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Usage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, 10, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 100, 100, 16));
        var records = all(PLAN); var huge = new Usage(1, 1, 1, 0, 0, Long.MAX_VALUE, 1, 0, 0);
        records.set(1, replace(records.get(1), records.get(1).cases(), Status.COMPLETED, huge, 100));
        records.set(5, replace(records.get(5), records.get(5).cases(), Status.COMPLETED, huge, 100));
        assertThrows(ArithmeticException.class, () -> CoordinationComparison.evaluate(PLAN, records));
    }
}
