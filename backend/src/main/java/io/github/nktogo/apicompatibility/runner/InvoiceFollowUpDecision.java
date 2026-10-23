package io.github.nktogo.apicompatibility.runner;

/** Fixed fresh role protocols; a question never creates a participant or grants authority. */
final class InvoiceFollowUpDecision {
    static final String RECONCILIATION = "invoice-fresh-reconciliation-v1", WEBHOOK = "invoice-fresh-webhook-v1";
    private InvoiceFollowUpDecision() {}
    static boolean accepts(String protocol) { return RECONCILIATION.equals(protocol) || WEBHOOK.equals(protocol); }
    static String protocol(String worker) { return worker.equals("invoice-reconciliation") ? RECONCILIATION : checkedWebhook(worker); }
    private static String checkedWebhook(String worker) { InvoiceWorkerDecision.protocol(worker); return WEBHOOK; }
    static String instructions(String protocol) {
        CaseInputs.require(accepts(protocol), "Unknown fresh invoice protocol");
        return """
                Investigate the delegatedQuestion in a fresh role context at delegation depth one.
                questionAnchors are selected untrusted statements and outcomes, not verified facts.
                An anchor's page IDs are not your own reads. Read captured evidence afresh and cite
                only your own read-N text pages. Do not reproduce an earlier dossier as your answer.
                No sibling follow-up history, grading feedback or recursive delegation is available.
                """ + InvoiceWorkerDecision.instructions(protocol.equals(RECONCILIATION) ? InvoiceWorkerDecision.RECONCILIATION : InvoiceWorkerDecision.WEBHOOK)
                .replace("No sibling messages, claims or grading feedback are supplied.", "Only the delegated question and its explicitly selected anchors are supplied from earlier stages.");
    }
}
