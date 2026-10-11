package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalVariantFixtures.*;

/** Authored open-invoice controls, not provider observations or consumer execution evidence. */
final class InvoiceRenewalUnpaidFixtures {
    static final String BUNDLE_SHA256 = "a48cb128e73a59365e30938e66ba19e5d8050cbca6539ed92f024f8e5b074ec1";
    private static final List<Variant> VARIANTS = List.of(
            new Variant("open-fresh-acacia", "not-paid", "acacia"),
            new Variant("open-seeded-acacia", "not-paid", "acacia", Layout.SEEDED_REFUSAL),
            new Variant("open-fresh-basil", "not-paid", "basil"),
            new Variant("open-seeded-basil", "not-paid", "basil", Layout.SEEDED_REFUSAL));
    private InvoiceRenewalUnpaidFixtures() {}
    static Bundle load(Path root) throws IOException {
        return InvoiceRenewalVariantFixtures.load(root, "unpaid-v1", BUNDLE_SHA256, "UNPAID_VARIANTS_ONLY", VARIANTS);
    }
}
