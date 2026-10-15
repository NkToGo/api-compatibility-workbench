package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Registered-source execution only. Host admission and assertions never travel with the source. */
final class InvoiceSandbox {
    static final String ADAPTER_RESOURCE = "/stripe-case/InvoiceMain.java.txt";
    private InvoiceSandbox() {}

    static JsonObject startup(InvoiceConsumerBuilds.Registration registration, String buildId,
                              InvoiceRenewalAdmission.Decision admission) {
        var build = registration.build(buildId);
        CaseInputs.require(admission.admitted(), "Invoice input blocked before dispatch");
        CaseInputs.require(build.consumerId().equals(admission.consumerId())
                && build.apiVersion().equals(admission.effectiveApiVersion())
                && build.sdkVersion().equals(admission.sdkVersion()), "Invoice build does not match admitted input");
        var result = new JsonObject(); result.addProperty("sdk", build.sdkVersion());
        result.addProperty("entryClass", build.entryClass()); result.add("sources", CaseInputs.JSON.toJsonTree(registration.workerSources(buildId)));
        var dependencies = new JsonObject();
        for (String name : List.of("stripe-java-" + build.sdkVersion() + ".jar", "gson-2.10.1.jar"))
            dependencies.addProperty(name, registration.artifactIdentity(name));
        result.add("dependencies", dependencies); return result;
    }

    static JsonObject run(InvoiceConsumerBuilds.Registration registration, String buildId,
                          InvoiceRenewalAdmission.Decision admission, String image, String name,
                          Duration deadline, BooleanSupplier stopped) throws Exception {
        return runObserved(registration, buildId, admission, image, name, deadline, stopped).evidence();
    }
    static InvoiceConsumerExecution runObserved(InvoiceConsumerBuilds.Registration registration, String buildId,
                          InvoiceRenewalAdmission.Decision admission, String image, String name,
                          Duration deadline, BooleanSupplier stopped) throws Exception {
        var payload = startup(registration, buildId, admission);
        String harness = harnessIdentity();
        var protocol = new Protocol(admission.oracle());
        var result = RepairSandbox.runProtocol(image, name, payload, InvoiceWorkerMain.class.getName(), protocol, deadline, stopped);
        result.addProperty("formatVersion", 1); result.addProperty("buildId", buildId);
        result.addProperty("sourceIdentity", registration.sourceIdentity(buildId));
        result.addProperty("configurationIdentity", registration.configurationIdentity(buildId));
        result.addProperty("dependenciesIdentity", registration.dependenciesIdentity());
        result.addProperty("harnessIdentity", harness); result.addProperty("imageId", image);
        if (admission.signatureEvidence() != null) result.add("signatureVerification", admission.signatureEvidence());
        if (admission.adapterEvidence() != null) result.add("adapterVerification", admission.adapterEvidence());
        result.addProperty("environmentIdentity", CaseInputs.identity(Map.of("imageId", image, "policy", "invoice-container-v1",
                "deadlineMillis", deadline.toMillis(), "arguments", RepairSandbox.command(image, "acw-00000000-0000-0000-0000-000000000000", InvoiceWorkerMain.class.getName()))));
        return new InvoiceConsumerExecution(result, protocol.observation());
    }

    static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>();
        for (Class<?> type : List.of(InvoiceSandbox.class, InvoiceWorkerMain.class, RepairSandbox.class,
                DockerCaseExecutor.class, ContainerCaseMain.class, CaseInputs.class, InvoiceRenewalFixtures.class, InvoiceRenewalOracle.class,
                InvoiceRenewalAdmission.class, InvoiceWebhookSignatures.class, InvoiceWebhookAdapters.class, InvoiceEventAdapterWorker.class, InvoiceConsumerExecution.class)) {
            captureClass(type, inventory);
        }
        try (var stream = InvoiceSandbox.class.getResourceAsStream(ADAPTER_RESOURCE)) {
            if (stream == null) throw new IOException("Missing invoice transport resource");
            inventory.put(ADAPTER_RESOURCE, CaseInputs.hash(stream.readAllBytes()));
        }
        return CaseInputs.identity(inventory);
    }
    static void captureClass(Class<?> type, Map<String, String> inventory) throws IOException {
        String name = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = InvoiceSandbox.class.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing invoice harness class"); inventory.put(name, CaseInputs.hash(stream.readAllBytes()));
        }
        for (Class<?> nested : type.getDeclaredClasses()) captureClass(nested, inventory);
    }

    /** Compiler facts are separate from the consumer result; incomplete or poisoned transport cannot pass. */
    static final class Protocol implements WorkerOracle {
        private final InvoiceRenewalOracle oracle;
        private JsonObject compilation;
        private boolean invalid;
        private JsonObject sealed;
        private InvoiceRenewalOracle.Observation observation;
        Protocol(InvoiceRenewalOracle oracle) { this.oracle = Objects.requireNonNull(oracle); }
        synchronized InvoiceRenewalOracle.Observation observation() {
            CaseInputs.require(sealed != null, "Invoice execution not sealed"); return observation;
        }
        public synchronized JsonElement request(JsonObject message) {
            CaseInputs.require(sealed == null, "Invoice worker protocol sealed");
            try {
                CaseInputs.require(!invalid, "Invoice worker protocol invalid");
                if (compilation == null) {
                    InvoiceRenewalFixtures.fields(message, "op", "disposition", "diagnostics");
                    InvoiceRenewalFixtures.equal(message, "op", "compile");
                    CaseInputs.require(Set.of("SUCCEEDED", "FAILED").contains(InvoiceRenewalFixtures.text(message, "disposition")), "Invalid compiler disposition");
                    var diagnostics = message.getAsJsonArray("diagnostics");
                    CaseInputs.require(diagnostics != null && diagnostics.size() <= 64, "Invalid compiler diagnostic count");
                    for (var item : diagnostics) {
                        var diagnostic = item.getAsJsonObject(); InvoiceRenewalFixtures.fields(diagnostic, "code", "source", "line", "column", "kind");
                        CaseInputs.require(InvoiceRenewalFixtures.text(diagnostic, "code").matches("[A-Za-z0-9_.-]{1,128}")
                                && Set.of("ERROR", "WARNING", "MANDATORY_WARNING", "NOTE", "OTHER").contains(InvoiceRenewalFixtures.text(diagnostic, "kind")), "Invalid compiler diagnostic");
                        var source = diagnostic.get("source");
                        CaseInputs.require(source.isJsonNull() || Set.of("RenewalPolicy.java", "InvoiceWebhookConsumer.java", "InvoiceReconciliationConsumer.java", "InvoiceMain.java").contains(source.getAsString()), "Invalid diagnostic source");
                        for (String key : List.of("line", "column")) CaseInputs.require(diagnostic.get(key).isJsonPrimitive()
                                && diagnostic.get(key).getAsJsonPrimitive().isNumber() && diagnostic.get(key).getAsLong() >= -1, "Invalid diagnostic position");
                    }
                    compilation = message.deepCopy(); compilation.remove("op"); return new JsonObject();
                }
                CaseInputs.require(compilation.get("disposition").getAsString().equals("SUCCEEDED"), "Failed compiler cannot execute");
                return oracle.request(message);
            } catch (RuntimeException e) { invalid = true; throw e; }
        }
        public synchronized JsonObject result(String transportStatus) {
            if (sealed != null) {
                CaseInputs.require(sealed.get("transportStatus").getAsString().equals(transportStatus), "Conflicting invoice transport status");
                return sealed.deepCopy();
            }
            String status = Set.of("COMPLETED", "TIMED_OUT", "CANCELLED").contains(transportStatus) ? transportStatus : "INFRASTRUCTURE_ERROR";
            if (status.equals("COMPLETED") && (invalid || compilation == null || !compilation.get("disposition").getAsString().equals("SUCCEEDED"))) status = "INFRASTRUCTURE_ERROR";
            observation = oracle.observation(status);
            var result = new JsonObject(); result.add("observation", observation.json());
            result.add("compilation", compilation == null ? JsonNull.INSTANCE : compilation.deepCopy());
            result.addProperty("transportStatus", transportStatus); result.addProperty("transportInvalid", invalid);
            sealed = result.deepCopy();
            return result;
        }
    }
}
