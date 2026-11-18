package io.github.nktogo.apicompatibility.runner;

import java.util.List;

/** Source closure shared by the curated build and repository capture. */
public final class InvoiceConsumerSourceCatalog {
    public static final String DIRECTORY = "consumer-sources-v1";
    public static final String REGISTRATION = "build-registration.json";
    public static final String REGISTRATION_SHA256 = "1f74dcacd771d6a9202186fa501e29dc97426c60452887ec42835ccf49463610";
    static final String PACKAGE = "io/github/nktogo/invoiceconsumer/";
    static final String CORE = "core/src/main/java/" + PACKAGE + "RenewalPolicy.java";
    public static final List<String> FILES = List.of(
            REGISTRATION, "core/pom.xml", CORE, "pom.xml", "reconciliation/pom.xml",
            "reconciliation/src/acacia/java/" + PACKAGE + "InvoiceReconciliationConsumer.java",
            "reconciliation/src/basil/java/" + PACKAGE + "InvoiceReconciliationConsumer.java",
            "webhook/pom.xml",
            "webhook/src/acacia/java/" + PACKAGE + "InvoiceWebhookConsumer.java",
            "webhook/src/basil/java/" + PACKAGE + "InvoiceWebhookConsumer.java");

    private InvoiceConsumerSourceCatalog() {}
}
