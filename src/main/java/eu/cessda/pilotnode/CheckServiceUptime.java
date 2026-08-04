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
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ARGO Uptime Monitor.
 *
 * <p>Builds {@code argo_uptime_report.json} for a node using a pluggable source strategy.
 * The current default is scraping the public ARGO status dashboard and extracting data
 * from its referenced JS/API payloads. A legacy API source is kept as fallback.
 *
 * <p>Usage:
 * <pre>
 *   CheckServiceUptime NODE_NAME [API_KEY] [START_DATE] [END_DATE] [dashboard_dir]
 * </pre>
 *
 * <ul>
 *   <li>{@code NODE_NAME}     – node name used for the output subdirectory (required)</li>
 *   <li>{@code API_KEY}       – API key for legacy ARGO API fallback (optional)</li>
 *   <li>{@code START_DATE}    – {@code YYYY-MM-DD} (optional, defaults to 6 days ago)</li>
 *   <li>{@code END_DATE}      – {@code YYYY-MM-DD} (optional, defaults to today)</li>
 *   <li>{@code dashboard_dir} – path to dashboard data directory
 *                               (optional, defaults to {@code ../dashboard/data})</li>
 * </ul>
 */
public class CheckServiceUptime {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final URI API_BASE =
            URI.create("https://api.devel.mon.argo.grnet.gr/api/v2/results/CORE/SERVICEGROUPS");

    private static final String PUBLIC_DASHBOARD_TEMPLATE =
            "https://status.devel.mon.argo.grnet.gr/public/tenants/%s/dashboard";
    private static final String DEFAULT_PUBLIC_API_BASE = "https://api-status.devel.mon.argo.grnet.gr";
    private static final Pattern API_BASE_PATTERN =
            Pattern.compile("https://api-status[\\w.-]*\\.grnet\\.gr");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern FIRST_COMPONENT_SPLIT_PATTERN =
            Pattern.compile("[\\s-]+");

    private static final Logger log = Logger.getLogger(CheckServiceUptime.class.getName());
    // ── Entry point ───────────────────────────────────────────────────────────

    @SuppressWarnings({"java:S106", "java:S8688"})
    public static void main(String[] args) throws IOException, InterruptedException {

        if (args.length < 1 || args[0].isBlank()) {
            printUsage();
            System.exit(-1);
        }

        String nodeName = args[0];
        int index = 1;
        String apiKey = "";
        if (args.length >= 2 && !looksLikeDate(args[1])) {
            apiKey = args[1];
            index = 2;
        }

        LocalDate startDate = args.length > index
                ? parseDate(args[index])
                : LocalDate.now().minusDays(6);
        LocalDate endDate = args.length > index + 1
                ? parseDate(args[index + 1])
                : LocalDate.now();

        if (!startDate.isBefore(endDate)) {
            System.err.println("Error: Start date (" + startDate + ") must be before end date (" + endDate + ")");
            System.exit(-1);
        }

        Path dashboardDir = Path.of(args.length > index + 2 ? args[index + 2] : "../dashboard/data");

        HttpClient http = HttpUtils.httpClient();

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
        // ── Build API URL ─────────────────────────────────────────────────────
        String startTime = startDate + "T00:00:00Z";
        String endTime   = endDate   + "T23:59:59Z";
      
        // ── Banner ────────────────────────────────────────────────────────────

        log.log(Level.INFO, """
                        ARGO Service Monitoring - Uptime Report
                        Node:   {0}
                        Period: {1} to {2}""",
                new Object[]{nodeName, startTime, endTime}
        );

        UptimeReportData reportData = resolveReportData(
                new UptimeQuery(nodeName, apiKey, startDate, endDate),
                http,
                mapper,
                startTime,
                endTime
        );

        // ── Write JSON report ─────────────────────────────────────────────────

        ObjectNode report = mapper.createObjectNode();
        report.put("generated", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        report.put("api_source", reportData.apiSource());
        if (reportData.resolvedDataEndpoint() != null && !reportData.resolvedDataEndpoint().isBlank()) {
            report.put("resolved_data_endpoint", reportData.resolvedDataEndpoint());
        }
        report.put("data_source", reportData.dataSource());
        ObjectNode period = report.putObject("period");
        period.put("start", startTime);
        period.put("end",   endTime);
        report.put("project", reportData.projectName());
        report.set("endpoints", reportData.endpoints());
        if (reportData.warningMessage() != null && !reportData.warningMessage().isBlank()) {
            report.put("warning", reportData.warningMessage());
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────────────

        log.log(Level.INFO, "Report complete [{0}] - JSON: {1}", new Object[]{reportData.dataSource(), reportFile});
    }

    private static UptimeReportData resolveReportData(UptimeQuery query, HttpClient http, ObjectMapper mapper,
                                                      String startTime, String endTime)
            throws IOException, InterruptedException {
        List<UptimeReportSource> sources = List.of(
                new DashboardSource(),
                new LegacyApiSource()
        );
        List<String> errors = new ArrayList<>();

        for (UptimeReportSource source : sources) {
            try {
                UptimeReportData data = source.fetch(query, http, mapper, startTime, endTime);
                if (data.endpoints().isEmpty()) {
                    log.log(Level.WARNING, "{0} returned no endpoints for node {1}",
                            new Object[]{source.name(), query.nodeName()});
                }
                return data;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (IOException | RuntimeException e) {
                String reason = source.name() + ": " + e.getMessage();
                errors.add(reason);
                log.log(Level.WARNING, reason);
            }
        }

        ArrayNode emptyEndpoints = mapper.createArrayNode();
        String dashboardUrl = String.format(PUBLIC_DASHBOARD_TEMPLATE, encodePathSegment(query.nodeName()));
        String warning = "No uptime data source was available. " + String.join(" | ", errors);
        return new UptimeReportData(query.nodeName(), dashboardUrl, null, "unavailable", emptyEndpoints, warning);
    }

    private record UptimeQuery(String nodeName, String apiKey, LocalDate startDate, LocalDate endDate) { }

    private record UptimeReportData(String projectName, String apiSource, String resolvedDataEndpoint, String dataSource,
                                    ArrayNode endpoints, String warningMessage) { }

    private interface UptimeReportSource {
        String name();

        UptimeReportData fetch(UptimeQuery query, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
                throws IOException, InterruptedException;
    }

    private static final class DashboardSource implements UptimeReportSource {
        @Override
        public String name() {
            return "public-dashboard";
        }

        @Override
        public UptimeReportData fetch(UptimeQuery query, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
                throws IOException, InterruptedException {
            IOException lastClientError = null;
            for (String tenantName : tenantNameCandidates(query.nodeName())) {
                URI dashboardUri = URI.create(String.format(PUBLIC_DASHBOARD_TEMPLATE, encodePathSegment(tenantName)));
                HttpTextResponse dashboardResponse = getBodyWithStatus(http, dashboardUri, "text/html");
                if (dashboardResponse.statusCode() != 200) {
                    if (is4xx(dashboardResponse.statusCode())) {
                        lastClientError = new IOException("dashboard not found for tenant '" + tenantName
                                + "' (HTTP " + dashboardResponse.statusCode() + ")");
                        continue;
                    }
                    throw new IOException("request to " + dashboardUri + " failed with HTTP " + dashboardResponse.statusCode());
                }

                Document document = Jsoup.parse(dashboardResponse.body(), dashboardUri.toString());
                Element scriptElement = document.selectFirst("script[src*=/assets/index-]");
                if (scriptElement == null) {
                    scriptElement = document.selectFirst("script[src]");
                }
                if (scriptElement == null) {
                    throw new IOException("No script tag found in dashboard page");
                }

                String scriptUrl = scriptElement.absUrl("src");
                if (scriptUrl == null || scriptUrl.isBlank()) {
                    throw new IOException("Could not resolve dashboard script URL");
                }

                String bundleJs = getBody(http, URI.create(scriptUrl), "text/javascript");
                String publicApiBase = extractPublicApiBase(bundleJs).orElse(DEFAULT_PUBLIC_API_BASE);

                URI groupsUri = URI.create(publicApiBase + "/v1/public/tenants/"
                        + encodePathSegment(tenantName)
                        + "/results/groups?start_time=" + encodeQueryValue(startTime)
                        + "&end_time=" + encodeQueryValue(endTime));
                HttpTextResponse groupsResponse = getBodyWithStatus(http, groupsUri, "application/json");
                if (groupsResponse.statusCode() != 200) {
                    if (is4xx(groupsResponse.statusCode())) {
                        lastClientError = new IOException("public results not found for tenant '" + tenantName
                                + "' (HTTP " + groupsResponse.statusCode() + ")");
                        continue;
                    }
                    throw new IOException("request to " + groupsUri + " failed with HTTP " + groupsResponse.statusCode());
                }

                JsonNode root = mapper.readTree(groupsResponse.body());
                JsonNode groups = root.path("data");
                if (!groups.isArray()) {
                    throw new IOException("Unexpected groups payload shape");
                }

                ArrayNode endpointsOut = mapper.createArrayNode();
                for (JsonNode group : groups) {
                    String endpointName = group.path("name").asText("(unnamed)");
                    JsonNode results = group.path("results");

                    double uptimeTotal = 0.0;
                    int uptimeDays = 0;
                    double availabilityTotal = 0.0;
                    int availabilityDays = 0;
                    double reliabilityTotal = 0.0;
                    int reliabilityDays = 0;

                    if (results.isArray()) {
                        for (JsonNode day : results) {
                            Double uptime = parseMetric(day.path("uptime"));
                            if (uptime != null) {
                                uptimeTotal += normalizeUptime(uptime);
                                uptimeDays++;
                            }
                            Double availability = parseMetric(day.path("availability"));
                            if (availability != null) {
                                availabilityTotal += availability;
                                availabilityDays++;
                            }
                            Double reliability = parseMetric(day.path("reliability"));
                            if (reliability != null) {
                                reliabilityTotal += reliability;
                                reliabilityDays++;
                            }
                        }
                    }

                    ObjectNode endpointOut = mapper.createObjectNode();
                    endpointOut.put("name", endpointName);
                    endpointOut.put("type", group.path("type").asText("SERVICEGROUP"));
                    Double uptimePercentage = null;
                    if (availabilityDays > 0) {
                        double avgAvailability = round2(availabilityTotal / availabilityDays);
                        endpointOut.put("average_availability", avgAvailability);
                        uptimePercentage = avgAvailability;
                    } else {
                        endpointOut.putNull("average_availability");
                    }
                    if (uptimePercentage == null && uptimeDays > 0) {
                        uptimePercentage = round2(uptimeTotal / uptimeDays);
                    }
                    if (uptimePercentage != null) {
                        endpointOut.put("uptime_percentage", uptimePercentage);
                    } else {
                        endpointOut.putNull("uptime_percentage");
                    }
                    if (reliabilityDays > 0) {
                        endpointOut.put("average_reliability", round2(reliabilityTotal / reliabilityDays));
                    } else {
                        endpointOut.putNull("average_reliability");
                    }
                    endpointOut.put("days_monitored", Math.max(uptimeDays, Math.max(availabilityDays, reliabilityDays)));
                    endpointsOut.add(endpointOut);
                }

                String warning = endpointsOut.isEmpty()
                        ? "Public dashboard was reachable but returned no service uptime values."
                        : null;
                return new UptimeReportData(
                        query.nodeName(),
                        dashboardUri.toString(),
                        groupsUri.toString(),
                        "dashboard-public-results",
                        endpointsOut,
                        warning
                );
            }

            if (lastClientError != null) {
                throw lastClientError;
            }
            throw new IOException("No tenant name candidate could be resolved for public dashboard");
        }
    }

    private static final class LegacyApiSource implements UptimeReportSource {
        @Override
        public String name() {
            return "legacy-argo-api";
        }

        @Override
        public UptimeReportData fetch(UptimeQuery query, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
                throws IOException, InterruptedException {
            if (query.apiKey() == null || query.apiKey().isBlank()) {
                throw new IOException("legacy API key is missing");
            }

            URI apiUrl = URI.create(API_BASE + "?start_time=" + startTime + "&end_time=" + endTime);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(apiUrl)
                    .header("Accept", "application/json")
                    .header("x-api-key", query.apiKey())
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("failed to fetch uptime data (HTTP " + response.statusCode() + ")");
            }

            JsonNode root;
            try (InputStream body = response.body()) {
                root = mapper.readTree(body);
            }
            JsonNode firstResult = root.path("results").path(0);
            if (firstResult.isMissingNode()) {
                throw new IOException("legacy API returned no results");
            }

            String projectName = firstResult.path("name").asText(query.nodeName());
            ArrayNode endpointsOut = mapper.createArrayNode();
            JsonNode endpoints = firstResult.path("endpoints");
            if (endpoints.isArray()) {
                for (JsonNode endpoint : endpoints) {
                    String endpointName = endpoint.path("name").asText("(unnamed)");
                    String endpointType = endpoint.path("type").asText("SERVICEGROUP");

                    double totalUptime = 0;
                    double totalAvailability = 0;
                    double totalReliability = 0;
                    int totalDays = 0;

                    JsonNode results = endpoint.path("results");
                    if (results.isArray()) {
                        for (JsonNode day : results) {
                            totalUptime += day.path("uptime").asDouble(0);
                            totalAvailability += day.path("availability").asDouble(0);
                            totalReliability += day.path("reliability").asDouble(0);
                            totalDays++;
                        }
                    }

                    ObjectNode endpointOut = mapper.createObjectNode();
                    endpointOut.put("name", endpointName);
                    endpointOut.put("type", endpointType);
                    endpointOut.put("uptime_percentage", totalDays > 0 ? round2((totalUptime / totalDays) * 100) : 0);
                    endpointOut.put("average_availability", totalDays > 0 ? round2(totalAvailability / totalDays) : 0);
                    endpointOut.put("average_reliability", totalDays > 0 ? round2(totalReliability / totalDays) : 0);
                    endpointOut.put("days_monitored", totalDays);
                    endpointsOut.add(endpointOut);
                }
            }

            return new UptimeReportData(
                    projectName,
                    apiUrl.toString(),
                    apiUrl.toString(),
                    "legacy-api",
                    endpointsOut,
                    null
            );
        }
    }

    private static String getBody(HttpClient http, URI uri, String acceptHeader) throws IOException, InterruptedException {
        HttpTextResponse response = getBodyWithStatus(http, uri, acceptHeader);
        if (response.statusCode() != 200) {
            throw new IOException("request to " + uri + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    private record HttpTextResponse(int statusCode, String body) { }

    private static HttpTextResponse getBodyWithStatus(HttpClient http, URI uri, String acceptHeader)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", acceptHeader)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpTextResponse(response.statusCode(), response.body());
    }

    private static Optional<String> extractPublicApiBase(String scriptText) {
        Matcher matcher = API_BASE_PATTERN.matcher(scriptText);
        if (matcher.find()) {
            return Optional.of(matcher.group());
        }
        return Optional.empty();
    }

    private static Double parseMetric(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static double normalizeUptime(double uptime) {
        return uptime <= 1.0 ? uptime * 100.0 : uptime;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean is4xx(int statusCode) {
        return statusCode >= 400 && statusCode < 500;
    }

    private static List<String> tenantNameCandidates(String nodeName) {
        Set<String> candidates = new LinkedHashSet<>();
        String trimmed = nodeName == null ? "" : nodeName.trim();
        if (!trimmed.isEmpty()) {
            candidates.add(trimmed);
            String firstComponent = firstNodeComponent(trimmed);
            if (!firstComponent.isBlank()) {
                candidates.add(firstComponent);
            }
        }
        return List.copyOf(candidates);
    }

    private static String firstNodeComponent(String nodeName) {
        String[] parts = FIRST_COMPONENT_SPLIT_PATTERN.split(nodeName, 2);
        return parts.length > 0 ? parts[0].trim() : nodeName.trim();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static LocalDate parseDate(String s) {
        return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static boolean looksLikeDate(String value) {
        return DATE_PATTERN.matcher(value).matches();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @SuppressWarnings("java:S106")
    private static void printUsage() {
        System.err.println("""
                usage: CheckServiceUptime <node-name> [<api-key>] [<start-date>] [<end-date>] [<dashboard-dir>]
                ======================================
                Arguments:
                  node-name     - Node name used for the output directory (required)
                  api-key       - API key for legacy ARGO API fallback (optional)
                  start-date    - Start date in YYYY-MM-DD format (optional, defaults to 6 days ago)
                  end-date      - End date in YYYY-MM-DD format (optional, defaults to today)
                  dashboard-dir - Path to dashboard data directory (optional, defaults to ../dashboard/data)
                ======================================
                Examples:
                  CheckServiceUptime CESSDA
                  CheckServiceUptime CESSDA my-api-key
                  CheckServiceUptime CESSDA 2026-02-01 2026-03-17
                  CheckServiceUptime CESSDA my-api-key 2026-02-01 2026-03-17 /path/to/dashboard/data""");
    }
}