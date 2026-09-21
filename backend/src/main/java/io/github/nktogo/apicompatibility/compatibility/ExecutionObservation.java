package io.github.nktogo.apicompatibility.compatibility;

import java.util.Objects;

/**
 * A reported execution of one declared behavioral assertion, not an interpretation by
 * an agent or a successful compilation. The future runner must supply authentic results;
 * constructing this record alone cannot establish that execution actually occurred.
 */
public record ExecutionObservation(
        String scenarioId,
        InputSnapshot inputs,
        Disposition disposition,
        AssertionOutcome assertionOutcome) {

    public ExecutionObservation {
        scenarioId = Checks.nonBlank(scenarioId, "scenarioId");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(assertionOutcome, "assertionOutcome");
        if (disposition != Disposition.COMPLETED && assertionOutcome != AssertionOutcome.NOT_EVALUATED) {
            throw new IllegalArgumentException("An incomplete execution cannot report an assertion result");
        }
    }

    public enum Disposition {
        COMPLETED, TIMED_OUT, CANCELLED, INFRASTRUCTURE_ERROR
    }

    public enum AssertionOutcome {
        PASSED, FAILED, NOT_EVALUATED
    }
}
