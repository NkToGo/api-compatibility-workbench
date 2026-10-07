package io.github.nktogo.apicompatibility.evaluation;

import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Supplied operational facts and separately bound review judgments; neither is authenticated here. */
public record CoordinationTrial(Slot slot, String runId, ContentDigest definitionIdentity, ContentDigest implementationIdentity,
                                String model, Status status, ContentDigest traceIdentity, Usage usage, long elapsedMillis,
                                List<CaseOutput> cases) {
    public enum Status { COMPLETED, FAILED, CANCELLED, PENDING, UNKNOWN_REMOTE_OUTCOME }
    public enum Response { ANSWERED, ABSTAINED, NOT_PROVIDED }
    public enum Judgment { SATISFIED, NOT_SATISFIED, UNRESOLVED }
    public record Usage(long reservedCalls, long registeredCalls, long completedResponses, long notDispatchedSlots,
                        long unresolvedUsageSlots, long inputTokens, long outputTokens, long toolCalls, long caseExecutions) {
        public Usage {
            require(reservedCalls >= 0 && registeredCalls >= 0 && completedResponses >= 0 && notDispatchedSlots >= 0
                    && unresolvedUsageSlots >= 0 && inputTokens >= 0 && outputTokens >= 0 && toolCalls >= 0 && caseExecutions >= 0,
                    "Negative measurement");
            require(completedResponses <= registeredCalls && registeredCalls <= reservedCalls, "Invalid call inventory");
            require(accounted(completedResponses, notDispatchedSlots, unresolvedUsageSlots) <= reservedCalls, "Usage exceeds reserved inventory");
            require(completedResponses > 0 || inputTokens == 0 && outputTokens == 0, "Tokens without a completed response");
        }
        public boolean complete() { return unresolvedUsageSlots == 0 && accounted(completedResponses, notDispatchedSlots, 0) == reservedCalls; }
        private static long accounted(long a, long b, long c) {
            try { return Math.addExact(Math.addExact(a, b), c); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("Usage inventory overflow", e); }
        }
    }
    public record Review(ContentDigest outputIdentity, ContentDigest reviewIdentity, boolean claimAuditComplete,
                         int materialUnsupportedClaims, Integer materialUnresolvedClaims, Map<String, Judgment> judgments) {
        /** Legacy metadata did not record unresolved claims; absence is not a declared zero. */
        public Review(ContentDigest outputIdentity, ContentDigest reviewIdentity, boolean claimAuditComplete,
                      int materialUnsupportedClaims, Map<String, Judgment> judgments) {
            this(outputIdentity, reviewIdentity, claimAuditComplete, materialUnsupportedClaims, null, judgments);
        }
        public Review {
            Objects.requireNonNull(outputIdentity); Objects.requireNonNull(reviewIdentity);
            require(materialUnsupportedClaims >= 0 && materialUnsupportedClaims <= 100000, "Invalid claim review count");
            require(materialUnresolvedClaims == null || materialUnresolvedClaims >= 0 && materialUnresolvedClaims <= 100000,
                    "Invalid unresolved claim review count");
            var values = new TreeMap<String, Judgment>(judgments); values.keySet().forEach(CoordinationExperiment::id);
            require(values.size() <= 32 && !values.containsValue(null), "Invalid criterion review"); judgments = Collections.unmodifiableMap(values);
        }
    }
    public record CaseOutput(String caseId, ContentDigest inputIdentity, Response response, ContentDigest outputIdentity, Review review) {
        public CaseOutput {
            id(caseId); Objects.requireNonNull(inputIdentity); Objects.requireNonNull(response);
            require(response == Response.NOT_PROVIDED ? outputIdentity == null && review == null : outputIdentity != null, "Invalid answer identity");
        }
    }
    public CoordinationTrial {
        Objects.requireNonNull(slot); id(runId); Objects.requireNonNull(definitionIdentity); Objects.requireNonNull(implementationIdentity);
        if (model != null) id(model); Objects.requireNonNull(status); Objects.requireNonNull(usage);
        require(status == Status.PENDING || traceIdentity != null, "Missing terminal trace identity");
        require(elapsedMillis >= 0, "Negative elapsed time"); cases = List.copyOf(cases); require(cases.size() <= 64, "Too many case outputs");
    }
}
