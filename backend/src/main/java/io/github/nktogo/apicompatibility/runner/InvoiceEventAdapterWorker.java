package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fixed container-only SDK parser. No consumer source, credentials, business answers or ledger. */
public final class InvoiceEventAdapterWorker {
    private InvoiceEventAdapterWorker() {}
    public static void main(String[] args) {
        try { execute(); }
        catch (Exception | StackOverflowError e) { System.err.println("Invoice adapter worker failed"); System.exit(4); }
    }
    private static void execute() throws Exception {
        ContainerCaseMain.verifyIsolation();
        byte[] prefix = System.in.readNBytes(4); CaseInputs.require(prefix.length == 4, "Missing adapter startup length");
        int length = ByteBuffer.wrap(prefix).getInt(); CaseInputs.require(length > 0 && length <= 400000, "Invalid adapter startup size");
        byte[] startup = System.in.readNBytes(length); CaseInputs.require(startup.length == length, "Truncated adapter startup");
        var input = InvoiceRenewalFixtures.object(startup); InvoiceRenewalFixtures.fields(input, "mainSdk", "adapterApiVersion", "source", "dependencies");
        String main = InvoiceRenewalFixtures.text(input, "mainSdk"), version = InvoiceRenewalFixtures.text(input, "adapterApiVersion");
        CaseInputs.require(InvoiceWebhookAdapters.supported(main, version), "Unsupported adapter route");
        var dependencies = input.getAsJsonObject("dependencies");
        CaseInputs.require(dependencies.keySet().equals(InvoiceWebhookAdapters.DEPENDENCIES), "Invalid adapter runtime closure");
        for (String name : dependencies.keySet()) CaseInputs.require(CaseInputs.hash(Files.readAllBytes(Path.of("/opt/workbench/dependencies", name)))
                .equals(InvoiceRenewalFixtures.text(dependencies, name)), "Adapter image dependency mismatch");
        byte[] source = Base64.getDecoder().decode(InvoiceRenewalFixtures.text(input, "source"));
        CaseInputs.require(source.length > 0 && source.length <= InvoiceWebhookSignatures.MAX_SOURCE_BYTES, "Invalid adapter source size");
        var event = InvoiceRenewalFixtures.object(source);
        CaseInputs.require(version.equals(InvoiceRenewalFixtures.text(event, "api_version")) && "invoice.paid".equals(InvoiceRenewalFixtures.text(event, "type")), "Adapter event version/type mismatch");
        String json = new String(source, StandardCharsets.UTF_8), selected = InvoiceWebhookAdapters.sdk(version);
        JsonObject primary = parse(main, json), adapted = main.equals(selected) ? primary : parse(selected, json);
        var report = new JsonObject(); report.addProperty("sourceIdentity", CaseInputs.hash(source));
        report.addProperty("mainSdk", main); report.addProperty("mainApiVersion", InvoiceWebhookAdapters.api(main));
        report.addProperty("adapterSdk", selected); report.addProperty("adapterApiVersion", version);
        report.addProperty("primaryAccepted", primary != null); report.addProperty("adapterAccepted", adapted != null);
        report.add("invoice", adapted == null ? JsonNull.INSTANCE : adapted);
        var message = new JsonObject(); message.addProperty("op", "adapter"); message.add("report", report);
        System.out.println(new GsonBuilder().serializeNulls().create().toJson(message)); System.out.flush();
        CaseInputs.require(Arrays.equals(System.in.readNBytes(3), "{}\n".getBytes(StandardCharsets.UTF_8)), "Invalid adapter acknowledgement");
    }
    private static JsonObject parse(String sdk, String json) throws Exception {
        var urls = new java.net.URL[] {Path.of("/opt/workbench/dependencies/stripe-java-" + sdk + ".jar").toUri().toURL(),
                Path.of("/opt/workbench/dependencies/gson-2.10.1.jar").toUri().toURL()};
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            var stripe = loader.loadClass("com.stripe.Stripe");
            CaseInputs.require(sdk.equals(stripe.getField("VERSION").get(null)) && InvoiceWebhookAdapters.api(sdk).equals(stripe.getField("API_VERSION").get(null)), "Adapter library version mismatch");
            CaseInputs.require(stripe.getField("apiKey").get(null) == null, "Adapter credential boundary violated");
            var eventClass = loader.loadClass("com.stripe.model.Event");
            Object gson = loader.loadClass("com.stripe.net.ApiResource").getField("GSON").get(null);
            try {
                Object event = gson.getClass().getMethod("fromJson", String.class, Class.class).invoke(gson, json, eventClass);
                Object deserializer = eventClass.getMethod("getDataObjectDeserializer").invoke(event);
                var object = (Optional<?>) deserializer.getClass().getMethod("getObject").invoke(deserializer);
                if (object.isEmpty() || !object.get().getClass().getName().equals("com.stripe.model.Invoice")) return null;
                Object invoice = object.get(); var result = new JsonObject();
                result.addProperty("invoiceId", text(invoice, "getId")); result.addProperty("customerId", text(invoice, "getCustomer"));
                String subscription;
                if (sdk.equals("28.4.0")) subscription = text(invoice, "getSubscription");
                else {
                    Object parent = call(invoice, "getParent"); Object details = parent != null && "subscription_details".equals(text(parent, "getType")) ? call(parent, "getSubscriptionDetails") : null;
                    subscription = details == null ? null : text(details, "getSubscription");
                }
                result.addProperty("subscriptionId", subscription); result.addProperty("status", text(invoice, "getStatus"));
                result.addProperty("billingReason", text(invoice, "getBillingReason")); return result;
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (loader.loadClass("com.google.gson.JsonParseException").isInstance(e.getCause())) return null;
                throw e;
            }
        }
    }
    private static Object call(Object object, String method) throws ReflectiveOperationException { return object.getClass().getMethod(method).invoke(object); }
    private static String text(Object object, String method) throws ReflectiveOperationException { return (String) call(object, method); }
}
