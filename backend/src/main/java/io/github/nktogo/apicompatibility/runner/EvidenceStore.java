package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Local append-only publication of verified host exports, not an evidence authenticity service. */
public final class EvidenceStore {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> FILES = Set.of("request.json", "stderr.txt", "worker-report.json",
            "worker-environment.json", "report.html", "probe.json", "worker-harness.json", "worker-sources.json",
            "worker-provenance.json", "worker-expectations.json");
    private final Path root;

    public EvidenceStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        directory(this.root);
        directory(this.root.resolve("objects"));
        directory(this.root.resolve("runs"));
    }

    /** Retains selected POMs and their binding report in a namespace separate from execution receipts. */
    public String saveInputs(Map<String, byte[]> source) throws IOException { return saveInputs(source, () -> {}); }

    String saveInputs(Map<String, byte[]> source, BeforePublish beforePublish) throws IOException {
        return saveSnapshot(SnapshotKind.PLANNING, source, beforePublish);
    }

    public String saveConsumerSources(Map<String, byte[]> source) throws IOException {
        return saveSnapshot(SnapshotKind.CONSUMER, source, () -> {});
    }

    String saveInvoiceRolloutReview(byte[] review) throws IOException {
        return saveSnapshot(SnapshotKind.INVOICE_REVIEW, Map.of("review.json", review), () -> {});
    }

    byte[] readInvoiceRolloutReview(String id) throws IOException {
        return readSnapshot(SnapshotKind.INVOICE_REVIEW, id).get("review.json");
    }

    private synchronized String saveSnapshot(SnapshotKind kind, Map<String, byte[]> source, BeforePublish beforePublish) throws IOException {
        var captured = new TreeMap<String, byte[]>(); source.forEach((name, bytes) -> captured.put(name, bytes.clone()));
        byte[] encoded = inputManifest(kind, captured); String hash = CaseInputs.hash(encoded);
        Path namespace = root.resolve(kind.directory);
        directory(namespace);
        try (var lease = LocalFileLease.acquire(root.resolve("writer.lock"))) {
            if (!lease.lock().isValid()) throw new IOException("Store lock lost");
            Path reference = namespace.resolve(hash + ".json");
            if (Files.exists(reference, LinkOption.NOFOLLOW_LINKS)) {
                CaseInputs.require(hash.equals(reference(reference)), "Input reference changed"); readSnapshot(kind, hash); return hash;
            }
            for (byte[] bytes : captured.values()) put(bytes);
            put(encoded); beforePublish.run();
            var ref = new JsonObject(); ref.addProperty("formatVersion", 1); ref.addProperty("manifestSha256", hash);
            publish(reference, encode(ref)); return hash;
        }
    }
    public static String inputIdentity(Map<String, byte[]> artifacts) { return CaseInputs.hash(inputManifest(SnapshotKind.PLANNING, artifacts)); }
    private static byte[] inputManifest(SnapshotKind kind, Map<String, byte[]> files) {
        validateSnapshot(kind, files);
        var manifest = new JsonObject(); manifest.addProperty("formatVersion", 1);
        manifest.addProperty("retention", kind.retention); var artifacts = new JsonObject();
        new TreeMap<>(files).forEach((name, bytes) -> {
            var entry = new JsonObject(); entry.addProperty("sha256", CaseInputs.hash(bytes)); entry.addProperty("size", bytes.length); artifacts.add(name, entry);
        });
        manifest.add("artifacts", artifacts); return encode(manifest);
    }
    /** Absence is distinguished from unreadable or unsafe references; contents still require readInputs. */
    public boolean hasInputReference(String id) throws IOException {
        inputId(id);
        try {
            var directory = Files.readAttributes(root.resolve("inputs"), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            CaseInputs.require(directory.isDirectory(), "Unsafe input namespace");
            var file = Files.readAttributes(root.resolve("inputs").resolve(id + ".json"), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            CaseInputs.require(file.isRegularFile(), "Unsafe input reference"); return true;
        } catch (NoSuchFileException e) { return false; }
    }

    public Map<String, byte[]> readInputs(String inputId) throws IOException {
        return readSnapshot(SnapshotKind.PLANNING, inputId);
    }
    public Map<String, byte[]> readConsumerSources(String captureId) throws IOException {
        return readSnapshot(SnapshotKind.CONSUMER, captureId);
    }
    private Map<String, byte[]> readSnapshot(SnapshotKind kind, String inputId) throws IOException {
        Path namespace = root.resolve(kind.directory);
        inputId(inputId); CaseInputs.require(Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS), "Missing or linked input namespace");
        String hash = reference(namespace.resolve(inputId + ".json"));
        CaseInputs.require(inputId.equals(hash), "Input reference identity mismatch"); var manifest = CaseInputs.object(object(hash));
        CaseInputs.require(manifest.keySet().equals(Set.of("formatVersion", "retention", "artifacts"))
                && inputNumber(manifest, "formatVersion") == 1 && kind.retention.equals(manifest.get("retention").getAsString()), "Unsupported input manifest");
        var inventory = manifest.getAsJsonObject("artifacts");
        CaseInputs.require(inventory.size() >= 1 && inventory.size() <= kind.maxFiles, "Invalid input inventory");
        var result = new TreeMap<String, byte[]>();
        for (var entry : inventory.entrySet()) {
            inputName(kind, entry.getKey()); var metadata = entry.getValue().getAsJsonObject();
            CaseInputs.require(metadata.keySet().equals(Set.of("sha256", "size")), "Invalid input artifact metadata");
            byte[] bytes = object(metadata.get("sha256").getAsString());
            CaseInputs.require(bytes.length == inputNumber(metadata, "size"), "Input object size mismatch"); result.put(entry.getKey(), bytes);
        }
        validateSnapshot(kind, result); return Collections.unmodifiableMap(result);
    }

    public List<String> listInputs() throws IOException {
        return listSnapshots(SnapshotKind.PLANNING);
    }
    public List<String> listConsumerSources() throws IOException {
        return listSnapshots(SnapshotKind.CONSUMER);
    }
    private List<String> listSnapshots(SnapshotKind kind) throws IOException {
        Path namespace = root.resolve(kind.directory);
        if (!Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) return List.of();
        CaseInputs.require(Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS), "Linked input namespace");
        var result = new ArrayList<String>();
        try (var entries = Files.list(namespace)) {
            for (Path file : entries.toList()) {
                String name = file.getFileName().toString(); if (name.endsWith(".pending")) continue;
                CaseInputs.require(name.matches("[0-9a-f]{64}\\.json"), "Unexpected input reference");
                String id = name.substring(0, 64); readSnapshot(kind, id); result.add(id);
            }
        }
        Collections.sort(result); return List.copyOf(result);
    }
    private enum SnapshotKind {
        PLANNING("inputs", "selected-poms-and-declarations-v1", "binding.json", "pom-[0-7]\\.bin", 9, MAX_BYTES),
        CONSUMER("consumer-sources", "invoice-consumer-sources-v1", "capture.json", "source-[0-9]\\.bin", 11, 65536),
        INVOICE_REVIEW("invoice-rollout-reviews", "invoice-rollout-review-v1", "review.json", "(?!)", 1, 65536);

        final String directory, retention, metadata, binaryPattern;
        final int maxFiles, metadataLimit;

        SnapshotKind(String directory, String retention, String metadata, String binaryPattern, int maxFiles, int metadataLimit) {
            this.directory = directory;
            this.retention = retention;
            this.metadata = metadata;
            this.binaryPattern = binaryPattern;
            this.maxFiles = maxFiles;
            this.metadataLimit = metadataLimit;
        }
    }
    private static void validateSnapshot(SnapshotKind kind, Map<String, byte[]> files) {
        CaseInputs.require(files.containsKey(kind.metadata) && files.size() <= kind.maxFiles, "Missing input metadata or excessive files");
        int bytes = 0;
        for (var entry : files.entrySet()) {
            inputName(kind, entry.getKey());
            boolean metadata = entry.getKey().equals(kind.metadata);
            CaseInputs.require(entry.getValue().length <= (metadata ? kind.metadataLimit : 65536), "Oversized input artifact");
            if (!metadata) bytes += entry.getValue().length;
        }
        CaseInputs.require(bytes <= 262144, "Input POM bytes exceed limit");
    }
    private static void inputName(SnapshotKind kind, String name) {
        CaseInputs.require(name.equals(kind.metadata) || name.matches(kind.binaryPattern), "Unsupported input artifact");
    }
    private static void inputId(String id) { CaseInputs.require(id != null && id.matches("[0-9a-f]{64}"), "Invalid input snapshot identity"); }
    private static long inputNumber(JsonObject object, String key) {
        var value = object.get(key); CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Invalid input metadata number");
        return value.getAsBigDecimal().longValueExact();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: EvidenceStore list ROOT | import ROOT RECEIPT | verify ROOT RUN_ID | export ROOT RUN_ID NEW_DIRECTORY");
        var store = new EvidenceStore(Path.of(args[1]));
        switch (args[0]) {
            case "list" -> {
                if (args.length != 2) throw new IllegalArgumentException("Invalid list arguments");
                for (String id : store.list()) {
                    var receipt = CaseInputs.object(store.read(id).get("execution.json"));
                    System.out.println(id + " " + receipt.get("status").getAsString());
                }
            }
            case "import" -> {
                if (args.length != 3) throw new IllegalArgumentException("Invalid import arguments");
                System.out.println("Stored run: " + store.save(Path.of(args[2])));
            }
            case "verify" -> {
                if (args.length != 3) throw new IllegalArgumentException("Invalid verify arguments");
                System.out.println("Verified " + store.read(args[2]).size() + " retained artifacts for " + args[2]);
            }
            case "export" -> {
                if (args.length != 4) throw new IllegalArgumentException("Invalid export arguments");
                store.export(args[2], Path.of(args[3]));
                System.out.println("Verified export: " + Path.of(args[3]).toAbsolutePath());
            }
            default -> throw new IllegalArgumentException("Unknown evidence command");
        }
    }

    public String save(Path receipt) throws IOException { return save(receipt, () -> {}); }

    // The hook exercises interruption at the publication boundary without killing the test JVM.
    synchronized String save(Path receipt, BeforePublish beforePublish) throws IOException {
        var captured = capture(receipt);
        String id = CaseInputs.object(captured.get("execution.json")).get("containerName").getAsString();
        id(id);
        var manifest = new JsonObject();
        manifest.addProperty("formatVersion", 1);
        manifest.addProperty("runId", id);
        manifest.addProperty("retention", "host-exports-only");
        manifest.addProperty("workerWorkspaceRetained", false);
        var artifacts = new JsonObject();
        captured.forEach((name, bytes) -> {
            var entry = new JsonObject(); entry.addProperty("sha256", CaseInputs.hash(bytes)); entry.addProperty("size", bytes.length);
            artifacts.add(name, entry);
        });
        manifest.add("artifacts", artifacts);
        byte[] manifestBytes = encode(manifest);
        String manifestHash = CaseInputs.hash(manifestBytes);
        // One writer per store across processes. Concurrent in-process callers retry a held JVM lock.
        try (var lease = LocalFileLease.acquire(root.resolve("writer.lock"))) {
            if (!lease.lock().isValid()) throw new IOException("Store lock lost");
            Path reference = root.resolve("runs").resolve(id + ".json");
            if (Files.exists(reference, LinkOption.NOFOLLOW_LINKS)) {
                CaseInputs.require(manifestHash.equals(reference(reference)), "Run identity already exists with different evidence");
                read(id); // Idempotent retries must not conceal damaged existing objects.
                return id;
            }
            for (byte[] bytes : captured.values()) put(bytes);
            put(manifestBytes);
            beforePublish.run();
            var ref = new JsonObject(); ref.addProperty("formatVersion", 1); ref.addProperty("manifestSha256", manifestHash);
            publish(reference, encode(ref));
            return id;
        }
    }

    public Map<String, byte[]> read(String runId) throws IOException {
        id(runId);
        String manifestHash = reference(root.resolve("runs").resolve(runId + ".json"));
        var manifest = CaseInputs.object(object(manifestHash));
        CaseInputs.require(manifest.get("formatVersion").getAsInt() == 1 && runId.equals(manifest.get("runId").getAsString())
                && "host-exports-only".equals(manifest.get("retention").getAsString())
                && !manifest.get("workerWorkspaceRetained").getAsBoolean(), "Unsupported evidence manifest");
        var result = new TreeMap<String, byte[]>();
        for (var entry : manifest.getAsJsonObject("artifacts").entrySet()) {
            CaseInputs.require(FILES.contains(entry.getKey()) || entry.getKey().equals("execution.json"), "Unsupported artifact name");
            var artifact = entry.getValue().getAsJsonObject();
            byte[] bytes = object(artifact.get("sha256").getAsString());
            CaseInputs.require(bytes.length == artifact.get("size").getAsLong(), "Artifact size mismatch");
            result.put(entry.getKey(), bytes);
        }
        validate(result);
        CaseInputs.require(runId.equals(CaseInputs.object(result.get("execution.json")).get("containerName").getAsString()), "Receipt identity mismatch");
        return Collections.unmodifiableMap(result);
    }

    public List<String> list() throws IOException {
        var result = new ArrayList<String>();
        try (var entries = Files.list(root.resolve("runs"))) {
            for (Path file : entries.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".pending")) continue;
                CaseInputs.require(name.endsWith(".json"), "Unexpected run reference");
                String runId = name.substring(0, name.length() - 5); id(runId); read(runId); result.add(runId);
            }
        }
        Collections.sort(result); return List.copyOf(result);
    }

    public void export(String runId, Path destination) throws IOException {
        var artifacts = read(runId); // Verify every retained byte before creating the destination.
        Files.createDirectory(destination);
        for (var entry : artifacts.entrySet()) {
            if (!entry.getKey().equals("execution.json")) publish(destination.resolve(entry.getKey()), entry.getValue());
        }
        publish(destination.resolve("execution.json"), artifacts.get("execution.json"));
    }

    private static Map<String, byte[]> capture(Path receipt) throws IOException {
        CaseInputs.require(receipt.getFileName().toString().equals("execution.json"), "A finalized execution receipt is required");
        CaseInputs.require(!Files.isSymbolicLink(receipt.toAbsolutePath().getParent()), "Linked source directory is not supported");
        var result = new TreeMap<String, byte[]>();
        byte[] bytes = bytes(receipt); result.put("execution.json", bytes);
        var record = CaseInputs.object(bytes);
        for (var entry : record.getAsJsonObject("artifacts").entrySet()) {
            CaseInputs.require(FILES.contains(entry.getKey()), "Unsupported artifact name");
            result.put(entry.getKey(), bytes(receipt.resolveSibling(entry.getKey())));
        }
        try (var files = Files.list(receipt.toAbsolutePath().getParent())) {
            CaseInputs.require(files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(result.keySet()), "Source inventory differs from finalized receipt");
        }
        validate(result); return result;
    }

    private static void validate(Map<String, byte[]> artifacts) {
        CaseInputs.require(artifacts.containsKey("execution.json") && artifacts.containsKey("request.json"), "Missing execution or request record");
        var receipt = CaseInputs.object(artifacts.get("execution.json"));
        CaseInputs.require(receipt.get("formatVersion").getAsInt() == 1, "Unsupported execution receipt");
        id(receipt.get("containerName").getAsString());
        CaseInputs.require(Set.of("COMPLETED", "FAILED", "TIMED_OUT", "OUTPUT_LIMIT", "CANCELLED", "CLEANUP_FAILED", "INTERRUPTED", "AUTHORIZATION_DENIED").contains(receipt.get("status").getAsString()), "Unknown execution status");
        var inventory = receipt.getAsJsonObject("artifacts");
        CaseInputs.require(inventory.size() + 1 == artifacts.size(), "Receipt inventory mismatch");
        for (var entry : inventory.entrySet()) {
            CaseInputs.require(FILES.contains(entry.getKey()) && artifacts.containsKey(entry.getKey()), "Missing or unsupported artifact");
            CaseInputs.require(CaseInputs.hash(artifacts.get(entry.getKey())).equals(entry.getValue().getAsString()), "Artifact digest mismatch: " + entry.getKey());
        }
        if ("COMPLETED".equals(receipt.get("status").getAsString()) && "case".equals(receipt.get("mode").getAsString()))
            CaseInputs.require(artifacts.keySet().containsAll(Set.of("worker-report.json", "worker-environment.json", "report.html")), "Completed case export is incomplete");
    }

    private String reference(Path path) throws IOException {
        var ref = CaseInputs.object(bytes(path));
        CaseInputs.require(ref.get("formatVersion").getAsInt() == 1, "Unsupported run reference");
        return ref.get("manifestSha256").getAsString();
    }
    private byte[] object(String hash) throws IOException {
        CaseInputs.require(hash.matches("[0-9a-f]{64}"), "Invalid object identity");
        byte[] value = bytes(root.resolve("objects").resolve(hash));
        CaseInputs.require(CaseInputs.hash(value).equals(hash), "Stored object digest mismatch"); return value;
    }
    private void put(byte[] value) throws IOException {
        String hash = CaseInputs.hash(value); Path file = root.resolve("objects").resolve(hash);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { object(hash); return; }
        publish(file, value);
    }
    private static void publish(Path path, byte[] value) throws IOException {
        Path pending = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(value); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE);
    }
    private static byte[] bytes(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing or linked evidence file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            CaseInputs.require(bytes.length <= MAX_BYTES, "Evidence file exceeds storage limit"); return bytes;
        }
    }
    private static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        CaseInputs.require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), "Evidence directory must not be a link");
    }
    private static byte[] encode(Object value) { return (CaseInputs.JSON.toJson(value) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static void id(String value) { CaseInputs.require(value.matches("acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid run identity"); }
    @FunctionalInterface interface BeforePublish { void run() throws IOException; }
}
