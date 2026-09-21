package io.github.nktogo.apicompatibility.compatibility;

import java.util.Objects;

/**
 * A declared SHA-256 identity for content stored outside this domain model.
 * Format validation does not calculate a digest or authenticate the referenced content.
 */
public record ContentDigest(String sha256) {
    public ContentDigest {
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain exactly 64 lowercase hexadecimal characters");
        }
    }
}
