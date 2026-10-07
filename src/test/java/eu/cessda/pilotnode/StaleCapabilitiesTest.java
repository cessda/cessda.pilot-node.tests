package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A failed fetch of a node's capability list must not wipe what we already knew about the node.
 */
class StaleCapabilitiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String base;

    /** What the node endpoint answers next; a negative value means "fail this many times, then recover". */
    private volatile int nodeStatus = 200;
    private volatile int failuresBeforeRecovery = 0;
    private final AtomicInteger nodeRequests = new AtomicInteger();
    private volatile int capabilityStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/cap", ex -> respond(ex, capabilityStatus, ""));
        server.createContext("/node", ex -> {
            int n = nodeRequests.incrementAndGet();
            if (failuresBeforeRecovery > 0 && n <= failuresBeforeRecovery) {
                respond(ex, 502, "bad gateway");
            } else if (nodeStatus != 200) {
                respond(ex, nodeStatus, "error");
            } else {
                respond(ex, 200, """
                        {"capabilities": [
                          {"capability_type": "Resource Catalogue", "endpoint": "%1$s/cap", "version": "6.2.0",
                           "api_spec": "https://example.org/spec", "protocol": "REST", "status": "OPERATIONAL"},
                          {"capability_type": "Front Office", "endpoint": "%1$s/cap", "version": "3.63.0"}
                        ], "node_details": {"name": "N"}}""".formatted(base));
            }
        });
        server.createContext("/registry", ex -> respond(ex, 200, MAPPER.writeValueAsString(List.of(
                Map.of("id", "1", "name", "Node", "pid", "21.T15999/Node", "node_endpoint", base + "/node",
                        "legal_entity", Map.of("name", "Org", "ror_id", "https://ror.org/x"))))));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, head || bytes.length == 0 ? -1 : bytes.length);
        if (!head && bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private CheckNodeCapabilities.Result run() throws IOException {
        return CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON), dataDir,
                HttpClient.newHttpClient(), MAPPER, URI.create(base + "/registry"), Duration.ZERO);
    }

    private JsonNode report() throws IOException {
        return MAPPER.readTree(dataDir.resolve("Node").resolve("endpoint_report.json").toFile());
    }

    // ── retry ────────────────────────────────────────────────────────────────

    @Test
    void aMomentaryBadGatewayIsRetriedAndLeavesNoTrace() throws IOException {
        failuresBeforeRecovery = 2;
        var result = run();
        assertEquals(3, nodeRequests.get(), "two 502s, then the answer");
        assertTrue(result.problems().isEmpty());
        assertFalse(report().has("error"));
        assertEquals(2, report().path("capabilities").size());
    }

    @Test
    void serverErrorsAreRetriedAThirdTimeAtMost() throws IOException {
        nodeStatus = 502;
        run();
        assertEquals(3, nodeRequests.get());
    }

    @Test
    void clientErrorsAreNotRetried() throws IOException {
        nodeStatus = 404;
        run();
        assertEquals(1, nodeRequests.get());
    }

    // ── stale capabilities ───────────────────────────────────────────────────

    @Test
    void aFailedFetchKeepsTheLastKnownCapabilitiesAndMarksThemStale() throws IOException {
        run();
        String firstGenerated = report().path("generated").asText();
        assertFalse(report().has("capabilities_stale"));

        nodeStatus = 502;
        var result = run();

        var r = report();
        assertEquals(2, r.path("capabilities").size(), "capabilities kept");
        assertEquals("Resource Catalogue", r.path("capabilities").get(0).path("capability_type").asText());
        assertEquals("6.2.0", r.path("capabilities").get(0).path("version").asText());
        assertEquals("https://example.org/spec", r.path("capabilities").get(0).path("api_spec").asText());
        assertEquals("OPERATIONAL", r.path("capabilities").get(0).path("declared_status").asText());
        assertEquals("N", r.path("node_details").path("name").asText());
        assertTrue(r.path("capabilities_stale").asBoolean());
        assertEquals(firstGenerated, r.path("capabilities_last_fetched").asText());
        assertTrue(r.path("error").asText().contains("502"));

        // the node is still flagged, and the message says its data is old
        assertEquals(1, result.problems().size());
        assertTrue(result.message().contains("502") && result.message().contains("last fetched"), result.message());
    }

    @Test
    void staleCapabilitiesAreProbedAgainSoTheirStatusIsCurrent() throws IOException {
        run();
        assertEquals("Available", report().path("capabilities").get(0).path("status").asText());

        nodeStatus = 502;
        capabilityStatus = 503;
        run();
        var r = report();
        assertEquals("Not available", r.path("capabilities").get(0).path("status").asText());
        assertEquals(503, r.path("capabilities").get(0).path("http_code").asInt());
        assertEquals(0, r.path("available_capabilities").asInt());
        assertEquals(2, r.path("total_capabilities").asInt());
    }

    @Test
    void repeatedFailuresKeepPointingAtTheLastSuccessfulFetch() throws IOException {
        run();
        String firstGenerated = report().path("generated").asText();
        nodeStatus = 502;
        run();
        run();
        assertEquals(firstGenerated, report().path("capabilities_last_fetched").asText());
        assertEquals(2, report().path("capabilities").size());
    }

    @Test
    void recoveryClearsTheStaleMarkers() throws IOException {
        run();
        nodeStatus = 502;
        run();
        nodeStatus = 200;
        var result = run();
        var r = report();
        assertTrue(result.problems().isEmpty());
        assertFalse(r.has("error"));
        assertFalse(r.has("capabilities_stale"));
        assertFalse(r.has("capabilities_last_fetched"));
    }

    @Test
    void withNothingKnownYetAFailureStillGivesAnEmptyFlaggedReport() throws IOException {
        nodeStatus = 502;
        run();
        var r = report();
        assertEquals(0, r.path("capabilities").size());
        assertTrue(r.path("error").asText().contains("502"));
        assertFalse(r.has("capabilities_stale"));
    }

    @Test
    void anEmptyFailureReportIsNotTreatedAsKnowledge() throws IOException {
        nodeStatus = 502;
        run();            // writes an empty, flagged report
        run();            // must not "keep" those zero capabilities as if they were real
        assertEquals(0, report().path("capabilities").size());
        assertFalse(report().has("capabilities_stale"));
    }

    @Test
    void aCorruptPreviousReportIsIgnored() throws IOException {
        Files.createDirectories(dataDir.resolve("Node"));
        Files.writeString(dataDir.resolve("Node").resolve("endpoint_report.json"), "{ not json");
        nodeStatus = 502;
        run();
        assertTrue(report().path("error").asText().contains("502"));
    }

    // ── Check All says why ───────────────────────────────────────────────────

    @Test
    void exchangeServicesSkipNamesTheNodeEndpointError() throws IOException {
        nodeStatus = 502;
        run();           // no previous data, so the report is empty and flagged
        var ex = assertThrows(Exception.class, () -> CheckAll.runCatalogueServices(dataDir, "Node",
                eu.cessda.pilotnode.catalogue.CatalogueSelector.standard(List.of("Service Catalogue", "Resource Catalogue"),
                        HttpClient.newHttpClient(), MAPPER), MAPPER, HttpClient.newHttpClient()));
        assertTrue(ex.getMessage().contains("502"), ex.getMessage());
    }
}
