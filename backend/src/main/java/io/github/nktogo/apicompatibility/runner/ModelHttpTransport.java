package io.github.nktogo.apicompatibility.runner;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** No redirects, proxy, tools, SDK retries or unbounded response buffering. */
final class ModelHttpTransport {
    static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private final URI endpoint;
    private final Duration timeout;
    ModelHttpTransport() { this(ENDPOINT, Duration.ofSeconds(90)); }
    // Test-only loopback endpoint; production callers cannot supply arbitrary destinations.
    ModelHttpTransport(URI endpoint, Duration timeout) {
        CaseInputs.require(endpoint.equals(ENDPOINT) || ("http".equals(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost()) && endpoint.getUserInfo() == null), "Unsupported model endpoint");
        this.endpoint = endpoint; this.timeout = timeout;
    }

    HttpResponse<byte[]> send(byte[] body, String key, String callId, BooleanSupplier permitted)
            throws InterruptedException, ExecutionException, TimeoutException {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) {}
                }).build();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .header("X-Client-Request-Id", callId).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            if (!permitted.getAsBoolean()) throw new CancellationException();
            future = client.sendAsync(request, ignored -> new LimitedBody());
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (!permitted.getAsBoolean()) throw new CancellationException();
                if (System.nanoTime() >= deadline) throw new TimeoutException();
                try { return future.get(50, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { /* Recheck authorization and the whole-body deadline. */ }
            }
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            client.shutdownNow();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > 262144 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response limit exceeded")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
