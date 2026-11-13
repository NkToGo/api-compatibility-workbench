package io.github.nktogo.apicompatibility.maintainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MaintainerViewTest {
    @TempDir Path temporary;
    private MaintainerView start() throws IOException {
        Path path = temporary.resolve("report.json"); Files.write(path, RecordedCaseTest.bytes(RecordedCaseTest.report()));
        return MaintainerView.open(path, 0);
    }
    private static HttpResponse<String> get(HttpClient client, MaintainerView view, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(view.uri().resolve(path)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void servesOnlySnapshotProjectionsWithSecurityHeaders() throws Exception {
        try (var view = start(); var client = HttpClient.newHttpClient()) {
            var home = get(client, view, "/"); assertEquals(200, home.statusCode());
            assertEquals("127.0.0.1", view.uri().getHost());
            assertTrue(home.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            assertEquals("no-store", home.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", home.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertEquals("DENY", home.headers().firstValue("X-Frame-Options").orElseThrow());
            assertEquals("no-referrer", home.headers().firstValue("Referrer-Policy").orElseThrow());
            assertEquals("same-origin", home.headers().firstValue("Cross-Origin-Resource-Policy").orElseThrow());
            assertTrue(home.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'none'"));
            assertTrue(home.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            var api = get(client, view, "/api/report"); assertEquals(200, api.statusCode());
            assertTrue(api.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            assertTrue(api.body().contains("REGRESSION_OBSERVED")); assertFalse(api.body().contains("PRIVATE_"));
            var css = get(client, view, "/assets/maintainer.css"); assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
            assertFalse(css.body().contains("@import"));
            String head = raw(view, "HEAD /api/report HTTP/1.1\r\nHost: " + view.uri().getAuthority() + "\r\n\r\n");
            assertTrue(head.startsWith("HTTP/1.1 200")); assertTrue(head.endsWith("\r\n\r\n"));
            assertTrue(head.toLowerCase(java.util.Locale.ROOT).contains("content-length:"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/report.json", "/.env", "/assets/../report.json", "/%2e%2e/report.json",
            "/api/report?file=secret", "/?", "/api%2freport", "/assets/maintainer.css/extra"})
    void deniesArbitraryFilesAndNonExactRoutes(String route) throws Exception {
        try (var view = start()) {
            String response = raw(view, "GET " + route + " HTTP/1.1\r\nHost: " + view.uri().getAuthority() + "\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 404"), response);
            assertFalse(response.contains("PRIVATE_")); assertFalse(response.contains(temporary.toString()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Host: attacker.example", "Host: localhost", "Host: 127.0.0.1",
            "Origin: https://attacker.example", "Origin: null", "Sec-Fetch-Site: cross-site", "Sec-Fetch-Site: same-site"})
    void rejectsForeignHostsAndBrowserOrigins(String invalid) throws Exception {
        try (var view = start()) {
            String headers = invalid.startsWith("Host:") ? invalid : "Host: " + view.uri().getAuthority() + "\r\n" + invalid;
            assertTrue(raw(view, "GET /api/report HTTP/1.1\r\n" + headers + "\r\n\r\n").startsWith("HTTP/1.1 403"));
        }
    }

    @Test void rejectsDuplicateOriginAbsoluteTargetsBodiesAndMutations() throws Exception {
        try (var view = start()) {
            String host = "Host: " + view.uri().getAuthority() + "\r\n";
            assertTrue(raw(view, "GET / HTTP/1.1\r\n" + host + "Origin: " + view.uri().resolve("/").toString().replaceAll("/$", "")
                    + "\r\nOrigin: https://attacker.example\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertTrue(raw(view, "GET http://" + view.uri().getAuthority() + "/ HTTP/1.1\r\n" + host + "\r\n").startsWith("HTTP/1.1 404"));
            assertTrue(raw(view, "GET / HTTP/1.1\r\n" + host + "Content-Length: 1\r\n\r\nx").startsWith("HTTP/1.1 400"));
            assertTrue(raw(view, "GET / HTTP/1.1\r\n" + host + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n").startsWith("HTTP/1.1 400"));
            for (String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS"}) {
                String response = raw(view, method + " /api/report HTTP/1.1\r\n" + host + "\r\n");
                assertTrue(response.startsWith("HTTP/1.1 405"));
                assertTrue(response.contains("GET, HEAD"));
            }
            assertTrue(raw(view, "GET / HTTP/1.1\r\n" + host + "Origin: http://" + view.uri().getAuthority()
                    + "\r\nSec-Fetch-Site: same-origin\r\n\r\n").startsWith("HTTP/1.1 200"));
        }
    }

    @Test void snapshotDoesNotFollowLaterFileChangesAndCloseReleasesPort() throws Exception {
        int port;
        try (var view = start(); var client = HttpClient.newHttpClient()) {
            port = view.uri().getPort(); String before = get(client, view, "/api/report").body();
            Files.writeString(temporary.resolve("report.json"), "PRIVATE_REPLACEMENT");
            assertEquals(before, get(client, view, "/api/report").body());
            assertEquals("PRIVATE_REPLACEMENT", Files.readString(temporary.resolve("report.json")));
        }
        try (var replacement = MaintainerView.open(writeFreshReport(), port)) { assertEquals(port, replacement.uri().getPort()); }
        assertThrows(IllegalArgumentException.class, () -> MaintainerView.open(writeFreshReport(), -1));
    }
    private Path writeFreshReport() throws IOException {
        Path path = temporary.resolve("fresh.json"); Files.write(path, RecordedCaseTest.bytes(RecordedCaseTest.report())); return path;
    }
    private static String raw(MaintainerView view, String request) throws IOException {
        try (var socket = new Socket("127.0.0.1", view.uri().getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
