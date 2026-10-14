package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceVersionedConsumerBuilds.*;

/** Fixed container bootstrap: two SDK closures, two compiler receipts and one isolated business process. */
public final class InvoiceVersionedWorkerMain {
    private InvoiceVersionedWorkerMain() {}
    public static void main(String[] args) {
        try { execute(); }
        catch (Exception | LinkageError e) { System.err.println("Versioned invoice execution rejected"); System.exit(5); }
    }
    private static void execute() throws Exception {
        ContainerCaseMain.verifyIsolation();
        byte[] lengthBytes = System.in.readNBytes(4); CaseInputs.require(lengthBytes.length == 4, "Missing versioned startup length");
        int length = ByteBuffer.wrap(lengthBytes).getInt(); CaseInputs.require(length > 0 && length <= 150000, "Invalid versioned startup length");
        byte[] bytes = System.in.readNBytes(length); CaseInputs.require(bytes.length == length, "Truncated versioned startup");
        var startup = InvoiceRenewalFixtures.object(bytes); validateStartup(startup);
        // Verify both image closures before reporting compilation or releasing any business input.
        for (var item : startup.getAsJsonArray("components")) {
            var dependencies = item.getAsJsonObject().getAsJsonObject("dependencies");
            for (String name : dependencies.keySet()) CaseInputs.require(CaseInputs.hash(Files.readAllBytes(dependency(name)))
                    .equals(InvoiceRenewalFixtures.text(dependencies, name)), "Versioned image dependency mismatch");
        }
        boolean succeeded = true; int index = 0;
        for (var component : Component.values()) {
            var receipt = compile(component, startup.getAsJsonArray("components").get(index++).getAsJsonObject());
            System.out.println(new GsonBuilder().serializeNulls().create().toJson(receipt)); System.out.flush();
            if (!Arrays.equals(System.in.readNBytes(3), "{}\n".getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid versioned compiler acknowledgement");
            succeeded &= receipt.get("disposition").getAsString().equals("SUCCEEDED");
        }
        if (!succeeded) { System.exit(4); return; }
        System.exit(new ProcessBuilder(consumerCommand()).inheritIO().start().waitFor());
    }
    static void validateStartup(JsonObject startup) {
        InvoiceRenewalFixtures.fields(startup, "buildId", "components"); InvoiceRenewalFixtures.equal(startup, "buildId", BUILD_ID);
        var components = InvoiceRenewalFixtures.array(startup, "components"); CaseInputs.require(components.size() == 2, "Incomplete versioned runtime closure"); int index = 0;
        for (var component : Component.values()) {
            var row = InvoiceRenewalFixtures.asObject(components.get(index++)); InvoiceRenewalFixtures.fields(row, "id", "sdk", "entryClass", "sources", "dependencies");
            InvoiceRenewalFixtures.equal(row, "id", component.id); InvoiceRenewalFixtures.equal(row, "sdk", component.sdk); InvoiceRenewalFixtures.equal(row, "entryClass", component.entry);
            var sources = InvoiceRenewalFixtures.obj(row, "sources");
            var expected = component == Component.MAIN ? Set.of("RenewalPolicy.java", "VersionedInvoiceWebhookConsumer.java") : Set.of("AcaciaInvoiceAdapter.java");
            CaseInputs.require(sources.keySet().equals(expected), "Invalid versioned source inventory");
            for (String name : sources.keySet()) CaseInputs.require(InvoiceRenewalFixtures.text(sources, name).length() <= 64000, "Versioned source budget exceeded");
            var dependencies = InvoiceRenewalFixtures.obj(row, "dependencies"); CaseInputs.require(dependencies.keySet().equals(Set.of("stripe-java-" + component.sdk + ".jar", "gson-2.10.1.jar")), "Invalid versioned dependency inventory");
            for (String name : dependencies.keySet()) CaseInputs.require(InvoiceRenewalFixtures.text(dependencies, name).matches("[0-9a-f]{64}"), "Invalid versioned dependency identity");
        }
    }
    private static Path dependency(String name) { return Path.of("/opt/workbench/dependencies", name); }
    private static String classpath(Component component) { return dependency("stripe-java-" + component.sdk + ".jar") + File.pathSeparator + dependency("gson-2.10.1.jar"); }
    private static Path classes(Component component) { return Path.of("/work", component.id, "classes"); }
    static List<String> consumerCommand() {
        var command = new ArrayList<String>(); command.add(FixtureProcess.javaExecutable()); command.addAll(consumerArguments()); return List.copyOf(command);
    }
    static List<String> consumerArguments() {
        // This entry runs only in the verified Linux image; bind its actual layout independent of the host OS.
        return List.of("-Xmx128m", "-XX:-UsePerfData", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC",
                "-Dworkbench.invoice.legacy.classes=/work/acacia-adapter/classes",
                "-Dworkbench.invoice.legacy.sdk=/opt/workbench/dependencies/stripe-java-28.4.0.jar",
                "-Dworkbench.invoice.legacy.gson=/opt/workbench/dependencies/gson-2.10.1.jar",
                "-cp", "/work/main/classes:/opt/workbench/dependencies/stripe-java-29.0.0.jar:/opt/workbench/dependencies/gson-2.10.1.jar", "InvoiceMain");
    }
    private static JsonObject compile(Component component, JsonObject row) throws IOException {
        Path work = Files.createDirectory(Path.of("/work", component.id)); Path classes = Files.createDirectory(classes(component)); var paths = new ArrayList<Path>();
        var sources = row.getAsJsonObject("sources");
        for (String name : new TreeSet<>(sources.keySet())) {
            Path path = work.resolve(name); Files.writeString(path, sources.get(name).getAsString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); paths.add(path);
        }
        if (component == Component.MAIN) try (var resource = InvoiceVersionedWorkerMain.class.getResourceAsStream(InvoiceSandbox.ADAPTER_RESOURCE)) {
            if (resource == null) throw new IOException("Missing versioned invoice transport");
            Path path = work.resolve("InvoiceMain.java"); Files.writeString(path, new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("REGISTERED_ENTRY", component.entry), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); paths.add(path);
        }
        var compiler = ToolProvider.getSystemJavaCompiler(); CaseInputs.require(compiler != null && Runtime.version().feature() == 21, "Versioned invoice worker requires JDK 21");
        var diagnostics = new DiagnosticCollector<JavaFileObject>(); boolean compiled;
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            compiled = compiler.getTask(Writer.nullWriter(), manager, diagnostics, List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-classpath", classpath(component), "-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(paths)).call();
        }
        CaseInputs.require(diagnostics.getDiagnostics().size() <= 64, "Versioned compiler output budget exceeded");
        var receipt = new JsonObject(); receipt.addProperty("op", "compile"); receipt.addProperty("component", component.id); receipt.addProperty("disposition", compiled ? "SUCCEEDED" : "FAILED"); var records = new JsonArray();
        for (var diagnostic : diagnostics.getDiagnostics()) {
            var value = new JsonObject(); value.addProperty("code", diagnostic.getCode()); value.addProperty("source", diagnostic.getSource() == null ? null : Path.of(diagnostic.getSource().toUri()).getFileName().toString());
            value.addProperty("line", diagnostic.getLineNumber()); value.addProperty("column", diagnostic.getColumnNumber()); value.addProperty("kind", diagnostic.getKind().name()); records.add(value);
        }
        receipt.add("diagnostics", records); return receipt;
    }
}
