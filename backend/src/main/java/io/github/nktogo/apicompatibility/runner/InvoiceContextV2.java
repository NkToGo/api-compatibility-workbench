package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validates supplied owned records against immutable capture bytes; it cannot issue or authenticate grants. */
final class InvoiceContextV2 {
    static final String POLICY = "invoice-owned-read-context-v2";
    static final int MAX_INPUT_BYTES = 64000, RECENT_PAGES = 6;

    /** Host scheduling snapshot before reserving this round; no forecast is an authorization or guarantee. */
    record Scheduling(int sharedCallsRemaining, int activeParticipants) {
        Scheduling {
            CaseInputs.require(sharedCallsRemaining >= 1 && sharedCallsRemaining <= 60
                    && activeParticipants >= 1 && activeParticipants <= 2, "Invalid invoice scheduling snapshot");
        }
        JsonObject toJson() {
            int scheduled = Math.min(sharedCallsRemaining, activeParticipants), after = sharedCallsRemaining - scheduled;
            var value = new JsonObject(); value.addProperty("sharedCallsRemainingBeforeRound", sharedCallsRemaining);
            value.addProperty("activeParticipants", activeParticipants); value.addProperty("currentRoundReservations", scheduled);
            value.addProperty("currentParticipantScheduledDecisions", 1);
            value.addProperty("futureFullRoundsIfPeersStayActive", after / activeParticipants);
            value.addProperty("futurePartialRoundReservations", after % activeParticipants);
            value.addProperty("lastScheduledDecisionCertainIfPeersStayActive", after == 0);
            value.addProperty("laterSchedulingIfPeersStayActive", after == 0 ? "NONE"
                    : after < activeParticipants ? "PARTIAL_ROUND_UNASSIGNED" : "FULL_ROUND_AVAILABLE");
            value.addProperty("futureTurnGuaranteed", false); value.addProperty("finishConsumesDecision", true);
            value.addProperty("authority", "host-scheduling-snapshot-not-permission-or-future-turn-guarantee"); return value;
        }
    }
    private InvoiceContextV2() {}
    private record Prepared(JsonObject description, JsonArray metadata, JsonArray recent, InvoiceReadiness.Snapshot readiness) {}

    /** Caller must supply only runtime-admitted and integrity-verified records from this participant's trial. */
    static String build(InvoiceReadiness.Subject subject, InvoiceComparisonInputs.Bundle bundle,
                        JsonObject bootstrap, List<JsonObject> suppliedReads, Scheduling scheduling, int byteLimit) {
        Objects.requireNonNull(scheduling);
        CaseInputs.require(byteLimit >= 1 && byteLimit <= MAX_INPUT_BYTES, "Invalid invoice context byte ceiling");
        var prepared = prepare(subject, bundle, bootstrap, suppliedReads);
        var description = prepared.description(); var metadata = prepared.metadata(); var recent = prepared.recent(); var readiness = prepared.readiness();
        var context = new JsonObject(); context.addProperty("formatVersion", 2); context.addProperty("contextPolicy", POLICY);
        context.addProperty("protocol", InvoiceDecisionV2.protocol(subject.scope()));
        context.addProperty("contractIdentity", InvoiceDecisionV2.contractIdentity(subject.scope()));
        context.add("subject", CaseInputs.JSON.toJsonTree(subject)); context.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
        var task = new JsonObject(); task.addProperty("sha256", InvoiceDecisionV2.taskIdentity()); task.addProperty("content", InvoiceDecisionV2.PUBLIC_TASK);
        context.add("publicTask", task); context.add("captureMetadata", description);
        context.addProperty("captureMetadataAuthority", "historical-capture-not-current-runtime-capabilities");
        context.add("readiness", readiness.toJson()); context.add("scheduling", scheduling.toJson());
        context.add("readMetadata", metadata); context.add("recentPages", recent);
        context.add("contractActions", CaseInputs.JSON.toJsonTree(List.of("catalog", "catalog_prefix", "read_text", "finish")));
        context.addProperty("authority", "validated-context-not-runtime-admission-or-semantic-acceptance");
        context.addProperty("qualityReview", "NOT_PERFORMED");
        String input = InvoiceDecisionV2.canonical(context);
        while (bytes(input) > byteLimit && !recent.isEmpty()) { recent.remove(0); input = InvoiceDecisionV2.canonical(context); }
        if (bytes(input) > byteLimit) throw new CoordinationRuntime.Stop("CONTEXT_BUDGET_EXHAUSTED");
        return input;
    }
    static InvoiceReadiness.Snapshot readiness(InvoiceReadiness.Subject subject, InvoiceComparisonInputs.Bundle bundle,
                                               JsonObject bootstrap, List<JsonObject> suppliedReads) {
        return prepare(subject, bundle, bootstrap, suppliedReads).readiness();
    }
    private static Prepared prepare(InvoiceReadiness.Subject subject, InvoiceComparisonInputs.Bundle bundle,
                                     JsonObject bootstrap, List<JsonObject> suppliedReads) {
        Objects.requireNonNull(subject); Objects.requireNonNull(bundle);
        CaseInputs.require(subject.inputIdentity().equals(bundle.identity()), "Context input identity mismatch");
        var reads = List.copyOf(suppliedReads); CaseInputs.require(reads.size() <= InvoiceReadiness.MAX_PAGES, "Too many context reads");
        var description = ownedPage(subject, bundle, bootstrap, true);
        var metadata = new JsonArray(); var recent = new JsonArray(); var readinessPages = new ArrayList<InvoiceReadiness.Page>();
        int previous = 0;
        for (var record : reads) {
            var page = ownedPage(subject, bundle, record, false); String id = text(record, "operationId");
            CaseInputs.require(id.matches("read-[1-9][0-9]{0,2}"), "Invalid context page ID");
            int ordinal = Integer.parseInt(id.substring(5));
            CaseInputs.require(ordinal > previous, "Context reads must have unique increasing operation IDs"); previous = ordinal;
            var request = record.getAsJsonObject("pageRequest"); String kind = text(request, "kind");
            if (kind.equals("read_text")) readinessPages.add(new InvoiceReadiness.TextPage(subject, id, text(page, "alias"),
                    new io.github.nktogo.apicompatibility.compatibility.ContentDigest(text(page, "sha256")),
                    page.get("offset").getAsInt(), page.get("nextOffset").getAsInt(), page.get("totalCharacters").getAsInt()));
            else readinessPages.add(new InvoiceReadiness.CataloguePage(subject, id));
            page.addProperty("pageId", id); page.addProperty("kind", kind); page.addProperty("contentTrust", "untrusted-data");
            var thin = page.deepCopy(); thin.remove("content"); thin.remove("artifacts"); thin.add("pageRequest", request.deepCopy()); metadata.add(thin);
            recent.add(page); if (recent.size() > RECENT_PAGES) recent.remove(0);
        }
        var readiness = InvoiceReadiness.inspect(subject, readinessPages, description.get("sourceExecutionCoverageSatisfied").getAsBoolean());
        return new Prepared(description, metadata, recent, readiness);
    }
    private static JsonObject ownedPage(InvoiceReadiness.Subject subject, InvoiceComparisonInputs.Bundle bundle,
                                         JsonObject record, boolean bootstrap) {
        CaseInputs.require(record != null && bytes(record.toString()) <= 65536 && record.keySet().equals(Set.of(
                "participant", "operationId", "caseId", "bundleIdentity", "pageRequest", "page", "contentTrust")), "Invalid context read wrapper");
        CaseInputs.require(text(record, "participant").equals(subject.participantId())
                && text(record, "bundleIdentity").equals(bundle.identity().sha256())
                && text(record, "caseId").equals(InvoiceRenewalFixtures.CASE_ID)
                && text(record, "contentTrust").equals("untrusted-data"), "Read outside context subject");
        String operation = text(record, "operationId");
        CaseInputs.require(operation.matches("[a-z][a-z0-9-]{0,99}"), "Invalid context read operation");
        CaseInputs.require(record.get("pageRequest").isJsonObject() && record.get("page").isJsonObject(), "Invalid context page shape");
        var request = record.getAsJsonObject("pageRequest");
        CaseInputs.require(request.keySet().equals(Set.of("kind", "caseId", "alias", "offset", "limit"))
                && text(request, "caseId").equals(InvoiceRenewalFixtures.CASE_ID), "Invalid context page request");
        String kind = text(request, "kind"), alias = text(request, "alias");
        int offset = InvoiceDecisionV2.integer(request, "offset", 0, 1048576), limit = InvoiceDecisionV2.integer(request, "limit", 0, 4000);
        CaseInputs.require(bootstrap == kind.equals("describe"), "Unexpected context bootstrap/read kind");
        JsonObject expected = switch (kind) {
            case "describe" -> { CaseInputs.require(alias.isEmpty() && offset == 0 && limit == 0, "Invalid describe request"); yield bundle.describe(); }
            case "catalog" -> { CaseInputs.require(alias.isEmpty(), "Unexpected flat catalogue alias"); yield bundle.catalog(offset, limit); }
            case "catalog_prefix" -> bundle.catalogPrefix(alias, offset, limit);
            case "read_text" -> bundle.read(alias, offset, limit);
            default -> throw new IllegalArgumentException("Unsupported context read kind");
        };
        CaseInputs.require(expected.equals(record.get("page")), "Read content does not match captured request");
        return expected;
    }
    private static String text(JsonObject object, String key) {
        var value = object.get(key);
        CaseInputs.require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected context string");
        return value.getAsString();
    }
    private static int bytes(String input) { return input.getBytes(StandardCharsets.UTF_8).length; }
}
