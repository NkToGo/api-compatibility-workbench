package io.github.nktogo.apicompatibility.evaluation;

import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.*;
import java.security.*;
import java.util.*;

/** Frozen comparison conditions. Content identities do not authenticate their referenced artifacts. */
public final class CoordinationExperiment {
    private CoordinationExperiment() {}
    public enum Arm { DETERMINISTIC, SINGLE_AGENT, INDEPENDENT_WORKERS, COORDINATED_TEAM }
    public enum ExpectedResponse { ANSWER, ABSTAIN }
    public enum Origin { SYNTHETIC_EXAMPLE, SUPPLIED_RECORDS }

    public record Limits(int modelCalls, long elapsedMillis, int inputBytesPerCall, int outputTokensPerCall) {
        public Limits {
            require(modelCalls >= 1 && modelCalls <= 500 && elapsedMillis >= 1 && elapsedMillis <= 86400000
                    && inputBytesPerCall >= 1 && inputBytesPerCall <= 64000 && outputTokensPerCall >= 16
                    && outputTokensPerCall <= 4096, "Invalid comparison limits");
        }
    }
    public record CaseTask(String caseId, ContentDigest inputIdentity, ExpectedResponse expectedResponse, List<String> criteria) {
        public CaseTask {
            id(caseId); Objects.requireNonNull(inputIdentity); Objects.requireNonNull(expectedResponse);
            criteria = ids(criteria, 32); require(!criteria.isEmpty(), "Missing question criteria");
        }
    }
    public record Workload(String workloadId, List<CaseTask> cases) {
        public Workload {
            id(workloadId); cases = List.copyOf(cases);
            require(!cases.isEmpty() && cases.size() <= 32 && cases.stream().map(CaseTask::caseId).distinct().count() == cases.size(), "Invalid workload membership");
        }
    }
    public record Definition(String experimentId, Origin origin, String model, ContentDigest rubricIdentity,
                             Map<Arm, ContentDigest> implementations, List<Workload> workloads, int repetitions, Limits limits) {
        public Definition {
            id(experimentId); Objects.requireNonNull(origin); id(model); Objects.requireNonNull(rubricIdentity); Objects.requireNonNull(limits);
            var versions = new EnumMap<Arm, ContentDigest>(Arm.class); versions.putAll(implementations);
            require(versions.keySet().equals(EnumSet.allOf(Arm.class)) && !versions.containsValue(null), "Declare every arm implementation");
            implementations = Collections.unmodifiableMap(versions); workloads = List.copyOf(workloads);
            require(repetitions >= 1 && repetitions <= 10 && !workloads.isEmpty() && workloads.size() <= 32
                    && workloads.stream().map(Workload::workloadId).distinct().count() == workloads.size(), "Invalid planned denominator");
        }
        public int plannedTrials() { return Math.multiplyExact(Math.multiplyExact(workloads.size(), repetitions), Arm.values().length); }
    }
    public record Slot(String workloadId, int repetition, Arm arm) {
        public Slot { id(workloadId); require(repetition >= 1 && repetition <= 10, "Invalid repetition"); Objects.requireNonNull(arm); }
    }

    /** Stable, length-prefixed encoding of all declared conditions, independent of JSON property order. */
    public static ContentDigest identity(Definition definition) {
        Objects.requireNonNull(definition);
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeUTF("coordination-comparison-v1"); out.writeUTF(definition.experimentId()); out.writeUTF(definition.origin().name());
            out.writeUTF(definition.model()); out.writeUTF(definition.rubricIdentity().sha256());
            for (Arm arm : Arm.values()) { out.writeUTF(arm.name()); out.writeUTF(definition.implementations().get(arm).sha256()); }
            out.writeInt(definition.repetitions()); var limits = definition.limits(); out.writeInt(limits.modelCalls());
            out.writeLong(limits.elapsedMillis()); out.writeInt(limits.inputBytesPerCall()); out.writeInt(limits.outputTokensPerCall());
            out.writeInt(definition.workloads().size());
            for (Workload workload : definition.workloads()) {
                out.writeUTF(workload.workloadId()); out.writeInt(workload.cases().size());
                for (CaseTask task : workload.cases()) {
                    out.writeUTF(task.caseId()); out.writeUTF(task.inputIdentity().sha256()); out.writeUTF(task.expectedResponse().name());
                    out.writeInt(task.criteria().size()); for (String criterion : task.criteria()) out.writeUTF(criterion);
                }
            }
            return new ContentDigest(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot encode comparison definition", e); }
    }
    static String id(String value) {
        Objects.requireNonNull(value); require(value.matches("[a-z][a-z0-9.-]{0,99}"), "Invalid comparison identifier"); return value;
    }
    static List<String> ids(List<String> values, int maximum) {
        var copy = List.copyOf(values); copy.forEach(CoordinationExperiment::id);
        require(copy.size() <= maximum && new HashSet<>(copy).size() == copy.size(), "Invalid comparison identifier list"); return copy;
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
