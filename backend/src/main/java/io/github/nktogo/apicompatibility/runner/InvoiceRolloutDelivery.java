package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** One authorized synthetic reconciliation delivery using the matching captured rollout. */
public final class InvoiceRolloutDelivery {
    private static final String POLICY = "invoice-rollout-delivery-v1";
    private static final String CORPUS = "rollout-v1";
    private static final String ROLE = "invoice-reconciliation";
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Duration AUTHORIZATION = Duration.ofMinutes(2);

    public record Scope(int formatVersion, String policy, String runId, String preflightIdentity,
                        String inputId, String captureId, InvoiceCapturePreflight.Phase phase, String scenarioId,
                        InvoiceCapturePreflight.HostCase hostCase, InvoiceCapturePreflight.Delivery delivery,
                        String imageId, String runtimeIdentity, long deadlineSeconds, long authorizationSeconds) {}
    public record Preview(String scopeIdentity, Scope scope) {}
    public record Result(String scopeIdentity, String runId, String status, String assertionOutcome,
                         String authorizationDecision, Boolean containerRemoved) {}
    private record Prepared(Preview preview, InvoiceCaseCoverage.Plan plan, InvoiceBusinessCoverage.Entry entry) {}
    private final EvidenceStore store;
    private final Path caseRoot;
    private final Path runs;

    public InvoiceRolloutDelivery(EvidenceStore store, Path caseRoot, Path runs) {
        this.store = store;
        this.caseRoot = caseRoot;
        this.runs = runs.toAbsolutePath().normalize();
    }

    public Preview preview(String input, String capture, InvoiceCapturePreflight.Phase phase, String image, String runId) throws IOException {
        return prepare(input, capture, phase, image, runId).preview();
    }

    private Prepared prepare(String input, String capture, InvoiceCapturePreflight.Phase phase, String image, String runId) throws IOException {
        requireRunId(runId);
        CaseInputs.require(phase != null && image != null && image.matches("sha256:[0-9a-f]{64}"), "Invalid phase or worker image");
        var checked = InvoiceCapturePreflight.check(store, caseRoot, input, capture);
        var report = checked.report();
        CaseInputs.require(report.assessment().state() == InvoiceCapturePreflight.State.MATCHES_REGISTERED_ROLLOUT, "Invoice preflight is blocked");
        var match = report.assessment().matches().stream().filter(m -> m.phase() == phase).findFirst().orElseThrow();
        var delivery = match.deliveries().stream().filter(d -> d.consumerId().equals(ROLE)).findFirst().orElseThrow();
        var plan = checked.plan().business();
        var entry = plan.entries().stream().filter(e -> e.corpus().equals(CORPUS) && e.scenario().equals(match.scenarioId())
                && e.delivery().equals(delivery.deliveryId()) && e.consumer().equals(ROLE)).findFirst().orElseThrow();
        CaseInputs.require(!entry.expectedBlock() && entry.buildId().equals(delivery.buildId())
                && entry.evidenceIdentity().equals(delivery.evidenceIdentity())
                && InvoiceRenewalOracle.inputIdentity(entry.input()).equals(delivery.inputIdentity()), "Registered delivery differs from preflight");

        var runtime = new TreeMap<String, String>();
        runtime.put("invoiceSandbox", InvoiceSandbox.harnessIdentity());
        for (Class<?> type : new Class<?>[]{InvoiceRolloutDelivery.class, InvoiceCapturePreflight.class,
                InvoiceBusinessCoverage.class, InvoiceConsumerBuilds.class, ExecutionAuthorization.class, ExecutionGuard.class}) {
            InvoiceSandbox.captureClass(type, runtime);
        }
        var scope = new Scope(1, POLICY, runId, report.preflightIdentity(), input, capture, phase, match.scenarioId(),
                report.assessment().hostCase(), delivery, image, CaseInputs.identity(runtime), DEADLINE.toSeconds(), AUTHORIZATION.toSeconds());
        return new Prepared(new Preview(CaseInputs.identity(scope), scope), checked.plan(), entry);
    }

    public Result run(String input, String capture, InvoiceCapturePreflight.Phase phase, String image, String runId, String approvedScope) throws IOException {
        var prepared = prepare(input, capture, phase, image, runId);
        var preview = prepared.preview();
        CaseInputs.require(preview.scopeIdentity().equals(approvedScope), "Explicit approval of the current scope is required");
        Files.createDirectories(runs);
        ConsumerTasks.directory(runs);
        // This create-only boundary consumes the attempt ID even if the controller stops before writing a result.
        Path directory = Files.createDirectory(runs.resolve(runId));
        var authorization = new ExecutionAuthorization(directory, runId, image, POLICY + ":" + approvedScope, Clock.systemUTC());
        DurableExecution.publish(directory.resolve("scope.json"), preview);
        authorization.issue(AUTHORIZATION);
        var guard = new ExecutionGuard(() -> Thread.currentThread().isInterrupted(), authorization);
        if (guard.getAsBoolean()) return save(directory, preview, guard, guard.stopStatus(), null);
        var entry = prepared.entry();
        var host = new InvoiceRenewalAdmission.HostEvidence(entry.corpusIdentity(), preview.scope().delivery().inputIdentity(),
                entry.evidenceIdentity(), prepared.plan().business().source(entry), InvoiceRenewalAdmission.Verification.NOT_APPLICABLE,
                null, InvoiceRenewalAdmission.Verification.NOT_APPLICABLE, null);
        var admission = InvoiceRenewalAdmission.inspect(ROLE, entry.input(), entry.expected(), host);
        DurableExecution.publish(directory.resolve("admission.json"), admission.json());
        if (!admission.admitted()) return save(directory, preview, guard, "FIXTURE_ADMISSION_BLOCKED", null);
        if (guard.getAsBoolean()) return save(directory, preview, guard, guard.stopStatus(), null);

        String workerName = "acw-" + UUID.randomUUID();
        DurableExecution.publish(directory.resolve("started.json"), Map.of("startedAt", Instant.now().toString(), "containerName", workerName));
        InvoiceConsumerExecution execution;
        try {
            execution = InvoiceSandbox.runObserved(prepared.plan().builds(), entry.buildId(), admission, image, workerName, DEADLINE, guard);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return save(directory, preview, guard, "INTERRUPTED_CLEANUP_UNCONFIRMED", null);
        } catch (Exception e) {
            return save(directory, preview, guard, "EXECUTOR_ERROR_CLEANUP_UNCONFIRMED", null);
        }
        boolean stopped = guard.getAsBoolean();
        String status = execution.evidence().get("containerRemoved").getAsBoolean()
                ? (stopped ? guard.stopStatus() : execution.observation().executionStatus()) : "CLEANUP_UNCONFIRMED";
        return save(directory, preview, guard, status, execution);
    }

    private static Result save(Path directory, Preview preview, ExecutionGuard guard, String status, InvoiceConsumerExecution execution) throws IOException {
        JsonObject evidence = execution == null ? null : execution.evidence();
        String assertion = execution == null || !status.equals("COMPLETED")
                ? "NOT_EVALUATED" : execution.observation().assertion().name();
        Boolean removed = evidence == null ? null : evidence.get("containerRemoved").getAsBoolean();
        var result = new Result(preview.scopeIdentity(), preview.scope().runId(), status, assertion, guard.authorizationDecision(), removed);
        var receipt = CaseInputs.JSON.toJsonTree(result).getAsJsonObject();
        if (evidence != null) receipt.add("protectedExecution", evidence);
        boolean interrupted = Thread.interrupted();
        try {
            DurableExecution.publish(directory.resolve("result.json"), receipt);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return result;
    }

    public void revoke(String runId) throws IOException {
        requireRunId(runId);
        ConsumerTasks.directory(runs.resolve(runId));
        CaseInputs.require(Files.isRegularFile(runs.resolve(runId).resolve("scope.json"), LinkOption.NOFOLLOW_LINKS), "Missing registered rollout attempt");
        ExecutionAuthorization.revoke(runs.resolve(runId));
    }

    private static void requireRunId(String runId) {
        CaseInputs.require(runId != null && runId.matches("acw-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid rollout attempt ID");
    }

    public static void main(String[] args) {
        try {
            CaseInputs.require(args.length >= 5, "Missing rollout arguments");
            var runner = new InvoiceRolloutDelivery(new EvidenceStore(Path.of(args[1])), Path.of(args[2]), Path.of(args[3]));
            if (args[0].equals("revoke") && args.length == 5) {
                runner.revoke(args[4]);
                System.out.println("Revocation recorded.");
                return;
            }
            boolean preview = args[0].equals("preview") && args.length == 9;
            CaseInputs.require(preview || args[0].equals("run") && args.length == 10, "Invalid rollout arguments");
            var phase = InvoiceCapturePreflight.Phase.valueOf(args[7]);
            if (preview) {
                System.out.println(CaseInputs.JSON.toJson(runner.preview(args[4], args[5], phase, args[8], args[6])));
            } else {
                var result = runner.run(args[4], args[5], phase, args[8], args[6], args[9]);
                System.out.println(CaseInputs.JSON.toJson(result));
                if (!result.status().equals("COMPLETED") || !result.assertionOutcome().equals("PASSED")) System.exit(3);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Rollout operation failed. Check preflight, scope approval, attempt ID and local evidence.");
            System.exit(2);
        }
    }
}
