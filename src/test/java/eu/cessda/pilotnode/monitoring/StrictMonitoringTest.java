package eu.cessda.pilotnode.monitoring;

import static eu.cessda.pilotnode.catalogue.FakeNode.MAPPER;
import static eu.cessda.pilotnode.catalogue.FakeNode.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import eu.cessda.pilotnode.catalogue.FakeNode;

/** The strict Service Monitoring check: the declared endpoint as published, nothing guessed, every problem a finding. */
class StrictMonitoringTest {

    private static final String RESULTS = "{\"data\":[{\"name\":\"Svc\",\"type\":\"SERVICEGROUP\","
            + "\"results\":[{\"uptime\":1.0,\"availability\":99.0,\"reliability\":98.0}]}]}";
    private static final String METRICS = "/v1/public/nodes/NodeOne/capabilities/monitoring/metrics";

    @TempDir
    Path dataDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private FakeNode node;

    @BeforeEach
    void start() throws Exception {
        node = new FakeNode();
    }

    @AfterEach
    void stop() {
        node.close();
    }

    private void strictEndpointReport(String monitoringEndpoint) throws IOException {
        Path dir = dataDir.resolve("NodeOne");
        Files.createDirectories(dir);
        var rows = monitoringEndpoint == null
                ? new com.fasterxml.jackson.databind.node.ObjectNode[0]
                : new com.fasterxml.jackson.databind.node.ObjectNode[]{row("Monitoring", monitoringEndpoint, "OPERATIONAL")};
        MAPPER.writeValue(dir.resolve("endpoint_report.json").toFile(), FakeNode.strictReport("21.T15999/N", rows));
    }

    private JsonNode run() throws Exception {
        StrictServiceUptime.run("NodeOne", dataDir, http, MAPPER);
        return MAPPER.readTree(dataDir.resolve("NodeOne/argo_uptime_report.json").toFile());
    }

    private static List<String> codes(JsonNode report) {
        List<String> out = new ArrayList<>();
        report.path("findings").forEach(f -> out.add(f.path("code").asText()));
        return out;
    }

    @Test
    void itNeedsAStrictEndpointReport() {
        var e = assertThrows(IOException.class, this::run);
        assertTrue(e.getMessage().contains("run Node Capabilities in strict mode first"), e.getMessage());
    }

    @Test
    void aNodeThatDeclaresNoMonitoringEndpointFailsAndNothingIsRequested() throws Exception {
        strictEndpointReport(null);
        JsonNode report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(List.of("monitoring-endpoint-missing"), codes(report));
        assertEquals("strict", report.path("mode").asText());
        assertTrue(node.requests.isEmpty());
    }

    @Test
    void aDashboardPageIsNotADataApiAndIsNotScraped() throws Exception {
        strictEndpointReport(node.base() + "/tenants/ACME/dashboard/");
        JsonNode report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(List.of("monitoring-endpoint-not-a-data-api"), codes(report));
        assertTrue(node.requests.isEmpty(), "no request: " + node.requests);
        assertEquals("unavailable", report.path("data_source").asText());
    }

    @Test
    void theMetricsApiIsRequestedAsPublishedWithAPeriodItNames() throws Exception {
        node.json(METRICS, MAPPER.readTree(RESULTS));
        strictEndpointReport(node.base() + METRICS + "?start-date=2026-10-01&end-date=2026-10-31&granularity=monthly");
        JsonNode report = run();
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of(), codes(report));
        assertEquals("declared-monitoring-url", report.path("data_source").asText());
        assertEquals(1, report.path("endpoints").size());
        assertEquals(List.of(METRICS + "?start-date=2026-10-01&end-date=2026-10-31&granularity=monthly"), node.requests);
    }

    @Test
    void aUrlWithNoPeriodGetsTheLastMonthAndANote() throws Exception {
        String period = "start-date=2026-10-12&end-date=2026-11-12";
        node.json(METRICS, MAPPER.readTree(RESULTS));
        strictEndpointReport(node.base() + METRICS);
        StrictServiceUptime.run("NodeOne", dataDir, http, MAPPER, java.time.LocalDate.of(2026, 11, 12));
        JsonNode report = MAPPER.readTree(dataDir.resolve("NodeOne/argo_uptime_report.json").toFile());
        assertEquals(List.of(METRICS + "?" + period), node.requests);
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of("monitoring-period-added"), codes(report));
        assertEquals("INFO", report.path("findings").get(0).path("severity").asText());
        assertEquals("2026-10-12", report.path("period_start").asText());
        assertEquals("2026-11-12", report.path("period_end").asText());
        assertTrue(report.path("resolved_data_endpoint").asText().endsWith(period));
        assertEquals(node.base() + METRICS, report.path("api_source").asText(), "api_source stays as published");
    }

    @Test
    void aQueryWithoutAPeriodKeepsItsParametersAndGetsThePeriodAdded() throws Exception {
        node.json(METRICS, MAPPER.readTree(RESULTS));
        strictEndpointReport(node.base() + METRICS + "?granularity=daily");
        StrictServiceUptime.run("NodeOne", dataDir, http, MAPPER, java.time.LocalDate.of(2026, 11, 12));
        assertEquals(List.of(METRICS + "?granularity=daily&start-date=2026-10-12&end-date=2026-11-12"), node.requests);
    }

    @Test
    void anHttpErrorIsAFinding() throws Exception {
        node.status(METRICS, 500);
        strictEndpointReport(node.base() + METRICS + "?start-date=2026-10-01");
        JsonNode report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertTrue(codes(report).contains("endpoint-http-error"), codes(report).toString());
        assertEquals(1, node.requests.size(), "no fallback: " + node.requests);
    }

    @Test
    void aListOfNoServicesIsAFinding() throws Exception {
        node.json(METRICS, MAPPER.readTree("{\"data\":[]}"));
        strictEndpointReport(node.base() + METRICS + "?start-date=2026-10-01");
        assertTrue(codes(run()).contains("monitoring-no-services"));
    }

    @Test
    void aPayloadOfTheWrongShapeIsAFinding() throws Exception {
        node.json(METRICS, MAPPER.readTree("{\"nope\":1}"));
        strictEndpointReport(node.base() + METRICS + "?start-date=2026-10-01");
        assertTrue(codes(run()).contains("monitoring-payload-unreadable"));
    }

    @Test
    void theResultIsWrittenUnderTheStrictDirectoryOnly() throws Exception {
        node.json(METRICS, MAPPER.readTree(RESULTS));
        strictEndpointReport(node.base() + METRICS + "?start-date=2026-10-01");
        run();
        assertFalse(Files.exists(dataDir.resolve("NodeOne").resolve("lenient").resolve("argo_uptime_report.json")));
    }
}
