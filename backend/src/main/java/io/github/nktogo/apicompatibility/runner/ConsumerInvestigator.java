package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One adaptive investigator. Deterministic policy owns actions, budgets and evidence authority. */
public final class ConsumerInvestigator {
    static final int MAX_DECISIONS = 12;
    private final Path root;
    private final Models models;
    private final Tools tools;
    private final Clock clock;

    public ConsumerInvestigator(Path state) throws IOException {
        this(state.resolve("agents"), modelBackend(state), new InvestigationTools(state)::invoke, Clock.systemUTC());
    }
    ConsumerInvestigator(Path root, Models models, Tools tools, Clock clock) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        CaseInputs.require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS), "Invalid agent root");
        this.models = models; this.tools = tools; this.clock = clock;
    }
    static Models modelBackend(Path state) throws IOException {
        var broker = new ModelCallBroker(state.resolve("model-calls"));
        return new Models() {
            public String register(ModelCallBroker.Request request) throws IOException { return broker.register(request, Duration.ofMinutes(2)); }
            public JsonObject execute(String id) throws IOException {
                String status = broker.execute(id);
                return status.equals("ACTIVE") ? json(Map.of("status", status)) : broker.outcome(id);
            }
        };
    }
    public String create(String context, String model) throws IOException {
        new ModelCallBroker.Request(model, "check", 2048, "investigation-v2");
        var description = tools.invoke(context, "describe_case", new JsonObject());
        CaseInputs.require(description.get("ok").getAsBoolean(), "Invalid registered context");
        String id = "agent-" + UUID.randomUUID(); Path directory = Files.createDirectory(root.resolve(id));
        DurableExecution.publish(directory.resolve("session.json"), Map.of("formatVersion", 1, "agentId", id,
                "contextId", context, "model", model, "expiresAt", clock.instant().plus(Duration.ofMinutes(20)).toString(),
                "case", description, "maxDecisions", MAX_DECISIONS, "protocol", "investigation-v2"));
        return id;
    }

    public JsonObject run(String id) throws IOException {
        Path directory = directory(id);
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("owner.lock"))) {
            if (lease == null) return json(Map.of("status", "ACTIVE", "agentId", id));
            if (Files.exists(directory.resolve("result.json"))) return read(directory.resolve("result.json"));
            var session = read(directory.resolve("session.json"));
            CaseInputs.require(id.equals(session.get("agentId").getAsString()) && session.get("formatVersion").getAsInt() == 1
                    && session.get("maxDecisions").getAsInt() == MAX_DECISIONS, "Invalid agent session");
            String protocol = session.has("protocol") ? session.get("protocol").getAsString() : "investigation-v1";
            CaseInputs.require(Set.of("investigation-v1", "investigation-v2").contains(protocol), "Unsupported investigation protocol");
            boolean boundedRetries = protocol.equals("investigation-v2");
            var history = new JsonArray(); long inputTokens = 0, outputTokens = 0;
            for (int turn = 1; turn <= MAX_DECISIONS; turn++) {
                if (!clock.instant().isBefore(Instant.parse(session.get("expiresAt").getAsString())))
                    return finish(directory, "TIME_BUDGET_EXHAUSTED", history, inputTokens, outputTokens, null);
                Path callFile = directory.resolve("call-" + turn + ".json"); String call;
                if (Files.exists(callFile)) call = read(callFile).get("callId").getAsString();
                else {
                    var payload = new TreeMap<String, Object>(Map.of("case", session.get("case"), "events", history,
                            "decisionsRemaining", MAX_DECISIONS - turn + 1));
                    if (boundedRetries) payload.put("unavailableAttempts", unavailableAttempts(history));
                    String input = CaseInputs.JSON.toJson(payload);
                    if (input.getBytes(StandardCharsets.UTF_8).length > 64000)
                        return finish(directory, "CONTEXT_BUDGET_EXHAUSTED", history, inputTokens, outputTokens, null);
                    call = models.register(new ModelCallBroker.Request(session.get("model").getAsString(), input, 2048, protocol));
                    DurableExecution.publish(callFile, Map.of("callId", call));
                }
                var response = models.execute(call); String status = response.get("status").getAsString();
                if (status.equals("ACTIVE")) return json(Map.of("status", "ACTIVE", "agentId", id));
                if (!status.equals("COMPLETED")) return finish(directory, "MODEL_" + status, history, inputTokens, outputTokens, null);
                inputTokens += response.get("inputTokens").getAsLong(); outputTokens += response.get("outputTokens").getAsLong();
                if (!clock.instant().isBefore(Instant.parse(session.get("expiresAt").getAsString())))
                    return finish(directory, "TIME_BUDGET_EXHAUSTED", history, inputTokens, outputTokens, null);
                JsonObject decision;
                try { decision = InvestigationDecision.parse(response.get("outputText").getAsString()); }
                catch (RuntimeException e) { return finish(directory, "INVALID_MODEL_DECISION", history, inputTokens, outputTokens, null); }
                String action = decision.get("action").getAsString();
                if (action.equals("finish")) {
                    if (!validFindings(decision, history, boundedRetries)) return finish(directory, "UNSUPPORTED_FINDINGS", history, inputTokens, outputTokens, null);
                    // Recheck retained execution against captured inputs before accepting a completed investigation.
                    if (decision.get("outcome").getAsString().equals("INVESTIGATED")) {
                        var check = tools.invoke(session.get("contextId").getAsString(), "read_run", json(Map.of("requestId", id)));
                        if (!completed(check)) return finish(directory, "EVIDENCE_UNAVAILABLE", history, inputTokens, outputTokens, null);
                    }
                    return finish(directory, decision.get("outcome").getAsString(), history, inputTokens, outputTokens, decision);
                }
                Path eventFile = directory.resolve("event-" + turn + ".json"); JsonObject event;
                if (Files.exists(eventFile)) event = read(eventFile);
                else {
                    JsonObject arguments = action.equals("read_input") ? decision.deepCopy() : json(Map.of("requestId", id));
                    if (boundedRetries && unavailableAttempts(history).getOrDefault(resource(action, arguments), 0) >= 2)
                        return finish(directory, "TOOL_RETRY_BUDGET_EXHAUSTED", history, inputTokens, outputTokens, null);
                    arguments.remove("action"); long started = System.nanoTime();
                    var result = tools.invoke(session.get("contextId").getAsString(), action, arguments);
                    event = json(Map.of("eventId", "event-" + turn, "tool", action, "arguments", arguments, "result", result,
                            "durationMillis", Math.max(0, (System.nanoTime() - started) / 1_000_000), "modelCallId", call));
                    DurableExecution.publish(eventFile, event);
                }
                history.add(event);
            }
            return finish(directory, "DECISION_BUDGET_EXHAUSTED", history, inputTokens, outputTokens, null);
        }
    }

    private static String resource(String action, JsonObject arguments) {
        return action.equals("read_input") ? "input:" + arguments.get("inputId").getAsString() : "execution";
    }
    static Map<String, Integer> unavailableAttempts(JsonArray history) {
        var counts = new TreeMap<String, Integer>();
        for (var item : history) {
            var event = item.getAsJsonObject(); var result = event.getAsJsonObject("result");
            String resource = resource(event.get("tool").getAsString(), event.getAsJsonObject("arguments"));
            if (result.get("ok").getAsBoolean()) counts.remove(resource);
            else if (result.has("error") && result.get("error").getAsString().equals("UNAVAILABLE")) counts.merge(resource, 1, Integer::sum);
        }
        return counts;
    }

    static boolean validFindings(JsonObject decision, JsonArray history, boolean allowFailedLimitations) {
        Set<String> citations = new HashSet<>(), observed = new HashSet<>(); boolean source = false, execution = false;
        for (var item : history) {
            var event = item.getAsJsonObject(); var result = event.getAsJsonObject("result");
            observed.add(event.get("eventId").getAsString());
            if (!result.get("ok").getAsBoolean()) continue;
            citations.add(event.get("eventId").getAsString());
            source |= event.get("tool").getAsString().equals("read_input")
                    && event.getAsJsonObject("arguments").get("inputId").getAsString().equals("baseline-source")
                    && !result.getAsJsonObject("value").get("content").getAsString().isEmpty();
            execution |= completed(result);
        }
        for (var finding : decision.getAsJsonArray("findings")) {
            var value = finding.getAsJsonObject();
            var allowed = allowFailedLimitations && value.get("kind").getAsString().equals("LIMITATION") ? observed : citations;
            for (var citation : value.getAsJsonArray("citations"))
                if (!allowed.contains(citation.getAsString())) return false;
        }
        return !decision.get("outcome").getAsString().equals("INVESTIGATED") || (source && execution && !decision.getAsJsonArray("findings").isEmpty());
    }
    private static boolean completed(JsonObject result) {
        return result.get("ok").getAsBoolean() && result.has("value") && result.getAsJsonObject("value").has("authority")
                && result.getAsJsonObject("value").get("authority").getAsString().equals("recorded-execution")
                && result.getAsJsonObject("value").get("status").getAsString().equals("COMPLETED");
    }
    private JsonObject finish(Path directory, String status, JsonArray events, long input, long output, JsonObject findings) throws IOException {
        var result = json(Map.of("formatVersion", 1, "agentId", directory.getFileName().toString(), "status", status,
                "authority", "model-findings-not-execution-evidence", "inputTokens", input, "outputTokens", output,
                "usageScope", "completed-model-responses-only; unknown or incomplete requests may also incur charges",
                "events", events, "finishedAt", clock.instant().toString()));
        if (findings != null) result.add("findings", findings);
        DurableExecution.publish(directory.resolve("result.json"), result); return result;
    }
    private Path directory(String id) {
        CaseInputs.require(id.matches("agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid agent identity");
        Path directory = root.resolve(id); CaseInputs.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Missing agent"); return directory;
    }
    private static JsonObject read(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing agent record");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(262145); CaseInputs.require(bytes.length <= 262144, "Agent record too large"); return CaseInputs.object(bytes);
        }
    }
    private static JsonObject json(Object value) { return CaseInputs.JSON.toJsonTree(value).getAsJsonObject(); }
    interface Models { String register(ModelCallBroker.Request request) throws IOException; JsonObject execute(String id) throws IOException; }
    interface Tools { JsonObject invoke(String context, String tool, JsonObject arguments); }

    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3 || args.length == 4, "Usage: ConsumerInvestigator start STATE CONTEXT MODEL | resume STATE AGENT");
        var investigator = new ConsumerInvestigator(Path.of(args[1])); String id;
        if (args[0].equals("start") && args.length == 4) id = investigator.create(args[2], args[3]);
        else { CaseInputs.require(args[0].equals("resume") && args.length == 3, "Invalid investigator action"); id = args[2]; }
        System.out.println("Investigation: " + id);
        var result = investigator.run(id);
        System.out.println("Status: " + result.get("status").getAsString());
        System.out.println("Result: " + Path.of(args[1]).resolve("agents").resolve(id).resolve("result.json").toAbsolutePath());
        if (!Set.of("INVESTIGATED", "ABSTAINED").contains(result.get("status").getAsString())) throw new IOException("Investigation did not finish; inspect retained records");
    }
}
