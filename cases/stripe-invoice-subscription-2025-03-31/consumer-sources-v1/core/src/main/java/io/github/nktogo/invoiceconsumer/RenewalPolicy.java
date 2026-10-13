package io.github.nktogo.invoiceconsumer;

import com.google.gson.*;
import java.util.*;

/** Consumer business logic; host expectations and ledger effects are held separately. */
public final class RenewalPolicy {
    public record InvoiceValues(String id, String customer, String subscription, String status, String billingReason) {}
    private RenewalPolicy() {}

    public static JsonObject input(JsonObject exchange, String role, String apiVersion) {
        require(exchange.get("formatVersion").getAsInt() == 1, "Unsupported exchange");
        require(text(exchange, "inputIdentity") != null && text(exchange, "inputIdentity").matches("[0-9a-f]{64}"), "Missing input identity");
        require(text(exchange, "evidenceIdentity") != null && text(exchange, "evidenceIdentity").matches("[0-9a-f]{64}"), "Missing evidence identity");
        var input = exchange.getAsJsonObject("input");
        require(role.equals(text(input, "consumerId")), "Consumer role mismatch");
        require(apiVersion.equals(text(input, "effectiveApiVersion")), "Unsupported effective version");
        require(apiVersion.equals(text(input.getAsJsonObject("source"), "apiVersion")), "Source version mismatch");
        return input;
    }

    public static JsonObject evaluate(JsonObject exchange, JsonObject raw, InvoiceValues invoice, String apiVersion) {
        var input = exchange.getAsJsonObject("input"); String subscription = invoice.subscription();
        String associationIssue = association(raw, invoice, apiVersion);
        if (associationIssue != null) return fact(exchange, invoice, null, "UNRESOLVED", associationIssue, apiVersion);
        if (!"paid".equals(invoice.status())) return fact(exchange, invoice, subscription, "UNRESOLVED", "NOT_PAID", apiVersion);
        if (subscription == null) return fact(exchange, invoice, null, "NON_SUBSCRIPTION", null, apiVersion);
        boolean approved = false;
        for (var value : input.getAsJsonArray("approvedBindings")) {
            var binding = value.getAsJsonObject();
            if (Objects.equals(invoice.customer(), text(binding, "customerId")) && subscription.equals(text(binding, "subscriptionId"))) approved = true;
        }
        if (!approved) return fact(exchange, invoice, subscription, "UNRESOLVED", "UNAPPROVED_BINDING", apiVersion);
        String stateIssue = state(input.getAsJsonObject("subscriptionState"), invoice.customer(), subscription);
        return fact(exchange, invoice, subscription, stateIssue == null ? "RESOLVED" : "UNRESOLVED", stateIssue, apiVersion);
    }

    private static String association(JsonObject raw, InvoiceValues invoice, String apiVersion) {
        if (!"invoice".equals(text(raw, "object")) || invoice.id() == null || invoice.customer() == null) return "INVALID_ASSOCIATION";
        boolean subscriptionOrigin = "subscription_cycle".equals(invoice.billingReason()) || "subscription_create".equals(invoice.billingReason())
                || "subscription_update".equals(invoice.billingReason());
        boolean manual = "manual".equals(invoice.billingReason()), quote = "quote_accept".equals(invoice.billingReason());
        if (apiVersion.equals("2025-02-24.acacia")) {
            if (invoice.subscription() != null) return quote ? "INVALID_ASSOCIATION" : null;
            if (subscriptionOrigin || !raw.has("subscription")) return "MISSING_ASSOCIATION";
            return manual || quote ? null : "INVALID_ASSOCIATION";
        }
        var parent = raw.get("parent");
        if (parent == null || parent.isJsonNull()) {
            if (subscriptionOrigin || parent == null) return "MISSING_ASSOCIATION";
            return manual && invoice.subscription() == null ? null : "INVALID_ASSOCIATION";
        }
        if (!parent.isJsonObject()) return "INVALID_ASSOCIATION";
        var object = parent.getAsJsonObject(); String type = text(object, "type");
        if ("quote_details".equals(type)) return quote && invoice.subscription() == null
                && (!object.has("subscription_details") || object.get("subscription_details").isJsonNull()) ? null : "INVALID_ASSOCIATION";
        if (!"subscription_details".equals(type)) return "INVALID_ASSOCIATION";
        if (object.has("quote_details") && !object.get("quote_details").isJsonNull()) return "INVALID_ASSOCIATION";
        if (invoice.subscription() == null) return "MISSING_ASSOCIATION";
        if (raw.has("subscription") && !raw.get("subscription").isJsonNull()
                && !invoice.subscription().equals(reference(raw.get("subscription")))) return "INVALID_ASSOCIATION";
        return quote ? "INVALID_ASSOCIATION" : null;
    }
    private static String state(JsonObject descriptor, String customer, String subscription) {
        if (descriptor == null || descriptor.get("payload") == null || descriptor.get("payload").isJsonNull()) return "STATE_UNAVAILABLE";
        var payload = descriptor.getAsJsonObject("payload");
        if ("authored-state-lookup".equals(text(descriptor, "kind")))
            return "FAILED".equals(text(payload, "outcome")) ? "STATE_FAILED" : "STATE_UNAVAILABLE";
        JsonObject match;
        if ("list".equals(text(payload, "object"))) {
            if (!payload.has("data") || !payload.get("data").isJsonArray() || !payload.has("has_more") || payload.get("has_more").getAsBoolean()) return "STATE_UNAVAILABLE";
            var matches = new ArrayList<JsonObject>();
            for (var value : payload.getAsJsonArray("data")) if (value.isJsonObject() && subscription.equals(text(value.getAsJsonObject(), "id"))) matches.add(value.getAsJsonObject());
            if (matches.size() != 1) return "STATE_BINDING_MISMATCH"; match = matches.getFirst();
        } else match = payload;
        if (!"subscription".equals(text(match, "object")) || !subscription.equals(text(match, "id")) || !customer.equals(reference(match.get("customer")))) return "STATE_BINDING_MISMATCH";
        return "active".equals(text(match, "status")) ? null : "STATE_NOT_ACTIVE";
    }
    private static JsonObject fact(JsonObject exchange, InvoiceValues invoice, String subscription, String status, String reason, String version) {
        var result = new JsonObject(); result.addProperty("status", status); result.addProperty("invoiceId", invoice.id());
        result.addProperty("customerId", invoice.customer()); result.addProperty("subscriptionId", subscription);
        result.addProperty("effectiveApiVersion", version); result.addProperty("inputIdentity", text(exchange, "inputIdentity"));
        result.addProperty("evidenceIdentity", text(exchange, "evidenceIdentity")); result.addProperty("reason", reason); return result;
    }
    public static String text(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static String reference(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        return value.isJsonObject() ? text(value.getAsJsonObject(), "id")
                : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    public static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
