package io.github.nktogo.apicompatibility.evaluation;

import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationTrial.*;

/** Offline comparison of supplied measurements, never a semantic grader, runtime permission or routing policy. */
public final class CoordinationComparison {
    private CoordinationComparison() {}
    public enum Conclusion { RECORDS_COMPLETE, UNRESOLVED }
    public record CaseAssessment(String caseId, boolean qualityResolved, boolean accepted, int satisfiedCriteria,
                                 int requiredCriteria, int materialUnsupportedClaims, Integer materialUnresolvedClaims, List<String> issues) {
        public CaseAssessment { issues = List.copyOf(issues); }
    }
    public record Row(Slot slot, List<CoordinationTrial> records, boolean comparable, boolean acceptedBundle,
                      List<CaseAssessment> cases, List<String> issues) {
        public Row { records = List.copyOf(records); cases = List.copyOf(cases); issues = List.copyOf(issues); }
        public int acceptedCases() { return (int) cases.stream().filter(CaseAssessment::accepted).count(); }
    }
    public record ArmMetrics(int plannedBundles, int comparableBundles, int reviewedAcceptedBundles, int comparableAcceptedBundles,
                             int reviewedAcceptedCasesForUnambiguousSlots,
                             int failedBundles, int missingSlots, int ambiguousSlots, int incompleteUsageSlots,
                             long reportedInputTokensForUnambiguousSlots, long reportedOutputTokensForUnambiguousSlots,
                             long reportedReservedCallsForUnambiguousSlots, long reportedElapsedMillisForUnambiguousSlots,
                             long reportedToolCallsForUnambiguousSlots, long reportedCaseExecutionsForUnambiguousSlots) {}
    public record Pair(Arm left, Arm right, int comparableBlocks, int leftOnlyAcceptedBundle, int rightOnlyAcceptedBundle,
                       int bothAcceptedBundle, int neitherAcceptedBundle, int unestablishedBlocks,
                       int leftMoreAcceptedCases, int rightMoreAcceptedCases, int equalAcceptedCases) {}
    public record Assessment(Definition definition, Conclusion conclusion, String authority, String routingDecision,
                             List<Row> rows, List<CoordinationTrial> unexpectedRecords, Map<Arm, ArmMetrics> metrics,
                             List<Pair> pairs, List<String> issues) {
        public Assessment {
            rows = List.copyOf(rows); unexpectedRecords = List.copyOf(unexpectedRecords);
            metrics = Collections.unmodifiableMap(new EnumMap<>(metrics)); pairs = List.copyOf(pairs); issues = List.copyOf(issues);
        }
        public boolean appliesTo(Definition current) { return definition.equals(Objects.requireNonNull(current)); }
    }
    public static Assessment evaluate(Definition definition, List<CoordinationTrial> observations) {
        return evaluate(definition, observations, false);
    }
    /** Recomputes the original v1 contract only when validating retained v1 reports. */
    public static Assessment evaluateHistoricalV1(Definition definition, List<CoordinationTrial> observations) {
        require(observations.stream().flatMap(t -> t.cases().stream()).allMatch(c -> c.review() == null
                || c.review().materialUnresolvedClaims() == null), "Version 2 review in historical v1 records");
        return evaluate(definition, observations, true);
    }
    private static Assessment evaluate(Definition definition, List<CoordinationTrial> observations, boolean historicalV1) {
        Objects.requireNonNull(definition); observations = List.copyOf(observations);
        require(observations.size() <= 4096, "Too many comparison records");
        var expected = new LinkedHashMap<Slot, Workload>();
        for (Workload workload : definition.workloads()) for (int repetition = 1; repetition <= definition.repetitions(); repetition++)
            for (Arm arm : Arm.values()) expected.put(new Slot(workload.workloadId(), repetition, arm), workload);
        var grouped = new HashMap<Slot, List<CoordinationTrial>>(); var runCounts = new HashMap<String, Integer>(); var traceCounts = new HashMap<String, Integer>();
        var unexpected = new ArrayList<CoordinationTrial>();
        for (var observation : observations) {
            runCounts.merge(observation.runId(), 1, Integer::sum);
            if (observation.traceIdentity() != null) traceCounts.merge(observation.traceIdentity().sha256(), 1, Integer::sum);
            if (expected.containsKey(observation.slot())) grouped.computeIfAbsent(observation.slot(), ignored -> new ArrayList<>()).add(observation);
            else unexpected.add(observation);
        }
        var rows = new ArrayList<Row>();
        for (var entry : expected.entrySet()) rows.add(row(definition, entry.getKey(), entry.getValue(), grouped.getOrDefault(entry.getKey(), List.of()), runCounts, traceCounts, historicalV1));
        var issues = new ArrayList<String>(); if (!unexpected.isEmpty()) issues.add("UNEXPECTED_TRIAL_RECORDS");
        if (rows.stream().anyMatch(r -> !r.comparable())) issues.add("UNESTABLISHED_PAIRED_COVERAGE");
        var metrics = new EnumMap<Arm, ArmMetrics>(Arm.class);
        for (Arm arm : Arm.values()) metrics.put(arm, metrics(rows.stream().filter(r -> r.slot().arm() == arm).toList()));
        var pairs = new ArrayList<Pair>();
        for (Arm left : Arm.values()) for (Arm right : Arm.values()) if (left.ordinal() < right.ordinal()) pairs.add(pair(rows, left, right));
        return new Assessment(definition, issues.isEmpty() ? Conclusion.RECORDS_COMPLETE : Conclusion.UNRESOLVED,
                "supplied-comparison-records-not-authenticated", "NOT_ESTABLISHED", rows, unexpected, metrics, pairs, issues);
    }
    private static Row row(Definition definition, Slot slot, Workload workload, List<CoordinationTrial> records, Map<String, Integer> runCounts, Map<String, Integer> traceCounts, boolean historicalV1) {
        var issues = new ArrayList<String>(); var cases = new ArrayList<CaseAssessment>();
        if (records.size() != 1) {
            issues.add(records.isEmpty() ? "MISSING_TRIAL" : "AMBIGUOUS_TRIAL");
            for (CaseTask task : workload.cases()) cases.add(missing(task, "UNESTABLISHED_CASE_OUTPUT"));
            return new Row(slot, records, false, false, cases, issues);
        }
        var record = records.getFirst();
        if (runCounts.get(record.runId()) != 1) issues.add("REUSED_RUN_ID");
        if (record.traceIdentity() != null && traceCounts.get(record.traceIdentity().sha256()) != 1) issues.add("REUSED_TRACE_IDENTITY");
        boolean binding = identity(definition).equals(record.definitionIdentity())
                && definition.implementations().get(slot.arm()).equals(record.implementationIdentity())
                && (slot.arm() == Arm.DETERMINISTIC ? record.model() == null : definition.model().equals(record.model()));
        if (!binding) issues.add("CHANGED_COMPARISON_CONDITIONS");
        if (record.status() == Status.PENDING) issues.add("TRIAL_PENDING");
        if (record.status() == Status.UNKNOWN_REMOTE_OUTCOME) issues.add("UNKNOWN_REMOTE_OUTCOME");
        if (!record.usage().complete()) issues.add("INCOMPLETE_USAGE");
        // The first bound limits completed responses to at most 500 before multiplication.
        if (record.usage().reservedCalls() > definition.limits().modelCalls() || record.elapsedMillis() > definition.limits().elapsedMillis()
                || record.usage().outputTokens() > record.usage().completedResponses() * definition.limits().outputTokensPerCall()) issues.add("BUDGET_EXCEEDED");
        if (slot.arm() == Arm.DETERMINISTIC && (record.usage().reservedCalls() != 0 || record.usage().inputTokens() != 0 || record.usage().outputTokens() != 0)) issues.add("MODEL_USE_IN_DETERMINISTIC_ARM");
        Set<String> declaredCases = new HashSet<>(); workload.cases().forEach(c -> declaredCases.add(c.caseId()));
        if (record.cases().stream().anyMatch(c -> !declaredCases.contains(c.caseId()))) issues.add("UNEXPECTED_CASE_OUTPUT");
        for (CaseTask task : workload.cases()) {
            var values = record.cases().stream().filter(c -> c.caseId().equals(task.caseId())).toList();
            CaseAssessment assessment;
            if (values.size() > 1) assessment = missing(task, "AMBIGUOUS_CASE_OUTPUT");
            else if (values.isEmpty() || values.getFirst().response() == Response.NOT_PROVIDED) {
                boolean knownAbsence = record.status() == Status.FAILED || record.status() == Status.CANCELLED
                        || record.status() == Status.COMPLETED && !values.isEmpty();
                assessment = new CaseAssessment(task.caseId(), knownAbsence && binding, false, 0, task.criteria().size(), 0, null, List.of("NO_ANSWER_PROVIDED"));
                if (!values.isEmpty() && !task.inputIdentity().equals(values.getFirst().inputIdentity())) assessment = missing(task, "CHANGED_CASE_INPUTS");
            } else assessment = review(task, values.getFirst(), binding, historicalV1);
            cases.add(assessment);
        }
        if (cases.stream().anyMatch(c -> !c.qualityResolved())) issues.add("INCOMPLETE_QUALITY_REVIEW");
        boolean comparable = issues.isEmpty();
        boolean acceptedBundle = binding && record.status() == Status.COMPLETED && cases.stream().allMatch(CaseAssessment::accepted)
                && !issues.contains("UNEXPECTED_CASE_OUTPUT") && !issues.contains("REUSED_RUN_ID") && !issues.contains("REUSED_TRACE_IDENTITY");
        return new Row(slot, records, comparable, acceptedBundle, cases, issues);
    }
    private static CaseAssessment review(CaseTask task, CaseOutput output, boolean trialBinding, boolean historicalV1) {
        var issues = new ArrayList<String>(); var review = output.review();
        if (!trialBinding || !task.inputIdentity().equals(output.inputIdentity())) issues.add("CHANGED_CASE_INPUTS");
        if (review == null) return new CaseAssessment(task.caseId(), false, false, 0, task.criteria().size(), 0, null, concat(issues, "REVIEW_MISSING"));
        if (!review.outputIdentity().equals(output.outputIdentity())) issues.add("CHANGED_REVIEWED_OUTPUT");
        if (!review.claimAuditComplete()) issues.add("CLAIM_AUDIT_PENDING");
        if (!historicalV1) {
            if (review.materialUnresolvedClaims() == null) issues.add("MATERIAL_CLAIM_RESOLUTION_NOT_RECORDED");
            else if (review.materialUnresolvedClaims() > 0) issues.add("MATERIAL_UNRESOLVED_CLAIM");
        }
        if (!review.judgments().keySet().equals(new HashSet<>(task.criteria())) || review.judgments().containsValue(Judgment.UNRESOLVED)) issues.add("CRITERION_REVIEW_INCOMPLETE");
        int satisfied = (int) task.criteria().stream().filter(c -> review.judgments().get(c) == Judgment.SATISFIED).count();
        boolean resolved = issues.isEmpty();
        boolean responseMatches = task.expectedResponse() == ExpectedResponse.ANSWER ? output.response() == Response.ANSWERED : output.response() == Response.ABSTAINED;
        boolean accepted = resolved && satisfied == task.criteria().size() && review.materialUnsupportedClaims() == 0 && responseMatches;
        if (!responseMatches) issues.add("UNEXPECTED_RESPONSE_DISPOSITION");
        if (review.materialUnsupportedClaims() > 0) issues.add("MATERIAL_UNSUPPORTED_CLAIM");
        return new CaseAssessment(task.caseId(), resolved, accepted, satisfied, task.criteria().size(), review.materialUnsupportedClaims(), review.materialUnresolvedClaims(), issues);
    }
    private static CaseAssessment missing(CaseTask task, String issue) { return new CaseAssessment(task.caseId(), false, false, 0, task.criteria().size(), 0, null, List.of(issue)); }
    private static List<String> concat(List<String> values, String value) { var result = new ArrayList<>(values); result.add(value); return result; }
    private static ArmMetrics metrics(List<Row> rows) {
        int comparable = 0, accepted = 0, comparableAccepted = 0, cases = 0, failed = 0, missing = 0, ambiguous = 0, incompleteUsage = 0;
        long input = 0, output = 0, calls = 0, elapsed = 0, tools = 0, executions = 0;
        for (Row row : rows) {
            if (row.comparable()) comparable++;
            if (row.records().isEmpty()) { missing++; incompleteUsage++; continue; }
            if (row.records().size() > 1 || row.issues().contains("REUSED_RUN_ID") || row.issues().contains("REUSED_TRACE_IDENTITY")) { ambiguous++; incompleteUsage++; continue; }
            if (row.acceptedBundle()) { accepted++; if (row.comparable()) comparableAccepted++; } cases += row.acceptedCases();
            var record = row.records().getFirst(); var usage = record.usage();
            if (record.status() == Status.FAILED || record.status() == Status.CANCELLED || record.status() == Status.UNKNOWN_REMOTE_OUTCOME) failed++;
            if (!usage.complete() || record.status() == Status.PENDING) incompleteUsage++;
            input = Math.addExact(input, usage.inputTokens()); output = Math.addExact(output, usage.outputTokens()); calls = Math.addExact(calls, usage.reservedCalls());
            elapsed = Math.addExact(elapsed, record.elapsedMillis()); tools = Math.addExact(tools, usage.toolCalls()); executions = Math.addExact(executions, usage.caseExecutions());
        }
        return new ArmMetrics(rows.size(), comparable, accepted, comparableAccepted, cases, failed, missing, ambiguous, incompleteUsage, input, output, calls, elapsed, tools, executions);
    }
    private static Pair pair(List<Row> rows, Arm left, Arm right) {
        int compared = 0, leftOnly = 0, rightOnly = 0, both = 0, neither = 0, unavailable = 0, leftCases = 0, rightCases = 0, equalCases = 0;
        Map<String, Row> rightRows = new HashMap<>();
        for (Row row : rows) if (row.slot().arm() == right) rightRows.put(row.slot().workloadId() + ":" + row.slot().repetition(), row);
        for (Row a : rows) if (a.slot().arm() == left) {
            var b = rightRows.get(a.slot().workloadId() + ":" + a.slot().repetition());
            if (!a.comparable() || !b.comparable()) { unavailable++; continue; } compared++;
            if (a.acceptedBundle() && b.acceptedBundle()) both++; else if (a.acceptedBundle()) leftOnly++; else if (b.acceptedBundle()) rightOnly++; else neither++;
            if (a.acceptedCases() > b.acceptedCases()) leftCases++; else if (a.acceptedCases() < b.acceptedCases()) rightCases++; else equalCases++;
        }
        return new Pair(left, right, compared, leftOnly, rightOnly, both, neither, unavailable, leftCases, rightCases, equalCases);
    }
}
