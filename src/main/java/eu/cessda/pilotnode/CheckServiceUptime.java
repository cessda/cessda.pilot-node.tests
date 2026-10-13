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
import java.net.URLDecoder;
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
import java.util.List;
import java.util.Optional;
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
 * The current default calls the public ARGO capability-monitoring metrics API directly
 * ({@code GET /v1/public/nodes/{NODE}/capabilities/monitoring/metrics}) for monthly-granularity
 * availability/reliability/uptime figures. If that API is unavailable, this falls back to
 * scraping the public ARGO status dashboard and extracting data from its referenced JS/API
 * payloads, and finally to a legacy API source (requires an API key).
 *
 * <p>Both the capability-metrics API and the dashboard scrape need an ARGO tenant name for
 * the node being checked, resolved in this order (see
 * {@link #monitoringTenantFromEndpointReport} and {@link #appendNodeNameFallbacks}):
 * <ol>
 *   <li>The tenant declared by the node's own {@code Monitoring} capability in
 *       {@code endpoint_report.json} (authoritative — extracted from a
 *       {@code .../tenants/<TENANT>/...} URL; tried alone if present).</li>
 *   <li>Otherwise, the node name as-is.</li>
 *   <li>If that specifically 404s and the node name is hyphenated, the substring before
 *       the first hyphen (e.g. {@code LifeWatch-ERIC} &rarr; {@code LifeWatch}).</li>
 *   <li>If still unresolved (any 4xx) and the node name contains whitespace, its first
 *       whitespace-separated token (e.g. {@code EGI Pilot Node} &rarr; {@code EGI}).</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   CheckServiceUptime NODE_NAME [API_KEY] [START_DATE] [END_DATE] [dashboard_dir]
 * </pre>
 *
 * <ul>
 *   <li>{@code NODE_NAME}     - node name used for the output subdirectory (required)</li>
 *   <li>{@code API_KEY}       - API key for legacy ARGO API fallback (optional)</li>
 *   <li>{@code START_DATE}    - {@code YYYY-MM-DD} (optional, defaults to 1 month ago)</li>
 *   <li>{@code END_DATE}      - {@code YYYY-MM-DD} (optional, defaults to today)</li>
 *   <li>{@code dashboard_dir} - path to dashboard data directory
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
    private static final String CAPABILITY_METRICS_PATH_TEMPLATE =
            "/v1/public/nodes/%s/capabilities/monitoring/metrics";
    private static final String GRANULARITY = "monthly";
    private static final Pattern API_BASE_PATTERN =
            Pattern.compile("https://api-status[\\w.-]*\\.grnet\\.gr");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TENANT_FROM_URL_PATTERN =
            Pattern.compile("/tenants/([^/?#]+)");
    private static final Pattern WHITESPACE_PATTERN =
            Pattern.compile("\\s");

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
                : LocalDate.now().minusMonths(1);
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
                dashboardDir,
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

    private static UptimeReportData resolveReportData(UptimeQuery query, Path dashboardDir, HttpClient http, ObjectMapper mapper,
                                                      String startTime, String endTime)
            throws IOException, InterruptedException {
        List<UptimeReportSource> sources = List.of(
                new CapabilityApiSource(),
                new DashboardSource(),
                new LegacyApiSource()
        );
        List<String> errors = new ArrayList<>();

        for (UptimeReportSource source : sources) {
            try {
                UptimeReportData data = source.fetch(query, dashboardDir, http, mapper, startTime, endTime);
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

        UptimeReportData fetch(UptimeQuery query, Path dashboardDir, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
                throws IOException, InterruptedException;
    }

    /**
     * Calls the public ARGO capability-monitoring metrics API directly:
     * {@code GET /v1/public/nodes/{NODE}/capabilities/monitoring/metrics
     * ?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD&granularity=monthly}.
     *
     * <p>This is the preferred source — it needs no HTML/JS scraping, just the
     * node (tenant) name. The response shape is
     * {@code {"data":[{"name":"...","results":[{"date":...,"availability":...,
     * "reliability":...,"uptime":...}]}]}}; each service's {@code results} entries
     * are averaged the same way as the dashboard source.</p>
     */
    private static final class CapabilityApiSource implements UptimeReportSource {
        @Override
        public String name() {
            return "capability-metrics-api";
        }

        @Override
        public UptimeReportData fetch(UptimeQuery query, Path dashboardDir, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
                throws IOException, InterruptedException {
            String monitoringTenant = monitoringTenantFromEndpointReport(dashboardDir, query.nodeName(), mapper);
            List<String> tenantsToTry = new ArrayList<>();
            tenantsToTry.add(monitoringTenant != null ? monitoringTenant : query.nodeName());

            IOException lastClientError = null;
            for (int i = 0; i < tenantsToTry.size(); i++) {
                String tenantName = tenantsToTry.get(i);
                URI metricsUri = URI.create(DEFAULT_PUBLIC_API_BASE
                        + String.format(CAPABILITY_METRICS_PATH_TEMPLATE, encodePathSegment(tenantName))
                        + "?start_date=" + encodeQueryValue(query.startDate().toString())
                        + "&end_date=" + encodeQueryValue(query.endDate().toString())
                        + "&granularity=" + GRANULARITY);
                HttpTextResponse response = getBodyWithStatus(http, metricsUri, "application/json");
                if (response.statusCode() != 200) {
                    if (is4xx(response.statusCode())) {
                        lastClientError = new IOException("capability metrics not found for node '" + tenantName
                                + "' (HTTP " + response.statusCode() + ")");
                        appendNodeNameFallbacks(tenantsToTry, tenantName, response.statusCode(), monitoringTenant != null);
                        continue;
                    }
                    throw new IOException("request to " + metricsUri + " failed with HTTP " + response.statusCode());
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode services = root.path("data");
                if (!services.isArray()) {
                    throw new IOException("Unexpected capability metrics payload shape");
                }

                ArrayNode endpointsOut = mapper.createArrayNode();
                for (JsonNode service : services) {
                    String serviceName = service.path("name").asText("(unnamed)");
                    endpointsOut.add(summariseResults(mapper, serviceName, "SERVICE", service.path("results")));
                }

                String warning = endpointsOut.isEmpty()
                        ? "Capability metrics API was reachable but returned no services."
                        : null;
                return new UptimeReportData(
                        query.nodeName(),
                        metricsUri.toString(),
                        metricsUri.toString(),
                        "capability-metrics-api",
                        endpointsOut,
                        warning
                );
            }

            if (lastClientError != null) {
                throw lastClientError;
            }
            throw new IOException("No tenant name candidate could be resolved for capability metrics API");
        }
    }

    private static final class DashboardSource implements UptimeReportSource {
        @Override
        public String name() {
            return "public-dashboard";
        }

        @Override
        public UptimeReportData fetch(UptimeQuery query, Path dashboardDir, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
                throws IOException, InterruptedException {
            String monitoringTenant = monitoringTenantFromEndpointReport(dashboardDir, query.nodeName(), mapper);
            List<String> tenantsToTry = new ArrayList<>();
            tenantsToTry.add(monitoringTenant != null ? monitoringTenant : query.nodeName());

            IOException lastClientError = null;
            for (int i = 0; i < tenantsToTry.size(); i++) {
                String tenantName = tenantsToTry.get(i);
                URI dashboardUri = URI.create(String.format(PUBLIC_DASHBOARD_TEMPLATE, encodePathSegment(tenantName)));
                HttpTextResponse dashboardResponse = getBodyWithStatus(http, dashboardUri, "text/html");
                if (dashboardResponse.statusCode() != 200) {
                    if (is4xx(dashboardResponse.statusCode())) {
                        lastClientError = new IOException("dashboard not found for tenant '" + tenantName
                                + "' (HTTP " + dashboardResponse.statusCode() + ")");
                        appendNodeNameFallbacks(tenantsToTry, tenantName, dashboardResponse.statusCode(), monitoringTenant != null);
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
                        appendNodeNameFallbacks(tenantsToTry, tenantName, groupsResponse.statusCode(), monitoringTenant != null);
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
                    String endpointType = group.path("type").asText("SERVICEGROUP");
                    endpointsOut.add(summariseResults(mapper, endpointName, endpointType, group.path("results")));
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
        public UptimeReportData fetch(UptimeQuery query, Path dashboardDir, HttpClient http, ObjectMapper mapper, String startTime, String endTime)
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

    /**
     * Averages a service's daily/monthly {@code results} entries (each carrying
     * {@code uptime}, {@code availability}, {@code reliability}) into the single
     * endpoint summary object used in {@code argo_uptime_report.json}. Shared by
     * every source, since the capability-metrics API and the dashboard's
     * {@code results/groups} API return the same {@code name} + {@code results[]}
     * shape.
     */
    private static ObjectNode summariseResults(ObjectMapper mapper, String name, String type, JsonNode results) {
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
        endpointOut.put("name", name);
        endpointOut.put("type", type);
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
        return endpointOut;
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

    /**
     * Reads {@code <dashboardDir>/<nodeName>/endpoint_report.json} (written earlier by
     * {@code CheckNodeCapabilities}) and, if it has a {@code Monitoring} capability with
     * a {@code .../tenants/<TENANT>/...} endpoint URL, returns the URL-decoded
     * {@code <TENANT>} segment. Returns {@code null} — meaning "phase 1 is empty" to the
     * caller, which should then resolve a tenant from the node name instead — if the
     * report is missing or unreadable, there's no {@code Monitoring} capability, its
     * endpoint is blank, or that endpoint doesn't contain a recognisable
     * {@code /tenants/<name>} segment.
     */
    private static String monitoringTenantFromEndpointReport(Path dashboardDir, String nodeName, ObjectMapper mapper) {
        Path reportPath = dashboardDir.resolve(nodeName).resolve("endpoint_report.json");
        if (!Files.isRegularFile(reportPath)) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(reportPath.toFile());
            for (JsonNode cap : root.path("capabilities")) {
                if (!"Monitoring".equals(cap.path("capability_type").asText())) {
                    continue;
                }
                String endpoint = cap.path("endpoint").asText("");
                if (endpoint.isBlank()) {
                    return null;
                }
                Matcher matcher = TENANT_FROM_URL_PATTERN.matcher(endpoint);
                if (matcher.find()) {
                    return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
                }
                log.log(Level.WARNING,
                        "Monitoring capability endpoint for {0} has no /tenants/<name> segment: {1}",
                        new Object[]{nodeName, endpoint});
                return null;
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read {0} to resolve a Monitoring tenant: {1}",
                    new Object[]{reportPath, e.getMessage()});
        }
        return null;
    }

    /**
     * If {@code tenantName} was resolved from the node name rather than a declared
     * Monitoring capability ({@code derivedFromMonitoring} is {@code false} — an
     * authoritative Monitoring tenant is tried alone, with no further fallback), appends
     * the next fallback candidate(s) for {@code tenantsToTry} to try, if not already
     * present:
     * <ul>
     *   <li>on a strict 404, the substring before the first hyphen in {@code tenantName}
     *       (e.g. {@code LifeWatch-ERIC} &rarr; {@code LifeWatch});</li>
     *   <li>on any 4xx, the substring before the first whitespace in {@code tenantName}
     *       (e.g. {@code EGI Pilot Node} &rarr; {@code EGI}) — the pre-existing fallback.</li>
     * </ul>
     */
    private static void appendNodeNameFallbacks(List<String> tenantsToTry, String tenantName,
                                                 int statusCode, boolean derivedFromMonitoring) {
        if (derivedFromMonitoring) {
            return;
        }
        if (statusCode == 404) {
            String hyphenTrimmed = beforeFirstHyphen(tenantName);
            if (hyphenTrimmed != null && !tenantsToTry.contains(hyphenTrimmed)) {
                tenantsToTry.add(hyphenTrimmed);
            }
        }
        String whitespaceTrimmed = beforeFirstWhitespace(tenantName);
        if (whitespaceTrimmed != null && !tenantsToTry.contains(whitespaceTrimmed)) {
            tenantsToTry.add(whitespaceTrimmed);
        }
    }

    /** {@code "LifeWatch-ERIC"} &rarr; {@code "LifeWatch"}; {@code null} if there's no hyphen. */
    private static String beforeFirstHyphen(String name) {
        int idx = name.indexOf('-');
        return idx > 0 ? name.substring(0, idx).trim() : null;
    }

    /** {@code "EGI Pilot Node"} &rarr; {@code "EGI"}; {@code null} if there's no whitespace. */
    private static String beforeFirstWhitespace(String name) {
        Matcher matcher = WHITESPACE_PATTERN.matcher(name);
        return matcher.find() ? name.substring(0, matcher.start()).trim() : null;
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
                  start-date    - Start date in YYYY-MM-DD format (optional, defaults to 1 month ago)
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