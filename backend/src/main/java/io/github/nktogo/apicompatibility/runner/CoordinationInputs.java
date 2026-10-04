package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One input-only snapshot shared by every comparison arm. Capture never requests execution. */
public final class CoordinationInputs {
    private static final String UUID = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private final Path root;
    public record Source(Path state, String contextId, String requestId) {
        public Source {
            Objects.requireNonNull(state);
            CaseInputs.require(contextId != null && contextId.matches("(?:investigation|subscription-investigation)-" + UUID), "Invalid input context");
            CaseInputs.require(requestId != null && requestId.matches("[a-zA-Z0-9_-]{1,80}"), "Invalid retained request reference");
        }
    }
    public CoordinationInputs(Path state) throws IOException {
        root = state.toAbsolutePath().normalize().resolve("coordination-inputs"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    public String capture(Source customer, Source subscription) throws IOException {
        return capture(customer, ConsumerInvestigator.toolBackend(customer.state()), subscription, ConsumerInvestigator.toolBackend(subscription.state()));
    }
    String capture(Source customer, ConsumerInvestigator.Tools customerTools, Source subscription, ConsumerInvestigator.Tools subscriptionTools) throws IOException {
        CaseInputs.require(customer.contextId().startsWith("investigation-") && subscription.contextId().startsWith("subscription-investigation-"), "Declare both curated input contexts");
        var first = captureCase(customer, customerTools, CaseInputs.CASE_ID, InvestigationTools.INPUTS.keySet());
        var second = captureCase(subscription, subscriptionTools, SubscriptionPeriodCase.CASE_ID, SubscriptionInvestigationTools.INPUTS.keySet());
        String id = "inputs-" + java.util.UUID.randomUUID(); Path directory = Files.createDirectory(root.resolve(id));
        var cases = new TreeMap<String, Object>();
        for (var captured : List.of(first, second)) {
            String caseId = CaseInputs.object(captured.get("description")).get("caseId").getAsString();
            Path target = Files.createDirectory(directory.resolve(caseId)); var hashes = new TreeMap<String, String>();
            for (var artifact : captured.entrySet()) {
                Files.write(target.resolve(artifact.getKey()), artifact.getValue(), StandardOpenOption.CREATE_NEW);
                hashes.put(artifact.getKey(), CaseInputs.hash(artifact.getValue()));
            }
            cases.put(caseId, Map.of("artifacts", hashes, "inputIdentity", identity(caseId, hashes)));
        }
        DurableExecution.publish(directory.resolve("manifest.json"), Map.of("formatVersion", 1, "bundleId", id, "cases", cases,
                "authority", "captured-curated-inputs-not-general-provider-evidence"));
        DurableExecution.publish(directory.resolve("receipt.json"), Map.of("manifestSha256", CaseInputs.hash(ConsumerTasks.bytes(directory, "manifest.json"))));
        identities(id); return id;
    }
    private static Map<String, byte[]> captureCase(Source source, ConsumerInvestigator.Tools tools, String expectedCase, Set<String> aliases) throws IOException {
        var description = value(tools.invoke(source.contextId(), "describe_case", new JsonObject()));
        CaseInputs.require(expectedCase.equals(description.get("caseId").getAsString())
                && description.getAsJsonObject("inputs").keySet().equals(aliases), "Unsupported captured input inventory");
        var arguments = json(Map.of("requestId", source.requestId()));
        var execution = value(tools.invoke(source.contextId(), "read_run", arguments));
        CaseInputs.require("COMPLETED".equals(execution.get("status").getAsString())
                && "recorded-execution".equals(execution.get("authority").getAsString()), "Completed retained evidence required");
        var captured = new TreeMap<String, byte[]>();
        captured.put("description", encode(description)); captured.put("execution", encode(execution));
        for (String alias : aliases) {
            var text = new StringBuilder(); int offset = 0;
            while (true) {
                var page = value(tools.invoke(source.contextId(), "read_input", json(Map.of("inputId", alias, "offset", offset, "maxCharacters", 4000))));
                int next = page.get("nextOffset").getAsInt(), total = page.get("totalCharacters").getAsInt(); String content = page.get("content").getAsString();
                CaseInputs.require(alias.equals(page.get("inputId").getAsString()) && offset == page.get("offset").getAsInt()
                        && total >= next && total <= 262144 && next == offset + content.length() && (next > offset || next == total)
                        && description.getAsJsonObject("inputs").get(alias).equals(page.get("sha256")), "Changed or invalid input page");
                text.append(content); offset = next; if (offset == total) break;
            }
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            CaseInputs.require(bytes.length <= 1048576 && CaseInputs.hash(bytes).equals(description.getAsJsonObject("inputs").get(alias).getAsString()), "Changed captured input");
            captured.put(alias, bytes);
        }
        CaseInputs.require(description.equals(value(tools.invoke(source.contextId(), "describe_case", new JsonObject())))
                && execution.equals(value(tools.invoke(source.contextId(), "read_run", arguments))), "Evidence changed during capture");
        return captured;
    }
    public Map<String, ContentDigest> identities(String id) throws IOException {
        Path directory = directory(id); var manifest = CaseInputs.object(ConsumerTasks.bytes(directory, "manifest.json"));
        var receipt = CaseInputs.object(ConsumerTasks.bytes(directory, "receipt.json"));
        CaseInputs.require(manifest.get("formatVersion").getAsInt() == 1 && id.equals(manifest.get("bundleId").getAsString())
                && receipt.equals(json(Map.of("manifestSha256", CaseInputs.hash(ConsumerTasks.bytes(directory, "manifest.json")))))
                && manifest.getAsJsonObject("cases").keySet().equals(Set.of(CaseInputs.CASE_ID, SubscriptionPeriodCase.CASE_ID)), "Changed captured bundle");
        var identities = new TreeMap<String, ContentDigest>();
        for (String caseId : manifest.getAsJsonObject("cases").keySet()) {
            var spec = manifest.getAsJsonObject("cases").getAsJsonObject(caseId); var hashes = new TreeMap<String, String>();
            var allowed = new HashSet<>(caseId.equals(CaseInputs.CASE_ID) ? InvestigationTools.INPUTS.keySet() : SubscriptionInvestigationTools.INPUTS.keySet());
            allowed.addAll(Set.of("description", "execution"));
            CaseInputs.require(spec.getAsJsonObject("artifacts").keySet().equals(allowed), "Changed captured aliases");
            for (String alias : allowed) {
                String hash = CaseInputs.hash(ConsumerTasks.bytes(directory, caseId + "/" + alias));
                CaseInputs.require(hash.equals(spec.getAsJsonObject("artifacts").get(alias).getAsString()), "Changed captured artifact"); hashes.put(alias, hash);
            }
            var identity = identity(caseId, hashes);
            CaseInputs.require(CaseInputs.JSON.toJsonTree(identity).equals(spec.get("inputIdentity")), "Changed captured case identity"); identities.put(caseId, identity);
        }
        return Collections.unmodifiableMap(identities);
    }
    public byte[] read(String id, String caseId, String alias) throws IOException {
        CaseInputs.require(identities(id).containsKey(caseId), "Case outside captured bundle");
        var aliases = new HashSet<>(caseId.equals(CaseInputs.CASE_ID) ? InvestigationTools.INPUTS.keySet() : SubscriptionInvestigationTools.INPUTS.keySet());
        aliases.addAll(Set.of("description", "execution")); CaseInputs.require(aliases.contains(alias), "Input outside captured bundle");
        return ConsumerTasks.bytes(directory(id), caseId + "/" + alias);
    }
    String receiptHash(String id) throws IOException { identities(id); return CaseInputs.hash(ConsumerTasks.bytes(directory(id), "receipt.json")); }
    private Path directory(String id) {
        CaseInputs.require(id != null && id.matches("inputs-" + UUID), "Invalid captured bundle reference");
        ConsumerTasks.directory(root); Path directory = root.resolve(id); ConsumerTasks.directory(directory); return directory;
    }
    private static ContentDigest identity(String caseId, Map<String, String> hashes) {
        var fields = new TreeMap<String, Object>(); fields.put("formatVersion", 1); fields.put("caseId", caseId); fields.put("artifacts", new TreeMap<>(hashes));
        return new ContentDigest(CaseInputs.hash(encode(fields)));
    }
    private static byte[] encode(Object value) { return CaseInputs.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    private static JsonObject value(JsonObject response) {
        CaseInputs.require(response.get("ok").getAsBoolean(), "Registered evidence read failed"); return response.getAsJsonObject("value").deepCopy();
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 3, "Usage: CoordinationInputs capture STATE CUSTOMER_STATE CUSTOMER_CONTEXT CUSTOMER_REQUEST SUBSCRIPTION_STATE SUBSCRIPTION_CONTEXT | read STATE BUNDLE");
        var inputs = new CoordinationInputs(Path.of(args[1])); String id;
        if (args[0].equals("capture") && args.length == 7)
            id = inputs.capture(new Source(Path.of(args[2]), args[3], args[4]), new Source(Path.of(args[5]), args[6], "capture"));
        else { CaseInputs.require(args[0].equals("read") && args.length == 3, "Invalid captured input action"); id = args[2]; }
        System.out.println(id);
        for (var entry : inputs.identities(id).entrySet()) System.out.println(entry.getKey() + ": " + entry.getValue().sha256());
    }
}
