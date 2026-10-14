package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;

/** Explicit companion source/build registration. Compilation never loads either consumer component. */
final class InvoiceVersionedConsumerBuilds {
    static final String REGISTRATION_SHA256 = "8965f53b87cadfc03a1111b7f58d16332b53d7c9905d718bb61dc827491c0eb4";
    static final String BUILD_ID = "webhook-basil-with-acacia-companion";
    static final String CORE = "core/src/main/java/io/github/nktogo/invoiceconsumer/RenewalPolicy.java";
    static final String ACACIA = "2025-02-24.acacia", BASIL = "2025-03-31.basil";
    enum Component {
        MAIN("main", "29.0.0", "webhook", "io.github.nktogo.invoiceconsumer.VersionedInvoiceWebhookConsumer"),
        ACACIA_ADAPTER("acacia-adapter", "28.4.0", "acacia-adapter", "io.github.nktogo.invoiceconsumer.legacy.AcaciaInvoiceAdapter");
        final String id, sdk, module, entry;
        Component(String id, String sdk, String module, String entry) { this.id = id; this.sdk = sdk; this.module = module; this.entry = entry; }
        String source() { return module + "/src/main/java/" + entry.replace('.', '/') + ".java"; }
    }
    static final class Registration {
        private final InvoiceConsumerBuilds.Registration base;
        private final Map<String, byte[]> files;
        private Registration(InvoiceConsumerBuilds.Registration base, Map<String, byte[]> files) {
            this.base = base; var copy = new TreeMap<String, byte[]>(); files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            this.files = Collections.unmodifiableMap(copy);
        }
        String identity() { return REGISTRATION_SHA256; }
        String baseIdentity() { return base.identity(); }
        String dependenciesIdentity() { return base.dependenciesIdentity(); }
        List<Component> components() { return List.of(Component.MAIN, Component.ACACIA_ADAPTER); }
        Component component(String id) { return components().stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unregistered versioned component")); }
        byte[] bytes(String name) { CaseInputs.require(files.containsKey(name), "Unregistered versioned source"); return files.get(name).clone(); }
        byte[] artifactBytes(String name) { return base.artifactBytes(name); }
        Map<String, byte[]> sources(String id) {
            var component = component(id); var result = new LinkedHashMap<String, byte[]>();
            if (component == Component.MAIN) result.put("RenewalPolicy.java", base.bytes(CORE));
            result.put(Path.of(component.source()).getFileName().toString(), bytes(component.source())); return Collections.unmodifiableMap(result);
        }
        String sourceIdentity(String id) {
            var component = component(id); var inventory = new TreeMap<String, String>();
            for (String name : List.of("pom.xml", component.module + "/pom.xml", component.source())) inventory.put(name, CaseInputs.hash(files.get(name)));
            if (component == Component.MAIN) {
                inventory.put("policy/pom.xml", CaseInputs.hash(files.get("policy/pom.xml")));
                inventory.put("base/" + CORE, CaseInputs.hash(base.bytes(CORE)));
            }
            return CaseInputs.identity(inventory);
        }
        String configurationIdentity(String id) {
            var component = component(id);
            return CaseInputs.identity(Map.of("buildId", BUILD_ID, "component", id, "sdkVersion", component.sdk, "entryClass", component.entry,
                    "mainSdkVersion", "29.0.0", "eventApiVersion", ACACIA, "requestApiVersion", BASIL, "endpointApiVersion", BASIL,
                    "adapterApiVersion", ACACIA, "compilerPolicy", "invoice-jdk21-utf8-proc-none-xlint-all-werror"));
        }
        String dependenciesIdentity(String id) {
            var component = component(id); var inventory = new TreeMap<String, String>();
            for (String suffix : List.of("jar", "pom")) for (String name : List.of("stripe-java-" + component.sdk + "." + suffix, "gson-2.10.1." + suffix))
                inventory.put(name, base.artifactIdentity(name));
            return CaseInputs.identity(inventory);
        }
    }
    private InvoiceVersionedConsumerBuilds() {}
    static Registration load(Path caseRoot) throws IOException {
        var base = InvoiceConsumerBuilds.load(caseRoot); Path root = caseRoot.resolve("versioned-webhook-v1");
        byte[] index = InvoiceConsumerBuilds.read(root.resolve("build-registration.json"), 262144);
        CaseInputs.require(REGISTRATION_SHA256.equals(CaseInputs.hash(index)), "Versioned webhook registration changed");
        var manifest = object(index);
        fields(manifest, "formatVersion", "caseId", "policy", "origin", "stage", "baseRegistrationIdentity", "buildId", "mainSdkVersion",
                "eventApiVersion", "requestApiVersion", "endpointApiVersion", "adapterApiVersion", "javaRelease", "sharedPolicy", "files", "components");
        header(manifest); equal(manifest, "origin", "authored-consumer-sources"); equal(manifest, "stage", "VERSIONED_SOURCE_AND_BUILD_ONLY");
        equal(manifest, "baseRegistrationIdentity", base.identity()); equal(manifest, "buildId", BUILD_ID); equal(manifest, "mainSdkVersion", "29.0.0");
        equal(manifest, "eventApiVersion", ACACIA); equal(manifest, "requestApiVersion", BASIL); equal(manifest, "endpointApiVersion", BASIL); equal(manifest, "adapterApiVersion", ACACIA);
        CaseInputs.require(manifest.get("javaRelease").getAsInt() == 21, "Versioned Java release mismatch");
        var policy = obj(manifest, "sharedPolicy"); fields(policy, "path", "sha256"); equal(policy, "path", CORE);
        CaseInputs.require(CaseInputs.hash(base.bytes(CORE)).equals(text(policy, "sha256")), "Shared consumer policy changed");
        var expected = Set.of("pom.xml", "policy/pom.xml", "webhook/pom.xml", "acacia-adapter/pom.xml", Component.MAIN.source(), Component.ACACIA_ADAPTER.source());
        var declared = obj(manifest, "files"); CaseInputs.require(declared.keySet().equals(expected), "Unexpected versioned source inventory");
        var files = new TreeMap<String, byte[]>(); files.put("build-registration.json", index);
        for (String name : expected) {
            byte[] bytes = InvoiceConsumerBuilds.read(root.resolve(name), 262144);
            CaseInputs.require(CaseInputs.hash(bytes).equals(text(declared, name)), "Versioned consumer source changed"); files.put(name, bytes);
        }
        InvoiceConsumerBuilds.verifyInventory(root, expected, Set.of("target", "policy/target", "webhook/target", "acacia-adapter/target"));
        var components = array(manifest, "components"); CaseInputs.require(components.size() == 2, "Incomplete versioned components");
        int position = 0;
        for (var component : Component.values()) {
            var row = asObject(components.get(position++)); fields(row, "id", "sdkVersion", "entryClass");
            equal(row, "id", component.id); equal(row, "sdkVersion", component.sdk); equal(row, "entryClass", component.entry);
        }
        return new Registration(base, files);
    }
    static InvoiceConsumerBuilds.Result compile(Registration registration, String componentId, Path outputRoot) throws IOException {
        var component = registration.component(componentId); var libraries = new LinkedHashMap<String, byte[]>();
        for (String name : List.of("stripe-java-" + component.sdk + ".jar", "gson-2.10.1.jar")) libraries.put(name, registration.artifactBytes(name));
        var result = InvoiceConsumerBuilds.compileCaptured(registration.sources(componentId), libraries, outputRoot);
        return new InvoiceConsumerBuilds.Result(BUILD_ID + "/" + componentId, registration.sourceIdentity(componentId), registration.configurationIdentity(componentId),
                registration.dependenciesIdentity(componentId), result.compilerIdentity(), result.disposition(), result.diagnostics());
    }
}
