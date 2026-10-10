package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalVariantFixtures.*;

/** Compiled repeat/conflict catalog with separately held initial state and expected outcomes. */
final class InvoiceRenewalDeliveryFixtures {
    static final String BUNDLE_SHA256 = "9f8a794fd81f34ca322b9b3d867bc036080231255f716ce1bf7c0b7bc92af376";
    private InvoiceRenewalDeliveryFixtures() {}
    static Bundle load(Path root) throws IOException {
        var variants = new ArrayList<Variant>();
        for (String train : List.of("acacia", "basil")) {
            variants.add(new Variant("duplicate-delivery-" + train, "duplicate-delivery", train, Layout.REPEATED));
            variants.add(new Variant("seeded-conflict-" + train, "conflicting-association", train, Layout.SEEDED_CONFLICT));
            variants.add(new Variant("fresh-conflict-" + train, "conflicting-association", train, Layout.FRESH_CONFLICT));
        }
        return InvoiceRenewalVariantFixtures.load(root, "delivery-v1", BUNDLE_SHA256, "DELIVERY_VARIANTS_ONLY", variants);
    }
}
