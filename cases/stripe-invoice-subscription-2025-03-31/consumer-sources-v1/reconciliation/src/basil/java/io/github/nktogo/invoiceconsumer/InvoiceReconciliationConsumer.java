package io.github.nktogo.invoiceconsumer;

import com.google.gson.JsonObject;
import com.stripe.Stripe;
import com.stripe.model.Invoice;
import com.stripe.net.ApiResource;

/** Authored basil consumer; expects prior host admission and performs no provider requests. */
public final class InvoiceReconciliationConsumer {
    private InvoiceReconciliationConsumer() {}
    public static JsonObject consume(JsonObject exchange) {
        var input = RenewalPolicy.input(exchange, "invoice-reconciliation", Stripe.API_VERSION);
        var payload = input.getAsJsonObject("source").getAsJsonObject("payload");
        RenewalPolicy.require(Stripe.API_VERSION.equals(RenewalPolicy.text(input.getAsJsonObject("configuration"), "requestApiVersion")), "Unsupported request version");
        var invoice = ApiResource.GSON.fromJson(payload, Invoice.class);
        var raw = payload;
        var parent = invoice.getParent();
        String subscription = parent != null && "subscription_details".equals(parent.getType()) && parent.getSubscriptionDetails() != null
                ? parent.getSubscriptionDetails().getSubscription() : null;
        return RenewalPolicy.evaluate(exchange, raw, new RenewalPolicy.InvoiceValues(invoice.getId(), invoice.getCustomer(), subscription,
                invoice.getStatus(), invoice.getBillingReason()), Stripe.API_VERSION);
    }
}
