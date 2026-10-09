package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalVariantFixtures.Variant;

/** Authored absence and malformed-association controls, not alleged provider observations. */
final class InvoiceRenewalRefusalFixtures {
    static final String BUNDLE_SHA256 = "4a0eb74bce5f89b8ec85f200bda3dc5b2d7d16fa828bcac64fc7389521173f9f";
    private static final List<Variant> VARIANTS = List.of(
            new Variant("missing-field-acacia", "missing-association", "acacia"),
            new Variant("null-association-acacia", "missing-association", "acacia"),
            new Variant("missing-parent-basil", "missing-association", "basil"),
            new Variant("null-parent-basil", "missing-association", "basil"),
            new Variant("missing-details-basil", "missing-association", "basil"),
            new Variant("null-details-basil", "missing-association", "basil"),
            new Variant("missing-id-basil", "missing-association", "basil"),
            new Variant("null-id-basil", "missing-association", "basil"),
            new Variant("unknown-parent-basil", "contradictory-parent", "basil"),
            new Variant("quote-with-subscription-basil", "contradictory-parent", "basil"),
            new Variant("conflicting-fields-basil", "contradictory-parent", "basil"));
    private InvoiceRenewalRefusalFixtures() {}
    static InvoiceRenewalVariantFixtures.Bundle load(Path root) throws IOException {
        return InvoiceRenewalVariantFixtures.load(root, "refusal-v1", BUNDLE_SHA256, "ASSOCIATION_REFUSALS_ONLY", VARIANTS);
    }
}
