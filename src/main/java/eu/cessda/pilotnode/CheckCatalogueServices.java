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
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service Catalogue Resource Checker
 *
 * Reads JSON from API and checks availability of each resource webpage.
 *
 * Usage: java CheckCatalogueServices NODE_NAME [api_base_url] [quantity] [dashboard_dir]
 *   NODE_NAME:    Node name to use as keyword filter (required)
 *   api_base_url: Base URL of the node's Resource Catalogue API
 *                 (default: the CESSDA staging URL below)
 *                 Should be the endpoint value for capability_type
 *                 "Resource Catalogue" from that node's
 *                 endpoint_report.json. Any trailing "/api" or "/api/"
 *                 is stripped before "/api/service/all" is appended.
 *   quantity:     Maximum number of services to retrieve (default: 10)
 *   dashboard_dir: Path to the dashboard data directory
 *                 (default: ../dashboard/data)
 *                 Output is written to
 *                 <dashboard_dir>/<NODE_NAME>/catalogue_services_report.json
 */
public class CheckCatalogueServices {

    /**
     * Fallback API base URL used when no api_base_url argument is
     * supplied or when the primary API returns HTTP 400 or above.
     */
    private static final URI FALLBACK_BASE_URL =
            URI.create("https://providers.sandbox.eosc-beyond.eu");

    // ── Metric 13 thresholds ─────────────────────────────────────────────
    // From the Proposed Validation Metrics doc's Test Methodology /
    // Success Criteria for Exchange Services - Machine Actionable Tests.
    // A service exceeding RESPONSE_TIME_THRESHOLD_MS fails its own check;
    // RESPONSE_TIME_TARGET_MS is the doc's target for the *average* across
    // all services, reported but not itself pass/fail per service.
    private static final long RESPONSE_TIME_THRESHOLD_MS = 30_000L;
    private static final long RESPONSE_TIME_TARGET_MS    = 5_000L;

    private static final Logger log =
            Logger.getLogger(CheckCatalogueServices.class.getName());

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String NC     = "\033[0m";

    public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {

        // ── Argument parsing ──────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Error: NODE_NAME is required");
            System.err.println("Usage: java CheckCatalogueServices <NODE_NAME>"
                    + " [<api_base_url>] [<quantity>] [<dashboard_dir>]");
            System.exit(-1);
        }

        String nodeName     = args[0];
        // 2nd argument: the node's Resource Catalogue endpoint URL.
        // Any trailing "/api" or "/api/" is stripped before
        // "/api/service/all" is appended, so the raw endpoint value
        // from endpoint_report.json can be passed directly.
        URI apiBaseUrl = args.length >= 2 && !args[1].isBlank()
                ? new URI(args[1])
                : FALLBACK_BASE_URL;
        int    quantity     = args.length >= 3
                ? Integer.parseInt(args[2]) : 10;
        String dashboardDir = args.length >= 4
                ? args[3] : "../dashboard/data";
        // 5th argument: node PID from endpoint_report.json, used as a
        // fallback keyword if both primary and secondary URLs fail.
        String nodePid      = args.length >= 5 && !args[4].isBlank()
                ? args[4] : null;

        HttpUtils httpUtils = new HttpUtils();

        ObjectMapper objectMapper = new ObjectMapper();

        // Validate that nodeName contains no directory elements
        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() == 1 && !nodeNamePath.isAbsolute()) {
            run(Path.of(dashboardDir), nodeName, nodePid, apiBaseUrl, quantity, httpUtils, objectMapper);
        } else {
            throw new IllegalArgumentException(
                    "nodeName must be a file name");
        }
    }

    /**
     * Strips a trailing {@code /api} or {@code /api/} from
     * {@code url}, then appends {@code /api/service/all}, ensuring
     * exactly one {@code /} between the base and the path.
     */
    static URI buildApiServiceUrl(URI url) {
        String base = url.toString();
        if (url.toString().endsWith("/api/")) {
            base = base.substring(0, base.length() - 5);
        } else if (base.endsWith("/api")) {
            base = base.substring(0, base.length() - 4);
        }
        // Remove any remaining trailing slash before appending.
        base = base.stripTrailing();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/api/service/all");
    }

    public static void run(
            Path dashboardDir,
            String nodeName,
            String nodePid,
            URI apiBaseUrl,
            int quantity,
            HttpUtils httpClient,
            ObjectMapper mapper)
            throws IOException, URISyntaxException, InterruptedException {

        // ── Output paths ──────────────────────────────────────────────
        Path outputDir      = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFileJson =
                outputDir.resolve("catalogue_services_report.json");

        // ── Build primary API URL ─────────────────────────────────────

        // When calling the node's own Resource Catalogue endpoint the
        // API returns all services for that node without filtering.
        // Filter arguments (keyword, quantity, order) are only needed
        // when falling back to FALLBACK_BASE_URL, which aggregates
        // services across multiple nodes.
        URI apiUrl = buildApiServiceUrl(apiBaseUrl);

        // ── Header ────────────────────────────────────────────────────
        log.log(Level.INFO, """
                        Service Catalogue Resource Availability Report
                        Generated : {0}
                        Node Name : {1}
                        API Source: {2}""",
                new Object[]{Instant.now(), nodeName, apiUrl}
        );

        // ── Fetch data ────────────────────────────────────────────────────────
        log.info("Fetching service data from API");

        InputStream apiResponse;
        try {
            apiResponse = fetchData(httpClient, apiUrl);
        } catch (IOException e) {

            URI fallbackServiceBase = buildApiServiceUrl(FALLBACK_BASE_URL);
            URI fallbackUrl = buildFallbackUrl(fallbackServiceBase, nodeName, quantity);

            log.log(Level.WARNING, "Primary URL returned an error status. Retrying with fallback URL: {0}", fallbackUrl);

            try {
                apiResponse = fetchData(httpClient, fallbackUrl);
                apiUrl = fallbackUrl;
            } catch (IOException fallbackException) {
                fallbackException.addSuppressed(e);
                if (nodePid == null || nodePid.isBlank()) {
                    throw new IOException(
                            "Failed to fetch Catalogue Services data from"
                                    + " both primary and fallback URLs, and no"
                                    + " node_pid is available for a further retry", fallbackException);
                }

                URI pidUrl = buildFallbackUrl(fallbackServiceBase, nodePid, quantity);
                log.warning("Fallback URL also returned an error status. Retrying with node_pid as keyword: " + pidUrl);
                try {
                    apiResponse = fetchData(httpClient, pidUrl);
                } catch (IOException nodePidException) {
                    nodePidException.addSuppressed(fallbackException);
                    throw new IOException(
                            "Failed to fetch Catalogue Services data from"
                                    + " primary URL, fallback URL, and fallback URL"
                                    + " with node_pid keyword", nodePidException);
                }
                apiUrl = pidUrl;
                log.info("Data retrieved successfully using node_pid as keyword.");
            }
        }

        // ── Parse JSON ────────────────────────────────────────────────────────
        JsonNode root;
        try (InputStream jsonData = apiResponse) {
            root = mapper.readTree(jsonData);
        }

        if (root.has("error")) {
            log.log(Level.WARNING, "API response contains an 'error' field.");
        }

        long total = root.path("total").asLong(0);
        log.log(Level.INFO, "Total services found: {0}", total);
        // ── Check each service webpage ────────────────────────────────
        // Metric 13 (Proposed Validation Metrics doc): automated
        // validation of each Exchange Service — HTTP status, response
        // content-type/validity appropriate to the service, and response
        // time against the doc's 30s per-service / 5s average targets.
        log.info("Checking service webpages...");

        ArrayNode servicesArray = mapper.createArrayNode();
        long   healthyCount        = 0;
        long   responseTimeSampleN = 0;
        double responseTimeSumMs   = 0;

        JsonNode results = root.path("results");
        for (JsonNode service : results) {
            String name         = service.path("name").asText();
            String webpage = service.path("webpage").asText();
            String serviceId    = service.path("id").asText();
            String abbreviation = service.path("abbreviation").asText();

            String  status;
            Integer httpCode;
            String  colour;
            String  contentType   = null;
            Boolean contentValid  = null;
            Long    responseTimeMs = null;

            if (webpage.isEmpty()) {
                status   = "No webpage defined";
                httpCode = null;
                colour   = YELLOW;
            } else {
                try {
                    var url = new URI(webpage);
                    WebpageCheckResult check = checkWebpage(httpClient, url);
                    httpCode       = check.httpCode();
                    contentType    = check.contentType();
                    contentValid   = check.contentValid();
                    responseTimeMs = check.responseTimeMs();

                    if (responseTimeMs != null) {
                        responseTimeSumMs += responseTimeMs;
                        responseTimeSampleN++;
                    }

                    if (httpCode == 404) {
                        status = "Not found";
                        colour = YELLOW;
                    } else if (httpCode >= 200 && httpCode < 400) {
                        if (Boolean.FALSE.equals(contentValid)) {
                            status = "Available (content check failed)";
                            colour = YELLOW;
                        } else if (responseTimeMs != null && responseTimeMs > RESPONSE_TIME_THRESHOLD_MS) {
                            status = "Available (slow: " + responseTimeMs + "ms)";
                            colour = YELLOW;
                        } else {
                            status = "Available";
                            colour = GREEN;
                            healthyCount++;
                        }
                    } else {
                        status = "Not available";
                        colour = RED;
                    }
                } catch (URISyntaxException e) {
                    status = "Webpage has an invalid URL: "
                            + e.getMessage();
                    httpCode = null;
                    colour   = YELLOW;
                } catch (IOException e) {
                    status   = "Not available: " + e.getMessage();
                    httpCode = null;
                    colour   = RED;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Console output
            System.out.printf("%-50s %s%s%s%n",
                    name, colour, status, NC);

            // Build JSON entry
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", name);
            entry.put("abbreviation", abbreviation);
            entry.put("service_id", serviceId);
            if (!webpage.isEmpty()) {
                entry.put("webpage", webpage);
            } else                 {
                entry.putNull("webpage");
            }
            entry.put("status", status);
            if (httpCode != null) {
                entry.put("http_code", httpCode);
            } else                  {
                entry.putNull("http_code");
            }
            if (contentType != null) {
                entry.put("content_type", contentType);
            } else {
                entry.putNull("content_type");
            }
            if (contentValid != null) {
                entry.put("content_valid", contentValid);
            } else {
                entry.putNull("content_valid");
            }
            if (responseTimeMs != null) {
                entry.put("response_time_ms", responseTimeMs);
            } else {
                entry.putNull("response_time_ms");
            }

            servicesArray.add(entry);
        }

        // ── Write JSON report ─────────────────────────────────────────
        long   checkedServices    = servicesArray.size();
        long   pctHealthy         = checkedServices > 0
                ? Math.round(healthyCount * 100.0 / checkedServices) : 0;
        Long   avgResponseTimeMs  = responseTimeSampleN > 0
                ? Math.round(responseTimeSumMs / responseTimeSampleN) : null;

        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name",      nodeName);
        report.put("api_source", apiUrl.toString());
        report.put("total_services", total);
        report.put("healthy_services", healthyCount);
        report.put("pct_healthy", pctHealthy);
        if (avgResponseTimeMs != null) {
            report.put("avg_response_time_ms", avgResponseTimeMs);
        } else {
            report.putNull("avg_response_time_ms");
        }
        report.put("response_time_target_ms", RESPONSE_TIME_TARGET_MS);
        report.put("response_time_threshold_ms", RESPONSE_TIME_THRESHOLD_MS);
        report.set("services", servicesArray);

        mapper.writerWithDefaultPrettyPrinter()
              .writeValue(reportFileJson.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────
        log.log(Level.INFO, "Report generated: JSON: {0}", reportFileJson.toAbsolutePath());
    }



    /**
     * Issues an HTTP HEAD request to the given URL and returns the HTTP status code
     */
    private static InputStream fetchData(HttpUtils httpUtils, URI url) throws IOException, InterruptedException {
        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(url)
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> apiResponse = httpUtils.send(
                apiRequest,
                HttpResponse.BodyHandlers.ofInputStream());

        int status = apiResponse.statusCode();
        if (status != 200) {
            throw new IOException("HTTP " + apiResponse.statusCode());
        }

        return apiResponse.body();
    }

    private static URI buildFallbackUrl(URI fallbackServiceBase, String keyword, int quantity) {
        String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return URI.create(fallbackServiceBase + "?keyword=" + encodedKeyword
                + "&from=0&quantity=" + quantity + "&order=asc");
    }

    /**
     * Result of a single Metric 13 webpage/endpoint check.
     */
    private record WebpageCheckResult(
            int httpCode, String contentType, boolean contentValid, long responseTimeMs) {
    }

    /**
     * Issues an HTTP GET request to {@code url} and evaluates it against
     * Metric 13's per-service success criteria: HTTP status, response
     * content-type, and content validity appropriate to that type.
     *
     * <p>The doc's test methodology distinguishes REST API Services
     * (validate content-type is JSON and the body is non-empty/parses)
     * from Web Services (detect service-specific keywords in HTML). This
     * class has no per-service "expected keyword" metadata to check
     * against — the Resource Catalogue's service listing doesn't carry
     * one — so the Web Service branch is a non-empty-body check rather
     * than a keyword match. If the Catalogue starts exposing expected
     * keywords per service, tighten {@code isContentValid} accordingly.</p>
     *
     * <p>A response exceeding {@link #RESPONSE_TIME_THRESHOLD_MS} throws
     * {@link IOException} via the client's request timeout, which the
     * caller treats as "Not available".</p>
     */
    private static WebpageCheckResult checkWebpage(HttpUtils client, URI url)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url)
                .GET()
                .timeout(Duration.ofMillis(RESPONSE_TIME_THRESHOLD_MS))
                .build();

        Instant start = Instant.now();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        long responseTimeMs = Duration.between(start, Instant.now()).toMillis();

        int httpCode = response.statusCode();
        String contentType = response.headers().firstValue("content-type").orElse("");
        boolean contentValid = isContentValid(contentType, response.body());

        return new WebpageCheckResult(httpCode, contentType, contentValid, responseTimeMs);
    }

    /**
     * REST API Services: content-type must indicate JSON and the body
     * must parse to a non-empty JSON value. Web Services (anything else):
     * body must simply be non-blank. See {@link #checkWebpage} Javadoc
     * for why this doesn't do per-service keyword matching.
     */
    private static boolean isContentValid(String contentType, String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lowerType = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (lowerType.contains("json")) {
            try {
                JsonNode parsed = new ObjectMapper().readTree(body);
                return !(parsed.isContainerNode() && parsed.isEmpty());
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}