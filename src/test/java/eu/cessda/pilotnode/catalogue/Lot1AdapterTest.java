package eu.cessda.pilotnode.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

class Lot1AdapterTest {

    private final Lot1Adapter adapter = new Lot1Adapter(new Lot1Validator());
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

    private CatalogueList read(String path) throws Exception {
        URI endpoint = URI.create(node.base() + path);
        URI probeUrl = adapter.probeUrls(endpoint).get(0);
        var probe = new CatalogueAdapter.Probe(endpoint, probeUrl, FakeNode.MAPPER.readTree(probeUrl.toURL()));
        String format = adapter.detect(probe.root()).orElseThrow();
        return adapter.read(new CatalogueContext("21.T15999/N"), probe, format, http, FakeNode.MAPPER);
    }

    @Test
    void everyPageIsReadNotJustTheFirst() throws Exception {
        node.lot1("/services", FakeNode.lot1Services(120, FakeNode.V2_CATEGORY));   // three pages of 50
        var list = read("/services");
        assertEquals(120, list.root().path("results").size());
        assertEquals(120, list.root().path("total").asInt());
        assertTrue(list.invalid().isEmpty());
        assertTrue(list.warnings().isEmpty());
        assertEquals(List.of("/services?from=0&quantity=50", "/services?from=50&quantity=50",
                        "/services?from=100&quantity=50"), node.requests,
                "the probe (first page), then the pages from 50 and from 100");
    }

    @Test
    void servicesAreConvertedToTheBeyondShape() throws Exception {
        node.lot1("/services", FakeNode.lot1Services(1, FakeNode.V2_CATEGORY));
        var list = read("/services");
        assertTrue(list.converted());
        ObjectNode s = (ObjectNode) list.root().path("results").get(0);
        assertEquals("svc-1", s.path("id").asText());
        assertEquals("Service 1", s.path("name").asText());
        assertEquals("https://example.org/svc-1", s.path("webpage").asText());
        assertEquals("https://example.org/svc-1", s.path("urls").get(0).asText());
        assertEquals("21.T15999/N", s.path("nodePID").asText());
        assertEquals("Service", s.path("type").asText());
        assertEquals("help@example.org", s.path("publicContacts").get(0).asText());
        assertEquals("trl-8", s.path("trl").asText());
        assertEquals("one", s.path("tags").get(0).asText());
        assertFalse(s.has("service"), "no Lot-1 wrapper left");
    }

    @Test
    void aRecordThatBreaksTheModelIsLeftOutAndReported() throws Exception {
        var services = FakeNode.lot1Services(3, FakeNode.V2_CATEGORY);
        services.get(1).put("trl", "trl-99");                      // not in the model
        node.lot1("/services", services);
        var list = read("/services");
        assertEquals(2, list.root().path("results").size());
        assertEquals(2, list.root().path("total").asInt(), "the total counts what is reported");
        assertEquals(1, list.invalid().size());
        assertEquals("svc-2", list.invalid().get(0).id());
        assertTrue(list.invalid().get(0).messages().stream().anyMatch(m -> m.contains("trl")), list.invalid().toString());
    }

    @Test
    void aV1CatalogueIsValidatedAgainstV1() throws Exception {
        node.lot1("/services", FakeNode.lot1Services(2, FakeNode.V1_CATEGORY));
        var list = read("/services");
        assertEquals(2, list.root().path("results").size());
        assertTrue(list.invalid().isEmpty(), "v1 categories are valid under v1.0.0");
    }

    @Test
    void aV1VocabularyUnderV2IsInvalid() throws Exception {
        var services = FakeNode.lot1Services(2, FakeNode.V2_CATEGORY);
        services.get(0).remove("categories");
        services.get(0).putArray("categories").addObject().put("category", FakeNode.V1_CATEGORY);
        node.lot1("/services", services);
        var list = read("/services");       // detected as v2 because of the other service
        assertEquals(1, list.invalid().size());
    }

    @Test
    void aPageThatFailsGivesAPartialListAndAWarning() throws Exception {
        var all = FakeNode.lot1Services(120, FakeNode.V2_CATEGORY);
        node.route("/services", q -> {
            int from = Integer.parseInt(q.getOrDefault("from", "0"));
            return from >= 100 ? new FakeNode.Reply(500, "boom")
                    : new FakeNode.Reply(200, FakeNode.lot1Page(all, from, 50).toString());
        });
        var list = read("/services");
        assertEquals(100, list.root().path("results").size());
        assertEquals(1, list.warnings().size());
        assertTrue(list.warnings().get(0).contains("100 of 120") && list.warnings().get(0).contains("500"),
                list.warnings().toString());
    }

    @Test
    void aCatalogueThatNeverReachesItsTotalStops() throws Exception {
        var all = FakeNode.lot1Services(30, FakeNode.V2_CATEGORY);
        node.route("/services", q -> {
            ObjectNode page = FakeNode.lot1Page(all, Integer.parseInt(q.getOrDefault("from", "0")), 50);
            page.put("total", 500);                                  // claims far more than it has
            return new FakeNode.Reply(200, page.toString());
        });
        var list = read("/services");
        assertEquals(30, list.root().path("results").size());
        assertFalse(list.warnings().isEmpty());
    }

    @Test
    void theNodePidIsNotInventedWhenUnknown() throws Exception {
        node.lot1("/services", FakeNode.lot1Services(1, FakeNode.V2_CATEGORY));
        URI endpoint = URI.create(node.base() + "/services");
        URI probeUrl = adapter.probeUrls(endpoint).get(0);
        var probe = new CatalogueAdapter.Probe(endpoint, probeUrl, FakeNode.MAPPER.readTree(probeUrl.toURL()));
        var list = adapter.read(new CatalogueContext(null), probe, "Lot-1 v2.0.0", http, FakeNode.MAPPER);
        assertNull(list.root().path("results").get(0).get("nodePID"));
    }

    @Test
    void nothingIsReadFromAnOversizedResponse() throws Exception {
        node.route("/big", q -> new FakeNode.Reply(200, "{\"pad\":\"" + "x".repeat(JsonFetcher.MAX_BYTES) + "\"}"));
        var fetched = JsonFetcher.fetch(http, FakeNode.MAPPER, URI.create(node.base() + "/big"));
        assertFalse(fetched.ok());
        assertTrue(fetched.problem().contains("larger"), fetched.problem());
    }

    @Test
    void anEmptyCatalogueIsAnEmptyList() throws Exception {
        node.lot1("/services", List.of());
        var list = read("/services");
        assertEquals(0, list.root().path("results").size());
    }
}
