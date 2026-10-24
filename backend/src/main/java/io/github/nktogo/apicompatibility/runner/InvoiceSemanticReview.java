package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Binds supplied, possibly incomplete judgments; never decides semantic truth or campaign acceptance. */
public final class InvoiceSemanticReview {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String TIMING = "POST_HOC_FOR_EXISTING_OPERATIONAL_TRIALS";
    private final Path evidence, state;
    private final InvoiceReviewInputs inputs;

    public InvoiceSemanticReview(Path evidence, Path state) throws IOException {
        this.evidence = evidence.toAbsolutePath().normalize(); this.state = state.toAbsolutePath().normalize();
        inputs = new InvoiceReviewInputs(this.evidence, this.state);
    }

    public JsonObject draft(String packetId, ContentDigest packetIdentity) throws IOException {
        return draft(packet(packetId, packetIdentity), packetIdentity);
    }

    public JsonObject verify(Path reviewFile, ContentDigest reviewIdentity) throws IOException {
        Path file = reviewFile.toAbsolutePath().normalize(); byte[] bytes = ConsumerTasks.bytes(file.getParent(), file.getFileName().toString());
        require(new ContentDigest(CaseInputs.hash(bytes)).equals(reviewIdentity), "Changed independently selected invoice review bytes");
        JsonObject review = InvoiceReviewInputs.object(bytes); String packetId = string(review, "packetId");
        var packetIdentity = new ContentDigest(string(review, "packetSha256")); var packet = packet(packetId, packetIdentity);
        var result = validate(review, packet, artifact -> ConsumerTasks.bytes(evidence, artifact));
        require(Arrays.equals(bytes, ConsumerTasks.bytes(file.getParent(), file.getFileName().toString())), "Review changed during inspection");
        packet(packetId, packetIdentity); // Check the entire retained source inventory again, not only used citations.
        result.addProperty("reviewSha256", reviewIdentity.sha256()); return result;
    }

    public void create(Path destination, String packetId, ContentDigest packetIdentity) throws IOException {
        var packet = packet(packetId, packetIdentity); Path target = destination.toAbsolutePath().normalize(); safeDirectory(target.getParent());
        require(!target.startsWith(state.resolve("invoice-review-packets")), "Review output overlaps its input packets");
        var selection = packet.getAsJsonObject("selection"); var roots = new ArrayList<String>();
        for (String key : List.of("inputDirectory", "contractDirectory", "rubricArtifact")) roots.add(string(selection, key));
        selection.getAsJsonArray("sources").forEach(s -> roots.add(string(s.getAsJsonObject(), "stateDirectory")));
        for (String root : roots) require(!target.startsWith(evidence.resolve(root)), "Review output overlaps selected source evidence");
        byte[] bytes = (encoded(draft(packet, packetIdentity)) + "\n").getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= 1048576, "Invoice review exceeds 1 MiB");
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private JsonObject packet(String id, ContentDigest identity) throws IOException {
        require(id.matches("reviewpacket-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice review packet ID");
        Path root = state.resolve("invoice-review-packets/" + id);
        require(CaseInputs.hash(ConsumerTasks.bytes(root, "packet.json")).equals(identity.sha256()), "Changed independently selected invoice packet bytes");
        var packet = inputs.read(id);
        require(CaseInputs.hash(ConsumerTasks.bytes(root, "packet.json")).equals(identity.sha256()), "Packet changed during inspection"); return packet;
    }

    static JsonObject draft(JsonObject packet, ContentDigest packetIdentity) {
        var inventory = packet.getAsJsonObject("inventory"); var rows = new JsonArray();
        for (var element : inventory.getAsJsonArray("trials")) {
            var trial = element.getAsJsonObject(); var units = new JsonArray(); var criteria = new JsonArray();
            for (var unit : trial.getAsJsonArray("units")) {
                var u = unit.getAsJsonObject(); units.add(json(Map.of("unitId", string(u, "unitId"), "valueIdentity", u.get("valueIdentity"),
                        "coverage", "PENDING", "claims", List.of(), "coveredBy", List.of(), "notes", "Not reviewed.")));
            }
            for (String id : criterionIds()) criteria.add(json(Map.of("criterionId", id, "judgment", "PENDING", "rationale", "Not reviewed.", "unitIds", List.of(), "evidenceIds", List.of())));
            rows.add(json(Map.of("position", trial.get("position"), "source", trial.get("source"), "output", trial.getAsJsonObject("trial").getAsJsonArray("cases").get(0),
                    "coverageComplete", false, "criteria", criteria, "units", units)));
        }
        var reviewer = json(Map.of("method", "NOT_RECORDED", "label", "Not reviewed", "independentHumanAdjudication", false,
                "calibratedReviewerAccuracy", false, "blinded", false, "newProviderCalls", 0)); reviewer.add("reviewedOn", JsonNull.INSTANCE);
        var result = json(Map.of("formatVersion", 1, "reviewId", "semanticreview-" + UUID.randomUUID(), "packetId", string(packet, "packetId"),
                "packetSha256", packetIdentity.sha256(), "rubricIdentity", inventory.get("rubricIdentity"), "contractIdentity", inventory.get("contractIdentity"),
                "criteriaTiming", TIMING, "reviewer", reviewer, "scope", "Incomplete descriptive review of selected historical operational trials; no campaign or routing acceptance."));
        result.add("evidence", new JsonArray()); result.add("trials", rows); return result;
    }

    @FunctionalInterface interface SourceReader { byte[] read(String artifact) throws IOException; }

    static JsonObject validate(JsonObject review, JsonObject packet, SourceReader reader) throws IOException {
        keys(review, "formatVersion", "reviewId", "packetId", "packetSha256", "rubricIdentity", "contractIdentity", "criteriaTiming", "reviewer", "scope", "evidence", "trials");
        require(integer(review.get("formatVersion")) == 1 && string(review, "reviewId").matches("semanticreview-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice review identity");
        var inventory = packet.getAsJsonObject("inventory");
        require(review.get("packetId").equals(packet.get("packetId")) && review.get("rubricIdentity").equals(inventory.get("rubricIdentity"))
                && review.get("contractIdentity").equals(inventory.get("contractIdentity")) && string(review, "criteriaTiming").equals(TIMING)
                && string(inventory, "criteriaTiming").equals(TIMING), "Changed invoice review scope or protected identities");
        new ContentDigest(string(review, "packetSha256")); text(review, "scope");
        var artifacts = inventory.getAsJsonObject("sourceArtifacts"); var cache = new HashMap<String, byte[]>();
        SourceReader checked = artifact -> {
            require(artifacts.has(artifact), "Review evidence is outside the bound invoice packet");
            byte[] bytes = reader.read(artifact); require(bytes.length <= 1048576 && CaseInputs.hash(bytes).equals(artifacts.get(artifact).getAsString()), "Changed review source bytes");
            var previous = cache.putIfAbsent(artifact, bytes); require(previous == null || Arrays.equals(previous, bytes), "Review source changed during inspection"); return bytes;
        };
        var rubric = InvoiceReviewInputs.object(checked.read(string(inventory, "rubricArtifact"))); InvoiceReviewInputs.validateRubric(rubric);
        var evidence = new HashSet<String>(); var used = new HashSet<String>();
        for (var element : array(review, "evidence", 5000)) {
            var ref = element.getAsJsonObject(); keys(ref, "evidenceId", "artifact", "sha256", "pointer", "valueIdentity"); String id = string(ref, "evidenceId"), artifact = string(ref, "artifact");
            require(id.matches("[a-z0-9-]{1,120}") && evidence.add(id) && artifacts.has(artifact) && ref.get("sha256").equals(artifacts.get(artifact)), "Invalid or duplicate invoice review evidence");
            var value = InvoiceReviewInputs.pointer(sourceValue(checked.read(artifact)), string(ref, "pointer"));
            require(ref.get("valueIdentity").equals(json(digest(encoded(value)))), "Changed review evidence pointer value");
        }
        var sources = inventory.getAsJsonArray("trials"); var rows = array(review, "trials", 4); require(rows.size() == sources.size() && rows.size() == 4, "Incomplete four-arm review denominator");
        int claims = 0, pending = 0, pendingCriteria = 0, historicalUnsupported = 0, historicalUnresolved = 0, complete = 0; boolean judgmentsMade = false;
        var outcomes = new JsonArray();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i).getAsJsonObject(); var source = sources.get(i).getAsJsonObject();
            keys(row, "position", "source", "output", "coverageComplete", "criteria", "units");
            require(integer(row.get("position")) == i + 1 && row.get("position").equals(source.get("position")) && row.get("source").equals(source.get("source"))
                    && row.get("output").equals(source.getAsJsonObject("trial").getAsJsonArray("cases").get(0)), "Changed ordered invoice trial or output binding");
            boolean attested = bool(row, "coverageComplete"); var items = source.getAsJsonArray("units"); var units = array(row, "units", 2000);
            require(units.size() == items.size(), "Incomplete invoice review unit denominator");
            var byId = new HashMap<String, JsonObject>(); var kinds = new HashMap<String, JsonObject>(); int rowPending = 0, unsupported = 0, unresolved = 0;
            for (int n = 0; n < units.size(); n++) {
                var unit = units.get(n).getAsJsonObject(); var item = items.get(n).getAsJsonObject();
                keys(unit, "unitId", "valueIdentity", "coverage", "claims", "coveredBy", "notes");
                String id = string(unit, "unitId"), kind = string(item, "kind"), coverage = string(unit, "coverage");
                require(id.equals(string(item, "unitId")) && byId.putIfAbsent(id, unit) == null && unit.get("valueIdentity").equals(item.get("valueIdentity")), "Changed, reordered or duplicate invoice review unit"); kinds.put(id, item);
                var value = InvoiceReviewInputs.pointer(sourceValue(checked.read(string(item, "artifact"))), string(item, "pointer"));
                require(item.get("valueIdentity").equals(json(digest(encoded(value)))), "Changed reviewed unit value");
                text(unit, "notes"); var judgments = array(unit, "claims", 2000); var coveredBy = array(unit, "coveredBy", 2000);
                require(Set.of("PENDING", "CLAIMS", "CONTAINER", "CONTEXT").contains(coverage), "Invalid invoice unit coverage");
                if (coverage.equals("PENDING")) { require(judgments.isEmpty() && coveredBy.isEmpty(), "Pending units cannot conceal judgments"); rowPending++; continue; }
                judgmentsMade = true;
                if (coverage.equals("CONTEXT")) require(judgments.isEmpty() && coveredBy.isEmpty() && contextOnly(item, value), "Claim-bearing invoice unit cannot be excluded as context");
                if (coverage.equals("CONTAINER")) require(judgments.isEmpty() && Set.of("FINAL_DOSSIER", "WORKER_DOSSIER", "PARTIAL_AGGREGATE").contains(kind), "Invalid invoice container delegation");
                if (coverage.equals("CLAIMS")) require(!judgments.isEmpty() && coveredBy.isEmpty(), "Missing invoice claim decomposition");
                var ids = new HashSet<String>();
                for (var element : judgments) {
                    var claim = element.getAsJsonObject(); keys(claim, "claimId", "anchor", "proposition", "verdict", "material", "endorsed", "evidenceIds", "rationale");
                    require(string(claim, "claimId").matches("claim-[1-9][0-9]{0,5}") && ids.add(string(claim, "claimId")), "Duplicate or invalid invoice claim ID");
                    text(claim, "proposition"); text(claim, "rationale"); String verdict = string(claim, "verdict");
                    require(Set.of("SUPPORTED_WITHIN_RECORDED_SCOPE", "UNSUPPORTED", "UNRESOLVED").contains(verdict), "Invalid supplied invoice claim verdict");
                    boolean material = bool(claim, "material"), endorsed = bool(claim, "endorsed");
                    if (!Set.of("TRIAL_OUTCOME", "WORKER_OUTCOME").contains(kind)) require(material, "Claim passage cannot be relabeled as nonmaterial");
                    if (Set.of("SUMMARY", "FINDING", "DOSSIER_SECTION").contains(kind)) require(endorsed, "Final or worker dossier assertion cannot be excluded from review gates");
                    var anchor = claim.getAsJsonObject("anchor"); keys(anchor, "representation", "pointer", "valueIdentity");
                    JsonElement base = value; String representation = string(anchor, "representation");
                    if (representation.equals("JSON_TEXT")) { require(kind.equals("MODEL_RESPONSE") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Invalid decoded response anchor"); base = InvoiceReviewInputs.object(value.getAsString().getBytes(StandardCharsets.UTF_8)); }
                    else require(representation.equals("NATIVE"), "Unknown claim anchor representation");
                    require(anchor.get("valueIdentity").equals(json(digest(encoded(InvoiceReviewInputs.pointer(base, string(anchor, "pointer")))))), "Changed invoice claim anchor");
                    references(claim, "evidenceIds", evidence, used, false);
                    if (material && verdict.equals("UNSUPPORTED")) { historicalUnsupported++; if (endorsed) unsupported++; }
                    if (material && verdict.equals("UNRESOLVED")) { historicalUnresolved++; if (endorsed) unresolved++; }
                    claims++;
                }
            }
            for (var unit : byId.values()) if (string(unit, "coverage").equals("CONTAINER")) {
                var item = kinds.get(string(unit, "unitId")); var expected = new HashSet<String>();
                for (var other : kinds.values()) if (other.get("artifact").equals(item.get("artifact")) && !string(other, "pointer").isEmpty()) expected.add(string(other, "unitId"));
                var delegated = refs(unit, "coveredBy", byId.keySet(), false); require(delegated.equals(expected) && !expected.isEmpty(), "Incomplete or cyclic invoice container coverage");
            }
            var criteria = array(row, "criteria", 6); require(criteria.size() == 6, "Missing protected invoice criteria"); var seen = new HashSet<String>(); boolean criteriaSatisfied = true; int rowPendingCriteria = 0;
            for (var element : criteria) {
                var criterion = element.getAsJsonObject(); keys(criterion, "criterionId", "judgment", "rationale", "unitIds", "evidenceIds");
                require(criterionIds().contains(string(criterion, "criterionId")) && seen.add(string(criterion, "criterionId")), "Changed or duplicate protected criterion"); text(criterion, "rationale");
                String judgment = string(criterion, "judgment"); require(Set.of("PENDING", "SATISFIED", "NOT_SATISFIED", "UNRESOLVED").contains(judgment), "Invalid invoice criterion judgment");
                var cited = refs(criterion, "unitIds", byId.keySet(), judgment.equals("PENDING")); references(criterion, "evidenceIds", evidence, used, judgment.equals("PENDING"));
                if (judgment.equals("PENDING")) { require(cited.isEmpty() && criterion.getAsJsonArray("evidenceIds").isEmpty(), "Pending criteria cannot conceal review citations"); criteriaSatisfied = false; rowPendingCriteria++; }
                else { judgmentsMade = true; require(cited.stream().noneMatch(id -> string(byId.get(id), "coverage").equals("PENDING")), "Criterion cites an unreviewed unit"); criteriaSatisfied &= judgment.equals("SATISFIED"); }
            }
            boolean allReviewed = rowPending == 0 && criteria.asList().stream().noneMatch(c -> string(c.getAsJsonObject(), "judgment").equals("PENDING"));
            require(!attested || allReviewed, "Incomplete invoice review cannot attest complete coverage");
            boolean responseMatches = string(row.getAsJsonObject("output"), "response").equals(string(rubric, "expectedResponse"));
            boolean satisfies = attested && allReviewed && criteriaSatisfied && responseMatches && unsupported == 0 && unresolved == 0 && string(source.getAsJsonObject("trial"), "status").equals("COMPLETED");
            if (attested) complete++; pending += rowPending; pendingCriteria += rowPendingCriteria;
            outcomes.add(json(Map.of("position", i + 1, "arm", source.getAsJsonObject("source").get("arm"), "coverageAttestedComplete", attested, "pendingUnits", rowPending,
                    "pendingCriteria", rowPendingCriteria, "endorsedMaterialUnsupported", unsupported, "endorsedMaterialUnresolved", unresolved, "satisfiesDeclaredPostHocReview", satisfies)));
        }
        require(evidence.equals(used), "Unused or missing invoice review evidence catalog"); provenance(review.getAsJsonObject("reviewer"), judgmentsMade);
        for (var entry : cache.entrySet()) require(Arrays.equals(entry.getValue(), checked.read(entry.getKey())), "Review evidence changed during verification");
        var result = json(Map.of("status", "INVOICE_REVIEW_BINDINGS_MATCHED", "reviewId", string(review, "reviewId"), "packetId", string(packet, "packetId"), "reviewedTrials", complete,
                "pendingUnits", pending, "claimJudgments", claims, "historicalMaterialUnsupported", historicalUnsupported, "historicalMaterialUnresolved", historicalUnresolved,
                "criteriaTiming", TIMING, "campaignAcceptance", "NOT_ESTABLISHED")); result.add("trials", outcomes); result.addProperty("pendingCriteria", pendingCriteria);
        result.addProperty("authority", "Validates supplied bindings and declared review gates, not semantic truth, reviewer accuracy, release approval or routing permission."); return result;
    }

    private static void provenance(JsonObject reviewer, boolean judgments) {
        keys(reviewer, "method", "label", "reviewedOn", "independentHumanAdjudication", "calibratedReviewerAccuracy", "blinded", "newProviderCalls"); text(reviewer, "label");
        require(!bool(reviewer, "independentHumanAdjudication") && !bool(reviewer, "calibratedReviewerAccuracy") && !bool(reviewer, "blinded") && integer(reviewer.get("newProviderCalls")) == 0, "Unsupported reviewer provenance for offline unblinded inspection");
        String method = string(reviewer, "method");
        if (method.equals("NOT_RECORDED")) require(!judgments && reviewer.get("reviewedOn").isJsonNull(), "Judgments require recorded reviewer provenance");
        else { require(Set.of("model-assisted-offline-inspection", "human-offline-inspection").contains(method), "Unknown invoice reviewer method"); LocalDate.parse(string(reviewer, "reviewedOn")); }
    }

    private static boolean contextOnly(JsonObject item, JsonElement value) {
        String kind = string(item, "kind"), pointer = string(item, "pointer");
        if (Set.of("TRIAL_OUTCOME", "WORKER_OUTCOME").contains(kind)) return true;
        if (kind.equals("TEAM_CONTEXT")) return value.isJsonArray() && value.getAsJsonArray().isEmpty();
        if (kind.equals("DOSSIER_SECTION")) {
            if (pointer.equals("/formatVersion")) return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            if (pointer.equals("/summaryCitations")) return value.isJsonArray() && value.getAsJsonArray().asList().stream().allMatch(v -> v.isJsonPrimitive() && v.getAsString().matches("[a-zA-Z0-9:/._-]{1,160}"));
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false; String text = value.getAsString();
            return switch (pointer) {
                case "/caseId" -> text.equals(InvoiceRenewalFixtures.CASE_ID);
                case "/bundleIdentity" -> text.matches("[0-9a-f]{64}");
                case "/response" -> Set.of("ANSWERED", "ABSTAINED", "NOT_PROVIDED").contains(text);
                case "/qualityReview" -> text.equals("NOT_PERFORMED");
                case "/action" -> text.equals("finish");
                case "/workerId", "/workerRole", "/selectedDecision" -> text.matches("[a-zA-Z0-9-]{1,128}");
                case "/authority" -> Set.of("model-advice-not-semantic-acceptance", "deterministic-recorded-facts-not-semantic-acceptance", "verbatim-worker-advice-not-semantic-acceptance").contains(text);
                default -> false;
            };
        }
        if (kind.equals("MODEL_RESPONSE") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            try { var decision = InvoiceInvestigationDecision.parse(value.getAsString()); return Set.of("catalog", "read_text").contains(string(decision, "action")); }
            catch (RuntimeException ignored) { return false; }
        }
        return false;
    }

    private static JsonElement sourceValue(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); String trimmed = text.stripLeading();
        return trimmed.startsWith("{") ? InvoiceReviewInputs.object(bytes) : new JsonPrimitive(text);
    }
    private static List<String> criterionIds() { return List.of("versioned-association", "independent-version-boundaries", "application-policy-gates", "joint-ledger-behavior", "execution-and-causal-scope", "evidence-and-uncertainty"); }
    private static Set<String> refs(JsonObject value, String field, Set<String> allowed, boolean emptyAllowed) {
        var values = array(value, field, 5000); require(emptyAllowed || !values.isEmpty(), "Missing invoice review references"); var seen = new HashSet<String>();
        for (var element : values) { require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(), "Invalid reference type"); String id = element.getAsString(); require(allowed.contains(id) && seen.add(id), "Unknown or duplicate invoice review reference"); } return seen;
    }
    private static void references(JsonObject value, String field, Set<String> allowed, Set<String> used, boolean emptyAllowed) { used.addAll(refs(value, field, allowed, emptyAllowed)); }
    private static JsonArray array(JsonObject value, String name, int maximum) { require(value.has(name) && value.get(name).isJsonArray() && value.getAsJsonArray(name).size() <= maximum, "Missing or excessive invoice review array"); return value.getAsJsonArray(name); }
    private static boolean bool(JsonObject value, String key) { require(value.has(key) && value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isBoolean(), "Invalid invoice review boolean"); return value.get(key).getAsBoolean(); }
    private static long integer(JsonElement value) { require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Invalid invoice review integer"); return value.getAsBigDecimal().longValueExact(); }
    private static String string(JsonObject value, String key) { require(value.has(key) && value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isString(), "Invalid invoice review string"); return value.get(key).getAsString(); }
    private static void text(JsonObject value, String key) { String s = string(value, key); require(!s.isBlank() && s.length() <= 16000, "Missing or excessive invoice review text"); }
    private static void keys(JsonObject value, String... keys) { require(value != null && value.keySet().equals(Set.of(keys)), "Unexpected invoice review fields"); }
    private static void require(boolean value, String message) { CaseInputs.require(value, message); }
    private static void safeDirectory(Path path) { for (Path p = path; p != null; p = p.getParent()) ConsumerTasks.directory(p); }

    public static void main(String[] args) throws IOException {
        require(args.length == 5 || args.length == 6, "Usage: draft EVIDENCE STATE PACKET PACKET_SHA DESTINATION | verify EVIDENCE STATE REVIEW REVIEW_SHA");
        var review = new InvoiceSemanticReview(Path.of(args[1]), Path.of(args[2]));
        if (args[0].equals("draft") && args.length == 6) { review.create(Path.of(args[5]), args[3], new ContentDigest(args[4])); System.out.println("Created incomplete invoice review; all units and criteria PENDING. No semantic acceptance."); }
        else { require(args[0].equals("verify") && args.length == 5, "Invalid invoice review action"); System.out.println(encoded(review.verify(Path.of(args[3]), new ContentDigest(args[4])))); }
    }
}
