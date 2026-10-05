package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Generates inert source-only candidate checkouts. Generated code is never compiled or executed here. */
public final class RepairCandidates {
    private final Path state;
    private final ConsumerInvestigator.Models models;
    private final ConsumerInvestigator.Tools tools;
    private static final Set<String> ARTIFACTS = Set.of("baseline.java.txt", "checkout/consumer/baseline.java.txt", "candidate.patch", "proposal.json");
    public RepairCandidates(Path state) throws IOException { this(state, backend(state), new InvestigationTools(state)::invoke); }
    RepairCandidates(Path state, ConsumerInvestigator.Models models, ConsumerInvestigator.Tools tools) throws IOException {
        this.state = state.toAbsolutePath().normalize(); this.models = models; this.tools = tools;
        Files.createDirectories(this.state.resolve("candidates"));
    }
    static ConsumerInvestigator.Models backend(Path state) throws IOException {
        var broker = new ModelCallBroker(state.resolve("model-calls"));
        return new ConsumerInvestigator.Models() {
            public String register(ModelCallBroker.Request request) throws IOException { return broker.register(request, Duration.ofMinutes(5)); }
            public JsonObject execute(String id) throws IOException {
                String status = broker.execute(id);
                return status.equals("ACTIVE") ? json(Map.of("status", status)) : broker.outcome(id);
            }
        };
    }
    public String create(String agentId, String model) throws IOException {
        return create(agentId, model, "No additional SDK signature evidence supplied.");
    }
    String create(String agentId, String model, String sdkEvidence) throws IOException {
        validateId(agentId, "agent");
        Path agent = state.resolve("agents").resolve(agentId);
        var session = read(agent.resolve("session.json")); var findings = read(agent.resolve("result.json"));
        CaseInputs.require(agentId.equals(session.get("agentId").getAsString()) && agentId.equals(findings.get("agentId").getAsString())
                && findings.get("status").getAsString().equals("INVESTIGATED"), "Completed investigation required");
        String context = session.get("contextId").getAsString();
        var evidence = execution(context, agentId);
        String source = input(context, "baseline-source", 32000);
        String expectations = input(context, "expectations", 16000);
        String fixtures = input(context, "fixtures", 16000);
        var evidenceInputs = Map.of("expectations", hash(expectations), "fixtures", hash(fixtures));
        String payload = CaseInputs.JSON.toJson(Map.of("baselineSource", source, "expectations", expectations,
                "execution", evidence, "advisoryFindings", findings.get("findings"), "targetSdkSignatures", sdkEvidence,
                "syntheticFixtures", Map.of("inputId", "fixtures", "sha256", hash(fixtures), "content", fixtures,
                        "authority", "authored-synthetic-responses", "contentTrust", "untrusted-data")));
        var request = new ModelCallBroker.Request(model, payload, 4096, "repair-v2");
        String id = "candidate-" + UUID.randomUUID(); Path directory = Files.createDirectory(state.resolve("candidates").resolve(id));
        put(directory.resolve("baseline.java.txt"), source);
        String call = models.register(request);
        DurableExecution.publish(directory.resolve("registration.json"), Map.of("candidateId", id, "agentId", agentId,
                "contextId", context, "modelCallId", call, "baselineSha256", hash(source), "receiptSha256", evidence.getAsJsonObject("value").get("receiptSha256").getAsString(),
                "evidenceInputHashes", evidenceInputs));
        return id;
    }
    public JsonObject generate(String id) throws IOException {
        validateId(id, "candidate"); Path directory = state.resolve("candidates").resolve(id);
        CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing candidate registration");
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("status", "ACTIVE"));
            if (Files.exists(directory.resolve("result.json"))) {
                var result = read(directory.resolve("result.json"));
                if (result.get("status").getAsString().equals("CANDIDATE_READY")) verify(directory, result);
                return result;
            }
            var registration = read(directory.resolve("registration.json"));
            CaseInputs.require(id.equals(registration.get("candidateId").getAsString()), "Candidate identity mismatch");
            String source = Files.readString(directory.resolve("baseline.java.txt"), StandardCharsets.UTF_8);
            CaseInputs.require(hash(source).equals(registration.get("baselineSha256").getAsString()), "Baseline changed");
            if (!current(registration)) return finish(directory, "INPUT_IDENTITY_MISMATCH", Map.of());
            var response = models.execute(registration.get("modelCallId").getAsString());
            String status = response.get("status").getAsString();
            if (status.equals("ACTIVE")) return json(Map.of("status", status));
            if (!status.equals("COMPLETED")) return finish(directory, "MODEL_" + status, Map.of());
            var usage = Map.of("inputTokens", response.get("inputTokens"), "outputTokens", response.get("outputTokens"));
            RepairProposal.Proposal proposal; String candidate;
            try {
                proposal = RepairProposal.parse(response.get("outputText").getAsString());
                if (proposal.status().equals("ABSTAINED")) return finish(directory, "ABSTAINED", Map.of("summary", proposal.summary(), "usage", usage));
                candidate = RepairProposal.apply(source, proposal);
            } catch (RuntimeException e) { return finish(directory, "INVALID_PROPOSAL", Map.of("usage", usage)); }
            if (!current(registration)) return finish(directory, "INPUT_IDENTITY_MISMATCH", Map.of("usage", usage));
            Path checkout = directory.resolve("checkout"); Files.createDirectories(checkout.resolve("consumer"));
            CaseInputs.require(Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(checkout.resolve("consumer"), LinkOption.NOFOLLOW_LINKS), "Linked candidate checkout");
            put(checkout.resolve("consumer/baseline.java.txt"), candidate);
            put(directory.resolve("candidate.patch"), RepairProposal.patch(source, candidate));
            put(directory.resolve("proposal.json"), CaseInputs.JSON.toJson(proposal) + "\n");
            var hashes = new TreeMap<String, String>();
            for (String artifact : ARTIFACTS) hashes.put(artifact, CaseInputs.hash(Files.readAllBytes(directory.resolve(artifact))));
            var result = json(Map.of("artifacts", hashes)); verify(directory, result);
            return finish(directory, "CANDIDATE_READY", Map.of("artifacts", hashes, "summary", proposal.summary(), "usage", usage,
                    "registrationSha256", CaseInputs.hash(Files.readAllBytes(directory.resolve("registration.json")))));
        }
    }
    private boolean current(JsonObject registration) {
        try {
            String context = registration.get("contextId").getAsString();
            // Earlier registrations retain their original evidence contract on resume.
            if (registration.has("evidenceInputHashes")) {
                var hashes = registration.getAsJsonObject("evidenceInputHashes");
                CaseInputs.require(hashes.keySet().equals(Set.of("expectations", "fixtures")), "Invalid generation evidence inventory");
                for (String id : hashes.keySet())
                    if (!hash(input(context, id, 16000)).equals(hashes.get(id).getAsString())) return false;
            }
            return hash(input(context, "baseline-source", 32000)).equals(registration.get("baselineSha256").getAsString())
                    && execution(context, registration.get("agentId").getAsString()).getAsJsonObject("value").get("receiptSha256").equals(registration.get("receiptSha256"));
        } catch (RuntimeException e) { return false; }
    }
    private JsonObject execution(String context, String agent) {
        var result = tools.invoke(context, "read_run", json(Map.of("requestId", agent)));
        CaseInputs.require(result.get("ok").getAsBoolean() && result.getAsJsonObject("value").get("status").getAsString().equals("COMPLETED")
                && result.getAsJsonObject("value").get("authority").getAsString().equals("recorded-execution"), "Current execution evidence required");
        return result;
    }
    private String input(String context, String id, int limit) {
        var text = new StringBuilder(); int offset = 0; String digest = null;
        do {
            var response = tools.invoke(context, "read_input", json(Map.of("inputId", id, "offset", offset, "maxCharacters", 4000)));
            CaseInputs.require(response.get("ok").getAsBoolean(), "Registered input unavailable");
            var page = response.getAsJsonObject("value"); String hash = page.get("sha256").getAsString();
            CaseInputs.require(digest == null || digest.equals(hash), "Input changed during paging"); digest = hash;
            String content = page.get("content").getAsString(); text.append(content);
            int next = page.get("nextOffset").getAsInt(), total = page.get("totalCharacters").getAsInt();
            CaseInputs.require(text.length() <= limit && next == offset + content.length() && next <= total, "Invalid input page");
            if (next == total) break;
            CaseInputs.require(next > offset, "Input paging stalled"); offset = next;
        } while (true);
        CaseInputs.require(hash(text.toString()).equals(digest), "Input digest mismatch"); return text.toString();
    }
    private static void verify(Path directory, JsonObject result) throws IOException {
        if (result.has("registrationSha256")) CaseInputs.require(CaseInputs.hash(Files.readAllBytes(directory.resolve("registration.json")))
                .equals(result.get("registrationSha256").getAsString()), "Candidate registration changed");
        var artifacts = result.getAsJsonObject("artifacts"); CaseInputs.require(artifacts.keySet().equals(ARTIFACTS), "Invalid candidate inventory");
        for (String name : ARTIFACTS) {
            Path file = directory.resolve(name); CaseInputs.require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && CaseInputs.hash(Files.readAllBytes(file)).equals(artifacts.get(name).getAsString()), "Candidate artifact changed");
        }
        CaseInputs.require(CaseInputs.inventory(directory.resolve("checkout")).keySet().equals(Set.of("consumer/baseline.java.txt")), "Unexpected candidate file");
    }
    private static JsonObject finish(Path directory, String status, Map<String, ?> details) throws IOException {
        var result = json(details); result.addProperty("status", status); result.addProperty("candidateId", directory.getFileName().toString());
        result.addProperty("authority", "unverified-source-candidate"); result.addProperty("executed", false);
        DurableExecution.publish(directory.resolve("result.json"), result); return result;
    }
    private static void put(Path path, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(bytes, Files.readAllBytes(path)), "Partial or changed candidate artifact");
        } else Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }
    private static JsonObject read(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 262144, "Invalid candidate record"); return CaseInputs.object(Files.readAllBytes(path));
    }
    private static String hash(String text) { return CaseInputs.hash(text.getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static void validateId(String id, String prefix) { CaseInputs.require(id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid identity"); }
    static String sdkEvidence(Path jar) throws IOException {
        CaseInputs.require(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) && Files.size(jar) < 16000000
                && CaseInputs.hash(Files.readAllBytes(jar)).equals(CaseInputs.EXECUTABLES.get("stripe-java-20.33.0.jar")), "Pinned target SDK required");
        try {
            var inspected = BoundedCommand.run(List.of(Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
                    "-public", "-classpath", jar.toAbsolutePath().toString(), "com.stripe.model.Customer",
                    "com.stripe.model.PaymentSourceCollection", "com.stripe.model.StripeCollection"), Duration.ofSeconds(30), 32000);
            CaseInputs.require(inspected.status() == BoundedCommand.Status.EXITED && inspected.exitCode() == 0, "SDK inspection failed");
            var bytecode = BoundedCommand.run(List.of(Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
                    "-c", "-public", "-classpath", jar.toAbsolutePath().toString(), "com.stripe.model.PaymentSourceCollection"), Duration.ofSeconds(30), 64000);
            CaseInputs.require(bytecode.status() == BoundedCommand.Status.EXITED && bytecode.exitCode() == 0, "SDK implementation inspection failed");
            String implementation = new String(bytecode.stdout(), StandardCharsets.UTF_8);
            String method = "  public com.stripe.model.PaymentSourceCollection list(java.util.Map<java.lang.String, java.lang.Object>, com.stripe.net.RequestOptions)";
            int start = implementation.indexOf(method), end = implementation.indexOf("\n  public ", Math.max(0, start) + method.length());
            CaseInputs.require(start >= 0 && end > start && end - start <= 8000, "Missing bounded SDK source-list implementation");
            CaseInputs.require(CaseInputs.hash(Files.readAllBytes(jar)).equals(CaseInputs.EXECUTABLES.get("stripe-java-20.33.0.jar")), "SDK changed during inspection");
            return "stripe-java 20.33.0 SHA-256 " + CaseInputs.EXECUTABLES.get("stripe-java-20.33.0.jar") + "\n"
                    + new String(inspected.stdout(), StandardCharsets.UTF_8)
                    + "\nPinned SDK implementation evidence (not live provider behavior): PaymentSourceCollection.list(Map, RequestOptions)\n"
                    + implementation.substring(start, end).stripTrailing() + "\n";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("SDK inspection interrupted"); }
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3 || args.length == 5, "Usage: RepairCandidates start STATE AGENT MODEL TARGET_SDK_JAR | resume STATE CANDIDATE");
        var candidates = new RepairCandidates(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 5) id = candidates.create(args[2], args[3], sdkEvidence(Path.of(args[4])));
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid candidate command"); id = args[2]; }
        System.out.println("Candidate: " + id); var result = candidates.generate(id); System.out.println("Status: " + result.get("status").getAsString());
        System.out.println("Result: " + Path.of(args[1]).resolve("candidates").resolve(id).resolve("result.json").toAbsolutePath());
        if (!Set.of("CANDIDATE_READY", "ABSTAINED").contains(result.get("status").getAsString())) throw new IOException("Candidate generation did not finish; inspect retained records");
    }
}
