package io.github.nktogo.apicompatibility.compatibility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Conclusion;
import static io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Issue;
import static io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Reason;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.AssertionOutcome;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.Disposition;

/** Deterministic comparison of reported observations; it neither runs tests nor authenticates evidence. */
public final class CompatibilityComparison {
    private CompatibilityComparison() {
    }

    /**
     * Source and configuration may change across the declared transition, including an SDK
     * upgrade. Therefore a regression is associated with the whole transition, not attributed
     * solely to the provider version. All required scenarios must have exactly one completed
     * assertion on each side; duplicates require explicit reconciliation outside this model.
     */
    public static ComparisonAssessment compare(
            ComparisonDefinition definition,
            List<ExecutionObservation> baselineObservations,
            List<ExecutionObservation> targetObservations) {
        Objects.requireNonNull(definition, "definition");
        baselineObservations = List.copyOf(baselineObservations);
        targetObservations = List.copyOf(targetObservations);
        var issues = new ArrayList<Issue>();
        var regressions = new ArrayList<String>();

        if (!comparable(definition.baseline(), definition.target())) {
            issues.add(new Issue(Reason.NON_COMPARABLE_INPUTS,
                    "Require one case and provider, changed source/configuration/provider version, and identical expectations, "
                            + "fixture bundle, harness and environment"));
            return new ComparisonAssessment(definition, Conclusion.UNRESOLVED, regressions, issues);
        }

        var baseline = inspect("baseline", definition.baseline(), definition.requiredScenarioIds(),
                baselineObservations, issues);
        var target = inspect("target", definition.target(), definition.requiredScenarioIds(),
                targetObservations, issues);

        for (String scenario : definition.requiredScenarioIds()) {
            var before = baseline.get(scenario);
            var after = target.get(scenario);
            if (before == AssertionOutcome.FAILED) {
                issues.add(new Issue(Reason.BASELINE_ASSERTION_FAILED,
                        "Baseline did not satisfy the declared expectation for " + scenario));
            }
            if (before == AssertionOutcome.PASSED && after == AssertionOutcome.FAILED) {
                regressions.add(scenario);
            }
        }

        Conclusion conclusion = !issues.isEmpty() ? Conclusion.UNRESOLVED
                : regressions.isEmpty() ? Conclusion.NO_REGRESSION_OBSERVED : Conclusion.REGRESSION_OBSERVED;
        return new ComparisonAssessment(definition, conclusion, regressions, issues);
    }

    private static boolean comparable(InputSnapshot baseline, InputSnapshot target) {
        return baseline.caseId().equals(target.caseId())
                && baseline.providerId().equals(target.providerId())
                && (!baseline.source().equals(target.source())
                    || !baseline.configuration().equals(target.configuration())
                    || !baseline.providerVersion().equals(target.providerVersion()))
                && baseline.expectations().equals(target.expectations())
                && baseline.fixtures().equals(target.fixtures())
                && baseline.harness().equals(target.harness())
                && baseline.environment().equals(target.environment());
    }

    private static Map<String, AssertionOutcome> inspect(
            String side,
            InputSnapshot expected,
            List<String> required,
            List<ExecutionObservation> observations,
            List<Issue> issues) {
        var byScenario = new HashMap<String, List<ExecutionObservation>>();
        for (var observation : observations) {
            if (!required.contains(observation.scenarioId())) {
                issues.add(new Issue(Reason.UNDECLARED_SCENARIO,
                        side + " contains undeclared scenario " + observation.scenarioId()));
                continue;
            }
            byScenario.computeIfAbsent(observation.scenarioId(), ignored -> new ArrayList<>()).add(observation);
        }

        var completed = new HashMap<String, AssertionOutcome>();
        for (String scenario : required) {
            var matches = byScenario.getOrDefault(scenario, List.of());
            if (matches.isEmpty()) {
                issues.add(new Issue(Reason.MISSING_OBSERVATION, side + " is missing scenario " + scenario));
                continue;
            }
            if (matches.size() != 1) {
                issues.add(new Issue(Reason.DUPLICATE_OBSERVATIONS,
                        side + " contains multiple observations for " + scenario));
                continue;
            }
            var observation = matches.getFirst();
            if (!expected.equals(observation.inputs())) {
                issues.add(new Issue(Reason.INPUT_MISMATCH, side + " input identity differs for " + scenario));
                continue;
            }
            if (observation.disposition() != Disposition.COMPLETED
                    || observation.assertionOutcome() == AssertionOutcome.NOT_EVALUATED) {
                issues.add(new Issue(Reason.EXECUTION_INCOMPLETE,
                        side + " has no completed assertion for " + scenario + " (" + observation.disposition() + ")"));
                continue;
            }
            completed.put(scenario, observation.assertionOutcome());
        }
        return completed;
    }
}
