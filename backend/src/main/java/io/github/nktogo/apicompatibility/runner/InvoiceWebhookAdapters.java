package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Authenticated source to observed SDK parsing; never a business verdict or consumer execution grant. */
final class InvoiceWebhookAdapters {
    static final Set<String> DEPENDENCIES = Set.of("stripe-java-28.4.0.jar", "stripe-java-29.0.0.jar", "gson-2.10.1.jar");
    enum Outcome { VERIFIED, AUTHENTICATION_REQUIRED, ABSENT, REJECTED, NOT_EVALUATED }
    static String api(String sdk) { return switch (sdk) { case "28.4.0" -> "2025-02-24.acacia"; case "29.0.0" -> "2025-03-31.basil"; default -> null; }; }
    static String sdk(String api) { return switch (api) { case "2025-02-24.acacia" -> "28.4.0"; case "2025-03-31.basil" -> "29.0.0"; default -> null; }; }
    static boolean supported(String main, String version) {
        return main != null && version != null && api(main) != null && sdk(version) != null
                && (main.equals("29.0.0") || api(main).equals(version));
    }
    record Prepared(Outcome rejection, String mainSdk, String adapterVersion, String sourceIdentity, JsonObject projection) {
        Prepared { projection = projection == null ? null : projection.deepCopy(); }
        @Override public JsonObject projection() { return projection == null ? null : projection.deepCopy(); }
        boolean ready() { return rejection == null; }
        @Override public String toString() { return "Invoice adapter preparation [" + (ready() ? "READY" : rejection) + "]"; }
    }
    static final class Receipt {
        private final InvoiceWebhookSignatures.Scope scope;
        private final String source, signature, main, version;
        private final Outcome outcome;
        private final JsonObject evidence;
        private Receipt(InvoiceWebhookSignatures.Scope scope, String source, String signature, String main, String version, Outcome outcome, JsonObject evidence) {
            this.scope = scope; this.source = source; this.signature = signature;
            this.main = main != null && api(main) != null ? main : null; this.version = version != null && sdk(version) != null ? version : null;
            this.outcome = outcome; this.evidence = evidence.deepCopy();
            if (!this.evidence.has("configurationIdentity")) {
                var configuration = new JsonObject(); configuration.addProperty("mainSdk", main); configuration.addProperty("adapterApiVersion", version);
                this.evidence.addProperty("configurationIdentity", CaseInputs.identity(configuration));
            }
        }
        boolean verified() { return outcome == Outcome.VERIFIED; }
        Outcome outcome() { return outcome; }
        String sourceIdentity() { return source; }
        String mainSdk() { return main; }
        String adapterVersion() { return version; }
        boolean matches(InvoiceWebhookSignatures.Scope expected, InvoiceWebhookSignatures.Receipt authentication) {
            return scope.equals(expected) && authentication != null && authentication.verified()
                    && Objects.equals(signature, authentication.json().get("receiptIdentity").getAsString());
        }
        JsonObject json() {
            var result = evidence.deepCopy(); result.addProperty("formatVersion", 1); result.addProperty("outcome", outcome.name());
            result.addProperty("sourceIdentity", source); result.addProperty("signatureReceiptIdentity", signature);
            result.addProperty("mainSdk", main); result.addProperty("adapterApiVersion", version);
            result.addProperty("installationId", scope.installationId()); result.addProperty("endpointId", scope.endpointId()); result.addProperty("keySetVersion", scope.keySetVersion());
            result.addProperty("receiptIdentity", CaseInputs.identity(result)); return result;
        }
        @Override public String toString() { return "Invoice adapter receipt [" + outcome + "]"; }
    }
    private InvoiceWebhookAdapters() {}

    static Prepared prepare(byte[] source, String main, String version, InvoiceWebhookSignatures.Scope scope, InvoiceWebhookSignatures.Receipt signature) {
        Objects.requireNonNull(scope); String identity = source == null || source.length == 0 || source.length > InvoiceWebhookSignatures.MAX_SOURCE_BYTES ? null : CaseInputs.hash(source);
        if (signature == null || !signature.verified() || !signature.matchesScope(scope) || identity == null || !identity.equals(signature.sourceIdentity()))
            return new Prepared(Outcome.AUTHENTICATION_REQUIRED, main, version, identity, null);
        if (version == null) return new Prepared(Outcome.ABSENT, main, null, identity, null);
        if (!supported(main, version)) return new Prepared(Outcome.REJECTED, main, version, identity, null);
        try {
            var event = InvoiceRenewalFixtures.object(source);
            CaseInputs.require(version.equals(text(event, "api_version")) && "invoice.paid".equals(text(event, "type")), "Unsupported adapter event");
            var invoice = event.getAsJsonObject("data").getAsJsonObject("object");
            CaseInputs.require("invoice".equals(text(invoice, "object")), "Unsupported adapter object");
            return new Prepared(null, main, version, identity, project(invoice, version));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException e) { return new Prepared(Outcome.REJECTED, main, version, identity, null); }
    }
    static JsonObject startup(InvoiceConsumerBuilds.Registration registration, Prepared prepared, byte[] source) {
        CaseInputs.require(prepared.ready() && prepared.sourceIdentity().equals(CaseInputs.hash(source)), "Adapter source not prepared");
        var result = new JsonObject(); result.addProperty("mainSdk", prepared.mainSdk()); result.addProperty("adapterApiVersion", prepared.adapterVersion());
        result.addProperty("source", Base64.getEncoder().encodeToString(source)); var dependencies = new JsonObject();
        for (String name : new TreeSet<>(DEPENDENCIES)) dependencies.addProperty(name, registration.artifactIdentity(name)); result.add("dependencies", dependencies); return result;
    }
    static Receipt verify(InvoiceConsumerBuilds.Registration registration, byte[] source, String main, String version,
                          InvoiceWebhookSignatures.Scope scope, InvoiceWebhookSignatures.Receipt signature,
                          String image, String name, Duration deadline, BooleanSupplier stopped) throws Exception {
        byte[] bytes = source == null ? null : source.clone(); var prepared = prepare(bytes, main, version, scope, signature);
        var evidence = new JsonObject(); evidence.addProperty("executionAttempted", false);
        String authentication = signature == null ? null : signature.json().get("receiptIdentity").getAsString();
        if (!prepared.ready()) return new Receipt(scope, prepared.sourceIdentity(), authentication, main, version, prepared.rejection(), evidence);
        var protocol = new Protocol(prepared); String harness = InvoiceSandbox.harnessIdentity();
        var execution = RepairSandbox.runProtocol(image, name, startup(registration, prepared, bytes), InvoiceEventAdapterWorker.class.getName(), protocol, deadline, stopped);
        evidence.addProperty("executionAttempted", true); evidence.addProperty("harnessIdentity", harness); evidence.addProperty("imageId", image);
        evidence.addProperty("dependenciesIdentity", registration.dependenciesIdentity());
        evidence.addProperty("configurationIdentity", CaseInputs.identity(Map.of("mainSdk", main, "adapterApiVersion", version, "signatureReceiptIdentity", authentication)));
        evidence.addProperty("environmentIdentity", CaseInputs.identity(Map.of("imageId", image, "policy", "invoice-event-adapter-v1", "deadlineMillis", deadline.toMillis(),
                "arguments", RepairSandbox.command(image, "acw-00000000-0000-0000-0000-000000000000", InvoiceEventAdapterWorker.class.getName()))));
        for (String field : List.of("transportStatus", "protocolInvalid", "executionStatus", "workerExitCode", "containerRemoved")) evidence.add(field, execution.get(field).deepCopy());
        var report = execution.get("report"); boolean complete = "COMPLETED".equals(text(execution, "executionStatus")) && execution.get("workerExitCode").getAsInt() == 0
                && execution.get("containerRemoved").getAsBoolean() && !execution.get("protocolInvalid").getAsBoolean() && report.isJsonObject();
        if (report.isJsonObject()) {
            evidence.addProperty("reportIdentity", CaseInputs.identity(report)); var observed = report.getAsJsonObject();
            if (!execution.get("protocolInvalid").getAsBoolean())
                for (String field : List.of("mainApiVersion", "adapterSdk", "primaryAccepted", "adapterAccepted")) evidence.add(field, observed.get(field).deepCopy());
            if (observed.has("invoice") && observed.get("invoice").isJsonObject()) evidence.addProperty("projectionIdentity", CaseInputs.identity(observed.get("invoice")));
        }
        Outcome outcome = complete ? report.getAsJsonObject().get("adapterAccepted").getAsBoolean() ? Outcome.VERIFIED : Outcome.REJECTED : Outcome.NOT_EVALUATED;
        return new Receipt(scope, prepared.sourceIdentity(), authentication, main, version, outcome, evidence);
    }

    static final class Protocol implements WorkerOracle {
        private final Prepared prepared;
        private JsonObject report;
        private boolean invalid;
        private JsonObject sealed;
        Protocol(Prepared prepared) { CaseInputs.require(prepared.ready(), "Adapter input not prepared"); this.prepared = prepared; }
        public synchronized JsonElement request(JsonObject message) {
            CaseInputs.require(sealed == null, "Adapter protocol sealed");
            try {
                CaseInputs.require(!invalid && report == null, "Duplicate or invalid adapter result");
                InvoiceRenewalFixtures.fields(message, "op", "report"); InvoiceRenewalFixtures.equal(message, "op", "adapter");
                report = message.getAsJsonObject("report").deepCopy();
                InvoiceRenewalFixtures.fields(report, "sourceIdentity", "mainSdk", "mainApiVersion", "adapterSdk", "adapterApiVersion", "primaryAccepted", "adapterAccepted", "invoice");
                InvoiceRenewalFixtures.equal(report, "sourceIdentity", prepared.sourceIdentity()); InvoiceRenewalFixtures.equal(report, "mainSdk", prepared.mainSdk());
                InvoiceRenewalFixtures.equal(report, "mainApiVersion", api(prepared.mainSdk())); InvoiceRenewalFixtures.equal(report, "adapterSdk", sdk(prepared.adapterVersion()));
                InvoiceRenewalFixtures.equal(report, "adapterApiVersion", prepared.adapterVersion());
                for (String field : List.of("primaryAccepted", "adapterAccepted")) CaseInputs.require(report.get(field).isJsonPrimitive() && report.getAsJsonPrimitive(field).isBoolean(), "Invalid adapter acceptance type");
                boolean same = api(prepared.mainSdk()).equals(prepared.adapterVersion()); boolean primary = report.get("primaryAccepted").getAsBoolean(), accepted = report.get("adapterAccepted").getAsBoolean();
                CaseInputs.require(same ? primary == accepted : !primary, "Invalid primary adapter result");
                CaseInputs.require(accepted ? prepared.projection().equals(report.get("invoice")) : report.get("invoice").isJsonNull(), "Adapter source projection mismatch");
                return new JsonObject();
            } catch (RuntimeException e) { invalid = true; throw new IllegalArgumentException("Invalid invoice adapter protocol"); }
        }
        public synchronized JsonObject result(String status) {
            if (sealed != null) return sealed.deepCopy();
            var result = new JsonObject(); result.addProperty("transportStatus", status); result.addProperty("protocolInvalid", invalid);
            result.add("report", report == null ? JsonNull.INSTANCE : report.deepCopy()); sealed = result.deepCopy(); return result;
        }
    }
    private static JsonObject project(JsonObject invoice, String version) {
        var result = new JsonObject(); result.addProperty("invoiceId", text(invoice, "id")); result.addProperty("customerId", reference(invoice.get("customer")));
        String subscription = null;
        if (version.equals("2025-02-24.acacia")) subscription = reference(invoice.get("subscription"));
        else if (invoice.get("parent") instanceof JsonObject parent && "subscription_details".equals(text(parent, "type"))
                && parent.get("subscription_details") instanceof JsonObject details) subscription = reference(details.get("subscription"));
        result.addProperty("subscriptionId", subscription); result.addProperty("status", text(invoice, "status")); result.addProperty("billingReason", text(invoice, "billing_reason")); return result;
    }
    private static String reference(JsonElement value) { return value instanceof JsonObject object ? text(object, "id")
            : value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null; }
    private static String text(JsonObject value, String field) { var item = value == null ? null : value.get(field);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : null; }
}
