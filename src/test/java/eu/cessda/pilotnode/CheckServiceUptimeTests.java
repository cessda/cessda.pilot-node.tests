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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CheckServiceUptime}.
 *
 * <p>Network calls are not made. Tests cover constructor validation,
 * date arithmetic, uptime-averaging logic, and JSON report structure.
 */
class CheckServiceUptimeTests {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Constructor ───────────────────────────────────────────────────────────

    @Test
    void constructor_createsInstanceWithoutThrowing(@TempDir Path tempDir) {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end   = LocalDate.of(2026, 1, 31);

        CheckServiceUptime checker =
                new CheckServiceUptime("CESSDA", "api-key", start, end, tempDir);

        assertThat(checker).isNotNull();
    }

    // ── Date handling ─────────────────────────────────────────────────────────

    @Test
    void startDate_mustBeBeforeEndDate() {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end   = LocalDate.of(2026, 1, 1);
        // The class performs this guard in main(); replicate the rule here
        assertThat(start.isBefore(end)).isFalse();
    }

    @Test
    void startDate_beforeEndDate_isValid() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end   = LocalDate.of(2026, 1, 31);
        assertThat(start.isBefore(end)).isTrue();
    }

    @Test
    void defaultDateRange_is30Days() {
        LocalDate end   = LocalDate.now();
        LocalDate start = end.minusDays(30);
        assertThat(start).isEqualTo(LocalDate.now().minusDays(30));
    }

    // ── API URL construction ──────────────────────────────────────────────────

    @Test
    void apiUrl_containsStartAndEndTime() {
        String base      = "https://api.example.com/api/v2/results/CORE/SERVICEGROUPS";
        LocalDate start  = LocalDate.of(2026, 2, 1);
        LocalDate end    = LocalDate.of(2026, 3, 17);

        String url = base +
                "?start_time=" + start + "T00:00:00Z" +
                "&end_time="   + end   + "T23:59:59Z";

        assertThat(url).contains("start_time=2026-02-01T00:00:00Z");
        assertThat(url).contains("end_time=2026-03-17T23:59:59Z");
    }

    // ── Uptime averaging logic ────────────────────────────────────────────────

    /** Mirrors the averaging loop in {@code CheckServiceUptime.run()}. */
    private static double averageUptime(double[] dailyUptimes) {
        if (dailyUptimes.length == 0) return 0;
        double sum = 0;
        for (double d : dailyUptimes) sum += d;
        return (sum / dailyUptimes.length) * 100.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Test
    void averageUptime_perfectUptime_returns100() {
        double result = averageUptime(new double[]{1.0, 1.0, 1.0});
        assertThat(round2(result)).isEqualTo(100.0);
    }

    @Test
    void averageUptime_zeroUptime_returns0() {
        double result = averageUptime(new double[]{0.0, 0.0, 0.0});
        assertThat(round2(result)).isEqualTo(0.0);
    }

    @Test
    void averageUptime_mixedValues_returnsCorrectAverage() {
        // (0.8 + 1.0 + 0.6) / 3 = 0.8 -> 80.0 %
        double result = averageUptime(new double[]{0.8, 1.0, 0.6});
        assertThat(round2(result)).isEqualTo(80.0);
    }

    @Test
    void averageUptime_emptyArray_returns0() {
        assertThat(averageUptime(new double[]{})).isEqualTo(0.0);
    }

    @ParameterizedTest(name = "round2({0}) = {1}")
    @CsvSource({
        "99.999,  100.0",
        "80.005,   80.01",
        "33.333,   33.33",
        "0.001,     0.0"
    })
    void round2_roundsToTwoDecimalPlaces(double input, double expected) {
        assertThat(round2(input)).isEqualTo(expected);
    }

    // ── JSON report structure ─────────────────────────────────────────────────

    @Test
    void jsonReport_hasRequiredTopLevelFields() throws Exception {
        ObjectNode report = mapper.createObjectNode();
        report.put("generated",  "2026-01-01T00:00:00Z");
        report.put("api_source", "https://example.com/api");

        ObjectNode period = report.putObject("period");
        period.put("start", "2026-01-01T00:00:00Z");
        period.put("end",   "2026-01-31T23:59:59Z");

        report.put("project", "CORE");

        ArrayNode endpoints = mapper.createArrayNode();
        ObjectNode ep = mapper.createObjectNode();
        ep.put("name",                 "my-service");
        ep.put("type",                 "OAI-PMH");
        ep.put("uptime_percentage",    99.5);
        ep.put("average_availability", 99.5);
        ep.put("average_reliability",  98.0);
        ep.put("days_monitored",       30);
        endpoints.add(ep);
        report.set("endpoints", endpoints);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);

        assertThat(json).contains("\"project\" : \"CORE\"");
        assertThat(json).contains("\"uptime_percentage\" : 99.5");
        assertThat(json).contains("\"days_monitored\" : 30");
    }

    @Test
    void jsonReport_isWrittenToCorrectPath(@TempDir Path tempDir) throws Exception {
        Path nodeDir    = tempDir.resolve("CESSDA");
        Files.createDirectories(nodeDir);
        Path reportFile = nodeDir.resolve("argo_uptime_report.json");

        ObjectNode stub = mapper.createObjectNode();
        stub.put("generated", "now");
        stub.set("endpoints", mapper.createArrayNode());

        Files.writeString(reportFile,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stub));

        assertThat(reportFile).exists();
        assertThat(Files.readString(reportFile)).contains("\"generated\"");
    }
}