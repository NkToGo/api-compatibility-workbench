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

class InvoiceTeamV2AdmissionTest {
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
    private static CoordinationRuntime.Participant participant(String id, String protocol) {
        return new CoordinationRuntime.Participant(id, Set.of(InvoiceRenewalFixtures.CASE_ID), Set.of(protocol));
    }
    private static List<CoordinationRuntime.Participant> participants() {
        return List.of(participant("webhook", InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK)),
                participant("reconciliation", InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.RECONCILIATION)), participant("coordinator", InvoiceTeamDecisionV2.PROTOCOL));
    }
    private static CoordinationRuntime.Policy policy() throws Exception { return CoordinationRuntime.invoiceTeamReviewV2Policy(2, 100, participants()); }
    private CoordinationRuntime runtime() throws Exception { return CoordinationRuntime.forInvoiceTeamReviewV2(root, directory, bundle.identity(), clock, brokers); }
    private static Limits limits() { return new Limits(60, 1200000, 64000, 2048); }
    private static Definition definition(CoordinationRuntime.Policy p, Limits limits) {
        var original = InvoiceComparisonRuntimeMain.definition(bundle.identity(), p);
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class); implementations.putAll(original.implementations());
        implementations.put(Arm.COORDINATED_TEAM, CoordinationRuntime.implementationIdentity(p));
        return new Definition("invoice-team-v2-admission-check", original.origin(), "gpt-test", original.rubricIdentity(), implementations, original.workloads(), 1, limits);
    }
    private Fixture fixture(CoordinationRuntime.Policy p, Limits limits) throws Exception {
        var runtime = runtime(); return new Fixture(runtime, p, runtime.register(bundle.identity().sha256(), definition(p, limits), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), p));
    }
    private Fixture fixture() throws Exception { return fixture(policy(), limits()); }
    private Path trial(Fixture f) { return root.resolve("invoice-team-v2-trials/" + f.id()); }
    private static ModelCallBroker.Request request() { return new ModelCallBroker.Request("gpt-test", "approved test context", 2048, InvoiceTeamDecisionV2.PROTOCOL); }
    private static int usage(Fixture f, String name) throws Exception { return f.runtime().snapshot(f.id()).getAsJsonObject("usage").get(name).getAsInt(); }
    private static CoordinationRuntime.Policy changed(CoordinationRuntime.Policy p, String field) {
        var zero = new ContentDigest("0".repeat(64));
        return new CoordinationRuntime.Policy(p.parallelCalls(), field.equals("reads") ? 101 : p.maxReads(), field.equals("build") ? zero : p.adapterIdentity(),
                field.equals("prompt") ? zero : p.promptIdentity(), field.equals("tools") ? zero : p.toolPolicyIdentity(),
                field.equals("context") ? zero : p.contextPolicyIdentity(), field.equals("selection") ? "unknown-selection" : p.finalSelection(), p.participants());
    }
    @Test void separateNamespaceGrantsOnlyTheThreeExplicitParticipantProtocols() throws Exception {
        var f = fixture(); assertTrue(Files.exists(trial(f).resolve("grant.json"))); assertFalse(Files.exists(trial(f).resolve("started.json")));
        for (var p : participants()) {
            var request = new ModelCallBroker.Request("gpt-test", "approved test context", 2048, p.protocols().iterator().next());
            assertEquals("COMPLETED", f.runtime().call(f.id(), p.id(), "call-1", request).get("status").getAsString());
        }
        assertEquals(3, usage(f, "reservedCalls")); assertEquals(3, brokers.dispatched.get());
        assertFalse(Files.exists(root.resolve("invoice-v2-trials/" + f.id())));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "webhook", "call-2", request()));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "coordinator", "call-2", new ModelCallBroker.Request("gpt-test", "input", 2048, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK))));
        assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "stranger", "call-1", request()));
        assertEquals(3, brokers.registered.get());
    }
    @Test void jointHistoricalMixedEmptyAndForeignCaseScopesAreRejected() {
        for (var protocols : List.of(Set.<String>of(), Set.of(InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.JOINT)),
                Set.of(InvoiceTeamDecision.PROTOCOL), Set.of(InvoiceTeamDecisionV2.PROTOCOL, InvoiceDecisionV2.protocol(InvoiceReadiness.Scope.WEBHOOK))))
            assertThrows(IllegalArgumentException.class, () -> CoordinationRuntime.invoiceTeamReviewV2Policy(2, 100,
                    List.of(new CoordinationRuntime.Participant("agent", Set.of(InvoiceRenewalFixtures.CASE_ID), protocols))));
        assertThrows(IllegalArgumentException.class, () -> CoordinationRuntime.invoiceTeamReviewV2Policy(2, 100,
                List.of(new CoordinationRuntime.Participant("agent", Set.of(CaseInputs.CASE_ID), Set.of(InvoiceTeamDecisionV2.PROTOCOL)))));
    }
    @Test void identitiesBuildAndCeilingsMustMatchBeforePublishingARegistration() throws Exception {
        var p = policy(); var runtime = runtime();
        for (String field : List.of("prompt", "tools", "context", "selection", "reads", "build")) {
            var invalid = changed(p, field);
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(invalid, limits()), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), invalid));
        }
        for (var limits : List.of(new Limits(61, 1200000, 64000, 2048), new Limits(60, 1200001, 64000, 2048), new Limits(60, 1200000, 64000, 2049)))
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(p, limits), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), p));
        assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(p, limits()), new Slot("invoice-joint", 1, Arm.DETERMINISTIC), p));
        try (var files = Files.list(root.resolve("invoice-team-v2-trials"))) { assertEquals(0, files.count()); }
        assertEquals(0, brokers.registered.get());
    }
    @Test void olderFactoriesRejectTeamPoliciesAtRegistrationAndAfterCopying() throws Exception {
        var f = fixture();
        var old = List.of(CoordinationRuntime.forInvoiceInvestigationV2(root, directory, bundle.identity(), clock, brokers),
                CoordinationRuntime.forInvoice(root, directory, bundle.identity(), clock), CoordinationRuntime.forInvoiceInvestigation(root, directory, bundle.identity(), clock, brokers),
                CoordinationRuntime.forInvoiceNavigation(root, directory, bundle.identity(), clock), new CoordinationRuntime(root));
        for (var runtime : old)
            assertThrows(IllegalArgumentException.class, () -> runtime.register(bundle.identity().sha256(), definition(f.policy(), limits()), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), f.policy()));
        for (String namespace : List.of("invoice-v2-trials", "invoice-comparison-trials", "invoice-navigation-trials", "coordination-trials")) copy(trial(f), root.resolve(namespace + "/" + f.id()));
        for (var runtime : old) { assertThrows(IllegalArgumentException.class, () -> runtime.snapshot(f.id())); assertThrows(IllegalArgumentException.class, () -> runtime.start(f.id())); }
        assertEquals(0, brokers.registered.get());
    }
    @Test void copiedInvestigationOnlyRegistrationCannotBecomeATeamGrant() throws Exception {
        var old = CoordinationRuntime.forInvoiceInvestigationV2(root, directory, bundle.identity(), clock, brokers);
        var p = CoordinationRuntime.invoiceInvestigationV2Policy(2, 100, participants().subList(0, 2));
        String id = old.register(bundle.identity().sha256(), definition(p, limits()), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), p);
        copy(root.resolve("invoice-v2-trials/" + id), root.resolve("invoice-team-v2-trials/" + id)); var current = runtime();
        assertThrows(IllegalArgumentException.class, () -> current.snapshot(id)); assertThrows(IllegalArgumentException.class, () -> current.start(id));
        assertFalse(Files.exists(root.resolve("invoice-team-v2-trials/" + id + "/started.json"))); assertEquals(0, brokers.registered.get());
    }
    private static void copy(Path source, Path destination) throws IOException {
        Files.createDirectories(destination); for (String name : List.of("reads", "calls", "broker")) Files.createDirectory(destination.resolve(name));
        for (String name : List.of("spec.json", "grant.json")) Files.copy(source.resolve(name), destination.resolve(name));
    }
    @Test void exactCallsAndPrefixReadsRecoverWithoutNewDispatchButChangedRequestsFail() throws Exception {
        var f = fixture(); var page = f.runtime().catalogPrefix(f.id(), "coordinator", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40);
        var result = f.runtime().call(f.id(), "coordinator", "call-1", request()); var restarted = runtime();
        assertEquals(result, restarted.call(f.id(), "coordinator", "call-1", request()));
        assertEquals(page, restarted.catalogPrefix(f.id(), "coordinator", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40));
        assertThrows(IllegalArgumentException.class, () -> restarted.call(f.id(), "coordinator", "call-1", new ModelCallBroker.Request("gpt-test", "changed", 2048, InvoiceTeamDecisionV2.PROTOCOL)));
        assertThrows(IllegalArgumentException.class, () -> restarted.catalogPrefix(f.id(), "coordinator", "read-1", InvoiceRenewalFixtures.CASE_ID, "order/", 0, 40));
        assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(f, "reservedCalls")); assertEquals(1, usage(f, "toolCalls"));
    }
    @Test void interruptedRegistrationAndUnknownRemoteOutcomeAreNotRedispatched() throws Exception {
        var f = fixture(); brokers.interruptRegistration = true;
        assertThrows(IOException.class, () -> f.runtime().call(f.id(), "coordinator", "call-1", request()));
        assertEquals("CALL_REGISTRATION_UNCERTAIN", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "coordinator", "call-1", request())).getMessage());
        assertEquals(1, usage(f, "reservedCalls")); assertEquals(0, brokers.dispatched.get());
        brokers.interruptRegistration = false; brokers.unknown = true; var second = fixture();
        var result = second.runtime().call(second.id(), "coordinator", "call-1", request());
        assertEquals("UNKNOWN_REMOTE_OUTCOME", result.get("status").getAsString()); assertEquals(result, runtime().call(second.id(), "coordinator", "call-1", request()));
        assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(second, "unresolvedUsageSlots"));
    }
    @Test void coordinatorRequestsCannotExceedTheDeclaredModelByteOrTokenScope() throws Exception {
        var f = fixture(policy(), new Limits(60, 1200000, 64, 128));
        for (var request : List.of(new ModelCallBroker.Request("gpt-other", "input", 128, InvoiceTeamDecisionV2.PROTOCOL),
                new ModelCallBroker.Request("gpt-test", "x".repeat(65), 128, InvoiceTeamDecisionV2.PROTOCOL),
                new ModelCallBroker.Request("gpt-test", "input", 129, InvoiceTeamDecisionV2.PROTOCOL),
                new ModelCallBroker.Request("gpt-test", "input", 128, InvoiceTeamDecision.PROTOCOL)))
            assertThrows(IllegalArgumentException.class, () -> f.runtime().call(f.id(), "coordinator", "call-1", request));
        assertEquals(0, brokers.registered.get()); assertEquals(0, usage(f, "reservedCalls"));
    }
    @Test void alteredMappedRequestAndRetainedPrefixCannotBeLoadedAsOriginal() throws Exception {
        var f = fixture(); f.runtime().call(f.id(), "coordinator", "call-1", request());
        Path brokerDirectory; try (var files = Files.list(trial(f).resolve("broker"))) { brokerDirectory = files.findFirst().orElseThrow(); }
        Path file = brokerDirectory.resolve("request.json"); byte[] original = Files.readAllBytes(file);
        var request = CaseInputs.object(original); request.addProperty("protocol", InvoiceTeamDecision.PROTOCOL); Files.writeString(file, request.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime().snapshot(f.id())); Files.write(file, original);
        f.runtime().catalogPrefix(f.id(), "coordinator", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40);
        Path read; try (var files = Files.list(trial(f).resolve("reads"))) { read = files.findFirst().orElseThrow(); }
        var page = CaseInputs.object(Files.readAllBytes(read)); page.getAsJsonObject("pageRequest").addProperty("alias", "order/"); Files.writeString(read, page.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime().snapshot(f.id())); assertEquals(1, brokers.dispatched.get());
    }
    @Test void interruptedControllerRecoversTheCompletedBrokerResult() throws Exception {
        var f = fixture(); brokers.interruptAfterResult = true;
        assertThrows(IOException.class, () -> f.runtime().call(f.id(), "coordinator", "call-1", request()));
        assertEquals("COMPLETED", runtime().call(f.id(), "coordinator", "call-1", request()).get("status").getAsString());
        assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(f, "reservedCalls"));
    }
    @Test void workersAndCoordinatorShareTheSameCallAndReadCeilings() throws Exception {
        var p = CoordinationRuntime.invoiceTeamReviewV2Policy(2, 1, participants()); var f = fixture(p, new Limits(1, 1200000, 64000, 2048));
        var executor = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (var participant : List.of(participants().get(0), participants().get(2))) futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { f.runtime().call(f.id(), participant.id(), "call-1", new ModelCallBroker.Request("gpt-test", "input", 2048, participant.protocols().iterator().next())); return true; }
                catch (CoordinationRuntime.Stop e) { assertEquals("CALL_BUDGET_EXHAUSTED", e.getMessage()); return false; }
            }));
            assertEquals(1, (futures.get(0).get(20, TimeUnit.SECONDS) ? 1 : 0) + (futures.get(1).get(20, TimeUnit.SECONDS) ? 1 : 0));
        } finally { executor.shutdownNow(); }
        f.runtime().describe(f.id(), "webhook", "metadata", InvoiceRenewalFixtures.CASE_ID);
        assertEquals("READ_BUDGET_EXHAUSTED", assertThrows(CoordinationRuntime.Stop.class, () -> f.runtime().catalogPrefix(f.id(), "coordinator", "read-1", InvoiceRenewalFixtures.CASE_ID, "source/", 0, 40)).getMessage());
        assertEquals(1, brokers.dispatched.get()); assertEquals(1, usage(f, "toolCalls"));
    }
    @Test void expiryRevocationAndClosureBlockEvenCachedCalls() throws Exception {
        var f = fixture(); f.runtime().call(f.id(), "coordinator", "call-1", request()); clock.now = clock.now.plusMillis(1200000);
        assertEquals("TRIAL_EXPIRED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "coordinator", "call-1", request())).getMessage());
        f.runtime().revoke(f.id()); assertEquals("TRIAL_REVOKED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "coordinator", "call-1", request())).getMessage());
        var closed = f.runtime().finish(f.id(), CoordinationTrial.Status.CANCELLED); assertEquals(closed, runtime().retained(f.id()));
        assertEquals("TRIAL_FINISHED", assertThrows(CoordinationRuntime.Stop.class, () -> runtime().call(f.id(), "coordinator", "call-1", request())).getMessage()); assertEquals(1, brokers.dispatched.get());
    }
    @Test void foreignBuildCanBeInspectedAndClosedButCannotStartNewWork() throws Exception {
        var f = fixture(); var stale = changed(f.policy(), "build"); var file = trial(f).resolve("spec.json");
        var spec = CaseInputs.object(Files.readAllBytes(file)); spec.add("policy", CaseInputs.JSON.toJsonTree(stale)); spec.add("definition", CaseInputs.JSON.toJsonTree(definition(stale, limits())));
        Files.writeString(file, spec.toString()); Files.writeString(trial(f).resolve("grant.json"), CaseInputs.JSON.toJson(Map.of("permission", "registered-coordination-trial", "specSha256", CaseInputs.hash(Files.readAllBytes(file)))));
        var restarted = runtime(); assertEquals(0, restarted.snapshot(f.id()).getAsJsonObject("usage").get("reservedCalls").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> restarted.call(f.id(), "coordinator", "call-1", request())); assertFalse(Files.exists(trial(f).resolve("started.json")));
        assertEquals(restarted.finish(f.id(), CoordinationTrial.Status.CANCELLED), runtime().retained(f.id())); assertEquals(0, brokers.registered.get());
    }
    @Test void ownedReviewContextUsesTheRealBrokerAndRemainsAdvisoryAfterRestart() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var calls = new AtomicInteger(); var credentials = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); var body = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(InvoiceTeamDecisionV2.FUNCTION, body.getAsJsonObject("tool_choice").get("name").getAsString());
            var context = JsonParser.parseString(body.get("input").getAsString()).getAsJsonObject(); assertEquals(2, context.getAsJsonArray("workers").size());
            assertEquals(InvoiceTeamDecisionV2.PROTOCOL, context.get("protocol").getAsString());
            var output = Map.of("type", "function_call", "name", InvoiceTeamDecisionV2.FUNCTION, "arguments", Brokers.ARGUMENTS);
            byte[] bytes = CaseInputs.JSON.toJson(Map.of("status", "completed", "usage", Map.of("input_tokens", 10, "output_tokens", 2), "output", List.of(output))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
        try {
            CoordinationRuntime.Brokers actual = (store, permitted) -> {
                var broker = new ModelCallBroker(store, () -> { credentials.incrementAndGet(); return "fixture-credential-never-real"; }, new ModelHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), Duration.ofSeconds(5)), clock, permitted);
                return new CoordinationRuntime.Broker() {
                    public String register(ModelCallBroker.Request r, Duration d) throws IOException { return broker.register(r, d); }
                    public JsonObject execute(String id) throws IOException { broker.execute(id); return broker.outcome(id); }
                    public JsonObject recover(String id) throws IOException { broker.recover(id); return broker.outcome(id); }
                };
            };
            var runtime = CoordinationRuntime.forInvoiceTeamReviewV2(root, directory, bundle.identity(), clock, actual); var p = policy();
            String id = runtime.register(bundle.identity().sha256(), definition(p, limits()), new Slot("invoice-joint", 1, Arm.COORDINATED_TEAM), p);
            var bootstrap = runtime.describe(id, "coordinator", "metadata", InvoiceRenewalFixtures.CASE_ID);
            var originals = new ArrayList<InvoiceTeamReviewV2.WorkerResult>();
            for (var worker : InvoiceWorkerDecision.WORKERS) {
                var scope = worker.equals("invoice-webhook") ? InvoiceReadiness.Scope.WEBHOOK : InvoiceReadiness.Scope.RECONCILIATION;
                originals.add(new InvoiceTeamReviewV2.WorkerResult(worker, bundle.identity(), InvoiceDecisionV2.protocol(scope), InvoiceDecisionV2.contractIdentity(scope), InvoiceDecisionV2.taskIdentity(),
                        InvoiceTeamReviewV2.WorkerStatus.FAILED, "NO_CAPTURED_DOSSIER", null, null, List.of()));
            }
            String input = new InvoiceTeamReviewV2("coordinator", bundle, originals).context(bootstrap, List.of(), CoordinationTeamBudget.forCeiling(60), new CoordinationTeamBudget.Reservations(0, 0, 0, 0), 64000);
            var request = new ModelCallBroker.Request("gpt-test", input, 2048, InvoiceTeamDecisionV2.PROTOCOL); var result = runtime.call(id, "coordinator", "call-1", request);
            assertEquals("catalog_prefix", InvoiceTeamDecisionV2.parse(result.get("outputText").getAsString()).get("action").getAsString());
            var restarted = CoordinationRuntime.forInvoiceTeamReviewV2(root, directory, bundle.identity(), clock, actual);
            assertEquals(result, restarted.call(id, "coordinator", "call-1", request)); var closed = restarted.finish(id, CoordinationTrial.Status.COMPLETED);
            assertEquals(closed, runtime.retained(id)); assertEquals(1, calls.get()); assertEquals(1, credentials.get());
            assertEquals(0, closed.getAsJsonObject("usage").get("caseExecutions").getAsInt());
        } finally { server.stop(0); }
    }
    private static final class Brokers implements CoordinationRuntime.Brokers {
        static final String ARGUMENTS = "{\"decision\":{\"action\":\"catalog_prefix\",\"prefix\":\"source/\",\"offset\":0,\"limit\":40}}";
        final AtomicInteger registered = new AtomicInteger(), dispatched = new AtomicInteger();
        boolean interruptRegistration, interruptAfterResult, unknown;
        public CoordinationRuntime.Broker create(Path store, BooleanSupplier permitted) {
            return new CoordinationRuntime.Broker() {
                public String register(ModelCallBroker.Request request, Duration lifetime) throws IOException {
                    String id = "model-" + UUID.randomUUID(); Files.createDirectory(store.resolve(id)); DurableExecution.publish(store.resolve(id + "/request.json"), request); registered.incrementAndGet();
                    if (interruptRegistration) throw new IOException("Interrupted registration"); return id;
                }
                public JsonObject execute(String id) throws IOException {
                    Path file = store.resolve(id + "/result.json"); if (Files.exists(file)) return CaseInputs.object(Files.readAllBytes(file));
                    assertTrue(permitted.getAsBoolean()); dispatched.incrementAndGet();
                    var result = CaseInputs.JSON.toJsonTree(Map.of("callId", id, "status", unknown ? "UNKNOWN_REMOTE_OUTCOME" : "COMPLETED", "remoteOutcome", unknown ? "UNKNOWN" : "RESPONSE_RECEIVED",
                            "inputTokens", 5, "outputTokens", 2, "outputText", ARGUMENTS)).getAsJsonObject(); DurableExecution.publish(file, result);
                    if (interruptAfterResult) { interruptAfterResult = false; throw new IOException("Interrupted controller"); } return result;
                }
                public JsonObject recover(String id) throws IOException { return CaseInputs.object(Files.readAllBytes(store.resolve(id + "/result.json"))); }
            };
        }
    }
}
