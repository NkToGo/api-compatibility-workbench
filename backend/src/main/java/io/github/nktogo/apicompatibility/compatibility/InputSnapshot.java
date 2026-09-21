package io.github.nktogo.apicompatibility.compatibility;

import java.util.Objects;

/**
 * Exact declared inputs for one side of a compatibility case.
 * Digests identify the source tree and all relevant configuration, approved expectations,
 * fixture bundle, verification harness and execution environment. Configuration content
 * should reference secret versions, never embed secret values in this record.
 * Metadata equality does not authenticate evidence or protect expectations from mutation.
 */
public record InputSnapshot(
        String caseId,
        String providerId,
        String providerVersion,
        ContentDigest source,
        ContentDigest configuration,
        ContentDigest expectations,
        ContentDigest fixtures,
        ContentDigest harness,
        ContentDigest environment) {

    public InputSnapshot {
        caseId = Checks.nonBlank(caseId, "caseId");
        providerId = Checks.nonBlank(providerId, "providerId");
        providerVersion = Checks.nonBlank(providerVersion, "providerVersion");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(expectations, "expectations");
        Objects.requireNonNull(fixtures, "fixtures");
        Objects.requireNonNull(harness, "harness");
        Objects.requireNonNull(environment, "environment");
    }
}
