package io.github.nktogo.apicompatibility.maintainer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Loopback-only read-only server for a single host-selected report snapshot. */
public final class MaintainerView implements AutoCloseable {
    private record Resource(String type, byte[] body) {}
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final String authority;
    private final Map<String, Resource> resources;

    public static MaintainerView open(Path report, int port) throws IOException {
        return new MaintainerView(RecordedCase.load(report), port);
    }
    private MaintainerView(RecordedCase.Summary report, int port) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid local port");
        resources = Map.of("/", resource("text/html", MaintainerPage.render(report)),
                "/api/report", resource("application/json", RecordedCase.JSON.toJson(report)),
                "/assets/maintainer.css", new Resource("text/css", stylesheet()));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 16);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
        authority = "127.0.0.1:" + server.getAddress().getPort();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }
    public URI uri() { return URI.create("http://" + authority + "/"); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Cross-Origin-Resource-Policy", "same-origin");
            headers.set("Cross-Origin-Opener-Policy", "same-origin");
            headers.set("Content-Security-Policy", "default-src 'none'; style-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            headers.set("Connection", "close");
            var request = exchange.getRequestHeaders();
            if (!single(request.get("Host"), authority)
                    || (request.containsKey("Origin") && !single(request.get("Origin"), "http://" + authority))
                    || (request.containsKey("Sec-Fetch-Site") && !single(request.get("Sec-Fetch-Site"), "same-origin")
                        && !single(request.get("Sec-Fetch-Site"), "none"))) {
                respond(exchange, 403, resource("text/plain", "Local same-origin access required.\n")); return;
            }
            if (!List.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
                headers.set("Allow", "GET, HEAD"); respond(exchange, 405, resource("text/plain", "Read-only endpoint.\n")); return;
            }
            if (request.containsKey("Transfer-Encoding")
                    || (request.containsKey("Content-Length") && !single(request.get("Content-Length"), "0"))) {
                respond(exchange, 400, resource("text/plain", "Request bodies are not accepted.\n")); return;
            }
            URI uri = exchange.getRequestURI();
            Resource selected = !uri.isAbsolute() && uri.getRawQuery() == null && uri.getRawFragment() == null
                    ? resources.get(uri.getRawPath()) : null;
            respond(exchange, selected == null ? 404 : 200,
                    selected == null ? resource("text/plain", "Endpoint not found.\n") : selected);
        }
    }
    private static boolean single(List<String> values, String expected) {
        return values != null && values.size() == 1 && expected.equals(values.getFirst());
    }
    private static Resource resource(String type, String body) { return new Resource(type, body.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] stylesheet() throws IOException {
        try (var stream = MaintainerView.class.getResourceAsStream("/maintainer/maintainer.css")) {
            if (stream == null) throw new IOException("Missing maintainer stylesheet");
            return stream.readAllBytes();
        }
    }
    private static void respond(HttpExchange exchange, int status, Resource resource) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", resource.type() + "; charset=utf-8");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(resource.body().length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, resource.body().length);
            exchange.getResponseBody().write(resource.body());
        }
    }
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: MaintainerView REPORT_JSON LOCAL_PORT"); System.exit(2); return;
        }
        try {
            var view = open(Path.of(args[0]), Integer.parseInt(args[1]));
            var stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { view.close(); stopped.countDown(); }));
            System.out.println("Read-only recorded case: " + view.uri());
            System.out.println("No current-input, artifact or execution authentication. Stop with Ctrl+C.");
            stopped.await();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot open the local view. Check the selected supported report and local port."); System.exit(1);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
