package io.github.nktogo.apicompatibility.runner;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.AssertionOutcome;
import static io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.Disposition;

/** Offline presentation of the curated runner's recorded evidence, without scripts or remote assets. */
final class CaseReportView {
    private CaseReportView() {}

    private static final List<String> VARIANTS = List.of("baseline", "target", "upstream-reference");
    private static final Map<String, String> LABELS = Map.of(
            "baseline", "Baseline", "target", "Upgrade", "upstream-reference", "Historical patch");
    private static final Map<String, String> SCENARIOS = Map.of(
            "modern-method-survives-omission", "Keep a modern payment method",
            "empty-account-survives-omission", "Handle an empty account",
            "legacy-method-not-lost", "Keep a saved legacy card",
            "present-legacy-collection-control", "Handle a returned legacy collection",
            "confirmed-removed-legacy-method", "Confirm removal before deleting a card");

    static String render(Instant completedAt, Map<String, ComparisonDefinition> definitions,
                         Map<String, List<ExecutionObservation>> observations,
                         Map<String, List<JsonObject>> details, Map<String, JsonObject> expectations) throws IOException {
        var upgrade = Objects.requireNonNull(definitions.get("target"));
        var reference = Objects.requireNonNull(definitions.get("upstream-reference"));
        if (!upgrade.baseline().equals(reference.baseline())
                || !upgrade.requiredScenarioIds().equals(reference.requiredScenarioIds())) {
            throw new IllegalArgumentException("Report comparisons must share baseline and scenario selection");
        }
        var scenarios = upgrade.requiredScenarioIds();
        var snapshots = Map.of("baseline", upgrade.baseline(), "target", upgrade.target(), "upstream-reference", reference.target());
        var assessments = new LinkedHashMap<String, ComparisonAssessment>();
        for (String variant : List.of("target", "upstream-reference")) {
            // Derive the displayed conclusion from observations, never from a claimed result label.
            assessments.put(variant, CompatibilityComparison.compare(definitions.get(variant),
                    observations.getOrDefault("baseline", List.of()), observations.getOrDefault(variant, List.of())));
        }
        String css;
        try (var stream = CaseReportView.class.getResourceAsStream("/report/report.css")) {
            if (stream == null) throw new IOException("Missing report stylesheet");
            // HTML parsing normalizes line endings before CSP hashes are checked.
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        }
        String policy = "default-src 'none'; style-src 'sha256-" + styleHash(css) + "'; base-uri 'none'; form-action 'none'";
        var html = new StringBuilder("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(escape(policy)).append("\">")
                .append("<title>Stripe migration report | API Compatibility Workbench</title><style>").append(css)
                .append("</style></head><body><a class=\"skip\" href=\"#main\">Skip to results</a>")
                .append("<header class=\"topbar\"><span class=\"brand\">API Compatibility Workbench</span><span>Recorded case report</span></header>")
                .append("<main id=\"main\"><div class=\"eyebrow\">Historical case / Stripe</div><h1>Will saved payment methods survive the upgrade?</h1>")
                .append("<p class=\"lead\">Compare the original consumer, its SDK upgrade and the historical patch against the same expected behavior.</p>")
                .append("<p class=\"recorded\">Recorded <time>").append(escape(completedAt.toString())).append("</time> · ")
                .append(scenarios.size()).append(" declared scenarios · Offline fixture execution</p>")
                .append("<nav aria-label=\"Report sections\"><a href=\"#comparisons\">Comparisons</a><a href=\"#coverage\">Scenario coverage</a>")
                .append("<a href=\"#evidence\">Evidence</a><a href=\"#limits\">Scope &amp; limits</a></nav>")
                .append("<section id=\"comparisons\" aria-labelledby=\"comparison-title\"><h2 id=\"comparison-title\">Two changes. Separate conclusions.</h2><div class=\"comparisons\">");
        for (var entry : assessments.entrySet()) {
            String variant = entry.getKey();
            var assessment = entry.getValue();
            html.append("<article class=\"card\"><p class=\"eyebrow\">").append(LABELS.get(variant)).append(" vs baseline</p>")
                    .append("<h3>").append(conclusion(assessment)).append("</h3><p>")
                    .append(variant.equals("target") ? "Original consumer with the newer Stripe SDK." : "Upstream reference changes with the newer Stripe SDK.")
                    .append("</p><p class=\"count\"><strong>").append(assessment.regressedScenarioIds().size())
                    .append("</strong> observed regressions / ").append(scenarios.size()).append(" scenarios</p>");
            if (assessment.conclusion() == ComparisonAssessment.Conclusion.UNRESOLVED) {
                html.append("<p class=\"notice\">Incomplete or conflicting evidence prevents a complete conclusion. Any demonstrated regressions remain listed.</p>");
            } else if (assessment.conclusion() == ComparisonAssessment.Conclusion.NO_REGRESSION_OBSERVED) {
                html.append("<p>All declared checks passed on both sides. This is limited coverage, not a release approval.</p>");
            } else {
                html.append("<p>All declared checks were evaluated and the baseline passed. The changed version failed the checks listed below.</p>");
            }
            if (!assessment.regressedScenarioIds().isEmpty()) {
                html.append("<ul>");
                for (String id : assessment.regressedScenarioIds()) html.append("<li>").append(escape(title(id))).append("</li>");
                html.append("</ul>");
            }
            if (!assessment.issues().isEmpty()) {
                html.append("<details open><summary>Unresolved issues (").append(assessment.issues().size()).append(")</summary><ul class=\"issues\">");
                for (var issue : assessment.issues()) html.append("<li><strong>").append(escape(issue.reason().name()))
                        .append("</strong>: ").append(escape(issue.detail())).append("</li>");
                html.append("</ul></details>");
            }
            html.append("</article>");
        }
        html.append("</div><p class=\"notice\">A historical patch is a comparison point, not a verified repair. These results do not authorize a deployment.</p></section>")
                .append("<section id=\"coverage\" aria-labelledby=\"coverage-title\"><h2 id=\"coverage-title\">Scenario coverage</h2>")
                .append("<p>Passed and failed refer to behavioral assertions. Unresolved means an assertion could not be used. Select a scenario for its evidence.</p>")
                .append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Scenario comparison table\"><table>")
                .append("<caption>Recorded outcomes for each declared scenario</caption><thead><tr><th scope=\"col\">Expected behavior</th>");
        for (String variant : VARIANTS) html.append("<th scope=\"col\">").append(LABELS.get(variant)).append("<small>API ")
                .append(escape(snapshots.get(variant).providerVersion())).append("</small></th>");
        html.append("</tr></thead><tbody>");
        for (int i = 0; i < scenarios.size(); i++) {
            String id = scenarios.get(i);
            html.append("<tr><th scope=\"row\"><a href=\"#scenario-").append(i).append("\">").append(escape(title(id))).append("</a></th>");
            for (String variant : VARIANTS) html.append("<td>").append(badge(outcome(id, snapshots.get(variant), observations.getOrDefault(variant, List.of())))).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table></div></section><section id=\"evidence\" aria-labelledby=\"evidence-title\"><h2 id=\"evidence-title\">Inspect the evidence</h2>")
                .append("<p>Expand a scenario to compare expected behavior, recorded effects and ordered request events. Identifiers are synthetic.</p>");
        for (int i = 0; i < scenarios.size(); i++) {
            String id = scenarios.get(i);
            html.append("<details class=\"scenario\" id=\"scenario-").append(i).append("\"><summary>").append(escape(title(id))).append("</summary><div class=\"scenario-body\">")
                    .append("<p class=\"mono\">").append(escape(id)).append("</p><h3>Expected behavior</h3>")
                    .append("<p>The consumer must complete, return and retain the expected methods, and establish absence before deactivating any method.</p>");
            if (expectations.containsKey(id)) html.append(json(expectations.get(id)));
            else html.append("<p class=\"notice\">Expected-behavior details were not recorded in this view.</p>");
            html.append("<div class=\"variant-evidence\">");
            for (String variant : VARIANTS) {
                var result = outcome(id, snapshots.get(variant), observations.getOrDefault(variant, List.of()));
                var matching = details.getOrDefault(variant, List.of()).stream()
                        .filter(d -> d.has("scenarioId") && id.equals(d.get("scenarioId").getAsString())).toList();
                html.append("<article><h3>").append(LABELS.get(variant)).append("</h3>").append(badge(result));
                if (matching.size() != 1) html.append("<p>Supporting details are missing or ambiguous.</p>");
                else {
                    var detail = matching.getFirst();
                    html.append("<p>").append(escape(explanation(detail, result))).append("</p><details><summary>Recorded output &amp; events</summary>")
                            .append(json(detail)).append("</details>");
                }
                html.append("</article>");
            }
            html.append("</div></div></details>");
        }
        html.append("<details class=\"technical\"><summary>Exact inputs &amp; assessment records</summary><p>These identities describe the recorded run. ")
                .append("They have not been compared with your current checkout. Changed inputs require a new run. Hashes are not signatures.</p>")
                .append(json(assessments)).append("<h3>Execution observations</h3>").append(json(observations)).append("</details></section>")
                .append("<section id=\"limits\" class=\"limits\" aria-labelledby=\"limits-title\"><h2 id=\"limits-title\">What this report establishes</h2>")
                .append("<p>Preserved Kill Bill consumer methods execute with real historical Stripe SDKs, synthetic provider responses and in-memory service doubles.</p>")
                .append("<ul><li>Baseline: Stripe Java 17.4.0 / API 2019-12-03. Changed variants: Stripe Java 20.33.0 / API 2020-08-27.</li>")
                .append("<li>No live Stripe account or full Kill Bill installation was tested. Database effects, transactions and unlisted scenarios are outside coverage.</li>")
                .append("<li>No validated repair is included. A passing subset does not establish overall compatibility.</li>")
                .append("<li>This is a recorded, unsigned local artifact. It does not monitor current code, verify retained file integrity, or prove who produced the evidence.</li></ul></section>")
                .append("<footer>Case: ").append(escape(upgrade.baseline().caseId()))
                .append(" · Generated from recorded execution · No external assets or scripts</footer></main></body></html>\n");
        return html.toString();
    }

    private enum Outcome { PASSED, FAILED, UNRESOLVED }

    private static Outcome outcome(String id, InputSnapshot snapshot, List<ExecutionObservation> observations) {
        var matching = observations.stream().filter(o -> o.scenarioId().equals(id)).toList();
        if (matching.size() != 1) return Outcome.UNRESOLVED;
        var observation = matching.getFirst();
        if (!snapshot.equals(observation.inputs()) || observation.disposition() != Disposition.COMPLETED
                || observation.assertionOutcome() == AssertionOutcome.NOT_EVALUATED) return Outcome.UNRESOLVED;
        return observation.assertionOutcome() == AssertionOutcome.PASSED ? Outcome.PASSED : Outcome.FAILED;
    }

    private static String conclusion(ComparisonAssessment assessment) {
        return switch (assessment.conclusion()) {
            case REGRESSION_OBSERVED -> "Regression observed";
            case NO_REGRESSION_OBSERVED -> "No regression observed";
            case UNRESOLVED -> "Unresolved";
        };
    }

    private static String badge(Outcome outcome) {
        return "<span class=\"badge " + outcome.name().toLowerCase(Locale.ROOT) + "\">"
                + switch (outcome) { case PASSED -> "Passed"; case FAILED -> "Failed"; case UNRESOLVED -> "Unresolved"; } + "</span>";
    }

    private static String explanation(JsonObject detail, Outcome outcome) {
        if (outcome == Outcome.UNRESOLVED) return "No usable assertion result. Inspect the comparison issues and recorded output for the reason.";
        if (outcome == Outcome.PASSED) return "Recorded behavior meets this scenario's expectations.";
        if (detail.has("consumerException")) return "Consumer execution stopped with " + detail.get("consumerException").getAsString() + ".";
        if (detail.has("unsafeDeactivationIds") && !detail.getAsJsonArray("unsafeDeactivationIds").isEmpty())
            return "Payment methods were deactivated without recorded absence evidence: " + detail.get("unsafeDeactivationIds") + ".";
        return "Recorded behavior differs from the expected behavior. Compare the method identifiers and events below.";
    }

    private static String title(String id) { return SCENARIOS.getOrDefault(id, id); }
    private static String json(Object value) { return "<pre>" + escape(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value)) + "</pre>"; }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String styleHash(String css) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(css.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
