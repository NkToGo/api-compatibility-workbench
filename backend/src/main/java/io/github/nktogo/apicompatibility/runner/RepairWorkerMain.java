package io.github.nktogo.apicompatibility.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Container-only compiler/bootstrap. Assertions and mutable fixture state are not installed here. */
public final class RepairWorkerMain {
    private RepairWorkerMain() {}
    public static void main(String[] args) throws Exception {
        ContainerCaseMain.verifyIsolation();
        var input = CaseInputs.object(System.in.readNBytes(readLength()));
        String sdk = input.get("sdk").getAsString(), source = input.get("source").getAsString();
        CaseInputs.require(Set.of("17.4.0", "20.33.0", "subscription-period-v1").contains(sdk) && source.length() <= 32000, "Invalid worker input");
        Path work = Path.of("/work"); Path classes = Files.createDirectory(work.resolve("classes"));
        if (sdk.equals("subscription-period-v1")) {
            runSubscription(work, classes, source); return;
        }
        String prefix = resource("Consumer.prefix").replace("Consumer(State state) { super(state); }", "Consumer() { super(); }");
        Path consumer = work.resolve("Consumer.java"), support = work.resolve("RemoteFixtureMain.java");
        Files.writeString(consumer, prefix + "\n" + source + "\n}\n");
        Files.writeString(support, resource("RemoteFixtureMain.java.txt"));
        String cp = "/opt/workbench/dependencies/stripe-java-" + sdk + ".jar:/opt/workbench/dependencies/gson-2.8.6.jar";
        try (var errors = Files.newOutputStream(work.resolve("compiler.txt"))) {
            if (ToolProvider.getSystemJavaCompiler().run(null, errors, errors, "--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-classpath", cp, "-d", classes.toString(), consumer.toString(), support.toString()) != 0) System.exit(4);
        }
        var command = new ArrayList<String>(); command.add(FixtureProcess.javaExecutable()); command.addAll(StripeCaseRunner.WORKER_OPTIONS);
        command.addAll(List.of("-cp", classes + ":" + cp, "RemoteFixtureMain"));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
    private static void runSubscription(Path work, Path classes, String source) throws Exception {
        Path consumer = work.resolve("PeriodConsumer.java"), adapter = work.resolve("PeriodMain.java");
        Files.writeString(consumer, source);
        Files.writeString(adapter, resource("PeriodMain.java.txt"));
        String cp = "/opt/workbench/lib/gson-2.14.0.jar";
        try (var errors = Files.newOutputStream(work.resolve("compiler.txt"))) {
            if (ToolProvider.getSystemJavaCompiler().run(null, errors, errors, "--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-classpath", cp, "-d", classes.toString(), consumer.toString(), adapter.toString()) != 0) System.exit(4);
        }
        var command = new ArrayList<String>(); command.add(FixtureProcess.javaExecutable()); command.addAll(StripeCaseRunner.WORKER_OPTIONS);
        command.addAll(List.of("-cp", classes + ":" + cp, "PeriodMain"));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
    private static int readLength() throws java.io.IOException {
        byte[] bytes = System.in.readNBytes(4); CaseInputs.require(bytes.length == 4, "Missing input length");
        int length = java.nio.ByteBuffer.wrap(bytes).getInt(); CaseInputs.require(length > 0 && length <= 150000, "Invalid input length"); return length;
    }
    private static String resource(String name) throws java.io.IOException {
        try (var stream = RepairWorkerMain.class.getResourceAsStream("/stripe-case/" + name)) {
            if (stream == null) throw new java.io.IOException("Missing worker resource");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
