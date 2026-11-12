package eu.cessda.pilotnode.core;

import eu.cessda.pilotnode.core.lenient.CheckCoreIntegrations;
import eu.cessda.pilotnode.core.StrictCoreIntegrations;
import eu.cessda.pilotnode.core.ArgoStatus;
import static eu.cessda.pilotnode.catalogue.FakeNode.MAPPER;
import static eu.cessda.pilotnode.catalogue.FakeNode.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.catalogue.FakeNode;

/** The strict Core Service integrations check: exact group name, every status that is not OK a finding. */
class StrictCoreIntegrationsTest {

    private static final String FEED = "/v1/public/tenants/T/status/Default/endpoints";

    @TempDir
    Path dataDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private FakeNode node;
    private ArgoStatus.Source source;

    @BeforeEach
    void start() throws Exception {
        node = new FakeNode();
        source = new ArgoStatus.Source(node.base(), "T", "https://ui.example");
    }

    @AfterEach
    void stop() {
        node.close();
    }

    /** A feed with one group holding one endpoint per {@code type, url, statuses...} given. */
    private JsonNode feed(String group, String type, String url, String... statuses) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode g = root.putArray("groups").addObject().put("name", group);
        ObjectNode serviceType = g.putArray("service-types").addObject().put("name", type);
        ObjectNode endpoint = serviceType.putArray("endpoints").addObject().put("name", type + "-1");
        endpoint.putObject("info").put("capability_type", type).put("URL", url);
        ArrayNode list = endpoint.putArray("statuses");
        for (int i = 0; i < statuses.length; i++) {
            list.addObject().put("timestamp", "2026-11-11T0" + i + ":00:00Z").put("value", statuses[i]);
        }
        return root;
    }

    private void declared(String type, String url) throws IOException {
        Path dir = dataDir.resolve("N");
        Files.createDirectories(dir);
        MAPPER.writeValue(dir.resolve("endpoint_report.json").toFile(),
                FakeNode.strictReport("21.T15999/N", row(type, url, "OPERATIONAL")));
    }

    private void declared(String type, String url, String status) throws IOException {
        Path dir = dataDir.resolve("N");
        Files.createDirectories(dir);
        MAPPER.writeValue(dir.resolve("endpoint_report.json").toFile(),
                FakeNode.strictReport("21.T15999/N", row(type, url, status)));
    }

    private JsonNode run() throws Exception {
        StrictCoreIntegrations.run(dataDir, "N", source, http, MAPPER);
        return MAPPER.readTree(dataDir.resolve("N/core_integrations_report.json").toFile());
    }

    private static List<String> codes(JsonNode report) {
        List<String> out = new ArrayList<>();
        report.path("findings").forEach(f -> out.add(f.path("code").asText()));
        return out;
    }

    @Test
    void anEndpointThatIsOkAndDeclaredGivesNoFindings() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "OK", "OK"));
        declared("AAI", "https://n.example/aai");
        JsonNode report = run();
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of(), codes(report));
        assertEquals("strict", report.path("mode").asText());
        assertEquals(1, report.path("ok_endpoints").asInt());
    }

    @Test
    void aCriticalEndpointIsAnError() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "OK", "CRITICAL"));
        declared("AAI", "https://n.example/aai");
        JsonNode report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(List.of("core-endpoint-critical"), codes(report));
        assertEquals("https://n.example/aai", report.path("findings").get(0).path("url").asText());
    }

    @Test
    void aWarningEndpointIsOnlyAWarning() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "WARNING"));
        declared("AAI", "https://n.example/aai");
        JsonNode report = run();
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of("core-endpoint-warning"), codes(report));
    }

    @Test
    void anEndpointThatRecoveredIsANote() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "CRITICAL", "OK"));
        declared("AAI", "https://n.example/aai");
        JsonNode report = run();
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of("core-endpoint-recovered"), codes(report));
        assertEquals("INFO", report.path("findings").get(0).path("severity").asText());
    }

    @Test
    void aGroupNamedInAnotherCaseIsNotTheNode() throws Exception {
        node.json(FEED, feed("n", "AAI", "https://n.example/aai", "OK"));
        declared("AAI", "https://n.example/aai");
        JsonNode report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(List.of("node-not-monitored"), codes(report));
        assertTrue(report.path("findings").get(0).path("actual").asText().contains("different case"));
        assertEquals(0, report.path("endpoints").size());
        // lenient mode accepts the same feed
        CheckCoreIntegrations.run(dataDir, "N", source, http, MAPPER);
        assertEquals(1, MAPPER.readTree(dataDir.resolve("N/lenient/core_integrations_report.json").toFile()).path("endpoints").size());
    }

    @Test
    void aFeedThatCannotBeReadIsAFindingNotAnException() throws Exception {
        node.status(FEED, 502);
        JsonNode report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(List.of("argo-feed-unavailable"), codes(report));
        assertTrue(report.path("findings").get(0).path("actual").asText().contains("502"));
    }

    @Test
    void aCriticalEndpointThatTheNodeDeclaresUnavailableIsANoteNotAnError() throws Exception {
        node.json(FEED, feed("N", "Helpdesk", "https://n.example/help", "OK", "CRITICAL"));
        declared("Helpdesk", "https://n.example/help", "UNAVAILABLE");
        JsonNode report = run();
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of("declared-unavailable-confirmed"), codes(report));
        assertEquals("INFO", report.path("findings").get(0).path("severity").asText());
    }

    @Test
    void anOkEndpointThatTheNodeDeclaresUnavailableIsAWarning() throws Exception {
        node.json(FEED, feed("N", "Helpdesk", "https://n.example/help", "OK"));
        declared("Helpdesk", "https://n.example/help", "UNAVAILABLE");
        assertEquals(List.of("declared-unavailable-but-ok"), codes(run()));
    }

    @Test
    void aMonitoredUrlThatDiffersFromTheDeclaredOneIsAWarning() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/old-aai", "OK"));
        declared("AAI", "https://n.example/aai");
        JsonNode report = run();
        assertEquals(List.of("monitored-url-differs"), codes(report));
        assertEquals("https://n.example/aai", report.path("findings").get(0).path("expected").asText());
    }

    @Test
    void theMonitoringCapabilityIsNotComparedBecauseItIsAboutTheNodesOwnServices() throws Exception {
        node.json(FEED, feed("N", "Monitoring", "https://argo.example/tenants/N/dashboard/", "OK"));
        declared("Monitoring", "https://argo-api.example/v1/public/nodes/N/capabilities/monitoring/metrics");
        assertEquals(List.of(), codes(run()));
    }

    @Test
    void aMonitoredCapabilityTheNodeDoesNotDeclareIsANote() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "OK"));
        declared("Helpdesk", "https://n.example/help");
        assertEquals(List.of("monitored-capability-not-declared"), codes(run()));
    }

    @Test
    void withoutAStrictEndpointReportTheComparisonIsNotDoneAndSaysSo() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "OK"));
        JsonNode report = run();
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(List.of("declared-capabilities-not-compared"), codes(report));
    }

    @Test
    void theResultIsWrittenUnderTheStrictDirectoryOnly() throws Exception {
        node.json(FEED, feed("N", "AAI", "https://n.example/aai", "OK"));
        run();
        assertFalse(Files.exists(dataDir.resolve("N").resolve("lenient").resolve("core_integrations_report.json")));
    }
}
