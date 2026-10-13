package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class CapabilityProbeTest {

    private HttpServer server;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // GET-only API, like a FastAPI service catalogue
        server.createContext("/get-only", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders("GET".equals(ex.getRequestMethod()) ? 200 : 405, -1);
            ex.close();
        });
        server.createContext("/head-ok", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/not-implemented", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders("GET".equals(ex.getRequestMethod()) ? 204 : 501, -1);
            ex.close();
        });
        // GET-only route that a HEAD request cannot reach, so the server says 404
        server.createContext("/head-404", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders("GET".equals(ex.getRequestMethod()) ? 200 : 404, -1);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/forbidden", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.createContext("/broken", ex -> {
            methods.add(ex.getRequestMethod());
            ex.sendResponseHeaders("GET".equals(ex.getRequestMethod()) ? 500 : 405, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void headSupportedNoRetry() {
        var status = CheckNodeCapabilities.probe(http, uri("/head-ok"));
        assertTrue(status.available());
        assertEquals(200, status.httpCode());
        assertEquals(List.of("HEAD"), methods);
    }

    @Test
    void methodNotAllowedFallsBackToGet() {
        var status = CheckNodeCapabilities.probe(http, uri("/get-only"));
        assertTrue(status.available(), "a GET-only endpoint that works must be reported available");
        assertEquals(200, status.httpCode());
        assertEquals(List.of("HEAD", "GET"), methods);
    }

    @Test
    void notImplementedFallsBackToGet() {
        var status = CheckNodeCapabilities.probe(http, uri("/not-implemented"));
        assertTrue(status.available());
        assertEquals(204, status.httpCode());
        assertEquals(List.of("HEAD", "GET"), methods);
    }

    @Test
    void notFoundOnHeadFallsBackToGet() {
        var status = CheckNodeCapabilities.probe(http, uri("/head-404"));
        assertTrue(status.available());
        assertEquals(200, status.httpCode());
        assertEquals(List.of("HEAD", "GET"), methods);
    }

    @Test
    void genuinelyMissingEndpointStillReportsNotFound() {
        var status = CheckNodeCapabilities.probe(http, uri("/missing"));
        assertFalse(status.available());
        assertEquals(404, status.httpCode());
        assertEquals(List.of("HEAD", "GET"), methods);
    }

    @Test
    void otherErrorsAreNotRetried() {
        var status = CheckNodeCapabilities.probe(http, uri("/forbidden"));
        assertFalse(status.available());
        assertEquals(403, status.httpCode());
        assertEquals(List.of("HEAD"), methods);
    }

    @Test
    void failingGetStillReportsNotAvailable() {
        var status = CheckNodeCapabilities.probe(http, uri("/broken"));
        assertFalse(status.available());
        assertEquals(500, status.httpCode());
        assertEquals(List.of("HEAD", "GET"), methods);
    }
}
