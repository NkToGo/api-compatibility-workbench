package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.CoordinationTrial;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceV2AdmissionTest {
    @TempDir static Path captureRoot;
    @TempDir Path root;
    private static Path directory;
    private static InvoiceComparisonInputs.Bundle bundle;
    private final CoordinationRuntimeTest.MutableClock clock = new CoordinationRuntimeTest.MutableClock();
    private final Brokers brokers = new Brokers();
    private record Fixture(CoordinationRuntime runtime, CoordinationRuntime.Policy policy, String id) {}
    @BeforeAll static void capture() throws Exception {
        var plan = InvoiceCaseCoverage.load(InvoiceSandboxTest.root());
        var run = InvoiceCaseCoverage.run(plan, "sha256:" + "1".repeat(64), captureRoot.resolve("coverage"), Duration.ofSeconds(30), () -> true);
        directory = captureRoot.resolve("inputs"); bundle = InvoiceComparisonInputs.capture(plan, run, directory);
    }
    private static CoordinationRuntime.Participant participant(String id, InvoiceReadiness.Scope scope) {
        return new CoordinationRuntime.Participant(id, Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(InvoiceDecisionV2.protocol(scope)));
    }
    private static CoordinationRuntime.Policy policy(int reads, List<CoordinationRuntime.Participant> participants) throws Exception {
        return CoordinationRuntime.invoiceInvestigationV2Policy(2, reads, participants);
    }
    private CoordinationRuntime runtime() throws Exception { return CoordinationRuntime.forInvoiceInvestigationV2(root, directory, bundle.identity(), clock, brokers); }
    private static Limits limits() { return new Limits(60, 1200000, 64000, 2048); }
    private static Definition definition(CoordinationRuntime.Policy policy, Limits limits) {
        var original = InvoiceComparisonRuntimeMain.definition(bundle.identity(), policy);
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); implementations.putAll(original.implementations());
        implementations.put(Arm.SINGLE_AGENT, CoordinationRuntime.implementationIdentity(policy));
        return new Definition("invoice-v2-admission-check", original.origin(), "gpt-test", original.rubricIdentity(), implementations,
                original.workloads(), 1, limits);
    }
    private Fixture fixture(CoordinationRuntime.Policy policy, Limits limits) throws Exception {
        var runtime = runtime(); String id = runtime.register(bundle.identity().sha256(), definition(policy, limits), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), policy);
        return new Fixture(runtime, policy, id);
    }
    private Fixture fixture() throws Exception { return fixture(policy(100, List.of(participant("agent", InvoiceReadiness.Scope.JOINT))), limits()); }
    private Path trial(Fixture f) { return root.resolve("invoice-v2-trials/" + f.id()); }
    private static ModelCallBroker.Request request(InvoiceReadiness.Scope scope) {
        return new ModelCallBroker.Request("gpt-test", "host-approved test input", 2048, InvoiceDecisionV2.protocol(scope));
    }
    private static int usage(Fixture f, String name) throws Exception { return f.runtime().snapshot(f.id()).getAsJsonObject("usage").get(name).getAsInt(); }
    private static CoordinationRuntime.Policy changed(CoordinationRuntime.Policy p, String field) {
        var zero = new ContentDigest("0".repeat(64));
        return new CoordinationRuntime.Policy(p.parallelCalls(), field.equals("reads") ? 101 : p.maxReads(), field.equals("build") ? zero : p.adapterIdentity(),
                field.equals("prompt") ? zero : p.promptIdentity(), field.equals("tools") ? zero : p.toolPolicyIdentity(),
                field.equals("context") ? zero : p.contextPolicyIdentity(), field.equals("selection") ? "unregistered-selection" : p.finalSelection(), p.participants());
    }
    @Test void validPolicyAdmitsOnlyV2ScopesAndUsesASeparateNamespace() throws Exception {
        var f = fixture(); assertTrue(Files.exists(trial(f).resolve("grant.json"))); assertFalse(Files.exists(trial(f).resolve("started.json")));
        var response = f.runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT));
        assertEquals("COMPLETED", response.get("status").getAsString()); assertEquals(1, usage(f, "reservedCalls"));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.forInvoiceInvestigation(root, directory, bundle.identity(), clock, brokers).snapshot(f.id()));
        assertThrows(RuntimeException.class, () -> CoordinationRuntime.forInvoiceNavigation(root, directory, bundle.identity(), clock).snapshot(f.id()));
    }
    @Test void allThreeScopesCanBeExplicitlyGrantedWithoutChangingInstructions() throws Exception {
        for (var scope : InvoiceReadiness.Scope.values()) {
            var f = fixture(policy(10, List.of(participant("agent", scope))), limits());
            assertEquals("COMPLETED", f.runtime().call(f.id(), "agent", "call-1", request(scope)).get("status").getAsString());
        }
        assertEquals(3, brokers.dispatched.get());
    }
    @Test void declaredIdentitiesAndBuildMustMatchBeforeARegistrationIsCreated() throws Exception {
        var p = policy(100, List.of(participant("agent", InvoiceReadiness.Scope.JOINT))); var runtime = runtime();
        for (String field : List.of("prompt", "tools", "context", "selection", "reads", "build")) {
            var invalid = changed(p, field);
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(invalid, limits()), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), invalid));
        }
        try (var files = Files.list(root.resolve("invoice-v2-trials"))) { assertEquals(0, files.count()); }
        assertEquals(0, brokers.registered.get());
    }
    @Test void scopesCannotBeEmptyMixedHistoricalOrForAnotherCase() throws Exception {
        for (var protocols : List.of(Set.<String>of(), Set.of(InvoiceInvestigationDecision.PROTOCOL),
                Set.of(InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT), InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK)))) {
            assertThrows(IllegalArgumentException.class, () -> policy(10, List.of(new CoordinationRuntime.Participant("agent", Set.of(InvoiceRenewalFixtures.CASE_ID), protocols))));
        }
        assertThrows(IllegalArgumentException.class, () -> policy(10, List.of(new CoordinationRuntime.Participant("agent", Set.of(CaseInputs.CASE_ID),
                Set.of(InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT))))));
    }
    @Test void definitionCannotExpandCallsDeadlineInputOrOutputCeilings() throws Exception {
        var p = policy(100, List.of(participant("agent", InvoiceReadiness.Scope.JOINT))); var runtime = runtime();
        for (var limit : List.of(new Limits(61, 1200000, 64000, 2048), new Limits(60, 1200001, 64000, 2048), new Limits(60, 1200000, 64000, 2049)))
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(p, limit), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), p));
        assertThrows(IllegalArgumentException.class, () -> new Limits(60, 1200000, 64001, 2048));
        assertEquals(0, brokers.registered.get());
    }
    @Test void oldReadOnlyV1AndLegacyBoundariesCannotAdmitAV2Policy() throws Exception {
        var p = policy(100, List.of(participant("agent", InvoiceReadiness.Scope.JOINT)));
        for (var runtime : List.of(CoordinationRuntime.forInvoice(root, directory, bundle.identity(), clock),
                CoordinationRuntime.forInvoiceInvestigation(root, directory, bundle.identity(), clock, brokers),
                CoordinationRuntime.forInvoiceNavigation(root, directory, bundle.identity(), clock), new CoordinationRuntime(root)))
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(p, limits()), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), p));
        assertEquals(0, brokers.registered.get());
    }
    @Test void aReadOnlyPolicyCannotBePromotedByUsingTheV2Factory() throws Exception {
        var runtime = runtime();
        for (var p : List.of(CoordinationRuntime.invoiceReadPolicy(10, List.of("agent")), CoordinationRuntime.invoiceNavigationReadPolicy(10, List.of("agent"))))
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(p, limits()), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), p));
        var p = policy(10, List.of(participant("agent", InvoiceReadiness.Scope.JOINT)));
        assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(p, limits()), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), p));
    }
    @Test void callsCannotChangeParticipantProtocolModelOrDeclaredByteAndTokenLimits() throws Exception {
        var f = fixture(policy(10, List.of(participant("agent", InvoiceReadiness.Scope.JOINT))), new Limits(60, 1200000, 64, 128));
        for (var request : List.of(new ModelCallBroker.Request("gpt-test", "input", 128, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK)),
                new ModelCallBroker.Request("gpt-other", "input", 128, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT)),
                new ModelCallBroker.Request("gpt-test", "x".repeat(65), 128, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT)),
                new ModelCallBroker.Request("gpt-test", "input", 129, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT)),
                new ModelCallBroker.Request("gpt-test", "input", 128, InvoiceInvestigationDecision.PROTOCOL)))
            assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "agent", "call-1", request));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "stranger", "call-1", request(InvoiceReadiness.Scope.JOINT)));
        assertEquals(0, brokers.registered.get()); assertEquals(0, usage(f, "reservedCalls"));
    }
    @Test void typedOwnedContextPassesTheRealBrokerWithoutExecutingItsDecision() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var calls = new AtomicInteger(); var credentials = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var body = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(InvoiceDecisionV2.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString());
            var context = JsonParser.parseString(body.get("input").getAsString()).getAsJsonObject();
            assertEquals(InvoiceDecisionV2.taskIdentity(), context.getAsJsonObject("publicTask").get("sha256").getAsString());
            String arguments = "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"source/\",\"offset\":0,\"limit\":40}}";
            byte[] response = CaseInputs.JSON.toJson(Map.of("status", "completed", "usage", Map.of("input_tokens", 10, "output_tokens", 2),
                    "output", List.of(Map.of("type", "function_call", "name", InvoiceDecisionV2.FUNCTION, "arguments", arguments)))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); try (var output = exchange.getResponseBody()) { output.write(response); }
        }); server.start();
        try {
            CoordinationRuntime.Brokers actual = (store, permitted) -> {
                var broker = new ModelCallBroker(store, () -> { credentials.incrementAndGet(); return "fixture-credential-not-a-real-key"; },
                        new ModelHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), Duration.ofSeconds(5)), clock, permitted);
                return new CoordinationRuntime.Broker() {
                    public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException { return broker.register(request, lifetime); }
                    public JsonObject execute(String id) throws IOException { broker.execute(id); return broker.outcome(id); }
                    public JsonObject recover(String id) throws IOException { broker.recover(id); return broker.outcome(id); }
                };
            };
            var runtime = CoordinationRuntime.forInvoiceInvestigationV2(root, directory, bundle.identity(), clock, actual);
            var policy = policy(100, List.of(participant("agent", InvoiceReadiness.Scope.JOINT)));
            String id = runtime.register(bundle.identity().sha256(), definition(policy, limits()), new Slot("invoice-joint", 1, Arm.SINGLE_AGENT), policy);
            var bootstrap = runtime.describe(id, "agent", "metadata", InvoiceRenewalFixtures.CASE_ID);
            var page = runtime.catalogPrefix(id, "agent", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40);
            String input = InvoiceContextV2.build(new InvoiceReadiness.Subject("agent", bundle.identity(), InvoiceReadiness.Scope.JOINT), bundle,
                    bootstrap, List.of(page), new InvoiceContextV2.Scheduling(60, 1), 64000);
            var request = new ModelCallBroker.Request("gpt-test", input, 2048, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT));
            var result = runtime.call(id, "agent", "call-1", request);
            assertEquals("catalog_prefix", InvoiceDecisionV2.parse(result.get("outputText").getAsString()).get("action").getAsString());
            var restarted = CoordinationRuntime.forInvoiceInvestigationV2(root, directory, bundle.identity(), clock, actual);
            assertEquals(result, restarted.call(id, "agent", "call-1", request));
            var closed = restarted.finish(id, CoordinationTrial.Status.COMPLETED); assertEquals(closed, runtime.retained(id));
            var usage = closed.getAsJsonObject("usage"); assertEquals(2, usage.get("toolCalls").getAsInt());
            assertEquals(1, usage.get("reservedCalls").getAsInt()); assertEquals(0, usage.get("caseExecutions").getAsInt());
            assertEquals(1, calls.get()); assertEquals(1, credentials.get());
        } finally { server.stop(0); }
    }
    @Test void exactCallsAndPrefixRetriesSurviveRestartWithoutRechargingOrRedispatch() throws Exception {
        var f = fixture(); var request = request(InvoiceReadiness.Scope.JOINT);
        var page = f.runtime().catalogPrefix(f.id(), "agent", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40);
        var response = f.runtime().call(f.id(), "agent", "call-1", request); var restarted = runtime();
        assertEquals(response, restarted.call(f.id(), "agent", "call-1", request));
        assertEquals(page, restarted.catalogPrefix(f.id(), "agent", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        assertEquals(1, brokers.registered.get()); assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(f, "reservedCalls"));
        assertEquals(1, usage(f, "toolCalls"));
        assertThrows(IllegalArgumentException.class, () -> restarted.call(f.id(), "agent", "call-1", new ModelCallBroker.Request("gpt-test", "changed", 2048, request.protocol())));
        assertThrows(IllegalArgumentException.class, () -> restarted.catalogPrefix(f.id(), "agent", "read-1", InvoiceRenewalFixtures.CASE_ID, "order/", 0, 40));
    }
    @Test void completedBrokerOutcomeSurvivesControllerInterruption() throws Exception {
        var f = fixture(); brokers.interruptAfterResult = true;
        assertThrows(IOException.class, () -> f.runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT)));
        var response = runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT));
        assertEquals("COMPLETED", response.get("status").getAsString()); assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(f, "reservedCalls"));
    }
    @Test void uncertainRegistrationIsChargedAndNeverGuessedOrRetried() throws Exception {
        var f = fixture(); brokers.interruptRegistration = true;
        assertThrows(IOException.class, () -> f.runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT)));
        assertEquals("CALL_REGISTRATION_UNCERTAIN", assertThrows(CoordinationRuntime.Stop.class,
                () -> runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT))).getMessage());
        assertEquals(1, usage(f, "reservedCalls")); assertEquals(0, brokers.dispatched.get());
        assertTrue(usage(f, "unresolvedUsageSlots") > 0);
    }
    @Test void uncertainRemoteOutcomeRemainsUncertainWithoutRedispatch() throws Exception {
        var f = fixture(); brokers.unknown = true;
        var first = f.runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT));
        assertEquals("UNKNOWN_REMOTE_OUTCOME", first.get("status").getAsString());
        assertEquals(first, runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT)));
        assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(f, "reservedCalls"));
    }
    @Test void theOneSharedCallReservationCannotBeOverspentByTwoParticipants() throws Exception {
        var f = fixture(policy(100, List.of(participant("webhook", InvoiceReadiness.Scope.WEBHOOK), participant("reconciliation", InvoiceReadiness.Scope.RECONCILIATION))),
                new Limits(1, 1200000, 64000, 2048));
        var executor = Executors.newFixedThreadPool(2); var ready = new CyclicBarrier(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (var scope : List.of(InvoiceReadiness.Scope.WEBHOOK, InvoiceReadiness.Scope.RECONCILIATION)) futures.add(executor.submit(() -> {
                ready.await(10, TimeUnit.SECONDS);
                try { f.runtime().call(f.id(), scope.name().toLowerCase(Locale.ROOT), "call-1", request(scope)); return true; }
                catch (CoordinationRuntime.Stop e) { assertEquals("CALL_BUDGET_EXHAUSTED", e.getMessage()); return false; }
            }));
            assertEquals(1, (futures.get(0).get(20, TimeUnit.SECONDS) ? 1 : 0) + (futures.get(1).get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, usage(f, "reservedCalls")); assertEquals(1, brokers.dispatched.get());
        } finally { executor.shutdownNow(); }
    }
    @Test void prefixReadsStillShareTheBoundedPool() throws Exception {
        var f = fixture(policy(1, List.of(participant("agent", InvoiceReadiness.Scope.JOINT))), limits());
        f.runtime().describe(f.id(), "agent", "metadata", InvoiceRenewalFixtures.CASE_ID);
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class,
                () -> f.runtime().catalogPrefix(f.id(), "agent", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40)).getMessage());
        assertEquals(1, usage(f, "toolCalls"));
    }
    @Test void revokeExpiryAndTerminalStateBlockEvenCachedModelCalls() throws Exception {
        var f = fixture(); f.runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT));
        clock.now = clock.now.plusMillis(1200000);
        assertEquals("TRIAL_EXPIRED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT))).getMessage());
        f.runtime().revoke(f.id());
        assertEquals("TRIAL_REVOKED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT))).getMessage());
        var result = f.runtime().finish(f.id(), CoordinationTrial.Status.CANCELLED); assertEquals(result, runtime().retained(f.id()));
        assertEquals("TRIAL_FINISHED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT))).getMessage());
        assertEquals(1, brokers.dispatched.get());
    }
    @Test void changedMappedRequestOrRetainedPrefixCannotBeAcceptedAsOriginal() throws Exception {
        var f = fixture(); f.runtime().call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT));
        Path brokerDirectory; try (var files = Files.list(trial(f).resolve("broker"))) { brokerDirectory = files.findFirst().orElseThrow(); }
        Path file = brokerDirectory.resolve("request.json"); byte[] original = Files.readAllBytes(file);
        var changed = CaseInputs.object(original); changed.addProperty("input", "changed"); Files.writeString(file, changed.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime().snapshot(f.id())); Files.write(file, original);
        f.runtime().catalogPrefix(f.id(), "agent", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40);
        Path read; try (var files = Files.list(trial(f).resolve("reads"))) { read = files.findFirst().orElseThrow(); }
        var page = CaseInputs.object(Files.readAllBytes(read)); page.getAsJsonObject("pageRequest").addProperty("alias", "order/"); Files.writeString(read, page.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime().snapshot(f.id())); assertEquals(1, brokers.dispatched.get());
    }
    @Test void foreignBuildCanBeInspectedAndClosedButCannotResumeWork() throws Exception {
        var f = fixture(); var stale = changed(f.policy(), "build"); Path file = trial(f).resolve("spec.json");
        var spec = CaseInputs.object(Files.readAllBytes(file)); spec.add("policy", CaseInputs.JSON.toJsonTree(stale));
        spec.add("definition", CaseInputs.JSON.toJsonTree(definition(stale, limits()))); Files.writeString(file, spec.toString());
        Files.writeString(trial(f).resolve("grant.json"), CaseInputs.JSON.toJson(Map.of("permission", "registered-coordination-trial", "specSha256", CaseInputs.hash(Files.readAllBytes(file)))));
        var reopened = runtime(); assertEquals(0, reopened.snapshot(f.id()).getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> reopened.call(f.id(), "agent", "call-1", request(InvoiceReadiness.Scope.JOINT)));
        assertFalse(Files.exists(trial(f).resolve("started.json")));
        var closed = reopened.finish(f.id(), CoordinationTrial.Status.CANCELLED); assertEquals(closed, runtime().retained(f.id())); assertEquals(0, brokers.registered.get());
    }
    @Test void copiedReadOnlyRegistrationCannotBecomeAV2Grant() throws Exception {
        var old = CoordinationRuntime.forInvoice(root, directory, bundle.identity(), clock); var policy = CoordinationRuntime.invoiceReadPolicy(10, List.of("agent"));
        String id = old.register(bundle.identity().sha256(), InvoiceComparisonRuntimeMain.definition(bundle.identity(), policy), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), policy);
        var current = runtime(); Path copied = Files.createDirectory(root.resolve("invoice-v2-trials/" + id));
        for (String name : List.of("reads", "calls", "broker")) Files.createDirectory(copied.resolve(name));
        for (String name : List.of("spec.json", "grant.json")) Files.copy(root.resolve("invoice-comparison-trials/" + id + "/" + name), copied.resolve(name));
        assertThrows(IllegalArgumentException.class, () -> current.snapshot(id)); assertThrows(IllegalArgumentException.class, () -> current.start(id));
        assertFalse(Files.exists(copied.resolve("started.json"))); assertEquals(0, brokers.registered.get());
    }
    @Test void copiedV2RegistrationCannotEnableAnOldFactoryDuringRecovery() throws Exception {
        var f = fixture();
        for (String namespace : List.of("invoice-comparison-trials", "invoice-navigation-trials", "coordination-trials")) {
            Path copied = Files.createDirectories(root.resolve(namespace + "/" + f.id()));
            for (String name : List.of("reads", "calls", "broker")) Files.createDirectory(copied.resolve(name));
            for (String name : List.of("spec.json", "grant.json")) Files.copy(trial(f).resolve(name), copied.resolve(name));
        }
        for (var old : List.of(CoordinationRuntime.forInvoice(root, directory, bundle.identity(), clock),
                CoordinationRuntime.forInvoiceInvestigation(root, directory, bundle.identity(), clock, brokers),
                CoordinationRuntime.forInvoiceNavigation(root, directory, bundle.identity(), clock), new CoordinationRuntime(root))) {
            assertThrows(IllegalArgumentException.class, () -> old.snapshot(f.id()));
            assertThrows(IllegalArgumentException.class, () -> old.start(f.id()));
        }
        for (String namespace : List.of("invoice-comparison-trials", "invoice-navigation-trials", "coordination-trials"))
            assertFalse(Files.exists(root.resolve(namespace + "/" + f.id() + "/started.json")));
        assertEquals(0, brokers.registered.get());
    }
    private static final class Brokers implements CoordinationRuntime.Brokers {
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger();
        boolean interruptRegistration, interruptAfterResult, unknown;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id));
                    DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (interruptRegistration) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file));
                    assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var result = CaseInputs.JSON.toJsonTree(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED",
                            "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED", "inputTokens", 5, "outputTokens", 2,
                            "outputText", "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"source/\",\"offset\":0,\"limit\":40}}" )).getAsJsonObject();
                    DurableExecution.publish(file, result);
                    if (interruptAfterResult) { interruptAfterResult = false; throw new IOException("Interrupted controller"); } return result;
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
