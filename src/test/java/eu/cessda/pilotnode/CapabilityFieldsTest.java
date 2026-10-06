package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Everything a node says about a capability, and about itself, is kept in its report.
 */
class CapabilityFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/up", ex -> respond(ex, 200, ""));
        server.createContext("/down", ex -> respond(ex, 503, ""));
        server.createContext("/node", ex -> respond(ex, 200, """
                {
                  "node_details": {"name": "Full Node", "url": "https://example.org", "description": "d"},
                  "capabilities": [
                    {"capability_type": "Resource Catalogue", "endpoint": "%1$s/up", "version": "0.2.56",
                     "api_spec": "https://example.org/spec", "protocol": "REST", "status": "OPERATIONAL"},
                    {"capability_type": "Resource Catalogue", "endpoint": "%1$s/down", "version": "0.2.56",
                     "api_spec": null, "protocol": "OAI-PMH", "status": "OPERATIONAL"},
                    {"capability_type": "Front Office", "endpoint": "%1$s/up", "version": null,
                     "api_spec": null, "protocol": null, "status": null},
                    {"capability_type": "AAI", "endpoint": "%1$s/up"}
                  ]
                }""".formatted(base)));
        server.createContext("/registry", ex -> respond(ex, 200, MAPPER.writeValueAsString(List.of(
                Map.of("id", "1", "name", "Full", "pid", "21.T15999/Full", "node_endpoint", base + "/node",
                        "logo", "https://example.org/logo.svg",
                        "legal_entity", Map.of("name", "Org", "ror_id", "https://ror.org/x")),
                Map.of("id", "2", "name", "NoLogo", "pid", "21.T15999/NoLogo", "node_endpoint", base + "/node",
                        "legal_entity", Map.of("name", "Org", "ror_id", "https://ror.org/y")),
                Map.of("id", "3", "name", "Broken", "pid", "21.T15999/Broken", "node_endpoint", base + "/down",
                        "logo", "https://example.org/b.svg",
                        "legal_entity", Map.of("name", "Org", "ror_id", "https://ror.org/z"))))));
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

    private JsonNode report(String node) throws IOException {
        CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON), dataDir,
                HttpClient.newHttpClient(), MAPPER, URI.create(base + "/registry"));
        return MAPPER.readTree(dataDir.resolve(node).resolve("endpoint_report.json").toFile());
    }

    @Test
    void apiSpecProtocolAndDeclaredStatusAreKept() throws IOException {
        var cap = report("Full").path("capabilities").get(0);
        assertEquals("https://example.org/spec", cap.path("api_spec").asText());
        assertEquals("REST", cap.path("protocol").asText());
        assertEquals("OPERATIONAL", cap.path("declared_status").asText());
    }

    @Test
    void declaredStatusDoesNotReplaceTheProbeStatus() throws IOException {
        var caps = report("Full").path("capabilities");
        // both capabilities declare OPERATIONAL, but one endpoint answers 503
        assertEquals("OPERATIONAL", caps.get(1).path("declared_status").asText());
        assertEquals("Not available", caps.get(1).path("status").asText());
        assertEquals(503, caps.get(1).path("http_code").asInt());
        assertEquals("Available", caps.get(0).path("status").asText());
    }

    @Test
    void missingOrNullFieldsAreJsonNullNotTheStringNull() throws IOException {
        var caps = report("Full").path("capabilities");
        for (int i : new int[]{2, 3}) {
            var cap = caps.get(i);
            for (String field : List.of("version", "api_spec", "protocol", "declared_status")) {
                assertTrue(cap.has(field), field + " should be present");
                assertTrue(cap.get(field).isNull(), "capability " + i + " " + field + " should be JSON null");
            }
        }
        assertEquals("0.2.56", caps.get(0).path("version").asText());
    }

    @Test
    void existingFieldsKeepTheirMeaning() throws IOException {
        var cap = report("Full").path("capabilities").get(0);
        assertEquals("Resource Catalogue", cap.path("capability_type").asText());
        assertEquals(base + "/up", cap.path("endpoint").asText());
        assertEquals(200, cap.path("http_code").asInt());
    }

    @Test
    void nodeLogoAndDetailsAreKept() throws IOException {
        var r = report("Full");
        assertEquals("https://example.org/logo.svg", r.path("logo").asText());
        assertEquals("Full Node", r.path("node_details").path("name").asText());
    }

    @Test
    void missingLogoIsJsonNullAndNodeDetailsAreOptional() throws IOException {
        var r = report("NoLogo");
        assertTrue(r.has("logo") && r.get("logo").isNull());
        // the other node does publish node_details, so absence is only shown for a failed node
        assertFalse(report("Broken").has("node_details"));
    }

    @Test
    void failedNodeStillShowsItsLogo() throws IOException {
        var r = report("Broken");
        assertTrue(r.hasNonNull("error"));
        assertEquals("https://example.org/b.svg", r.path("logo").asText());
    }
}
