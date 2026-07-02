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
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private static final String FALLBACK_BASE_URL =
            "https://providers.sandbox.eosc-beyond.eu";

    private static final Logger log =
            Logger.getLogger(CheckCatalogueServices.class.getName());

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String BLUE   = "\033[0;34m";
    private static final String NC     = "\033[0m";

    public static void main(String[] args) throws Exception {

        // ── Argument parsing ──────────────────────────────────────────
        if (args.length < 1) {
            log.warning("Error: NODE_NAME is required");
            log.warning("Usage: java CheckCatalogueServices <NODE_NAME>"
                    + " [<api_base_url>] [<quantity>] [<dashboard_dir>]");
            throw new RuntimeException(
                    "Failed to fetch Catalogue Services data");
        }

        String nodeName     = args[0];
        // 2nd argument: the node's Resource Catalogue endpoint URL.
        // Any trailing "/api" or "/api/" is stripped before
        // "/api/service/all" is appended, so the raw endpoint value
        // from endpoint_report.json can be passed directly.
        String apiBaseUrl   = args.length >= 2 && !args[1].isBlank()
                ? args[1]
                : FALLBACK_BASE_URL;
        int    quantity     = args.length >= 3
                ? Integer.parseInt(args[2]) : 10;
        String dashboardDir = args.length >= 4
                ? args[3] : "../dashboard/data";
        // 5th argument: node PID from endpoint_report.json, used as a
        // fallback keyword if both primary and secondary URLs fail.
        String nodePid      = args.length >= 5 && !args[4].isBlank()
                ? args[4] : null;

        // Validate that nodeName contains no directory elements
        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() == 1
                && !nodeNamePath.isAbsolute()) {
            run(Path.of(dashboardDir), nodeName, nodePid,
                    apiBaseUrl, quantity);
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
    static String buildApiServiceUrl(String url) {
        String base = url;
        if (base.endsWith("/api/")) {
            base = base.substring(0, base.length() - 5);
        } else if (base.endsWith("/api")) {
            base = base.substring(0, base.length() - 4);
        }
        // Remove any remaining trailing slash before appending.
        base = base.stripTrailing();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/service/all";
    }

    public static void run(
            Path dashboardDir,
            String nodeName,
            String nodePid,
            String apiBaseUrl,
            int quantity)
            throws IOException, InterruptedException {

        // ── Output paths ──────────────────────────────────────────────
        Path outputDir      = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFileJson =
                outputDir.resolve("catalogue_services_report.json");

        // ── Build primary API URL ─────────────────────────────────────
        String serviceBase = buildApiServiceUrl(apiBaseUrl);
        String apiUrl = "%s?keyword=%s&from=0&quantity=%d&order=asc"
                .formatted(serviceBase, nodeName, quantity);

        // ── Header ────────────────────────────────────────────────────
        separator();
        log.info("Service Catalogue Resource Availability Report");
        separator();
        log.info("Generated : " + Instant.now());
        log.info("Node Name : " + nodeName);
        log.info("API Source: " + apiUrl);
        separator();

        // ── Fetch data ────────────────────────────────────────────────
        log.info(BLUE + "Fetching service data from API..." + NC);

        HttpClient httpClient = trustAllHttpClient();

        String jsonData = fetchData(httpClient, apiUrl);

        // ── Fallback on HTTP 4xx / 5xx ────────────────────────────────
        if (jsonData == null) {
            String fallbackServiceBase =
                    buildApiServiceUrl(FALLBACK_BASE_URL);
            String fallbackUrl =
                    "%s?keyword=%s&from=0&quantity=%d&order=asc"
                    .formatted(fallbackServiceBase, nodeName, quantity);
            log.warning(YELLOW
                    + "Primary URL returned an error status."
                    + " Retrying with fallback URL: "
                    + fallbackUrl + NC);
            jsonData = fetchData(httpClient, fallbackUrl);
            if (jsonData != null) {
                apiUrl = fallbackUrl;
                log.info("Fallback data retrieved successfully.");
            }
        }

        // ── Second fallback: use node_pid as keyword ──────────────────
        if (jsonData == null) {
            if (nodePid == null || nodePid.isBlank()) {
                throw new RuntimeException(
                        "Failed to fetch Catalogue Services data from"
                        + " both primary and fallback URLs, and no"
                        + " node_pid is available for a further retry");
            }
            String encodedPid = URLEncoder.encode(
                    nodePid, StandardCharsets.UTF_8);
            String fallbackServiceBase =
                    buildApiServiceUrl(FALLBACK_BASE_URL);
            String pidUrl =
                    "%s?keyword=%s&from=0&quantity=%d&order=asc"
                    .formatted(fallbackServiceBase, encodedPid, quantity);
            log.warning(YELLOW
                    + "Fallback URL also returned an error status."
                    + " Retrying with node_pid as keyword: "
                    + pidUrl + NC);
            jsonData = fetchData(httpClient, pidUrl);
            if (jsonData == null) {
                throw new RuntimeException(
                        "Failed to fetch Catalogue Services data from"
                        + " primary URL, fallback URL, and fallback URL"
                        + " with node_pid keyword");
            }
            apiUrl = pidUrl;
            log.info("Data retrieved successfully using node_pid"
                    + " as keyword.");
        }

        log.info("=== JSON RESPONSE (first 500 characters) ===");
        log.info(jsonData.substring(0, Math.min(500, jsonData.length())));
        log.info("============================================");

        // ── Parse JSON ────────────────────────────────────────────────
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(jsonData);
        } catch (IOException e) {
            log.warning(RED + "ERROR: Response is not valid JSON: "
                    + e.getMessage() + NC);
            throw new RuntimeException(
                    "Failed to fetch Catalogue Services data");
        }

        if (root.has("error")) {
            log.info(YELLOW
                    + "WARNING: API response contains an 'error' field."
                    + NC);
        }

        long total = root.path("total").asLong(0);
        log.info("Total services found: " + total);

        // ── Check each service webpage ────────────────────────────────
        log.info("Checking service webpages...");

        JsonNode results = root.path("results");
        List<ObjectNode> serviceResults = new ArrayList<>();

        for (JsonNode service : results) {
            String name = service.path("name").asText();
            String webpage =
                    service.path("webpage").isMissingNode()
                    || service.path("webpage").isNull()
                    ? null
                    : service.path("webpage").asText().trim();
            String serviceId  = service.path("id").asText();
            String abbreviation =
                    service.path("abbreviation").isMissingNode()
                    || service.path("abbreviation").isNull()
                    ? null
                    : service.path("abbreviation").asText();

            String  status;
            Integer httpCode;
            String  colour;

            if (webpage == null || webpage.isEmpty()) {
                status   = "No webpage defined";
                httpCode = null;
                colour   = YELLOW;
            } else {
                try {
                    var url = new URI(webpage);
                    httpCode = checkWebpage(httpClient, url);

                    if (httpCode == 404) {
                        status = "Not found";
                        colour = YELLOW;
                    } else if (httpCode >= 200 && httpCode < 400) {
                        status = "Available";
                        colour = GREEN;
                    } else {
                        status = "Not available";
                        colour = RED;
                    }
                } catch (URISyntaxException e) {
                    status = "Webpage has an invalid URL: "
                            + e.getMessage();
                    httpCode = null;
                    colour   = YELLOW;
                }
            }

            // Console output
            System.out.printf("%-50s %s%s%s%n",
                    name, colour, status, NC);

            // Build JSON entry
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name",         name);
            entry.put("abbreviation",
                    abbreviation != null ? abbreviation : "");
            entry.put("service_id",   serviceId);
            if (webpage != null) entry.put("webpage", webpage);
            else                 entry.putNull("webpage");
            entry.put("status",       status);
            if (httpCode != null) entry.put("http_code", httpCode);
            else                  entry.putNull("http_code");

            serviceResults.add(entry);
        }

        // ── Write JSON report ─────────────────────────────────────────
        ObjectNode report = mapper.createObjectNode();
        report.put("generated",      Instant.now().toString());
        report.put("node_name",      nodeName);
        report.put("api_source",     apiUrl);
        report.put("total_services", total);

        ArrayNode servicesArray = mapper.createArrayNode();
        serviceResults.forEach(servicesArray::add);
        report.set("services", servicesArray);

        mapper.writerWithDefaultPrettyPrinter()
              .writeValue(reportFileJson.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────
        separator();
        log.info("Report generated:");
        log.info("  JSON: " + reportFileJson.toAbsolutePath());
        separator();
    }

    /**
     * Builds an {@link HttpClient} that accepts all TLS certificates.
     *
     * <p>This is intentional: pilot node catalogue endpoints may be
     * signed by CAs not present in the default JVM trust store (e.g.
     * GEANT/IGTF CAs common in research infrastructure). This tool is
     * a monitoring client, not a security-sensitive data processor, so
     * bypassing certificate validation is an acceptable trade-off.</p>
     */
    private static HttpClient trustAllHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    public void checkClientTrusted(
                            X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(
                            X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, null);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslContext)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            // Should never happen with a standard JVM
            throw new IllegalStateException(
                    "Failed to create trust-all SSLContext", e);
        }
    }

    /**
     * Fetches JSON data from {@code url}. Returns the response body if
     * the HTTP status is below 400, or {@code null} if the status is
     * 400 or above or if the request fails with an I/O error.
     */
    private static String fetchData(HttpClient httpClient, String url)
            throws InterruptedException {
        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> apiResponse;
        try {
            apiResponse = httpClient.send(
                    apiRequest,
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warning(RED + "ERROR: HTTP request failed: "
                    + e.getMessage() + NC);
            return null;
        }

        int status = apiResponse.statusCode();
        if (status >= 400) {
            log.warning(RED + "ERROR: API returned HTTP " + status + NC);
            return null;
        }

        String body = apiResponse.body();
        if (body == null || body.isBlank()) {
            log.warning(RED + "ERROR: No data received from API (HTTP "
                    + status + ")" + NC);
            return null;
        }

        log.info("Data retrieved successfully! (HTTP " + status + ")");
        return body;
    }

    /**
     * Issues an HTTP HEAD request to the given URL and returns the
     * HTTP status code, or throws if the request cannot be completed.
     */
    private static int checkWebpage(HttpClient client, URI url)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> resp =
                client.send(req, HttpResponse.BodyHandlers.discarding());
        return resp.statusCode();
    }

    private static void separator() {
        log.info("======================================");
    }
}