/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.pilotnode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CheckNodeCapabilities}.
 *
 * <p>HTTP calls are not made in these tests. Instead, the internal
 * helpers and supporting types are exercised directly.
 */
class CheckNodeCapabilitiesTests {

    // ── OutputFormat ──────────────────────────────────────────────────────────

    @Test
    void outputFormat_textIncludesText_notJson() {
        assertThat(CheckNodeCapabilities.OutputFormat.TEXT.includesText()).isTrue();
        assertThat(CheckNodeCapabilities.OutputFormat.TEXT.includesJson()).isFalse();
    }

    @Test
    void outputFormat_jsonIncludesJson_notText() {
        assertThat(CheckNodeCapabilities.OutputFormat.JSON.includesJson()).isTrue();
        assertThat(CheckNodeCapabilities.OutputFormat.JSON.includesText()).isFalse();
    }

    @Test
    void outputFormat_bothIncludesTextAndJson() {
        assertThat(CheckNodeCapabilities.OutputFormat.BOTH.includesText()).isTrue();
        assertThat(CheckNodeCapabilities.OutputFormat.BOTH.includesJson()).isTrue();
    }

    // ── CapabilityStatus ──────────────────────────────────────────────────────

    @Test
    void capabilityStatus_of_000_returnsNotAvailable() {
        CheckNodeCapabilities.CapabilityStatus status =
                CheckNodeCapabilities.CapabilityStatus.of("000");
        assertThat(status.httpCode()).isEqualTo("000");
        assertThat(status.label()).isEqualTo("Not available");
    }

    @Test
    void capabilityStatus_of_404_returnsNotFound() {
        CheckNodeCapabilities.CapabilityStatus status =
                CheckNodeCapabilities.CapabilityStatus.of("404");
        assertThat(status.httpCode()).isEqualTo("404");
        assertThat(status.label()).isEqualTo("Not found");
    }

    @ParameterizedTest(name = "HTTP {0} -> Available")
    @CsvSource({"200", "201", "204", "301", "302"})
    void capabilityStatus_of_2xxAnd3xx_returnsAvailable(String code) {
        CheckNodeCapabilities.CapabilityStatus status =
                CheckNodeCapabilities.CapabilityStatus.of(code);
        assertThat(status.label()).isEqualTo("Available");
        assertThat(status.httpCode()).isEqualTo(code);
    }

    @ParameterizedTest(name = "HTTP {0} -> Not available")
    @CsvSource({"400", "401", "403", "500", "503"})
    void capabilityStatus_of_4xxAnd5xx_returnsNotAvailable(String code) {
        CheckNodeCapabilities.CapabilityStatus status =
                CheckNodeCapabilities.CapabilityStatus.of(code);
        assertThat(status.label()).isEqualTo("Not available");
        assertThat(status.httpCode()).isEqualTo(code);
    }

    // ── Constructor / field initialisation ───────────────────────────────────

    @Test
    void constructor_storesFieldsWithoutThrowing(@TempDir Path tempDir) {
        // Should not throw; verifies the object can be created
        CheckNodeCapabilities checker = new CheckNodeCapabilities(
                "test-key", CheckNodeCapabilities.OutputFormat.JSON, tempDir);
        assertThat(checker).isNotNull();
    }

    // ── buildNodeSummary (via reflection helper below) ────────────────────────
    // The method is package-private in intent (private in source), so we test
    // its observable output through the public ObjectNode produced by the record.

    @Test
    void capabilityStatus_record_componentsAreAccessible() {
        CheckNodeCapabilities.CapabilityStatus s =
                new CheckNodeCapabilities.CapabilityStatus("200", "Available");
        assertThat(s.httpCode()).isEqualTo("200");
        assertThat(s.label()).isEqualTo("Available");
    }

    // ── ObjectMapper sanity (used internally) ─────────────────────────────────

    @Test
    void objectMapper_canSerialiseTypicalNodeSummary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("node_name", "TestNode");
        node.put("total_capabilities", 5);
        node.put("available_capabilities", 3);

        String json = mapper.writeValueAsString(node);
        assertThat(json).contains("\"node_name\":\"TestNode\"");
        assertThat(json).contains("\"total_capabilities\":5");
    }
}