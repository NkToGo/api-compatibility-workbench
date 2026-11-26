package io.github.nktogo.apicompatibility.github;

import io.github.nktogo.apicompatibility.github.GitHubPullRequestLookup.Intent;
import io.github.nktogo.apicompatibility.runner.DurableExecution;
import io.github.nktogo.apicompatibility.runner.LocalFileLease;
import io.github.nktogo.apicompatibility.runner.RepairCommitBinding;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Local approval for a single draft repair PR operation. No publication transport. */
public final class RepairPublicationApproval {
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final String PERMISSION = "create-draft-repair-pull-request";
    private static final int RECORD_LIMIT = 8192;
    private final Path root;
    private final Clock clock;
    private final GitHubRepairRefs.RemoteCheck remote;

    public enum State { RECORDED, APPLICABLE, ALREADY_RECORDED, BUSY, UNAVAILABLE, INVALID, EXPIRED, REVOKED, STALE, UNCONFIRMED }
    public record Tip(String ref, String commitSha, String observedAt) {}
    public record ReviewedBranches(Tip base, Tip head, Tip baseRecheck) {}
    public record Approval(int formatVersion, String permission, String operationId, String bindingIdentity,
                           ReviewedBranches reviewedBranches, String issuedAt, String expiresAt) {}
    public record Report(State state, String operationId, Approval approval, GitHubRepairRefs.Report branchCheck) {}
    private record Stored(State blocker, Approval approval) {}

    RepairPublicationApproval(Path state, Clock clock, GitHubRepairRefs.RemoteCheck remote) {
        root = state.toAbsolutePath().normalize().resolve("repair-publications");
        this.clock = clock;
        this.remote = remote;
    }

    Report approve(Intent intent, RepairCommitBinding.Report local, String reviewedIdentity) throws IOException, InterruptedException {
        verifySelection(intent, local);
        String id = intent.operationId();
        Path directory = operation(id);
        Path file = directory.resolve("approval.json");
        if (exists(file) || exists(directory.resolve("approval.revoked")))
            return new Report(State.ALREADY_RECORDED, id, null, null);
        Instant started = clock.instant();
        var branches = GitHubRepairRefs.checkReviewed(local, reviewedIdentity, remote);
        var blocked = blockedState(branches);
        if (blocked != null) return new Report(blocked, id, null, branches);
        if (!reviewedIdentity.equals(branches.bindingIdentity()))
            return new Report(State.UNCONFIRMED, id, null, branches);
        var reviewed = reviewedBranches(branches);
        checkDirectory(root.getParent());
        ensureDirectory(root);
        ensureDirectory(directory);
        try (var lease = LocalFileLease.tryAcquire(directory.resolve("approval.lock"))) {
            if (lease == null) return new Report(State.BUSY, id, null, branches);
            if (exists(file) || exists(directory.resolve("approval.revoked")))
                return new Report(State.ALREADY_RECORDED, id, null, branches);
            Instant issued = clock.instant();
            if (!matches(reviewed, intent, started, issued))
                return new Report(State.UNCONFIRMED, id, null, branches);
            var approval = new Approval(1, PERMISSION, id, branches.bindingIdentity(), reviewed,
                    issued.toString(), issued.plus(LIFETIME).toString());
            byte[] bytes = (GitHubJson.JSON.toJson(approval) + "\n").getBytes(StandardCharsets.UTF_8);
            DurableExecution.publishMetadata(file, bytes);
            return new Report(State.RECORDED, id, approval, branches);
        }
    }

    Report check(Intent intent, RepairCommitBinding.Report local) throws IOException, InterruptedException {
        return check(intent, local, remote);
    }

    Report recorded(Intent intent, String bindingIdentity) throws IOException {
        String id = intent.operationId();
        var stored = read(operation(id), id);
        var approval = stored.approval();
        if (approval == null) return new Report(stored.blocker(), id, null, null);
        if (!bindingIdentity.equals(approval.bindingIdentity()))
            return new Report(State.STALE, id, null, null);
        if (!matches(approval.reviewedBranches(), intent, Instant.MIN, Instant.parse(approval.issuedAt())))
            return new Report(State.INVALID, id, null, null);
        return new Report(stored.blocker() == null ? State.RECORDED : stored.blocker(), id, approval, null);
    }

    Report check(Intent intent, RepairCommitBinding.Report local, GitHubRepairRefs.RemoteCheck branchCheck)
            throws IOException, InterruptedException {
        verifySelection(intent, local);
        String id = intent.operationId();
        Path directory = operation(id);
        var stored = read(directory, id);
        if (stored.blocker() != null) return new Report(stored.blocker(), id, stored.approval(), null);
        var approval = stored.approval();
        Instant started = clock.instant();
        if (approval.bindingIdentity().equals(local.bindingIdentity())
                && !matches(approval.reviewedBranches(), intent, Instant.MIN, Instant.parse(approval.issuedAt())))
            return new Report(State.INVALID, id, null, null);
        var branches = GitHubRepairRefs.checkReviewed(local, approval.bindingIdentity(), branchCheck);
        var after = read(directory, id);
        if (after.blocker() != null) return new Report(after.blocker(), id, approval, branches);
        if (!approval.equals(after.approval())) return new Report(State.INVALID, id, null, branches);
        var blocked = blockedState(branches);
        if (blocked != null) return new Report(blocked, id, approval, branches);
        var reviewed = reviewedBranches(branches);
        if (!approval.bindingIdentity().equals(branches.bindingIdentity()) || !matches(reviewed, intent, started, clock.instant()))
            return new Report(State.UNCONFIRMED, id, approval, branches);
        return new Report(State.APPLICABLE, id, approval, branches);
    }

    Report revoke(String id) throws IOException {
        Path directory = operation(id);
        if (!exists(directory.resolve("approval.json"))) return new Report(State.UNAVAILABLE, id, null, null);
        checkDirectories(directory);
        try (var marker = FileChannel.open(directory.resolve("approval.revoked"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            marker.force(true);
        } catch (FileAlreadyExistsException ignored) {
            // An existing marker keeps revocation in force.
        }
        return new Report(State.REVOKED, id, null, null);
    }

    State dispatchBlocker(Approval approval) throws IOException {
        var stored = read(operation(approval.operationId()), approval.operationId());
        if (stored.blocker() != null) return stored.blocker();
        return approval.equals(stored.approval()) ? null : State.INVALID;
    }

    private Stored read(Path directory, String id) throws IOException {
        Path file = directory.resolve("approval.json");
        if (!exists(file)) return new Stored(State.UNAVAILABLE, null);
        checkDirectories(directory);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Stored(State.INVALID, null);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var json = GitHubJson.parse(input.readNBytes(RECORD_LIMIT + 1), RECORD_LIMIT);
            var approval = GitHubJson.JSON.fromJson(json, Approval.class);
            GitHubJson.require(json.equals(GitHubJson.JSON.toJsonTree(approval)) && approval.formatVersion() == 1
                    && id.equals(approval.operationId()) && PERMISSION.equals(approval.permission())
                    && approval.bindingIdentity().matches("[0-9a-f]{64}"));
            Instant now = clock.instant(), issued = Instant.parse(approval.issuedAt()), expires = Instant.parse(approval.expiresAt());
            GitHubJson.require(expires.equals(issued.plus(LIFETIME))
                    && ordered(approval.reviewedBranches(), Instant.MIN, issued) && !now.isBefore(issued));
            if (exists(directory.resolve("approval.revoked"))) return new Stored(State.REVOKED, approval);
            if (!now.isBefore(expires)) return new Stored(State.EXPIRED, approval);
            return new Stored(null, approval);
        } catch (RuntimeException invalid) {
            return new Stored(State.INVALID, null);
        }
    }

    private static State blockedState(GitHubRepairRefs.Report report) {
        return switch (report.state()) {
            case MATCHED -> null;
            case BLOCKED -> State.STALE;
            case INCOMPLETE -> State.UNCONFIRMED;
        };
    }

    private static ReviewedBranches reviewedBranches(GitHubRepairRefs.Report branches) {
        return new ReviewedBranches(tip(branches.base()), tip(branches.head()), tip(branches.baseRecheck()));
    }

    private static Tip tip(GitHubRepairRefs.Observation observed) {
        return new Tip(observed.ref(), observed.observedSha(), observed.observedAt().toString());
    }

    private static boolean matches(ReviewedBranches review, Intent intent, Instant start, Instant end) {
        return matchesTip(review.base(), intent.baseRef(), intent.selection().commitSha())
                && matchesTip(review.head(), intent.headRef(), intent.headSha())
                && matchesTip(review.baseRecheck(), intent.baseRef(), intent.selection().commitSha())
                && ordered(review, start, end);
    }

    private static boolean matchesTip(Tip tip, String branch, String sha) {
        return ("refs/heads/" + branch).equals(tip.ref()) && sha.equals(tip.commitSha());
    }

    private static boolean ordered(ReviewedBranches review, Instant start, Instant end) {
        Instant base = Instant.parse(review.base().observedAt()), head = Instant.parse(review.head().observedAt());
        Instant recheck = Instant.parse(review.baseRecheck().observedAt());
        return !base.isBefore(start) && !head.isBefore(base) && !recheck.isBefore(head) && !end.isBefore(recheck);
    }

    private static void verifySelection(Intent intent, RepairCommitBinding.Report local) {
        GitHubJson.require(local.state() != RepairCommitBinding.State.MATCHED || intent.equals(local.proof().publication().intent()));
    }

    Path operation(String id) {
        GitHubJson.require(id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        return root.resolve(id);
    }

    void checkDirectories(Path directory) throws IOException {
        checkDirectory(root.getParent());
        checkDirectory(root);
        checkDirectory(directory);
    }

    private static void ensureDirectory(Path path) throws IOException {
        try { Files.createDirectory(path); }
        catch (FileAlreadyExistsException ignored) { /* Validate the existing directory before using it. */ }
        checkDirectory(path);
    }

    private static void checkDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Unsafe approval directory");
    }

    static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException missing) { return false; }
    }

    public static void main(String[] args) {
        try {
            GitHubJson.require(args.length >= 3);
            var approvals = new RepairPublicationApproval(Path.of(args[1]), Clock.systemUTC(), GitHubRepairRefs::readWithConfiguredApp);
            Report report;
            if (args[0].equals("revoke")) {
                GitHubJson.require(args.length == 3);
                report = approvals.revoke(args[2]);
            } else {
                boolean approve = args[0].equals("approve");
                GitHubJson.require(approve ? args.length == 7 : args[0].equals("check") && args.length == 6);
                var intent = GitHubPullRequestLookup.loadIntent(Path.of(args[4]));
                var local = RepairCommitBinding.check(Path.of(args[1]), args[2], Path.of(args[3]), intent, args[5]);
                report = approve ? approvals.approve(intent, local, args[6]) : approvals.check(intent, local);
            }
            System.out.println(GitHubJson.JSON.toJson(report));
            if (report.state() != State.RECORDED && report.state() != State.APPLICABLE
                    && !(args[0].equals("revoke") && report.state() == State.REVOKED)) System.exit(3);
        } catch (IOException | RuntimeException e) {
            System.err.println("Repair publication approval could not be processed. Check the selection, local store and App configuration.");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Repair publication approval check interrupted.");
            System.exit(3);
        }
    }
}
