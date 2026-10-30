package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure structural disclosure over supplied read metadata; never an execution or answer-quality verdict. */
final class InvoiceReadiness {
    static final int FORMAT_VERSION = 1, MAX_PAGES = 100, MAX_CHARACTERS = 1048576, MAX_OUTPUT_BYTES = 16384;
    static final String POLICY = "invoice-readiness-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    enum Scope { JOINT, WEBHOOK, RECONCILIATION }
    enum CoverageState { UNREAD, PARTIAL, COMPLETE }

    record Subject(String participantId, ContentDigest inputIdentity, Scope scope) {
        Subject {
            CaseInputs.require(participantId != null && participantId.matches("[a-z][a-z0-9-]{0,99}"), "Invalid readiness participant");
            Objects.requireNonNull(inputIdentity); Objects.requireNonNull(scope);
        }
    }
    sealed interface Page permits TextPage, CataloguePage {
        Subject subject();
        String pageId();
    }
    record TextPage(Subject subject, String pageId, String alias, ContentDigest artifactIdentity,
                    int offset, int nextOffset, int totalCharacters) implements Page {
        TextPage {
            Objects.requireNonNull(subject); InvoiceReadiness.pageId(pageId); InvoiceReadiness.alias(alias); Objects.requireNonNull(artifactIdentity);
            CaseInputs.require(offset >= 0 && nextOffset >= offset && nextOffset <= totalCharacters
                    && totalCharacters <= MAX_CHARACTERS && nextOffset - offset <= 4000, "Invalid readiness text range");
        }
    }
    record CataloguePage(Subject subject, String pageId) implements Page {
        CataloguePage { Objects.requireNonNull(subject); InvoiceReadiness.pageId(pageId); }
    }
    record Span(int start, int end) {
        Span { CaseInputs.require(start >= 0 && end >= start && end <= MAX_CHARACTERS, "Invalid readiness span"); }
    }
    record ArtifactCoverage(String alias, CoverageState state, ContentDigest artifactIdentity,
                            Integer totalCharacters, List<Span> coveredRanges, List<Span> missingRanges) {
        ArtifactCoverage { coveredRanges = List.copyOf(coveredRanges); missingRanges = List.copyOf(missingRanges); }
    }
    record EvidenceGroup(int observedArtifacts, int completeArtifacts, String firstCompleteAlias) {}
    record Snapshot(int formatVersion, String policy, String authority, Subject subject,
                    int suppliedReadCount, boolean sourceExecutionCoverageSatisfied,
                    List<ArtifactCoverage> requiredReads, EvidenceGroup businessEvidence, EvidenceGroup orderEvidence,
                    List<String> citableTextPageIds, List<String> nonCitableCataloguePageIds,
                    boolean structuralAccessReady, String qualityReview) {
        Snapshot {
            requiredReads = List.copyOf(requiredReads); citableTextPageIds = List.copyOf(citableTextPageIds);
            nonCitableCataloguePageIds = List.copyOf(nonCitableCataloguePageIds);
        }
        JsonObject toJson() {
            var value = JSON.toJsonTree(this).getAsJsonObject();
            CaseInputs.require(value.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_OUTPUT_BYTES,
                    "Readiness disclosure exceeds byte limit");
            return value;
        }
    }
    private InvoiceReadiness() {}

    static Snapshot inspect(Subject subject, List<? extends Page> suppliedPages, boolean sourceExecutionCoverageSatisfied) {
        Objects.requireNonNull(subject); var pages = List.copyOf(suppliedPages);
        CaseInputs.require(pages.size() <= MAX_PAGES, "Too many readiness reads");
        var ids = new HashSet<String>(); var textIds = new ArrayList<String>(); var catalogueIds = new ArrayList<String>();
        var artifacts = new TreeMap<String, Artifact>();
        for (var page : pages) {
            CaseInputs.require(subject.equals(page.subject()), "Read outside readiness subject");
            CaseInputs.require(ids.add(page.pageId()), "Ambiguous readiness page ID");
            if (page instanceof TextPage text) {
                textIds.add(text.pageId());
                artifacts.computeIfAbsent(text.alias(), ignored -> new Artifact(text)).add(text);
            } else catalogueIds.add(page.pageId());
        }
        Comparator<String> pageOrder = Comparator.comparingInt(id -> Integer.parseInt(id.substring(5)));
        textIds.sort(pageOrder); catalogueIds.sort(pageOrder);
        var coverage = new TreeMap<String, ArtifactCoverage>(); artifacts.forEach((alias, artifact) -> coverage.put(alias, artifact.coverage()));
        var required = required(subject.scope()).stream().map(alias -> coverage.getOrDefault(alias,
                new ArtifactCoverage(alias, CoverageState.UNREAD, null, null, List.of(), List.of()))).toList();
        String role = switch (subject.scope()) { case JOINT -> null; case WEBHOOK -> "webhook"; case RECONCILIATION -> "reconciliation"; };
        var business = group(coverage, "business/", role); var order = group(coverage, "order/", null);
        boolean ready = sourceExecutionCoverageSatisfied && required.stream().allMatch(c -> c.state() == CoverageState.COMPLETE)
                && business.completeArtifacts() > 0 && order.completeArtifacts() > 0;
        return new Snapshot(FORMAT_VERSION, POLICY, "supplied-read-metadata-not-semantic-acceptance", subject, pages.size(),
                sourceExecutionCoverageSatisfied, required, business, order, textIds, catalogueIds, ready, "NOT_PERFORMED");
    }
    private static List<String> required(Scope scope) {
        var aliases = new ArrayList<>(List.of("task", "build-configuration"));
        if (scope != Scope.RECONCILIATION) aliases.add("source/webhook-baseline/InvoiceWebhookConsumer.java");
        if (scope != Scope.WEBHOOK) aliases.add("source/reconciliation-baseline/InvoiceReconciliationConsumer.java");
        return List.copyOf(aliases);
    }
    private static EvidenceGroup group(SortedMap<String, ArtifactCoverage> coverage, String prefix, String role) {
        var observed = coverage.values().stream().filter(c -> c.alias().startsWith(prefix)
                && (role == null || c.alias().endsWith("/" + role))).toList();
        var complete = observed.stream().filter(c -> c.state() == CoverageState.COMPLETE).toList();
        return new EvidenceGroup(observed.size(), complete.size(), complete.isEmpty() ? null : complete.getFirst().alias());
    }
    private static void pageId(String value) {
        CaseInputs.require(value != null && value.matches("read-[1-9][0-9]{0,2}"), "Invalid readiness page ID");
    }
    private static void alias(String value) {
        CaseInputs.require(value != null && value.length() <= 300 && value.matches("[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)*")
                && Arrays.stream(value.split("/")).noneMatch(part -> part.equals(".") || part.equals("..")), "Invalid readiness alias");
    }
    private static final class Artifact {
        private final String alias;
        private final ContentDigest identity;
        private final int total;
        private final List<Span> ranges = new ArrayList<>();
        private Artifact(TextPage page) { alias = page.alias(); identity = page.artifactIdentity(); total = page.totalCharacters(); }
        private void add(TextPage page) {
            CaseInputs.require(identity.equals(page.artifactIdentity()) && total == page.totalCharacters(), "Conflicting readiness artifact metadata");
            ranges.add(new Span(page.offset(), page.nextOffset()));
        }
        private ArtifactCoverage coverage() {
            var merged = new ArrayList<Span>();
            for (var span : ranges.stream().sorted(Comparator.comparingInt(Span::start).thenComparingInt(Span::end)).toList()) {
                if (span.start() == span.end()) continue;
                if (merged.isEmpty() || span.start() > merged.getLast().end()) merged.add(span);
                else { var old = merged.removeLast(); merged.add(new Span(old.start(), Math.max(old.end(), span.end()))); }
            }
            var missing = new ArrayList<Span>(); int end = 0;
            for (var span : merged) { if (span.start() > end) missing.add(new Span(end, span.start())); end = span.end(); }
            if (end < total) missing.add(new Span(end, total));
            return new ArtifactCoverage(alias, missing.isEmpty() ? CoverageState.COMPLETE : CoverageState.PARTIAL,
                    identity, total, merged, missing);
        }
    }
}
