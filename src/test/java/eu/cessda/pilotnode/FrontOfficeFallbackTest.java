package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A Front Office that cannot filter by a node's PID must not make that node look invisible, and must not be
 * reported as "Not visible" when it is really failing.
 */
class FrontOfficeFallbackTest {

    private static final String PID = "21.T15999/";

    private HttpServer server;
    private URI frontOffice;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        frontOffice = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/federation/services", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        requests.add(query);
        if (query.contains("nodes[]=")) {                       // the node-filtered query
            String node = query.substring(query.indexOf("nodes[]=") + 8).replace(PID, "");
            switch (node) {
                case "GOOD" -> reply(ex, 200, results("GOOD"));
                case "EMPTY" -> reply(ex, 200, "{\"results\":[]}");
                case "MISSING" -> reply(ex, 404, "not found");
                case "SERVERERR", "KEYWORDDOWN" -> reply(ex, 500, "boom");
                default -> reply(ex, 200, "{\"error\":\"API Mapping Failed\"}");   // BROKEN, NOMATCH, EOSC-BEYOND
            }
        } else {                                                 // the keyword search
            switch (query.substring(query.indexOf("q=") + 2)) {
                case "BROKEN" -> reply(ex, 200, results("BROKEN", "BROKEN", "OTHER"));
                case "SERVERERR" -> reply(ex, 200, results("SERVERERR"));
                case "EOSC-BEYOND" -> reply(ex, 200, results("EOSC-Beyond"));
                case "KEYWORDDOWN" -> reply(ex, 500, "boom");
                default -> reply(ex, 200, results("OTHER"));      // NOMATCH
            }
        }
    }

    private static String results(String... nodePids) throws IOException {
        var items = new ObjectMapper().createArrayNode();
        for (String n : nodePids) {
            items.addObject().put("name", "svc").put("nodePID", n);
        }
        return new ObjectMapper().writeValueAsString(java.util.Map.of("results", items));
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private CheckOtherMetrics.MetricResult query(String node) {
        return CheckOtherMetrics.queryFrontOffice(http, frontOffice, PID + node);
    }

    @Test
    void aWorkingFilterIsUsedAsIs() {
        var r = query("GOOD");
        assertTrue(r.visible());
        assertEquals("Available", r.status());
        assertNull(r.note());
        assertEquals(1, requests.size(), "no keyword search needed");
    }

    @Test
    void anEmptyAnswerIsStillNotVisibleAndNeedsNoFallback() {
        var r = query("EMPTY");
        assertFalse(r.visible());
        assertEquals("Not visible", r.status());
        assertEquals(1, requests.size());
    }

    @Test
    void aBackendErrorBehindHttp200IsFoundByKeywordSearch() {
        var r = query("BROKEN");
        assertTrue(r.visible());
        assertEquals("Available", r.status());
        assertEquals(2, r.resultCount(), "only the node's own services are counted");
        assertTrue(r.note().contains("API Mapping Failed") && r.note().contains("keyword search"), r.note());
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).contains("nodes[]=") && requests.get(1).endsWith("q=BROKEN"));
    }

    @Test
    void whenKeywordSearchFindsNoneTheOriginalErrorStands() {
        var r = query("NOMATCH");
        assertFalse(r.visible());
        assertEquals("Error", r.status(), "a backend failure is not 'Not visible'");
        assertEquals("API Mapping Failed", r.error());
        assertTrue(r.note().contains("found none"), r.note());
    }

    @Test
    void aServerErrorIsAlsoRetriedWithAKeywordSearch() {
        var r = query("SERVERERR");
        assertTrue(r.visible());
        assertEquals(2, requests.size());
    }

    @Test
    void notFoundIsNotARetryCase() {
        var r = query("MISSING");
        assertEquals("Error", r.status());
        assertEquals(404, r.httpCode());
        assertEquals(1, requests.size());
    }

    @Test
    void nodeNamesAreMatchedIgnoringCase() {
        var r = query("EOSC-BEYOND");     // the PID is upper case, the result says "EOSC-Beyond"
        assertTrue(r.visible());
        assertEquals(1, r.resultCount());
    }

    @Test
    void aFailingKeywordSearchKeepsTheOriginalErrorAndSaysSo() {
        var r = query("KEYWORDDOWN");
        assertEquals("Error", r.status());
        assertEquals(500, r.httpCode());
        assertTrue(r.note().contains("keyword search") && r.note().contains("500"), r.note());
    }

    @Test
    void theReportEntryCarriesTheErrorAndTheNote() {
        var entry = CheckOtherMetrics.buildPeerEntry(new ObjectMapper(), "Peer", "x", query("NOMATCH"));
        assertEquals("API Mapping Failed", entry.path("error").asText());
        assertTrue(entry.path("note").asText().contains("found none"));
        var ok = CheckOtherMetrics.buildPeerEntry(new ObjectMapper(), "Peer", "x", query("GOOD"));
        assertFalse(ok.has("error"));
        assertFalse(ok.has("note"));
    }
}
