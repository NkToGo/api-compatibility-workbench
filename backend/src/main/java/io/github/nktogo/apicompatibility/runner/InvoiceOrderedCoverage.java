package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static io.github.nktogo.apicompatibility.runner.InvoiceBusinessCoverage.Entry;

/** Ordered host-policy replay of captured typed observations, not new consumer dispatch per position. */
final class InvoiceOrderedCoverage {
    private static final Gson IDENTITY_JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    enum Outcome { VERIFIED, FAILED, NOT_EVALUATED, CANCELLED, NOT_ATTEMPTED }
    record Sequence(String corpus, String corpusIdentity, String scenario, String id, String installation,
                    Map<String, InvoiceRenewalLedger.Effect> initial, List<Entry> entries,
                    List<String> expectedOutcomes, Map<String, InvoiceRenewalLedger.Effect> expectedFinal,
                    boolean blockedPathReviewRequired) {
        Sequence { initial = Map.copyOf(initial); entries = List.copyOf(entries); expectedOutcomes = List.copyOf(expectedOutcomes); expectedFinal = Map.copyOf(expectedFinal); }
        String key() { return corpus + "/" + scenario + "/" + id; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("key", key()); json.addProperty("corpusIdentity", corpusIdentity);
            json.addProperty("corpusId", corpus); json.addProperty("scenarioId", scenario); json.addProperty("sequenceId", id);
            json.addProperty("installationId", installation); json.addProperty("blockedPathReviewRequired", blockedPathReviewRequired);
            var order = new JsonArray(); entries.forEach(e -> order.add(e.key())); json.add("deliveries", order); return json;
        }
    }
    static final class Plan {
        private final InvoiceBusinessCoverage.Plan business;
        private final List<Sequence> sequences;
        private Plan(InvoiceBusinessCoverage.Plan business) {
            this.business = business; var fixtures = business.fixtures(); var indexed = new HashMap<String, Entry>();
            business.entries().forEach(e -> indexed.put(e.key(), e)); var rows = new ArrayList<Sequence>();
            for (var corpus : fixtures.businessCorpora().values()) for (var scenario : corpus.scenarios()) for (var sequence : scenario.sequences())
                rows.add(new Sequence(corpus.id(), corpus.identity(), scenario.id(), sequence.id(), corpus.installationId(), scenario.initialEffects(),
                        select(indexed, corpus.id(), scenario.id(), sequence.deliveries()), sequence.outcomes().stream().map(Enum::name).toList(), sequence.finalEffects(), false));
            var old = fixtures.oldDelivery();
            for (var scenario : old.scenarios()) for (var sequence : scenario.sequences())
                rows.add(new Sequence("old-delivery-v1", old.identity(), scenario.id(), sequence.id(), old.installationId(), Map.of(),
                        select(indexed, "old-delivery-v1", scenario.id(), sequence.deliveries()), sequence.outcomes().stream().map(Enum::name).toList(), sequence.finalEffects(), scenario.requiresBlockedPathReview()));
            CaseInputs.require(rows.size() == 188 && rows.stream().map(Sequence::key).distinct().count() == 188, "Incomplete ordered business plan");
            this.sequences = List.copyOf(rows);
        }
        InvoiceBusinessCoverage.Plan business() { return business; }
        List<Sequence> sequences() { return sequences; }
        JsonObject metadata() {
            var json = new JsonObject(); json.addProperty("formatVersion", 1); json.addProperty("caseId", InvoiceRenewalFixtures.CASE_ID);
            json.addProperty("authority", "host-ledger-replay-of-captured-observations"); json.addProperty("coverageStage", "ORDERED_BUSINESS_REPLAY");
            json.addProperty("registryIdentity", business.fixtures().identity()); json.addProperty("businessPlanIdentity", identity(business.metadata()));
            json.addProperty("declaredSequences", sequences.size()); json.addProperty("declaredPositions", sequences.stream().mapToInt(s -> s.entries.size()).sum());
            json.addProperty("consumerDispatchesDuringReplay", 0); json.addProperty("blockedPathReviewRequired", sequences.stream().anyMatch(Sequence::blockedPathReviewRequired));
            var rows = new JsonArray(); sequences.forEach(s -> rows.add(s.metadata())); json.add("sequences", rows); return json;
        }
    }
    static Plan load(Path caseRoot) throws IOException { return new Plan(InvoiceBusinessCoverage.load(caseRoot)); }
    static Plan from(InvoiceBusinessCoverage.Plan business) { return new Plan(Objects.requireNonNull(business)); }
    private static List<Entry> select(Map<String, Entry> indexed, String corpus, String scenario, List<String> deliveries) {
        return deliveries.stream().map(id -> Objects.requireNonNull(indexed.get(corpus + "/" + scenario + "/" + id), "Unregistered ordered delivery")).toList();
    }
    record Position(Outcome outcome, JsonObject evidence) {
        Position { evidence = evidence.deepCopy(); }
        @Override public JsonObject evidence() { return evidence.deepCopy(); }
    }
    /** Pure checker used only after the complete typed run/plan boundary has matched. */
    static Position observe(Entry entry, InvoiceRenewalOracle.Observation observation, InvoiceRenewalLedger ledger, String expectedOutcome) {
        var row = position(entry, expectedOutcome); row.add("observation", observation.json());
        boolean bound = entry.expected() != null && entry.consumer().equals(observation.consumerId()) && entry.expected().equals(observation.expected());
        if (!bound) { row.addProperty("bindingIssue", "OBSERVATION_SCOPE_MISMATCH"); return positionResult(row, Outcome.NOT_EVALUATED); }
        var receipt = ledger.accept(observation); var receiptJson = new JsonObject(); receiptJson.addProperty("sequence", receipt.sequence()); receiptJson.addProperty("outcome", receipt.outcome().name());
        row.add("ledgerReceipt", receiptJson); row.addProperty("actualOutcome", receipt.outcome().name());
        Outcome outcome = observation.assertion() == InvoiceRenewalOracle.Assertion.NOT_EVALUATED ? Outcome.NOT_EVALUATED
                : observation.assertion() == InvoiceRenewalOracle.Assertion.PASSED && expectedOutcome.equals(receipt.outcome().name()) ? Outcome.VERIFIED : Outcome.FAILED;
        return positionResult(row, outcome);
    }
    private static JsonObject position(Entry entry, String expected) {
        var row = new JsonObject(); row.addProperty("deliveryKey", entry.key()); row.addProperty("consumerId", entry.consumer());
        row.addProperty("inputIdentity", InvoiceRenewalOracle.inputIdentity(entry.input())); row.addProperty("evidenceIdentity", entry.evidenceIdentity());
        row.addProperty("expectedOutcome", expected); row.add("actualOutcome", JsonNull.INSTANCE); row.add("observation", JsonNull.INSTANCE); row.add("ledgerReceipt", JsonNull.INSTANCE); return row;
    }
    private static Position positionResult(JsonObject row, Outcome outcome) { row.addProperty("outcome", outcome.name()); return new Position(outcome, row); }
    static final class SequenceResult {
        private final Outcome outcome;
        private final JsonObject json;
        private SequenceResult(Outcome outcome, JsonObject json) { this.outcome = outcome; this.json = json.deepCopy(); }
        Outcome outcome() { return outcome; }
        JsonObject json() { return json.deepCopy(); }
    }
    static final class Run {
        private final Plan plan;
        private final InvoiceBusinessCoverage.Run business;
        private final List<SequenceResult> sequences;
        private final JsonObject report;
        private Run(Plan plan, InvoiceBusinessCoverage.Run business, JsonObject metadata, List<SequenceResult> sequences) {
            this.plan = plan; this.business = business;
            this.sequences = List.copyOf(sequences); report = metadata.deepCopy(); report.remove("sequences");
            var outcomes = new JsonObject(); for (var value : Outcome.values()) outcomes.addProperty(value.name(), sequences.stream().filter(s -> s.outcome == value).count()); report.add("outcomes", outcomes);
            var rows = new JsonArray(); sequences.forEach(s -> rows.add(s.json())); report.add("sequences", rows);
            report.addProperty("orderedCoverageSatisfied", orderedCoverageSatisfied()); report.addProperty("boundaryControlsVerified", 0); report.addProperty("fullCaseCoverageSatisfied", false);
            report.addProperty("orderedSequencesVerified", sequences.stream().filter(s -> s.outcome == Outcome.VERIFIED).count());
        }
        List<SequenceResult> sequences() { return sequences; }
        boolean belongsTo(Plan plan, InvoiceBusinessCoverage.Run business) { return this.plan == plan && this.business == business; }
        JsonObject json() { return report.deepCopy(); }
        boolean orderedCoverageSatisfied() { return sequences.size() == 188 && sequences.stream().allMatch(s -> s.outcome == Outcome.VERIFIED); }
        void requireFullCaseCoverage() { throw new IllegalStateException("Boundary execution controls and joint comparison remain separate work"); }
    }
    private InvoiceOrderedCoverage() {}
    static Run evaluate(Plan plan, InvoiceBusinessCoverage.Run business, Path directory, BooleanSupplier stopped) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(stopped);
        CaseInputs.require(business != null && business.belongsTo(plan.business), "Ordered replay requires the original typed business run and plan");
        var indexed = new HashMap<String, InvoiceBusinessCoverage.DeliveryResult>();
        for (var delivery : business.deliveries()) CaseInputs.require(indexed.put(delivery.entry().key(), delivery) == null, "Duplicate business result");
        CaseInputs.require(indexed.keySet().equals(new HashSet<>(plan.business.entries().stream().map(Entry::key).toList())), "Incomplete business result inventory");
        Path output = directory.toAbsolutePath().normalize(); ConsumerTasks.directory(output.getParent()); Files.createDirectory(output); ConsumerTasks.directory(output);
        var metadata = plan.metadata(); metadata.addProperty("businessEvidenceIdentity", identity(business.json())); metadata.addProperty("imageId", business.json().get("imageId").getAsString());
        metadata.addProperty("businessCoverageSatisfied", business.businessCoverageSatisfied()); metadata.addProperty("orderedHarnessIdentity", harnessIdentity());
        InvoiceBusinessCoverage.write(output.resolve("plan.json"), metadata); var results = new ArrayList<SequenceResult>(); boolean aborted = false;
        for (var sequence : plan.sequences) {
            boolean notStarted = aborted || stopped.getAsBoolean(); var result = replay(sequence, indexed, stopped, notStarted);
            results.add(result); InvoiceBusinessCoverage.write(output.resolve(String.format(Locale.ROOT, "%03d.json", results.size())), result.json());
            aborted |= notStarted || result.outcome == Outcome.CANCELLED;
        }
        var run = new Run(plan, business, metadata, results); InvoiceBusinessCoverage.write(output.resolve("report.json"), run.json()); return run;
    }
    private static SequenceResult replay(Sequence sequence, Map<String, InvoiceBusinessCoverage.DeliveryResult> indexed, BooleanSupplier stopped, boolean notStarted) {
        var ledger = new InvoiceRenewalLedger(sequence.installation, sequence.initial); var rows = new JsonArray(); var outcomes = new ArrayList<Outcome>(); boolean cancelled = false;
        for (int i = 0; i < sequence.entries.size(); i++) {
            var entry = sequence.entries.get(i); var delivery = indexed.get(entry.key()); String expected = sequence.expectedOutcomes.get(i); Position result;
            if (notStarted || cancelled) result = positionResult(position(entry, expected), Outcome.NOT_ATTEMPTED);
            else if (stopped.getAsBoolean()) { cancelled = true; result = positionResult(position(entry, expected), Outcome.NOT_ATTEMPTED); }
            else if (delivery.observation() != null) result = observe(entry, delivery.observation(), ledger, expected);
            else if (entry.expectedBlock() && delivery.outcome() == InvoiceBusinessCoverage.Outcome.EXPECTED_BLOCK) {
                var row = position(entry, expected); row.addProperty("actualOutcome", "BLOCKED_MISSING_ADAPTER");
                result = positionResult(row, expected.equals("BLOCKED_MISSING_ADAPTER") ? Outcome.VERIFIED : Outcome.FAILED);
            } else result = positionResult(position(entry, expected), Outcome.NOT_EVALUATED);
            var row = result.evidence(); row.addProperty("position", i + 1); row.addProperty("businessDeliveryOutcome", delivery.outcome().name()); rows.add(row); outcomes.add(result.outcome());
        }
        var snapshot = ledger.snapshot(); boolean effectsMatch = snapshot.effects().equals(keys(sequence.installation, sequence.expectedFinal));
        Outcome outcome = notStarted ? Outcome.NOT_ATTEMPTED : cancelled ? Outcome.CANCELLED : outcomes.contains(Outcome.FAILED) ? Outcome.FAILED
                : outcomes.stream().anyMatch(o -> o != Outcome.VERIFIED) ? Outcome.NOT_EVALUATED : effectsMatch ? Outcome.VERIFIED : Outcome.FAILED;
        var json = sequence.metadata(); json.add("positions", rows); json.addProperty("outcome", outcome.name());
        json.add("initialEffects", effects(snapshot.initialEffects())); json.add("actualFinalEffects", effects(snapshot.effects())); json.add("expectedFinalEffects", effects(keys(sequence.installation, sequence.expectedFinal)));
        json.addProperty("finalEffectsMatch", effectsMatch); json.addProperty("ledgerReceipts", snapshot.receipts().size()); return new SequenceResult(outcome, json);
    }
    private static Map<InvoiceRenewalLedger.Key, InvoiceRenewalLedger.Effect> keys(String installation, Map<String, InvoiceRenewalLedger.Effect> effects) {
        var keyed = new HashMap<InvoiceRenewalLedger.Key, InvoiceRenewalLedger.Effect>(); effects.forEach((id, effect) -> keyed.put(new InvoiceRenewalLedger.Key(installation, id), effect)); return keyed;
    }
    private static JsonArray effects(Map<InvoiceRenewalLedger.Key, InvoiceRenewalLedger.Effect> effects) {
        var rows = new JsonArray(); effects.entrySet().stream().sorted(Comparator.comparing((Map.Entry<InvoiceRenewalLedger.Key, InvoiceRenewalLedger.Effect> e) -> e.getKey().installationId()).thenComparing(e -> e.getKey().invoiceId())).forEach(e -> {
            var row = new JsonObject(); row.addProperty("installationId", e.getKey().installationId()); row.addProperty("invoiceId", e.getKey().invoiceId());
            row.addProperty("customerId", e.getValue().customerId()); row.addProperty("subscriptionId", e.getValue().subscriptionId()); row.addProperty("action", e.getValue().action().name()); rows.add(row);
        }); return rows;
    }
    static String identity(JsonObject evidence) { return CaseInputs.hash(IDENTITY_JSON.toJson(evidence).getBytes(StandardCharsets.UTF_8)); }
    private static String harnessIdentity() throws IOException {
        var classes = new TreeMap<String, String>(); for (Class<?> type : List.of(InvoiceOrderedCoverage.class, InvoiceOrderedCoverageMain.class, InvoiceRenewalLedger.class, InvoiceRenewalOracle.class, ConsumerTasks.class)) InvoiceSandbox.captureClass(type, classes); return CaseInputs.identity(classes);
    }
}
