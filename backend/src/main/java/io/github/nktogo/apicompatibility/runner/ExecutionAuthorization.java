package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** Local operator permission for one fixed attempt, not a provider credential or bearer token. */
final class ExecutionAuthorization {
    enum Decision { ALLOWED, EXPIRED, REVOKED, UNAVAILABLE, INVALID }
    private final Path directory;
    private final String runId, image, mode;
    private final Clock clock;

    ExecutionAuthorization(Path directory, String runId, String image, String mode, Clock clock) {
        this.directory = directory; this.runId = runId; this.image = image; this.mode = mode; this.clock = clock;
    }

    void issue(Duration lifetime) throws IOException {
        CaseInputs.require(lifetime.toMillis() > 0 && lifetime.compareTo(Duration.ofHours(1)) <= 0, "Invalid authorization lifetime");
        CaseInputs.require(!Files.exists(directory.resolve("authorization.json"), LinkOption.NOFOLLOW_LINKS), "An existing authorization cannot be renewed");
        Instant now = clock.instant();
        DurableExecution.publish(directory.resolve("authorization.json"), Map.of("formatVersion", 1,
                "runId", runId, "imageId", image, "mode", mode, "permission", "execute-curated-case",
                "issuedAt", now.toString(), "expiresAt", now.plus(lifetime).toString()));
    }

    void revoke() throws IOException {
        try (var file = FileChannel.open(directory.resolve("authorization.revoked"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
        catch (FileAlreadyExistsException ignored) { /* Revocation is permanent and idempotent. */ }
    }

    Decision decision() {
        if (Files.exists(directory.resolve("authorization.revoked"), LinkOption.NOFOLLOW_LINKS)) return Decision.REVOKED;
        Path file = directory.resolve("authorization.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Decision.UNAVAILABLE;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) return Decision.INVALID;
            var grant = CaseInputs.object(bytes);
            if (grant.get("formatVersion").getAsInt() != 1 || !runId.equals(grant.get("runId").getAsString())
                    || !image.equals(grant.get("imageId").getAsString()) || !mode.equals(grant.get("mode").getAsString())
                    || !"execute-curated-case".equals(grant.get("permission").getAsString())) return Decision.INVALID;
            Instant issued = Instant.parse(grant.get("issuedAt").getAsString());
            Instant expires = Instant.parse(grant.get("expiresAt").getAsString());
            Instant now = clock.instant();
            if (!expires.isAfter(issued) || Duration.between(issued, expires).compareTo(Duration.ofHours(1)) > 0
                    || now.isBefore(issued)) return Decision.INVALID;
            return now.isBefore(expires) ? Decision.ALLOWED : Decision.EXPIRED;
        } catch (IOException e) { return Decision.UNAVAILABLE; }
        catch (RuntimeException e) { return Decision.INVALID; }
    }
}
