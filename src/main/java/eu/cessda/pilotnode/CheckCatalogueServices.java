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
import java.util.logging.Level;
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
    private static final URI DEFAULT_API_BASE_URL =
            URI.create("https://providers.sandbox.eosc-beyond.eu");

    private static final Logger log =
            Logger.getLogger(CheckCatalogueServices.class.getName());

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String NC     = "\033[0m";

    public static void main(String[] args) throws IOException, URISyntaxException {

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


        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        ObjectMapper objectMapper = new ObjectMapper();

        // Validate that nodeName contains no directory elements
        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() == 1
                && !nodeNamePath.isAbsolute()) {
            run(Path.of(dashboardDir), nodeName, nodePid,
                    apiBaseUrl, quantity, httpClient, objectMapper);
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
            HttpClient httpClient,
            ObjectMapper mapper)
            throws IOException, URISyntaxException {

        // ── Output paths ──────────────────────────────────────────────
        Path outputDir      = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFileJson =
                outputDir.resolve("catalogue_services_report.json");

        // ── Build primary API URL ─────────────────────────────────────
        URI serviceBase = buildApiServiceUrl(apiBaseUrl);
        // When calling the node's own Resource Catalogue endpoint the
        // API returns all services for that node without filtering.
        // Filter arguments (keyword, quantity, order) are only needed
        // when falling back to FALLBACK_BASE_URL, which aggregates
        // services across multiple nodes.
        URI apiUrl = serviceBase;

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

        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(apiUrl)
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> apiResponse;
        try {
            apiResponse = httpClient.send(apiRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (apiResponse.statusCode() != 200) {
            String fallbackServiceBase =
                    buildApiServiceUrl(FALLBACK_BASE_URL);
            String fallbackUrl =
                    "%s?keyword=%s&from=0&quantity=%d&order=asc"
                            .formatted(fallbackServiceBase, nodeName, quantity);
            log.warning(YELLOW
                    + "Primary URL returned an error status."
                    + " Retrying with fallback URL: "
                    + fallbackUrl + NC);
            var jsonData = fetchData(httpClient, fallbackUrl);
            if (jsonData != null) {
                apiUrl = fallbackUrl;
                log.info("Fallback data retrieved successfully.");
            }

            if (jsonData == null) {
                if (nodePid == null || nodePid.isBlank()) {
                    throw new IOException(
                            "Failed to fetch Catalogue Services data from"
                                    + " both primary and fallback URLs, and no"
                                    + " node_pid is available for a further retry");
                }
                String encodedPid = URLEncoder.encode(
                        nodePid, StandardCharsets.UTF_8);
                fallbackServiceBase =
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
                    throw new IOException(
                            "Failed to fetch Catalogue Services data from"
                                    + " primary URL, fallback URL, and fallback URL"
                                    + " with node_pid keyword");
                }
                apiUrl = pidUrl;
                log.info("Data retrieved successfully using node_pid"
                        + " as keyword.");
            }


            throw new IOException("Failed to fetch Catalogue Services data (HTTP " + apiResponse.statusCode() + ")");
        }

        // ── Parse JSON ────────────────────────────────────────────────────────
        JsonNode root;
        try (InputStream jsonData = apiResponse.body()) {
            root = mapper.readTree(jsonData);
        }

        if (root.has("error")) {
            log.log(Level.WARNING, "API response contains an 'error' field.");
        }

        long total = root.path("total").asLong(0);
        log.log(Level.INFO, "Total services found: {0}", total);
        // ── Check each service webpage ────────────────────────────────
        log.info("Checking service webpages...");

        ArrayNode servicesArray = mapper.createArrayNode();

        JsonNode results = root.path("results");
        for (JsonNode service : results) {
            String name         = service.path("name").asText();
            String webpage = service.path("webpage").asText();
            String serviceId    = service.path("id").asText();
            String abbreviation = service.path("abbreviation").asText();

            String  status;
            Integer httpCode;
            String  colour;

            if (webpage.isEmpty()) {
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

            servicesArray.add(entry);
        }

        // ── Write JSON report ─────────────────────────────────────────
        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name",      nodeName);
        report.put("api_source", apiUrl.toString());
        report.put("total_services", total);
        report.set("services", servicesArray);

        mapper.writerWithDefaultPrettyPrinter()
              .writeValue(reportFileJson.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────
        log.log(Level.INFO, "Report generated: JSON: {0}", reportFileJson.toAbsolutePath());
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
     * Issues an HTTP HEAD request to the given URL and returns the HTTP status code
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

        return
                client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}