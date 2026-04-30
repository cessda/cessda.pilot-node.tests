/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package eu.cessda.pilotnode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ARGO Uptime Monitor.
 *
 * <p>Queries the ARGO Monitoring API and writes {@code argo_uptime_report.json}
 * to {@code <dashboardDir>/<nodeName>/argo_uptime_report.json}.
 *
 * <p>Usage:
 * <pre>
 *   CheckServiceUptime NODE_NAME API_KEY [START_DATE] [END_DATE] [dashboard_dir]
 * </pre>
 *
 * <ul>
 *   <li>{@code NODE_NAME}     – node name used for the output subdirectory (required)</li>
 *   <li>{@code API_KEY}       – API key for ARGO authentication (required)</li>
 *   <li>{@code START_DATE}    – {@code YYYY-MM-DD} (optional, defaults to 30 days ago)</li>
 *   <li>{@code END_DATE}      – {@code YYYY-MM-DD} (optional, defaults to today)</li>
 *   <li>{@code dashboard_dir} – path to dashboard data directory
 *                               (optional, defaults to {@code ../dashboard/data})</li>
 * </ul>
 */
public class CheckServiceUptime {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String API_BASE =
            "https://api.devel.mon.argo.grnet.gr/api/v2/results/CORE/SERVICEGROUPS";

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        if (args.length < 1 || args[0].isBlank()) {
            printUsage();
            System.exit(1);
        }
        if (args.length < 2 || args[1].isBlank()) {
            System.err.println("Error: API_KEY is required");
            printUsage();
            System.exit(1);
        }

        String nodeName = args[0];
        String apiKey   = args[1];

        LocalDate startDate = args.length >= 3
                ? parseDate(args[2])
                : LocalDate.now().minusDays(30);
        LocalDate endDate = args.length >= 4
                ? parseDate(args[3])
                : LocalDate.now();

        if (!startDate.isBefore(endDate)) {
            System.err.println("Error: Start date (" + startDate +
                    ") must be before end date (" + endDate + ")");
            System.exit(1);
        }

        Path dashboardDir = Path.of(args.length >= 5 ? args[4] : "../dashboard/data");

        new CheckServiceUptime(nodeName, apiKey, startDate, endDate, dashboardDir).run();
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String    nodeName;
    private final String    apiKey;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Path      dashboardDir;
    private final HttpClient http;
    private final ObjectMapper mapper;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CheckServiceUptime(String nodeName, String apiKey,
                              LocalDate startDate, LocalDate endDate, Path dashboardDir) {
        this.nodeName     = nodeName;
        this.apiKey       = apiKey;
        this.startDate    = startDate;
        this.endDate      = endDate;
        this.dashboardDir = dashboardDir;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    // ── Main logic ────────────────────────────────────────────────────────────

    public void run() throws Exception {

        // ── Resolve output path ───────────────────────────────────────────────

        Path outputDir  = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("argo_uptime_report.json");

        // ── Build API URL ─────────────────────────────────────────────────────

        String startTime = startDate + "T00:00:00Z";
        String endTime   = endDate   + "T23:59:59Z";
        String apiUrl    = API_BASE +
                "?start_time=" + startTime +
                "&end_time="   + endTime;

        // ── Banner ────────────────────────────────────────────────────────────

        System.out.println("==========================================");
        System.out.println("ARGO Monitoring - Uptime Report");
        System.out.println("==========================================");
        System.out.println();
        System.out.println("Node:   " + nodeName);
        System.out.println("Period: " + startTime + " to " + endTime);
        System.out.println();
        System.out.println("Fetching data from API...");

        // ── Fetch data ────────────────────────────────────────────────────────

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept",    "application/json")
                .header("x-api-key", apiKey)
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("Error: API request failed with HTTP status code " +
                    response.statusCode());
            System.err.println(response.body());
            System.exit(1);
        }

        System.out.println("Data retrieved successfully!");
        System.out.println();

        // ── Parse response ────────────────────────────────────────────────────

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            System.err.println("Error: API response is not valid JSON");
            System.err.println(response.body());
            System.exit(1);
            return;
        }

        JsonNode firstResult = root.path("results").path(0);
        String projectName = firstResult.path("name").asText();

        System.out.println("Results:");
        System.out.println("==========================================");
        System.out.println("Project: " + projectName);
        System.out.println();

        // ── Process endpoints ─────────────────────────────────────────────────

        ArrayNode endpointsOut = mapper.createArrayNode();

        JsonNode endpoints = firstResult.path("endpoints");
        if (endpoints.isArray()) {
            for (JsonNode endpoint : endpoints) {

                String endpointName = endpoint.path("name").asText();
                String endpointType = endpoint.path("type").asText();

                // Accumulate uptime / availability / reliability across daily results
                double totalUptime       = 0;
                double totalAvailability = 0;
                double totalReliability  = 0;
                int    totalDays         = 0;

                JsonNode results = endpoint.path("results");
                if (results.isArray()) {
                    for (JsonNode day : results) {
                        totalUptime       += day.path("uptime").asDouble(0);
                        totalAvailability += day.path("availability").asDouble(0);
                        totalReliability  += day.path("reliability").asDouble(0);
                        totalDays++;
                    }
                }

                double uptimePct  = 0;
                double avgAvail   = 0;
                double avgRel     = 0;

                if (totalDays > 0) {
                    uptimePct = (totalUptime / totalDays) * 100;
                    avgAvail  = totalAvailability / totalDays;
                    avgRel    = totalReliability  / totalDays;
                }

                System.out.println("Endpoint: " + endpointName);
                System.out.printf("  Uptime:           %.2f%%%n", uptimePct);
                System.out.printf("  Availability:     %.2f%%%n", avgAvail);
                System.out.printf("  Reliability:      %.2f%%%n", avgRel);
                System.out.println("  Days monitored:   " + totalDays);
                System.out.println();

                ObjectNode endpointOut = mapper.createObjectNode();
                endpointOut.put("name",                 endpointName);
                endpointOut.put("type",                 endpointType);
                endpointOut.put("uptime_percentage",    round2(uptimePct));
                endpointOut.put("average_availability", round2(avgAvail));
                endpointOut.put("average_reliability",  round2(avgRel));
                endpointOut.put("days_monitored",       totalDays);
                endpointsOut.add(endpointOut);
            }
        }

        // ── Write JSON report ─────────────────────────────────────────────────

        ObjectNode report = mapper.createObjectNode();
        report.put("generated",  nowIso());
        report.put("api_source", apiUrl);
        ObjectNode period = report.putObject("period");
        period.put("start", startTime);
        period.put("end",   endTime);
        report.put("project",   projectName);
        report.set("endpoints", endpointsOut);

        Files.writeString(reportFile,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));

        // ── Footer ────────────────────────────────────────────────────────────

        System.out.println("==========================================");
        System.out.println("Report complete");
        System.out.println();
        System.out.println("  JSON: " + reportFile);
        System.out.println("==========================================");
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            System.err.println("Error: Invalid date format '" + s + "'. Expected YYYY-MM-DD");
            System.exit(1);
            return null; // unreachable
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String nowIso() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static void printUsage() {
        System.err.println("Usage: CheckServiceUptime NODE_NAME API_KEY [START_DATE] [END_DATE] [dashboard_dir]");
        System.err.println();
        System.err.println("Arguments:");
        System.err.println("  NODE_NAME     - Node name used for the output directory (required)");
        System.err.println("  API_KEY       - API key for ARGO authentication (required)");
        System.err.println("  START_DATE    - Start date in YYYY-MM-DD format (optional, defaults to 30 days ago)");
        System.err.println("  END_DATE      - End date in YYYY-MM-DD format (optional, defaults to today)");
        System.err.println("  dashboard_dir - Path to dashboard data directory (optional, defaults to ../dashboard/data)");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  CheckServiceUptime CESSDA my-api-key");
        System.err.println("  CheckServiceUptime CESSDA my-api-key 2026-02-01");
        System.err.println("  CheckServiceUptime CESSDA my-api-key 2026-02-01 2026-03-17");
        System.err.println("  CheckServiceUptime CESSDA my-api-key 2026-02-01 2026-03-17 /path/to/dashboard/data");
    }
}