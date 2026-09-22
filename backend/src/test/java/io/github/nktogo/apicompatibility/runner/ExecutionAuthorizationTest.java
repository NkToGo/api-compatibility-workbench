package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionAuthorizationTest {
    @TempDir Path root;
    private static final String IMAGE = "sha256:" + "0".repeat(64);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private ExecutionAuthorization authority(Instant time) {
        return new ExecutionAuthorization(root, "attempt", IMAGE, "case", Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test void expirationIsExclusiveAndClockRollbackFailsClosed() throws Exception {
        authority(NOW).issue(Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> authority(NOW).issue(Duration.ofMinutes(1)));
        assertEquals(ExecutionAuthorization.Decision.ALLOWED, authority(NOW.plusSeconds(9)).decision());
        assertEquals(ExecutionAuthorization.Decision.EXPIRED, authority(NOW.plusSeconds(10)).decision());
        assertEquals(ExecutionAuthorization.Decision.INVALID, authority(NOW.minusSeconds(1)).decision());
    }

    @Test void scopeCannotBeReusedForAnotherAttemptImageOrMode() throws Exception {
        authority(NOW).issue(Duration.ofMinutes(1));
        for (String[] scope : new String[][] { {"other", IMAGE, "case"}, {"attempt", "sha256:" + "1".repeat(64), "case"}, {"attempt", IMAGE, "probe"} }) {
            assertEquals(ExecutionAuthorization.Decision.INVALID, new ExecutionAuthorization(root, scope[0], scope[1], scope[2], Clock.fixed(NOW, ZoneOffset.UTC)).decision());
        }
    }

    @Test void missingCorruptAndOversizedGrantsDenyWithoutEchoingContents() throws Exception {
        assertEquals(ExecutionAuthorization.Decision.UNAVAILABLE, authority(NOW).decision());
        Files.writeString(root.resolve("authorization.json"), "not-json");
        assertEquals(ExecutionAuthorization.Decision.INVALID, authority(NOW).decision());
        Files.writeString(root.resolve("authorization.json"), "x".repeat(8193));
        assertEquals(ExecutionAuthorization.Decision.INVALID, authority(NOW).decision());
    }

    @Test void revocationPersistsAcrossInstancesAndTheGuardLatchesDenial() throws Exception {
        authority(NOW).issue(Duration.ofMinutes(1));
        var guard = new ExecutionGuard(() -> false, authority(NOW));
        assertFalse(guard.getAsBoolean());
        authority(NOW).revoke();
        authority(NOW).revoke();
        assertTrue(guard.getAsBoolean());
        assertEquals("AUTHORIZATION_DENIED", guard.stopStatus());
        assertEquals("REVOKED", guard.authorizationDecision());
        // Even an operator removing the marker cannot restore an already stopped controller.
        Files.delete(root.resolve("authorization.revoked"));
        assertTrue(guard.getAsBoolean());
    }
}
