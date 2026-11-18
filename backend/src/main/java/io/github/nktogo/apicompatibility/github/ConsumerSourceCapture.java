package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import io.github.nktogo.apicompatibility.runner.InvoiceConsumerSourceCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.*;
import static io.github.nktogo.apicompatibility.github.GitHubSourceInventory.Stop;
import static io.github.nktogo.apicompatibility.runner.InvoiceConsumerSourceCatalog.FILES;

/** Retains source observations for the host's fixed invoice consumer catalog. */
public final class ConsumerSourceCapture {
    static final String ROOT = "cases/" + MigrationConfiguration.CASE_ID + "/" + InvoiceConsumerSourceCatalog.DIRECTORY;
    private static final String POLICY = "invoice-consumer-sources-v1";
    private static final int MAX_REQUESTS = 64;
    private static final int MAX_BYTES = 262144;
    private static final Map<String, Set<String>> EXPECTED_CHILDREN = expectedChildren();
    private static final Set<String> REQUIRED_POMS = Set.of(path("pom.xml"), path("webhook/pom.xml"), path("reconciliation/pom.xml"));
    private static final Set<String> ALLOWED_POMS = Set.of(path("pom.xml"), path("webhook/pom.xml"), path("reconciliation/pom.xml"), path("core/pom.xml"));

    public enum State { CAPTURED_REGISTERED_SOURCE, CAPTURED_WITH_GAPS }
    public enum FileState { READ, MISSING, UNSUPPORTED_ENTRY, SIZE_LIMIT, READ_FAILED, NOT_READ }
    public enum CatalogState { MATCHED, INCOMPLETE, REGISTRATION_UNAVAILABLE, REGISTRATION_MISMATCH, FILE_MISMATCH }
    public record FileObservation(String path, FileState state, String gitBlobSha, String contentSha256, Integer bytes) {}
    public record DirectoryGap(String directory, int unexpectedEntries) {}
    public record ProviderFailure(Code code, Stage stage, int httpStatus, String retryNotBefore, boolean tokenMayRemainActive) {
        public ProviderFailure {
            require(code != null && (stage == Stage.SOURCE_TREE || stage == Stage.SOURCE_BLOB));
            require(httpStatus == 0 || httpStatus >= 100 && httpStatus <= 599);
            if (retryNotBefore != null) Instant.parse(retryNotBefore);
        }
        static ProviderFailure from(Failure failure) {
            return new ProviderFailure(failure.code(), failure.stage(), failure.httpStatus(),
                    failure.retryNotBefore() == null ? null : failure.retryNotBefore().toString(), failure.tokenMayRemainActive());
        }
    }
    public record Problem(Stop reason, String path, ProviderFailure providerFailure) {
        public Problem {
            require(reason != null && FILES.contains(path));
            require((reason == Stop.CONNECTION_FAILURE) == (providerFailure != null));
        }
    }
    public record Observation(int formatVersion, String policy, String inputId, String bindingIdentity,
                              Selection selection, long appId, String treeSha, String observedAt, String completedAt,
                              List<FileObservation> files, List<DirectoryGap> directoryGaps, int sourceRequests,
                              int sourceBytes, Problem problem) {
        public Observation {
            require(formatVersion == 1 && POLICY.equals(policy) && isDigest(inputId) && isDigest(bindingIdentity));
            require(selection != null && appId > 0 && treeSha != null && treeSha.matches("[0-9a-f]{40}"));
            require(!Instant.parse(completedAt).isBefore(Instant.parse(observedAt)));
            files = List.copyOf(files);
            directoryGaps = List.copyOf(directoryGaps);
            require(sourceRequests >= 0 && sourceRequests <= MAX_REQUESTS && sourceBytes >= 0 && sourceBytes <= MAX_BYTES);
        }
    }
    public record Summary(String captureId, State state, Observation observation, CatalogState catalogState,
                          String expectedRegistrationSha256, List<String> catalogMismatches, int planningPomsCompared,
                          List<String> planningPomMismatches, MigrationConfiguration.State declarationState,
                          boolean executionAuthorized) {
        public Summary {
            catalogMismatches = List.copyOf(catalogMismatches);
            planningPomMismatches = List.copyOf(planningPomMismatches);
        }
    }
    private record CatalogCheck(CatalogState state, List<String> mismatches) {}

    private final EvidenceStore store;
    private final Clock clock;

    public ConsumerSourceCapture(EvidenceStore store) {
        this(store, Clock.systemUTC());
    }

    ConsumerSourceCapture(EvidenceStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public Summary capture(String inputId, Selection selection, GitHubAppCredentials credentials)
            throws IOException, ConnectionFailure, InterruptedException {
        try (var transport = new GitHubTransport()) {
            return capture(inputId, selection, credentials, transport);
        }
    }

    Summary capture(String inputId, Selection selection, GitHubAppCredentials credentials, GitHubTransport transport)
            throws IOException, ConnectionFailure, InterruptedException {
        var input = planningInput(inputId);
        var previous = input.binding().inventory().repository();
        require(previous.selection().equals(selection));
        Instant deadline = clock.instant().plusSeconds(180);
        var access = new GitHubRepositoryConnection(clock).openVerified(selection, credentials, transport);
        var current = access.snapshot();
        require(current.appId() == previous.appId() && current.treeSha().equals(previous.treeSha()));

        var reader = new GitHubPathReader(access, clock, deadline, MAX_REQUESTS, MAX_BYTES);
        var rows = new ArrayList<FileObservation>();
        var files = new TreeMap<String, byte[]>();
        var directoryGaps = new TreeMap<String, DirectoryGap>();
        Problem problem = null;
        for (String name : FILES) {
            FileState state = FileState.NOT_READ;
            if (problem == null) {
                try {
                    var file = reader.file(path(name), (directory, names) -> auditDirectory(directory, names, directoryGaps));
                    if (file.state() == GitHubPathReader.State.READ) {
                        byte[] bytes = file.bytes();
                        files.put(artifact(rows.size()), bytes);
                        rows.add(new FileObservation(name, FileState.READ, file.gitBlobSha(), GitSourceObjects.sha256(bytes), bytes.length));
                        continue;
                    }
                    state = FileState.valueOf(file.state().name());
                } catch (GitHubPathReader.Limit e) {
                    problem = new Problem(e.reason, name, null);
                } catch (ConnectionFailure e) {
                    state = FileState.READ_FAILED;
                    problem = new Problem(Stop.CONNECTION_FAILURE, name, ProviderFailure.from(e.failure()));
                } catch (IllegalArgumentException | ArithmeticException e) {
                    state = FileState.READ_FAILED;
                    problem = new Problem(Stop.INVALID_TREE_OR_BLOB, name, null);
                }
            }
            rows.add(new FileObservation(name, state, null, null, null));
        }
        var observation = new Observation(1, POLICY, inputId, input.summary().bindingIdentity(), selection, current.appId(),
                current.treeSha(), current.observedAt().toString(), clock.instant().toString(), rows,
                List.copyOf(directoryGaps.values()), reader.requests(), reader.totalBytes(), problem);
        files.put("capture.json", (GitHubJson.JSON.toJson(observation) + "\n").getBytes(StandardCharsets.UTF_8));
        summarize(null, observation, input, files);
        return inspect(store.saveConsumerSources(files));
    }

    public Summary inspect(String captureId) throws IOException {
        var artifacts = store.readConsumerSources(captureId);
        var json = GitHubJson.parse(artifacts.get("capture.json"), 65536);
        var observed = GitHubJson.JSON.fromJson(json, Observation.class);
        require(GitHubJson.JSON.toJsonTree(observed).equals(json));
        return summarize(captureId, observed, planningInput(observed.inputId()), artifacts);
    }

    public List<Summary> list() throws IOException {
        var summaries = new ArrayList<Summary>();
        for (String id : store.listConsumerSources()) summaries.add(inspect(id));
        return List.copyOf(summaries);
    }

    private MigrationInputCapture.Retained planningInput(String inputId) throws IOException {
        var input = MigrationInputCapture.reopen(store, inputId);
        var binding = input.binding();
        var selected = binding.inventory().plan().pomPaths();
        require(MigrationConfiguration.CASE_ID.equals(binding.declarations().caseId()));
        require(binding.declarations().repository().equals(MigrationConfiguration.Repository.from(binding.inventory().repository())));
        require(selected.containsAll(REQUIRED_POMS) && ALLOWED_POMS.containsAll(selected));
        require(new HashSet<>(selected).equals(new HashSet<>(binding.declarations().poms().stream().map(MigrationConfiguration.Pom::path).toList())));
        return input;
    }

    private static Summary summarize(String captureId, Observation observed, MigrationInputCapture.Retained input,
                                     Map<String, byte[]> artifacts) {
        var prior = input.binding().inventory().repository();
        require(observed.inputId().equals(input.summary().inputId()) && observed.bindingIdentity().equals(input.summary().bindingIdentity()));
        require(observed.selection().equals(prior.selection()) && observed.appId() == prior.appId() && observed.treeSha().equals(prior.treeSha()));
        require(observed.files().size() == FILES.size());

        var expectedArtifacts = new HashSet<>(Set.of("capture.json"));
        var content = new TreeMap<String, byte[]>();
        int bytesRead = 0;
        boolean stopped = false;
        Problem problem = observed.problem();
        for (int i = 0; i < FILES.size(); i++) {
            String name = FILES.get(i);
            var file = observed.files().get(i);
            require(name.equals(file.path()) && file.state() != null);
            if (problem != null && name.equals(problem.path())) {
                stopped = true;
                boolean budget = problem.reason() == Stop.DEADLINE || problem.reason() == Stop.REQUEST_LIMIT;
                require(file.state() == (budget ? FileState.NOT_READ : FileState.READ_FAILED));
            } else if (stopped) {
                require(file.state() == FileState.NOT_READ);
            } else {
                require(file.state() != FileState.NOT_READ && file.state() != FileState.READ_FAILED);
            }
            if (file.state() == FileState.READ) {
                String artifact = artifact(i);
                byte[] bytes = artifacts.get(artifact);
                require(bytes != null && bytes.length <= GitHubPathReader.MAX_FILE_BYTES && Objects.equals(file.bytes(), bytes.length));
                require(GitSourceObjects.sha256(bytes).equals(file.contentSha256()) && GitSourceObjects.gitHash("blob", bytes).equals(file.gitBlobSha()));
                bytesRead += bytes.length;
                expectedArtifacts.add(artifact);
                content.put(name, bytes);
            } else {
                require(file.bytes() == null && file.contentSha256() == null && file.gitBlobSha() == null);
            }
        }
        require(bytesRead == observed.sourceBytes() && observed.sourceRequests() >= content.size()
                && artifacts.keySet().equals(expectedArtifacts));
        String previousDirectory = null;
        for (var gap : observed.directoryGaps()) {
            require(EXPECTED_CHILDREN.containsKey(path(gap.directory())) && gap.unexpectedEntries() > 0 && gap.unexpectedEntries() <= 1000);
            require(previousDirectory == null || previousDirectory.compareTo(gap.directory()) < 0);
            previousDirectory = gap.directory();
        }

        var catalog = matchCatalog(content);
        var conflicts = new ArrayList<String>();
        var poms = input.poms();
        int compared = 0;
        for (var entry : content.entrySet()) {
            byte[] priorBytes = poms.get(path(entry.getKey()));
            if (priorBytes != null) {
                compared++;
                if (!Arrays.equals(priorBytes, entry.getValue())) conflicts.add(entry.getKey());
            }
        }
        boolean complete = catalog.state() == CatalogState.MATCHED && observed.directoryGaps().isEmpty() && conflicts.isEmpty();
        return new Summary(captureId, complete ? State.CAPTURED_REGISTERED_SOURCE : State.CAPTURED_WITH_GAPS, observed,
                catalog.state(), InvoiceConsumerSourceCatalog.REGISTRATION_SHA256, catalog.mismatches(), compared, conflicts,
                input.summary().declarationState(), false);
    }

    private static CatalogCheck matchCatalog(Map<String, byte[]> content) {
        String registration = InvoiceConsumerSourceCatalog.REGISTRATION;
        byte[] manifest = content.get(registration);
        if (manifest == null) return new CatalogCheck(CatalogState.REGISTRATION_UNAVAILABLE, List.of());
        if (!InvoiceConsumerSourceCatalog.REGISTRATION_SHA256.equals(GitSourceObjects.sha256(manifest)))
            return new CatalogCheck(CatalogState.REGISTRATION_MISMATCH, List.of(registration));

        var declared = GitHubJson.object(GitHubJson.parse(manifest, 65536).get("files"));
        var paths = new HashSet<>(FILES);
        paths.remove(registration);
        require(declared.keySet().equals(paths));
        var mismatches = new ArrayList<String>();
        for (var item : content.entrySet()) {
            String name = item.getKey();
            if (!name.equals(registration) && !GitHubJson.text(declared, name).equals(GitSourceObjects.sha256(item.getValue())))
                mismatches.add(name);
        }
        if (!mismatches.isEmpty()) return new CatalogCheck(CatalogState.FILE_MISMATCH, mismatches);
        return new CatalogCheck(content.size() == FILES.size() ? CatalogState.MATCHED : CatalogState.INCOMPLETE, List.of());
    }

    private static Map<String, Set<String>> expectedChildren() {
        var result = new HashMap<String, Set<String>>();
        for (String name : FILES) {
            String directory = ROOT;
            for (String segment : name.split("/")) {
                result.computeIfAbsent(directory, ignored -> new HashSet<>()).add(segment);
                directory += "/" + segment;
            }
        }
        return result;
    }

    private static void auditDirectory(String directory, Set<String> names, Map<String, DirectoryGap> gaps) {
        var expected = EXPECTED_CHILDREN.get(directory);
        if (expected == null) return;
        int unexpected = (int) names.stream().filter(name -> !expected.contains(name)).count();
        if (unexpected > 0) {
            String relative = directory.equals(ROOT) ? "" : directory.substring(ROOT.length() + 1);
            gaps.put(relative, new DirectoryGap(relative, unexpected));
        }
    }

    private static String path(String name) { return name.isEmpty() ? ROOT : ROOT + "/" + name; }
    private static String artifact(int position) { return "source-" + position + ".bin"; }
    private static boolean isDigest(String text) { return text != null && text.matches("[0-9a-f]{64}"); }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid consumer source capture or scope");
    }

    public static void main(String[] args) {
        try {
            require(args.length >= 2);
            var capture = new ConsumerSourceCapture(new EvidenceStore(Path.of(args[1])));
            switch (args[0]) {
                case "capture" -> {
                    require(args.length == 4 && isDigest(args[2]));
                    var selection = GitHubRepositoryConnection.loadSelection(Path.of(args[3]));
                    String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER");
                    String keyFile = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
                    require(issuer != null && keyFile != null && !keyFile.isBlank());
                    var result = capture.capture(args[2], selection, GitHubAppCredentials.load(issuer, Path.of(keyFile)));
                    System.out.println(GitHubJson.JSON.toJson(result));
                    if (result.state() == State.CAPTURED_WITH_GAPS) System.exit(3);
                }
                case "inspect" -> {
                    require(args.length == 3);
                    System.out.println(GitHubJson.JSON.toJson(capture.inspect(args[2])));
                }
                case "list" -> {
                    require(args.length == 2);
                    System.out.println(GitHubJson.JSON.toJson(capture.list()));
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (ConnectionFailure e) {
            System.err.println(GitHubJson.JSON.toJson(e.failure()));
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Source capture interrupted. No capture was finalized.");
            System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("Consumer source operation failed. Check the retained inputs, scope, App settings and store integrity.");
            System.exit(2);
        }
    }
}
