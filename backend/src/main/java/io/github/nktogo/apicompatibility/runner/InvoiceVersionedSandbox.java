package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static io.github.nktogo.apicompatibility.runner.InvoiceVersionedConsumerBuilds.*;

/** One registered mixed-version route; actual host verifier authority is required before dispatch. */
final class InvoiceVersionedSandbox {
    private InvoiceVersionedSandbox() {}

    static JsonObject startup(Registration registration, InvoiceRenewalAdmission.Decision admission) {
        CaseInputs.require(admission.observedWebhookVerified(), "Observed signature and adapter verification required");
        var input = admission.input(); var configuration = InvoiceRenewalFixtures.obj(input, "configuration");
        CaseInputs.require(admission.consumerId().equals("invoice-webhook") && admission.sdkVersion().equals("29.0.0")
                && admission.effectiveApiVersion().equals(ACACIA), "Versioned business route mismatch");
        InvoiceRenewalFixtures.equal(configuration, "requestApiVersion", BASIL);
        InvoiceRenewalFixtures.equal(configuration, "endpointApiVersion", BASIL);
        InvoiceRenewalFixtures.equal(configuration, "adapterApiVersion", ACACIA);
        var startup = new JsonObject(); startup.addProperty("buildId", BUILD_ID); var components = new JsonArray();
        for (var component : registration.components()) {
            var row = new JsonObject(); row.addProperty("id", component.id); row.addProperty("sdk", component.sdk); row.addProperty("entryClass", component.entry);
            var sources = new JsonObject(); registration.sources(component.id).forEach((name, bytes) -> sources.addProperty(name, new String(bytes, StandardCharsets.UTF_8)));
            row.add("sources", sources); var dependencies = new JsonObject();
            for (String name : List.of("stripe-java-" + component.sdk + ".jar", "gson-2.10.1.jar")) dependencies.addProperty(name, CaseInputs.hash(registration.artifactBytes(name)));
            row.add("dependencies", dependencies); components.add(row);
        }
        startup.add("components", components); return startup;
    }

    static JsonObject run(Registration registration, InvoiceRenewalAdmission.Decision admission, String image, String name,
                          Duration deadline, BooleanSupplier stopped) throws Exception {
        return runObserved(registration, admission, image, name, deadline, stopped).evidence();
    }
    static InvoiceConsumerExecution runObserved(Registration registration, InvoiceRenewalAdmission.Decision admission, String image, String name,
                          Duration deadline, BooleanSupplier stopped) throws Exception {
        var payload = startup(registration, admission); String harness = harnessIdentity();
        var protocol = new InvoiceVersionedProtocol(admission.oracle());
        var result = RepairSandbox.runProtocol(image, name, payload, InvoiceVersionedWorkerMain.class.getName(),
                protocol, deadline, stopped);
        result.addProperty("formatVersion", 1); result.addProperty("buildId", BUILD_ID);
        result.addProperty("registrationIdentity", registration.identity()); result.addProperty("baseRegistrationIdentity", registration.baseIdentity());
        var sources = new TreeMap<String, String>(); var configurations = new TreeMap<String, String>(); var components = new JsonArray();
        for (var component : registration.components()) {
            sources.put(component.id, registration.sourceIdentity(component.id)); configurations.put(component.id, registration.configurationIdentity(component.id));
            var row = new JsonObject(); row.addProperty("id", component.id); row.addProperty("sdkVersion", component.sdk);
            row.addProperty("sourceIdentity", registration.sourceIdentity(component.id)); row.addProperty("configurationIdentity", registration.configurationIdentity(component.id));
            row.addProperty("dependenciesIdentity", registration.dependenciesIdentity(component.id)); components.add(row);
        }
        result.add("components", components); result.addProperty("sourceIdentity", CaseInputs.identity(sources));
        result.addProperty("configurationIdentity", CaseInputs.identity(configurations)); result.addProperty("dependenciesIdentity", registration.dependenciesIdentity());
        result.addProperty("harnessIdentity", harness); result.addProperty("imageId", image);
        result.add("signatureVerification", admission.signatureEvidence()); result.add("adapterVerification", admission.adapterEvidence());
        result.addProperty("environmentIdentity", CaseInputs.identity(Map.of("imageId", image, "policy", "versioned-invoice-container-v1",
                "deadlineMillis", deadline.toMillis(), "arguments", RepairSandbox.command(image, "acw-00000000-0000-0000-0000-000000000000", InvoiceVersionedWorkerMain.class.getName()),
                "consumerArguments", InvoiceVersionedWorkerMain.consumerArguments())));
        return new InvoiceConsumerExecution(result, protocol.observation());
    }

    static String harnessIdentity() throws IOException {
        var inventory = new TreeMap<String, String>(); inventory.put("invoiceHarness", InvoiceSandbox.harnessIdentity());
        for (Class<?> type : List.of(InvoiceVersionedSandbox.class, InvoiceVersionedProtocol.class, InvoiceVersionedWorkerMain.class,
                InvoiceVersionedConsumerBuilds.class, InvoiceConsumerBuilds.class, FixtureProcess.class)) capture(type, inventory);
        return CaseInputs.identity(inventory);
    }
    private static void capture(Class<?> type, Map<String, String> inventory) throws IOException {
        String name = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing versioned invoice harness class"); inventory.put(name, CaseInputs.hash(stream.readAllBytes()));
        }
        for (Class<?> nested : type.getDeclaredClasses()) capture(nested, inventory);
    }
}
