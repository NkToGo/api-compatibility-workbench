package io.github.nktogo.apicompatibility.runner;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ModelCallBrokerTest {
    @TempDir Path root;
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger credentialReads = new AtomicInteger();
    private static final String KEY = "fixture-secret-never-a-real-key";
    private static final String RESPONSE = """
            {"status":"completed","output":[{"type":"reasoning","summary":[{"text":"do-not-retain-reasoning"}]},
            {"type":"message","content":[{"type":"output_text","text":"ready"}]}],"usage":{"input_tokens":10,"output_tokens":2}}
            """;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private ModelCallBroker broker() throws Exception { return broker(Clock.systemUTC(), Duration.ofSeconds(5)); }
    private ModelCallBroker broker(Clock clock, Duration timeout) throws Exception {
        return new ModelCallBroker(root, () -> { credentialReads.incrementAndGet(); return KEY; },
                new ModelHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), timeout), clock);
    }
    private String register(ModelCallBroker broker) throws Exception {
        return broker.register(new ModelCallBroker.Request("gpt-test", "approved input", 128), Duration.ofMinutes(1));
    }
    private void reply(int code, String text) {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("x-request-id", "req_fixture");
            exchange.getResponseHeaders().add("retry-after", "12");
            exchange.getResponseHeaders().add("location", "http://127.0.0.1:" + server.getAddress().getPort() + "/stolen");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(code, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    @Test void dispatchIsScopedAndFinalizationDoesNotRepeatTheCallOrRetainSecrets() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer " + KEY, exchange.getRequestHeaders().getFirst("Authorization"));
            var request = CaseInputs.object(exchange.getRequestBody().readAllBytes());
            assertEquals(5, request.size()); assertFalse(request.get("store").getAsBoolean());
            assertFalse(request.get("background").getAsBoolean()); assertFalse(request.has("tools"));
            byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        var broker = broker(); String id = register(broker);
        assertEquals("COMPLETED", broker.execute(id));
        assertEquals("COMPLETED", broker().execute(id));
        assertEquals("COMPLETED", broker().revoke(id));
        assertEquals(1, calls.get());
        String result = Files.readString(root.resolve(id).resolve("result.json"));
        assertTrue(result.contains("ready")); assertFalse(result.contains("do-not-retain-reasoning"));
        assertNoSecret();
    }

    @Test void requestMutationAndExpiryDenyBeforeCredentialResolution() throws Exception {
        reply(200, RESPONSE);
        var broker = broker(); String id = register(broker);
        var changed = CaseInputs.object(Files.readAllBytes(root.resolve(id).resolve("request.json")));
        changed.addProperty("model", "gpt-other"); CaseInputs.write(root.resolve(id).resolve("request.json"), changed);
        assertEquals("AUTHORIZATION_DENIED", broker.execute(id));
        Instant now = Instant.now();
        var early = broker(Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(5)); String expired = register(early);
        assertEquals("AUTHORIZATION_DENIED", broker(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC), Duration.ofSeconds(5)).execute(expired));
        assertEquals(0, calls.get());
        assertEquals(0, credentialReads.get());
    }

    @Test void queuedRevocationAndInterruptedDispatchNeverReplay() throws Exception {
        reply(200, RESPONSE); var broker = broker(); String revoked = register(broker);
        assertEquals("CLOSED_WITHOUT_DISPATCH", broker.revoke(revoked));
        assertEquals("CLOSED_WITHOUT_DISPATCH", broker.execute(revoked));
        String interrupted = register(broker);
        Files.writeString(root.resolve(interrupted).resolve("started.json"), "{}");
        assertEquals("UNKNOWN_REMOTE_OUTCOME", broker().recover(interrupted));
        assertEquals("UNKNOWN_REMOTE_OUTCOME", broker.execute(interrupted));
        assertEquals(0, calls.get());
    }

    @Test void providerCredentialRejectionRateLimitsAndRedirectsAreSanitizedAndNotRetried() throws Exception {
        for (int status : new int[] {401, 403, 429, 302, 503}) {
            reply(status, "provider error containing " + KEY);
            var broker = broker(); String id = register(broker);
            String expected = switch(status) { case 401, 403 -> "CREDENTIAL_REJECTED"; case 429 -> "RATE_LIMITED"; case 302 -> "REDIRECT_REJECTED"; default -> "PROVIDER_ERROR"; };
            assertEquals(expected, broker.execute(id)); assertEquals(expected, broker.execute(id));
            server.removeContext("/v1/responses");
        }
        assertEquals(5, calls.get()); assertNoSecret();
    }

    @Test void liveRevocationDiscardsLateOutputAndKeepsUnknownRemoteOutcome() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var broker = broker(); String id = register(broker);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> broker.execute(id));
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertEquals("REVOCATION_REQUESTED", broker().revoke(id));
                assertEquals("ACCESS_ENDED", result.get(5, TimeUnit.SECONDS));
                assertEquals("ACCESS_ENDED", broker().recover(id));
                assertTrue(Files.readString(root.resolve(id).resolve("result.json")).contains("UNKNOWN"));
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
        }
    }

    @Test void oversizedMalformedIncompleteAndSecretEchoResponsesCannotBecomeSuccess() throws Exception {
        for (String response : new String[] {"x".repeat(262145), "not-json", "{\"status\":\"incomplete\"}", RESPONSE.replace("ready", KEY), RESPONSE.replace("ready", KEY.replace("f", "\\u0066"))}) {
            reply(200, response); var broker = broker(); String id = register(broker);
            assertNotEquals("COMPLETED", broker.execute(id));
            assertNoSecret(); server.removeContext("/v1/responses");
        }
        assertEquals(5, calls.get());
    }

    @Test void missingCredentialAndUnexpectedDestinationFailClosed() throws Exception {
        var broker = new ModelCallBroker(root, () -> null, new ModelHttpTransport(), Clock.systemUTC());
        assertEquals("CREDENTIAL_UNAVAILABLE", broker.execute(register(broker)));
        assertThrows(IllegalArgumentException.class, () -> new ModelHttpTransport(URI.create("https://example.com"), Duration.ofSeconds(1)));
        assertEquals(0, calls.get());
    }

    @Test void timeoutCoversAStalledResponseBodyWithoutAutomaticReplay() throws Exception {
        var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try {
            var broker = broker(Clock.systemUTC(), Duration.ofSeconds(2)); String id = register(broker);
            assertEquals("UNKNOWN_REMOTE_OUTCOME", broker.execute(id));
            assertEquals("UNKNOWN_REMOTE_OUTCOME", broker.execute(id));
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void permissionExpiryDuringARequestDiscardsTheRemoteResult() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var broker = broker(clock, Duration.ofSeconds(5)); String id = register(broker);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> broker.execute(id));
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                now.set(now.get().plusSeconds(60));
                assertEquals("ACCESS_ENDED", result.get(5, TimeUnit.SECONDS));
                assertEquals("ACCESS_ENDED", broker.recover(id)); assertEquals(1, calls.get());
            } finally { release.countDown(); }
        }
    }

    @Test void controllerDeathAfterServerReceiptCannotResendTheRequest() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        String id = register(broker());
        var controller = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Controller.class.getName(), root.toString(), id,
                Integer.toString(server.getAddress().getPort())).redirectErrorStream(true)
                .redirectOutput(root.resolve("controller.log").toFile()).start();
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS));
            controller.destroyForcibly(); assertTrue(controller.waitFor(10, TimeUnit.SECONDS));
            String status = "ACTIVE"; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (status.equals("ACTIVE") && System.nanoTime() < deadline) { status = broker().recover(id); if (status.equals("ACTIVE")) Thread.sleep(50); }
            assertEquals("UNKNOWN_REMOTE_OUTCOME", status);
            assertEquals("UNKNOWN_REMOTE_OUTCOME", broker().execute(id));
            assertEquals(1, calls.get()); assertNoSecret();
        } finally { controller.destroyForcibly(); release.countDown(); }
    }

    public static final class Controller {
        private Controller() {}
        public static void main(String[] args) throws Exception {
            var transport = new ModelHttpTransport(URI.create("http://127.0.0.1:" + args[2] + "/v1/responses"), Duration.ofSeconds(30));
            new ModelCallBroker(Path.of(args[0]), () -> KEY, transport, Clock.systemUTC()).execute(args[1]);
        }
    }

    private void assertNoSecret() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) assertFalse(Files.readString(path).contains(KEY), path.getFileName().toString());
        }
    }
}
