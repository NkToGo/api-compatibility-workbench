package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Container-only bootstrap. No expected facts, fixture catalog, signature keys or ledger state. */
public final class InvoiceWorkerMain {
    private InvoiceWorkerMain() {}
    public static void main(String[] args) throws Exception {
        ContainerCaseMain.verifyIsolation();
        byte[] lengthBytes = System.in.readNBytes(4); CaseInputs.require(lengthBytes.length == 4, "Missing invoice startup length");
        int length = ByteBuffer.wrap(lengthBytes).getInt(); CaseInputs.require(length > 0 && length <= 150000, "Invalid invoice startup length");
        byte[] bytes = System.in.readNBytes(length); CaseInputs.require(bytes.length == length, "Truncated invoice startup");
        var input = InvoiceRenewalFixtures.object(bytes); InvoiceRenewalFixtures.fields(input, "sdk", "entryClass", "sources", "dependencies");
        String sdk = InvoiceRenewalFixtures.text(input, "sdk"), entry = InvoiceRenewalFixtures.text(input, "entryClass");
        CaseInputs.require(Set.of("28.4.0", "29.0.0").contains(sdk), "Unregistered invoice SDK");
        CaseInputs.require(Set.of("io.github.nktogo.invoiceconsumer.InvoiceWebhookConsumer", "io.github.nktogo.invoiceconsumer.InvoiceReconciliationConsumer").contains(entry), "Unregistered invoice entry");
        String entryFile = entry.substring(entry.lastIndexOf('.') + 1) + ".java";
        var sources = input.getAsJsonObject("sources"); CaseInputs.require(sources.keySet().equals(Set.of("RenewalPolicy.java", entryFile)), "Invalid invoice source inventory");
        var dependencies = input.getAsJsonObject("dependencies");
        CaseInputs.require(dependencies.keySet().equals(Set.of("stripe-java-" + sdk + ".jar", "gson-2.10.1.jar")), "Invalid invoice runtime closure");
        var classpath = new ArrayList<String>();
        for (String name : new TreeSet<>(dependencies.keySet())) {
            Path path = Path.of("/opt/workbench/dependencies", name);
            CaseInputs.require(CaseInputs.hash(Files.readAllBytes(path)).equals(InvoiceRenewalFixtures.text(dependencies, name)), "Invoice image dependency mismatch");
            classpath.add(path.toString());
        }
        Path work = Path.of("/work"), classes = Files.createDirectory(work.resolve("classes")); var paths = new ArrayList<Path>();
        for (String name : new TreeSet<>(sources.keySet())) {
            String source = InvoiceRenewalFixtures.text(sources, name); CaseInputs.require(source.length() <= 64000, "Invoice source budget exceeded");
            Path path = work.resolve(name); Files.writeString(path, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); paths.add(path);
        }
        try (var resource = InvoiceWorkerMain.class.getResourceAsStream(InvoiceSandbox.ADAPTER_RESOURCE)) {
            if (resource == null) throw new IOException("Missing invoice adapter");
            String source = new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("REGISTERED_ENTRY", entry);
            Path path = work.resolve("InvoiceMain.java"); Files.writeString(path, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); paths.add(path);
        }
        var compiler = ToolProvider.getSystemJavaCompiler(); CaseInputs.require(compiler != null && Runtime.version().feature() == 21, "Invoice worker requires JDK 21");
        var diagnostics = new DiagnosticCollector<JavaFileObject>(); boolean compiled;
        String cp = String.join(File.pathSeparator, classpath);
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            compiled = compiler.getTask(Writer.nullWriter(), manager, diagnostics, List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-classpath", cp, "-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(paths)).call();
        }
        CaseInputs.require(diagnostics.getDiagnostics().size() <= 64, "Invoice compiler output budget exceeded");
        var message = new JsonObject(); message.addProperty("op", "compile"); message.addProperty("disposition", compiled ? "SUCCEEDED" : "FAILED");
        var records = new JsonArray();
        for (var diagnostic : diagnostics.getDiagnostics()) {
            var row = new JsonObject(); row.addProperty("code", diagnostic.getCode());
            row.addProperty("source", diagnostic.getSource() == null ? null : Path.of(diagnostic.getSource().toUri()).getFileName().toString());
            row.addProperty("line", diagnostic.getLineNumber()); row.addProperty("column", diagnostic.getColumnNumber()); row.addProperty("kind", diagnostic.getKind().name()); records.add(row);
        }
        message.add("diagnostics", records); System.out.println(new GsonBuilder().serializeNulls().create().toJson(message)); System.out.flush();
        if (!Arrays.equals(System.in.readNBytes(3), "{}\n".getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid compiler acknowledgement");
        if (!compiled) { System.exit(4); return; }
        var command = new ArrayList<>(List.of(FixtureProcess.javaExecutable(), "-Xmx128m", "-XX:-UsePerfData", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC", "-cp", classes + File.pathSeparator + cp, "InvoiceMain"));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
