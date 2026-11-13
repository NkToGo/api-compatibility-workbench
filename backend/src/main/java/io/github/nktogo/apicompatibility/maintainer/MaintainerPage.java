package io.github.nktogo.apicompatibility.maintainer;

import io.github.nktogo.apicompatibility.compatibility.ComparisonAssessment;
import io.github.nktogo.apicompatibility.compatibility.ExecutionObservation;
import io.github.nktogo.apicompatibility.compatibility.RepairEvaluation;

import java.util.List;

/** Presentation of the safe projection only; raw report details are never passed to this renderer. */
final class MaintainerPage {
    private MaintainerPage() {}

    static String render(RecordedCase.Summary report) {
        var html = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Customer payment methods · API Compatibility Workbench</title>
                <link rel="stylesheet" href="/assets/maintainer.css"></head><body>
                <header><div class="brand">API Compatibility Workbench</div><span class="badge">Local · Read-only</span></header>
                <main><div class="eyebrow">Recorded case / Stripe</div><h1>Keep customer payment methods<br>through an API upgrade.</h1>
                <p class="lead">Compare the original integration, the upgrade and a historical patch against the same five synthetic scenarios.</p>
                <aside class="notice"><strong>Historical, supplied evidence.</strong> Conclusions below are recalculated from this report’s observations.
                Execution and retained artifacts have not been authenticated here. Current repository inputs have not been checked.
                This view cannot approve a release.</aside>
                <section aria-label="Report identity" class="metadata">
                """);
        html.append("<div><span>Recorded</span><time datetime=\"").append(escape(report.recordedAt())).append("\">")
                .append(escape(report.recordedAt())).append("</time></div>")
                .append("<div><span>API transition</span>2019-12-03 → 2020-08-27</div>")
                .append("<div><span>Report SHA-256</span><code>").append(report.reportSha256()).append("</code></div></section>")
                .append("<section aria-label=\"Recorded comparisons\" class=\"cards\">");
        card(html, "Unrepaired upgrade", report.upgrade());
        card(html, "Historical reference patch", report.historicalPatch());
        html.append("</section><section class=\"panel\"><h2>What did the historical patch change?</h2><p>")
                .append(report.repair().count(RepairEvaluation.Outcome.FIXED)).append(" fixed · ")
                .append(report.repair().count(RepairEvaluation.Outcome.STILL_FAILING)).append(" still failing · ")
                .append(report.repair().count(RepairEvaluation.Outcome.NEW_FAILURE)).append(" new failures · ")
                .append(report.repair().count(RepairEvaluation.Outcome.STILL_PASSING)).append(" still passing · ")
                .append(report.repair().count(RepairEvaluation.Outcome.UNRESOLVED)).append(" unresolved</p><p class=\"muted\">Judgment: ")
                .append(escape(label(report.repair().conclusion().name()))).append(". This is a historical reference, not a proposed release.</p></section>")
                .append("<section class=\"panel\"><h2>Scenario coverage</h2><p class=\"muted\">Each cell requires one completed assertion with the declared input identity. Missing, duplicate and stale records stay unresolved.</p>")
                .append("<div class=\"table-scroll\"><table><caption>Recorded assertion outcomes for all declared scenarios</caption><thead><tr><th scope=\"col\">Behavior</th><th scope=\"col\">Original</th><th scope=\"col\">Upgrade</th><th scope=\"col\">Historical patch</th></tr></thead><tbody>");
        for (String scenario : RecordedCase.SCENARIOS) {
            html.append("<tr><th scope=\"row\">").append(title(scenario)).append("</th>");
            for (var variant : report.variants()) {
                String state = state(variant, scenario);
                html.append("<td><span class=\"status ").append(state.toLowerCase(java.util.Locale.ROOT))
                        .append("\">").append(label(state)).append("</span></td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></div></section><section class=\"panel\"><h2>Inspect the recorded evidence</h2>")
                .append("<p class=\"muted\">Observation metadata only. Customer payloads, source files and raw tool output are excluded.</p>");
        for (String scenario : RecordedCase.SCENARIOS) {
            html.append("<details><summary>").append(title(scenario)).append("</summary>");
            for (var variant : report.variants()) {
                var values = variant.observations().stream().filter(o -> o.scenarioId().equals(scenario)).toList();
                html.append("<h3>").append(variantTitle(variant.id())).append("</h3><p>")
                        .append(values.size()).append(" recorded observation(s). Input identities are checked against this variant’s declaration.</p><pre>")
                        .append(escape(RecordedCase.JSON.toJson(values))).append("</pre>");
            }
            html.append("</details>");
        }
        html.append("</section><section class=\"panel\"><h2>Advanced details</h2><details><summary>Blocking comparison issues</summary>");
        issues(html, "Unrepaired upgrade", report.upgrade());
        issues(html, "Historical reference patch", report.historicalPatch());
        html.append("<h3>Repair boundary issues</h3><pre>").append(escape(RecordedCase.JSON.toJson(report.repair().boundaryIssues())))
                .append("</pre></details><details><summary>Declared input identities</summary>");
        for (var variant : report.variants()) html.append("<h3>").append(variantTitle(variant.id()))
                .append("</h3><pre>").append(escape(RecordedCase.JSON.toJson(variant.inputs()))).append("</pre>");
        html.append("</details><p><a href=\"/api/report\">Open the projected JSON summary</a></p></section>")
                .append("<footer><strong>Scope matters.</strong> Synthetic, extracted consumer methods only. No live Stripe calls, full application assessment, automatic repair, deployment or migration approval.")
                .append(" The report was loaded once at startup; reload the server to select a different snapshot. A digest identifies bytes, not their trustworthiness.</footer></main></body></html>");
        return html.toString();
    }

    private static void card(StringBuilder html, String title, ComparisonAssessment comparison) {
        html.append("<article class=\"card\"><h2>").append(title).append("</h2><div class=\"metric\">")
                .append(comparison.regressedScenarioIds().size()).append("<span> / 5</span></div><p>recorded regressions</p><strong>")
                .append(escape(label(comparison.conclusion().name()))).append("</strong><p class=\"muted\">")
                .append(comparison.issues().size()).append(" blocking issue(s). Passing checks apply only to the declared scenarios.</p></article>");
    }
    private static void issues(StringBuilder html, String title, ComparisonAssessment comparison) {
        html.append("<h3>").append(title).append("</h3><pre>").append(escape(RecordedCase.JSON.toJson(comparison.issues()))).append("</pre>");
    }
    private static String state(RecordedCase.Variant variant, String scenario) {
        List<ExecutionObservation> values = variant.observations().stream().filter(o -> o.scenarioId().equals(scenario)).toList();
        if (values.size() != 1) return "UNRESOLVED";
        var value = values.getFirst();
        if (!value.inputs().equals(variant.inputs()) || value.disposition() != ExecutionObservation.Disposition.COMPLETED
                || value.assertionOutcome() == ExecutionObservation.AssertionOutcome.NOT_EVALUATED) return "UNRESOLVED";
        return value.assertionOutcome().name();
    }
    private static String variantTitle(String id) {
        return switch (id) { case "baseline" -> "Original"; case "target" -> "Upgrade"; default -> "Historical patch"; };
    }
    private static String title(String scenario) {
        return switch (scenario) {
            case "modern-method-survives-omission" -> "Keep a modern payment method";
            case "empty-account-survives-omission" -> "Handle an empty account";
            case "legacy-method-not-lost" -> "Keep a saved legacy card";
            case "present-legacy-collection-control" -> "Handle a returned legacy collection";
            case "confirmed-removed-legacy-method" -> "Confirm removal before deleting a card";
            default -> throw new IllegalArgumentException("Unsupported scenario");
        };
    }
    private static String label(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
