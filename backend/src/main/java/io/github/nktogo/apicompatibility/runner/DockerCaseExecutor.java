package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Host controller for one immutable, locally prepared case image. Never falls back to host execution. */
public final class DockerCaseExecutor {
    private DockerCaseExecutor() {}
    static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
    static final Set<String> MODES = Set.of("case", "probe", "deadline-probe", "output-probe", "failure-probe", "scratch-probe");
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: DockerCaseExecutor IMAGE_ID OUTPUT_DIRECTORY EVIDENCE_STORE");
        Path receipt = run(args[0], Path.of(args[1]), "case", Duration.ofSeconds(180), new EvidenceStore(Path.of(args[2])));
        var record = CaseInputs.object(Files.readAllBytes(receipt));
        System.out.println("Execution: " + record.get("status").getAsString());
        System.out.println("Receipt: " + receipt);
        System.out.println("Stored run: " + record.get("containerName").getAsString());
        if (!"COMPLETED".equals(record.get("status").getAsString())) throw new IllegalStateException("Isolated execution did not produce a completed report");
        var report = CaseInputs.object(Files.readAllBytes(receipt.resolveSibling("worker-report.json")));
        for (var assessment : report.getAsJsonObject("assessments").entrySet())
            System.out.println(assessment.getKey() + ": " + assessment.getValue().getAsJsonObject().get("conclusion").getAsString());
        System.out.println("Open in a browser: " + receipt.resolveSibling("report.html"));
    }

    static List<String> createArguments(String image, String name, String mode) {
        CaseInputs.require(image.matches("sha256:[0-9a-f]{64}"), "An immutable local image ID is required");
        CaseInputs.require(name.matches("acw-[0-9a-f-]{36}") && MODES.contains(mode), "Invalid container request");
        return List.of("docker", "create", "--pull=never", "--platform=linux/amd64", "--name", name,
                "--label", "io.github.nktogo.acw=isolated-case-v1", "--label", "io.github.nktogo.acw.attempt=" + name,
                "--network=none", "--read-only", "--user=65532:65532",
                "--cap-drop=ALL", "--security-opt=no-new-privileges:true", "--init", "--restart=no", "--log-driver=none",
                "--memory=1g", "--memory-swap=1g", "--cpus=2", "--pids-limit=128", "--ulimit=nofile=1024:1024",
                "--tmpfs=/work:rw,noexec,nosuid,nodev,size=256m,mode=700,uid=65532,gid=65532",
                "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777", "--workdir=/work",
                "--entrypoint=/usr/bin/timeout", image, "--signal=KILL", "185s", "/usr/bin/env", "-i",
                "PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "HOME=/work", "TMPDIR=/work", "LANG=C.UTF-8",
                "/opt/java/openjdk/bin/java", "-Xmx384m", "-XX:-UsePerfData", "-cp", "/opt/workbench/lib/*",
                "io.github.nktogo.apicompatibility.runner.ContainerCaseMain", mode, image);
    }

    static Path run(String image, Path outputDirectory, String mode, Duration deadline) throws Exception {
        return run(image, outputDirectory, mode, deadline, null);
    }

    static Path run(String image, Path outputDirectory, String mode, Duration deadline, EvidenceStore store) throws Exception {
        return runNamed(image, outputDirectory, mode, deadline, store, "acw-" + UUID.randomUUID(), () -> false);
    }

    static Path runNamed(String image, Path outputDirectory, String mode, Duration deadline, EvidenceStore store,
                         String name, java.util.function.BooleanSupplier cancelled) throws Exception {
        return runNamed(image, outputDirectory, mode, deadline, store, name, new ExecutionGuard(cancelled, null));
    }

    static Path runNamed(String image, Path outputDirectory, String mode, Duration deadline, EvidenceStore store,
                         String name, ExecutionGuard cancelled) throws Exception {
        var command = createArguments(image, name, mode);
        CaseInputs.require(!deadline.isZero() && !deadline.isNegative() && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid deadline");
        Files.createDirectories(outputDirectory);
        Path directory = Files.createDirectory(outputDirectory.toAbsolutePath().resolve(name));
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("formatVersion", 1); receipt.put("containerName", name); receipt.put("imageId", image);
        receipt.put("mode", mode); receipt.put("startedAt", Instant.now().toString());
        receipt.put("deadlineMillis", deadline.toMillis()); receipt.put("streamLimitBytes", OUTPUT_LIMIT);
        receipt.put("status", "FAILED"); receipt.put("phase", "AUTHORIZE");
        CaseInputs.write(directory.resolve("request.json"), Map.of("arguments", command, "deadlineMillis", deadline.toMillis()));
        boolean mayExist = false;
        try {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            receipt.put("phase", "PREFLIGHT");
            JsonObject info = CaseInputs.object(requireCommand(List.of("docker", "info", "--format", "{{json .}}"), Duration.ofSeconds(15)).stdout());
            CaseInputs.require("linux".equals(info.get("OSType").getAsString()) && "2".equals(info.get("CgroupVersion").getAsString()), "Linux cgroup v2 required");
            CaseInputs.require(info.getAsJsonArray("SecurityOptions").asList().stream().anyMatch(e -> e.getAsString().contains("name=seccomp")), "Docker seccomp required");
            receipt.put("dockerServerVersion", info.get("ServerVersion").getAsString());
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            mayExist = true; // A failed CLI call can still have created the uniquely named container.
            receipt.put("phase", "CREATE");
            requireCommand(command, Duration.ofSeconds(20));
            receipt.put("phase", "VERIFY_CONFIGURATION");
            var before = inspect(name);
            verifyConfiguration(before, image);
            var hostConfig = before.getAsJsonObject("HostConfig");
            receipt.put("enforcedConfiguration", Map.of("network", hostConfig.get("NetworkMode"), "memory", hostConfig.get("Memory"),
                    "memorySwap", hostConfig.get("MemorySwap"), "nanoCpus", hostConfig.get("NanoCpus"), "pids", hostConfig.get("PidsLimit"),
                    "readOnlyRoot", hostConfig.get("ReadonlyRootfs"), "tmpfs", hostConfig.get("Tmpfs")));
            receipt.put("phase", "EXECUTE");
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var execution = BoundedCommand.run(List.of("docker", "start", "--attach", name), deadline, OUTPUT_LIMIT, cancelled);
            var state = inspect(name).getAsJsonObject("State");
            receipt.put("runningBeforeCleanup", state.get("Running"));
            if (!state.get("Running").getAsBoolean()) receipt.put("exitCode", state.get("ExitCode"));
            receipt.put("oomKilled", state.get("OOMKilled"));
            receipt.put("stdoutBytes", execution.stdout().length); receipt.put("stderrBytes", execution.stderr().length);
            // Diagnostics are bounded files, never echoed to the terminal.
            Files.write(directory.resolve("stderr.txt"), execution.stderr());
            if (execution.status() == BoundedCommand.Status.CANCELLED) receipt.put("status", cancelled.stopStatus());
            else if (execution.status() != BoundedCommand.Status.EXITED) receipt.put("status", execution.status().name());
            else if (execution.exitCode() == 0 && !state.get("Running").getAsBoolean()
                    && state.get("ExitCode").getAsInt() == 0 && !state.get("OOMKilled").getAsBoolean()) {
                receipt.put("phase", "VALIDATE_REPORT");
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                var envelope = CaseInputs.object(execution.stdout());
                CaseInputs.require(envelope.get("formatVersion").getAsInt() == 1 && envelope.getAsJsonObject("isolation").get("verified").getAsBoolean(), "Worker guard evidence missing");
                receipt.put("isolationProbe", envelope.getAsJsonObject("isolation"));
                if (mode.equals("case")) exportCase(envelope, directory, image);
                else CaseInputs.write(directory.resolve("probe.json"), envelope);
                receipt.put("status", "COMPLETED");
            }
        } catch (java.util.concurrent.CancellationException e) {
            receipt.put("status", cancelled.stopStatus());
        } catch (InterruptedException e) {
            receipt.put("status", "CANCELLED");
            throw e;
        } catch (Exception e) {
            receipt.put("failureType", e.getClass().getSimpleName());
        } finally {
            boolean cleaned = !mayExist;
            if (mayExist) {
                try {
                    removeOwned(name, image);
                    cleaned = true;
                } catch (IOException | InterruptedException | RuntimeException e) { cleaned = false; }
            }
            receipt.put("containerRemoved", cleaned);
            if ("COMPLETED".equals(receipt.get("status")) && cancelled.getAsBoolean()) receipt.put("status", cancelled.stopStatus());
            receipt.put("authorizationDecision", cancelled.authorizationDecision());
            if (!cleaned) receipt.put("status", "CLEANUP_FAILED");
            if ("COMPLETED".equals(receipt.get("status"))) receipt.put("phase", "DONE");
            receipt.put("finishedAt", Instant.now().toString());
            receipt.put("artifacts", CaseInputs.inventory(directory));
            Path pending = directory.resolve("execution.json.pending");
            CaseInputs.write(pending, receipt);
            Files.move(pending, directory.resolve("execution.json"), StandardCopyOption.ATOMIC_MOVE);
            if (store != null) store.save(directory.resolve("execution.json"));
        }
        return directory.resolve("execution.json");
    }

    private static BoundedCommand.Result requireCommand(List<String> command, Duration deadline) throws IOException, InterruptedException {
        var result = BoundedCommand.run(command, deadline, OUTPUT_LIMIT);
        if (result.status() != BoundedCommand.Status.EXITED || result.exitCode() != 0) throw new IOException("Docker control command failed: " + command.get(1));
        return result;
    }
    static JsonObject inspect(String name) throws IOException, InterruptedException {
        return CaseInputs.object(requireCommand(List.of("docker", "inspect", "--format", "{{json .}}", name), Duration.ofSeconds(10)).stdout());
    }

    /** Reconcile only a registered attempt; never remove a container by name alone. */
    static void removeOwned(String name, String image) throws IOException, InterruptedException {
        var found = requireCommand(List.of("docker", "ps", "--all", "--filter", "name=^/" + name + "$", "--format", "{{.ID}}"), Duration.ofSeconds(10));
        if (new String(found.stdout(), StandardCharsets.UTF_8).isBlank()) return;
        var container = inspect(name);
        var labels = container.getAsJsonObject("Config").getAsJsonObject("Labels");
        CaseInputs.require(image.equals(container.get("Image").getAsString()) && ("/" + name).equals(container.get("Name").getAsString())
                && labels != null && labels.has("io.github.nktogo.acw.attempt")
                && name.equals(labels.get("io.github.nktogo.acw.attempt").getAsString())
                && labels.has("io.github.nktogo.acw") && "isolated-case-v1".equals(labels.get("io.github.nktogo.acw").getAsString()),
                "Container ownership mismatch; recovery refused");
        String containerId = container.get("Id").getAsString();
        CaseInputs.require(containerId.matches("[0-9a-f]{64}"), "Invalid container identity");
        requireCommand(List.of("docker", "rm", "--force", containerId), Duration.ofSeconds(15));
    }
    static void verifyConfiguration(JsonObject inspection, String image) {
        var config = inspection.getAsJsonObject("HostConfig");
        CaseInputs.require(image.equals(inspection.get("Image").getAsString()), "Image identity changed");
        CaseInputs.require("65532:65532".equals(inspection.getAsJsonObject("Config").get("User").getAsString()), "Unexpected worker user");
        CaseInputs.require(config.get("ReadonlyRootfs").getAsBoolean() && !config.get("Privileged").getAsBoolean(), "Unsafe root configuration");
        CaseInputs.require("none".equals(config.get("NetworkMode").getAsString()) && inspection.getAsJsonArray("Mounts").isEmpty(), "Unexpected network or mount");
        CaseInputs.require(config.get("Memory").getAsLong() == 1073741824L && config.get("MemorySwap").getAsLong() == 1073741824L
                && config.get("NanoCpus").getAsLong() == 2000000000L && config.get("PidsLimit").getAsLong() == 128, "Required resource limits absent");
        CaseInputs.require(config.getAsJsonArray("CapDrop").asList().stream().anyMatch(e -> "ALL".equalsIgnoreCase(e.getAsString())), "Capabilities not dropped");
        CaseInputs.require(config.getAsJsonArray("SecurityOpt").asList().stream().anyMatch(e -> "no-new-privileges:true".equals(e.getAsString())), "Privilege escalation is not blocked");
    }

    private static void exportCase(JsonObject envelope, Path directory, String image) throws IOException {
        var report = envelope.getAsJsonObject("report");
        CaseInputs.require(report.get("formatVersion").getAsInt() == 1 && CaseInputs.CASE_ID.equals(report.get("caseId").getAsString()), "Unsupported worker report");
        var definitions = new LinkedHashMap<String, ComparisonDefinition>();
        var observations = new LinkedHashMap<String, List<ExecutionObservation>>();
        var details = new LinkedHashMap<String, List<JsonObject>>();
        for (String variant : List.of("baseline", "target", "upstream-reference")) {
            observations.put(variant, report.getAsJsonObject("observations").getAsJsonArray(variant).asList().stream()
                    .map(e -> CaseInputs.JSON.fromJson(e, ExecutionObservation.class)).toList());
            details.put(variant, report.getAsJsonObject("details").getAsJsonArray(variant).asList().stream().map(e -> e.getAsJsonObject()).toList());
        }
        String expectations = envelope.get("expectations").getAsString();
        var environment = envelope.getAsJsonObject("environment");
        var harness = envelope.getAsJsonObject("harness");
        var sources = envelope.getAsJsonObject("sources");
        String provenance = envelope.get("provenance").getAsString();
        CaseInputs.require(CaseInputs.hash(provenance.getBytes(StandardCharsets.UTF_8)).equals(
                report.getAsJsonObject("artifacts").get("inputs/provenance.json").getAsString()), "Provenance digest mismatch");
        CaseInputs.require(image.equals(environment.get("execution.imageId").getAsString()), "Worker environment image mismatch");
        CaseInputs.require(environment.get("execution.isolationChecks").getAsString().equals(
                CaseInputs.identity(new TreeMap<>(envelope.getAsJsonObject("isolation").asMap()))), "Isolation identity mismatch");
        for (String variant : List.of("target", "upstream-reference")) {
            var assessment = CaseInputs.JSON.fromJson(report.getAsJsonObject("assessments").get(variant), ComparisonAssessment.class);
            CaseInputs.require(assessment.equals(CompatibilityComparison.compare(assessment.definition(), observations.get("baseline"), observations.get(variant))), "Worker assessment disagrees with recorded observations");
            CaseInputs.require(assessment.definition().baseline().expectations().sha256().equals(CaseInputs.hash(expectations.getBytes(StandardCharsets.UTF_8))), "Expectation identity mismatch");
            CaseInputs.require(assessment.definition().baseline().environment().sha256().equals(CaseInputs.identity(environment)), "Environment identity mismatch");
            CaseInputs.require(assessment.definition().baseline().harness().sha256().equals(CaseInputs.identity(harness)), "Harness identity mismatch");
            CaseInputs.require(assessment.definition().baseline().source().sha256().equals(CaseInputs.identity(new TreeMap<>(sources.getAsJsonObject("baseline").asMap())))
                    && assessment.definition().target().source().sha256().equals(CaseInputs.identity(new TreeMap<>(sources.getAsJsonObject(variant).asMap()))), "Source provenance mismatch");
            definitions.put(variant, assessment.definition());
        }
        var expected = new LinkedHashMap<String, JsonObject>();
        for (var item : CaseInputs.object(expectations.getBytes(StandardCharsets.UTF_8)).getAsJsonArray("scenarios")) {
            var scenario = item.getAsJsonObject(); expected.put(scenario.get("id").getAsString(), scenario);
        }
        // Never write worker-supplied HTML or accept worker-controlled destination paths.
        String html = CaseReportView.render(Instant.parse(report.get("completedAt").getAsString()), definitions, observations, details, expected);
        CaseInputs.write(directory.resolve("worker-report.json"), report);
        CaseInputs.write(directory.resolve("worker-environment.json"), environment);
        CaseInputs.write(directory.resolve("worker-harness.json"), harness);
        CaseInputs.write(directory.resolve("worker-sources.json"), sources);
        Files.writeString(directory.resolve("worker-provenance.json"), provenance);
        Files.writeString(directory.resolve("worker-expectations.json"), expectations);
        Files.writeString(directory.resolve("report.html"), html);
    }
}
