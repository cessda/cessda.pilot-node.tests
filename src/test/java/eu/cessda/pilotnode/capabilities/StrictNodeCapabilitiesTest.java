package eu.cessda.pilotnode.capabilities;

import static eu.cessda.pilotnode.catalogue.FakeNode.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cessda.pilotnode.capabilities.CheckNodeCapabilities;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import eu.cessda.pilotnode.catalogue.FakeNode;
import eu.cessda.pilotnode.common.CheckMode;

/** The strict Node Capabilities check: URLs as published, one GET each, everything wrong stated. */
class StrictNodeCapabilitiesTest {

    @TempDir
    Path dataDir;

    private FakeNode node;
    private final List<Map<String, Object>> registry = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        node = new FakeNode();
        node.json("/up", MAPPER.createObjectNode().put("ok", true));
        node.status("/down", 404);
        node.status("/maintenance-down", 503);
        node.raw("/get405", ex -> {                       // answers HEAD, refuses GET
            ex.sendResponseHeaders("HEAD".equals(ex.getRequestMethod()) ? 200 : 405, -1);
        });
        node.raw("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", node.base() + "/up");
            ex.sendResponseHeaders(302, -1);
        });
        node.json("/spec/openapi", MAPPER.createObjectNode().put("openapi", "3.1.0").set("info", MAPPER.createObjectNode().put("version", "2.0.0")));
        node.route("/spec/html", q -> new FakeNode.Reply(200, "<html><body>Swagger UI</body></html>"));
        node.status("/spec/missing", 404);
        node.json("/spec/notopenapi", MAPPER.createObjectNode().put("hello", "world"));
        registry.clear();
        node.route("/registry", q -> new FakeNode.Reply(200, writeRegistry()));
    }

    @AfterEach
    void stop() {
        node.close();
    }

    private String writeRegistry() {
        try {
            return MAPPER.writeValueAsString(registry);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void addNode(String name, String endpointPath) {
        registry.add(Map.of("id", name.toLowerCase(), "name", name, "pid", "21.T15999/" + name,
                "node_endpoint", endpointPath.startsWith("http") ? endpointPath : node.base() + endpointPath,
                "legal_entity", Map.of("name", name + " Org", "ror_id", "https://ror.org/x")));
    }

    private String cap(String type, String endpointPath, String extra) {
        return "{\"capability_type\":\"" + type + "\",\"endpoint\":\"" + node.base() + endpointPath + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private void document(String path, String... capabilities) {
        node.route(path, q -> new FakeNode.Reply(200,
                "{\"node_endpoint\":\"" + node.base() + path + "\",\"capabilities\":[" + String.join(",", capabilities) + "]}"));
    }

    private CheckNodeCapabilities.Result run(CheckMode mode) throws IOException {
        return CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON), dataDir,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),     // as in production
                MAPPER, URI.create(node.base() + "/registry"), Duration.ZERO, mode);
    }

    private JsonNode strictReport(String name) throws IOException {
        return MAPPER.readTree(dataDir.resolve(name).resolve("endpoint_report.json").toFile());
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

    // ── separate results ────────────────────────────────────────────────────

    @Test
    void strictResultsAreKeptApartFromLenientOnes() throws IOException {
        addNode("Good", "/node/good");
        document("/node/good", cap("AAI", "/up", "\"protocol\":\"REST\",\"status\":\"OPERATIONAL\""));
        Path lenient = dataDir.resolve("Good").resolve("lenient").resolve("endpoint_report.json");
        Files.createDirectories(lenient.getParent());
        Files.writeString(lenient, "{\"marker\":\"lenient result\"}");

        run(CheckMode.STRICT);

        assertEquals("{\"marker\":\"lenient result\"}", Files.readString(lenient), "a strict run never touches a lenient result");
        assertTrue(Files.exists(dataDir.resolve("Good/endpoint_report.json")));
        // one summary, listing the node; what the check found is in the node's own report
        var summary = MAPPER.readTree(dataDir.resolve("node_registry_summary.json").toFile());
        assertEquals("Good", summary.path("nodes").get(0).path("node_name").asText());
        assertFalse(summary.has("mode"));
        assertFalse(summary.path("nodes").get(0).has("strict_result"));
        assertFalse(Files.exists(dataDir.resolve("strict")), "there is no separate strict tree");
    }

    @Test
    void aStrictRunReplacesAnEarlierVersionsLenientReportInTheNodesFolder() throws IOException {
        addNode("Good", "/node/good");
        document("/node/good", cap("AAI", "/up", ""));
        Path legacy = dataDir.resolve("Good").resolve("endpoint_report.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"node_name\":\"Good\",\"capabilities\":[]}");      // no mode: an earlier version's lenient report

        run(CheckMode.STRICT);

        assertEquals("strict", MAPPER.readTree(legacy.toFile()).path("mode").asText());
    }

    @Test
    void aLenientRunDoesNotTouchStrictResults() throws IOException {
        addNode("Good", "/node/good");
        document("/node/good", cap("AAI", "/up", ""));
        run(CheckMode.STRICT);
        Path strict = dataDir.resolve("Good/endpoint_report.json");
        String before = Files.readString(strict);

        run(CheckMode.LENIENT);

        assertEquals(before, Files.readString(strict));
        assertEquals("lenient", MAPPER.readTree(dataDir.resolve("Good/lenient/endpoint_report.json").toFile()).path("mode").asText());
    }

    // ── a conforming node ───────────────────────────────────────────────────

    @Test
    void aNodeThatFollowsTheModelPasses() throws IOException {
        addNode("Good", "/node/good");
        document("/node/good",
                cap("AAI", "/up", "\"protocol\":\"REST\",\"status\":\"OPERATIONAL\",\"version\":\"1.0.0\""),
                cap("Helpdesk", "/up", ""));
        var result = run(CheckMode.STRICT);
        var report = strictReport("Good");
        assertEquals("PASS", report.path("strict_result").asText());
        assertEquals(0, report.path("findings_error").asInt());
        assertEquals(2, report.path("capabilities").size());
        assertEquals("Available", report.path("capabilities").get(0).path("status").asText());
        assertEquals(2, report.path("available_capabilities").asInt());
        assertEquals("REST", report.path("capabilities").get(0).path("protocol").asText());
        assertTrue(result.problems().isEmpty());
        assertTrue(result.message().contains("1 node(s)") && !result.message().contains("problems"), result.message());
    }

    // ── the URL, exactly as published ───────────────────────────────────────

    @Test
    void anEndpointIsRequestedOnceWithGetAndNeverWithHead() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("AAI", "/up", ""));
        run(CheckMode.STRICT);
        assertEquals(1, node.methods.stream().filter(m -> m.endsWith(" /up")).count());
        assertTrue(node.methods.contains("GET /up"));
        assertFalse(node.methods.contains("HEAD /up"));
    }

    @Test
    void anEndpointThatRefusesGetIsAnErrorEvenThoughLenientModeWouldPassIt() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Odd", "/get405", ""));

        run(CheckMode.LENIENT);
        assertEquals("Available", MAPPER.readTree(dataDir.resolve("N/lenient/endpoint_report.json").toFile()).path("capabilities").get(0).path("status").asText(),
                "lenient mode retries without HEAD only on 404, 405, 501, and HEAD succeeds here");

        run(CheckMode.STRICT);
        var report = strictReport("N");
        assertEquals("FAIL", report.path("strict_result").asText());
        var f = findings(report, "endpoint-http-error").get(0);
        assertEquals("ERROR", f.path("severity").asText());
        assertEquals("HTTP 405", f.path("actual").asText());
        assertTrue(f.path("hint").asText().contains("lenient"), f.toString());
    }

    @Test
    void aFailingEndpointStatesWhatWasRequestedAndWhatCameBack() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/down", ""));
        run(CheckMode.STRICT);
        var report = strictReport("N");
        var f = findings(report, "endpoint-http-error").get(0);
        assertEquals(node.base() + "/down", f.path("url").asText());
        assertEquals("a 2xx answer to GET " + node.base() + "/down", f.path("expected").asText());
        assertEquals("HTTP 404", f.path("actual").asText());
        assertTrue(f.path("message").asText().contains("GET " + node.base() + "/down"));
        assertTrue(f.path("subject").asText().startsWith("Catalogue"));
        assertEquals("Not available", report.path("capabilities").get(0).path("status").asText());
        assertEquals(404, report.path("capabilities").get(0).path("http_code").asInt());
    }

    @Test
    void aFailureThatTheNodeItselfDeclaresIsOnlyInformation() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/maintenance-down", "\"status\":\"MAINTENANCE\""));
        run(CheckMode.STRICT);
        var f = findings(strictReport("N"), "endpoint-http-error").get(0);
        assertEquals("INFO", f.path("severity").asText());
        assertEquals("PASS", strictReport("N").path("strict_result").asText());
    }

    @Test
    void aRedirectIsReportedBecauseTheUrlAsPublishedDoesNotAnswer() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("AAI", "/redirect", ""));
        run(CheckMode.STRICT);
        var f = findings(strictReport("N"), "endpoint-redirected").get(0);
        assertEquals("WARNING", f.path("severity").asText());
        assertTrue(f.path("actual").asText().contains("/up"));
        assertEquals("Available", strictReport("N").path("capabilities").get(0).path("status").asText());
    }

    @Test
    void aRelativeOrMissingEndpointIsNeverRequestedOrGuessed() throws IOException {
        addNode("N", "/node/n");
        node.route("/node/n", q -> new FakeNode.Reply(200, "{\"node_endpoint\":\"" + node.base() + "/node/n\",\"capabilities\":["
                + "{\"capability_type\":\"A\",\"endpoint\":\"up\"},{\"capability_type\":\"B\"}]}"));
        run(CheckMode.STRICT);
        var report = strictReport("N");
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(1, findings(report, "endpoint-invalid").size());
        assertEquals(1, findings(report, "endpoint-missing").size());
        assertTrue(node.requests.stream().noneMatch(r -> r.equals("/up") || r.startsWith("/node/n/up")), "nothing was completed or guessed");
        assertEquals("Not available", report.path("capabilities").get(0).path("status").asText());
    }

    // ── the document ────────────────────────────────────────────────────────

    @Test
    void anEndpointsListIsNotExpandedAndTheNodeFails() throws IOException {
        addNode("DataTerra", "/node/dt");
        node.route("/node/dt", q -> new FakeNode.Reply(200, "{\"node_endpoint\":\"" + node.base() + "/node/dt\",\"capabilities\":["
                + "{\"capability_type\":\"Service Catalogue\",\"version\":\"0.2.58\",\"status\":\"OPERATIONAL\",\"endpoints\":["
                + "{\"protocol\":\"REST\",\"endpoint\":\"" + node.base() + "/up\"}]}]}"));
        run(CheckMode.STRICT);
        var report = strictReport("DataTerra");
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(1, report.path("capabilities").size(), "one row per capability, as published");
        assertEquals("", report.path("capabilities").get(0).path("endpoint").asText());
        assertEquals(1, findings(report, "endpoint-missing").size());
        assertTrue(findings(report, "field-not-in-model").isEmpty());
        assertTrue(node.requests.stream().noneMatch(r -> r.equals("/up")), "the nested URL was not used");
    }

    @Test
    void aFailedFetchIsRetriedAndTheRetryIsReported() throws IOException {
        addNode("Flaky", "/node/flaky");
        AtomicInteger calls = new AtomicInteger();
        node.route("/node/flaky", q -> calls.incrementAndGet() < 3 ? new FakeNode.Reply(502, "bad gateway")
                : new FakeNode.Reply(200, "{\"node_endpoint\":\"x\",\"capabilities\":[" + cap("AAI", "/up", "") + "]}"));
        run(CheckMode.STRICT);
        var report = strictReport("Flaky");
        var f = findings(report, "request-retried").get(0);
        assertEquals("WARNING", f.path("severity").asText());
        assertTrue(f.path("message").asText().contains("3 attempts"));
        assertEquals(3, calls.get());
    }

    @Test
    void anUnreadableDocumentIsAnErrorAndEarlierDataIsNotKept() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("AAI", "/up", ""));
        run(CheckMode.STRICT);
        assertEquals(1, strictReport("N").path("capabilities").size());

        node.status("/node/n", 503);
        var result = run(CheckMode.STRICT);

        var report = strictReport("N");
        assertEquals(0, report.path("capabilities").size(), "strict mode shows what is there now, not what was");
        assertFalse(report.has("capabilities_stale"));
        assertTrue(report.path("error").asText().contains("503"));
        var f = findings(report, "node-endpoint-unavailable").get(0);
        assertEquals(node.base() + "/node/n", f.path("url").asText());
        assertTrue(f.path("actual").asText().contains("HTTP 503") && f.path("actual").asText().contains("3 attempts"), f.toString());
        assertEquals(1, result.problems().size());
        assertEquals("N", result.problems().get(0).node());
    }

    @Test
    void aBadRegistryEntryIsNotRequestedAndSaysSo() throws IOException {
        addNode("Bad", "node.example.org/api");               // no scheme
        run(CheckMode.STRICT);
        var report = strictReport("Bad");
        assertEquals(1, findings(report, "registry-node-endpoint-invalid").size());
        assertTrue(report.has("error"));
        assertTrue(node.requests.stream().noneMatch(r -> r.contains("node.example.org")));
    }

    @Test
    void aStrictFailureIsListedAmongTheProblemsWithItsFirstError() throws IOException {
        addNode("Good", "/node/good");
        addNode("Bad", "/node/bad");
        document("/node/good", cap("AAI", "/up", ""));
        document("/node/bad", cap("AAI", "/down", ""));
        var result = run(CheckMode.STRICT);
        assertEquals(1, result.problems().size());
        assertEquals("Bad", result.problems().get(0).node());
        assertTrue(result.problems().get(0).reason().contains("1 error finding"), result.problems().get(0).reason());
        assertTrue(result.problems().get(0).reason().contains("GET " + node.base() + "/down"), result.problems().get(0).reason());
        assertTrue(result.message().contains("Bad"));
    }

    // ── api_spec and version ────────────────────────────────────────────────

    @Test
    void anOpenApiApiSpecIsNotedAndItsVersionComparedWithTheCapabilitys() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/up", "\"version\":\"1.0.0\",\"api_spec\":\"" + node.base() + "/spec/openapi\""));
        run(CheckMode.STRICT);
        var report = strictReport("N");
        assertEquals(1, findings(report, "api-spec-openapi").size());
        var differs = findings(report, "version-differs-from-api-spec").get(0);
        assertEquals("INFO", differs.path("severity").asText());
        assertTrue(differs.path("expected").asText().contains("2.0.0") && differs.path("actual").asText().equals("1.0.0"));
        assertEquals("PASS", report.path("strict_result").asText());
    }

    @Test
    void aMatchingVersionHasNoFinding() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/up", "\"version\":\"2.0.0\",\"api_spec\":\"" + node.base() + "/spec/openapi\""));
        run(CheckMode.STRICT);
        assertTrue(findings(strictReport("N"), "version-differs-from-api-spec").isEmpty());
    }

    @Test
    void anApiSpecThatIsADocumentationPageIsAWarningWithAdvice() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/up", "\"api_spec\":\"" + node.base() + "/spec/html\""));
        run(CheckMode.STRICT);
        var f = findings(strictReport("N"), "api-spec-not-machine-readable").get(0);
        assertEquals("WARNING", f.path("severity").asText());
        assertTrue(f.path("hint").asText().contains("openapi.json"));
    }

    @Test
    void anApiSpecThatCannotBeFetchedIsAnError() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/up", "\"api_spec\":\"" + node.base() + "/spec/missing\""));
        run(CheckMode.STRICT);
        var f = findings(strictReport("N"), "api-spec-unreachable").get(0);
        assertEquals("ERROR", f.path("severity").asText());
        assertEquals("HTTP 404", f.path("actual").asText());
        assertEquals("FAIL", strictReport("N").path("strict_result").asText());
    }

    @Test
    void jsonThatIsNotOpenApiIsAWarning() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Catalogue", "/up", "\"api_spec\":\"" + node.base() + "/spec/notopenapi\""));
        run(CheckMode.STRICT);
        assertEquals(1, findings(strictReport("N"), "api-spec-not-openapi").size());
    }

    @Test
    void anApiSpecSharedByCapabilitiesIsFetchedOnce() throws IOException {
        addNode("N", "/node/n");
        String spec = "\"api_spec\":\"" + node.base() + "/spec/openapi\"";
        document("/node/n", cap("A", "/up", spec), cap("B", "/up", spec));
        run(CheckMode.STRICT);
        assertEquals(1, node.requests.stream().filter(r -> r.equals("/spec/openapi")).count());
        assertEquals(2, findings(strictReport("N"), "api-spec-openapi").size(), "but it is reported for each capability");
    }

    @Test
    void textFormatIsNotProducedInStrictMode() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("AAI", "/up", ""));
        CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.TEXT, CheckNodeCapabilities.OutputFormat.JSON),
                dataDir, HttpClient.newHttpClient(), MAPPER, URI.create(node.base() + "/registry"), Duration.ZERO, CheckMode.STRICT);
        assertFalse(Files.exists(dataDir.resolve("node_registry_summary.txt")));
    }

    @Test
    void aRedirectThatIsNotFollowedSaysWhereItPointsWithoutItsQueryString() throws IOException {
        addNode("N", "/node/n");
        document("/node/n", cap("Helpdesk", "/login-redirect", ""));
        node.raw("/login-redirect", ex -> {
            ex.getResponseHeaders().add("Location", "https://login.example.org/oidc/authorize?state=SECRET&nonce=SECRET2");
            ex.sendResponseHeaders(302, -1);
        });
        CheckNodeCapabilities.run("key", EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON), dataDir,
                HttpClient.newHttpClient(),                       // does not follow redirects
                MAPPER, URI.create(node.base() + "/registry"), Duration.ZERO, CheckMode.STRICT);
        var report = strictReport("N");
        var f = findings(report, "endpoint-redirect-not-followed").get(0);
        assertEquals("ERROR", f.path("severity").asText());
        assertEquals("HTTP 302, redirecting to https://login.example.org/oidc/authorize (not followed)", f.path("actual").asText());
        assertFalse(report.toString().contains("SECRET"), "no one-time login values in the report");
    }

    // ── the payload, against the api_spec ────────────────────────────────────

    private static final String LIST_SPEC = "{\"openapi\":\"3.0.3\",\"paths\":{\"/list\":{\"get\":{\"responses\":{\"200\":"
            + "{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":[\"total\"],"
            + "\"properties\":{\"total\":{\"type\":\"integer\"}}}}}}}}}}}";

    private JsonNode payloadRun(String listBody, String specExtra, String specBody) throws IOException {
        addNode("P", "/node/p");
        node.route("/list", q -> new FakeNode.Reply(200, listBody));
        if (specBody != null) {
            node.route("/spec", q -> new FakeNode.Reply(200, specBody));
        }
        document("/node/p", cap("Service Catalogue", "/list", specExtra));
        run(CheckMode.STRICT);
        return strictReport("P");
    }

    @Test
    void anAnswerThatFollowsTheApiSpecIsCheckedAndPasses() throws IOException {
        JsonNode report = payloadRun("{\"total\":3}", "\"api_spec\":\"" + node.base() + "/spec\"", LIST_SPEC);
        JsonNode check = report.path("capabilities").get(0).path("payload_check");
        assertEquals("checked", check.path("status").asText());
        assertEquals("PASS", check.path("result").asText());
        assertEquals("GET /list", check.path("operation").asText());
        assertEquals(1, findings(report, "payload-matches-api-spec").size());
        assertEquals(0, findings(report, "payload-differs-from-api-spec").size());
    }

    @Test
    void anAnswerThatBreaksTheApiSpecFailsAndTheViolationIsShown() throws IOException {
        JsonNode report = payloadRun("{\"total\":\"three\"}", "\"api_spec\":\"" + node.base() + "/spec\"", LIST_SPEC);
        JsonNode check = report.path("capabilities").get(0).path("payload_check");
        assertEquals("FAIL", check.path("result").asText());
        assertEquals(1, check.path("violation_count").asInt());
        assertTrue(check.path("violations").get(0).asText().contains("total"), check.toString());
        var f = findings(report, "payload-differs-from-api-spec");
        assertEquals(1, f.size());
        assertEquals("ERROR", f.get(0).path("severity").asText());
        assertEquals("FAIL", report.path("strict_result").asText());
    }

    @Test
    void withNoApiSpecThePayloadIsReportedAsNotChecked() throws IOException {
        JsonNode report = payloadRun("{\"total\":3}", "", null);
        JsonNode check = report.path("capabilities").get(0).path("payload_check");
        assertEquals("not_checked", check.path("status").asText());
        assertEquals("no api_spec is published", check.path("reason").asText());
        assertEquals(0, findings(report, "payload-differs-from-api-spec").size());
        assertEquals(0, findings(report, "payload-matches-api-spec").size());
    }

    @Test
    void anApiSpecThatIsADocumentationPageMeansThePayloadIsNotChecked() throws IOException {
        JsonNode report = payloadRun("{\"total\":3}", "\"api_spec\":\"" + node.base() + "/spec\"", "<html>docs</html>");
        JsonNode check = report.path("capabilities").get(0).path("payload_check");
        assertEquals("not_checked", check.path("status").asText());
        assertTrue(check.path("reason").asText().contains("could not be read as a JSON document"), check.toString());
    }

    @Test
    void anApiSpecWithNoPathForTheEndpointSaysSo() throws IOException {
        String other = LIST_SPEC.replace("\"/list\"", "\"/elsewhere\"");
        JsonNode report = payloadRun("{\"total\":3}", "\"api_spec\":\"" + node.base() + "/spec\"", other);
        JsonNode check = report.path("capabilities").get(0).path("payload_check");
        assertEquals("not_checked", check.path("status").asText());
        assertTrue(check.path("reason").asText().contains("no path for /list"), check.toString());
    }

    @Test
    void anEndpointThatDoesNotAnswerHasNoPayloadToCheck() throws IOException {
        addNode("P", "/node/p");
        node.status("/list", 503);
        node.route("/spec", q -> new FakeNode.Reply(200, LIST_SPEC));
        document("/node/p", cap("Service Catalogue", "/list", "\"api_spec\":\"" + node.base() + "/spec\""));
        run(CheckMode.STRICT);
        JsonNode check = strictReport("P").path("capabilities").get(0).path("payload_check");
        assertEquals("not_checked", check.path("status").asText());
        assertEquals("the endpoint did not answer successfully", check.path("reason").asText());
    }
}
