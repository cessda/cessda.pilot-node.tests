package eu.cessda.pilotnode.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Checking an answer against the OpenAPI document its capability names. */
class OpenApiPayloadCheckTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return M.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String LIST_SCHEMA = "{\"type\":\"object\",\"required\":[\"total\",\"results\"],"
            + "\"properties\":{\"total\":{\"type\":\"integer\"},\"results\":{\"type\":\"array\","
            + "\"items\":{\"$ref\":\"#/components/schemas/Item\"}}}}";
    private static final String ITEM = "{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"string\"}}}";

    private static JsonNode spec(String servers, String pathBlock) {
        return json("{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"t\",\"version\":\"1\"}," + servers + "\"paths\":{" + pathBlock
                + "},\"components\":{\"schemas\":{\"Item\":" + ITEM + "}}}");
    }

    private static String getWith(String path, String responseBlock) {
        return "\"" + path + "\":{\"get\":{\"responses\":{" + responseBlock + "}}}";
    }

    private static final String OK_LIST = "\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":{\"schema\":" + LIST_SCHEMA + "}}}";

    @Test
    void anAnswerThatFollowsTheSchemaPasses() {
        var r = OpenApiPayloadCheck.check(spec("", getWith("/service/all", OK_LIST)), URI.create("https://n.example/service/all"),
                json("{\"total\":1,\"results\":[{\"id\":\"a\"}]}"));
        assertTrue(r.checked());
        assertTrue(r.valid(), r.violations().toString());
        assertEquals("GET /service/all", r.operation());
    }

    @Test
    void anAnswerThatBreaksTheSchemaFailsAndSaysHow() {
        var r = OpenApiPayloadCheck.check(spec("", getWith("/service/all", OK_LIST)), URI.create("https://n.example/service/all"),
                json("{\"total\":\"many\",\"results\":[{\"name\":\"x\"}]}"));
        assertTrue(r.checked());
        assertFalse(r.valid());
        assertEquals(2, r.violationCount());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("total")), r.violations().toString());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("id")), r.violations().toString());
    }

    @Test
    void theViolationsShownAreCappedButAllAreCounted() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            items.append(i == 0 ? "" : ",").append("{}");
        }
        var r = OpenApiPayloadCheck.check(spec("", getWith("/x", OK_LIST)), URI.create("https://n.example/x"),
                json("{\"total\":1,\"results\":[" + items + "]}"));
        assertEquals(25, r.violationCount());
        assertEquals(OpenApiPayloadCheck.MAX_VIOLATIONS, r.violations().size());
    }

    @Test
    void aPathTemplateMatchesAndAnExactPathWinsOverIt() {
        JsonNode spec = spec("", getWith("/service/{id}", "\"200\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"string\"}}}}")
                + "," + getWith("/service/all", "\"200\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"integer\"}}}}"));
        assertTrue(OpenApiPayloadCheck.check(spec, URI.create("https://n.example/service/abc"), json("\"text\"")).valid());
        var exact = OpenApiPayloadCheck.check(spec, URI.create("https://n.example/service/all"), json("\"text\""));
        assertFalse(exact.valid(), "the exact path /service/all says integer, not the {id} template");
        assertEquals("GET /service/all", exact.operation());
    }

    @Test
    void theServersPathIsTakenOffTheEndpointsPath() {
        JsonNode spec = spec("\"servers\":[{\"url\":\"https://n.example/api/v2\"}],", getWith("/service/all", OK_LIST));
        var r = OpenApiPayloadCheck.check(spec, URI.create("https://n.example/api/v2/service/all"),
                json("{\"total\":0,\"results\":[]}"));
        assertTrue(r.checked() && r.valid(), String.valueOf(r.reason()));
    }

    @Test
    void aSwagger2DocumentIsUsedToo() {
        JsonNode spec = json("{\"swagger\":\"2.0\",\"basePath\":\"/api\",\"paths\":{\"/ping\":{\"get\":{\"responses\":{\"200\":"
                + "{\"schema\":{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"type\":\"boolean\"}}}}}}}}}");
        assertTrue(OpenApiPayloadCheck.check(spec, URI.create("https://n.example/api/ping"), json("{\"ok\":true}")).valid());
        assertFalse(OpenApiPayloadCheck.check(spec, URI.create("https://n.example/api/ping"), json("{\"ok\":1}")).valid());
    }

    @Test
    void aResponseDefinedByReferenceIsFollowed() {
        JsonNode spec = json("{\"openapi\":\"3.0.0\",\"paths\":{\"/p\":{\"get\":{\"responses\":{\"200\":{\"$ref\":\"#/components/responses/R\"}}}}},"
                + "\"components\":{\"responses\":{\"R\":{\"description\":\"r\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"array\"}}}}}}}");
        assertTrue(OpenApiPayloadCheck.check(spec, URI.create("https://n.example/p"), json("[]")).valid());
        assertFalse(OpenApiPayloadCheck.check(spec, URI.create("https://n.example/p"), json("{}")).valid());
    }

    @Test
    void aPayloadThatIsNotJsonFailsWhenTheSpecificationSaysJson() {
        var r = OpenApiPayloadCheck.check(spec("", getWith("/x", OK_LIST)), URI.create("https://n.example/x"), null);
        assertTrue(r.checked());
        assertFalse(r.valid());
        assertTrue(r.violations().get(0).contains("not valid JSON"));
    }

    @Test
    void itSaysWhyANoCheckWasPossible() {
        URI x = URI.create("https://n.example/x");
        JsonNode body = json("{}");
        assertReason("not an OpenAPI document", OpenApiPayloadCheck.check(json("{\"hello\":1}"), x, body));
        assertReason("no path for /x", OpenApiPayloadCheck.check(spec("", getWith("/other", OK_LIST)), x, body));
        assertReason("no GET operation for /x", OpenApiPayloadCheck.check(
                spec("", "\"/x\":{\"post\":{\"responses\":{\"200\":{\"description\":\"d\"}}}}"), x, body));
        assertReason("no success (2xx) response", OpenApiPayloadCheck.check(
                spec("", getWith("/x", "\"404\":{\"description\":\"d\"}")), x, body));
        assertReason("no JSON response", OpenApiPayloadCheck.check(
                spec("", getWith("/x", "\"200\":{\"content\":{\"text/html\":{\"schema\":{\"type\":\"string\"}}}}")), x, body));
        assertReason("no response schema", OpenApiPayloadCheck.check(
                spec("", getWith("/x", "\"200\":{\"content\":{\"application/json\":{}}}")), x, body));
        assertReason("no paths", OpenApiPayloadCheck.check(json("{\"openapi\":\"3.0.0\"}"), x, body));
    }

    @Test
    void aReferenceToAnotherDocumentIsNeverFollowed() {
        JsonNode spec = json("{\"openapi\":\"3.0.0\",\"paths\":{\"/x\":{\"get\":{\"responses\":{\"200\":{\"content\":"
                + "{\"application/json\":{\"schema\":{\"$ref\":\"https://evil.example/schema.json\"}}}}}}}}}");
        var r = OpenApiPayloadCheck.check(spec, URI.create("https://n.example/x"), json("{}"));
        assertFalse(r.checked());
        assertTrue(r.reason().contains("another document"), r.reason());
    }

    @Test
    void aResultIsWrittenAsJson() {
        var checked = OpenApiPayloadCheck.check(spec("", getWith("/x", OK_LIST)), URI.create("https://n.example/x"), json("{}"));
        JsonNode j = checked.toJson(M, "https://n.example/openapi.json");
        assertEquals("checked", j.path("status").asText());
        assertEquals("FAIL", j.path("result").asText());
        assertEquals("GET /x", j.path("operation").asText());
        assertEquals("https://n.example/openapi.json", j.path("api_spec").asText());
        JsonNode not = OpenApiPayloadCheck.Result.notChecked("because").toJson(M, null);
        assertEquals("not_checked", not.path("status").asText());
        assertEquals("because", not.path("reason").asText());
        assertNull(not.get("api_spec"));
    }

    private static void assertReason(String contains, OpenApiPayloadCheck.Result r) {
        assertFalse(r.checked(), "expected not checked: " + contains);
        assertTrue(r.reason().contains(contains), r.reason());
    }
}
