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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ARGO Uptime Monitor.
 *
 * <p>Queries the ARGO Service Monitoring API and writes {@code argo_uptime_report.json}
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

    private static final URI API_BASE =
            URI.create("https://api.devel.mon.argo.grnet.gr/api/v2/results/CORE/SERVICEGROUPS");


    private static final Logger log = Logger.getLogger(CheckServiceUptime.class.getName());
    // ── Entry point ───────────────────────────────────────────────────────────

    @SuppressWarnings({"java:S106", "java:S8688"})
    public static void main(String[] args) throws IOException, InterruptedException {

        if (args.length < 1 || args[0].isBlank()) {
            printUsage();
            System.exit(-1);
        }
        if (args.length < 2 || args[1].isBlank()) {
            System.err.println("api-key is required");
            printUsage();
            System.exit(-1);
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
            System.err.println("Error: Start date (" + startDate + ") must be before end date (" + endDate + ")");
            System.exit(-1);
        }

        Path dashboardDir = Path.of(args.length >= 5 ? args[4] : "../dashboard/data");

        HttpClient http = HttpUtils.trustAllHttpClient();

        var objectMapper = new ObjectMapper();

        run(nodeName, apiKey, startDate, endDate, dashboardDir, http, objectMapper);
    }

    // ── Main logic ────────────────────────────────────────────────────────────
    @SuppressWarnings("java:S8688")
    public static void run(String nodeName, String apiKey,
                           LocalDate startDate, LocalDate endDate, Path dashboardDir,
                           HttpClient http, ObjectMapper mapper) throws IOException, InterruptedException {

        // ── Resolve output path ───────────────────────────────────────────────

        Path outputDir  = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("argo_uptime_report.json");

        // ── Build API URL ─────────────────────────────────────────────────────

        String startTime = startDate + "T00:00:00Z";
        String endTime   = endDate   + "T23:59:59Z";
        URI apiUrl = URI.create(API_BASE + "?start_time=" + startTime + "&end_time=" + endTime);

        // ── Banner ────────────────────────────────────────────────────────────

        log.log(Level.INFO, """
                        ARGO Service Monitoring - Uptime Report
                        Node:   {0}
                        Period: {1} to {2}""",
                new Object[]{nodeName, startTime, endTime}
        );

        log.info("Fetching data from API...");

        // ── Fetch data ────────────────────────────────────────────────────────

        HttpRequest request = HttpRequest.newBuilder()
                .uri(apiUrl)
                .header("Accept",    "application/json")
                .header("x-api-key", apiKey)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to fetch Service Uptime data (HTTP " + response.statusCode() + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        log.info("Data retrieved successfully!");
        

        // ── Parse response ────────────────────────────────────────────────────

        JsonNode root;
        try (InputStream body = response.body()) {
            root = mapper.readTree(body);
        }

        JsonNode firstResult = root.path("results").path(0);
        String projectName = firstResult.path("name").asText();

        log.log(Level.INFO, "Project: {0}", projectName);
        

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

                log.log(Level.INFO, """
                                Endpoint:         {0}
                                Uptime:           {1}
                                Availability:     {2}
                                Reliability:      {3}
                                Days monitored:   {4}""",
                        new Object[]{endpointName, round2(uptimePct), round2(avgAvail), round2(avgRel), totalDays}
                );
                

                ObjectNode endpointOut = mapper.createObjectNode();
                endpointOut.put("name",                 endpointName);
                endpointOut.put("type",                 endpointType);
                endpointOut.put("uptime_percentage", uptimePct);
                endpointOut.put("average_availability", avgAvail);
                endpointOut.put("average_reliability", avgRel);
                endpointOut.put("days_monitored",       totalDays);
                endpointsOut.add(endpointOut);
            }
        }

        // ── Write JSON report ─────────────────────────────────────────────────

        ObjectNode report = mapper.createObjectNode();
        report.put("generated", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        report.put("api_source", apiUrl.toString());
        ObjectNode period = report.putObject("period");
        period.put("start", startTime);
        period.put("end",   endTime);
        report.put("project",   projectName);
        report.set("endpoints", endpointsOut);

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────────────

        log.log(Level.INFO, "Report complete - JSON: {0}", reportFile);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static LocalDate parseDate(String s) {
        return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @SuppressWarnings("java:S106")
    private static void printUsage() {
        System.err.println("""
                usage: CheckServiceUptime <node-name> <api-key> [<start-date>] [<end-date>] [<dashboard-dir>]
                ======================================
                Arguments:
                  node-name     - Node name used for the output directory (required)
                  api-key       - API key for ARGO authentication (required)
                  start-date    - Start date in YYYY-MM-DD format (optional, defaults to 30 days ago)
                  end-date      - End date in YYYY-MM-DD format (optional, defaults to today)
                  dashboard-dir - Path to dashboard data directory (optional, defaults to ../dashboard/data)
                ======================================
                Examples:
                  CheckServiceUptime CESSDA my-api-key
                  CheckServiceUptime CESSDA my-api-key 2026-02-01
                  CheckServiceUptime CESSDA my-api-key 2026-02-01 2026-03-17
                  CheckServiceUptime CESSDA my-api-key 2026-02-01 2026-03-17 /path/to/dashboard/data""");
    }
}