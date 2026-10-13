package io.github.nktogo.invoiceconsumer;

import com.google.gson.JsonObject;
import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.net.ApiResource;

/** Authored basil consumer; expects prior host admission and performs no provider requests. */
public final class InvoiceWebhookConsumer {
    private InvoiceWebhookConsumer() {}
    public static JsonObject consume(JsonObject exchange) {
        var input = RenewalPolicy.input(exchange, "invoice-webhook", Stripe.API_VERSION);
        var payload = input.getAsJsonObject("source").getAsJsonObject("payload");
        var event = ApiResource.GSON.fromJson(payload, Event.class);
        RenewalPolicy.require("invoice.paid".equals(event.getType()) && Stripe.API_VERSION.equals(event.getApiVersion()), "Unsupported webhook snapshot");
        var object = event.getDataObjectDeserializer().getObject().orElseThrow(() -> new IllegalArgumentException("No supported event object"));
        RenewalPolicy.require(object instanceof Invoice, "Unexpected event object");
        var invoice = (Invoice) object;
        var raw = payload.getAsJsonObject("data").getAsJsonObject("object");
        var parent = invoice.getParent();
        String subscription = parent != null && "subscription_details".equals(parent.getType()) && parent.getSubscriptionDetails() != null
                ? parent.getSubscriptionDetails().getSubscription() : null;
        return RenewalPolicy.evaluate(exchange, raw, new RenewalPolicy.InvoiceValues(invoice.getId(), invoice.getCustomer(), subscription,
                invoice.getStatus(), invoice.getBillingReason()), Stripe.API_VERSION);
    }
}
