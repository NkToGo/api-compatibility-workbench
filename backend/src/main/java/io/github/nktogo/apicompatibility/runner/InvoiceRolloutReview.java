package io.github.nktogo.apicompatibility.runner;

import io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment.Conclusion;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Records a local maintainer's decision about one exact synthetic comparison. */
public final class InvoiceRolloutReview {
    private final EvidenceStore store;
    private final Path caseRoot;
    private final Path runs;

    public enum Disposition { ACCEPT_NO_REGRESSION, HOLD }
    public enum State { CURRENT, STALE, CURRENT_EVIDENCE_UNAVAILABLE }

    public record Selection(String inputId, String captureId, String baselineRunId, String targetRunId) {
        public Selection {
            new ContentDigest(inputId);
            new ContentDigest(captureId);
            InvoiceRolloutComparison.validateRunId(baselineRunId);
            InvoiceRolloutComparison.validateRunId(targetRunId);
            CaseInputs.require(baselineRunId == null || !baselineRunId.equals(targetRunId), "Select separate attempts");
        }
    }

    public record Preview(int formatVersion, String evidenceIdentity, InvoiceRolloutComparison.ReviewEvidence evidence) {}
    public record Decision(Disposition disposition, Preview reviewed) {}
    public record Retained(String reviewId, Decision decision) {}
    public record Check(State state, Retained historical, Preview current) {}

    public InvoiceRolloutReview(EvidenceStore store, Path caseRoot, Path runs) {
        this.store = store;
        this.caseRoot = caseRoot;
        this.runs = runs;
    }

    public Preview preview(Selection selection) throws IOException {
        var evidence = InvoiceRolloutComparison.reviewEvidence(store, caseRoot, runs, selection.inputId(),
                selection.captureId(), selection.baselineRunId(), selection.targetRunId());
        return new Preview(1, CaseInputs.identity(evidence), evidence);
    }

    public Retained record(Selection selection, String reviewedIdentity, Disposition disposition) throws IOException {
        new ContentDigest(reviewedIdentity);
        var current = preview(selection);
        CaseInputs.require(current.evidenceIdentity().equals(reviewedIdentity), "Reviewed evidence changed");
        var decision = new Decision(disposition, current);
        validate(decision);
        byte[] bytes = CaseInputs.JSON.toJson(decision).getBytes(StandardCharsets.UTF_8);
        return new Retained(store.saveInvoiceRolloutReview(bytes), decision);
    }

    public Retained reopen(String reviewId) throws IOException {
        var stored = InvoiceRenewalFixtures.object(store.readInvoiceRolloutReview(reviewId));
        var decision = CaseInputs.JSON.fromJson(stored, Decision.class);
        CaseInputs.require(stored.equals(CaseInputs.JSON.toJsonTree(decision)), "Invalid stored review fields");
        validate(decision);
        return new Retained(reviewId, decision);
    }

    public Check check(String reviewId, Selection currentSelection) throws IOException {
        var historical = reopen(reviewId);
        Objects.requireNonNull(currentSelection, "currentSelection");
        Preview current;
        try {
            current = preview(currentSelection);
        } catch (IOException | RuntimeException unavailable) {
            return new Check(State.CURRENT_EVIDENCE_UNAVAILABLE, historical, null);
        }
        var state = historical.decision().reviewed().equals(current) ? State.CURRENT : State.STALE;
        return new Check(state, historical, current);
    }

    private static void validate(Decision decision) {
        CaseInputs.require(decision != null && decision.disposition() != null && decision.reviewed() != null,
                "Incomplete review decision");
        var reviewed = decision.reviewed();
        CaseInputs.require(reviewed.formatVersion() == 1 && reviewed.evidence() != null
                && CaseInputs.identity(reviewed.evidence()).equals(reviewed.evidenceIdentity()), "Invalid reviewed evidence identity");
        CaseInputs.require(decision.disposition() == Disposition.HOLD
                || reviewed.evidence().comparison().conclusion() == Conclusion.NO_REGRESSION_OBSERVED,
                "Acceptance requires an observed no-regression result");
    }

    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length >= 8, "Missing review command arguments");
            var selection = new Selection(args[4], args[5], attempt(args[6]), attempt(args[7]));
            var reviews = new InvoiceRolloutReview(new EvidenceStore(Path.of(args[2])), Path.of(args[1]), Path.of(args[3]));
            switch (args[0]) {
                case "preview" -> {
                    CaseInputs.require(args.length == 8, "Unexpected preview arguments");
                    output(reviews.preview(selection));
                }
                case "record" -> {
                    CaseInputs.require(args.length == 10, "Recording requires the reviewed identity and a disposition");
                    output(reviews.record(selection, args[8], Disposition.valueOf(args[9])));
                }
                case "check" -> {
                    CaseInputs.require(args.length == 9, "Checking requires an exact review ID");
                    var checked = reviews.check(args[8], selection);
                    output(checked);
                    if (checked.state() != State.CURRENT) System.exit(3);
                }
                default -> throw new IllegalArgumentException("Unknown review command");
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Invoice review failed. Check the selection, review identity, evidence and disposition.");
            System.exit(2);
        }
    }

    private static String attempt(String id) { return "-".equals(id) ? null : id; }
    private static void output(Object value) { System.out.println(CaseInputs.JSON.toJson(value)); }
}
