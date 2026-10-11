package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalVariantFixtures.*;

/** Authored unavailable-state controls, not live lookup or consumer execution evidence. */
final class InvoiceRenewalStateFixtures {
    static final String BUNDLE_SHA256 = "71a4413a2a85e9380aa7f4188f2de2dbf5941e4c143793c4b49f30aa52987a48";
    private static final List<Variant> VARIANTS = List.of(
            new Variant("absent-state-acacia", "unavailable-state", "acacia", Layout.SINGLE, StateEvidence.ABSENT),
            new Variant("failed-state-acacia", "unavailable-state", "acacia", Layout.SINGLE, StateEvidence.FAILED),
            new Variant("inactive-state-acacia", "unavailable-state", "acacia", Layout.SINGLE, StateEvidence.INACTIVE),
            new Variant("wrong-customer-state-acacia", "unavailable-state", "acacia", Layout.SINGLE, StateEvidence.WRONG_CUSTOMER),
            new Variant("wrong-subscription-state-acacia", "unavailable-state", "acacia", Layout.SINGLE, StateEvidence.WRONG_SUBSCRIPTION),
            new Variant("absent-state-basil", "unavailable-state", "basil", Layout.SINGLE, StateEvidence.ABSENT),
            new Variant("failed-state-basil", "unavailable-state", "basil", Layout.SINGLE, StateEvidence.FAILED),
            new Variant("inactive-state-basil", "unavailable-state", "basil", Layout.SINGLE, StateEvidence.INACTIVE),
            new Variant("wrong-customer-state-basil", "unavailable-state", "basil", Layout.SINGLE, StateEvidence.WRONG_CUSTOMER),
            new Variant("wrong-subscription-state-basil", "unavailable-state", "basil", Layout.SINGLE, StateEvidence.WRONG_SUBSCRIPTION));
    private InvoiceRenewalStateFixtures() {}
    static Bundle load(Path root) throws IOException {
        return InvoiceRenewalVariantFixtures.load(root, "state-v1", BUNDLE_SHA256, "SUBSCRIPTION_STATE_VARIANTS_ONLY", VARIANTS);
    }
}
