package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Trusted host-only broker. Model output is advisory and never execution evidence. */
public final class ModelCallBroker {
    private final Path root;
    private final Supplier<String> credential;
    private final ModelHttpTransport transport;
    private final Clock clock;
    private final BooleanSupplier parentPermitted;

    public ModelCallBroker(Path root) throws IOException {
        this(root, () -> System.getenv("OPENAI_API_KEY"), new ModelHttpTransport(), Clock.systemUTC());
    }
    ModelCallBroker(Path root, Supplier<String> credential, ModelHttpTransport transport, Clock clock) throws IOException {
        this(root, credential, transport, clock, () -> true);
    }
    ModelCallBroker(Path root, Supplier<String> credential, ModelHttpTransport transport, Clock clock, BooleanSupplier parentPermitted) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        CaseInputs.require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS), "Invalid broker directory");
        this.credential = credential; this.transport = transport; this.clock = clock; this.parentPermitted = Objects.requireNonNull(parentPermitted);
    }

    public record Request(String model, String input, int maxOutputTokens, String protocol) {
        public Request(String model, String input, int maxOutputTokens) { this(model, input, maxOutputTokens, null); }
        public Request {
            CaseInputs.require(protocol == null || InvoiceDecisionV2.accepts(protocol) || CaseInvestigationDecision.accepts(protocol) || InvoiceWorkerDecision.accepts(protocol) || InvoiceFollowUpDecision.accepts(protocol) || Set.of("investigation-v1", "investigation-v2", "repair-v1", "repair-v2", "subscription-repair-v1", "coordination-v1", "coordination-v2", "reconciliation-v1", BundleInvestigationDecision.PROTOCOL, CapturedTeamDecision.PROTOCOL, CapturedReconciliationDecision.PROTOCOL, InvoiceInvestigationDecision.PROTOCOL, InvoiceTeamDecision.PROTOCOL, InvoiceReconciliationDecision.PROTOCOL).contains(protocol), "Unsupported model protocol");
            CaseInputs.require(model != null && model.matches("gpt-[a-zA-Z0-9.\\-]{1,80}"), "Invalid model identity");
            CaseInputs.require(input != null && !input.isBlank() && input.getBytes(StandardCharsets.UTF_8).length <= (protocol == null ? 16000 : 64000), "Invalid model input size");
            CaseInputs.require(maxOutputTokens >= 16 && maxOutputTokens <= 4096, "Invalid output budget");
        }
        byte[] body() {
            var body = new TreeMap<String, Object>(Map.of("model", model, "input", input, "max_output_tokens", maxOutputTokens,
                    "store", false, "background", false));
            if (protocol != null) {
                boolean repair = Set.of("repair-v1", "repair-v2", "subscription-repair-v1").contains(protocol), coordination = Set.of("coordination-v1", "coordination-v2").contains(protocol);
                boolean reconciliation = protocol.equals("reconciliation-v1");
                String function = reconciliation ? "reconciliation_decision" : coordination ? "coordination_decision" : repair ? "repair_proposal" : "investigation_decision";
                body.put("instructions", reconciliation ? ReconciliationDecision.INSTRUCTIONS : protocol.equals("coordination-v2") ? CoordinationDecision.OBSERVED_REFERENCE_INSTRUCTIONS : coordination ? CoordinationDecision.INSTRUCTIONS : protocol.equals("subscription-repair-v1") ? SubscriptionCandidates.INSTRUCTIONS : protocol.equals("repair-v2")
                        ? RepairProposal.FIXTURE_INSTRUCTIONS : repair ? RepairProposal.INSTRUCTIONS : protocol.equals("investigation-v2")
                        ? InvestigationDecision.BOUNDED_INSTRUCTIONS : InvestigationDecision.INSTRUCTIONS);
                body.put("tools", List.of(Map.of("type", "function", "name", function, "description", reconciliation ? "Select one captured-evidence read or advisory reconciliation action." : coordination ? "Select one evidence review or inert coordination proposal." : repair ? "Submit one unverified scoped repair proposal or abstention." : "Select exactly one next investigation action; the host executes it after this response.",
                        "strict", true, "parameters", reconciliation ? ReconciliationDecision.schema() : coordination ? CoordinationDecision.schema() : repair ? RepairProposal.schema() : InvestigationDecision.schema())));
                body.put("tool_choice", Map.of("type", "function", "name", function));
                body.put("parallel_tool_calls", false);
                if (protocol.equals(InvoiceInvestigationDecision.PROTOCOL) || InvoiceWorkerDecision.accepts(protocol) || InvoiceFollowUpDecision.accepts(protocol)) {
                    body.put("instructions", InvoiceFollowUpDecision.accepts(protocol) ? InvoiceFollowUpDecision.instructions(protocol) : InvoiceWorkerDecision.accepts(protocol) ? InvoiceWorkerDecision.instructions(protocol) : InvoiceInvestigationDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", InvoiceInvestigationDecision.FUNCTION,
                            "description", "Read one bounded invoice evidence page or return one cited advisory dossier.", "strict", true, "parameters", InvoiceInvestigationDecision.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", InvoiceInvestigationDecision.FUNCTION));
                }
                if (InvoiceDecisionV2.accepts(protocol)) {
                    body.put("instructions", InvoiceDecisionV2.instructions(InvoiceDecisionV2.scope(protocol)));
                    body.put("tools", List.of(Map.of("type", "function", "name", InvoiceDecisionV2.FUNCTION,
                            "description", "Read one bounded captured invoice page or return one cited advisory dossier.", "strict", true, "parameters", InvoiceDecisionV2.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", InvoiceDecisionV2.FUNCTION));
                }
                if (protocol.equals(BundleInvestigationDecision.PROTOCOL)) {
                    body.put("instructions", BundleInvestigationDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", BundleInvestigationDecision.FUNCTION,
                            "description", "Select one captured page read or the complete two-case dossier.", "strict", true, "parameters", BundleInvestigationDecision.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", BundleInvestigationDecision.FUNCTION));
                }
                if (CaseInvestigationDecision.accepts(protocol)) {
                    body.put("instructions", CaseInvestigationDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", CaseInvestigationDecision.FUNCTION,
                            "description", "Select one captured page read or the complete single-case dossier.", "strict", true, "parameters", CaseInvestigationDecision.schema(protocol))));
                    body.put("tool_choice", Map.of("type", "function", "name", CaseInvestigationDecision.FUNCTION));
                }
                if (protocol.equals(CapturedTeamDecision.PROTOCOL)) {
                    body.put("instructions", CapturedTeamDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", CapturedTeamDecision.FUNCTION,
                            "description", "Select one captured page read or a cited review with inert follow-up questions.", "strict", true, "parameters", CapturedTeamDecision.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", CapturedTeamDecision.FUNCTION));
                }
                if (protocol.equals(InvoiceTeamDecision.PROTOCOL)) {
                    body.put("instructions", InvoiceTeamDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", InvoiceTeamDecision.FUNCTION,
                            "description", "Read one bounded invoice page or return a cited review with inert role-bound questions.", "strict", true, "parameters", InvoiceTeamDecision.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", InvoiceTeamDecision.FUNCTION));
                }
                if (protocol.equals(InvoiceReconciliationDecision.PROTOCOL)) {
                    body.put("instructions", InvoiceReconciliationDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", InvoiceReconciliationDecision.FUNCTION,
                            "description", "Read one invoice page or return the single reconciled joint dossier.", "strict", true, "parameters", InvoiceReconciliationDecision.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", InvoiceReconciliationDecision.FUNCTION));
                }
                if (protocol.equals(CapturedReconciliationDecision.PROTOCOL)) {
                    body.put("instructions", CapturedReconciliationDecision.INSTRUCTIONS);
                    body.put("tools", List.of(Map.of("type", "function", "name", CapturedReconciliationDecision.FUNCTION,
                            "description", "Read one captured page or return the complete reconciled two-case dossier.", "strict", true, "parameters", CapturedReconciliationDecision.schema())));
                    body.put("tool_choice", Map.of("type", "function", "name", CapturedReconciliationDecision.FUNCTION));
                }
            }
            return CaseInputs.JSON.toJson(canonical(CaseInputs.JSON.toJsonTree(body))).getBytes(StandardCharsets.UTF_8);
        }
        @Override public String toString() { return "Model request (content omitted)"; }
    }

    private static com.google.gson.JsonElement canonical(com.google.gson.JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key))));
            return sorted;
        }
        if (value.isJsonArray()) {
            var array = new com.google.gson.JsonArray(); value.getAsJsonArray().forEach(item -> array.add(canonical(item))); return array;
        }
        return value;
    }

    public String register(Request request, Duration lifetime) throws IOException {
        CaseInputs.require(lifetime.toMillis() > 0 && lifetime.compareTo(Duration.ofMinutes(5)) <= 0, "Invalid model permission lifetime");
        String id = "model-" + UUID.randomUUID(); Path directory = Files.createDirectory(root.resolve(id));
        // Request content is approved input, not a secret store. The credential never enters this file.
        DurableExecution.publish(directory.resolve("request.json"), request);
        Instant now = clock.instant();
        DurableExecution.publish(directory.resolve("grant.json"), Map.of("formatVersion", 1, "callId", id,
                "permission", "openai.responses.create", "requestSha256", CaseInputs.hash(request.body()),
                "issuedAt", now.toString(), "expiresAt", now.plus(lifetime).toString()));
        return id;
    }

    public String execute(String id) throws IOException {
        Path directory = directory(id);
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return "ACTIVE";
            if (exists(directory, "result.json")) return result(directory);
            if (exists(directory, "started.json")) return finish(directory, "UNKNOWN_REMOTE_OUTCOME", "UNKNOWN", Map.of());
            Request request;
            try { request = CaseInputs.JSON.fromJson(read(directory.resolve("request.json")), Request.class); }
            catch (RuntimeException | IOException e) { return finish(directory, "AUTHORIZATION_DENIED", "NOT_DISPATCHED", Map.of()); }
            byte[] body = request.body();
            if (!permitted(directory, id, body)) return finish(directory, "AUTHORIZATION_DENIED", "NOT_DISPATCHED", Map.of());
            String key;
            try { key = credential.get(); }
            catch (RuntimeException e) { return finish(directory, "CREDENTIAL_UNAVAILABLE", "NOT_DISPATCHED", Map.of()); }
            if (key == null || !key.matches("[A-Za-z0-9_-]{16,512}"))
                return finish(directory, "CREDENTIAL_UNAVAILABLE", "NOT_DISPATCHED", Map.of());
            if (!permitted(directory, id, body)) return finish(directory, "AUTHORIZATION_DENIED", "NOT_DISPATCHED", Map.of());
            DurableExecution.publish(directory.resolve("started.json"), Map.of("callId", id, "requestSha256", CaseInputs.hash(body), "startedAt", clock.instant().toString()));
            try {
                var response = transport.send(body, key, id, () -> permitted(directory, id, body));
                if (!permitted(directory, id, body)) return finish(directory, "ACCESS_ENDED", "UNKNOWN", Map.of());
                var metadata = new LinkedHashMap<String, Object>(); metadata.put("httpStatus", response.statusCode());
                response.headers().firstValue("x-request-id").filter(v -> v.matches("[a-zA-Z0-9_-]{1,128}") && !v.contains(key))
                        .ifPresent(v -> metadata.put("providerRequestId", v));
                response.headers().firstValue("retry-after").filter(v -> v.matches("[0-9]{1,5}"))
                        .ifPresent(v -> metadata.put("retryAfterSeconds", Integer.parseInt(v)));
                String status;
                if (response.statusCode() == 401 || response.statusCode() == 403) status = "CREDENTIAL_REJECTED";
                else if (response.statusCode() == 429) status = "RATE_LIMITED";
                else if (response.statusCode() >= 300 && response.statusCode() < 400) status = "REDIRECT_REJECTED";
                else if (response.statusCode() != 200) status = "PROVIDER_ERROR";
                else {
                    String raw = new String(response.body(), StandardCharsets.UTF_8);
                    if (raw.contains(key)) status = "UNSAFE_RESPONSE";
                    else status = extract(response.body(), metadata, request.protocol());
                    if (metadata.getOrDefault("outputText", "").toString().contains(key)) {
                        metadata.clear(); status = "UNSAFE_RESPONSE";
                    }
                }
                if (!permitted(directory, id, body)) return finish(directory, "ACCESS_ENDED", "UNKNOWN", Map.of());
                return finish(directory, status, "RESPONSE_RECEIVED", metadata);
            } catch (CancellationException e) { return finish(directory, "ACCESS_ENDED", "UNKNOWN", Map.of()); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return finish(directory, "INTERRUPTED", "UNKNOWN", Map.of());
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                // Provider payloads, headers, credentials and exception messages never enter diagnostics.
                return finish(directory, "UNKNOWN_REMOTE_OUTCOME", "UNKNOWN", Map.of());
            }
        }
    }

    public String revoke(String id) throws IOException {
        Path directory = directory(id);
        try (var channel = FileChannel.open(directory.resolve("revoked"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        catch (FileAlreadyExistsException ignored) {}
        String status = recover(id);
        return status.equals("ACTIVE") ? "REVOCATION_REQUESTED" : status;
    }

    public String recover(String id) throws IOException {
        Path directory = directory(id);
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return "ACTIVE";
            if (exists(directory, "result.json")) return result(directory);
            return finish(directory, exists(directory, "started.json") ? "UNKNOWN_REMOTE_OUTCOME" : "CLOSED_WITHOUT_DISPATCH",
                    exists(directory, "started.json") ? "UNKNOWN" : "NOT_DISPATCHED", Map.of());
        }
    }

    public JsonObject outcome(String id) throws IOException { return read(directory(id).resolve("result.json")); }

    private boolean permitted(Path directory, String id, byte[] body) {
        try {
            if (!parentPermitted.getAsBoolean()) return false;
            if (exists(directory, "revoked")) return false;
            var grant = read(directory.resolve("grant.json"));
            Instant issued = Instant.parse(grant.get("issuedAt").getAsString());
            Instant expires = Instant.parse(grant.get("expiresAt").getAsString());
            Instant now = clock.instant();
            return grant.get("formatVersion").getAsInt() == 1 && id.equals(grant.get("callId").getAsString())
                    && "openai.responses.create".equals(grant.get("permission").getAsString())
                    && CaseInputs.hash(body).equals(grant.get("requestSha256").getAsString())
                    && expires.isAfter(issued) && Duration.between(issued, expires).compareTo(Duration.ofMinutes(5)) <= 0
                    && !now.isBefore(issued) && now.isBefore(expires);
        } catch (IOException | RuntimeException e) { return false; }
    }

    private static String extract(byte[] body, Map<String, Object> metadata, String protocol) {
        boolean decision = protocol != null;
        String function = InvoiceDecisionV2.accepts(protocol) ? InvoiceDecisionV2.FUNCTION : InvoiceReconciliationDecision.PROTOCOL.equals(protocol) ? InvoiceReconciliationDecision.FUNCTION : InvoiceTeamDecision.PROTOCOL.equals(protocol) ? InvoiceTeamDecision.FUNCTION : InvoiceInvestigationDecision.PROTOCOL.equals(protocol) || InvoiceWorkerDecision.accepts(protocol) || InvoiceFollowUpDecision.accepts(protocol) ? InvoiceInvestigationDecision.FUNCTION : CapturedReconciliationDecision.PROTOCOL.equals(protocol) ? CapturedReconciliationDecision.FUNCTION : CapturedTeamDecision.PROTOCOL.equals(protocol) ? CapturedTeamDecision.FUNCTION : CaseInvestigationDecision.accepts(protocol) ? CaseInvestigationDecision.FUNCTION : BundleInvestigationDecision.PROTOCOL.equals(protocol) ? BundleInvestigationDecision.FUNCTION : "reconciliation-v1".equals(protocol) ? "reconciliation_decision" : Set.of("coordination-v1", "coordination-v2").contains(protocol == null ? "" : protocol) ? "coordination_decision"
                : decision && Set.of("repair-v1", "repair-v2", "subscription-repair-v1").contains(protocol) ? "repair_proposal" : "investigation_decision";
        try {
            var response = CaseInputs.object(body);
            if (!"completed".equals(response.get("status").getAsString())) return "INCOMPLETE";
            var text = new StringBuilder(); int decisions = 0;
            for (var item : response.getAsJsonArray("output")) {
                var output = item.getAsJsonObject();
                if (decision && "function_call".equals(output.get("type").getAsString())) {
                    if (!function.equals(output.get("name").getAsString()) || ++decisions > 1) return "INVALID_RESPONSE";
                    text.append(output.get("arguments").getAsString()); continue;
                }
                if (!"message".equals(output.get("type").getAsString())) continue;
                for (var itemContent : output.getAsJsonArray("content")) {
                    var content = itemContent.getAsJsonObject();
                    if ("refusal".equals(content.get("type").getAsString())) return "REFUSED";
                    if (!decision && "output_text".equals(content.get("type").getAsString())) text.append(content.get("text").getAsString());
                }
            }
            if ((decision && decisions != 1) || text.isEmpty() || text.toString().getBytes(StandardCharsets.UTF_8).length > 32768) return "INVALID_RESPONSE";
            var usage = response.getAsJsonObject("usage");
            long input = usage.get("input_tokens").getAsLong(), output = usage.get("output_tokens").getAsLong();
            if (input < 0 || output < 0) return "INVALID_RESPONSE";
            metadata.put("inputTokens", input); metadata.put("outputTokens", output);
            metadata.put("outputText", text.toString());
            return "COMPLETED";
        } catch (RuntimeException e) { return "INVALID_RESPONSE"; }
    }

    private String finish(Path directory, String status, String remoteOutcome, Map<String, Object> metadata) throws IOException {
        var record = new LinkedHashMap<String, Object>(metadata);
        record.put("formatVersion", 1); record.put("callId", directory.getFileName().toString());
        record.put("status", status); record.put("remoteOutcome", remoteOutcome);
        record.put("authority", "model-output-not-execution-evidence"); record.put("finishedAt", clock.instant().toString());
        DurableExecution.publish(directory.resolve("result.json"), record);
        return status;
    }
    private static String result(Path directory) throws IOException { return read(directory.resolve("result.json")).get("status").getAsString(); }
    private Path directory(String id) {
        CaseInputs.require(id.matches("model-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid model call identity");
        Path directory = root.resolve(id);
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing or linked model call"); return directory;
    }
    private static boolean exists(Path directory, String file) { return Files.exists(directory.resolve(file), LinkOption.NOFOLLOW_LINKS); }
    private static JsonObject read(Path file) throws IOException {
        CaseInputs.require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Missing or linked broker record");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(262145); CaseInputs.require(bytes.length <= 262144, "Broker record exceeds limit");
            return CaseInputs.object(bytes);
        }
    }

    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3, "Usage: ModelCallBroker probe ROOT MODEL | recover/revoke ROOT CALL_ID");
        var broker = new ModelCallBroker(Path.of(args[1]));
        String id = args[2]; String status;
        switch (args[0]) {
            case "probe" -> {
                id = broker.register(new Request(args[2], "Reply with exactly the single word ready.", 1024), Duration.ofMinutes(2));
                System.out.println("Registered model call: " + id); status = broker.execute(id);
            }
            case "recover" -> status = broker.recover(id);
            case "revoke" -> status = broker.revoke(id);
            default -> throw new IllegalArgumentException("Unknown broker action");
        }
        System.out.println(id + " " + status);
        if (args[0].equals("probe") && !status.equals("COMPLETED")) throw new IOException("Model connectivity check did not complete; inspect its sanitized result");
    }
}
