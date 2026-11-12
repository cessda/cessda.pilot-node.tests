package eu.cessda.pilotnode.catalogue;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.catalogue.lenient.CatalogueSelector;
import eu.cessda.pilotnode.catalogue.FakeNode;

/** The strict Exchange Services check: the endpoint as published, nothing completed, every failure explained. */
class StrictCatalogueServicesTest {

    private static final String PID = "21.T15999/N";
    private static final String LOT1_V2_SPEC =
            "https://raw.githubusercontent.com/EOSC-Lot-1/eosc-service-catalogues-specs/v2.0.0/service_catalogue.schema.json";
    private static final String LOT1_V1_SPEC =
            "https://raw.githubusercontent.com/EOSC-Lot-1/eosc-service-catalogues-specs/v1.0.0/service_catalogue.schema.json";

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

    private List<ObjectNode> services(int count, String category) {
        List<ObjectNode> list = FakeNode.lot1Services(count, category);
        list.forEach(s -> s.put("webpage", node.base() + "/page"));
        return list;
    }

    private ObjectNode beyondList(String... names) {
        ObjectNode list = FakeNode.beyondList(names);
        list.withArray("results").forEach(r -> ((ObjectNode) r).put("webpage", node.base() + "/page"));
        return list;
    }

    private void strictEndpointReport(ObjectNode... rows) throws IOException {
        Path dir = dataDir.resolve("N");
        Files.createDirectories(dir);
        MAPPER.writeValue(dir.resolve("endpoint_report.json").toFile(), FakeNode.strictReport(PID, rows));
    }

    private ObjectNode rowWith(String type, String path, String apiSpec, String version) {
        ObjectNode r = row(type, node.base() + path, "OPERATIONAL");
        if (apiSpec != null) {
            r.put("api_spec", apiSpec);
        }
        if (version != null) {
            r.put("version", version);
        }
        return r;
    }

    private JsonNode run() throws Exception {
        StrictCatalogueServices.run(dataDir, "N", null, selector.candidateSource(), http, MAPPER, Duration.ZERO);
        return MAPPER.readTree(dataDir.resolve("N/catalogue_services_report.json").toFile());
    }

    private static List<JsonNode> findings(JsonNode report, String code) {
        List<JsonNode> out = new ArrayList<>();
        report.path("findings").forEach(f -> {
            if (code.equals(f.path("code").asText())) {
                out.add(f);
            }
        });
        return out;
    }

    // ── what strict mode reads ──────────────────────────────────────────────

    @Test
    void itNeedsAStrictEndpointReportNotALenientOne() throws Exception {
        Path lenient = dataDir.resolve("N").resolve("lenient");
        Files.createDirectories(lenient);
        MAPPER.writeValue(lenient.resolve("endpoint_report.json").toFile(),
                FakeNode.report(PID, rowWith("Resource Catalogue", "/res/api", null, null)));
        var e = assertThrows(NoCatalogueEndpointException.class, this::run);
        assertTrue(e.getMessage().contains("run Node Capabilities in strict mode first"), e.getMessage());
        assertTrue(node.requests.isEmpty());
    }

    @Test
    void aStrictReportWithNoCatalogueEndpointStatesWhy() throws Exception {
        strictEndpointReport(rowWith("AAI", "/aai", null, null));
        var e = assertThrows(NoCatalogueEndpointException.class, this::run);
        assertTrue(e.getMessage().contains("no Service Catalogue or Resource Catalogue endpoint"), e.getMessage());
    }

    // ── the URL, exactly as published ───────────────────────────────────────

    @Test
    void aBeyondListAtTheEndpointItselfIsReadWithoutAddingAnything() throws Exception {
        node.json("/res/api/service/all", beyondList("A", "B", "C"));
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api/service/all", null, null));
        var report = run();
        assertEquals("strict", report.path("mode").asText());
        assertEquals(3, report.path("services").size());
        assertEquals(List.of("/res/api/service/all"), node.requests.stream().filter(r -> !r.startsWith("/page")).toList(),
                "one catalogue request, to the URL as published (the others are the service webpage checks)");
        assertFalse(report.path("source").path("converted").asBoolean());
        assertEquals(node.base() + "/res/api/service/all", report.path("api_source").asText());
    }

    @Test
    void anApiBaseIsNotCompletedAndTheFailureIsExplainedWithWhatLenientModeWouldHaveDone() throws Exception {
        node.json("/res/api/service/all", beyondList("A", "B"));       // this is where the list really is
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api", null, null));
        var report = run();

        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(0, report.path("services").size());
        var f = findings(report, "catalogue-unreachable").get(0);
        assertEquals(node.base() + "/res/api", f.path("url").asText());
        assertEquals("HTTP 404", f.path("actual").asText());
        assertTrue(f.path("message").asText().contains("GET " + node.base() + "/res/api"));
        assertEquals("Lenient mode would request " + node.base() + "/res/api/service/all instead of the URL as published",
                f.path("hint").asText());
        assertEquals(List.of("/res/api"), node.requests, "the completed URL was not requested");
        assertEquals(1, findings(report, "no-readable-catalogue").size());
    }

    @Test
    void theSandboxIsNeverAsked() throws Exception {
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api", null, null));
        var report = run();
        assertTrue(node.requests.stream().noneMatch(r -> r.startsWith("/sandbox")));
        assertFalse(report.has("fallback"));
        assertEquals(0, report.path("total_services").asInt());
    }

    @Test
    void aLot1ListIsRequestedAsPublishedAndTheRestIsPagedWithTheModelsParameters() throws Exception {
        node.lot1("/svc/services", services(120, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, null));
        var report = run();
        assertEquals(120, report.path("services").size());
        assertEquals("/svc/services", node.requests.get(0), "the first request carries no added parameters");
        assertTrue(node.requests.get(1).startsWith("/svc/services?from=10&quantity="), node.requests.get(1));
        assertEquals(1, findings(report, "paged-with-from-quantity").size());
        assertEquals("INFO", findings(report, "paged-with-from-quantity").get(0).path("severity").asText());
    }

    // ── what the node declares ──────────────────────────────────────────────

    @Test
    void anApiSpecNamingTheReleaseThatCameBackIsConfirmed() throws Exception {
        node.lot1("/svc/services", services(3, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", LOT1_V2_SPEC, null));
        var report = run();
        assertEquals(1, findings(report, "payload-matches-api-spec").size());
        assertEquals("PASS", report.path("strict_result").asText());
    }

    @Test
    void anApiSpecNamingAnotherReleaseIsAnError() throws Exception {
        node.lot1("/svc/services", services(3, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", LOT1_V1_SPEC, null));
        var report = run();
        var f = findings(report, "format-differs-from-api-spec").get(0);
        assertEquals("ERROR", f.path("severity").asText());
        assertTrue(f.path("expected").asText().startsWith("Lot-1 v1.0.0") && f.path("actual").asText().equals("Lot-1 v2.0.0"), f.toString());
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(3, report.path("services").size(), "the services are still read and checked");
    }

    @Test
    void noApiSpecIsNotedAndTheFormatIsTakenFromTheContent() throws Exception {
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, null));
        var f = findings(run(), "api-spec-absent").get(0);
        assertEquals("INFO", f.path("severity").asText());
        assertTrue(f.path("actual").asText().contains("Lot-1 v2.0.0"));
    }

    @Test
    void anApiSpecTheCheckCannotUseIsNoted() throws Exception {
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", node.base() + "/docs#/EEN", null));
        assertEquals(1, findings(run(), "api-spec-not-used").size());
    }

    @Test
    void aVersionThatLooksLikeAnotherLot1ReleaseIsNoted() throws Exception {
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, "1.0.0"));
        var f = findings(run(), "format-differs-from-version").get(0);
        assertEquals("INFO", f.path("severity").asText());
    }

    @Test
    void aSoftwareVersionIsLeftAlone() throws Exception {
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, "0.2.58"));
        assertTrue(findings(run(), "format-differs-from-version").isEmpty());
    }

    // ── choosing, and saying what happened to each endpoint ─────────────────

    @Test
    void dataTerrasLayoutReadsTheLot1ListAndDoesNotTouchTheResearchProductCatalogue() throws Exception {
        node.json("/svc/dcat", FakeNode.dcatFeed());
        node.lot1("/svc/services", services(21, FakeNode.V2_CATEGORY));
        node.json("/res/dcat", FakeNode.dcatFeed());
        strictEndpointReport(
                rowWith("Resource Catalogue", "/res/dcat", null, null),
                rowWith("Service Catalogue", "/svc/dcat", null, null),
                rowWith("Service Catalogue", "/svc/services", null, null));
        var report = run();

        assertEquals(21, report.path("services").size());
        assertEquals("Service Catalogue", report.path("source").path("capability_type").asText());
        assertEquals(node.base() + "/svc/services", report.path("source").path("endpoint").asText());
        assertTrue(report.path("source").path("converted").asBoolean());
        // the DCAT alternative is a warning (the check cannot read it), not an error
        var dcat = findings(report, "catalogue-format-unrecognised").get(0);
        assertEquals("WARNING", dcat.path("severity").asText());
        assertTrue(dcat.path("actual").asText().contains("DCAT"));
        assertEquals("PASS", report.path("strict_result").asText());
        assertTrue(node.requests.stream().noneMatch(r -> r.startsWith("/res/")), "the lower-ranked catalogue is not requested");
        assertEquals(3, report.path("tried").size());
        assertTrue(report.path("tried").get(2).path("outcome").asText().contains("not requested"));
    }

    @Test
    void anEndpointThatFailsBeforeTheOneThatWorksIsAnError() throws Exception {
        node.status("/svc/broken", 500);
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(
                rowWith("Service Catalogue", "/svc/broken", null, null),
                rowWith("Service Catalogue", "/svc/services", null, null));
        var report = run();
        assertEquals(2, report.path("services").size());
        assertEquals("ERROR", findings(report, "catalogue-unreachable").get(0).path("severity").asText());
        assertTrue(findings(report, "catalogue-unreachable").get(0).path("actual").asText().contains("after 3 attempts"));
        assertEquals("FAIL", report.path("strict_result").asText());
    }

    @Test
    void aMomentaryFailureIsRetriedAndReported() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        node.route("/svc/services", q -> calls.incrementAndGet() < 2 ? new FakeNode.Reply(503, "x")
                : new FakeNode.Reply(200, FakeNode.lot1Page(services(2, FakeNode.V2_CATEGORY), 0, 10).toString()));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, null));
        var report = run();
        assertEquals(2, report.path("services").size());
        var f = findings(report, "request-retried").get(0);
        assertEquals("WARNING", f.path("severity").asText());
        assertTrue(f.path("actual").asText().contains("2 attempts"));
    }

    @Test
    void anUnsupportedFormatEverywhereIsAnErrorBecauseNothingCouldBeRead() throws Exception {
        node.json("/svc/dcat", FakeNode.dcatFeed());
        strictEndpointReport(rowWith("Service Catalogue", "/svc/dcat", null, null));
        var report = run();
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals("WARNING", findings(report, "catalogue-format-unrecognised").get(0).path("severity").asText());
        assertEquals("ERROR", findings(report, "no-readable-catalogue").get(0).path("severity").asText());
    }

    @Test
    void recordsThatBreakTheModelAreErrors() throws Exception {
        var all = services(4, FakeNode.V2_CATEGORY);
        all.get(1).put("trl", "trl-99");
        node.lot1("/svc/services", all);
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, null));
        var report = run();
        assertEquals(3, report.path("services").size());
        var f = findings(report, "record-invalid").get(0);
        assertEquals("ERROR", f.path("severity").asText());
        assertTrue(f.path("message").asText().contains("svc-2") && f.path("actual").asText().contains("trl"), f.toString());
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(1, report.path("invalid_records").asInt());
    }

    @Test
    void aMalformedEndpointIsNotRequestedAtAll() throws Exception {
        strictEndpointReport(rowWith("Service Catalogue", "", null, null));
        ObjectNode bad = row("Service Catalogue", "services.example.org/list", "OPERATIONAL");
        strictEndpointReport(bad);
        var report = run();
        assertEquals(1, findings(report, "endpoint-invalid").size());
        assertTrue(node.requests.isEmpty());
    }

    // ── results kept apart ──────────────────────────────────────────────────

    @Test
    void theStrictReportIsWrittenInTheStrictDirectoryOnly() throws Exception {
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", null, null));
        Path lenient = dataDir.resolve("N").resolve("lenient").resolve("catalogue_services_report.json");
        Files.createDirectories(lenient.getParent());
        Files.writeString(lenient, "{\"marker\":\"lenient result\"}");
        run();
        assertEquals("{\"marker\":\"lenient result\"}", Files.readString(lenient));
        assertTrue(Files.exists(dataDir.resolve("N/catalogue_services_report.json")));
    }

    @Test
    void aCatalogueEndpointThatRedirectsSaysWhereWithoutTheQueryString() throws Exception {
        node.raw("/svc/moved", ex -> {
            ex.getResponseHeaders().add("Location", "http://elsewhere.example.org/api/?token=SECRET");
            ex.sendResponseHeaders(302, -1);
        });
        strictEndpointReport(rowWith("Service Catalogue", "/svc/moved", null, null));
        var report = run();
        var f = findings(report, "catalogue-unreachable").get(0);
        assertEquals("HTTP 302, redirecting to http://elsewhere.example.org/api/ (not followed)", f.path("actual").asText());
        assertFalse(report.toString().contains("SECRET"));
    }

    @Test
    void aListInAnUnpagedFormatThatHoldsLessThanItReportsIsFlagged() throws Exception {
        var list = beyondList("A", "B", "C");
        list.put("total", 50);
        node.json("/res/api/service/all", list);
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api/service/all", null, null));
        var report = run();
        var f = findings(report, "list-incomplete").get(0);
        assertEquals("WARNING", f.path("severity").asText());
        assertEquals("3 of 50", f.path("actual").asText());
        assertEquals(3, report.path("services").size());
    }

    @Test
    void aCatalogueDeclaredWithoutAnEndpointIsAFailureNotASkippedNode() throws Exception {
        ObjectNode noEndpoint = FakeNode.row("Service Catalogue", "", "OPERATIONAL");
        strictEndpointReport(noEndpoint, rowWith("AAI", "/aai", null, null));
        var report = run();                                        // writes a report; does not throw
        assertEquals("FAIL", report.path("strict_result").asText());
        var f = findings(report, "catalogue-endpoint-missing").get(0);
        assertEquals("Service Catalogue", f.path("subject").asText());
        assertEquals(0, report.path("services").size());
        assertTrue(node.requests.isEmpty());
    }

    // ── the payload, against the api_spec ────────────────────────────────────

    private static final String LIST_API = "{\"openapi\":\"3.0.3\",\"paths\":{\"/res/api/service/all\":{\"get\":{\"responses\":{\"200\":"
            + "{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":[\"total\",\"results\"],"
            + "\"properties\":{\"total\":{\"type\":\"integer\"},\"results\":{\"type\":\"array\"}}}}}}}}}}}";

    @Test
    void anOpenApiApiSpecIsUsedToCheckTheListAndTheResultIsInTheReport() throws Exception {
        node.json("/res/api/service/all", beyondList("A", "B"));
        node.route("/spec", q -> new FakeNode.Reply(200, LIST_API));
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api/service/all", node.base() + "/spec", null));
        var report = run();
        var check = report.path("source").path("payload_check");
        assertEquals("checked", check.path("status").asText());
        assertEquals("PASS", check.path("result").asText());
        assertEquals("GET /res/api/service/all", check.path("operation").asText());
        assertEquals(1, findings(report, "payload-matches-api-spec").size());
    }

    @Test
    void aListThatBreaksTheOpenApiApiSpecIsAnError() throws Exception {
        node.json("/res/api/service/all", beyondList("A", "B"));
        node.route("/spec", q -> new FakeNode.Reply(200, LIST_API.replace("\"integer\"", "\"string\"")));
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api/service/all", node.base() + "/spec", null));
        var report = run();
        var check = report.path("source").path("payload_check");
        assertEquals("FAIL", check.path("result").asText());
        assertEquals(1, findings(report, "payload-differs-from-api-spec").size());
        assertEquals("FAIL", report.path("strict_result").asText());
    }

    @Test
    void withNoApiSpecThePayloadIsShownAsNotChecked() throws Exception {
        node.json("/res/api/service/all", beyondList("A", "B"));
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api/service/all", null, null));
        var check = run().path("source").path("payload_check");
        assertEquals("not_checked", check.path("status").asText());
        assertTrue(check.path("reason").asText().contains("no api_spec is published"), check.toString());
    }

    @Test
    void anApiSpecThatCannotBeReadMeansTheListIsNotChecked() throws Exception {
        node.json("/res/api/service/all", beyondList("A", "B"));
        node.status("/spec", 404);
        strictEndpointReport(rowWith("Resource Catalogue", "/res/api/service/all", node.base() + "/spec", null));
        var report = run();
        var check = report.path("source").path("payload_check");
        assertEquals("not_checked", check.path("status").asText());
        assertTrue(check.path("reason").asText().contains("could not be read as a JSON document"), check.toString());
        assertEquals(1, findings(report, "api-spec-not-used").size());
    }

    @Test
    void aLot1ApiSpecGivesAPassedPayloadCheckWhenTheFormatMatches() throws Exception {
        node.lot1("/svc/services", services(2, FakeNode.V2_CATEGORY));
        strictEndpointReport(rowWith("Service Catalogue", "/svc/services", LOT1_V2_SPEC, null));
        var check = run().path("source").path("payload_check");
        assertEquals("checked", check.path("status").asText());
        assertEquals("PASS", check.path("result").asText());
    }
}
