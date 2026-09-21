package io.github.nktogo.apicompatibility.compatibility;

import java.util.Objects;

final class Checks {
    private Checks() {
    }

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be nonblank without surrounding whitespace");
        }
        return value;
    }
}
