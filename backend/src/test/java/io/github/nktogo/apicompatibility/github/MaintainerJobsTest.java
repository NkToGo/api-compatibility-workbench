package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.runner.EvidenceStore;
import io.github.nktogo.apicompatibility.runner.LocalFileLease;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnectionTest.*;
import static io.github.nktogo.apicompatibility.github.MaintainerJobs.*;
import static org.junit.jupiter.api.Assertions.*;

class MaintainerJobsTest {
    @TempDir Path temporary;
    static final GitHubSourceInventory.Plan POMS = new GitHubSourceInventory.Plan(1, List.of("pom.xml", "webhook/pom.xml", "reconciliation/pom.xml"));
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final class Crash extends Error { private static final long serialVersionUID = 1L; }
    @BeforeAll static void keys() throws Exception { GitHubSourceInventoryTest.key(); }
    static GitHubSourceInventory.Capture capture(Instant observed, GitHubSourceInventory.CancellationCheck control, java.util.function.Consumer<Fixture> setup)
            throws IOException, InterruptedException, GitHubRepositoryConnection.ConnectionFailure {
        Fixture created;
        try { created = new Fixture(); } catch (Exception e) { throw new IOException("Fixture server failed", e); }
        try (var fixture = created) {
            var root = new GitHubSourceInventoryTest.Directory(); root.files.put("pom.xml", GitHubSourceInventoryTest.POM);
            root.directory("webhook").files.put("pom.xml", GitHubSourceInventoryTest.POM);
            root.directory("reconciliation").files.put("pom.xml", GitHubSourceInventoryTest.POM);
            try { GitHubSourceInventoryTest.install(fixture, root); } catch (Exception e) { throw new IOException("Fixture failed", e); }
            setup.accept(fixture);
            try (var transport = new GitHubTransport(fixture.origin(), Duration.ofSeconds(5), control)) {
                return new GitHubSourceInventory(Clock.fixed(observed, ZoneOffset.UTC)).capture(SELECTION, GitHubSourceInventoryTest.sourceCredentials, POMS, transport);
            }
        }
    }
    static String input(EvidenceStore store) throws Exception {
        var captured = capture(NOW, () -> {}, fixture -> {});
        return MigrationInputCapture.retain(store, captured, MigrationConfigurationTest.plan(captured.inventory())).summary().inputId();
    }
    MaintainerJobs jobs(Path journal, EvidenceStore store, AtomicInteger calls) throws IOException {
        return new MaintainerJobs(journal, store, CLOCK, (selection, plan, control) -> {
            calls.incrementAndGet(); assertEquals(SELECTION, selection); assertEquals(POMS, plan);
            return capture(NOW.plusSeconds(10), control, fixture -> {});
        }, (stage, id) -> {});
    }
    @Test void queuedJobPinsInputAndRefreshesOnceWithAnUnprivilegedOutput() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var calls = new AtomicInteger(); var journal = temporary.resolve("jobs");
        var jobs = jobs(journal, store, calls); var created = jobs.create(input(store)); assertEquals(State.QUEUED, created.state());
        var result = jobs.run(created.jobId(), SELECTION); assertEquals(State.REFRESHED, result.state()); assertFalse(result.executionAuthorized());
        assertFalse(result.output().executionAuthorized()); assertNotEquals(result.inputId(), result.outputInputId());
        assertEquals(2, store.listInputs().size()); assertEquals(1, calls.get());
        byte[] bytes = Files.readAllBytes(journal.resolve(created.jobId()).resolve("result.json"));
        var reopened = jobs(journal, store, calls); assertEquals(result, reopened.status(created.jobId()));
        assertEquals(result, reopened.run(created.jobId(), null)); assertEquals(result, reopened.recover(created.jobId()));
        assertEquals(result, reopened.cancel(created.jobId())); assertEquals(1, calls.get());
        assertArrayEquals(bytes, Files.readAllBytes(journal.resolve(created.jobId()).resolve("result.json")));
    }
    @Test void wrongHostScopeAndChangedInputsCannotDispatchOrPoisonAQueuedJob() throws Exception {
        var root = temporary.resolve("store"); var store = new EvidenceStore(root); var calls = new AtomicInteger(); var jobs = jobs(temporary.resolve("jobs"), store, calls);
        var created = jobs.create(input(store)); var wrong = new GitHubRepositoryConnection.Selection(1, 20, 30, 40, "sample-owner", "billing", "c".repeat(40));
        assertThrows(IllegalArgumentException.class, () -> jobs.run(created.jobId(), wrong)); assertEquals(State.QUEUED, jobs.status(created.jobId()).state());
        assertTrue(jobs.work(wrong, 1).jobs().isEmpty()); assertEquals(0, calls.get());
        Path manifest = root.resolve("objects").resolve(created.inputId()); byte[] original = Files.readAllBytes(manifest); Files.writeString(manifest, "damaged");
        assertThrows(IllegalArgumentException.class, () -> jobs.run(created.jobId(), SELECTION)); assertEquals(0, calls.get());
        Files.write(manifest, original); assertEquals(State.REFRESHED, jobs.run(created.jobId(), SELECTION).state());
    }
    @Test void queuedCancellationNeedsNoAppConfigurationOrSourceReads() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var calls = new AtomicInteger(); var jobs = jobs(temporary.resolve("jobs"), store, calls);
        var job = jobs.create(input(store)); var cancelled = jobs.cancel(job.jobId());
        assertEquals(State.CANCELLED, cancelled.state()); assertEquals(Reason.CANCELLED_BEFORE_START, cancelled.reason());
        assertFalse(cancelled.tokenMayRemainActive()); assertEquals(cancelled, jobs.run(job.jobId(), null)); assertEquals(0, calls.get());
    }
    @Test void activeCancellationStopsAtAnActualHttpBoundaryWithoutReadingTheCommit() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var ref = new AtomicReference<MaintainerJobs>(); var id = new AtomicReference<String>(); var requests = new AtomicInteger();
        var jobs = new MaintainerJobs(temporary.resolve("jobs"), store, CLOCK, (selection, plan, control) ->
                capture(NOW.plusSeconds(10), control, fixture -> fixture.afterScope = () -> {
                    requests.set(fixture.requests.size());
                    try { assertEquals(State.CANCELLATION_REQUESTED, ref.get().cancel(id.get()).state()); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }), (stage, job) -> {});
        ref.set(jobs); id.set(jobs.create(input(store)).jobId()); var result = jobs.run(id.get(), SELECTION);
        assertEquals(State.CANCELLED, result.state()); assertEquals(Reason.CANCELLED_DURING_READ, result.reason());
        assertEquals(4, requests.get()); assertTrue(result.tokenMayRemainActive()); assertEquals(1, store.listInputs().size());
    }
    @Test void oneWorkerLeaseBoundsConcurrentRunAndWorkProcesses() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var journal = temporary.resolve("jobs"); var calls = new AtomicInteger(); var jobs = jobs(journal, store, calls);
        var job = jobs.create(input(store));
        try (var lease = LocalFileLease.tryAcquire(journal.resolve("worker.lock"))) {
            assertNotNull(lease); assertEquals(State.WORKER_BUSY, jobs.run(job.jobId(), SELECTION).state());
            assertTrue(jobs.work(SELECTION, 1).busy()); assertEquals(State.QUEUED, jobs.status(job.jobId()).state()); assertEquals(0, calls.get());
        }
        assertEquals(State.REFRESHED, jobs.run(job.jobId(), SELECTION).state());
    }
    @Test void observingAnActiveJobCannotReleaseItsOwnerLease() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var jobs = new MaintainerJobs(temporary.resolve("jobs"), store, CLOCK, (selection, plan, control) -> {
            calls.incrementAndGet(); entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture wait expired");
            return capture(NOW.plusSeconds(10), control, fixture -> {});
        }, (stage, id) -> {});
        var job = jobs.create(input(store));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> jobs.run(job.jobId(), SELECTION));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); assertEquals(State.ACTIVE, jobs.status(job.jobId()).state());
                assertEquals(State.ACTIVE, jobs.recover(job.jobId()).state()); assertEquals(State.WORKER_BUSY, jobs.run(job.jobId(), SELECTION).state());
            } finally { release.countDown(); }
            assertEquals(State.REFRESHED, running.get(10, TimeUnit.SECONDS).state()); assertEquals(1, calls.get());
        }
    }
    @Test void boundedWorkerDrainsQueuedJobsAndLeavesOthersQueued() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var calls = new AtomicInteger(); var jobs = jobs(temporary.resolve("jobs"), store, calls); String input = input(store);
        for (int i = 0; i < 4; i++) jobs.create(input); var order = jobs.list().stream().map(View::jobId).toList();
        var worker = jobs.work(SELECTION, 2); assertFalse(worker.busy()); assertEquals(order.subList(0, 2), worker.jobs().stream().map(View::jobId).toList());
        assertEquals(2, calls.get()); assertEquals(2, jobs.list().stream().filter(v -> v.state() == State.QUEUED).count());
        assertThrows(IllegalArgumentException.class, () -> jobs.work(SELECTION, 0)); assertThrows(IllegalArgumentException.class, () -> jobs.work(SELECTION, 11));
    }
    @Test void providerFailureAndPartialRateLimitsAreRetainedWithoutAutomaticQueueRetries() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var calls = new AtomicInteger(); String input = input(store);
        var failed = new MaintainerJobs(temporary.resolve("failed"), store, CLOCK, (selection, plan, control) -> {
            calls.incrementAndGet(); return capture(NOW.plusSeconds(10), control, fixture -> { fixture.faultPath = "/app"; fixture.faultStatus = 401; });
        }, (stage, id) -> {});
        var result = failed.run(failed.create(input).jobId(), SELECTION);
        assertEquals(State.FAILED, result.state()); assertEquals(Reason.PROVIDER_FAILURE, result.reason()); assertEquals(401, result.providerFailure().httpStatus());
        assertFalse(result.tokenMayRemainActive()); assertEquals(result, failed.run(result.jobId(), null)); assertEquals(1, calls.get());
        calls.set(0);
        var limited = new MaintainerJobs(temporary.resolve("limited"), store, CLOCK, (selection, plan, control) -> {
            calls.incrementAndGet(); return capture(NOW.plusSeconds(10), control, fixture -> {
                fixture.faultPath = GitHubSourceInventoryTest.blobPath(GitSourceObjects.gitHash("blob", GitHubSourceInventoryTest.POM));
                fixture.faultStatus = 429; fixture.faultHeaders.put("Retry-After", "120");
            });
        }, (stage, id) -> {});
        limited.create(input); limited.create(input); var processed = limited.work(SELECTION, 2);
        assertEquals(1, processed.jobs().size()); assertEquals(1, calls.get()); var partial = processed.jobs().getFirst();
        assertEquals(State.REFRESHED_WITH_GAPS, partial.state()); assertEquals(429, partial.providerFailure().httpStatus());
        assertEquals(NOW.plusSeconds(130).toString(), partial.providerFailure().retryNotBefore());
        assertEquals(1, limited.list().stream().filter(v -> v.state() == State.QUEUED).count());
    }
    @Test void jobDeadlineAndThreadInterruptionRemainDifferentTerminalOutcomes() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); String input = input(store); var clock = new MutableClock();
        var timed = new MaintainerJobs(temporary.resolve("timed"), store, clock, (selection, plan, control) -> { clock.value = NOW.plusSeconds(181); control.check(); throw new AssertionError(); }, (stage, id) -> {});
        var result = timed.run(timed.create(input).jobId(), SELECTION); assertEquals(State.TIMED_OUT, result.state()); assertEquals(Reason.DEADLINE_EXCEEDED, result.reason());
        var interrupted = new MaintainerJobs(temporary.resolve("interrupted"), store, CLOCK, (selection, plan, control) -> { throw new InterruptedException(); }, (stage, id) -> {});
        try {
            var stopped = interrupted.run(interrupted.create(input).jobId(), SELECTION); assertEquals(State.INTERRUPTED, stopped.state()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @ParameterizedTest @ValueSource(strings = {"started", "prepared", "published", "finalizing"})
    void interruptedStagesRecoverLocallyWithoutRepeatingTheSourceRead(String stage) throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var journal = temporary.resolve("jobs"); var calls = new AtomicInteger();
        var crashed = new AtomicBoolean();
        var jobs = new MaintainerJobs(journal, store, CLOCK, (selection, plan, control) -> { calls.incrementAndGet(); return capture(NOW.plusSeconds(10), control, fixture -> {}); },
                (at, id) -> { if (at.equals(stage) && crashed.compareAndSet(false, true)) throw new Crash(); });
        var job = jobs.create(input(store)); assertThrows(Crash.class, () -> jobs.run(job.jobId(), SELECTION));
        assertEquals(State.RECOVERY_REQUIRED, jobs.status(job.jobId()).state()); assertEquals(State.RECOVERY_REQUIRED, jobs.run(job.jobId(), SELECTION).state());
        int originalCalls = calls.get(); var reopened = jobs(journal, store, calls); var result = reopened.recover(job.jobId());
        assertEquals(Set.of("published", "finalizing").contains(stage) ? State.REFRESHED : State.INTERRUPTED, result.state());
        assertTrue(result.recovered()); assertEquals(originalCalls, calls.get()); assertEquals(result, reopened.recover(job.jobId())); assertEquals(result, reopened.run(job.jobId(), null));
    }
    @Test void damagedPublishedOutputBlocksRecoveryUntilTheExactBytesAreRestored() throws Exception {
        var root = temporary.resolve("store"); var store = new EvidenceStore(root); var journal = temporary.resolve("jobs"); var calls = new AtomicInteger();
        var jobs = new MaintainerJobs(journal, store, CLOCK, (selection, plan, control) -> { calls.incrementAndGet(); return capture(NOW.plusSeconds(10), control, fixture -> {}); },
                (at, id) -> { if (at.equals("published")) throw new Crash(); });
        var job = jobs.create(input(store)); assertThrows(Crash.class, () -> jobs.run(job.jobId(), SELECTION));
        String output = jobs.status(job.jobId()).pendingOutputInputId(); Path object = root.resolve("objects").resolve(output); byte[] original = Files.readAllBytes(object); Files.writeString(object, "damaged");
        var reopened = jobs(journal, store, calls); assertThrows(IllegalArgumentException.class, () -> reopened.recover(job.jobId()));
        assertFalse(Files.exists(journal.resolve(job.jobId()).resolve("result.json"))); assertEquals(1, calls.get());
        Files.write(object, original); assertEquals(State.REFRESHED, reopened.recover(job.jobId()).state()); assertEquals(1, calls.get());
    }
    @Test void recoveryPreservesARecordedProviderFailureWithoutAnotherRead() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var journal = temporary.resolve("jobs"); var calls = new AtomicInteger();
        var jobs = new MaintainerJobs(journal, store, CLOCK, (selection, plan, control) -> {
            calls.incrementAndGet(); return capture(NOW.plusSeconds(10), control, fixture -> { fixture.faultPath = "/app"; fixture.faultStatus = 401; });
        }, (at, id) -> { if (at.equals("finalizing")) throw new Crash(); });
        var job = jobs.create(input(store)); assertThrows(Crash.class, () -> jobs.run(job.jobId(), SELECTION));
        var reopened = jobs(journal, store, calls); var recovered = reopened.recover(job.jobId());
        assertEquals(State.FAILED, recovered.state()); assertEquals(Reason.PROVIDER_FAILURE, recovered.reason());
        assertEquals(401, recovered.providerFailure().httpStatus()); assertTrue(recovered.recovered()); assertEquals(1, calls.get());
        assertEquals(recovered, reopened.run(job.jobId(), null)); assertEquals(1, calls.get());
    }
    @Test void cancellationAfterPublicationKeepsTheCompletedObservationAndItsRequest() throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var journal = temporary.resolve("jobs"); var calls = new AtomicInteger();
        var observer = jobs(journal, store, calls);
        var jobs = new MaintainerJobs(journal, store, CLOCK, (selection, plan, control) -> {
            calls.incrementAndGet(); return capture(NOW.plusSeconds(10), control, fixture -> {});
        }, (at, id) -> { if (at.equals("published")) assertEquals(State.CANCELLATION_REQUESTED, observer.cancel(id).state()); });
        var job = jobs.create(input(store)); var completed = jobs.run(job.jobId(), SELECTION);
        assertEquals(State.REFRESHED, completed.state()); assertTrue(completed.cancellationRequested()); assertEquals(1, calls.get());
        assertEquals(completed, observer.cancel(job.jobId())); assertEquals(completed, observer.recover(job.jobId()));
    }
    @ParameterizedTest @ValueSource(strings = {"kind", "deadline", "job-id", "extra-field", "duplicate-key"})
    void malformedRegistrationCannotStartARead(String changed) throws Exception {
        var store = new EvidenceStore(temporary.resolve("store")); var journal = temporary.resolve("jobs"); var calls = new AtomicInteger(); var jobs = jobs(journal, store, calls);
        var job = jobs.create(input(store)); Path path = journal.resolve(job.jobId()).resolve("spec.json"); var spec = GitHubJson.parse(Files.readAllBytes(path), 65536);
        switch (changed) {
            case "kind" -> spec.addProperty("kind", "execute-repository"); case "deadline" -> spec.addProperty("deadlineSeconds", 181);
            case "job-id" -> spec.addProperty("jobId", "../outside"); case "extra-field" -> spec.addProperty("credential", "PRIVATE_VALUE");
            case "duplicate-key" -> { Files.writeString(path, "{\"jobId\":\"a\",\"jobId\":\"b\"}"); assertThrows(RuntimeException.class, () -> jobs.run(job.jobId(), SELECTION)); assertEquals(0, calls.get()); return; }
            default -> throw new AssertionError();
        }
        Files.writeString(path, spec.toString()); assertThrows(RuntimeException.class, () -> jobs.run(job.jobId(), SELECTION)); assertEquals(0, calls.get());
    }
    @Test void aKilledWorkerReleasesItsLeaseAndRecoveryNeverReplaysItsUnknownRead() throws Exception {
        var storeRoot = temporary.resolve("store"); var store = new EvidenceStore(storeRoot); var journal = temporary.resolve("jobs"); var jobs = jobs(journal, store, new AtomicInteger());
        var job = jobs.create(input(store)); Path marker = temporary.resolve("entered");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Controller.class.getName(), journal.toString(), storeRoot.toString(), job.jobId(), marker.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("child.log").toFile()).start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); while (!Files.exists(marker) && System.nanoTime() < end) Thread.sleep(20);
            assertTrue(Files.exists(marker)); assertEquals(State.ACTIVE, jobs.status(job.jobId()).state());
            process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            // Windows may report process exit before its kernel file handles finish closing.
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jobs.status(job.jobId()).state() == State.ACTIVE && System.nanoTime() < end) Thread.sleep(20);
            assertEquals(State.RECOVERY_REQUIRED, jobs.status(job.jobId()).state()); var recovered = jobs.recover(job.jobId());
            assertEquals(State.INTERRUPTED, recovered.state()); assertEquals(Reason.LOST_READ_OUTCOME, recovered.reason()); assertTrue(recovered.tokenMayRemainActive());
            assertEquals(recovered, jobs.run(job.jobId(), null)); assertEquals("entered", Files.readString(marker));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
    }
    public static final class Controller {
        public static void main(String[] args) throws Exception {
            var jobs = new MaintainerJobs(Path.of(args[0]), new EvidenceStore(Path.of(args[1])), CLOCK,
                    (selection, plan, control) -> { control.check(); Files.writeString(Path.of(args[3]), "entered", StandardOpenOption.CREATE_NEW); Thread.sleep(30000); throw new IOException("Fixture wait ended"); }, (at, id) -> {});
            jobs.run(args[2], SELECTION);
        }
    }
}
