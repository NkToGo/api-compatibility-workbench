package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Transient, credential-free inventories of already sealed v2 invoice advice. */
public final class InvoiceReviewInputsV2 {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID;
    private static final Map<Arm, String> STORES = Map.of(Arm.DETERMINISTIC, "invoice-diagnostics-v2", Arm.SINGLE_AGENT, "invoice-single-agent-v2",
            Arm.INDEPENDENT_WORKERS, "invoice-independent-workers-v2", Arm.COORDINATED_TEAM, "invoice-team-comparisons-v2");
    public record Selection(int formatVersion, String inputDirectory, ContentDigest inputIdentity, String contractDirectory, ContentDigest contractIdentity,
                            String rubricArtifact, ContentDigest rubricIdentity, String publicTaskSha256, List<InvoiceReviewInputs.Source> sources) {
        public Selection {
            CaseInputs.require(formatVersion == 2 && InvoiceDecisionV2.taskIdentity().equals(publicTaskSha256), "Unsupported v2 review selection or public task");
            relative(inputDirectory); relative(contractDirectory); relative(rubricArtifact);
            Objects.requireNonNull(inputIdentity); Objects.requireNonNull(contractIdentity); Objects.requireNonNull(rubricIdentity); sources = List.copyOf(sources);
            CaseInputs.require(sources.stream().map(InvoiceReviewInputs.Source::arm).toList().equals(List.of(Arm.values()))
                    && sources.stream().map(s -> s.stateDirectory() + "/" + s.runId()).distinct().count() == 4, "Exactly four ordered distinct v2 arm sources required");
        }
    }
    private final Path evidence;
    public InvoiceReviewInputsV2(Path evidence) {
        this.evidence = evidence.toAbsolutePath().normalize(); safeDirectory(this.evidence);
    }
    /** No publication, dispatch, credential access, chronology inference or semantic judgment. */
    public JsonObject inspect(Selection selection) throws IOException {
        var roots = new ArrayList<>(List.of(selection.inputDirectory(), selection.contractDirectory(), selection.rubricArtifact()));
        for (var source : selection.sources()) roots.addAll(roots(source));
        // Preflight every required directory before constructing retained-only adapter readers.
        for (var source : selection.sources()) for (String root : roots(source)) safeDirectory(path(root));
        var before = inventory(roots);
        InvoiceComparisonInputs.open(path(selection.inputDirectory()), selection.inputIdentity());
        var contract = inventory(List.of(selection.contractDirectory())); var local = new TreeMap<String, String>();
        contract.forEach((name, hash) -> local.put(name.substring(selection.contractDirectory().length() + 1), hash));
        CaseInputs.require(new ContentDigest(CaseInputs.identity(local)).equals(selection.contractIdentity())
                && local.keySet().containsAll(Set.of("README.md", "business-oracle.md", "provenance.json")), "Changed or incomplete protected v2 invoice contract");
        var observed = new TreeMap<String, String>(); var rubric = sourceJson(selection.rubricArtifact(), observed);
        CaseInputs.require(selection.rubricIdentity().sha256().equals(observed.get(selection.rubricArtifact())), "Changed protected v2 invoice rubric");
        InvoiceReviewInputs.validateRubric(rubric);
        // Adapter readers authenticate retained histories. Strictly parse their JSON first as
        // well, so a duplicate key cannot be hidden by an older permissive decoder.
        for (var source : selection.sources()) for (String ref : before.keySet())
            if (roots(source).stream().anyMatch(root -> ref.startsWith(root + "/")) && ref.endsWith(".json")) sourceJson(ref, observed);
        var rows = new JsonArray();
        for (var source : selection.sources()) {
            String adapter = source.stateDirectory() + "/" + STORES.get(source.arm()) + "/" + source.runId();
            var result = sourceJson(adapter + "/result.json", observed);
            CaseInputs.require(source.resultIdentity().sha256().equals(observed.get(adapter + "/result.json")), "Selected v2 result identity changed");
            Path state = path(source.stateDirectory()), inputs = path(selection.inputDirectory());
            JsonObject retained = switch (source.arm()) {
                case DETERMINISTIC -> new InvoiceDiagnosticsV2(state, inputs, selection.inputIdentity()).retained(source.runId());
                case SINGLE_AGENT -> new InvoiceSingleAgentV2(state, inputs, selection.inputIdentity()).retained(source.runId());
                case INDEPENDENT_WORKERS -> new InvoiceIndependentWorkersV2(state, inputs, selection.inputIdentity()).retained(source.runId());
                case COORDINATED_TEAM -> new InvoiceTeamComparisonV2(state, inputs, selection.inputIdentity()).retained(source.runId());
            };
            CaseInputs.require(result.equals(retained) && result.get("formatVersion").getAsInt() == 2
                    && result.get("qualityReview").getAsString().equals("NOT_PERFORMED"), "Changed or previously reviewed v2 result");
            var trial = JSON.fromJson(result.get("trial"), CoordinationTrial.class);
            CaseInputs.require(trial.slot().arm() == source.arm() && trial.runId().equals(source.runId())
                    && trial.status() != CoordinationTrial.Status.PENDING && trial.cases().size() == 1, "Changed v2 trial selection");
            var output = trial.cases().getFirst();
            CaseInputs.require(output.caseId().equals(CASE) && output.inputIdentity().equals(selection.inputIdentity()) && output.review() == null, "Foreign or reviewed v2 output");
            var units = new JsonArray(); unit(units, "TRIAL_OUTCOME", adapter + "/result.json", "/trial", "", "", observed);
            String dossierPath = adapter + "/dossier.json"; JsonObject dossier = null;
            if (Files.exists(path(dossierPath), LinkOption.NOFOLLOW_LINKS)) {
                dossier = sourceJson(dossierPath, observed); var decision = decision(dossier);
                CaseInputs.require(dossier.get("caseId").getAsString().equals(CASE) && dossier.get("bundleIdentity").getAsString().equals(selection.inputIdentity().sha256())
                        && decision.get("response").getAsString().equals(output.response().name()), "Changed v2 dossier scope");
                CaseInputs.require(output.response() == CoordinationTrial.Response.NOT_PROVIDED ? source.arm() == Arm.INDEPENDENT_WORKERS && output.outputIdentity() == null
                        : output.outputIdentity().sha256().equals(observed.get(dossierPath)), "Changed v2 dossier identity");
                sections(units, output.response() == CoordinationTrial.Response.NOT_PROVIDED ? "PARTIAL_AGGREGATE" : "FINAL_DOSSIER", dossierPath, "", dossier, observed);
            } else CaseInputs.require(output.response() == CoordinationTrial.Response.NOT_PROVIDED && output.outputIdentity() == null, "Missing selected v2 dossier");
            if (source.arm() == Arm.INDEPENDENT_WORKERS) {
                var workers = result.getAsJsonArray("workers");
                for (int i = 0; i < workers.size(); i++) {
                    var worker = workers.get(i).getAsJsonObject(); String id = worker.get("workerId").getAsString();
                    unit(units, "WORKER_OUTCOME", adapter + "/result.json", "/workers/" + i, id, "", observed);
                    if (worker.get("status").getAsString().equals("COMPLETED")) {
                        String file = adapter + "/" + id + ".json"; sections(units, "WORKER_DOSSIER", file, "", sourceJson(file, observed), observed);
                    }
                }
            }
            if (source.arm() == Arm.COORDINATED_TEAM) teamUnits(units, adapter, source, dossier, observed);
            String runtime = roots(source).get(1);
            for (String ref : before.keySet()) if (ref.startsWith(runtime + "/") && ref.matches(".*/broker/model-[0-9a-f-]+/result\\.json")) {
                var response = sourceJson(ref, observed);
                if (!response.get("status").getAsString().equals("COMPLETED")) unit(units, "BROKER_OUTCOME", ref, "", "", "", observed);
                if (response.has("outputText")) unit(units, "MODEL_RESPONSE", ref, "/outputText", "", "", observed);
            }
            var row = json(Map.of("position", rows.size() + 1, "source", source, "trial", trial, "units", units));
            row.add("dossierArtifact", dossier == null ? JsonNull.INSTANCE : new JsonPrimitive(dossierPath)); rows.add(row);
        }
        var after = inventory(roots);
        CaseInputs.require(before.equals(after) && observed.entrySet().stream().allMatch(e -> e.getValue().equals(after.get(e.getKey()))), "V2 review sources changed during inspection");
        var result = json(Map.of("formatVersion", 2, "inputIdentity", selection.inputIdentity(), "contractDirectory", selection.contractDirectory(), "contractIdentity", selection.contractIdentity(),
                "rubricArtifact", selection.rubricArtifact(), "rubricIdentity", selection.rubricIdentity(), "publicTaskSha256", selection.publicTaskSha256(), "sourceArtifacts", after, "trials", rows));
        result.addProperty("criteriaTiming", "NOT_INFERRED_FROM_SELECTION"); result.addProperty("campaignAcceptance", "NOT_ESTABLISHED"); result.addProperty("qualityReview", "NOT_PERFORMED"); return result;
    }
    private void teamUnits(JsonArray units, String adapter, InvoiceReviewInputs.Source source, JsonObject dossier, Map<String, String> observed) throws IOException {
        var team = sourceJson(adapter + "/evidence.json", observed); var cited = citations(dossier);
        for (String group : List.of("originalClaims", "freshClaims")) for (int i = 0; i < team.getAsJsonArray(group).size(); i++) {
            String reference = team.getAsJsonArray(group).get(i).getAsJsonObject().get("referenceId").getAsString();
            unit(units, group.equals("originalClaims") ? "ORIGINAL_CLAIM" : "FRESH_CLAIM", adapter + "/evidence.json", "/" + group + "/" + i, reference, cited.contains(reference) ? "CITED" : "NOT_CITED", observed);
        }
        for (String group : List.of("workers", "freshWorkers", "coordinatorReview", "selectedFollowUps", "possibleConflicts")) unit(units, "TEAM_CONTEXT", adapter + "/evidence.json", "/" + group, "", "", observed);
        unit(units, "RECONCILIATION", adapter + "/reconciliation.json", "", "", "", observed);
        for (String root : roots(source).subList(2, 4)) unit(units, "TEAM_SOURCE", root + "/result.json", "", "", "", observed);
    }
    private static JsonObject decision(JsonObject dossier) { return dossier.has("decision") ? dossier.getAsJsonObject("decision") : dossier; }
    private static Set<String> citations(JsonObject dossier) {
        var result = new HashSet<String>(); if (dossier == null) return result; var value = decision(dossier);
        if (value.has("summaryCitations")) value.getAsJsonArray("summaryCitations").forEach(v -> result.add(v.getAsString()));
        if (value.has("findings")) value.getAsJsonArray("findings").forEach(f -> f.getAsJsonObject().getAsJsonArray("citations").forEach(v -> result.add(v.getAsString()))); return result;
    }
    private void sections(JsonArray units, String kind, String artifact, String prefix, JsonObject value, Map<String, String> observed) throws IOException {
        unit(units, kind, artifact, prefix, "", "", observed);
        for (String key : new TreeSet<>(value.keySet())) {
            String pointer = prefix + "/" + key.replace("~", "~0").replace("/", "~1");
            if (key.equals("findings")) for (int i = 0; i < value.getAsJsonArray(key).size(); i++) unit(units, "FINDING", artifact, pointer + "/" + i, "", "", observed);
            else if (key.equals("decision") && value.get(key).isJsonObject()) sections(units, "ADVISORY_DECISION", artifact, pointer, value.getAsJsonObject(key), observed);
            else unit(units, key.equals("summary") ? "SUMMARY" : "DOSSIER_SECTION", artifact, pointer, "", "", observed);
        }
    }
    private void unit(JsonArray units, String kind, String artifact, String pointer, String reference, String relation, Map<String, String> observed) throws IOException {
        var value = InvoiceReviewInputs.pointer(sourceJson(artifact, observed), pointer);
        units.add(json(Map.of("unitId", "unit-" + (units.size() + 1), "kind", kind, "artifact", artifact, "pointer", pointer,
                "valueIdentity", digest(encoded(value)), "referenceId", reference, "directFinalCitation", relation)));
        CaseInputs.require(units.size() <= 2000, "V2 invoice review unit limit exceeded");
    }
    private static List<String> roots(InvoiceReviewInputs.Source source) {
        String state = source.stateDirectory(), id = source.runId();
        String runtime = switch (source.arm()) {
            case DETERMINISTIC -> "invoice-navigation-trials";
            case SINGLE_AGENT, INDEPENDENT_WORKERS -> "invoice-v2-trials";
            case COORDINATED_TEAM -> "invoice-team-reconciliation-v2-trials";
        };
        var roots = new ArrayList<>(List.of(state + "/" + STORES.get(source.arm()) + "/" + id, state + "/" + runtime + "/" + id));
        if (source.arm() == Arm.COORDINATED_TEAM) roots.addAll(List.of(state + "/invoice-team-reconciliation-v2-prefix-runs/" + id, state + "/invoice-team-reconciliation-v2-fresh-runs/" + id));
        return roots;
    }
    private Map<String, String> inventory(List<String> roots) throws IOException {
        var values = new TreeMap<String, String>(); var queue = new ArrayDeque<>(roots); int directories = 0; long bytes = 0;
        while (!queue.isEmpty()) {
            String ref = queue.removeFirst(); Path path = path(ref);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                safeDirectory(path); CaseInputs.require(++directories <= 5000, "V2 review directory limit exceeded");
                try (var children = Files.list(path)) { for (var child : children.toList()) queue.add(ref + "/" + child.getFileName()); }
            } else if (!path.getFileName().toString().endsWith(".lock")) {
                byte[] content = ConsumerTasks.bytes(evidence, ref); bytes += content.length; values.put(ref, CaseInputs.hash(content));
                CaseInputs.require(values.size() <= 5000 && bytes <= 67108864, "V2 review source limit exceeded");
            } else CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Linked v2 review lock");
        }
        return values;
    }
    private JsonObject sourceJson(String ref, Map<String, String> observed) throws IOException {
        path(ref); byte[] bytes = ConsumerTasks.bytes(evidence, ref); String hash = CaseInputs.hash(bytes), old = observed.putIfAbsent(ref, hash);
        CaseInputs.require(old == null || old.equals(hash), "V2 review source changed while reading"); return InvoiceReviewInputs.object(bytes);
    }
    private Path path(String ref) {
        relative(ref); Path path = evidence.resolve(ref).normalize(); safeDirectory(path.getParent());
        CaseInputs.require(!Files.isSymbolicLink(path), "Linked v2 review source"); return path;
    }
    private static void relative(String ref) { CaseInputs.require(ref != null && ref.matches("[A-Za-z0-9_./-]+") && !ref.startsWith("/") && Arrays.stream(ref.split("/", -1)).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")), "Invalid v2 review relative path"); }
    private static void safeDirectory(Path path) {
        ConsumerTasks.directory(path);
        if (path.getFileSystem().provider().getClass().getName().equals("sun.nio.fs.WindowsFileSystemProvider")) return;
        for (Path parent = path.toAbsolutePath().getParent(); parent != null; parent = parent.getParent()) ConsumerTasks.directory(parent);
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 3 && args[0].equals("inspect"), "Usage: inspect REVIEW_EVIDENCE SELECTION");
        Path plan = Path.of(args[2]).toAbsolutePath(); var tree = InvoiceReviewInputs.object(ConsumerTasks.bytes(plan.getParent(), plan.getFileName().toString()));
        var selection = JSON.fromJson(tree, Selection.class); CaseInputs.require(tree.equals(JSON.toJsonTree(selection)), "Unexpected v2 review selection fields");
        System.out.println(encoded(new InvoiceReviewInputsV2(Path.of(args[1])).inspect(selection)));
    }
}
