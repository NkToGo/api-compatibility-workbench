package io.github.nktogo.apicompatibility.runner;

/** Cumulative stage ceilings; unused opportunities move forward without renewing the trial. */
final class CoordinationTeamBudget {
    static final String POLICY = "team-cumulative-stages-equal-worker-pools-ten-percent-review-and-reconciliation-forward-carry-v1";
    enum Stage { INVESTIGATION, REVIEW, FOLLOW_UP, RECONCILIATION }
    record Reservations(int investigation, int review, int followUp, int reconciliation) {
        Reservations {
            for (int n : new int[] {investigation, review, followUp, reconciliation})
                CaseInputs.require(n >= 0 && n <= 500, "Invalid team reservation count");
        }
        int total() { return investigation + review + followUp + reconciliation; }
        private int[] counts() { return new int[] {investigation, review, followUp, reconciliation}; }
    }
    record Allocation(int investigation, int review, int followUp, int reconciliation) {
        Allocation {
            for (int n : new int[] {investigation, review, followUp, reconciliation})
                CaseInputs.require(n >= 1 && n <= 500, "Invalid team stage ceiling");
            CaseInputs.require(investigation >= 2 && followUp >= 2 && review >= 1 && reconciliation >= 1
                    && investigation + review + followUp + reconciliation <= 500, "Invalid team allocation");
        }
        int ceiling() { return investigation + review + followUp + reconciliation; }
        int remaining(Stage stage, Reservations reserved) {
            int cumulative = 0, used = 0;
            int[] allocation = {investigation, review, followUp, reconciliation}, counts = reserved.counts();
            for (int i = 0; i < allocation.length; i++) {
                cumulative += allocation[i]; used += counts[i];
                CaseInputs.require(used <= cumulative, "Team stage exceeded its cumulative ceiling");
                CaseInputs.require(i <= stage.ordinal() || counts[i] == 0, "Later team stage already reserved calls");
            }
            cumulative = 0;
            for (int i = 0; i <= stage.ordinal(); i++) cumulative += allocation[i];
            return cumulative - reserved.total();
        }
    }
    private CoordinationTeamBudget() {}
    static Allocation forCeiling(int calls) {
        CaseInputs.require(calls >= 6 && calls <= 500, "Team comparison requires 6 to 500 call opportunities");
        int review = Math.max(1, calls / 10), workers = calls - 2 * review;
        return new Allocation(workers / 2, review, workers - workers / 2, review);
    }
}
