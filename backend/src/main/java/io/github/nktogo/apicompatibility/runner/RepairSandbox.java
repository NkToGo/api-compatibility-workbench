package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One interactive, network-disabled container. Every stdout message is untrusted service input. */
final class RepairSandbox {
    private RepairSandbox() {}
    static List<String> command(String image, String name) {
        var command = new ArrayList<>(DockerCaseExecutor.createArguments(image, name, "probe"));
        command.add(2, "--interactive");
        command.set(command.indexOf(ContainerCaseMain.class.getName()), RepairWorkerMain.class.getName());
        return List.copyOf(command);
    }
    static List<String> command(String image, String name, String entry) {
        CaseInputs.require(Set.of(RepairWorkerMain.class.getName(), InvoiceWorkerMain.class.getName()).contains(entry), "Unknown worker entry");
        var result = new ArrayList<>(command(image, name));
        result.set(result.indexOf(RepairWorkerMain.class.getName()), entry); return List.copyOf(result);
    }
    static JsonObject run(String image, String name, String source, String sdk, RepairOracle oracle,
                          Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
        CaseInputs.require(source.length() <= 32000 && Set.of("17.4.0", "20.33.0").contains(sdk), "Invalid sandbox input");
        return runProtocol(image, name, source, sdk, oracle, deadline, stopped);
    }
    static JsonObject runSubscription(String image, String name, String source, SubscriptionPeriodOracle oracle,
                                      Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
        CaseInputs.require(source.length() <= 32000, "Invalid subscription source");
        return runProtocol(image, name, source, "subscription-period-v1", oracle, deadline, stopped);
    }
    private static JsonObject runProtocol(String image, String name, String source, String sdk, WorkerOracle oracle,
                                         Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
        var startup = new JsonObject(); startup.addProperty("sdk", sdk); startup.addProperty("source", source);
        return runProtocol(image, name, startup, RepairWorkerMain.class.getName(), oracle, deadline, stopped);
    }
    static JsonObject runProtocol(String image, String name, JsonObject startup, String entry, WorkerOracle oracle,
                                  Duration deadline, java.util.function.BooleanSupplier stopped) throws Exception {
        CaseInputs.require(Set.of(RepairWorkerMain.class.getName(), InvoiceWorkerMain.class.getName()).contains(entry), "Unknown worker entry");
        CaseInputs.require(deadline.toMillis() > 0 && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid sandbox deadline");
        byte[] payload = new Gson().toJson(startup).getBytes(StandardCharsets.UTF_8);
        CaseInputs.require(payload.length > 0 && payload.length <= 150000, "Invalid worker startup size");
        var command = command(image, name, entry);
        var status = new AtomicReference<>("COMPLETED");
        Process process = null; Thread exchange = null, diagnostics = null; boolean cleaned = false;
        int exitCode = -1;
        try {
            check(stopped);
            var info = CaseInputs.object(control(List.of("docker", "info", "--format", "{{json .}}")).stdout());
            CaseInputs.require(info.get("OSType").getAsString().equals("linux") && info.get("CgroupVersion").getAsString().equals("2")
                    && info.getAsJsonArray("SecurityOptions").asList().stream().anyMatch(e -> e.getAsString().contains("name=seccomp")), "Linux cgroup v2 and seccomp required");
            check(stopped); control(command);
            DockerCaseExecutor.verifyConfiguration(DockerCaseExecutor.inspect(name), image);
            check(stopped);
            process = new ProcessBuilder("docker", "start", "--attach", "--interactive", name).start();
            Process attached = process;
            exchange = Thread.ofPlatform().daemon().start(() -> {
                try {
                    var output = attached.getOutputStream();
                    output.write(ByteBuffer.allocate(4).putInt(payload.length).array()); output.write(payload); output.flush();
                    int total = 0;
                    for (byte[] line; (line = line(attached.getInputStream())) != null;) {
                        total += line.length; CaseInputs.require(total <= 262144, "Output budget exceeded");
                        // Bound parser nesting, including braces in strings (conservative rejection is acceptable).
                        int nesting = 0;
                        for (byte c : line) { if (c == '{' || c == '[') nesting++; CaseInputs.require(nesting <= 128, "Protocol nesting limit"); }
                        var response = oracle.request(entry.equals(InvoiceWorkerMain.class.getName())
                                ? InvoiceRenewalFixtures.object(line) : CaseInputs.object(line));
                        byte[] reply = responseBytes(response);
                        CaseInputs.require(reply.length <= 16384, "Response budget exceeded"); output.write(reply); output.flush();
                    }
                } catch (IOException | RuntimeException | StackOverflowError e) { status.compareAndSet("COMPLETED", "PROTOCOL_ERROR"); }
            });
            diagnostics = Thread.ofPlatform().daemon().start(() -> {
                try { if (attached.getErrorStream().readNBytes(65537).length > 65536) status.compareAndSet("COMPLETED", "OUTPUT_LIMIT"); }
                catch (IOException e) { status.compareAndSet("COMPLETED", "STREAM_ERROR"); }
            });
            long end = System.nanoTime() + deadline.toNanos();
            while (!process.waitFor(25, TimeUnit.MILLISECONDS)) {
                if (stopped.getAsBoolean()) { status.set("CANCELLED"); break; }
                if (!status.get().equals("COMPLETED")) break;
                if (System.nanoTime() >= end) { status.set("TIMED_OUT"); break; }
            }
            if (!process.isAlive()) {
                exitCode = process.exitValue();
                if (exitCode != 0) status.compareAndSet("COMPLETED", "WORKER_FAILED");
            }
            if (status.get().equals("COMPLETED")) {
                exchange.join(2000); diagnostics.join(2000);
                if (exchange.isAlive() || diagnostics.isAlive()) status.set("STREAM_ERROR");
                var state = DockerCaseExecutor.inspect(name).getAsJsonObject("State");
                if (state.get("Running").getAsBoolean() || state.get("ExitCode").getAsInt() != 0 || state.get("OOMKilled").getAsBoolean()) status.set("WORKER_FAILED");
            }
        } catch (java.util.concurrent.CancellationException e) { status.set("CANCELLED"); }
        catch (IOException | RuntimeException e) { status.set("INFRASTRUCTURE_ERROR"); }
        finally {
            // Release attach backpressure before removing a worker that floods its output.
            // Killing the CLI is not cleanup; the separately owned container is still removed below.
            if (process != null) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            try { DockerCaseExecutor.removeOwned(name, image); cleaned = true; }
            finally {
                if (process != null) {
                    process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
                }
                if (exchange != null) exchange.join(2000);
                if (diagnostics != null) diagnostics.join(2000);
            }
        }
        if (stopped.getAsBoolean()) status.set("CANCELLED");
        var result = oracle.result(status.get()); result.addProperty("executionStatus", status.get());
        result.addProperty("workerExitCode", exitCode);
        result.addProperty("containerName", name); result.addProperty("containerRemoved", cleaned);
        return result;
    }
    static byte[] responseBytes(JsonElement response) {
        return (new GsonBuilder().serializeNulls().create().toJson(response) + "\n").getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int c; (c = input.read()) != -1;) {
            if (c == '\n') return bytes.toByteArray();
            if (bytes.size() >= 16384) throw new IOException("Protocol line too large");
            bytes.write(c);
        }
        if (bytes.size() != 0) throw new IOException("Truncated protocol line");
        return null;
    }
    private static void check(java.util.function.BooleanSupplier stopped) { if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException(); }
    private static BoundedCommand.Result control(List<String> command) throws IOException, InterruptedException {
        var result = BoundedCommand.run(command, Duration.ofSeconds(20), 65536);
        if (result.status() != BoundedCommand.Status.EXITED || result.exitCode() != 0) throw new IOException("Container control failed");
        return result;
    }
}
