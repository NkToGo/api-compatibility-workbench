package io.github.nktogo.invoiceconsumer;

import com.google.gson.JsonObject;
import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Explicit Acacia companion under a Basil main runtime; no provider requests or host expectations. */
public final class VersionedInvoiceWebhookConsumer {
    private static final String ACACIA = "2025-02-24.acacia", BASIL = "2025-03-31.basil";
    private VersionedInvoiceWebhookConsumer() {}
    public static JsonObject consume(JsonObject exchange) throws IOException, ReflectiveOperationException {
        RenewalPolicy.require("29.0.0".equals(Stripe.class.getField("VERSION").get(null))
                && BASIL.equals(Stripe.class.getField("API_VERSION").get(null)), "Main runtime mismatch");
        var input = RenewalPolicy.input(exchange, "invoice-webhook", ACACIA);
        var configuration = input.getAsJsonObject("configuration");
        RenewalPolicy.require("29.0.0".equals(RenewalPolicy.text(configuration, "sdkVersion"))
                && ACACIA.equals(RenewalPolicy.text(configuration, "adapterApiVersion"))
                && BASIL.equals(RenewalPolicy.text(configuration, "requestApiVersion"))
                && BASIL.equals(RenewalPolicy.text(configuration, "endpointApiVersion")), "Unsupported version route");
        var payload = input.getAsJsonObject("source").getAsJsonObject("payload");
        var event = ApiResource.GSON.fromJson(payload, Event.class);
        RenewalPolicy.require("invoice.paid".equals(event.getType()) && ACACIA.equals(event.getApiVersion()), "Unsupported webhook snapshot");
        RenewalPolicy.require(event.getDataObjectDeserializer().getObject().isEmpty(), "Unexpected primary parser acceptance");
        var urls = new java.net.URL[] {runtimePath("classes").toUri().toURL(), runtimePath("sdk").toUri().toURL(), runtimePath("gson").toUri().toURL()};
        String[] values;
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            var adapter = loader.loadClass("io.github.nktogo.invoiceconsumer.legacy.AcaciaInvoiceAdapter");
            values = (String[]) adapter.getMethod("read", String.class).invoke(null, payload.toString());
        }
        RenewalPolicy.require(values != null && values.length == 5, "Invalid adapter values");
        return RenewalPolicy.evaluate(exchange, payload.getAsJsonObject("data").getAsJsonObject("object"),
                new RenewalPolicy.InvoiceValues(values[0], values[1], values[2], values[3], values[4]), ACACIA);
    }
    private static Path runtimePath(String component) {
        String value = System.getProperty("workbench.invoice.legacy." + component);
        RenewalPolicy.require(value != null && !value.isBlank(), "Missing companion runtime"); return Path.of(value);
    }
}
