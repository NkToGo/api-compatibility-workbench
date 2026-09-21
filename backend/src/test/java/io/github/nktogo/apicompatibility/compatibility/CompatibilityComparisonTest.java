package io.github.nktogo.apicompatibility.compatibility;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Conclusion;
import static io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Reason;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.AssertionOutcome;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.Disposition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityComparisonTest {
    private static final String SCENARIO = "retains-entitlement";
    private static final String SECOND_SCENARIO = "accepts-renewal";
    private static final InputSnapshot BASELINE = new InputSnapshot(
            "case-1", "example-payments", "api-v1", digest('1'), digest('2'),
            digest('3'), digest('4'), digest('5'), digest('6'));
    private static final InputSnapshot TARGET = new InputSnapshot(
            "case-1", "example-payments", "api-v2", digest('7'), digest('8'),
            digest('3'), digest('4'), digest('5'), digest('6'));
    private static final ComparisonDefinition DEFINITION = new ComparisonDefinition(
            BASELINE, TARGET, List.of(SCENARIO));

    @Test
    void recordsOnlyTheBoundedTransitionRegressionEvenWhenSourceAndConfigurationChange() {
        var result = compare(passed(BASELINE), failed(TARGET));

        assertEquals(Conclusion.REGRESSION_OBSERVED, result.conclusion());
        assertEquals(List.of(SCENARIO), result.regressedScenarioIds());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void reportsNoRegressionOnlyForTheDeclaredScenarioSet() {
        var result = compare(passed(BASELINE), passed(TARGET));

        assertEquals(Conclusion.NO_REGRESSION_OBSERVED, result.conclusion());
        assertEquals(List.of(SCENARIO), result.definition().requiredScenarioIds());
        assertTrue(result.regressedScenarioIds().isEmpty());
    }

    @Test
    void successfulBuildWithNoBehavioralObservationsCannotProduceAGreenAssessment() {
        var result = CompatibilityComparison.compare(DEFINITION, List.of(), List.of());

        assertUnresolved(result, Reason.MISSING_OBSERVATION);
    }

    @Test
    void targetFailureWithoutABaselineIsNotAnObservedRegression() {
        var result = CompatibilityComparison.compare(DEFINITION, List.of(), List.of(failed(TARGET)));

        assertUnresolved(result, Reason.MISSING_OBSERVATION);
        assertTrue(result.regressedScenarioIds().isEmpty());
    }

    @Test
    void aFailingBaselineCannotEstablishARegression() {
        var result = compare(failed(BASELINE), failed(TARGET));

        assertUnresolved(result, Reason.BASELINE_ASSERTION_FAILED);
        assertTrue(result.regressedScenarioIds().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Disposition.class)
    void unevaluatedAssertionsRemainUnresolvedForEveryExecutionDisposition(Disposition disposition) {
        var unexecuted = new ExecutionObservation(SCENARIO, TARGET, disposition, AssertionOutcome.NOT_EVALUATED);

        assertUnresolved(compare(passed(BASELINE), unexecuted), Reason.EXECUTION_INCOMPLETE);
    }

    @ParameterizedTest
    @EnumSource(value = Disposition.class, mode = EnumSource.Mode.EXCLUDE, names = "COMPLETED")
    void infrastructureFailureCannotBeReportedAsAnAssertionFailure(Disposition disposition) {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionObservation(SCENARIO, TARGET, disposition, AssertionOutcome.FAILED));
    }

    @Test
    void conflictingDuplicateObservationsCannotOverwriteTheFailure() {
        var result = CompatibilityComparison.compare(DEFINITION, List.of(passed(BASELINE)),
                List.of(failed(TARGET), passed(TARGET)));

        assertUnresolved(result, Reason.DUPLICATE_OBSERVATIONS);
        var reversed = CompatibilityComparison.compare(DEFINITION, List.of(passed(BASELINE)),
                List.of(passed(TARGET), failed(TARGET)));
        assertEquals(result, reversed);
    }

    @Test
    void evenIdenticalDuplicateObservationsRequireReconciliation() {
        var result = CompatibilityComparison.compare(DEFINITION,
                List.of(passed(BASELINE), passed(BASELINE)), List.of(passed(TARGET)));

        assertUnresolved(result, Reason.DUPLICATE_OBSERVATIONS);
    }

    @ParameterizedTest
    @EnumSource(InputField.class)
    void evidenceWithAnyDifferentInputIdentityCannotSatisfyTheTarget(InputField field) {
        var result = compare(passed(BASELINE), passed(changed(TARGET, field)));

        assertUnresolved(result, Reason.INPUT_MISMATCH);
        assertTrue(result.regressedScenarioIds().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = InputField.class, names = {
            "CASE", "PROVIDER", "EXPECTATIONS", "FIXTURES", "HARNESS", "ENVIRONMENT"
    })
    void changingSharedComparisonConditionsCannotProduceAConclusion(InputField field) {
        var changedTarget = changed(TARGET, field);
        var definition = new ComparisonDefinition(BASELINE, changedTarget, List.of(SCENARIO));
        var result = CompatibilityComparison.compare(definition,
                List.of(passed(BASELINE)), List.of(passed(changedTarget)));

        assertUnresolved(result, Reason.NON_COMPARABLE_INPUTS);
    }

    @Test
    void identicalInputsDoNotEstablishADeclaredTransition() {
        var definition = new ComparisonDefinition(BASELINE, BASELINE, List.of(SCENARIO));
        var result = CompatibilityComparison.compare(definition,
                List.of(passed(BASELINE)), List.of(passed(BASELINE)));

        assertUnresolved(result, Reason.NON_COMPARABLE_INPUTS);
    }

    @Test
    void acceptsAnSdkSourceUpgradeWithTheSameEffectiveProviderVersion() {
        var sdkUpgrade = changed(BASELINE, InputField.SOURCE);
        var definition = new ComparisonDefinition(BASELINE, sdkUpgrade, List.of(SCENARIO));
        var result = CompatibilityComparison.compare(definition,
                List.of(passed(BASELINE)), List.of(passed(sdkUpgrade)));

        assertEquals(Conclusion.NO_REGRESSION_OBSERVED, result.conclusion());
    }

    @Test
    void acceptsAConfigurationOnlyTransitionWithTheSameEffectiveProviderVersion() {
        var reconfigured = changed(BASELINE, InputField.CONFIGURATION);
        var definition = new ComparisonDefinition(BASELINE, reconfigured, List.of(SCENARIO));
        var result = CompatibilityComparison.compare(definition,
                List.of(passed(BASELINE)), List.of(failed(reconfigured)));

        assertEquals(Conclusion.REGRESSION_OBSERVED, result.conclusion());
    }

    @Test
    void assessmentCannotReportUndeclaredOrDuplicateRegressedScenarios() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonAssessment(DEFINITION, Conclusion.REGRESSION_OBSERVED,
                        List.of(SECOND_SCENARIO), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonAssessment(DEFINITION, Conclusion.REGRESSION_OBSERVED,
                        List.of(SCENARIO, SCENARIO), List.of()));
    }

    @Test
    void anUndeclaredScenarioCannotSubstituteForTheRequiredScenario() {
        var wrongScenario = new ExecutionObservation(SECOND_SCENARIO, TARGET,
                Disposition.COMPLETED, AssertionOutcome.PASSED);
        var result = compare(passed(BASELINE), wrongScenario);

        assertUnresolved(result, Reason.UNDECLARED_SCENARIO);
        assertTrue(result.issues().stream().anyMatch(issue -> issue.reason() == Reason.MISSING_OBSERVATION));
    }

    @Test
    void incompleteCoverageRemainsUnresolvedWithoutHidingAnObservedFailureElsewhere() {
        var definition = new ComparisonDefinition(BASELINE, TARGET, List.of(SCENARIO, SECOND_SCENARIO));
        var result = CompatibilityComparison.compare(definition,
                List.of(passed(BASELINE), new ExecutionObservation(SECOND_SCENARIO, BASELINE,
                        Disposition.COMPLETED, AssertionOutcome.PASSED)),
                List.of(failed(TARGET)));

        assertUnresolved(result, Reason.MISSING_OBSERVATION);
        assertEquals(List.of(SCENARIO), result.regressedScenarioIds());
    }

    @ParameterizedTest
    @EnumSource(InputField.class)
    void changedCurrentInputsMakeTheHistoricalAssessmentInapplicableWithoutRewritingIt(InputField field) {
        var result = compare(passed(BASELINE), passed(TARGET));
        var current = new ComparisonDefinition(BASELINE, changed(TARGET, field), List.of(SCENARIO));

        assertTrue(result.appliesTo(DEFINITION));
        assertFalse(result.appliesTo(current));
        assertEquals(Conclusion.NO_REGRESSION_OBSERVED, result.conclusion());
        assertEquals(TARGET, result.definition().target());
    }

    @Test
    void expandingTheRequiredScenarioSetAlsoInvalidatesReuse() {
        var result = compare(passed(BASELINE), passed(TARGET));
        var expanded = new ComparisonDefinition(BASELINE, TARGET, List.of(SCENARIO, SECOND_SCENARIO));

        assertFalse(result.appliesTo(expanded));
    }

    @Test
    void requiredScenariosMustBeNonemptyUniqueAndUnambiguous() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonDefinition(BASELINE, TARGET, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonDefinition(BASELINE, TARGET, List.of(SCENARIO, SCENARIO)));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonDefinition(BASELINE, TARGET, List.of(" ")));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonDefinition(BASELINE, TARGET, List.of(" " + SCENARIO)));
    }

    @Test
    void aCallerCannotMutateTheRequiredCoverageAfterCreatingTheDefinition() {
        var scenarioIds = new ArrayList<>(List.of(SCENARIO));
        var definition = new ComparisonDefinition(BASELINE, TARGET, scenarioIds);
        scenarioIds.clear();

        assertEquals(List.of(SCENARIO), definition.requiredScenarioIds());
        assertThrows(UnsupportedOperationException.class, () -> definition.requiredScenarioIds().clear());
    }

    @Test
    void rejectsMutableLabelsAndMalformedDigestsAsContentIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ContentDigest("main"));
        assertThrows(IllegalArgumentException.class, () -> new ContentDigest("f".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ContentDigest("g".repeat(64)));
    }

    private static ComparisonAssessment compare(ExecutionObservation before, ExecutionObservation after) {
        return CompatibilityComparison.compare(DEFINITION, List.of(before), List.of(after));
    }

    private static void assertUnresolved(ComparisonAssessment result, Reason reason) {
        assertEquals(Conclusion.UNRESOLVED, result.conclusion());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.reason() == reason), result.issues()::toString);
    }

    private static ExecutionObservation passed(InputSnapshot inputs) {
        return new ExecutionObservation(SCENARIO, inputs, Disposition.COMPLETED, AssertionOutcome.PASSED);
    }

    private static ExecutionObservation failed(InputSnapshot inputs) {
        return new ExecutionObservation(SCENARIO, inputs, Disposition.COMPLETED, AssertionOutcome.FAILED);
    }

    private static ContentDigest digest(char character) {
        return new ContentDigest(String.valueOf(character).repeat(64));
    }

    private static InputSnapshot changed(InputSnapshot original, InputField field) {
        return new InputSnapshot(
                field == InputField.CASE ? "another-case" : original.caseId(),
                field == InputField.PROVIDER ? "another-provider" : original.providerId(),
                field == InputField.VERSION ? "api-other" : original.providerVersion(),
                field == InputField.SOURCE ? digest('a') : original.source(),
                field == InputField.CONFIGURATION ? digest('a') : original.configuration(),
                field == InputField.EXPECTATIONS ? digest('a') : original.expectations(),
                field == InputField.FIXTURES ? digest('a') : original.fixtures(),
                field == InputField.HARNESS ? digest('a') : original.harness(),
                field == InputField.ENVIRONMENT ? digest('a') : original.environment());
    }

    private enum InputField {
        CASE, PROVIDER, VERSION, SOURCE, CONFIGURATION, EXPECTATIONS, FIXTURES, HARNESS, ENVIRONMENT
    }
}
