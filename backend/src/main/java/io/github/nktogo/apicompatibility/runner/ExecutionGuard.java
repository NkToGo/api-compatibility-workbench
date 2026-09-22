package io.github.nktogo.apicompatibility.runner;

import java.util.function.BooleanSupplier;

/** Latches the first observed stop condition, including loss of local authorization. */
final class ExecutionGuard implements BooleanSupplier {
    private final BooleanSupplier cancelled;
    private final ExecutionAuthorization authorization;
    private String status;
    private ExecutionAuthorization.Decision decision;

    ExecutionGuard(BooleanSupplier cancelled, ExecutionAuthorization authorization) {
        this.cancelled = cancelled; this.authorization = authorization;
    }
    @Override public boolean getAsBoolean() {
        if (status != null) return true;
        if (authorization != null) decision = authorization.decision();
        if (cancelled.getAsBoolean()) status = "CANCELLED";
        else if (decision != null && decision != ExecutionAuthorization.Decision.ALLOWED) status = "AUTHORIZATION_DENIED";
        return status != null;
    }
    String stopStatus() { return status == null ? "CANCELLED" : status; }
    String authorizationDecision() { return decision == null ? "NOT_CHECKED" : decision.name(); }
}
