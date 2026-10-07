package eu.cessda.pilotnode;

import static eu.cessda.pilotnode.catalogue.FakeNode.MAPPER;
import static eu.cessda.pilotnode.catalogue.FakeNode.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.catalogue.CatalogueSelector;
import eu.cessda.pilotnode.catalogue.FakeNode;

/** From a node's endpoint report to its Exchange Services report. */
class CatalogueSelectionReportTest {

    private static final String PID = "21.T15999/Data-Terra";

    @TempDir
    Path dataDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final CatalogueSelector selector =
            CatalogueSelector.standard(List.of("Service Catalogue", "Resource Catalogue"), http, MAPPER);
    private FakeNode node;

    @BeforeEach
    void start() throws Exception {
        node = new FakeNode();
        node.route("/page", q -> new FakeNode.Reply(200, "{\"ok\":true}"));
    }

    @AfterEach
    void stop() {
        node.close();
    }

    /** Lot-1 services whose webpages are on the fake node, so that the webpage checks stay local. */
    private List<ObjectNode> services(int count, String category) {
        List<ObjectNode> list = FakeNode.lot1Services(count, category);
        list.forEach(s -> s.put("webpage", node.base() + "/page"));
        return list;
    }

    private void writeEndpointReport(ObjectNode... rows) throws IOException {
        Files.createDirectories(dataDir.resolve("N"));
        MAPPER.writeValue(dataDir.resolve("N").resolve("endpoint_report.json").toFile(), FakeNode.report(PID, rows));
    }

    private JsonNode run(String nodePid) throws Exception {
        CheckCatalogueServices.runSelected(dataDir, "N", nodePid, 10, selector, http, MAPPER,
                URI.create(node.base() + "/sandbox"));
        return MAPPER.readTree(dataDir.resolve("N").resolve("catalogue_services_report.json").toFile());
    }

    @Test
    void dataTerrasLayoutGivesItsServicesWithProvenance() throws Exception {
        node.json("/svc/dcat", FakeNode.dcatFeed());
        node.lot1("/svc/services", services(21, FakeNode.V2_CATEGORY));
        node.json("/res/dcat", FakeNode.dcatFeed());
        writeEndpointReport(
                row("Resource Catalogue", node.base() + "/res/dcat", "OPERATIONAL"),
                row("Service Catalogue", node.base() + "/svc/dcat", "OPERATIONAL"),
                row("Service Catalogue", node.base() + "/svc/services", "OPERATIONAL"));

        var report = run(null);

        assertEquals(21, report.path("total_services").asInt());
        assertEquals(21, report.path("services").size());
        assertEquals(21, report.path("healthy_services").asInt());
        assertFalse(report.has("fallback"));
        var source = report.path("source");
        assertEquals("Service Catalogue", source.path("capability_type").asText());
        assertEquals(node.base() + "/svc/services", source.path("endpoint").asText());
        assertEquals("lot1", source.path("adapter").asText());
        assertEquals("Lot-1 v2.0.0", source.path("format").asText());
        assertTrue(source.path("converted").asBoolean());
        assertEquals(node.base() + "/svc/services", report.path("api_source").asText());
        assertTrue(report.path("tried").toString().contains("DCAT"), "the skipped feed is on record");
        assertTrue(node.requests.stream().noneMatch(r -> r.startsWith("/sandbox")));
    }

    @Test
    void aNativeBeyondListIsNotMarkedConverted() throws Exception {
        var list = FakeNode.beyondList("A", "B");
        list.withArray("results").forEach(r -> ((ObjectNode) r).put("webpage", node.base() + "/page"));
        node.json("/res/api/service/all", list);
        writeEndpointReport(row("Resource Catalogue", node.base() + "/res/api", "OPERATIONAL"));
        var report = run(null);
        assertEquals(2, report.path("services").size());
        assertEquals("beyond", report.path("source").path("adapter").asText());
        assertFalse(report.path("source").path("converted").asBoolean());
    }

    @Test
    void recordsThatBreakTheModelAreCountedInTheReport() throws Exception {
        var all = services(4, FakeNode.V2_CATEGORY);
        all.get(2).put("trl", "trl-99");
        node.lot1("/svc/services", all);
        writeEndpointReport(row("Service Catalogue", node.base() + "/svc/services", "OPERATIONAL"));
        var report = run(null);
        assertEquals(3, report.path("services").size());
        assertEquals(1, report.path("invalid_records").asInt());
        assertEquals("svc-3", report.path("invalid_details").get(0).path("id").asText());
    }

    @Test
    void aListThatEndedEarlyCarriesAWarning() throws Exception {
        var all = services(120, FakeNode.V2_CATEGORY);
        node.route("/svc/services", q -> {
            int from = Integer.parseInt(q.getOrDefault("from", "0"));
            return from >= 50 ? new FakeNode.Reply(500, "boom")
                    : new FakeNode.Reply(200, FakeNode.lot1Page(all, from, 50).toString());
        });
        writeEndpointReport(row("Service Catalogue", node.base() + "/svc/services", "OPERATIONAL"));
        var report = run(null);
        assertEquals(50, report.path("services").size());
        assertTrue(report.path("warnings").get(0).asText().contains("50 of 120"));
    }

    @Test
    void whenNothingIsReadableTheSandboxIsAskedForTheNodeAndSaysSo() throws Exception {
        node.json("/svc/dcat", FakeNode.dcatFeed());
        node.route("/sandbox/api/service/all", q -> {
            var mixed = MAPPER.createObjectNode();
            var results = mixed.putArray("results");
            results.addObject().put("name", "Mine").put("nodePID", PID).put("webpage", node.base() + "/page");
            results.addObject().put("name", "Not mine").put("nodePID", "21.T15999/ENES").put("webpage", node.base() + "/page");
            mixed.put("total", 2);
            return new FakeNode.Reply(200, mixed.toString());
        });
        writeEndpointReport(row("Service Catalogue", node.base() + "/svc/dcat", "OPERATIONAL"));

        var report = run(null);          // the PID is read from the endpoint report

        assertTrue(report.path("fallback").asBoolean());
        assertTrue(report.path("fallback_reason").asText().contains("no catalogue endpoint could be read"));
        assertEquals(1, report.path("services").size());
        assertEquals("Mine", report.path("services").get(0).path("name").asText());
        assertTrue(node.requests.stream().anyMatch(r -> r.startsWith("/sandbox/api/service/all?node=" + PID)));
        assertFalse(report.has("source"), "no endpoint of the node was used");
        assertEquals(1, report.path("tried").size());
    }

    @Test
    void withoutAnyCatalogueEndpointNothingIsGuessed() throws Exception {
        writeEndpointReport(row("AAI", node.base() + "/aai", "OPERATIONAL"));
        var e = assertThrows(CheckCatalogueServices.NoCatalogueEndpointException.class, () -> run(null));
        assertTrue(e.getMessage().contains("no Service Catalogue or Resource Catalogue endpoint"));
        assertTrue(node.requests.isEmpty(), "no request at all, not even to the Sandbox");
    }

    @Test
    void theErrorOfAFailedNodeIsInTheMessage() throws Exception {
        Files.createDirectories(dataDir.resolve("N"));
        ObjectNode failed = FakeNode.report(PID);
        failed.put("error", "node_endpoint returned HTTP 502");
        MAPPER.writeValue(dataDir.resolve("N").resolve("endpoint_report.json").toFile(), failed);
        var e = assertThrows(CheckCatalogueServices.NoCatalogueEndpointException.class, () -> run(null));
        assertTrue(e.getMessage().contains("502"), e.getMessage());
    }

    // ── Check All ───────────────────────────────────────────────────────────

    @Test
    void checkAllUsesTheSelectionAndSkipsANodeWithNoCatalogue() throws Exception {
        node.lot1("/svc/services", services(3, FakeNode.V2_CATEGORY));
        writeEndpointReport(row("Service Catalogue", node.base() + "/svc/services", "OPERATIONAL"));
        CheckAll.runCatalogueServices(dataDir, "N", selector, MAPPER, http);
        var report = MAPPER.readTree(dataDir.resolve("N").resolve("catalogue_services_report.json").toFile());
        assertEquals(3, report.path("services").size());

        writeEndpointReport(row("AAI", node.base() + "/aai", "OPERATIONAL"));
        Exception skipped = assertThrows(Exception.class, () -> CheckAll.runCatalogueServices(dataDir, "N", selector, MAPPER, http));
        assertEquals("SkippedException", skipped.getClass().getSimpleName());
        assertTrue(skipped.getMessage().contains("no Service Catalogue or Resource Catalogue endpoint"));
    }
}
