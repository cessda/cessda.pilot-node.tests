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
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

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
    private static final Pattern FIRST_COMPONENT_SPLIT_PATTERN =
            Pattern.compile("[\\s-]+");

    private static final Logger log = Logger.getLogger(CheckServiceUptime.class.getName());

    // ── Instance variables ────────────────────────────────────────────────────

    private final HttpUtils http;
    private final ObjectMapper mapper;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CheckServiceUptime(HttpUtils http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

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

        LocalDate startDate;
        startDate = args.length > index ? LocalDate.parse(args[index]) : LocalDate.now().minusDays(6);
        LocalDate endDate;
        endDate = args.length > index + 1 ? LocalDate.parse(args[index + 1]) : LocalDate.now();

        if (!startDate.isBefore(endDate)) {
            System.err.println("Error: Start date (" + startDate + ") must be before end date (" + endDate + ")");
            System.exit(-1);
        }

        Path dashboardDir = Path.of(args.length > index + 2 ? args[index + 2] : "../dashboard/data");

        HttpUtils http = new HttpUtils();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(WRITE_DATES_AS_TIMESTAMPS);

        run(nodeName, apiKey, startDate, endDate, dashboardDir, http, objectMapper);
    }

    // ── Main logic ────────────────────────────────────────────────────────────
    @SuppressWarnings("java:S8688")
    public static void run(String nodeName, String apiKey,
                           LocalDate startDate, LocalDate endDate, Path dashboardDir,
                           HttpUtils http, ObjectMapper mapper) throws IOException, InterruptedException {
        var checkServiceUptime = new CheckServiceUptime(http, mapper);
        checkServiceUptime.runInternal(nodeName, apiKey, startDate, endDate, dashboardDir);
    }

    private static String encodeUrlSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Set<String> tenantNameCandidates(String nodeName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String trimmed = nodeName == null ? "" : nodeName.trim();
        if (!trimmed.isEmpty()) {
            candidates.add(trimmed);
            String firstComponent = firstNodeComponent(trimmed);
            if (!firstComponent.isBlank()) {
                candidates.add(firstComponent);
            }
        }
        return candidates;
    }

    private static boolean looksLikeDate(CharSequence value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private void runInternal(String nodeName, String apiKey, LocalDate startDate, LocalDate endDate, Path dashboardDir) throws IOException, InterruptedException {
        // ── Resolve output path ───────────────────────────────────────────────

        Path outputDir  = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("argo_uptime_report.json");
        // ── Build API URL ─────────────────────────────────────────────────────
        // ── Build API URL ─────────────────────────────────────────────────────
        OffsetDateTime startTime = startDate.atTime(OffsetTime.of(LocalTime.MIDNIGHT, ZoneOffset.UTC));
        OffsetDateTime endTime = endDate.atTime(OffsetTime.of(LocalTime.of(23, 59, 59), ZoneOffset.UTC));

        // ── Banner ────────────────────────────────────────────────────────────

        log.log(Level.INFO, """
                        ARGO Service Monitoring - Uptime Report
                        Node:   {0}
                        Period: {1} to {2}""",
                new Object[]{nodeName, startTime, endTime}
        );

        UptimeReportData reportData = resolveReportData(
                new UptimeQuery(nodeName, apiKey, startDate, endDate),
                startTime,
                endTime
        );

        // ── Write JSON report ─────────────────────────────────────────────────

        var report = new Report(
                OffsetDateTime.now(),
                reportData.apiSource(),
                reportData.resolvedDataEndpoint(),
                reportData.dataSource(),
                new Period(startTime, endTime),
                reportData.projectName(),
                reportData.endpoints(),
                reportData.warningMessage()
        );

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────────────

        log.log(Level.INFO, "Report complete [{0}] - JSON: {1}", new Object[]{reportData.dataSource(), reportFile});
    }

    private record UptimeQuery(String nodeName, String apiKey, LocalDate startDate, LocalDate endDate) { }

    private UptimeReportData resolveReportData(UptimeQuery query, OffsetDateTime startTime, OffsetDateTime endTime) throws InterruptedException {
        List<UptimeReportSource> sources = List.of(
                new DashboardSource(),
                new LegacyApiSource()
        );
        List<String> errors = new ArrayList<>();

        for (UptimeReportSource source : sources) {
            try {
                UptimeReportData data = source.fetch(query, startTime, endTime);
                if (data.endpoints().isEmpty()) {
                    log.log(Level.WARNING, "{0} returned no endpoints for node {1}",
                            new Object[]{source.name(), query.nodeName()});
                }
                return data;
            } catch (IOException e) {
                String reason = source.name() + ": " + e.getMessage();
                errors.add(reason);
                log.log(Level.WARNING, reason);
            }
        }

        List<UptimeReportSource.EndpointOut> emptyEndpoints = Collections.emptyList();
        URI dashboardUrl = URI.create(String.format(PUBLIC_DASHBOARD_TEMPLATE, encodeUrlSegment(query.nodeName())));
        String warning = "No uptime data source was available. " + String.join(" | ", errors);
        return new UptimeReportData(query.nodeName(), dashboardUrl, null, "unavailable", emptyEndpoints, warning);
    }

    private interface UptimeReportSource {
        String name();

        UptimeReportData fetch(UptimeQuery query, OffsetDateTime startTime, OffsetDateTime endTime) throws IOException, InterruptedException;

        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        record EndpointOut(
                String name,
                String type,
                Double averageAvailability,
                Double uptimePercentage,
                Double averageReliability,
                int daysMonitored
        ) {
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record Report(
            OffsetDateTime generated,
            URI apiSource,
            URI resolvedDataEndpoint,
            String dataSource,
            Period period,
            String project,
            List<UptimeReportSource.EndpointOut> endpoints,
            String warning
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record Period(
            OffsetDateTime start,
            OffsetDateTime end
    ) {
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

    private record UptimeReportData(String projectName, URI apiSource, URI resolvedDataEndpoint, String dataSource,
                                    List<UptimeReportSource.EndpointOut> endpoints, String warningMessage) {
    }

    private static boolean is4xx(int statusCode) {
        return statusCode >= 400 && statusCode < 500;
    }

    private final class DashboardSource implements UptimeReportSource {

        private static final String NAME = "name";
        private static final String TYPE = "type";

        @Override
        public String name() {
            return "public-dashboard";
        }

        private static URI getScriptUrl(InputStream dashboardResponse, String dashboardUri) throws IOException {
            Document document = Jsoup.parse(dashboardResponse, null, dashboardUri);
            Element scriptElement = document.selectFirst("script[src*=/assets/index-]");
            if (scriptElement == null) {
                scriptElement = document.selectFirst("script[src]");
            }
            if (scriptElement == null) {
                throw new IOException("No script tag found in dashboard page");
            }

            String scriptUrl = scriptElement.absUrl("src");
            if (scriptUrl.isBlank()) {
                throw new IOException("Could not resolve dashboard script URL");
            }

            return URI.create(scriptUrl);
        }

        @Override
        public UptimeReportData fetch(UptimeQuery query, OffsetDateTime startTime, OffsetDateTime endTime)
                throws IOException, InterruptedException {
            IOException lastClientError = null;
            Set<String> candidates = tenantNameCandidates(query.nodeName());
            for (String tenantName : candidates) {
                URI dashboardUri = URI.create(String.format(PUBLIC_DASHBOARD_TEMPLATE, encodeUrlSegment(tenantName)));

                URI scriptUrl;
                try {
                    HttpResponse<InputStream> dashboardResponse = getBodyWithStatus(dashboardUri, "text/html", HttpResponse.BodyHandlers.ofInputStream());
                    scriptUrl = getScriptUrl(dashboardResponse.body(), dashboardUri.toString());
                } catch (HTTPException httpException) {
                    if (is4xx(httpException.getResponse().statusCode())) {
                        lastClientError = new IOException("dashboard not found for tenant '" + tenantName + "'", httpException);
                        continue;
                    } else {
                        throw httpException;
                    }
                }

                HttpResponse<String> bundleJsResponse = getBodyWithStatus(scriptUrl, "text/javascript", HttpResponse.BodyHandlers.ofString());
                String bundleJs = bundleJsResponse.body();
                String publicApiBase = extractPublicApiBase(bundleJs).orElse(DEFAULT_PUBLIC_API_BASE);

                URI groupsUri = URI.create(publicApiBase + "/v1/public/tenants/"
                        + encodeUrlSegment(tenantName)
                        + "/results/groups?start_time=" + startTime
                        + "&end_time=" + endTime);

                HttpResponse<InputStream> groupsResponse;
                try {
                    groupsResponse = getBodyWithStatus(groupsUri, "application/json", HttpResponse.BodyHandlers.ofInputStream());
                } catch (HTTPException httpException) {
                    if (is4xx(httpException.getResponse().statusCode())) {
                        lastClientError = new IOException("public results not found for tenant '" + tenantName + "'", httpException);
                        continue;
                    }
                    throw httpException;
                }

                JsonNode root = mapper.readTree(groupsResponse.body());
                JsonNode groups = root.path("data");
                if (!groups.isArray()) {
                    throw new IOException("Unexpected groups payload shape");
                }

                ArrayList<EndpointOut> endpointsOut = new ArrayList<>();
                for (JsonNode group : groups) {
                    EndpointOut endpointOut = parseGroup(group);
                    endpointsOut.add(endpointOut);
                }

                String warning = endpointsOut.isEmpty()
                        ? "Public dashboard was reachable but returned no service uptime values."
                        : null;
                return new UptimeReportData(
                        query.nodeName(),
                        dashboardUri,
                        groupsUri,
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

        private EndpointOut parseGroup(JsonNode group) {
            String endpointName = group.path(NAME).asText("(unnamed)");
            String type = group.path(TYPE).asText("SERVICEGROUP");

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


            Double avgAvailability = null;
            Double uptimePercentage = null;
            Double averageReliability = null;

            if (availabilityDays > 0) {
                avgAvailability = round2(availabilityTotal / availabilityDays);
                uptimePercentage = avgAvailability;
            }

            if (uptimePercentage == null && uptimeDays > 0) {
                uptimePercentage = round2(uptimeTotal / uptimeDays);
            }

            if (reliabilityDays > 0) {
                averageReliability = round2(reliabilityTotal / reliabilityDays);
            }

            int daysMonitored = Math.max(uptimeDays, Math.max(availabilityDays, reliabilityDays));

            return new EndpointOut(
                    endpointName,
                    type,
                    avgAvailability,
                    uptimePercentage,
                    averageReliability,
                    daysMonitored
            );
        }

        private <T> HttpResponse<T> getBodyWithStatus(URI uri, String acceptHeader, HttpResponse.BodyHandler<T> bodyHandler)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", acceptHeader)
                    .GET()
                    .build();
            return http.send(request, bodyHandler);
        }
    }

    private static String firstNodeComponent(String nodeName) {
        String[] parts = FIRST_COMPONENT_SPLIT_PATTERN.split(nodeName, 2);
        return parts.length > 0 ? parts[0].trim() : nodeName.trim();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private final class LegacyApiSource implements UptimeReportSource {
        @Override
        public String name() {
            return "legacy-argo-api";
        }

        @Override
        public UptimeReportData fetch(UptimeQuery query, OffsetDateTime startTime, OffsetDateTime endTime)
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

            JsonNode root;
            try (InputStream body = response.body()) {
                root = mapper.readTree(body);
            }
            JsonNode firstResult = root.path("results").path(0);
            if (firstResult.isMissingNode()) {
                throw new IOException("legacy API returned no results");
            }

            String projectName = firstResult.path("name").asText(query.nodeName());
            ArrayList<EndpointOut> endpointsOut = new ArrayList<>();
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

                    double uptimePercentage = totalDays > 0 ? round2((totalUptime / totalDays) * 100) : 0;
                    double averageAvailability = totalDays > 0 ? round2(totalAvailability / totalDays) : 0;
                    double averageReliability = totalDays > 0 ? round2(totalReliability / totalDays) : 0;

                    EndpointOut endpointOut = new EndpointOut(
                            endpointName,
                            endpointType,
                            averageAvailability,
                            uptimePercentage,
                            averageReliability,
                            totalDays
                    );

                    endpointsOut.add(endpointOut);
                }
            }

            return new UptimeReportData(
                    projectName,
                    apiUrl,
                    apiUrl,
                    "legacy-api",
                    endpointsOut,
                    null
            );
        }
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