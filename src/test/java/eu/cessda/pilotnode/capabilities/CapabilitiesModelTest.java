package eu.cessda.pilotnode.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cessda.pilotnode.capabilities.CapabilitiesModel;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.cessda.pilotnode.common.Findings;
import eu.cessda.pilotnode.common.Finding;

/** The Node Endpoint document, checked against what the dashboard needs of it. */
class CapabilitiesModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Findings validate(String json) throws IOException {
        return CapabilitiesModel.validate(MAPPER.readTree(json));
    }

    private static List<Finding> withCode(Findings f, String code) {
        return f.all().stream().filter(x -> x.code().equals(code)).toList();
    }

    /** The example from the model's own README. */
    private static final String README_EXAMPLE = """
            {"node_endpoint":"https://node.eosc-beyond.eu","capabilities":[
              {"capability_type":"metadata","endpoint":"https://node.eosc-beyond.eu/api/metadata","version":"1.0.0",
               "api_spec":"https://node.eosc-beyond.eu/api/openapi.json","protocol":"REST","status":"OPERATIONAL"}]}""";

    @Test
    void theModelsOwnExampleHasNoFindings() throws IOException {
        assertTrue(validate(README_EXAMPLE).all().isEmpty(), validate(README_EXAMPLE).all().toString());
    }

    @Test
    void protocolAndStatusMayBeLeftOutBecauseTheModelDefaultsThem() throws IOException {
        assertTrue(validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"capability_type\":\"AAI\",\"endpoint\":\"https://n/aai\"}]}").all().isEmpty());
    }

    @Test
    void version_and_api_specMayBeNull() throws IOException {
        assertTrue(validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"capability_type\":\"AAI\",\"endpoint\":\"https://n/aai\",\"version\":null,\"api_spec\":null}]}").all().isEmpty());
    }

    // ── Data-Terra's current document ───────────────────────────────────────

    private static final String DATA_TERRA_SHAPE = """
            {"node_endpoint":"https://resources.earth-data.eu/.well-known/eosc-federation/node",
             "node_details":{"name":"Data Terra EOSC Node"},
             "capabilities":[
               {"capability_type":"Service Catalogue","version":"0.2.58","status":"OPERATIONAL","endpoints":[
                 {"protocol":"REST","endpoint":"https://s/services","api_spec":"https://s/docs#/EEN"}]},
               {"capability_type":"AAI","protocol":"OIDC","status":"OPERATIONAL","endpoint":"https://sso/.well-known/openid-configuration"},
               {"capability_type":"Monitoring","status":"PLANNED"}]}""";

    @Test
    void anEndpointsListGetsAHintToListEachInterfaceAsItsOwnCapability() throws IOException {
        var missing = withCode(validate(DATA_TERRA_SHAPE), "endpoint-missing").stream()
                .filter(x -> x.subject().startsWith("Service Catalogue")).findFirst().orElseThrow();
        assertTrue(missing.hint().contains("own capability entry"), missing.hint());
    }

    @Test
    void aFieldTheDashboardDoesNotKnowIsNotAFinding() throws IOException {
        assertTrue(withCode(validate(DATA_TERRA_SHAPE), "field-not-in-model").isEmpty());
        var withId = validate("{\"node_endpoint\":\"https://n/e\",\"node_details\":{},\"capabilities\":["
                + "{\"id\":null,\"capability_type\":\"AAI\",\"endpoint\":\"https://n/aai\",\"status\":\"OPERATIONAL\"}]}");
        assertTrue(withId.all().isEmpty(), withId.all().toString());
    }

    @Test
    void aLiveCapabilityWithoutAnEndpointIsAnErrorThatPointsAtTheEndpointsList() throws IOException {
        var missing = withCode(validate(DATA_TERRA_SHAPE), "endpoint-missing");
        var catalogue = missing.stream().filter(x -> x.subject().startsWith("Service Catalogue")).findFirst().orElseThrow();
        assertEquals(Finding.Severity.ERROR, catalogue.severity());
        assertTrue(catalogue.hint().contains("one endpoint per capability"));
    }

    @Test
    void aCapabilityThatIsNotLiveMayLackAnEndpointWithOnlyAWarning() throws IOException {
        var monitoring = withCode(validate(DATA_TERRA_SHAPE), "endpoint-missing").stream()
                .filter(x -> x.subject().startsWith("Monitoring")).findFirst().orElseThrow();
        assertEquals(Finding.Severity.WARNING, monitoring.severity());
    }

    @Test
    void valuesOutsideTheRecommendedListsAreWarningsNotErrors() throws IOException {
        var f = validate(DATA_TERRA_SHAPE);
        assertEquals("OIDC", withCode(f, "protocol-not-recommended").get(0).actual());
        assertEquals("PLANNED", withCode(f, "status-not-recommended").get(0).actual());
        assertEquals(Finding.Severity.WARNING, withCode(f, "protocol-not-recommended").get(0).severity());
    }

    // ── individual rules ────────────────────────────────────────────────────

    @Test
    void protocolsAreComparedAsTheModelDoes() {
        for (String ok : List.of("REST", "rest", " Rest ", "gRPC", "GRPC", "web socket", "web-socket", "Web_Socket", "rsocket", "SSE", "soap")) {
            assertTrue(CapabilitiesModel.isRecommendedProtocol(ok), ok);
        }
        for (String other : List.of("OAI-PMH", "OIDC", "SPARQL", "FTP")) {
            assertFalse(CapabilitiesModel.isRecommendedProtocol(other), other);
        }
    }

    @Test
    void anEndpointMustBeAbsolute() throws IOException {
        for (String bad : List.of("node.example.org/api", "/api/x", "not a uri", "")) {
            var f = validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"capability_type\":\"X\",\"endpoint\":\"" + bad + "\"}]}");
            assertTrue(f.hasErrors(), "should be an error: '" + bad + "'");
        }
    }

    @Test
    void aNonHttpEndpointIsAWarningBecauseItCannotBeProbed() throws IOException {
        var f = validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"capability_type\":\"X\",\"endpoint\":\"grpc://n:9000\"}]}");
        assertFalse(f.hasErrors());
        assertEquals(1, withCode(f, "endpoint-not-http").size());
    }

    @Test
    void anApiSpecThatIsNotAnAbsoluteUriIsAnError() throws IOException {
        var f = validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"capability_type\":\"X\",\"endpoint\":\"https://n/x\",\"api_spec\":\"docs/openapi.json\"}]}");
        assertEquals(1, withCode(f, "api-spec-invalid").size());
        assertTrue(f.hasErrors());
    }

    @Test
    void aNumericVersionIsNotAString() throws IOException {
        var f = validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"capability_type\":\"X\",\"endpoint\":\"https://n/x\",\"version\":2}]}");
        assertEquals(1, withCode(f, "version-not-string").size());
        assertFalse(f.hasErrors());
    }

    @Test
    void aCapabilityNeedsAType() throws IOException {
        var f = validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[{\"endpoint\":\"https://n/x\"}]}");
        assertEquals(1, withCode(f, "capability-type-missing").size());
    }

    @Test
    void theDocumentNeedsAListOfCapabilities() throws IOException {
        assertEquals(1, withCode(validate("{\"node_endpoint\":\"https://n\"}"), "capabilities-missing").size());
        assertEquals(1, withCode(validate("{\"node_endpoint\":\"https://n\",\"capabilities\":{}}"), "capabilities-missing").size());
        assertEquals(1, withCode(validate("[]"), "document-not-object").size());
        assertEquals(1, withCode(validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[]}"), "capabilities-empty").size());
    }

    @Test
    void theNodeEndpointIsExpectedButItsAbsenceIsOnlyAWarning() throws IOException {
        var absent = validate("{\"capabilities\":[]}");
        assertEquals(Finding.Severity.WARNING, withCode(absent, "node-endpoint-missing").get(0).severity());
        assertTrue(validate("{\"node_endpoint\":\"not absolute\",\"capabilities\":[]}").hasErrors());
    }

    @Test
    void capabilitiesThatAreNotObjectsAreReported() throws IOException {
        var f = validate("{\"node_endpoint\":\"https://n\",\"capabilities\":[\"x\", 3]}");
        assertEquals(2, withCode(f, "capability-not-object").size());
    }

    @Test
    void statusIsComparedIgnoringCase() {
        assertTrue(CapabilitiesModel.declaresLive(null));
        assertTrue(CapabilitiesModel.declaresLive("operational"));
        assertTrue(CapabilitiesModel.declaresLive(" OPERATIONAL "));
        assertFalse(CapabilitiesModel.declaresLive("MAINTENANCE"));
        assertFalse(CapabilitiesModel.declaresLive("PLANNED"));
    }

    @Test
    void findingsAreWrittenWithTheirCountsAndAResult() throws IOException {
        var f = validate(DATA_TERRA_SHAPE);
        var report = MAPPER.createObjectNode();
        f.writeTo(report, MAPPER);
        assertEquals("strict", report.path("mode").asText());
        assertEquals("FAIL", report.path("strict_result").asText());
        assertEquals(f.count(Finding.Severity.ERROR), report.path("findings_error").asLong());
        assertEquals(f.all().size(), report.path("findings").size());
        JsonNode first = report.path("findings").get(0);
        assertNotNull(first.get("severity"));
        assertNotNull(first.get("code"));

        var clean = MAPPER.createObjectNode();
        new Findings().writeTo(clean, MAPPER);
        assertEquals("PASS", clean.path("strict_result").asText());
    }
}
