package io.github.nktogo.apicompatibility.runner;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Typed host observation and its diagnostic envelope; JSON alone cannot reconstruct the observation. */
record InvoiceConsumerExecution(JsonObject evidence, InvoiceRenewalOracle.Observation observation) {
    InvoiceConsumerExecution {
        Objects.requireNonNull(observation);
        CaseInputs.require(evidence != null && observation.json().equals(evidence.get("observation")), "Invoice observation/envelope mismatch");
        evidence = evidence.deepCopy();
    }
    @Override public JsonObject evidence() { return evidence.deepCopy(); }
    @Override public String toString() { return "Invoice consumer execution [" + observation.executionStatus() + "]"; }
}
