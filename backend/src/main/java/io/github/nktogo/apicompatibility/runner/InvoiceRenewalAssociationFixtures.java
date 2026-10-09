package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Compiled association catalog; source bytes and identities are unchanged. */
final class InvoiceRenewalAssociationFixtures {
    static final String BUNDLE_SHA256 = "991c268eb208ba25a1704ed7583e85d00cfa5165400f522eadd6a5f3ffff0940";
    private InvoiceRenewalAssociationFixtures() {}
    static InvoiceRenewalVariantFixtures.Bundle load(Path root) throws IOException {
        var variants = new ArrayList<InvoiceRenewalVariantFixtures.Variant>();
        for (String train : List.of("acacia", "basil"))
            for (String name : List.of("expanded-webhook", "expanded-reconciliation", "two-subscriptions", "standalone-invoice", "quote-parent"))
                variants.add(new InvoiceRenewalVariantFixtures.Variant(name + "-" + train,
                        name.startsWith("expanded-") ? "expanded-association" : name, train));
        return InvoiceRenewalVariantFixtures.load(root, "association-v1", BUNDLE_SHA256, "ASSOCIATION_VARIANTS_ONLY", List.copyOf(variants));
    }
}
