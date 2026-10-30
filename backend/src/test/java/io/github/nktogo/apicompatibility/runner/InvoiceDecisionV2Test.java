package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceDecisionV2Test {
    private static String prefix(String prefix, String offset, String limit) {
        return "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":" + new JsonPrimitive(prefix)
                + ",\"offset\":" + offset + ",\"limit\":" + limit + "}}";
    }
    @Test void validPrefixDecisionsKeepLiteralQueriesAndFilteredOffsets() {
        for (String value : List.of("", "source/", "source/webhook-bas", "order/example_1.v2-")) {
            var parsed = InvoiceDecisionV2.parse(prefix(value, "768", "40"));
            assertEquals(value, parsed.get("prefix").getAsString()); assertEquals(768, parsed.get("offset").getAsInt());
        }
    }
    @Test void prefixDecisionsRejectPathsWildcardsControlsAndOversizeQueries() {
        for (String value : List.of("/source", "../", "source/../", "source//", "source\\", "source/*", "source\n", "x".repeat(301)))
            assertThrows(IllegalArgumentException.class, () -> InvoiceDecisionV2.parse(prefix(value, "0", "40")));
    }
    @Test void numericBoundsAreEnforcedLocallyIncludingFractionsStringsAndOverflow() {
        for (String n : List.of("-1", "769", "0.5", "\"0\"", "true", "null", "1e100"))
            assertThrows(IllegalArgumentException.class, () -> InvoiceDecisionV2.parse(prefix("", n, "40")));
        for (String n : List.of("0", "41", "1.5", "\"1\"", "null"))
            assertThrows(IllegalArgumentException.class, () -> InvoiceDecisionV2.parse(prefix("", "0", n)));
    }
    @Test void malformedAmbiguousOrExpandedEnvelopesAreRejected() {
        for (String raw : Arrays.asList(null, "", "null", "[]", "{}", "{\"decision\":null}",
                "{\"decision\":{\"action\":\"delegate\"}}", prefix("", "0", "1") + "{}",
                prefix("", "0", "1").replace("\"prefix\":\"\"", "\"prefix\":null"),
                prefix("", "0", "1").replace("\"offset\":0", "\"offset\":0,\"offset\":1"),
                prefix("", "0", "1").replace("\"limit\":1", "\"limit\":1,\"permission\":true"),
                "{\"decision\":{\"action\":\"finish\"}}", "x".repeat(32769)))
            assertThrows(IllegalArgumentException.class, () -> InvoiceDecisionV2.parse(raw));
    }
    @Test void existingActionsRetainTheV1ValidationRules() {
        for (String raw : List.of("{\"decision\":{\"action\":\"catalog\",\"offset\":0,\"limit\":40}}",
                "{\"decision\":{\"action\":\"read_text\",\"alias\":\"task\",\"offset\":0,\"maxCharacters\":4000}}",
                "{\"decision\":{\"action\":\"finish\",\"response\":\"ABSTAINED\",\"summary\":\"Evidence unavailable\",\"summaryCitations\":[],\"findings\":[]}}"))
            assertEquals(InvoiceInvestigationDecision.parse(raw), InvoiceDecisionV2.parse(raw));
        assertThrows(IllegalArgumentException.class, () -> InvoiceInvestigationDecision.parse(prefix("", "0", "40")));
    }
    @Test void finishCannotInventCitationShapesOrSilentFindings() {
        String raw = "{\"decision\":{\"action\":\"finish\",\"response\":\"ANSWERED\",\"summary\":\"Observation\",\"summaryCitations\":[\"read-1\"],"
                + "\"findings\":[{\"kind\":\"OBSERVATION\",\"statement\":\"Observation\",\"citations\":[\"read-1\"]}]}}";
        assertEquals("finish", InvoiceDecisionV2.parse(raw).get("action").getAsString());
        for (String invalid : List.of(raw.replace("read-1", "metadata"), raw.replace("[\"read-1\"]", "[\"read-1\",\"read-1\"]"),
                raw.replace("\"citations\":[\"read-1\"]", "\"citations\":[]")))
            assertThrows(IllegalArgumentException.class, () -> InvoiceDecisionV2.parse(invalid));
    }
    @Test void schemaHasOneStrictObjectEnvelopeAndFourBoundedActionAlternatives() {
        var schema = InvoiceDecisionV2.schema(); strictObjects(schema);
        assertEquals("object", schema.get("type").getAsString()); assertFalse(schema.has("anyOf"));
        var choices = schema.getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf");
        assertEquals(4, choices.size()); var actions = new HashSet<String>();
        choices.forEach(c -> actions.add(c.getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum").get(0).getAsString()));
        assertEquals(Set.of("catalog", "catalog_prefix", "read_text", "finish"), actions);
        choices.remove(0); assertEquals(4, InvoiceDecisionV2.schema().getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf").size());
        assertEquals(3, InvoiceInvestigationDecision.schema().getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("anyOf").size());
    }
    @Test void distinctScopedIdentitiesDoNotRegisterNewBrokerProtocols() {
        var identities = new HashSet<String>(); var protocols = new HashSet<String>();
        for (var scope : InvoiceReadiness.Scope.values()) {
            String protocol = InvoiceDecisionV2.protocol(scope); protocols.add(protocol); identities.add(InvoiceDecisionV2.contractIdentity(scope));
            assertTrue(InvoiceDecisionV2.instructions(scope).contains("captureMetadata"));
            assertThrows(IllegalArgumentException.class, () -> new ModelCallBroker.Request("gpt-6-astra", "context", 2048, protocol));
            assertThrows(IllegalArgumentException.class, () -> new CoordinationRuntime.Participant("investigator", Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(protocol)));
        }
        assertEquals(3, identities.size()); assertEquals(3, protocols.size());
        assertEquals(CaseInputs.hash(InvoiceDecisionV2.PUBLIC_TASK.getBytes(StandardCharsets.UTF_8)), InvoiceDecisionV2.taskIdentity());
        assertNotEquals(InvoiceDecisionV2.taskIdentity(), InvoiceDecisionV2.contractIdentity(InvoiceReadiness.Scope.JOINT));
    }
    private static void strictObjects(JsonElement element) {
        if (element.isJsonObject()) {
            var object = element.getAsJsonObject();
            if (object.has("type") && object.get("type").isJsonPrimitive() && object.get("type").getAsString().equals("object")) {
                assertFalse(object.get("additionalProperties").getAsBoolean()); var required = new HashSet<String>();
                object.getAsJsonArray("required").forEach(v -> required.add(v.getAsString()));
                assertEquals(object.getAsJsonObject("properties").keySet(), required);
            }
            object.entrySet().forEach(e -> strictObjects(e.getValue()));
        } else if (element.isJsonArray()) element.getAsJsonArray().forEach(InvoiceDecisionV2Test::strictObjects);
    }
}
