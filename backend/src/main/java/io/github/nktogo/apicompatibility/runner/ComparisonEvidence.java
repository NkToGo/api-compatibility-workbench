package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Host-selected input formats; neither participant arguments nor stored reads select a backend. */
interface ComparisonEvidence {
    String INVOICE_TOOLS = "invoice-describe-catalog-40-text-4000-utf16-read-only-v1";
    String INVOICE_NAVIGATION_TOOLS = "invoice-describe-catalog-prefix-300-40-32768-text-4000-utf16-read-only-v1";
    record PageRequest(String kind, String caseId, String alias, int offset, int limit) {}
    Map<String, ContentDigest> identities(String bundle) throws IOException;
    String receiptHash(String bundle) throws IOException;
    byte[] read(String bundle, String caseId, String alias) throws IOException;
    default JsonObject page(String bundle, PageRequest request) { throw new IllegalArgumentException("Paged tools unavailable for this input format"); }
    default void validatePolicy(CoordinationRuntime.Policy policy) {}
    default void validateRetainedPolicy(CoordinationRuntime.Policy policy) {}
    default void validateActivePolicy(CoordinationRuntime.Policy policy) {}

    static ComparisonEvidence legacy(Path state) throws IOException {
        var inputs = new CoordinationInputs(state);
        return new ComparisonEvidence() {
            public Map<String, ContentDigest> identities(String bundle) throws IOException { return inputs.identities(bundle); }
            public String receiptHash(String bundle) throws IOException { return inputs.receiptHash(bundle); }
            public byte[] read(String bundle, String caseId, String alias) throws IOException { return inputs.read(bundle, caseId, alias); }
        };
    }

    static ComparisonEvidence invoice(Path directory, ContentDigest expected) throws IOException {
        return invoice(directory, expected, false);
    }
    static ComparisonEvidence invoice(Path directory, ContentDigest expected, boolean investigation) throws IOException {
        return invoice(directory, expected, investigation, false);
    }
    static ComparisonEvidence invoiceNavigation(Path directory, ContentDigest expected) throws IOException {
        return invoice(directory, expected, false, true);
    }
    private static ComparisonEvidence invoice(Path directory, ContentDigest expected, boolean investigation, boolean navigation) throws IOException {
        var inputs = InvoiceComparisonInputs.open(directory, expected);
        byte[] receipt = ConsumerTasks.bytes(directory, "receipt.json");
        CaseInputs.require(CaseInputs.object(receipt).equals(CaseInputs.JSON.toJsonTree(Map.of("manifestSha256", expected.sha256()))), "Changed invoice input receipt");
        String receiptHash = CaseInputs.hash(receipt);
        ContentDigest currentAdapter = navigation ? new ContentDigest(RepairVerification.harness()) : null;
        return new ComparisonEvidence() {
            private void bundle(String bundle) { CaseInputs.require(expected.sha256().equals(bundle), "Invoice bundle outside registered scope"); }
            public Map<String, ContentDigest> identities(String bundle) { bundle(bundle); return Map.of(InvoiceRenewalFixtures.CASE_ID, expected); }
            public String receiptHash(String bundle) { bundle(bundle); return receiptHash; }
            public byte[] read(String bundle, String caseId, String alias) { throw new IllegalArgumentException("Invoice evidence requires bounded paged tools"); }
            public JsonObject page(String bundle, PageRequest request) {
                bundle(bundle); CaseInputs.require(InvoiceRenewalFixtures.CASE_ID.equals(request.caseId()), "Case outside invoice disclosure");
                return switch (request.kind()) {
                    case "describe" -> {
                        CaseInputs.require("".equals(request.alias()) && request.offset() == 0 && request.limit() == 0, "Description cannot select an artifact or range");
                        yield inputs.describe();
                    }
                    case "catalog" -> {
                        CaseInputs.require("".equals(request.alias()), "Catalog cannot select an artifact");
                        yield inputs.catalog(request.offset(), request.limit());
                    }
                    case "read_text" -> inputs.read(request.alias(), request.offset(), request.limit());
                    case "catalog_prefix" -> {
                        CaseInputs.require(navigation, "Prefix catalogue outside host-selected boundary");
                        yield inputs.catalogPrefix(request.alias(), request.offset(), request.limit());
                    }
                    default -> throw new IllegalArgumentException("Unknown invoice evidence tool");
                };
            }
            public void validatePolicy(CoordinationRuntime.Policy policy) {
                String tools = navigation ? INVOICE_NAVIGATION_TOOLS : INVOICE_TOOLS;
                CaseInputs.require((!navigation || policy.maxReads() <= 100)
                        && policy.toolPolicyIdentity().equals(new ContentDigest(CaseInputs.hash(tools.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                        && policy.participants().stream().allMatch(p -> p.protocols().isEmpty() || (investigation && p.protocols().size() == 1
                        && p.protocols().stream().allMatch(protocol -> protocol.equals(InvoiceInvestigationDecision.PROTOCOL) || InvoiceWorkerDecision.accepts(protocol) || protocol.equals(InvoiceTeamDecision.PROTOCOL) || InvoiceFollowUpDecision.accepts(protocol) || protocol.equals(InvoiceReconciliationDecision.PROTOCOL)))),
                        "Invoice participant protocol outside host-selected boundary");
            }
            public void validateRetainedPolicy(CoordinationRuntime.Policy policy) {
                if (navigation) validatePolicy(policy);
            }
            public void validateActivePolicy(CoordinationRuntime.Policy policy) {
                if (navigation) CaseInputs.require(currentAdapter.equals(policy.adapterIdentity()), "Changed navigation build; register a new trace");
            }
        };
    }
}
