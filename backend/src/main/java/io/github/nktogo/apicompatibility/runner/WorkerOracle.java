package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;

/** Host-owned protocol handler; execution status comes from the controller. */
interface WorkerOracle {
    JsonElement request(JsonObject request);
    JsonObject result(String executionStatus);
}
