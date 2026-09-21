package io.github.nktogo.apicompatibility.compatibility;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/**
 * A bounded observation about the declared scenarios and exact inputs, not a guarantee
 * of compatibility or a claim that a provider change caused an observed regression.
 * Historical results remain immutable when their applicability to current inputs changes.
 */
public record ComparisonAssessment(
        ComparisonDefinition definition,
        Conclusion conclusion,
        List<String> regressedScenarioIds,
        List<Issue> issues) {

    public ComparisonAssessment {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(conclusion, "conclusion");
        regressedScenarioIds = List.copyOf(regressedScenarioIds);
        issues = List.copyOf(issues);
        if (!definition.requiredScenarioIds().containsAll(regressedScenarioIds)
                || new HashSet<>(regressedScenarioIds).size() != regressedScenarioIds.size()) {
            throw new IllegalArgumentException("Regressed scenarios must be unique members of the declared scenario set");
        }
        if ((conclusion == Conclusion.UNRESOLVED) != !issues.isEmpty()) {
            throw new IllegalArgumentException("Only unresolved assessments contain blocking issues");
        }
        if (conclusion == Conclusion.REGRESSION_OBSERVED && regressedScenarioIds.isEmpty()) {
            throw new IllegalArgumentException("An observed regression requires an affected scenario");
        }
        if (conclusion == Conclusion.NO_REGRESSION_OBSERVED && !regressedScenarioIds.isEmpty()) {
            throw new IllegalArgumentException("A no-regression observation cannot contain regressions");
        }
    }

    public boolean appliesTo(ComparisonDefinition currentDefinition) {
        return definition.equals(Objects.requireNonNull(currentDefinition, "currentDefinition"));
    }

    /** Every conclusion is limited to the scenarios in {@link ComparisonAssessment#definition()}. */
    public enum Conclusion {
        UNRESOLVED, REGRESSION_OBSERVED, NO_REGRESSION_OBSERVED
    }

    public record Issue(Reason reason, String detail) {
        public Issue {
            Objects.requireNonNull(reason, "reason");
            detail = Checks.nonBlank(detail, "detail");
        }
    }

    public enum Reason {
        NON_COMPARABLE_INPUTS,
        MISSING_OBSERVATION,
        UNDECLARED_SCENARIO,
        DUPLICATE_OBSERVATIONS,
        INPUT_MISMATCH,
        EXECUTION_INCOMPLETE,
        BASELINE_ASSERTION_FAILED
    }
}
