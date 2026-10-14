package io.github.nktogo.invoiceconsumer.legacy;

import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.net.ApiResource;

/** Loaded only in the explicit companion loader; SDK objects never cross its boundary. */
public final class AcaciaInvoiceAdapter {
    private AcaciaInvoiceAdapter() {}
    public static String[] read(String json) throws ReflectiveOperationException {
        if (!"28.4.0".equals(Stripe.class.getField("VERSION").get(null))
                || !"2025-02-24.acacia".equals(Stripe.class.getField("API_VERSION").get(null)))
            throw new IllegalArgumentException("Companion runtime mismatch");
        var event = ApiResource.GSON.fromJson(json, Event.class);
        if (!"invoice.paid".equals(event.getType()) || !Stripe.API_VERSION.equals(event.getApiVersion()))
            throw new IllegalArgumentException("Unsupported companion event");
        var object = event.getDataObjectDeserializer().getObject().orElseThrow(() -> new IllegalArgumentException("Companion parser refusal"));
        if (!(object instanceof Invoice invoice)) throw new IllegalArgumentException("Unexpected companion object");
        return new String[] {invoice.getId(), invoice.getCustomer(), invoice.getSubscription(), invoice.getStatus(), invoice.getBillingReason()};
    }
}
