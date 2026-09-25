package io.github.nktogo.apicompatibility.compatibility;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.*;
import static io.github.nktogo.apicompatibility.compatibility.RepairEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairEvaluationTest {
    static final InputSnapshot BASE = snapshot("v1", '1', '2');
    static final InputSnapshot TARGET = snapshot("v2", '1', '3');
    static final InputSnapshot REPAIRED = snapshot("v2", '4', '3');
    static final Definition DEFINITION = new Definition(new ComparisonDefinition(BASE, TARGET, List.of("a", "b")), REPAIRED);

    @Test void completeRepairMustFixOriginalFailuresAndPreservePassingBehavior() {
        var result = evaluate(DEFINITION, observations(BASE, true, true), observations(TARGET, false, true), observations(REPAIRED, true, true));
        assertEquals(Conclusion.ALL_OBSERVED_REGRESSIONS_FIXED, result.conclusion());
        assertEquals(List.of(new Scenario("a", Outcome.FIXED), new Scenario("b", Outcome.STILL_PASSING)), result.scenarios());
    }
    @Test void fixingOneBugWhileIntroducingAnotherIsNotASuccess() {
        var result = evaluate(DEFINITION, observations(BASE, true, true), observations(TARGET, false, true), observations(REPAIRED, true, false));
        assertEquals(Conclusion.REGRESSIONS_REMAIN, result.conclusion());
        assertEquals(1, result.count(Outcome.FIXED)); assertEquals(1, result.count(Outcome.NEW_FAILURE));
    }
    @Test void noChangeControlPreservesRemainingFailures() {
        var result = evaluate(new Definition(DEFINITION.transition(), TARGET), observations(BASE, true, true),
                observations(TARGET, false, true), observations(TARGET, false, true));
        assertEquals(Conclusion.REGRESSIONS_REMAIN, result.conclusion());
        assertEquals(1, result.count(Outcome.STILL_FAILING));
    }
    @Test void alreadyPassingTransitionCannotEarnRepairCredit() {
        assertEquals(Conclusion.NO_REPAIR_NEEDED, evaluate(DEFINITION, observations(BASE, true, true),
                observations(TARGET, true, true), observations(REPAIRED, true, true)).conclusion());
    }
    @ParameterizedTest @EnumSource(Disposition.class)
    void unfinishedAssertionNeverCountsAsAFix(Disposition disposition) {
        var repaired = List.of(observation("a", REPAIRED, false), new ExecutionObservation("b", REPAIRED, disposition, AssertionOutcome.NOT_EVALUATED));
        var result = evaluate(DEFINITION, observations(BASE, true, true), observations(TARGET, false, false), repaired);
        assertEquals(Conclusion.UNRESOLVED, result.conclusion());
        assertEquals(1, result.count(Outcome.STILL_FAILING)); assertEquals(1, result.count(Outcome.UNRESOLVED));
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void missingAndConflictingResultsOnEverySidePreventSuccess(int side) {
        var sides = new ArrayList<>(List.of(observations(BASE, true, true), observations(TARGET, false, false), observations(REPAIRED, true, true)));
        var full = sides.get(side);
        sides.set(side, List.of(full.getFirst()));
        assertEquals(Conclusion.UNRESOLVED, evaluate(DEFINITION, sides.get(0), sides.get(1), sides.get(2)).conclusion());
        sides.set(side, List.of(full.getFirst(), full.getLast(), observation("b", full.getLast().inputs(), false)));
        var duplicate = evaluate(DEFINITION, sides.get(0), sides.get(1), sides.get(2));
        assertEquals(Conclusion.UNRESOLVED, duplicate.conclusion());
        assertEquals(Outcome.UNRESOLVED, duplicate.scenarios().getLast().outcome());
    }
    @Test void baselineFailureAndStaleCandidateEvidenceRemainUnresolved() {
        var result = evaluate(DEFINITION, observations(BASE, false, true), observations(TARGET, false, false), observations(REPAIRED, true, true));
        assertEquals(Conclusion.UNRESOLVED, result.conclusion());
        assertEquals(1, result.count(Outcome.FIXED));
        var stale = evaluate(DEFINITION, observations(BASE, true, true), observations(TARGET, false, false), observations(TARGET, true, true));
        assertEquals(Conclusion.UNRESOLVED, stale.conclusion()); assertEquals(2, stale.count(Outcome.UNRESOLVED));
    }
    @Test void extraEvidenceCannotSilentlyChangeCoverage() {
        var repaired = new ArrayList<>(observations(REPAIRED, true, true)); repaired.add(observation("extra", REPAIRED, true));
        var result = evaluate(DEFINITION, observations(BASE, true, true), observations(TARGET, false, false), repaired);
        assertEquals(Conclusion.UNRESOLVED, result.conclusion());
        assertEquals(2, result.count(Outcome.FIXED));
        assertTrue(result.repairedComparison().issues().stream().anyMatch(i -> i.reason() == ComparisonAssessment.Reason.UNDECLARED_SCENARIO));
    }
    @ParameterizedTest @ValueSource(strings = {"case", "provider", "version", "configuration", "expectations", "fixtures", "harness", "environment"})
    void changingProtectedConditionsCannotManufactureARepair(String field) {
        var r = REPAIRED; var different = digest('f');
        var changed = new InputSnapshot(field.equals("case") ? "other" : r.caseId(), field.equals("provider") ? "other" : r.providerId(),
                field.equals("version") ? "other" : r.providerVersion(), r.source(),
                field.equals("configuration") ? different : r.configuration(), field.equals("expectations") ? different : r.expectations(),
                field.equals("fixtures") ? different : r.fixtures(), field.equals("harness") ? different : r.harness(),
                field.equals("environment") ? different : r.environment());
        var result = evaluate(new Definition(DEFINITION.transition(), changed), observations(BASE, true, true),
                observations(TARGET, false, false), observations(changed, true, true));
        assertEquals(Conclusion.UNRESOLVED, result.conclusion()); assertEquals(2, result.count(Outcome.UNRESOLVED));
        assertFalse(result.boundaryIssues().isEmpty());
    }
    @Test void historyIsImmutableAndAppliesOnlyToExactDefinition() {
        var result = evaluate(DEFINITION, observations(BASE, true, true), observations(TARGET, false, true), observations(REPAIRED, true, true));
        assertTrue(result.appliesTo(DEFINITION));
        assertFalse(result.appliesTo(new Definition(DEFINITION.transition(), TARGET)));
        assertFalse(result.appliesTo(new Definition(new ComparisonDefinition(BASE, TARGET, List.of("a")), REPAIRED)));
        assertThrows(UnsupportedOperationException.class, () -> result.scenarios().clear());
    }
    static List<ExecutionObservation> observations(InputSnapshot input, boolean a, boolean b) {
        return List.of(observation("a", input, a), observation("b", input, b));
    }
    static ExecutionObservation observation(String id, InputSnapshot input, boolean passed) {
        return new ExecutionObservation(id, input, Disposition.COMPLETED, passed ? AssertionOutcome.PASSED : AssertionOutcome.FAILED);
    }
    static ContentDigest digest(char c) { return new ContentDigest(String.valueOf(c).repeat(64)); }
    static InputSnapshot snapshot(String version, char source, char config) {
        return new InputSnapshot("case", "provider", version, digest(source), digest(config), digest('5'), digest('6'), digest('7'), digest('8'));
    }
}
