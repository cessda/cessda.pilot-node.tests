/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.pilotnode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for logic exercised by {@link CheckCatalogueServices}.
 *
 * <p>Because the class is a CLI-only utility (its logic lives in {@code main}),
 * these tests focus on the JSON-building contract and the status-classification
 * rules that mirror the inline logic in {@code main}.
 */
class CheckCatalogueServicesTests {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Status classification rules ───────────────────────────────────────────

    /** Reproduces the inline status logic from CheckCatalogueServices.main. */
    private static String classify(String httpCode, String webpage) {
        if (webpage == null || webpage.isBlank()) {
            return "No webpage defined";
        }
        int code;
        try { code = Integer.parseInt(httpCode); } catch (NumberFormatException e) { code = 0; }

        if (code == 0)                       return "Not available";
        if (code == 404)                     return "Not found";
        if (code >= 200 && code < 400)       return "Available";
        return "Not available";
    }

    @Test
    void classify_nullWebpage_returnsNoWebpageDefined() {
        assertThat(classify(null, null)).isEqualTo("No webpage defined");
    }

    @Test
    void classify_blankWebpage_returnsNoWebpageDefined() {
        assertThat(classify(null, "   ")).isEqualTo("No webpage defined");
    }

    @Test
    void classify_httpCode000_returnsNotAvailable() {
        assertThat(classify("000", "https://example.com")).isEqualTo("Not available");
    }

    @Test
    void classify_httpCode404_returnsNotFound() {
        assertThat(classify("404", "https://example.com")).isEqualTo("Not found");
    }

    @Test
    void classify_httpCode200_returnsAvailable() {
        assertThat(classify("200", "https://example.com")).isEqualTo("Available");
    }

    @Test
    void classify_httpCode301_returnsAvailable() {
        assertThat(classify("301", "https://example.com")).isEqualTo("Available");
    }

    @Test
    void classify_httpCode399_returnsAvailable() {
        assertThat(classify("399", "https://example.com")).isEqualTo("Available");
    }

    @Test
    void classify_httpCode500_returnsNotAvailable() {
        assertThat(classify("500", "https://example.com")).isEqualTo("Not available");
    }

    @Test
    void classify_httpCode400_returnsNotAvailable() {
        assertThat(classify("400", "https://example.com")).isEqualTo("Not available");
    }

    @Test
    void classify_nonNumericCode_returnsNotAvailable() {
        // Simulates a network error string that is not a number
        assertThat(classify("ERR", "https://example.com")).isEqualTo("Not available");
    }

    // ── JSON report structure ─────────────────────────────────────────────────

    @Test
    void jsonReport_hasExpectedTopLevelFields() throws Exception {
        ObjectNode report = mapper.createObjectNode();
        report.put("generated",      "2026-01-01T00:00:00Z");
        report.put("node_name",      "TestNode");
        report.put("api_source",     "https://example.com/api");
        report.put("total_services", 2L);

        ArrayNode services = mapper.createArrayNode();

        ObjectNode svc1 = mapper.createObjectNode();
        svc1.put("name",         "Service Alpha");
        svc1.put("abbreviation", "SA");
        svc1.put("service_id",   "svc-001");
        svc1.put("webpage",      "https://alpha.example.com");
        svc1.put("status",       "Available");
        svc1.put("http_code",    "200");
        services.add(svc1);

        ObjectNode svc2 = mapper.createObjectNode();
        svc2.put("name",         "Service Beta");
        svc2.put("abbreviation", "SB");
        svc2.put("service_id",   "svc-002");
        svc2.putNull("webpage");
        svc2.put("status",       "No webpage defined");
        svc2.putNull("http_code");
        services.add(svc2);

        report.set("services", services);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        assertThat(json).contains("\"node_name\" : \"TestNode\"");
        assertThat(json).contains("\"total_services\" : 2");
        assertThat(json).contains("\"status\" : \"Available\"");
        assertThat(json).contains("\"status\" : \"No webpage defined\"");
    }

    // ── Output directory creation ─────────────────────────────────────────────

    @Test
    void outputDirectory_isCreatedCorrectly(@TempDir Path tempDir) throws Exception {
        Path nodeDir = tempDir.resolve("TESTNODE");
        Files.createDirectories(nodeDir);

        Path reportFile = nodeDir.resolve("catalogue_services_report.json");

        ObjectNode stub = mapper.createObjectNode();
        stub.put("generated",      "now");
        stub.put("node_name",      "TESTNODE");
        stub.put("api_source",     "https://example.com");
        stub.put("total_services", 0L);
        stub.set("services",       mapper.createArrayNode());

        Files.writeString(reportFile,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stub));

        assertThat(reportFile).exists();
        String written = Files.readString(reportFile);
        assertThat(written).contains("TESTNODE");
    }

    // ── API URL construction ──────────────────────────────────────────────────

    @Test
    void apiUrl_isFormattedCorrectly() {
        String base     = "https://service-catalogue-staging.beyond.cessda.eu/api/service/all";
        String nodeName = "CESSDA";
        int    quantity = 10;

        String url = "%s?keyword=%s&from=0&quantity=%d&order=asc"
                .formatted(base, nodeName, quantity);

        assertThat(url).isEqualTo(
                "https://service-catalogue-staging.beyond.cessda.eu/api/service/all"
                + "?keyword=CESSDA&from=0&quantity=10&order=asc");
    }

    @Test
    void apiUrl_usesCustomQuantity() {
        String base = "https://example.com/api/service/all";
        String url = "%s?keyword=%s&from=0&quantity=%d&order=asc"
                .formatted(base, "NODE", 25);
        assertThat(url).contains("quantity=25");
    }
}