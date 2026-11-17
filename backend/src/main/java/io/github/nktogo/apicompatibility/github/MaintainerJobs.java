package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.DurableExecution;
import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import io.github.nktogo.apicompatibility.runner.LocalFileLease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;

/** Durable read-only planning refreshes. Input identities and job completion never authorize migration execution. */
public final class MaintainerJobs {
    static final String KIND = "refresh-planning-inputs-v1";
    public enum State { QUEUED, ACTIVE, WORKER_BUSY, RECOVERY_REQUIRED, CANCELLATION_REQUESTED,
        REFRESHED, REFRESHED_WITH_GAPS, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED }
    public enum Reason { NONE, PROVIDER_FAILURE, HOST_OR_LOCAL_FAILURE, CANCELLED_BEFORE_START,
        CANCELLED_DURING_READ, DEADLINE_EXCEEDED, CONTROLLER_INTERRUPTED, LOST_READ_OUTCOME, LOCAL_PUBLICATION_MISSING }
    record Spec(int formatVersion, String jobId, String kind, String inputId, String bindingIdentity, String createdAt, int deadlineSeconds) {
        Spec { require(formatVersion == 1 && KIND.equals(kind) && deadlineSeconds == 180); id(jobId); hash(inputId); hash(bindingIdentity); time(createdAt); }
    }
    record StartRecord(int formatVersion, String jobId, String startedAt) {
        StartRecord { require(formatVersion == 1); id(jobId); time(startedAt); }
    }
    record RecoveryRecord(int formatVersion, String jobId, String recoveredAt) {
        RecoveryRecord { require(formatVersion == 1); id(jobId); time(recoveredAt); }
    }
    public record ProviderFailure(GitHubRepositoryConnection.Code code, GitHubRepositoryConnection.Stage stage,
                                  int httpStatus, String retryNotBefore, boolean tokenMayRemainActive) {
        public ProviderFailure { Objects.requireNonNull(code); Objects.requireNonNull(stage); require(httpStatus == 0 || httpStatus >= 100 && httpStatus <= 599); if (!retryNotBefore.isEmpty()) time(retryNotBefore); }
        static ProviderFailure of(GitHubRepositoryConnection.Failure f) {
            return f == null ? null : new ProviderFailure(f.code(), f.stage(), f.httpStatus(), f.retryNotBefore() == null ? "" : f.retryNotBefore().toString(), f.tokenMayRemainActive());
        }
    }
    record CaptureRecord(int formatVersion, String jobId, String inputId, String outputInputId, String outputBindingIdentity,
                         MigrationConfiguration.State declarationState, ProviderFailure providerFailure, String capturedAt) {
        CaptureRecord { require(formatVersion == 1); id(jobId); hash(inputId); hash(outputInputId); hash(outputBindingIdentity); Objects.requireNonNull(declarationState); time(capturedAt); }
    }
    record FailureRecord(int formatVersion, String jobId, String inputId, State state, Reason reason,
                         ProviderFailure providerFailure, boolean tokenMayRemainActive, String recordedAt) {
        FailureRecord { require(formatVersion == 1 && Set.of(State.FAILED, State.CANCELLED, State.TIMED_OUT, State.INTERRUPTED).contains(state) && reason != Reason.NONE); id(jobId); hash(inputId); time(recordedAt); }
    }
    record Result(int formatVersion, String jobId, String inputId, State state, Reason reason, String outputInputId,
                  boolean recovered, boolean tokenMayRemainActive, String finalizedAt) {
        Result { require(formatVersion == 1 && terminal(state)); id(jobId); hash(inputId); Objects.requireNonNull(reason); Objects.requireNonNull(outputInputId);
            require((state == State.REFRESHED || state == State.REFRESHED_WITH_GAPS) == !outputInputId.isEmpty());
            require((state == State.REFRESHED || state == State.REFRESHED_WITH_GAPS) == (reason == Reason.NONE));
            if (!outputInputId.isEmpty()) hash(outputInputId); time(finalizedAt); }
    }
    public record View(String jobId, State state, String inputId, String bindingIdentity, String outputInputId,
                       String pendingOutputInputId, boolean cancellationRequested, boolean recovered, Reason reason,
                       ProviderFailure providerFailure, boolean tokenMayRemainActive, MigrationInputCapture.Summary output,
                       boolean executionAuthorized) {}
    public record WorkerResult(boolean busy, List<View> jobs) { public WorkerResult { jobs = List.copyOf(jobs); } }
    @FunctionalInterface interface Refresher {
        GitHubSourceInventory.Capture refresh(GitHubRepositoryConnection.Selection selection, GitHubSourceInventory.Plan plan,
                                             GitHubSourceInventory.CancellationCheck control)
                throws IOException, InterruptedException, GitHubRepositoryConnection.ConnectionFailure;
    }
    @FunctionalInterface interface Hook { void reached(String stage, String jobId) throws IOException; }
    private static final class CancelRead extends InterruptedException { private static final long serialVersionUID = 1L; }
    private static final class DeadlineRead extends InterruptedException { private static final long serialVersionUID = 1L; }
    private final Path root;
    private final EvidenceStore store;
    private final Clock clock;
    private final Refresher refresher;
    private final Hook hook;
    public MaintainerJobs(Path root, EvidenceStore store) throws IOException {
        this(root, store, Clock.systemUTC(), (selection, plan, control) -> {
            String issuer = System.getenv("WORKBENCH_GITHUB_APP_ISSUER"), key = System.getenv("WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE");
            if (issuer == null || key == null || key.isBlank()) throw new IOException("Host App configuration unavailable");
            return new GitHubSourceInventory().capture(selection, GitHubAppCredentials.load(issuer, Path.of(key)), plan, control);
        }, (stage, id) -> {});
    }
    MaintainerJobs(Path root, EvidenceStore store, Clock clock, Refresher refresher, Hook hook) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)); this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock); this.refresher = Objects.requireNonNull(refresher); this.hook = Objects.requireNonNull(hook);
    }
    public View create(String inputId) throws IOException {
        var input = MigrationInputCapture.reopen(store, inputId); String job = "planjob-" + UUID.randomUUID();
        Files.createDirectory(path(job));
        publish(job, "spec.json", new Spec(1, job, KIND, inputId, input.summary().bindingIdentity(), now(), 180));
        return status(job);
    }
    public List<View> list() throws IOException {
        var specs = new ArrayList<Spec>();
        try (var entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString(); if (!name.startsWith("planjob-")) continue;
                require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)); id(name);
                if (exists(name, "spec.json")) specs.add(spec(name));
            }
        }
        specs.sort(Comparator.comparing((Spec s) -> Instant.parse(s.createdAt())).thenComparing(Spec::jobId));
        var result = new ArrayList<View>(); for (var s : specs) result.add(status(s.jobId())); return List.copyOf(result);
    }
    public View status(String job) throws IOException {
        var s = spec(job);
        try (var lease = owner(job)) {
            if (lease == null) return view(s, State.ACTIVE);
            if (exists(job, "result.json")) return finished(s);
            if (exists(job, "started.json") || exists(job, "capture.json") || exists(job, "failure.json") || exists(job, "recovery-started.json")) return view(s, State.RECOVERY_REQUIRED);
            return view(s, exists(job, "cancel.request") ? State.CANCELLATION_REQUESTED : State.QUEUED);
        }
    }
    public View run(String job, GitHubRepositoryConnection.Selection approvedSelection) throws IOException {
        var s = spec(job); if (exists(job, "result.json")) return finished(s);
        try (var worker = LocalFileLease.tryAcquire(root.resolve("worker.lock"))) {
            if (worker == null) return view(s, State.WORKER_BUSY);
            return runOwned(s, approvedSelection);
        }
    }
    private View runOwned(Spec s, GitHubRepositoryConnection.Selection approvedSelection) throws IOException {
        String job = s.jobId();
        try (var lease = owner(job)) {
            if (lease == null) return view(s, State.ACTIVE);
            if (exists(job, "result.json")) return finished(s);
            if (exists(job, "started.json") || exists(job, "capture.json") || exists(job, "failure.json") || exists(job, "recovery-started.json")) return view(s, State.RECOVERY_REQUIRED);
            if (exists(job, "cancel.request")) return fail(s, State.CANCELLED, Reason.CANCELLED_BEFORE_START, null, false, false);
            var input = MigrationInputCapture.reopen(store, s.inputId()); require(input.summary().bindingIdentity().equals(s.bindingIdentity()));
            var source = input.binding().inventory(); require(approvedSelection != null && approvedSelection.equals(source.repository().selection()));
            String started = now(); publish(job, "started.json", new StartRecord(1, job, started));
            hook.reached("started", job);
            GitHubSourceInventory.Capture capture;
            try {
                capture = refresher.refresh(approvedSelection, source.plan(), () -> {
                    if (exists(job, "cancel.request")) throw new CancelRead();
                    if (!clock.instant().isBefore(Instant.parse(started).plusSeconds(s.deadlineSeconds()))) throw new DeadlineRead();
                });
                require(capture.inventory().repository().selection().equals(approvedSelection) && capture.inventory().plan().equals(source.plan()));
            } catch (CancelRead e) { return fail(s, State.CANCELLED, Reason.CANCELLED_DURING_READ, null, true, false); }
            catch (DeadlineRead e) { return fail(s, State.TIMED_OUT, Reason.DEADLINE_EXCEEDED, null, true, false); }
            catch (InterruptedException e) {
                // Checkpoint the interruption with the flag temporarily cleared, then preserve it for the caller.
                Thread.interrupted();
                try { return fail(s, State.INTERRUPTED, Reason.CONTROLLER_INTERRUPTED, null, true, false); }
                finally { Thread.currentThread().interrupt(); }
            } catch (GitHubRepositoryConnection.ConnectionFailure e) {
                return fail(s, State.FAILED, Reason.PROVIDER_FAILURE, ProviderFailure.of(e.failure()), e.failure().tokenMayRemainActive(), false);
            } catch (IOException | RuntimeException e) { return fail(s, State.FAILED, Reason.HOST_OR_LOCAL_FAILURE, null, true, false); }
            var prepared = MigrationInputCapture.prepare(capture, input.binding().declarations());
            var failure = capture.inventory().problem() == null ? null : ProviderFailure.of(capture.inventory().problem().connectionFailure());
            publish(job, "capture.json", new CaptureRecord(1, job, s.inputId(), prepared.inputId(), prepared.binding().bindingIdentity(),
                    prepared.binding().state(), failure, capture.inventory().completedAt().toString()));
            hook.reached("prepared", job); prepared.publish(store); hook.reached("published", job);
            return finishCapture(s, false);
        }
    }
    public WorkerResult work(GitHubRepositoryConnection.Selection approvedSelection, int maximumJobs) throws IOException {
        require(approvedSelection != null && maximumJobs >= 1 && maximumJobs <= 10);
        try (var worker = LocalFileLease.tryAcquire(root.resolve("worker.lock"))) {
            if (worker == null) return new WorkerResult(true, List.of());
            var done = new ArrayList<View>();
            for (var queued : list()) {
                if (done.size() == maximumJobs || Thread.currentThread().isInterrupted()) break;
                if (queued.state() != State.QUEUED && queued.state() != State.CANCELLATION_REQUESTED) continue;
                var input = MigrationInputCapture.reopen(store, queued.inputId());
                if (!input.binding().inventory().repository().selection().equals(approvedSelection)) continue;
                var result = runOwned(spec(queued.jobId()), approvedSelection); done.add(result);
                if (!Set.of(State.REFRESHED, State.REFRESHED_WITH_GAPS, State.CANCELLED).contains(result.state()) || result.providerFailure() != null) break;
            }
            return new WorkerResult(false, done);
        }
    }
    public View cancel(String job) throws IOException {
        var s = spec(job); if (exists(job, "result.json")) return finished(s);
        try (var channel = java.nio.channels.FileChannel.open(path(job).resolve("cancel.request"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        catch (FileAlreadyExistsException ignored) { /* Idempotent request; existence is validated below. */ }
        var view = recover(job); return view.state() == State.ACTIVE ? view(s, State.CANCELLATION_REQUESTED) : view;
    }
    public View recover(String job) throws IOException {
        var s = spec(job);
        try (var lease = owner(job)) {
            if (lease == null) return view(s, State.ACTIVE);
            if (exists(job, "result.json")) return finished(s);
            if (exists(job, "capture.json")) {
                recoveryMarker(job);
                var capture = capture(s);
                if (store.hasInputReference(capture.outputInputId())) return finishCapture(s, true);
                return interrupted(s, Reason.LOCAL_PUBLICATION_MISSING);
            }
            if (exists(job, "failure.json")) {
                recoveryMarker(job); var failure = failure(s);
                return finish(s, failure.state(), failure.reason(), "", true, failure.tokenMayRemainActive());
            }
            if (exists(job, "started.json")) { recoveryMarker(job); return interrupted(s, Reason.LOST_READ_OUTCOME); }
            require(!exists(job, "recovery-started.json"));
            if (exists(job, "cancel.request")) return fail(s, State.CANCELLED, Reason.CANCELLED_BEFORE_START, null, false, false);
            return view(s, State.QUEUED);
        }
    }
    private View interrupted(Spec s, Reason reason) throws IOException {
        boolean cancelled = exists(s.jobId(), "cancel.request");
        return finish(s, cancelled ? State.CANCELLED : State.INTERRUPTED, cancelled ? Reason.CANCELLED_DURING_READ : reason, "", true, true);
    }
    private View fail(Spec s, State state, Reason reason, ProviderFailure provider, boolean token, boolean recovered) throws IOException {
        publish(s.jobId(), "failure.json", new FailureRecord(1, s.jobId(), s.inputId(), state, reason, provider, token, now()));
        return finish(s, state, reason, "", recovered, token);
    }
    private View finishCapture(Spec s, boolean recovered) throws IOException {
        var c = capture(s); output(c);
        State state = c.declarationState() == MigrationConfiguration.State.DECLARATIONS_COMPLETE_NOT_VERIFIED ? State.REFRESHED : State.REFRESHED_WITH_GAPS;
        return finish(s, state, Reason.NONE, c.outputInputId(), recovered, true);
    }
    private View finish(Spec s, State state, Reason reason, String output, boolean recovered, boolean token) throws IOException {
        hook.reached("finalizing", s.jobId());
        publish(s.jobId(), "result.json", new Result(1, s.jobId(), s.inputId(), state, reason, output, recovered, token, now()));
        return finished(s);
    }
    private View finished(Spec s) throws IOException {
        var result = read(path(s.jobId()).resolve("result.json"), Result.class); same(s, result.jobId(), result.inputId());
        require(!result.recovered() || exists(s.jobId(), "recovery-started.json"));
        MigrationInputCapture.Summary output = null; ProviderFailure provider = null; String pending = "";
        if (exists(s.jobId(), "capture.json")) { var c = capture(s); pending = c.outputInputId(); provider = c.providerFailure(); }
        if (!result.outputInputId().isEmpty()) {
            var c = capture(s); require(result.outputInputId().equals(c.outputInputId()) && result.reason() == Reason.NONE && result.tokenMayRemainActive());
            output = output(c); require((result.state() == State.REFRESHED) == (output.declarationState() == MigrationConfiguration.State.DECLARATIONS_COMPLETE_NOT_VERIFIED));
        } else if (exists(s.jobId(), "failure.json")) {
            var failure = failure(s); require(failure.state() == result.state() && failure.reason() == result.reason()
                    && failure.tokenMayRemainActive() == result.tokenMayRemainActive()); provider = failure.providerFailure();
        } else {
            require(result.recovered() && exists(s.jobId(), "started.json") && exists(s.jobId(), "recovery-started.json")
                    && Set.of(State.INTERRUPTED, State.CANCELLED).contains(result.state())
                    && Set.of(Reason.LOCAL_PUBLICATION_MISSING, Reason.LOST_READ_OUTCOME, Reason.CANCELLED_DURING_READ).contains(result.reason()));
        }
        return new View(s.jobId(), result.state(), s.inputId(), s.bindingIdentity(), result.outputInputId(), pending,
                exists(s.jobId(), "cancel.request"), result.recovered(), result.reason(), provider, result.tokenMayRemainActive(), output, false);
    }
    private MigrationInputCapture.Summary output(CaptureRecord c) throws IOException {
        var retained = MigrationInputCapture.reopen(store, c.outputInputId()); var output = retained.summary();
        var problem = retained.binding().inventory().problem();
        require(output.bindingIdentity().equals(c.outputBindingIdentity()) && output.declarationState() == c.declarationState()
                && retained.binding().inventory().completedAt().equals(Instant.parse(c.capturedAt()))
                && Objects.equals(c.providerFailure(), problem == null ? null : ProviderFailure.of(problem.connectionFailure()))); return output;
    }
    private View view(Spec s, State state) throws IOException {
        String pending = exists(s.jobId(), "capture.json") ? capture(s).outputInputId() : "";
        return new View(s.jobId(), state, s.inputId(), s.bindingIdentity(), "", pending, exists(s.jobId(), "cancel.request"), false, Reason.NONE, null, exists(s.jobId(), "started.json"), null, false);
    }
    private CaptureRecord capture(Spec s) throws IOException { var c = read(path(s.jobId()).resolve("capture.json"), CaptureRecord.class); same(s, c.jobId(), c.inputId()); return c; }
    private FailureRecord failure(Spec s) throws IOException { var f = read(path(s.jobId()).resolve("failure.json"), FailureRecord.class); same(s, f.jobId(), f.inputId()); return f; }
    private static void same(Spec s, String job, String input) { require(s.jobId().equals(job) && s.inputId().equals(input)); }
    private Spec spec(String job) throws IOException {
        require(Files.isDirectory(path(job), LinkOption.NOFOLLOW_LINKS)); var s = read(path(job).resolve("spec.json"), Spec.class); require(job.equals(s.jobId()));
        require(!(exists(job, "capture.json") && exists(job, "failure.json"))); return s;
    }
    private void recoveryMarker(String job) throws IOException { if (!exists(job, "recovery-started.json")) publish(job, "recovery-started.json", new RecoveryRecord(1, job, now())); }
    private LocalFileLease owner(String job) throws IOException { return LocalFileLease.tryAcquire(path(job).resolve("owner.lock")); }
    private boolean exists(String job, String name) throws IOException {
        try {
            var attributes = Files.readAttributes(path(job).resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); require(attributes.isRegularFile());
            if (name.equals("started.json")) require(job.equals(read(path(job).resolve(name), StartRecord.class).jobId()));
            if (name.equals("recovery-started.json")) require(job.equals(read(path(job).resolve(name), RecoveryRecord.class).jobId()));
            return true;
        }
        catch (NoSuchFileException e) { return false; }
    }
    private Path path(String job) { id(job); return root.resolve(job); }
    private String now() { return clock.instant().toString(); }
    private void publish(String job, String name, Object value) throws IOException {
        byte[] bytes = (GitHubJson.JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); require(bytes.length <= 65536);
        DurableExecution.publishMetadata(path(job).resolve(name), bytes);
    }
    private static <T> T read(Path path, Class<T> type) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var json = GitHubJson.parse(stream.readNBytes(65537), 65536); T record = GitHubJson.JSON.fromJson(json, type);
            require(GitHubJson.JSON.toJsonTree(record).equals(json)); return record;
        }
    }
    private static boolean terminal(State state) { return state != null && Set.of(State.REFRESHED, State.REFRESHED_WITH_GAPS, State.FAILED, State.CANCELLED, State.TIMED_OUT, State.INTERRUPTED).contains(state); }
    private static void id(String id) { require(id != null && id.matches("planjob-[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")); }
    private static void hash(String value) { require(value != null && value.matches("[0-9a-f]{64}")); }
    private static void time(String value) { Instant.parse(Objects.requireNonNull(value)); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid planning job state or scope"); }
    public static void main(String[] args) {
        if (args.length < 3) { System.err.println("Usage: MaintainerJobs create/status/cancel/recover/run/list/work JOURNAL STORE [INPUT_ID/JOB_ID] [HOST_SELECTION/MAXIMUM_JOBS]"); System.exit(2); return; }
        try {
            var jobs = new MaintainerJobs(Path.of(args[1]), new EvidenceStore(Path.of(args[2]))); String action = args[0]; Object result;
            switch (action) {
                case "create" -> { require(args.length == 4); result = jobs.create(args[3]); }
                case "status" -> { require(args.length == 4); result = jobs.status(args[3]); }
                case "cancel" -> { require(args.length == 4); result = jobs.cancel(args[3]); }
                case "recover" -> { require(args.length == 4); result = jobs.recover(args[3]); }
                case "run" -> { require(args.length == 4 || args.length == 5); result = jobs.run(args[3], args.length == 5 ? GitHubRepositoryConnection.loadSelection(Path.of(args[4])) : null); }
                case "list" -> { require(args.length == 3); result = jobs.list(); }
                case "work" -> { require(args.length == 5); result = jobs.work(GitHubRepositoryConnection.loadSelection(Path.of(args[3])), Integer.parseInt(args[4])); }
                default -> throw new IllegalArgumentException();
            }
            System.out.println(GitHubJson.JSON.toJson(result));
            if (result instanceof View view && needsAttention(view)
                    || result instanceof WorkerResult worker && (worker.busy() || worker.jobs().stream().anyMatch(MaintainerJobs::needsAttention))) System.exit(3);
        } catch (IOException | RuntimeException e) { System.err.println("Planning job operation could not complete. Check scope, job state, host App settings and retained input integrity."); System.exit(2); }
    }
    private static boolean needsAttention(View view) {
        return Set.of(State.FAILED, State.TIMED_OUT, State.INTERRUPTED, State.RECOVERY_REQUIRED,
                State.REFRESHED_WITH_GAPS, State.WORKER_BUSY, State.ACTIVE, State.CANCELLATION_REQUESTED).contains(view.state());
    }
}
