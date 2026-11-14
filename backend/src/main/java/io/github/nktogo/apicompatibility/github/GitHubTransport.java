package io.github.nktogo.apicompatibility.github;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Fixed API origin, no redirects/proxy/application retries, bounded whole-response time and bytes. */
final class GitHubTransport implements AutoCloseable {
    static final URI ORIGIN = URI.create("https://api.github.com");
    static final String API_VERSION = "2026-03-10";
    static final int MAX_BYTES = 262144;
    private final URI origin;
    private final Duration timeout;
    private final HttpClient client;
    GitHubTransport() { this(ORIGIN, Duration.ofSeconds(30)); }
    // Package-private loopback injection for credential-free transport tests only.
    GitHubTransport(URI origin, Duration timeout) {
        if (!(origin.equals(ORIGIN) || ("http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost())
                && origin.getPort() > 0 && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                && origin.getRawFragment() == null && origin.getRawPath().isEmpty()))
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Unsupported GitHub transport configuration");
        this.origin = origin; this.timeout = timeout;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                }).build();
    }
    HttpResponse<byte[]> send(String method, String path, byte[] body, String credential) throws IOException, InterruptedException {
        if (!List.of("GET", "POST").contains(method) || !path.startsWith("/") || path.startsWith("//"))
            throw new IllegalArgumentException("Unsupported GitHub request");
        var request = HttpRequest.newBuilder(URI.create(origin.toString() + path)).timeout(timeout)
                .header("Authorization", "Bearer " + credential).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION).header("User-Agent", "API-Compatibility-Workbench")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (body.length > 0) request.header("Content-Type", "application/json");
        var pending = client.sendAsync(request.build(), ignored -> new LimitedBody());
        try { return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("GitHub transport did not complete"); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }
    @Override public void close() { client.shutdownNow(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("GitHub response exceeds byte limit")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
