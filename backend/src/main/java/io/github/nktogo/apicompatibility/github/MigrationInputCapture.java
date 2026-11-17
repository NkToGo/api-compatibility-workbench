package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.inventory.MavenPomInventory;
import io.github.nktogo.apicompatibility.runner.EvidenceStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Retained selected POM inputs. Reopening checks consistency, never live access or execution authority. */
public final class MigrationInputCapture {
    public record Summary(String inputId, String bindingIdentity, MigrationConfiguration.State declarationState,
                          GitHubSourceInventory.State inventoryState, int retainedPoms, int retainedPomBytes,
                          boolean executionAuthorized, List<String> limitations) {
        public Summary { limitations = List.copyOf(limitations); }
    }
    public static final class Retained {
        private final Summary summary;
        private final MigrationConfiguration.Result binding;
        private final transient Map<String, byte[]> poms;
        private Retained(String id, MigrationConfiguration.Result binding, Map<String, byte[]> poms) {
            this.binding = binding; this.poms = copy(poms);
            this.summary = new Summary(id, binding.bindingIdentity(), binding.state(), binding.inventory().state(), poms.size(),
                    poms.values().stream().mapToInt(b -> b.length).sum(), false,
                    List.of("SELECTED_POMS_ONLY", "NOT_AN_EXECUTABLE_CONSUMER_BUNDLE", "DECLARATIONS_NOT_RUNTIME_PROOF",
                            "HISTORICAL_ACCESS_OBSERVATION", "NO_EXECUTION_OR_PUBLICATION_GRANT"));
        }
        public Summary summary() { return summary; }
        public MigrationConfiguration.Result binding() { return binding; }
        public Map<String, byte[]> poms() { return copy(poms); }
        @Override public String toString() { return "Retained[" + summary.inputId() + ", source bytes omitted]"; }
    }
    private MigrationInputCapture() {}
    public static final class Prepared {
        private final MigrationConfiguration.Result binding;
        private final transient Map<String, byte[]> artifacts;
        private final String inputId;
        private Prepared(MigrationConfiguration.Result binding, Map<String, byte[]> artifacts) {
            this.binding = binding; this.artifacts = copy(artifacts); this.inputId = EvidenceStore.inputIdentity(this.artifacts);
        }
        public String inputId() { return inputId; }
        public MigrationConfiguration.Result binding() { return binding; }
        public Retained publish(EvidenceStore store) throws IOException {
            require(inputId.equals(store.saveInputs(artifacts))); return reopen(store, inputId);
        }
        @Override public String toString() { return "Prepared[" + inputId + ", source bytes omitted]"; }
    }
    private static Map<String, byte[]> copy(Map<String, byte[]> source) {
        var result = new TreeMap<String, byte[]>(); source.forEach((path, bytes) -> result.put(path, bytes.clone()));
        return Collections.unmodifiableMap(result);
    }
    public static Retained retain(EvidenceStore store, GitHubSourceInventory.Capture capture, MigrationConfiguration.Plan declarations) throws IOException {
        return prepare(capture, declarations).publish(store);
    }
    public static Prepared prepare(GitHubSourceInventory.Capture capture, MigrationConfiguration.Plan declarations) {
        var binding = MigrationConfiguration.bind(capture.inventory(), declarations);
        var artifacts = new TreeMap<String, byte[]>();
        artifacts.put("binding.json", (GitHubJson.JSON.toJson(binding) + "\n").getBytes(StandardCharsets.UTF_8));
        var poms = capture.poms(); var files = capture.inventory().files();
        for (int i = 0; i < files.size(); i++) {
            byte[] bytes = poms.get(files.get(i).path()); if (bytes != null) artifacts.put(name(i), bytes);
        }
        validate(artifacts); // Validate the same schema and byte relationships before publication and on every reopen.
        return new Prepared(binding, artifacts);
    }
    public static Retained reopen(EvidenceStore store, String inputId) throws IOException {
        var artifacts = store.readInputs(inputId); var binding = validate(artifacts); var poms = new TreeMap<String, byte[]>();
        var files = binding.inventory().files();
        for (int i = 0; i < files.size(); i++) if (artifacts.containsKey(name(i))) poms.put(files.get(i).path(), artifacts.get(name(i)));
        return new Retained(inputId, binding, poms);
    }
    private static String name(int index) { return "pom-" + index + ".bin"; }
    private static MigrationConfiguration.Result validate(Map<String, byte[]> artifacts) {
        var report = GitHubJson.parse(Objects.requireNonNull(artifacts.get("binding.json")), 4 * 1024 * 1024);
        var declarations = MigrationConfiguration.parsePlan(obj(report, "declarations"));
        var source = obj(report, "inventory"); var snapshot = obj(source, "repository"); var selected = obj(snapshot, "selection");
        var selection = new GitHubRepositoryConnection.Selection(integer(selected, "formatVersion"), GitHubJson.number(selected, "installationId"),
                GitHubJson.number(selected, "accountId"), GitHubJson.number(selected, "repositoryId"), GitHubJson.text(selected, "owner"),
                GitHubJson.text(selected, "name"), GitHubJson.text(selected, "commitSha"));
        String tree = GitHubJson.text(snapshot, "treeSha"); require(tree.matches("[0-9a-f]{40}"));
        Instant observed = instant(snapshot, "observedAt"), expires = instant(snapshot, "tokenExpiresAt"), completed = instant(source, "completedAt");
        require(integer(snapshot, "formatVersion") == 1 && GitHubJson.number(snapshot, "appId") > 0 && observed.isBefore(expires) && !completed.isBefore(observed));
        require("AUTHENTICATED_GITHUB_METADATA".equals(GitHubJson.text(snapshot, "authority")) && snapshot.get("compatibilityAssessed").isJsonPrimitive()
                && snapshot.getAsJsonPrimitive("compatibilityAssessed").isBoolean() && !snapshot.get("compatibilityAssessed").getAsBoolean());
        var repository = new GitHubRepositoryConnection.Snapshot(1, GitHubJson.number(snapshot, "appId"), selection, tree, observed, expires, "AUTHENTICATED_GITHUB_METADATA", false);
        var selectedPoms = obj(source, "plan"); var plan = new GitHubSourceInventory.Plan(integer(selectedPoms, "formatVersion"), strings(selectedPoms, "pomPaths"));
        require(source.get("files").isJsonArray() && source.getAsJsonArray("files").size() == plan.pomPaths().size());
        var files = new ArrayList<GitHubSourceInventory.FileResult>(); var retainedNames = new HashSet<>(Set.of("binding.json")); int total = 0;
        for (int i = 0; i < plan.pomPaths().size(); i++) {
            var row = GitHubJson.object(source.getAsJsonArray("files").get(i)); String path = GitHubJson.text(row, "path");
            require(path.equals(plan.pomPaths().get(i))); var state = GitHubSourceInventory.FileState.valueOf(GitHubJson.text(row, "state"));
            byte[] bytes = artifacts.get(name(i)); GitHubSourceInventory.FileResult file;
            if (bytes != null) {
                require(bytes.length <= 65536); total += bytes.length; require(total <= GitHubSourceInventory.MAX_TOTAL_BYTES);
                var scan = MavenPomInventory.scan(bytes);
                var expectedState = scan.state() == MavenPomInventory.State.SCANNED ? GitHubSourceInventory.FileState.READ : GitHubSourceInventory.FileState.POM_REJECTED;
                require(state == expectedState); retainedNames.add(name(i));
                file = new GitHubSourceInventory.FileResult(path, state, GitSourceObjects.gitHash("blob", bytes), GitSourceObjects.sha256(bytes), bytes.length, scan);
            } else {
                require(state != GitHubSourceInventory.FileState.READ && state != GitHubSourceInventory.FileState.POM_REJECTED);
                file = new GitHubSourceInventory.FileResult(path, state, null, null, null, null);
            }
            require(GitHubJson.JSON.toJsonTree(file).equals(row)); files.add(file);
        }
        require(artifacts.keySet().equals(retainedNames) && integer(source, "sourceBytes") == total && integer(source, "formatVersion") == 1);
        int requests = integer(source, "sourceRequests"); require(requests >= 0 && requests <= GitHubSourceInventory.MAX_SOURCE_REQUESTS);
        var state = files.stream().allMatch(f -> f.state() == GitHubSourceInventory.FileState.READ)
                ? GitHubSourceInventory.State.COMPLETE_WITHIN_SELECTED_FILES : GitHubSourceInventory.State.INCOMPLETE;
        require(state.name().equals(GitHubJson.text(source, "state")));
        GitHubSourceInventory.Problem problem = null;
        if (!source.get("problem").isJsonNull()) {
            var p = obj(source, "problem"); GitHubRepositoryConnection.Failure failure = null;
            if (!p.get("connectionFailure").isJsonNull()) {
                var f = obj(p, "connectionFailure"); require(f.get("tokenMayRemainActive").isJsonPrimitive() && f.getAsJsonPrimitive("tokenMayRemainActive").isBoolean());
                int status = integer(f, "httpStatus"); require(status == 0 || status >= 100 && status <= 599);
                failure = new GitHubRepositoryConnection.Failure(GitHubRepositoryConnection.Code.valueOf(GitHubJson.text(f, "code")),
                        GitHubRepositoryConnection.Stage.valueOf(GitHubJson.text(f, "stage")), status,
                        f.get("retryNotBefore").isJsonNull() ? null : instant(f, "retryNotBefore"), f.get("tokenMayRemainActive").getAsBoolean());
            }
            var reason = GitHubSourceInventory.Stop.valueOf(GitHubJson.text(p, "reason"));
            require((reason == GitHubSourceInventory.Stop.CONNECTION_FAILURE) == (failure != null) && state == GitHubSourceInventory.State.INCOMPLETE);
            problem = new GitHubSourceInventory.Problem(reason, failure);
        }
        var inventory = new GitHubSourceInventory.Result(1, repository, plan, completed, state, files, requests, total, problem, strings(source, "limitations"));
        require(inventory.limitations().equals(List.of("SELECTED_POMS_ONLY", "SOURCE_BYTES_CAPTURED_IN_MEMORY", "MAVEN_EFFECTIVE_MODEL_NOT_RESOLVED",
                "REQUEST_API_VERSION_NOT_ASSESSED", "WEBHOOK_API_VERSION_NOT_ASSESSED", "ACCOUNT_DEFAULT_VERSION_NOT_ASSESSED", "NO_COMPATIBILITY_ASSESSMENT_OR_EXECUTION")));
        var rebound = MigrationConfiguration.bind(inventory, declarations);
        require(GitHubJson.JSON.toJsonTree(rebound).equals(report)); return rebound;
    }
    private static JsonObject obj(JsonObject parent, String key) { return GitHubJson.object(parent.get(key)); }
    private static int integer(JsonObject parent, String key) { return Math.toIntExact(GitHubJson.number(parent, key)); }
    private static Instant instant(JsonObject parent, String key) { return Instant.parse(GitHubJson.text(parent, key)); }
    private static List<String> strings(JsonObject parent, String key) {
        require(parent.get(key).isJsonArray() && parent.getAsJsonArray(key).size() <= 32); var result = new ArrayList<String>();
        for (var value : parent.getAsJsonArray(key)) { require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().length() <= 240); result.add(value.getAsString()); }
        return List.copyOf(result);
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Inconsistent retained migration inputs"); }
    public static void main(String[] args) {
        if (args.length < 2) { System.err.println("Usage: MigrationInputCapture capture ROOT SELECTION POM_PLAN CONFIGURATION | inspect ROOT INPUT_ID | list ROOT"); System.exit(2); return; }
        try {
            String action = args[0]; require(Set.of("capture", "inspect", "list").contains(action));
            if (action.equals("capture")) {
                require(args.length == 5);
                var selection = GitHubRepositoryConnection.loadSelection(Path.of(args[2])); var plan = GitHubSourceInventory.loadPlan(Path.of(args[3]));
                var declarations = MigrationConfiguration.load(Path.of(args[4]));
                String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER"), key = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
                if (issuer == null || key == null || key.isBlank()) throw new IllegalArgumentException();
                var store = new EvidenceStore(Path.of(args[1]));
                var capture = new GitHubSourceInventory().capture(selection, GitHubAppCredentials.load(issuer, Path.of(key)), plan);
                var retained = retain(store, capture, declarations); System.out.println(GitHubJson.JSON.toJson(retained.summary()));
                if (retained.summary().declarationState() == MigrationConfiguration.State.DECLARATIONS_HAVE_GAPS) System.exit(3);
            } else if (action.equals("inspect")) {
                require(args.length == 3); System.out.println(GitHubJson.JSON.toJson(reopen(new EvidenceStore(Path.of(args[1])), args[2]).summary()));
            } else {
                require(args.length == 2); var store = new EvidenceStore(Path.of(args[1])); var summaries = new ArrayList<Summary>();
                for (String id : store.listInputs()) summaries.add(reopen(store, id).summary());
                System.out.println(GitHubJson.JSON.toJson(summaries));
            }
        } catch (GitHubRepositoryConnection.ConnectionFailure e) { System.err.println(GitHubJson.JSON.toJson(e.failure())); System.exit(1); }
        catch (IOException | RuntimeException e) { System.err.println("Migration inputs could not be captured or verified. Check host settings and evidence-store integrity."); System.exit(2); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("Input capture interrupted; no automatic retry."); System.exit(1); }
    }
}
