package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A capability may offer several endpoints, either flat on the capability or as an {@code endpoints} list.
 */
class NestedEndpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String base;
    private final AtomicBoolean nodeUp = new AtomicBoolean(true);

    private static JsonNode json(String s) throws IOException {
        return MAPPER.readTree(s);
    }

    // ── endpointsOf ─────────────────────────────────────────────────────────

    @Test
    void aFlatCapabilityIsOneRow() throws IOException {
        var rows = CheckNodeCapabilities.endpointsOf(json(
                "{\"capability_type\":\"AAI\",\"endpoint\":\"https://a/x\",\"protocol\":\"OIDC\",\"api_spec\":\"https://a/spec\"}"));
        assertEquals(List.of(new CheckNodeCapabilities.EndpointRow("https://a/x", "OIDC", "https://a/spec")), rows);
    }

    @Test
    void aNestedListGivesOneRowPerEndpointEachWithItsOwnProtocolAndSpec() throws IOException {
        // the shape Data-Terra's Node Endpoint API serves
        var rows = CheckNodeCapabilities.endpointsOf(json("""
                {"capability_type":"Service Catalogue","version":"0.2.58","status":"OPERATIONAL","endpoints":[
                  {"protocol":"REST","endpoint":"https://s/api/resources?types=Service&format=dcat","api_spec":"https://s/docs#/Resources"},
                  {"protocol":"REST","endpoint":"https://s/services","api_spec":"https://s/docs#/EEN"}]}"""));
        assertEquals(2, rows.size());
        assertEquals("https://s/api/resources?types=Service&format=dcat", rows.get(0).endpoint());
        assertEquals("https://s/docs#/Resources", rows.get(0).apiSpec());
        assertEquals("https://s/services", rows.get(1).endpoint());
        assertEquals("https://s/docs#/EEN", rows.get(1).apiSpec());
    }

    @Test
    void differentProtocolsOnOneCapabilityAreKeptApart() throws IOException {
        var rows = CheckNodeCapabilities.endpointsOf(json("""
                {"capability_type":"Resource Catalogue","endpoints":[
                  {"protocol":"REST","endpoint":"https://r/api"},{"protocol":"OAI-PMH","endpoint":"https://r/oai"}]}"""));
        assertEquals(List.of("REST", "OAI-PMH"), rows.stream().map(CheckNodeCapabilities.EndpointRow::protocol).toList());
    }

    @Test
    void anEntryWithoutItsOwnProtocolOrSpecUsesTheCapabilitys() throws IOException {
        var rows = CheckNodeCapabilities.endpointsOf(json("""
                {"capability_type":"X","protocol":"REST","api_spec":"https://x/spec","endpoints":[{"endpoint":"https://x/a"}]}"""));
        assertEquals(new CheckNodeCapabilities.EndpointRow("https://x/a", "REST", "https://x/spec"), rows.get(0));
    }

    @Test
    void plainStringEntriesAreEndpoints() throws IOException {
        var rows = CheckNodeCapabilities.endpointsOf(json("{\"capability_type\":\"X\",\"endpoints\":[\"https://x/a\",\"https://x/b\"]}"));
        assertEquals(List.of("https://x/a", "https://x/b"), rows.stream().map(CheckNodeCapabilities.EndpointRow::endpoint).toList());
    }

    @Test
    void aNodeThatGivesBothShapesGetsBothWithoutDuplicates() throws IOException {
        var rows = CheckNodeCapabilities.endpointsOf(json("""
                {"capability_type":"X","endpoint":"https://x/a","endpoints":[{"endpoint":"https://x/a"},{"endpoint":"https://x/b"}]}"""));
        assertEquals(List.of("https://x/a", "https://x/b"), rows.stream().map(CheckNodeCapabilities.EndpointRow::endpoint).toList());
    }

    @Test
    void aCapabilityWithNoEndpointStillGetsOneEmptyRow() throws IOException {
        for (String cap : List.of("{\"capability_type\":\"Monitoring\",\"status\":\"PLANNED\"}",
                "{\"capability_type\":\"Monitoring\",\"endpoints\":[]}",
                "{\"capability_type\":\"Monitoring\",\"endpoints\":[{\"protocol\":\"REST\"},null,7]}")) {
            var rows = CheckNodeCapabilities.endpointsOf(json(cap));
            assertEquals(1, rows.size(), cap);
            assertEquals("", rows.get(0).endpoint(), cap);
        }
    }

    // ── a whole run ─────────────────────────────────────────────────────────

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/up", ex -> respond(ex, 200, ""));
        server.createContext("/down", ex -> respond(ex, 503, ""));
        server.createContext("/node", ex -> {
            if (!nodeUp.get()) {
                respond(ex, 502, "bad gateway");
                return;
            }
            respond(ex, 200, """
                    {"node_endpoint":"%1$s/node","node_details":{"name":"Nested"},"capabilities":[
                      {"capability_type":"Resource Catalogue","version":"0.2.58","status":"OPERATIONAL","endpoints":[
                        {"protocol":"REST","endpoint":"%1$s/up","api_spec":"https://r/docs#/rest"},
                        {"protocol":"OAI-PMH","endpoint":"%1$s/down","api_spec":"https://r/docs#/oai"}]},
                      {"capability_type":"Service Catalogue","version":"0.2.58","status":"OPERATIONAL","endpoints":[
                        {"protocol":"REST","endpoint":"%1$s/up","api_spec":"https://s/docs#/een"}]},
                      {"capability_type":"AAI","version":null,"status":"OPERATIONAL","protocol":"OIDC","endpoint":"%1$s/up"},
                      {"capability_type":"Monitoring","version":null,"status":"PLANNED"}
                    ]}""".formatted(base));
        });
        server.createContext("/registry", ex -> respond(ex, 200, MAPPER.writeValueAsString(List.of(
                Map.of("id", "1", "name", "Nested", "pid", "21.T15999/Nested", "node_endpoint", base + "/node",
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

    private JsonNode run() throws IOException {
        CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON), dataDir,
                HttpClient.newHttpClient(), MAPPER, URI.create(base + "/registry"), Duration.ZERO);
        return MAPPER.readTree(dataDir.resolve("Nested").resolve("endpoint_report.json").toFile());
    }

    @Test
    void eachEndpointBecomesItsOwnReportRowWithItsOwnProbeResult() throws IOException {
        var caps = run().path("capabilities");
        assertEquals(5, caps.size(), "2 + 1 + 1 + 1 (the planned one has no endpoint)");

        var rest = caps.get(0);
        assertEquals("Resource Catalogue", rest.path("capability_type").asText());
        assertEquals("REST", rest.path("protocol").asText());
        assertEquals("https://r/docs#/rest", rest.path("api_spec").asText());
        assertEquals(base + "/up", rest.path("endpoint").asText());
        assertEquals("Available", rest.path("status").asText());

        var oai = caps.get(1);
        assertEquals("OAI-PMH", oai.path("protocol").asText());
        assertEquals("https://r/docs#/oai", oai.path("api_spec").asText());
        assertEquals("Not available", oai.path("status").asText());
        assertEquals(503, oai.path("http_code").asInt());

        assertEquals("Service Catalogue", caps.get(2).path("capability_type").asText());
        assertEquals("https://s/docs#/een", caps.get(2).path("api_spec").asText());
    }

    @Test
    void theCapabilitysOwnVersionAndDeclaredStatusAreOnEveryRow() throws IOException {
        var caps = run().path("capabilities");
        assertEquals("0.2.58", caps.get(0).path("version").asText());
        assertEquals("0.2.58", caps.get(1).path("version").asText());
        assertEquals("OPERATIONAL", caps.get(1).path("declared_status").asText());
        assertTrue(caps.get(3).path("version").isNull());
    }

    @Test
    void aFlatCapabilityInTheSameResponseStillWorks() throws IOException {
        var aai = run().path("capabilities").get(3);
        assertEquals("AAI", aai.path("capability_type").asText());
        assertEquals("OIDC", aai.path("protocol").asText());
        assertEquals("Available", aai.path("status").asText());
    }

    @Test
    void aCapabilityWithoutAnEndpointIsReportedNotAvailableAndKeepsItsPlannedStatus() throws IOException {
        var planned = run().path("capabilities").get(4);
        assertEquals("Monitoring", planned.path("capability_type").asText());
        assertEquals("PLANNED", planned.path("declared_status").asText());
        assertEquals("Not available", planned.path("status").asText());
        assertEquals("", planned.path("endpoint").asText());
    }

    @Test
    void totalsCountEndpoints() throws IOException {
        var report = run();
        assertEquals(5, report.path("total_capabilities").asInt());
        assertEquals(3, report.path("available_capabilities").asInt(), "up, up, up (down and planned are not)");
        assertEquals("Nested", report.path("node_details").path("name").asText());
    }

    @Test
    void theRowsSurviveAFailedFetchAsLastKnownCapabilities() throws IOException {
        run();
        nodeUp.set(false);
        var stale = run();
        assertEquals(5, stale.path("capabilities").size());
        assertTrue(stale.path("capabilities_stale").asBoolean());
        assertEquals("OAI-PMH", stale.path("capabilities").get(1).path("protocol").asText());
        assertEquals("https://r/docs#/oai", stale.path("capabilities").get(1).path("api_spec").asText());
        assertNull(stale.path("capabilities").get(0).get("endpoints"), "rows stay flat");
    }
}
