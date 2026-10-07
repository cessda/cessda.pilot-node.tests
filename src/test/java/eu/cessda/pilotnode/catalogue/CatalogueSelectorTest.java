package eu.cessda.pilotnode.catalogue;

import static eu.cessda.pilotnode.catalogue.FakeNode.report;
import static eu.cessda.pilotnode.catalogue.FakeNode.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogueSelectorTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final CatalogueContext context = new CatalogueContext("21.T15999/N");
    private FakeNode node;
    private CatalogueSelector selector;

    @BeforeEach
    void start() throws Exception {
        node = new FakeNode();
        selector = CatalogueSelector.standard(List.of("Service Catalogue", "Resource Catalogue"), http, FakeNode.MAPPER);
    }

    @AfterEach
    void stop() {
        node.close();
    }

    private String url(String path) {
        return node.base() + path;
    }

    private CatalogueSelector.Result select(com.fasterxml.jackson.databind.node.ObjectNode... rows) throws Exception {
        return selector.select(report("21.T15999/N", rows), context);
    }

    @Test
    void dataTerrasLayoutPicksTheServiceCatalogueNotTheResearchProducts() throws Exception {
        node.json("/svc/dcat", FakeNode.dcatFeed());                                  // Service Catalogue, DCAT
        node.lot1("/svc/services", FakeNode.lot1Services(21, FakeNode.V2_CATEGORY)); // Service Catalogue, Lot-1
        node.json("/res/dcat", FakeNode.dcatFeed());                                  // Resource Catalogue, research products
        var result = select(
                row("Resource Catalogue", url("/res/dcat"), "OPERATIONAL"),
                row("Resource Catalogue", url("/res/oai"), "OPERATIONAL"),
                row("Service Catalogue", url("/svc/dcat"), "OPERATIONAL"),
                row("Service Catalogue", url("/svc/services"), "OPERATIONAL"));
        var chosen = result.selection().orElseThrow();
        assertEquals("Service Catalogue", chosen.candidate().capabilityType());
        assertEquals(url("/svc/services"), chosen.candidate().endpoint());
        assertEquals("lot1", chosen.adapterId());
        assertEquals("Lot-1 v2.0.0", chosen.format());
        assertEquals(21, chosen.list().root().path("results").size());
        assertTrue(chosen.list().converted());
        assertTrue(result.attempts().stream().anyMatch(a -> a.candidate().endpoint().endsWith("/svc/dcat")
                && a.outcome().contains("DCAT")), "the feed that was skipped is accounted for: " + result.attempts());
        assertTrue(node.requests.stream().noneMatch(r -> r.startsWith("/res/")),
                "the Resource Catalogue is not touched once the Service Catalogue works");
    }

    @Test
    void whenTheServiceCatalogueCannotBeReadTheResourceCatalogueIsUsed() throws Exception {
        node.status("/svc/services", 503);
        node.json("/res/api/service/all", FakeNode.beyondList("A", "B"));
        var chosen = select(
                row("Service Catalogue", url("/svc/services"), "OPERATIONAL"),
                row("Resource Catalogue", url("/res/api"), "OPERATIONAL")).selection().orElseThrow();
        assertEquals("Resource Catalogue", chosen.candidate().capabilityType());
        assertEquals("beyond", chosen.adapterId());
        assertFalse(chosen.list().converted());
        assertEquals(2, chosen.list().root().path("results").size());
    }

    @Test
    void theServiceCatalogueWinsEvenWhenTheResourceCatalogueIsNative() throws Exception {
        node.lot1("/svc/services", FakeNode.lot1Services(3, FakeNode.V2_CATEGORY));
        node.json("/res/api/service/all", FakeNode.beyondList("A", "B"));
        var chosen = select(
                row("Resource Catalogue", url("/res/api"), "OPERATIONAL"),
                row("Service Catalogue", url("/svc/services"), "OPERATIONAL")).selection().orElseThrow();
        assertEquals("Service Catalogue", chosen.candidate().capabilityType());
    }

    @Test
    void withinOneTypeANativeListBeatsAConvertedOne() throws Exception {
        node.lot1("/a/services", FakeNode.lot1Services(3, FakeNode.V2_CATEGORY));
        node.json("/b/api/service/all", FakeNode.beyondList("A", "B"));
        var chosen = select(
                row("Resource Catalogue", url("/a/services"), "OPERATIONAL"),
                row("Resource Catalogue", url("/b/api"), "OPERATIONAL")).selection().orElseThrow();
        assertEquals("beyond", chosen.adapterId());
    }

    @Test
    void anOperationalEndpointBeatsAPlannedOneOfTheSameFormat() throws Exception {
        node.lot1("/planned/services", FakeNode.lot1Services(1, FakeNode.V2_CATEGORY));
        node.lot1("/live/services", FakeNode.lot1Services(2, FakeNode.V2_CATEGORY));
        var chosen = select(
                row("Service Catalogue", url("/planned/services"), "PLANNED"),
                row("Service Catalogue", url("/live/services"), "OPERATIONAL")).selection().orElseThrow();
        assertEquals(url("/live/services"), chosen.candidate().endpoint());
    }

    @Test
    void theNodesOwnOrderBreaksTheLastTie() throws Exception {
        node.lot1("/one/services", FakeNode.lot1Services(1, FakeNode.V2_CATEGORY));
        node.lot1("/two/services", FakeNode.lot1Services(2, FakeNode.V2_CATEGORY));
        var chosen = select(
                row("Service Catalogue", url("/one/services"), "OPERATIONAL"),
                row("Service Catalogue", url("/two/services"), "OPERATIONAL")).selection().orElseThrow();
        assertEquals(url("/one/services"), chosen.candidate().endpoint());
    }

    @Test
    void capabilityTypesAreMatchedIgnoringCaseAndSpaces() throws Exception {
        node.lot1("/svc/services", FakeNode.lot1Services(1, FakeNode.V2_CATEGORY));
        assertTrue(select(row("  service catalogue ", url("/svc/services"), "OPERATIONAL")).selection().isPresent());
    }

    @Test
    void theOrderIsConfigurable() throws Exception {
        node.lot1("/svc/services", FakeNode.lot1Services(3, FakeNode.V2_CATEGORY));
        node.json("/res/api/service/all", FakeNode.beyondList("A"));
        selector = CatalogueSelector.standard(List.of("Resource Catalogue", "Service Catalogue"), http, FakeNode.MAPPER);
        var chosen = select(
                row("Service Catalogue", url("/svc/services"), "OPERATIONAL"),
                row("Resource Catalogue", url("/res/api"), "OPERATIONAL")).selection().orElseThrow();
        assertEquals("Resource Catalogue", chosen.candidate().capabilityType());
    }

    @Test
    void otherCapabilityTypesAndEmptyEndpointsAreNotCandidates() {
        var report = report("21.T15999/N",
                row("AAI", url("/aai"), "OPERATIONAL"),
                row("Service Catalogue", "", "OPERATIONAL"),
                row("Front Office", url("/fo"), "OPERATIONAL"));
        assertTrue(selector.candidates(report).isEmpty());
    }

    @Test
    void nothingReadableGivesNoSelectionAndAnAccountOfEachTry() throws Exception {
        node.json("/dcat", FakeNode.dcatFeed());
        node.status("/down", 500);
        var result = select(
                row("Service Catalogue", url("/dcat"), "OPERATIONAL"),
                row("Resource Catalogue", url("/down"), "OPERATIONAL"),
                row("Resource Catalogue", url("/missing"), "OPERATIONAL"));
        assertTrue(result.selection().isEmpty());
        assertEquals(3, result.attempts().size());
        assertTrue(result.attempts().get(0).outcome().contains("DCAT"));
        assertTrue(result.attempts().get(1).outcome().contains("HTTP 500"), result.attempts().get(1).outcome());
        assertTrue(result.attempts().get(2).outcome().contains("HTTP 404"), result.attempts().get(2).outcome());
    }

    @Test
    void theSameUrlIsNotRequestedTwiceForTwoAdapters() throws Exception {
        node.json("/same/api/service/all", FakeNode.beyondList("A"));
        // an endpoint that is already the Beyond list: Beyond probes it, Lot-1 would probe it with paging
        select(row("Resource Catalogue", url("/same/api/service/all"), "OPERATIONAL"));
        assertEquals(1, node.requests.stream().filter(r -> r.startsWith("/same/api/service/all")).count());
    }

    @Test
    void aMalformedEndpointIsSkippedNotFatal() throws Exception {
        node.json("/ok/api/service/all", FakeNode.beyondList("A"));
        var result = select(
                row("Resource Catalogue", "http://bad host/x y", "OPERATIONAL"),
                row("Resource Catalogue", url("/ok/api"), "OPERATIONAL"));
        assertTrue(result.selection().isPresent());
    }
}
