package eu.cessda.pilotnode.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.cessda.pilotnode.catalogue.JsonFetcher;

class FindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesAllTheFieldsThatAreSet() {
        JsonNode json = new Finding(Finding.Severity.ERROR, "endpoint-http-error", "It failed", "Service Catalogue",
                "https://example.org/x", "HTTP 200", "HTTP 404", "Check the URL").toJson(MAPPER);
        assertEquals(List.of("severity", "code", "message", "subject", "url", "expected", "actual", "hint", "fix"),
                List.copyOf(json.properties().stream().map(java.util.Map.Entry::getKey).toList()));
        assertEquals("ERROR", json.path("severity").asText());
        assertEquals("HTTP 404", json.path("actual").asText());
    }

    @Test
    void leavesOutOptionalFieldsThatAreNullOrEmptyButAlwaysWritesTheMessage() {
        JsonNode json = new Finding(Finding.Severity.INFO, "note", "", null, "", null, "seen", null).toJson(MAPPER);
        assertEquals("", json.path("message").asText());
        assertTrue(json.has("message"));
        assertEquals("seen", json.path("actual").asText());
        for (String absent : List.of("subject", "url", "expected", "hint")) {
            assertFalse(json.has(absent), absent);
        }
    }

    @Test
    void fetchingWithFewerThanOneAttemptIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> JsonFetcher.fetchWithRetries(HttpClient.newHttpClient(),
                MAPPER, URI.create("http://127.0.0.1:1/"), 0, Duration.ZERO));
    }
}
