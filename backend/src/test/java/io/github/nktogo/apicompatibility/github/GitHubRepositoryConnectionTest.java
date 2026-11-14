package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static io.github.nktogo.apicompatibility.github.GitHubRepositoryConnection.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubRepositoryConnectionTest {
    @TempDir Path temporary;
    static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    static final String SHA = "a".repeat(40), TREE = "b".repeat(40);
    static final String TOKEN = "ghs_APPID_" + "opaque.stateless.token".repeat(80);
    static final Selection SELECTION = new Selection(1, 20, 30, 40, "sample-owner", "billing", SHA);
    static final String INSTALLATION = "/app/installations/20", MINT = INSTALLATION + "/access_tokens";
    static final String SCOPE = "/installation/repositories?per_page=100&page=1", COMMIT = "/repos/sample-owner/billing/git/commits/" + SHA;
    static KeyPair pair;
    static GitHubAppCredentials credentials;
    @BeforeAll static void keys() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair();
        credentials = GitHubAppCredentials.parse("Iv1.fixture", GitHubAppCredentialsTest.pem(pair, true));
    }
    static final class MutableClock extends Clock {
        volatile Instant value = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
    record Captured(String method, String path, String authorization, String apiVersion, String agent, byte[] body) {}
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final Map<String, JsonObject> replies = new HashMap<>();
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch releaseBody = new CountDownLatch(1);
        String faultPath = "", contentType = "application/json";
        int faultStatus = 500;
        byte[] faultBody = "PRIVATE_PROVIDER_BODY_SENTINEL".getBytes(StandardCharsets.UTF_8);
        final Map<String, String> faultHeaders = new HashMap<>();
        boolean holdBody;
        Runnable afterScope = () -> {};
        Fixture() throws Exception {
            replies.put("/app", json("{\"id\":10,\"client_id\":\"Iv1.fixture\"}"));
            replies.put(INSTALLATION, json("{\"id\":20,\"app_id\":10,\"account\":{\"id\":30,\"login\":\"sample-owner\"},\"repository_selection\":\"selected\",\"suspended_at\":null}"));
            replies.put(MINT, json("{\"token\":\"" + TOKEN + "\",\"expires_at\":\"2026-11-14T01:00:00Z\",\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"));
            replies.put(SCOPE, json("{\"total_count\":1,\"repositories\":[{\"id\":40,\"name\":\"billing\",\"full_name\":\"sample-owner/billing\",\"owner\":{\"id\":30,\"login\":\"sample-owner\"},\"clone_url\":\"https://attacker.invalid/ignored\",\"description\":\"PRIVATE_DESCRIPTION_SENTINEL\"}]}"));
            replies.put(COMMIT, json("{\"sha\":\"" + SHA + "\",\"tree\":{\"sha\":\"" + TREE + "\"},\"message\":\"PRIVATE_COMMIT_MESSAGE_SENTINEL\"}"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().toString(); var headers = exchange.getRequestHeaders();
                    requests.add(new Captured(exchange.getRequestMethod(), path, headers.getFirst("Authorization"),
                            headers.getFirst("X-GitHub-Api-Version"), headers.getFirst("User-Agent"), exchange.getRequestBody().readNBytes(8192)));
                    boolean fault = path.equals(faultPath);
                    byte[] bytes = fault ? faultBody : GitHubJson.JSON.toJson(replies.get(path)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", fault ? contentType : "application/json");
                    if (fault) faultHeaders.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                    exchange.sendResponseHeaders(fault ? faultStatus : path.equals(MINT) ? 201 : 200, bytes.length);
                    if (path.equals(SCOPE)) afterScope.run();
                    if (fault && holdBody) try { releaseBody.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    try { exchange.getResponseBody().write(bytes); } catch (java.io.IOException ignored) { /* Expected bounded-client cancellation. */ }
                }
            }); server.start();
        }
        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        GitHubTransport transport() { return new GitHubTransport(origin(), Duration.ofSeconds(5)); }
        @Override public void close() { releaseBody.countDown(); server.stop(0); executor.shutdownNow(); }
    }
    static JsonObject json(String value) { return GitHubJson.parse(value.getBytes(StandardCharsets.UTF_8), 8192); }
    private static Snapshot inspect(Fixture fixture) throws Exception {
        try (var transport = fixture.transport()) { return new GitHubRepositoryConnection(Clock.fixed(NOW, ZoneOffset.UTC)).inspect(SELECTION, credentials, transport); }
    }
    private static Failure failure(Fixture fixture) throws Exception {
        try (var transport = fixture.transport()) {
            var exception = assertThrows(ConnectionFailure.class,
                    () -> new GitHubRepositoryConnection(Clock.fixed(NOW, ZoneOffset.UTC)).inspect(SELECTION, credentials, transport));
            assertNull(exception.getCause()); assertFalse(exception.getMessage().contains("PRIVATE_"));
            String json = GitHubJson.JSON.toJson(exception.failure()); assertFalse(json.contains(TOKEN)); assertFalse(json.contains("PRIVATE_"));
            return exception.failure();
        }
    }

    @Test void requestsOnlyOneRepositoryReadScopeAndPinsTheExactCommitWithoutReturningSecrets() throws Exception {
        try (var fixture = new Fixture()) {
            var snapshot = inspect(fixture);
            assertEquals(SELECTION, snapshot.selection()); assertEquals(TREE, snapshot.treeSha()); assertEquals(10, snapshot.appId());
            assertFalse(snapshot.compatibilityAssessed()); assertEquals(NOW, snapshot.observedAt());
            assertEquals(List.of("/app", INSTALLATION, MINT, SCOPE, COMMIT), fixture.requests.stream().map(Captured::path).toList());
            for (int i = 0; i < 5; i++) {
                var request = fixture.requests.get(i);
                assertEquals("2026-03-10", request.apiVersion()); assertEquals("API-Compatibility-Workbench", request.agent());
                if (i < 3) assertTrue(GitHubAppCredentialsTest.validJwt(request.authorization().substring(7), pair.getPublic()));
                else assertTrue(request.authorization().equals("Bearer " + TOKEN));
                assertEquals(i == 2 ? "POST" : "GET", request.method());
            }
            assertEquals(json("{\"repository_ids\":[40],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}"), GitHubJson.parse(fixture.requests.get(2).body(), 8192));
            String output = GitHubJson.JSON.toJson(snapshot);
            assertFalse(output.contains(TOKEN)); assertFalse(output.contains("PRIVATE_")); assertFalse(output.contains("attacker"));
            assertTrue(output.contains("2026-11-14T01:00:00Z"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"app", "installation", "installation-app", "account", "owner", "all", "suspended"})
    void refusesWrongOrSuspendedInstallationBeforeMintingAToken(String changed) throws Exception {
        try (var fixture = new Fixture()) {
            var install = fixture.replies.get(INSTALLATION);
            switch (changed) {
                case "app" -> fixture.replies.get("/app").addProperty("client_id", "Iv1.other");
                case "installation" -> install.addProperty("id", 99);
                case "installation-app" -> install.addProperty("app_id", 99);
                case "account" -> install.getAsJsonObject("account").addProperty("id", 99);
                case "owner" -> install.getAsJsonObject("account").addProperty("login", "other-owner");
                case "all" -> install.addProperty("repository_selection", "all");
                case "suspended" -> install.addProperty("suspended_at", NOW.toString());
                default -> throw new IllegalArgumentException();
            }
            var failure = failure(fixture); assertEquals(Code.IDENTITY_OR_SCOPE_MISMATCH, failure.code());
            assertFalse(failure.tokenMayRemainActive()); assertTrue(fixture.requests.size() <= 2);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"write", "extra", "expired", "long-lived", "newline"})
    void refusesBroaderPermissionsBadExpiryAndHeaderInjectionBeforeUsingTheToken(String changed) throws Exception {
        try (var fixture = new Fixture()) {
            var response = fixture.replies.get(MINT);
            switch (changed) {
                case "write" -> response.getAsJsonObject("permissions").addProperty("contents", "write");
                case "extra" -> response.getAsJsonObject("permissions").addProperty("pull_requests", "write");
                case "expired" -> response.addProperty("expires_at", NOW.minusSeconds(1).toString());
                case "long-lived" -> response.addProperty("expires_at", NOW.plusSeconds(7200).toString());
                case "newline" -> response.addProperty("token", "FORGED_TOKEN\r\nInjected: value");
                default -> throw new IllegalArgumentException();
            }
            var failure = failure(fixture); assertEquals(Stage.TOKEN, failure.stage()); assertTrue(failure.tokenMayRemainActive());
            assertEquals(3, fixture.requests.size());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"count", "multiple", "repository", "account", "renamed", "owner"})
    void refusesExpandedOrChangedEffectiveRepositoryScopeBeforeReadingAnyCommit(String changed) throws Exception {
        try (var fixture = new Fixture()) {
            var scope = fixture.replies.get(SCOPE); var repository = scope.getAsJsonArray("repositories").get(0).getAsJsonObject();
            switch (changed) {
                case "count" -> scope.addProperty("total_count", 2);
                case "multiple" -> scope.getAsJsonArray("repositories").add(repository.deepCopy());
                case "repository" -> repository.addProperty("id", 99);
                case "account" -> repository.getAsJsonObject("owner").addProperty("id", 99);
                case "renamed" -> repository.addProperty("full_name", "sample-owner/renamed");
                case "owner" -> repository.getAsJsonObject("owner").addProperty("login", "other-owner");
                default -> throw new IllegalArgumentException();
            }
            var failure = failure(fixture); assertEquals(Stage.REPOSITORY_SCOPE, failure.stage());
            assertTrue(failure.tokenMayRemainActive()); assertEquals(4, fixture.requests.size());
        }
    }

    @Test void refusesDifferentCommitAndMalformedTreeEvenWhenTheProviderClaimsSuccess() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.replies.get(COMMIT).addProperty("sha", "c".repeat(40));
            assertEquals(Code.IDENTITY_OR_SCOPE_MISMATCH, failure(fixture).code());
        }
        try (var fixture = new Fixture()) {
            fixture.replies.get(COMMIT).getAsJsonObject("tree").addProperty("sha", "main");
            assertEquals(Stage.REVISION, failure(fixture).stage());
        }
    }

    @Test void expiryDuringTheProbeDoesNotRenewOrReadTheRevision() throws Exception {
        var clock = new MutableClock();
        try (var fixture = new Fixture(); var transport = fixture.transport()) {
            fixture.afterScope = () -> clock.value = NOW.plusSeconds(3601);
            var exception = assertThrows(ConnectionFailure.class, () -> new GitHubRepositoryConnection(clock).inspect(SELECTION, credentials, transport));
            assertEquals(Code.TOKEN_EXPIRED, exception.failure().code()); assertEquals(4, fixture.requests.size());
        }
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 307, 401, 403, 404, 422, 429, 500, 503})
    void classifiesFailuresWithoutRetriesAnonymousFallbackOrFollowingRedirects(int status) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.faultPath = MINT; fixture.faultStatus = status;
            fixture.faultHeaders.put("Location", fixture.origin() + "/credential-leak");
            var failure = failure(fixture); assertEquals(status, failure.httpStatus()); assertEquals(Stage.TOKEN, failure.stage());
            assertEquals(3, fixture.requests.size());
            if (status < 400) assertEquals(Code.REDIRECT_REFUSED, failure.code());
            if (status == 401) assertEquals(Code.AUTHENTICATION_FAILED, failure.code());
            if (status == 404) assertEquals(Code.NOT_FOUND_OR_NOT_INSTALLED, failure.code());
            if (status >= 500) assertTrue(failure.tokenMayRemainActive());
        }
    }

    @Test void preservesRetryAfterAndPrimaryRateLimitResetWithoutSleepingOrRetrying() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.faultPath = INSTALLATION; fixture.faultStatus = 403;
            fixture.faultHeaders.put("Retry-After", "120"); fixture.faultHeaders.put("X-RateLimit-Remaining", "0");
            fixture.faultHeaders.put("X-RateLimit-Reset", Long.toString(NOW.plusSeconds(300).getEpochSecond()));
            var result = failure(fixture); assertEquals(Code.ACCESS_DENIED_OR_RATE_LIMITED, result.code());
            assertEquals(NOW.plusSeconds(300), result.retryNotBefore()); assertEquals(2, fixture.requests.size());
            fixture.faultHeaders.put("Retry-After", "invalid");
            assertEquals(NOW.plusSeconds(300), failure(fixture).retryNotBefore());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "html", "utf8", "oversized", "null"})
    void rejectsMalformedAndOversizedResponsesBeforeAnyTokenRequest(String fault) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.faultPath = "/app"; fixture.faultStatus = 200;
            switch (fault) {
                case "duplicate" -> fixture.faultBody = "{\"id\":10,\"id\":10}".getBytes(StandardCharsets.UTF_8);
                case "html" -> fixture.contentType = "text/html";
                case "utf8" -> fixture.faultBody = new byte[]{(byte) 0xc3, 0x28};
                case "oversized" -> fixture.faultBody = new byte[GitHubTransport.MAX_BYTES + 1];
                case "null" -> fixture.faultBody = "null".getBytes(StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException();
            }
            var result = failure(fixture); assertEquals(Stage.APP, result.stage()); assertEquals(1, fixture.requests.size());
        }
    }

    @Test void boundsTheWholeTokenResponseAndReportsAnUncertainActiveToken() throws Exception {
        try (var fixture = new Fixture(); var transport = new GitHubTransport(fixture.origin(), Duration.ofSeconds(2))) {
            fixture.faultPath = MINT; fixture.faultStatus = 201; fixture.holdBody = true;
            var exception = assertThrows(ConnectionFailure.class,
                    () -> new GitHubRepositoryConnection(Clock.fixed(NOW, ZoneOffset.UTC)).inspect(SELECTION, credentials, transport));
            assertEquals(Code.TRANSPORT_INCOMPLETE, exception.failure().code());
            assertEquals(Stage.TOKEN, exception.failure().stage()); assertTrue(exception.failure().tokenMayRemainActive());
            assertEquals(3, fixture.requests.size());
        }
    }

    @Test void selectionRequiresExactHostIdentifiersAndCommitInsteadOfMutableBranchesOrUrls() throws Exception {
        Path file = temporary.resolve("selection.json"); Files.writeString(file, GitHubJson.JSON.toJson(SELECTION));
        assertEquals(SELECTION, loadSelection(file));
        for (String field : List.of("owner", "name", "commitSha")) {
            var value = GitHubJson.JSON.toJsonTree(SELECTION).getAsJsonObject(); value.addProperty(field, field.equals("commitSha") ? "main" : "../escape");
            Files.writeString(file, GitHubJson.JSON.toJson(value)); assertThrows(IllegalArgumentException.class, () -> loadSelection(file));
        }
        var value = GitHubJson.JSON.toJsonTree(SELECTION).getAsJsonObject(); value.addProperty("repositoryId", 0);
        Files.writeString(file, GitHubJson.JSON.toJson(value)); assertThrows(IllegalArgumentException.class, () -> loadSelection(file));
        value = GitHubJson.JSON.toJsonTree(SELECTION).getAsJsonObject(); value.addProperty("permissions", "write");
        Files.writeString(file, GitHubJson.JSON.toJson(value)); assertThrows(IllegalArgumentException.class, () -> loadSelection(file));
        Files.write(file, new byte[8193]); assertThrows(IllegalArgumentException.class, () -> loadSelection(file));
    }

    @Test void transportRejectsAlternateOriginsAndDoesNotUseTheSystemProxy() throws Exception {
        for (String uri : List.of("https://attacker.invalid", "http://api.github.com", "http://127.0.0.1:12/path", "http://user@127.0.0.1:12"))
            assertThrows(IllegalArgumentException.class, () -> new GitHubTransport(URI.create(uri), Duration.ofSeconds(1)));
        ProxySelector previous = ProxySelector.getDefault();
        try (var fixture = new Fixture()) {
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { throw new AssertionError("Proxy must not receive credentials"); }
                @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) {}
            });
            inspect(fixture);
        } finally { ProxySelector.setDefault(previous); }
    }
}
