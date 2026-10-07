package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The Exchange Services report must only ever list the node's own services, and say when it is a stand-in.
 */
class CatalogueServicesFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PID = "21.T15999/Data-Terra";

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** How the node's own catalogue and the Sandbox answer. */
    private volatile int ownStatus = 200;
    private volatile int sandboxStatus = 200;
    private volatile String sandboxBody;
    private volatile String ownBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        sandboxBody = list(PID);
        ownBody = list("OWN");
        server.createContext("/page", ex -> respond(ex, 200, "<html>ok</html>", "text/html"));
        server.createContext("/own", ex -> {
            requests.add("own " + query(ex));
            respond(ex, ownStatus, ownStatus == 200 ? ownBody : "no", "application/json");
        });
        server.createContext("/sandbox", ex -> {
            requests.add("sandbox " + query(ex));
            respond(ex, sandboxStatus, sandboxBody, "application/json");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String query(HttpExchange ex) {
        String raw = ex.getRequestURI().getRawQuery();
        return ex.getRequestURI().getPath() + (raw == null ? "" : "?" + URLDecoder.decode(raw, StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** A service list whose entries belong to the given nodes (one service per PID given). */
    private String list(String... nodePids) {
        var items = MAPPER.createArrayNode();
        int i = 0;
        for (String n : nodePids) {
            items.addObject().put("name", "Service " + (++i) + " of " + n).put("id", "service/" + i)
                    .put("nodePID", n).put("webpage", base + "/page");
        }
        var root = MAPPER.createObjectNode();
        root.put("total", nodePids.length);
        root.set("results", items);
        return root.toString();
    }

    private JsonNode run(String nodePid, String endpoint) throws Exception {
        CheckCatalogueServices.run(dataDir, "Node", nodePid, URI.create(base + endpoint), 10,
                HttpClient.newHttpClient(), MAPPER, URI.create(base + "/sandbox"));
        return MAPPER.readTree(dataDir.resolve("Node").resolve("catalogue_services_report.json").toFile());
    }

    // ── the URL ────────────────────────────────────────────────────────────

    @Test
    void anEndpointThatIsAlreadyTheServiceListIsNotExtended() {
        for (String endpoint : List.of("https://c.example/api/service/all", "https://c.example/api/service/all/")) {
            assertEquals("https://c.example/api/service/all",
                    CheckCatalogueServices.buildApiServiceUrl(URI.create(endpoint)).toString());
        }
    }

    @Test
    void theUsualEndpointShapesStillWork() {
        for (String endpoint : List.of("https://c.example", "https://c.example/", "https://c.example/api",
                "https://c.example/api/")) {
            assertEquals("https://c.example/api/service/all",
                    CheckCatalogueServices.buildApiServiceUrl(URI.create(endpoint)).toString(), endpoint);
        }
    }

    // ── the primary catalogue ──────────────────────────────────────────────

    @Test
    void whenTheNodesOwnCatalogueAnswersNothingElseIsAsked() throws Exception {
        var report = run(PID, "/own/api");
        assertEquals(1, report.path("total_services").asInt());
        assertFalse(report.has("fallback"));
        assertFalse(report.has("note"));
        assertEquals(List.of("own /own/api/service/all"), requests);
    }

    @Test
    void anOwnEndpointThatIsTheListItselfIsReadDirectly() throws Exception {
        var report = run(PID, "/own/api/service/all");
        assertFalse(report.has("fallback"));
        assertEquals(List.of("own /own/api/service/all"), requests);
    }

    // ── the Sandbox fallback ───────────────────────────────────────────────

    @Test
    void theSandboxIsAskedForTheNodeNotForAKeyword() throws Exception {
        ownStatus = 404;
        run(PID, "/own/api");
        String sandbox = requests.stream().filter(r -> r.startsWith("sandbox")).findFirst().orElseThrow();
        assertTrue(sandbox.contains("node=" + PID), sandbox);
        assertFalse(sandbox.contains("keyword"), sandbox);
    }

    @Test
    void theFallbackReportSaysItIsAFallbackAndWhy() throws Exception {
        ownStatus = 404;
        var report = run(PID, "/own/api");
        assertTrue(report.path("fallback").asBoolean());
        assertTrue(report.path("fallback_reason").asText().contains("404"));
        assertTrue(report.path("note").asText().contains(PID));
        assertTrue(report.path("api_source").asText().contains("/sandbox/"));
        assertEquals(1, report.path("services").size());
    }

    @Test
    void servicesOfOtherNodesAreNeverReportedEvenIfTheSandboxReturnsThem() throws Exception {
        ownStatus = 404;
        // a backend that ignores the filter and answers with a mixed list
        sandboxBody = list("21.T15999/METROFOOD", PID, "21.T15999/ENES", "21.t15999/data-terra");
        var report = run(PID, "/own/api");
        assertEquals(2, report.path("services").size(), "only Data-Terra's two (case-insensitive)");
        assertEquals(2, report.path("total_services").asInt());
        for (JsonNode s : report.path("services")) {
            assertTrue(s.path("name").asText().contains("Data-Terra") || s.path("name").asText().contains("data-terra"),
                    s.path("name").asText());
        }
    }

    @Test
    void aNodeWithNoServicesInTheSandboxGetsAnEmptyReportNotSomeoneElsesServices() throws Exception {
        ownStatus = 404;
        sandboxBody = list();                       // the node is not registered there
        var report = run(PID, "/own/api");
        assertEquals(0, report.path("total_services").asInt());
        assertEquals(0, report.path("services").size());
        assertTrue(report.path("fallback").asBoolean());
    }

    @Test
    void withoutANodePidThereIsNoSafeFallback() {
        ownStatus = 404;
        var e = assertThrows(IOException.class, () -> run(null, "/own/api"));
        assertTrue(e.getMessage().contains("node_pid"), e.getMessage());
        assertTrue(requests.stream().noneMatch(r -> r.startsWith("sandbox")), "the Sandbox must not be searched");
        assertFalse(Files.exists(dataDir.resolve("Node").resolve("catalogue_services_report.json")));
    }

    @Test
    void ifTheSandboxFailsTooTheRunFails() {
        ownStatus = 404;
        sandboxStatus = 500;
        assertThrows(IOException.class, () -> run(PID, "/own/api"));
    }

    @Test
    void aServiceWithAnUnusableWebpageIsFlaggedAndDoesNotStopTheReport() throws Exception {
        ownBody = "{\"total\":2,\"results\":[{\"name\":\"No scheme\",\"id\":\"s/1\",\"webpage\":\"www.example.org/x\"},"
                + "{\"name\":\"Fine\",\"id\":\"s/2\",\"webpage\":\"" + base + "/page\"}]}";
        var report = run(PID, "/own/api");
        assertEquals(2, report.path("services").size());
        assertTrue(report.path("services").get(0).path("status").asText().startsWith("Webpage has an invalid URL"));
        assertEquals("Available", report.path("services").get(1).path("status").asText());
    }

    @Test
    void thePrimaryReportHasNoFallbackFields() throws Exception {
        var report = run(PID, "/own/api");
        assertNull(report.get("fallback_reason"));
    }
}
