package io.github.nktogo.apicompatibility.compatibility;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A declared input transition and its complete required scenario set.
 * Accepting this input shape does not establish adapter support for a provider or version.
 */
public record ComparisonDefinition(
        InputSnapshot baseline,
        InputSnapshot target,
        List<String> requiredScenarioIds) {

    public ComparisonDefinition {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(target, "target");
        requiredScenarioIds = List.copyOf(requiredScenarioIds);
        if (requiredScenarioIds.isEmpty()) {
            throw new IllegalArgumentException("At least one required scenario is necessary");
        }
        requiredScenarioIds.forEach(id -> Checks.nonBlank(id, "scenarioId"));
        if (new HashSet<>(requiredScenarioIds).size() != requiredScenarioIds.size()) {
            throw new IllegalArgumentException("Required scenario IDs must be unique");
        }
    }
}
