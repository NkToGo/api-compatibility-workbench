package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceVersionedConsumerBuilds.*;

/** Both compiler stages must complete before the host supplies an input; only the host asserts business facts. */
final class InvoiceVersionedProtocol implements WorkerOracle {
    private final InvoiceRenewalOracle oracle;
    private final List<JsonObject> compilations = new ArrayList<>();
    private boolean invalid;
    private JsonObject sealed;
    InvoiceVersionedProtocol(InvoiceRenewalOracle oracle) { this.oracle = Objects.requireNonNull(oracle); }
    public synchronized JsonElement request(JsonObject message) {
        CaseInputs.require(sealed == null, "Versioned invoice protocol sealed");
        try {
            CaseInputs.require(!invalid, "Versioned invoice protocol invalid");
            if (compilations.size() < 2) {
                var component = Component.values()[compilations.size()];
                InvoiceRenewalFixtures.fields(message, "op", "component", "disposition", "diagnostics");
                InvoiceRenewalFixtures.equal(message, "op", "compile"); InvoiceRenewalFixtures.equal(message, "component", component.id);
                String disposition = InvoiceRenewalFixtures.text(message, "disposition");
                CaseInputs.require(Set.of("SUCCEEDED", "FAILED").contains(disposition), "Invalid versioned compiler disposition");
                var diagnostics = InvoiceRenewalFixtures.array(message, "diagnostics"); CaseInputs.require(diagnostics.size() <= 64, "Compiler diagnostic budget exceeded");
                var allowed = component == Component.MAIN ? Set.of("RenewalPolicy.java", "VersionedInvoiceWebhookConsumer.java", "InvoiceMain.java") : Set.of("AcaciaInvoiceAdapter.java");
                for (var item : diagnostics) {
                    var row = InvoiceRenewalFixtures.asObject(item); InvoiceRenewalFixtures.fields(row, "code", "source", "line", "column", "kind");
                    CaseInputs.require(InvoiceRenewalFixtures.text(row, "code").matches("[A-Za-z0-9_.-]{1,128}")
                            && Set.of("ERROR", "WARNING", "MANDATORY_WARNING", "NOTE", "OTHER").contains(InvoiceRenewalFixtures.text(row, "kind")), "Invalid compiler diagnostic");
                    var source = row.get("source"); CaseInputs.require(source.isJsonNull() || source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()
                            && allowed.contains(source.getAsString()), "Invalid compiler source");
                    for (String key : List.of("line", "column")) {
                        var number = row.get(key); CaseInputs.require(number.isJsonPrimitive() && number.getAsJsonPrimitive().isNumber()
                                && number.getAsString().matches("-1|[0-9]{1,10}"), "Invalid compiler position");
                    }
                    CaseInputs.require(!disposition.equals("SUCCEEDED") || !row.get("kind").getAsString().equals("ERROR"), "Conflicting compiler facts");
                }
                var receipt = message.deepCopy(); receipt.remove("op"); compilations.add(receipt); return new JsonObject();
            }
            CaseInputs.require(compiled(), "Both versioned components must compile before execution");
            return oracle.request(message);
        } catch (RuntimeException e) { invalid = true; throw e; }
    }
    private boolean compiled() { return compilations.size() == 2 && compilations.stream().allMatch(row -> row.get("disposition").getAsString().equals("SUCCEEDED")); }
    public synchronized JsonObject result(String transportStatus) {
        if (sealed != null) {
            CaseInputs.require(sealed.get("transportStatus").getAsString().equals(transportStatus), "Conflicting versioned transport status"); return sealed.deepCopy();
        }
        String status = Set.of("COMPLETED", "TIMED_OUT", "CANCELLED").contains(transportStatus) ? transportStatus : "INFRASTRUCTURE_ERROR";
        if (status.equals("COMPLETED") && (invalid || !compiled())) status = "INFRASTRUCTURE_ERROR";
        var result = new JsonObject(); result.add("observation", oracle.result(status));
        var receipts = new JsonArray(); compilations.forEach(row -> receipts.add(row.deepCopy())); result.add("compilations", receipts);
        result.addProperty("transportStatus", transportStatus); result.addProperty("transportInvalid", invalid); sealed = result.deepCopy(); return result;
    }
}
