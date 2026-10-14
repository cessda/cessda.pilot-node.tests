package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** The Service Monitoring check tries the Monitoring URL the node declares before it falls back. */
class MonitoringDeclaredUrlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESULTS = "{\"data\":[{\"name\":\"Svc\",\"type\":\"SERVICEGROUP\","
            + "\"results\":[{\"uptime\":1.0,\"availability\":99.0,\"reliability\":98.0}]}]}";
    private static final String PAGE = "<html><head><script src=\"/assets/index-1.js\"></script></head></html>";

    @TempDir
    Path dataDir;

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            reply(ex, 404, "{}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void route(String path, int status, String body) {
        server.createContext(path, ex -> {
            requests.add(ex.getRequestURI().getPath());
            queries.add(String.valueOf(ex.getRequestURI().getRawQuery()));
            reply(ex, status, body);
        });
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private void endpointReport(String nodeName, String monitoringEndpoint) throws IOException {
        Path dir = dataDir.resolve("NodeOne");
        Files.createDirectories(dir);
        String capability = monitoringEndpoint == null ? ""
                : "{\"capability_type\":\"Monitoring\",\"endpoint\":\"" + monitoringEndpoint + "\"}";
        Files.writeString(dir.resolve("endpoint_report.json"),
                "{\"node_name\":\"" + nodeName + "\",\"capabilities\":[" + capability + "]}");
    }

    private JsonNode run() throws Exception {
        CheckServiceUptime.run("NodeOne", "", LocalDate.now().minusMonths(1), LocalDate.now(), dataDir, http,
                MAPPER, new CheckServiceUptime.Hosts(base + "/fallback/tenants/%s/dashboard", base));
        return MAPPER.readTree(dataDir.resolve("NodeOne/argo_uptime_report.json").toFile());
    }

    @Test
    void theDeclaredUrlIsUsedAsPublishedAndNothingIsGuessed() throws Exception {
        endpointReport("NodeOne", base + "/odd/path/tenants/ACME/view/");
        route("/odd/path/tenants/ACME/view/", 200, PAGE);
        route("/assets/index-1.js", 200, "var a=1;");
        route("/v1/public/tenants/ACME/results/groups", 200, RESULTS);

        JsonNode report = run();

        assertEquals("declared-monitoring-url", report.path("data_source").asText());
        assertEquals(base + "/odd/path/tenants/ACME/view/", report.path("api_source").asText());
        assertEquals(1, report.path("endpoints").size());
        assertFalse(requests.stream().anyMatch(p -> p.contains("/fallback/") || p.contains("capabilities/monitoring")),
                "no fallback was asked while the declared URL gave data: " + requests);
    }

    @Test
    void whenTheDeclaredUrlGivesNoDataTheNodeNameIsUsedAsFallback() throws Exception {
        endpointReport("NodeOne", base + "/declared/tenants/ACME/dashboard/");
        // declared page is missing (404); the capability API knows the node name as registered
        route("/v1/public/nodes/NodeOne/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("capability-metrics-api", report.path("data_source").asText());
        assertEquals(1, report.path("endpoints").size());
        assertTrue(requests.contains("/declared/tenants/ACME/dashboard/"), "the declared URL was tried first: " + requests);
    }

    @Test
    void theFallbackAlsoTriesTheTenantTheDeclaredUrlNames() throws Exception {
        endpointReport("NodeOne", base + "/declared/tenants/ACME/dashboard/");
        route("/v1/public/nodes/ACME/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("capability-metrics-api", report.path("data_source").asText());
        List<String> asked = requests.stream().filter(p -> p.contains("capabilities/monitoring")).toList();
        assertEquals(List.of("/v1/public/nodes/NodeOne/capabilities/monitoring/metrics",
                "/v1/public/nodes/ACME/capabilities/monitoring/metrics"), asked);
    }

    @Test
    void theNodeNameComesFromTheTopOfTheEndpointReport() throws Exception {
        endpointReport("RegistryName", null);
        route("/v1/public/nodes/RegistryName/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("capability-metrics-api", report.path("data_source").asText());
    }

    @Test
    void anEndpointWithoutATenantFallsBackWithoutBeingRequested() throws Exception {
        endpointReport("NodeOne", base + "/no-tenant/here");
        route("/v1/public/nodes/NodeOne/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("capability-metrics-api", report.path("data_source").asText());
        assertFalse(requests.contains("/no-tenant/here"), "the page cannot be mapped to a tenant, so it was not used");
    }

    @Test
    void aDeclaredPageWithNoServicesIsNotAccepted() throws Exception {
        endpointReport("NodeOne", base + "/declared/tenants/ACME/dashboard/");
        route("/declared/tenants/ACME/dashboard/", 200, PAGE);
        route("/assets/index-1.js", 200, "var a=1;");
        route("/v1/public/tenants/ACME/results/groups", 200, "{\"data\":[]}");
        route("/v1/public/nodes/NodeOne/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("capability-metrics-api", report.path("data_source").asText());
    }

    @Test
    void everythingFailingStillWritesAnUnavailableReport() throws Exception {
        endpointReport("NodeOne", base + "/declared/tenants/ACME/dashboard/");

        JsonNode report = run();

        assertEquals("unavailable", report.path("data_source").asText());
        assertTrue(report.path("warning").asText().contains("declared-monitoring-url"),
                "the warning says the declared URL was tried: " + report.path("warning").asText());
    }

    @Test
    void aDeclaredMetricsApiUrlIsUsedAndTheReportingPeriodIsAdded() throws Exception {
        endpointReport("NodeOne", base + "/v1/public/nodes/E-INFRA-CZ/capabilities/monitoring/metrics");
        route("/v1/public/nodes/E-INFRA-CZ/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("declared-monitoring-url", report.path("data_source").asText());
        assertEquals(1, report.path("endpoints").size());
        assertEquals(1, requests.size(), "nothing else was asked: " + requests);
        assertTrue(queries.get(0).contains("granularity=monthly") && queries.get(0).contains("start_date="),
                "the period was added: " + queries);
    }

    @Test
    void aDeclaredMetricsApiUrlWithItsOwnQueryIsUsedUnchanged() throws Exception {
        endpointReport("NodeOne", base + "/v1/public/nodes/E-INFRA-CZ/capabilities/monitoring/metrics?granularity=daily");
        route("/v1/public/nodes/E-INFRA-CZ/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("declared-monitoring-url", report.path("data_source").asText());
        assertEquals(List.of("granularity=daily"), queries);
    }

    @Test
    void aDeclaredMetricsApiThatFailsFallsBack() throws Exception {
        endpointReport("NodeOne", base + "/v1/public/nodes/E-WRONG/capabilities/monitoring/metrics");
        route("/v1/public/nodes/NodeOne/capabilities/monitoring/metrics", 200, RESULTS);

        JsonNode report = run();

        assertEquals("capability-metrics-api", report.path("data_source").asText());
    }
}
