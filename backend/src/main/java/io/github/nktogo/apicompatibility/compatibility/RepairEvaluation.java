package io.github.nktogo.apicompatibility.compatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Judges supplied observations; does not execute code, authenticate evidence or approve releases. */
public final class RepairEvaluation {
    private RepairEvaluation() {}

    public enum Conclusion {
        UNRESOLVED, REGRESSIONS_REMAIN, ALL_OBSERVED_REGRESSIONS_FIXED, NO_REPAIR_NEEDED
    }
    public enum Outcome { FIXED, STILL_FAILING, NEW_FAILURE, STILL_PASSING, UNRESOLVED }
    public record Scenario(String scenarioId, Outcome outcome) {}

    /** One original transition and a source-only repair under the same target conditions. */
    public record Definition(ComparisonDefinition transition, InputSnapshot repairedTarget) {
        public Definition {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(repairedTarget, "repairedTarget");
        }
    }

    /** Instances are derived by evaluate, not accepted as an agent's claimed repair result. */
    public static final class Assessment {
        private final Definition definition;
        private final Conclusion conclusion;
        private final List<Scenario> scenarios;
        private final ComparisonAssessment originalComparison;
        private final ComparisonAssessment repairedComparison;
        private final List<String> boundaryIssues;

        private Assessment(Definition definition, Conclusion conclusion, List<Scenario> scenarios,
                           ComparisonAssessment original, ComparisonAssessment repaired, List<String> issues) {
            this.definition = definition; this.conclusion = conclusion;
            this.scenarios = List.copyOf(scenarios);
            originalComparison = original; repairedComparison = repaired;
            boundaryIssues = List.copyOf(issues);
        }
        public Definition definition() { return definition; }
        public Conclusion conclusion() { return conclusion; }
        public List<Scenario> scenarios() { return scenarios; }
        public ComparisonAssessment originalComparison() { return originalComparison; }
        public ComparisonAssessment repairedComparison() { return repairedComparison; }
        public List<String> boundaryIssues() { return boundaryIssues; }
        public long count(Outcome outcome) { return scenarios.stream().filter(s -> s.outcome() == outcome).count(); }
        public boolean appliesTo(Definition current) { return definition.equals(Objects.requireNonNull(current)); }
    }

    public static Assessment evaluate(Definition definition, List<ExecutionObservation> baseline,
                                      List<ExecutionObservation> target, List<ExecutionObservation> repaired) {
        Objects.requireNonNull(definition, "definition");
        baseline = List.copyOf(baseline); target = List.copyOf(target); repaired = List.copyOf(repaired);
        var transition = definition.transition();
        var repairedDefinition = new ComparisonDefinition(transition.baseline(), definition.repairedTarget(),
                transition.requiredScenarioIds());
        var originalComparison = CompatibilityComparison.compare(transition, baseline, target);
        var repairedComparison = CompatibilityComparison.compare(repairedDefinition, baseline, repaired);
        var boundaryIssues = new ArrayList<String>();
        if (!sameTargetConditions(transition.target(), definition.repairedTarget())) {
            boundaryIssues.add("Repair may change source only; case, provider version, configuration, expectations, fixtures, harness and environment must match the unrepaired target");
        }
        var scenarios = new ArrayList<Scenario>();
        for (String id : transition.requiredScenarioIds()) {
            // Per-scenario judgments preserve known failures even if another scenario is unresolved.
            var before = CompatibilityComparison.compare(new ComparisonDefinition(transition.baseline(), transition.target(), List.of(id)),
                    select(baseline, id), select(target, id));
            var after = CompatibilityComparison.compare(new ComparisonDefinition(transition.baseline(), definition.repairedTarget(), List.of(id)),
                    select(baseline, id), select(repaired, id));
            Outcome outcome;
            if (!boundaryIssues.isEmpty() || before.conclusion() == ComparisonAssessment.Conclusion.UNRESOLVED
                    || after.conclusion() == ComparisonAssessment.Conclusion.UNRESOLVED) outcome = Outcome.UNRESOLVED;
            else if (!before.regressedScenarioIds().isEmpty())
                outcome = after.regressedScenarioIds().isEmpty() ? Outcome.FIXED : Outcome.STILL_FAILING;
            else outcome = after.regressedScenarioIds().isEmpty() ? Outcome.STILL_PASSING : Outcome.NEW_FAILURE;
            scenarios.add(new Scenario(id, outcome));
        }
        Conclusion conclusion;
        if (!boundaryIssues.isEmpty() || originalComparison.conclusion() == ComparisonAssessment.Conclusion.UNRESOLVED
                || repairedComparison.conclusion() == ComparisonAssessment.Conclusion.UNRESOLVED) conclusion = Conclusion.UNRESOLVED;
        else if (!repairedComparison.regressedScenarioIds().isEmpty()) conclusion = Conclusion.REGRESSIONS_REMAIN;
        else if (originalComparison.regressedScenarioIds().isEmpty()) conclusion = Conclusion.NO_REPAIR_NEEDED;
        else conclusion = Conclusion.ALL_OBSERVED_REGRESSIONS_FIXED;
        return new Assessment(definition, conclusion, scenarios, originalComparison, repairedComparison, boundaryIssues);
    }

    private static List<ExecutionObservation> select(List<ExecutionObservation> values, String id) {
        return values.stream().filter(value -> value.scenarioId().equals(id)).toList();
    }

    private static boolean sameTargetConditions(InputSnapshot target, InputSnapshot repaired) {
        return target.caseId().equals(repaired.caseId()) && target.providerId().equals(repaired.providerId())
                && target.providerVersion().equals(repaired.providerVersion())
                && target.configuration().equals(repaired.configuration()) && target.expectations().equals(repaired.expectations())
                && target.fixtures().equals(repaired.fixtures()) && target.harness().equals(repaired.harness())
                && target.environment().equals(repaired.environment());
    }
}
