package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * One node that cannot be checked must neither stop the run nor disappear from the summary.
 */
class CheckNodeCapabilitiesReportingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/ok", ex -> respond(ex, 200, ""));
        server.createContext("/node-good", ex -> respond(ex, 200, capabilities(
                cap("AAI", base + "/ok"))));
        server.createContext("/node-500", ex -> respond(ex, 500, "boom"));
        // capabilities whose endpoints cannot be requested at all, next to a healthy one
        server.createContext("/node-bad-capability", ex -> respond(ex, 200, capabilities(
                cap("Broken A", "no-scheme.example.org/x"),
                cap("Broken B", "ftp://example.org/x"),
                cap("Healthy", base + "/ok"))));
        server.createContext("/registry", ex -> respond(ex, 200, MAPPER.writeValueAsString(List.of(
                node("Good", base + "/node-good"),
                node("NoEndpoint", null),
                node("NoScheme", "node.example.org/api"),
                node("Http500", base + "/node-500"),
                node("BadCapability", base + "/node-bad-capability"),
                node("Last", base + "/node-good")))));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        // HEAD responses carry no body
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || bytes.length == 0 ? -1 : bytes.length);
        if (!head && bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static String cap(String type, String endpoint) {
        return "{\"capability_type\":\"%s\",\"endpoint\":\"%s\",\"version\":\"1.0.0\"}".formatted(type, endpoint);
    }

    private static String capabilities(String... caps) {
        return "{\"capabilities\":[" + String.join(",", caps) + "]}";
    }

    private static java.util.Map<String, Object> node(String name, String endpoint) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", name.toLowerCase());
        m.put("name", name);
        m.put("pid", "21.T15999/" + name);
        if (endpoint != null) {
            m.put("node_endpoint", endpoint);
        }
        m.put("legal_entity", java.util.Map.of("name", name + " Org", "ror_id", "https://ror.org/x"));
        return m;
    }

    private CheckNodeCapabilities.Result run() throws IOException {
        return CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON), dataDir,
                HttpClient.newHttpClient(), MAPPER, URI.create(base + "/registry"), java.time.Duration.ZERO);
    }

    private JsonNode summary() throws IOException {
        return MAPPER.readTree(dataDir.resolve("node_registry_summary.json").toFile());
    }

    private static JsonNode find(JsonNode summary, String name) {
        for (JsonNode n : summary.path("nodes")) {
            if (name.equals(n.path("node_name").asText())) {
                return n;
            }
        }
        throw new AssertionError("node not in summary: " + name);
    }

    @Test
    void everyListedNodeAppearsInTheSummaryInRegistryOrder() throws IOException {
        run();
        var names = new java.util.ArrayList<String>();
        summary().path("nodes").forEach(n -> names.add(n.path("node_name").asText()));
        assertEquals(List.of("Good", "NoEndpoint", "NoScheme", "Http500", "BadCapability", "Last"), names);
    }

    @Test
    void nodesAfterAProblemNodeAreStillChecked() throws IOException {
        run();
        var last = find(summary(), "Last");
        assertFalse(last.has("error"));
        assertEquals(1, last.path("available_capabilities").asInt());
    }

    @Test
    void problemNodesAreFlaggedWithAnError() throws IOException {
        var result = run();
        var s = summary();
        for (String name : List.of("NoEndpoint", "NoScheme", "Http500")) {
            var n = find(s, name);
            assertTrue(n.hasNonNull("error"), name + " should carry an error");
            assertEquals(0, n.path("total_capabilities").asInt());
        }
        assertTrue(find(s, "Http500").path("error").asText().contains("500"));
        assertEquals(List.of("NoEndpoint", "NoScheme", "Http500"),
                result.problems().stream().map(CheckNodeCapabilities.NodeProblem::node).toList());
        assertEquals(6, result.totalNodes());
    }

    @Test
    void unusableCapabilityEndpointIsNotAvailableAndDoesNotFailTheNode() throws IOException {
        run();
        var n = find(summary(), "BadCapability");
        assertFalse(n.has("error"), "the node itself is fine");
        assertEquals(3, n.path("total_capabilities").asInt());
        assertEquals(1, n.path("available_capabilities").asInt());
        assertEquals("Not available", n.path("capabilities").get(0).path("status").asText());
    }

    @Test
    void messageNamesTheProblemNodes() throws IOException {
        var message = run().message();
        assertTrue(message.contains("6 node(s)"), message);
        assertTrue(message.contains("3 with problems"), message);
        assertTrue(message.contains("NoEndpoint") && message.contains("NoScheme") && message.contains("Http500"), message);
    }

    @Test
    void failedNodeReplacesAStaleReport() throws IOException {
        Path report = dataDir.resolve("NoEndpoint").resolve("endpoint_report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"total_capabilities\":5,\"available_capabilities\":5}");
        run();
        var written = MAPPER.readTree(report.toFile());
        assertEquals(0, written.path("total_capabilities").asInt());
        assertTrue(written.hasNonNull("error"));
        assertTrue(Files.exists(dataDir.resolve("Http500").resolve("endpoint_report.json")));
    }

    @Test
    void noProblemsGivesAPlainMessage() {
        var result = new CheckNodeCapabilities.Result(11, List.of());
        assertEquals("node_registry_summary.json written: 11 node(s)", result.message());
    }
}
