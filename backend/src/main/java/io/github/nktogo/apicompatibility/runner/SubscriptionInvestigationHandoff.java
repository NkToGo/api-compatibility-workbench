package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Captures observable investigation records and checks them against their registered execution. */
final class SubscriptionInvestigationHandoff {
    private SubscriptionInvestigationHandoff() {}
    record Captured(String agentId, String contextId, String verificationId, JsonObject findings,
                    JsonArray events, Map<String, byte[]> records) {
        Map<String, String> hashes() {
            var hashes = new TreeMap<String, String>(); records.forEach((name, bytes) -> hashes.put(name, CaseInputs.hash(bytes))); return hashes;
        }
        Map<String, Object> identity() { return Map.of("agentId", agentId, "contextId", contextId, "records", hashes()); }
    }
    static Captured load(Path state, String agentId) throws IOException {
        valid(agentId, "agent"); Path agent = state.resolve("agents").resolve(agentId);
        var records = new TreeMap<String, byte[]>();
        records.put("session.json", bytes(agent.resolve("session.json")));
        records.put("result.json", bytes(agent.resolve("result.json")));
        var session = CaseInputs.object(records.get("session.json")); var result = CaseInputs.object(records.get("result.json"));
        CaseInputs.require(agentId.equals(session.get("agentId").getAsString()) && agentId.equals(result.get("agentId").getAsString())
                && session.get("formatVersion").getAsInt() == 1 && result.get("formatVersion").getAsInt() == 1
                && session.get("maxDecisions").getAsInt() == ConsumerInvestigator.MAX_DECISIONS
                && "investigation-v2".equals(session.get("protocol").getAsString())
                && "INVESTIGATED".equals(result.get("status").getAsString())
                && "model-findings-not-execution-evidence".equals(result.get("authority").getAsString()), "Completed subscription investigation required");
        String contextId = session.get("contextId").getAsString(); valid(contextId, "subscription-investigation");
        records.put("context.json", bytes(state.resolve("subscription-investigations").resolve(contextId).resolve("registration.json")));
        var context = CaseInputs.object(records.get("context.json")); String verificationId = context.get("verificationId").getAsString();
        valid(verificationId, "verification");
        var tools = new SubscriptionInvestigationTools(state);
        var description = tools.invoke(contextId, "describe_case", new JsonObject());
        CaseInputs.require(description.get("ok").getAsBoolean() && description.equals(session.get("case")), "Investigation context changed");
        var findings = InvestigationDecision.parse(CaseInputs.JSON.toJson(Map.of("decision", result.get("findings"))));
        var events = result.getAsJsonArray("events");
        CaseInputs.require("finish".equals(findings.get("action").getAsString())
                && "INVESTIGATED".equals(findings.get("outcome").getAsString())
                && events.size() < ConsumerInvestigator.MAX_DECISIONS
                && ConsumerInvestigator.validFindings(findings, events, true), "Unsupported investigation findings");
        int turn = 1;
        for (var item : events) {
            var event = item.getAsJsonObject(); String name = "event-" + turn++ + ".json";
            CaseInputs.require(name.equals(event.get("eventId").getAsString() + ".json"), "Invalid investigation event order");
            byte[] retained = bytes(agent.resolve(name));
            CaseInputs.require(CaseInputs.object(retained).equals(event), "Investigation event changed"); records.put(name, retained);
            var arguments = event.getAsJsonObject("arguments"); String tool = event.get("tool").getAsString();
            CaseInputs.require(Set.of("read_input", "run_case", "read_run").contains(tool), "Unsupported investigation tool");
            if (tool.equals("read_input")) {
                var decision = arguments.deepCopy(); decision.addProperty("action", tool);
                InvestigationDecision.parse(CaseInputs.JSON.toJson(Map.of("decision", decision)));
            } else CaseInputs.require(arguments.keySet().equals(Set.of("requestId"))
                    && agentId.equals(arguments.get("requestId").getAsString()), "Investigation request mismatch");
            if (event.getAsJsonObject("result").get("ok").getAsBoolean())
                CaseInputs.require(tools.invoke(contextId, tool, arguments).equals(event.get("result")), "Investigation observation differs from retained evidence");
        }
        return new Captured(agentId, contextId, verificationId, findings, events, Collections.unmodifiableMap(records));
    }
    static boolean matches(Path state, JsonObject identity) {
        try {
            var current = load(state, identity.get("agentId").getAsString());
            return current.contextId().equals(identity.get("contextId").getAsString())
                    && identity.get("records").equals(CaseInputs.JSON.toJsonTree(current.hashes()));
        } catch (IOException | RuntimeException e) { return false; }
    }
    private static void valid(String id, String prefix) {
        CaseInputs.require(id.matches(prefix + "-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid investigation reference");
    }
    private static byte[] bytes(Path path) throws IOException {
        CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing or linked investigation record");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(262145); CaseInputs.require(bytes.length <= 262144, "Investigation record too large"); return bytes;
        }
    }
}
