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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * The result count is the number of matches, not the number of results on the first page.
 */
class FrontOfficeResultCountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private URI frontOffice;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        frontOffice = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/federation/services", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String page(int onThisPage, String extra) {
        var items = new StringBuilder();
        for (int i = 0; i < onThisPage; i++) {
            items.append(i == 0 ? "" : ",").append("{\"nodePID\":\"N\"}");
        }
        return "{\"results\":[" + items + "]" + extra + "}";
    }

    private JsonNode read(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    private CheckOtherMetrics.MetricResult query() {
        return CheckOtherMetrics.queryFrontOffice(HttpClient.newHttpClient(), frontOffice, "21.T15999/N");
    }

    // ── the counting rule ───────────────────────────────────────────────────

    @Test
    void thePaginationTotalIsUsedNotThePageLength() throws IOException {
        // what a real Front Office returns: 10 on the page, 188 matches, no top-level "total"
        var root = read(page(10, ",\"pagination\":{\"current_page\":1,\"per_page\":10,\"total_count\":188}"));
        assertEquals(188, CheckOtherMetrics.totalResultCount(root));
    }

    @Test
    void aTopLevelTotalIsStillAccepted() throws IOException {
        assertEquals(42, CheckOtherMetrics.totalResultCount(read(page(10, ",\"total\":42"))));
    }

    @Test
    void thePaginationTotalWinsOverATopLevelTotal() throws IOException {
        assertEquals(188, CheckOtherMetrics.totalResultCount(
                read(page(10, ",\"total\":10,\"pagination\":{\"total_count\":188}"))));
    }

    @Test
    void withNoTotalAtAllThePageLengthIsUsed() throws IOException {
        assertEquals(3, CheckOtherMetrics.totalResultCount(read(page(3, ""))));
    }

    @Test
    void anUnusableTotalFallsBackToTheNextSource() throws IOException {
        assertEquals(7, CheckOtherMetrics.totalResultCount(
                read(page(2, ",\"total\":7,\"pagination\":{\"total_count\":\"many\"}"))));
        assertEquals(2, CheckOtherMetrics.totalResultCount(
                read(page(2, ",\"pagination\":{\"total_count\":-1}"))));
    }

    @Test
    void zeroMatchesIsZero() throws IOException {
        assertEquals(0, CheckOtherMetrics.totalResultCount(read(page(0, ",\"pagination\":{\"total_count\":0}"))));
    }

    // ── through the query ───────────────────────────────────────────────────

    @Test
    void theMetricReportsTheTotalAndIsVisible() {
        body = page(10, ",\"pagination\":{\"total_count\":188}");
        var r = query();
        assertEquals(188, r.resultCount());
        assertTrue(r.visible());
        assertEquals("Available", r.status());
    }

    @Test
    void noMatchesIsStillNotVisible() {
        body = page(0, ",\"pagination\":{\"total_count\":0}");
        var r = query();
        assertEquals(0, r.resultCount());
        assertFalse(r.visible());
        assertEquals("Not visible", r.status());
    }
}
