package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.inventory.MavenPomInventory;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Binds host declarations to selected source facts. Never resolves runtime configuration or grants an action. */
public final class MigrationConfiguration {
    static final String POLICY = "invoice-declared-configuration-v1";
    static final String CASE_ID = "stripe-invoice-subscription-2025-03-31";
    static final String ACACIA = "2025-02-24.acacia", BASIL = "2025-03-31.basil";
    static final Profile PROFILE = new Profile(CASE_ID,
            "97fec1eb25c3882b9cae4cf46372b325ac20dfde0b9e0db07a965c6f2ca6756a",
            List.of("invoice-reconciliation", "invoice-webhook"), List.of("28.4.0", "29.0.0"), List.of(ACACIA, BASIL));

    public record Repository(long accountId, long repositoryId, String owner, String name, String commitSha, String treeSha) {
        public Repository {
            new GitHubRepositoryConnection.Selection(1, 1, accountId, repositoryId, owner, name, commitSha);
            require(treeSha != null && treeSha.matches("[0-9a-f]{40}"));
        }
        public static Repository from(GitHubRepositoryConnection.Snapshot snapshot) {
            var s = snapshot.selection();
            return new Repository(s.accountId(), s.repositoryId(), s.owner(), s.name(), s.commitSha(), snapshot.treeSha());
        }
    }
    public record Pom(String path, String contentSha256) {
        public Pom {
            new GitHubSourceInventory.Plan(1, List.of(path));
            require(contentSha256 != null && contentSha256.matches("[0-9a-f]{64}"));
        }
    }
    /** Null denotes unknown; no SDK, account, request, endpoint or event value fills another field. */
    public record Versions(String sdkVersion, String requestApiVersion, String endpointApiVersion,
                           String eventApiVersion, String accountApiVersion, String adapterApiVersion) {
        public Versions {
            require(sdkVersion == null || sdkVersion.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"));
            for (String value : Arrays.asList(requestApiVersion, endpointApiVersion, eventApiVersion, accountApiVersion, adapterApiVersion)) {
                if (value == null) continue;
                require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(\\.[a-z]{1,16})?"));
                try { LocalDate.parse(value.substring(0, 10)); }
                catch (java.time.DateTimeException e) { throw new IllegalArgumentException("Invalid configuration date"); }
            }
        }
    }
    public record Consumer(String id, String pomPath, Versions baseline, Versions target) {
        public Consumer {
            require(id != null && id.matches("[a-z][a-z0-9-]{0,63}"));
            new GitHubSourceInventory.Plan(1, List.of(pomPath));
            Objects.requireNonNull(baseline); Objects.requireNonNull(target);
        }
    }
    public record Plan(int formatVersion, String caseId, Repository repository, List<Pom> poms, List<Consumer> consumers) {
        public Plan {
            require(formatVersion == 1 && caseId != null && caseId.matches("[a-z][a-z0-9-]{0,99}"));
            Objects.requireNonNull(repository); poms = List.copyOf(poms); consumers = List.copyOf(consumers);
            require(!poms.isEmpty() && poms.size() <= 8 && !consumers.isEmpty() && consumers.size() <= 8);
            require(poms.stream().map(Pom::path).distinct().count() == poms.size());
            require(consumers.stream().map(Consumer::id).distinct().count() == consumers.size());
            poms = poms.stream().sorted(Comparator.comparing(Pom::path)).toList();
            consumers = consumers.stream().sorted(Comparator.comparing(Consumer::id)).toList();
        }
    }
    public record Profile(String caseId, String contractSha256, List<String> consumers, List<String> sdkVersions, List<String> apiVersions) {
        public Profile { consumers = List.copyOf(consumers); sdkVersions = List.copyOf(sdkVersions); apiVersions = List.copyOf(apiVersions); }
    }
    public enum State { DECLARATIONS_COMPLETE_NOT_VERIFIED, DECLARATIONS_HAVE_GAPS }
    public enum Code { REPOSITORY_MISMATCH, INVENTORY_INCOMPLETE, POM_SET_MISMATCH, POM_UNAVAILABLE, POM_IDENTITY_MISMATCH,
        UNREGISTERED_CASE, MISSING_CONSUMER, UNREGISTERED_CONSUMER, UNKNOWN_VERSION, VERSION_OUTSIDE_PROFILE,
        SDK_REQUEST_PAIR_OUTSIDE_PROFILE, UNEXPECTED_WEBHOOK_CONFIGURATION, ADAPTER_EVENT_MISMATCH,
        ADAPTER_REQUIRED, ADAPTER_MAPPING_OUTSIDE_PROFILE, BASELINE_POM_DECLARATION_DIFFERS, NO_DECLARED_TRANSITION }
    public record Issue(Code code, String consumerId, String phase, String field, String path) {}
    public record Result(int formatVersion, String policy, String bindingIdentity, State state, Plan declarations,
                         GitHubSourceInventory.Result inventory, Profile profile, List<Issue> issues, List<String> limitations) {
        public Result { issues = List.copyOf(issues); limitations = List.copyOf(limitations); }
        /** Input equality only; a matching digest does not authenticate a report or authorize execution. */
        public boolean hasSameInputs(GitHubSourceInventory.Result currentInventory, Plan currentPlan) {
            return bindingIdentity.equals(identity(currentInventory, currentPlan));
        }
    }
    private record Identity(String policy, Plan declarations, Profile profile, Repository observedRepository,
                            GitHubSourceInventory.Plan selection, GitHubSourceInventory.State inventoryState,
                            List<GitHubSourceInventory.FileResult> files) {}
    private MigrationConfiguration() {}

    public static Result bind(GitHubSourceInventory.Result inventory, Plan plan) {
        Objects.requireNonNull(inventory); Objects.requireNonNull(plan); var issues = new ArrayList<Issue>();
        if (!plan.repository().equals(Repository.from(inventory.repository()))) issue(issues, Code.REPOSITORY_MISMATCH, null, null, null, null);
        if (inventory.state() != GitHubSourceInventory.State.COMPLETE_WITHIN_SELECTED_FILES)
            issue(issues, Code.INVENTORY_INCOMPLETE, null, null, null, null);
        var files = new LinkedHashMap<String, GitHubSourceInventory.FileResult>();
        for (var file : inventory.files()) require(files.putIfAbsent(file.path(), file) == null);
        var expected = new LinkedHashMap<String, Pom>(); for (var pom : plan.poms()) expected.put(pom.path(), pom);
        if (!expected.keySet().equals(new HashSet<>(inventory.plan().pomPaths())) || !expected.keySet().equals(files.keySet()))
            issue(issues, Code.POM_SET_MISMATCH, null, null, null, null);
        for (var pom : plan.poms()) {
            var file = files.get(pom.path());
            if (!read(file)) issue(issues, Code.POM_UNAVAILABLE, null, null, null, pom.path());
            else if (!pom.contentSha256().equals(file.contentSha256())) issue(issues, Code.POM_IDENTITY_MISMATCH, null, null, null, pom.path());
        }
        Profile profile = profile(plan);
        if (profile == null) issue(issues, Code.UNREGISTERED_CASE, null, null, null, null);
        else {
            for (String id : profile.consumers()) if (plan.consumers().stream().noneMatch(c -> c.id().equals(id)))
                issue(issues, Code.MISSING_CONSUMER, id, null, null, null);
        }
        boolean changed = false;
        for (var consumer : plan.consumers()) {
            changed |= !consumer.baseline().equals(consumer.target());
            var file = files.get(consumer.pomPath());
            if (!expected.containsKey(consumer.pomPath()) || !read(file))
                issue(issues, Code.POM_UNAVAILABLE, consumer.id(), null, null, consumer.pomPath());
            if (profile == null) continue;
            if (!profile.consumers().contains(consumer.id())) { issue(issues, Code.UNREGISTERED_CONSUMER, consumer.id(), null, null, consumer.pomPath()); continue; }
            versions(consumer, "baseline", consumer.baseline(), issues); versions(consumer, "target", consumer.target(), issues);
            if (read(file) && consumer.baseline().sdkVersion() != null) {
                for (var d : file.declarations().stripeJavaDeclarations()) {
                    if (!d.conditionalProfile() && !d.dependencyManagement() && d.versionKind() == MavenPomInventory.VersionKind.LITERAL_DECLARATION
                            && !consumer.baseline().sdkVersion().equals(d.declaredVersionExpression()))
                        issue(issues, Code.BASELINE_POM_DECLARATION_DIFFERS, consumer.id(), "baseline", "sdkVersion", consumer.pomPath());
                }
            }
        }
        if (!changed) issue(issues, Code.NO_DECLARED_TRANSITION, null, null, null, null);
        return new Result(1, POLICY, identity(inventory, plan), issues.isEmpty() ? State.DECLARATIONS_COMPLETE_NOT_VERIFIED : State.DECLARATIONS_HAVE_GAPS,
                plan, inventory, profile, issues, List.of("OPERATOR_DECLARATIONS_NOT_RUNTIME_PROOF", "SELECTED_POMS_ONLY",
                "MAVEN_EFFECTIVE_MODEL_NOT_RESOLVED", "STRIPE_ACCOUNT_AND_ENDPOINT_NOT_QUERIED", "EVENT_SOURCE_NOT_AUTHENTICATED",
                "CASE_MAPPING_IS_NOT_EXECUTION_AUTHORIZATION", "NO_COMPATIBILITY_OR_RELEASE_APPROVAL"));
    }
    private static boolean read(GitHubSourceInventory.FileResult file) {
        return file != null && file.state() == GitHubSourceInventory.FileState.READ && file.declarations() != null
                && file.declarations().state() == MavenPomInventory.State.SCANNED;
    }
    private static Profile profile(Plan plan) { return CASE_ID.equals(plan.caseId()) ? PROFILE : null; }
    private static void versions(Consumer consumer, String phase, Versions v, List<Issue> issues) {
        boolean webhook = consumer.id().equals("invoice-webhook");
        declared(consumer, phase, "sdkVersion", v.sdkVersion(), PROFILE.sdkVersions(), issues);
        declared(consumer, phase, "requestApiVersion", v.requestApiVersion(), PROFILE.apiVersions(), issues);
        declared(consumer, phase, "accountApiVersion", v.accountApiVersion(), PROFILE.apiVersions(), issues);
        String sdkApi = switch (Objects.toString(v.sdkVersion(), "")) { case "28.4.0" -> ACACIA; case "29.0.0" -> BASIL; default -> null; };
        if (sdkApi != null && v.requestApiVersion() != null && !sdkApi.equals(v.requestApiVersion()))
            issue(issues, Code.SDK_REQUEST_PAIR_OUTSIDE_PROFILE, consumer.id(), phase, "requestApiVersion", consumer.pomPath());
        if (!webhook) {
            if (v.endpointApiVersion() != null || v.eventApiVersion() != null || v.adapterApiVersion() != null)
                issue(issues, Code.UNEXPECTED_WEBHOOK_CONFIGURATION, consumer.id(), phase, null, consumer.pomPath());
            return;
        }
        declared(consumer, phase, "endpointApiVersion", v.endpointApiVersion(), PROFILE.apiVersions(), issues);
        declared(consumer, phase, "eventApiVersion", v.eventApiVersion(), PROFILE.apiVersions(), issues);
        if (v.adapterApiVersion() != null) {
            if (!PROFILE.apiVersions().contains(v.adapterApiVersion())) issue(issues, Code.VERSION_OUTSIDE_PROFILE, consumer.id(), phase, "adapterApiVersion", consumer.pomPath());
            if (!v.adapterApiVersion().equals(v.eventApiVersion())) issue(issues, Code.ADAPTER_EVENT_MISMATCH, consumer.id(), phase, "adapterApiVersion", consumer.pomPath());
        }
        if (sdkApi != null && v.eventApiVersion() != null && !sdkApi.equals(v.eventApiVersion())) {
            if (v.adapterApiVersion() == null) issue(issues, Code.ADAPTER_REQUIRED, consumer.id(), phase, "adapterApiVersion", consumer.pomPath());
            if (!(sdkApi.equals(BASIL) && v.eventApiVersion().equals(ACACIA)))
                issue(issues, Code.ADAPTER_MAPPING_OUTSIDE_PROFILE, consumer.id(), phase, "eventApiVersion", consumer.pomPath());
        }
    }
    private static void declared(Consumer consumer, String phase, String field, String value, List<String> allowed, List<Issue> issues) {
        if (value == null) issue(issues, Code.UNKNOWN_VERSION, consumer.id(), phase, field, consumer.pomPath());
        else if (!allowed.contains(value)) issue(issues, Code.VERSION_OUTSIDE_PROFILE, consumer.id(), phase, field, consumer.pomPath());
    }
    private static void issue(List<Issue> issues, Code code, String consumer, String phase, String field, String path) {
        var issue = new Issue(code, consumer, phase, field, path); if (!issues.contains(issue)) issues.add(issue);
    }
    private static String identity(GitHubSourceInventory.Result inventory, Plan plan) {
        var value = new Identity(POLICY, plan, profile(plan), Repository.from(inventory.repository()), inventory.plan(), inventory.state(), inventory.files());
        return GitSourceObjects.sha256(GitHubJson.JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid migration configuration"); }

    public static Plan load(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid configuration file");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)); var stream = Channels.newInputStream(channel)) {
            var o = GitHubJson.parse(stream.readNBytes(32769), 32768);
            GitHubJson.fields(o, "formatVersion", "caseId", "repository", "poms", "consumers");
            GitHubJson.require(GitHubJson.number(o, "formatVersion") == 1);
            var r = GitHubJson.object(o.get("repository")); GitHubJson.fields(r, "accountId", "repositoryId", "owner", "name", "commitSha", "treeSha");
            var repository = new Repository(GitHubJson.number(r, "accountId"), GitHubJson.number(r, "repositoryId"), GitHubJson.text(r, "owner"),
                    GitHubJson.text(r, "name"), GitHubJson.text(r, "commitSha"), GitHubJson.text(r, "treeSha"));
            require(o.get("poms").isJsonArray() && o.getAsJsonArray("poms").size() <= 8);
            var poms = new ArrayList<Pom>();
            for (var item : o.getAsJsonArray("poms")) {
                var p = GitHubJson.object(item); GitHubJson.fields(p, "path", "contentSha256"); poms.add(new Pom(GitHubJson.text(p, "path"), GitHubJson.text(p, "contentSha256")));
            }
            require(o.get("consumers").isJsonArray() && o.getAsJsonArray("consumers").size() <= 8);
            var consumers = new ArrayList<Consumer>();
            for (var item : o.getAsJsonArray("consumers")) {
                var c = GitHubJson.object(item); GitHubJson.fields(c, "id", "pomPath", "baseline", "target");
                consumers.add(new Consumer(GitHubJson.text(c, "id"), GitHubJson.text(c, "pomPath"), versions(GitHubJson.object(c.get("baseline"))), versions(GitHubJson.object(c.get("target")))));
            }
            return new Plan(1, GitHubJson.text(o, "caseId"), repository, poms, consumers);
        }
    }
    private static Versions versions(JsonObject o) {
        GitHubJson.fields(o, "sdkVersion", "requestApiVersion", "endpointApiVersion", "eventApiVersion", "accountApiVersion", "adapterApiVersion");
        return new Versions(nullable(o, "sdkVersion"), nullable(o, "requestApiVersion"), nullable(o, "endpointApiVersion"), nullable(o, "eventApiVersion"), nullable(o, "accountApiVersion"), nullable(o, "adapterApiVersion"));
    }
    private static String nullable(JsonObject o, String key) { return o.get(key).isJsonNull() ? null : GitHubJson.text(o, key); }
    public static void main(String[] args) {
        if (args.length != 3) { System.err.println("Usage: MigrationConfiguration SELECTION_JSON POM_PLAN_JSON CONFIGURATION_JSON"); System.exit(2); return; }
        try {
            var selection = GitHubRepositoryConnection.loadSelection(Path.of(args[0])); var pomPlan = GitHubSourceInventory.loadPlan(Path.of(args[1])); var plan = load(Path.of(args[2]));
            String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER"), key = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
            if (issuer == null || key == null || key.isBlank()) throw new IllegalArgumentException();
            var inventory = new GitHubSourceInventory().inspect(selection, GitHubAppCredentials.load(issuer, Path.of(key)), pomPlan);
            var result = bind(inventory, plan); System.out.println(GitHubJson.JSON.toJson(result));
            if (result.state() == State.DECLARATIONS_HAVE_GAPS) System.exit(3);
        } catch (GitHubRepositoryConnection.ConnectionFailure e) { System.err.println(GitHubJson.JSON.toJson(e.failure())); System.exit(1); }
        catch (IOException | RuntimeException e) { System.err.println("Configuration inspection could not start. Check the selection, plans and host App configuration."); System.exit(2); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("Configuration inspection interrupted; no automatic retry."); System.exit(1); }
    }
}
