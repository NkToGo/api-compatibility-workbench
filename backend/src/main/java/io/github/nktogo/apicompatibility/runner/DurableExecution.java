package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Single-host attempt journal. Lost worker output is never replayed or inferred to be successful. */
public final class DurableExecution {
    private final Path root;
    private final EvidenceStore store;

    public DurableExecution(Path root, EvidenceStore store) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        CaseInputs.require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS), "Journal must be a local directory");
        this.store = Objects.requireNonNull(store);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Usage: DurableExecution run JOURNAL STORE IMAGE | status/cancel/recover JOURNAL STORE [RUN_ID]");
        var journal = new DurableExecution(Path.of(args[1]), new EvidenceStore(Path.of(args[2])));
        if (args[0].equals("run")) {
            if (args.length != 4) throw new IllegalArgumentException("An image ID is required");
            String id = journal.create(args[3], "case", Duration.ofSeconds(180));
            System.out.println("Registered run: " + id);
            String status = journal.execute(id);
            System.out.println(id + " " + status);
            if (!status.equals("COMPLETED")) throw new IllegalStateException("Execution did not complete; inspect or recover this run");
            System.out.println("Open in a browser: " + journal.receipt(id).resolveSibling("report.html"));
            return;
        }
        if (args.length > 4 || !Set.of("status", "cancel", "recover").contains(args[0])) throw new IllegalArgumentException("Invalid journal command");
        if (args[0].equals("cancel") && args.length != 4) throw new IllegalArgumentException("Cancellation requires a run ID");
        for (String id : args.length == 4 ? List.of(args[3]) : journal.list()) {
            String status = switch (args[0]) {
                case "cancel" -> journal.cancel(id);
                case "recover" -> journal.recover(id);
                default -> journal.status(id);
            };
            System.out.println(id + " " + status);
        }
    }

    public String create(String image, String mode, Duration deadline) throws IOException {
        String id = "acw-" + UUID.randomUUID();
        DockerCaseExecutor.createArguments(image, id, mode);
        CaseInputs.require(deadline.toMillis() > 0 && deadline.compareTo(Duration.ofSeconds(180)) <= 0, "Invalid deadline");
        Path directory = Files.createDirectory(root.resolve(id));
        publish(directory.resolve("spec.json"), Map.of("formatVersion", 1, "runId", id, "imageId", image,
                "mode", mode, "deadlineMillis", deadline.toMillis(), "createdAt", Instant.now().toString()));
        return id;
    }

    public String execute(String id) throws Exception { return execute(id, () -> {}); }

    String execute(String id, BeforeFinalize beforeFinalize) throws Exception {
        var spec = spec(id);
        try (var lease = lease(id)) {
            if (lease == null) return "ACTIVE";
            if (exists(id, "result.json")) return terminal(id);
            CaseInputs.require(!exists(id, "started.json") && !exists(id, "recovery-started.json"),
                    "Attempt already started or recovering; recover it instead of replaying");
            publish(path(id).resolve("started.json"), Map.of("startedAt", Instant.now().toString()));
            DockerCaseExecutor.runNamed(spec.get("imageId").getAsString(), path(id).resolve("attempt"),
                    spec.get("mode").getAsString(), Duration.ofMillis(spec.get("deadlineMillis").getAsLong()),
                    null, id, () -> exists(id, "cancel.request"));
            if (!read(receipt(id)).get("containerRemoved").getAsBoolean()) return "RECOVERY_REQUIRED";
            return finish(id, receipt(id), false, beforeFinalize);
        }
    }

    public String status(String id) throws IOException {
        spec(id);
        try (var lease = lease(id)) {
            if (lease == null) return "ACTIVE";
            if (exists(id, "result.json")) return terminal(id);
            return exists(id, "started.json") || exists(id, "recovery-started.json") || exists(id, "cancel.request")
                    ? "RECOVERY_REQUIRED" : "QUEUED";
        }
    }

    public String cancel(String id) throws Exception {
        spec(id);
        if (exists(id, "result.json")) return terminal(id);
        // A request may race final publication; a finalized result always wins.
        try (var channel = FileChannel.open(path(id).resolve("cancel.request"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        catch (FileAlreadyExistsException ignored) { /* Idempotent durable request. */ }
        String result = recover(id);
        return result.equals("ACTIVE") ? "CANCELLATION_REQUESTED" : result;
    }

    public String recover(String id) throws Exception {
        var spec = spec(id);
        try (var lease = lease(id)) {
            if (lease == null) return "ACTIVE";
            if (exists(id, "result.json")) return terminal(id);
            if (!exists(id, "recovery-started.json"))
                publish(path(id).resolve("recovery-started.json"), Map.of("startedAt", Instant.now().toString()));
            Path receipt = receipt(id);
            if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
                var record = read(receipt);
                boolean reconciled = !record.get("containerRemoved").getAsBoolean();
                if (reconciled) DockerCaseExecutor.removeOwned(id, spec.get("imageId").getAsString());
                return finish(id, receipt, reconciled);
            }
            if (exists(id, "started.json")) DockerCaseExecutor.removeOwned(id, spec.get("imageId").getAsString());
            // A separate bundle preserves incomplete original exports without treating them as final evidence.
            Path recovery = path(id).resolve("recovery");
            Files.createDirectories(recovery);
            Path recoveredReceipt = recovery.resolve("execution.json");
            if (!Files.exists(recoveredReceipt)) {
                CaseInputs.write(recovery.resolve("request.json"), Map.of("arguments", DockerCaseExecutor.createArguments(
                        spec.get("imageId").getAsString(), id, spec.get("mode").getAsString()), "deadlineMillis", spec.get("deadlineMillis")));
                var record = new LinkedHashMap<String, Object>();
                record.put("formatVersion", 1); record.put("containerName", id);
                record.put("imageId", spec.get("imageId")); record.put("mode", spec.get("mode"));
                record.put("status", exists(id, "cancel.request") ? "CANCELLED" : "INTERRUPTED");
                record.put("phase", "RECOVERY"); record.put("containerRemoved", true);
                record.put("reason", "No final worker receipt; no execution result inferred and no replay performed");
                record.put("finishedAt", Instant.now().toString());
                record.put("artifacts", Map.of("request.json", CaseInputs.hash(Files.readAllBytes(recovery.resolve("request.json")))));
                publish(recoveredReceipt, record);
            }
            return finish(id, recoveredReceipt, false);
        }
    }

    public List<String> list() throws IOException {
        try (var entries = Files.list(root)) {
            // Registration can die before publishing spec.json; no worker can exist for that directory.
            var ids = entries.filter(p -> Files.exists(p.resolve("spec.json"), LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).sorted().toList();
            for (String id : ids) spec(id);
            return ids;
        }
    }

    private String finish(String id, Path receipt, boolean reconciled) throws IOException {
        return finish(id, receipt, reconciled, () -> {});
    }

    private String finish(String id, Path receipt, boolean reconciled, BeforeFinalize beforeFinalize) throws IOException {
        var spec = spec(id);
        var supplied = read(receipt);
        CaseInputs.require(id.equals(supplied.get("containerName").getAsString())
                && spec.get("imageId").equals(supplied.get("imageId")) && spec.get("mode").equals(supplied.get("mode")),
                "Receipt does not match registered attempt");
        String storedId = store.save(receipt);
        CaseInputs.require(id.equals(storedId), "Journal receipt identity mismatch");
        byte[] bytes = store.read(id).get("execution.json");
        var record = CaseInputs.object(bytes);
        beforeFinalize.run();
        publish(path(id).resolve("result.json"), Map.of("formatVersion", 1, "runId", id,
                "receiptSha256", CaseInputs.hash(bytes), "status", record.get("status").getAsString(),
                "cleanupReconciled", reconciled));
        return terminal(id);
    }

    private String terminal(String id) throws IOException {
        var result = read(path(id).resolve("result.json"));
        byte[] bytes = store.read(id).get("execution.json");
        String status = CaseInputs.object(bytes).get("status").getAsString();
        CaseInputs.require(result.get("formatVersion").getAsInt() == 1 && id.equals(result.get("runId").getAsString())
                && CaseInputs.hash(bytes).equals(result.get("receiptSha256").getAsString())
                && status.equals(result.get("status").getAsString()), "Journal and evidence disagree");
        return status;
    }

    private JsonObject spec(String id) throws IOException {
        Path directory = path(id);
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing or linked attempt directory");
        var spec = read(directory.resolve("spec.json"));
        CaseInputs.require(spec.get("formatVersion").getAsInt() == 1 && id.equals(spec.get("runId").getAsString()), "Invalid attempt specification");
        DockerCaseExecutor.createArguments(spec.get("imageId").getAsString(), id, spec.get("mode").getAsString());
        long deadline = spec.get("deadlineMillis").getAsLong();
        CaseInputs.require(deadline > 0 && deadline <= 180000, "Invalid attempt deadline");
        return spec;
    }

    private Path path(String id) {
        CaseInputs.require(id.matches("acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid run identity");
        return root.resolve(id);
    }
    Path receipt(String id) { return path(id).resolve("attempt").resolve(id).resolve("execution.json"); }
    private boolean exists(String id, String name) { return Files.exists(path(id).resolve(name), LinkOption.NOFOLLOW_LINKS); }

    private Lease lease(String id) throws IOException {
        var channel = FileChannel.open(path(id).resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) { channel.close(); return null; }
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }
    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    private static JsonObject read(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing or linked journal file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(65537);
            CaseInputs.require(bytes.length <= 65536, "Journal file exceeds limit");
            return CaseInputs.object(bytes);
        }
    }
    private static void publish(Path path, Object value) throws IOException {
        // A deterministic temporary name can be overwritten after a crash while the attempt lease is held.
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = ByteBuffer.wrap((CaseInputs.JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE);
    }
    @FunctionalInterface interface BeforeFinalize { void run() throws IOException; }
}
