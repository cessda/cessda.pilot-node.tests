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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 *                 Should be the endpoint value for capability_type "Resource Catalogue"
 *                 from that node's endpoint_report.json, with "/api/service/all" appended.
 *   quantity:     Maximum number of services to retrieve (default: 10)
 *   dashboard_dir: Path to the dashboard data directory (default: ../dashboard/data)
 *                 Output is written to <dashboard_dir>/<NODE_NAME>/catalogue_services_report.json
 */
public class CheckCatalogueServices {

    /** Fallback API base URL used when no api_base_url argument is supplied. */
    private static final URI DEFAULT_API_BASE_URL =
            URI.create("https://service-catalogue-staging.beyond.cessda.eu/api/service/all");

    private static final Logger log = Logger.getLogger(CheckCatalogueServices.class.getName());

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String NC     = "\033[0m";

    public static void main(String[] args) throws IOException, URISyntaxException {

        // ── Argument parsing ──────────────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Error: NODE_NAME is required");
            System.err.println("Usage: java CheckCatalogueServices <NODE_NAME> [<api_base_url>] [<quantity>] [<dashboard_dir>]");
            System.exit(-1);
        }

        String nodeName     = args[0];
        // 2nd argument: the node's Resource Catalogue API base URL.
        // The dashboard passes the "Resource Catalogue" endpoint from endpoint_report.json
        // with "/api/service/all" appended. Falls back to the CESSDA staging URL.
        URI apiBaseUrl = args.length >= 2 && !args[1].isBlank()
                ? new URI(args[1])
                : DEFAULT_API_BASE_URL;
        int    quantity     = args.length >= 3 ? Integer.parseInt(args[2]) : 10;
        String dashboardDir = args.length >= 4 ? args[3] : "../dashboard/data";


        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        ObjectMapper objectMapper = new ObjectMapper();

        // Validate that nodeName contains no directory elements
        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() == 1 && !nodeNamePath.isAbsolute()) {
            run(Path.of(dashboardDir), nodeName, apiBaseUrl, quantity, httpClient, objectMapper);
        } else {
            throw new IllegalArgumentException("nodeName must be a file name");
        }
    }

    public static void run(Path dashboardDir, String nodeName, URI apiBaseUrl, int quantity, HttpClient httpClient, ObjectMapper mapper) throws IOException, URISyntaxException {


        // ── Output paths ──────────────────────────────────────────────────────
        Path outputDir      = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFileJson = outputDir.resolve("catalogue_services_report.json");

        // ── Build API URL ─────────────────────────────────────────────────────
        URI apiUrl = new URI(apiBaseUrl + "?keyword=" + nodeName + "&from=0&quantity=" + quantity + "&order=asc");

        // ── Header ────────────────────────────────────────────────────────────
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

        // ── Check each service webpage ────────────────────────────────────────
        log.info("Checking service webpages...");

        ArrayNode servicesArray = mapper.createArrayNode();

        JsonNode results = root.path("results");
        for (JsonNode service : results) {
            String name         = service.path("name").asText();
            String webpage = service.path("webpage").asText();
            String serviceId    = service.path("id").asText();
            String abbreviation = service.path("abbreviation").asText();

            String status;
            Integer httpCode;
            String colour;

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
                    status = "Webpage has a invalid URL: " + e.getMessage();
                    httpCode = null;
                    colour = YELLOW;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Console output
            System.out.printf("%-50s %s%s%s%n", name, colour, status, NC);

            // Build JSON entry
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", name);
            entry.put("abbreviation", abbreviation);
            entry.put("service_id", serviceId);
            if (!webpage.isEmpty()) {
                entry.put("webpage", webpage);
            } else {
                entry.putNull("webpage");
            }
            entry.put("status", status);
            if (httpCode != null) {
                entry.put("http_code", httpCode);
            } else {
                entry.putNull("http_code");
            }

            servicesArray.add(entry);
        }

        // ── Write JSON report ─────────────────────────────────────────────────
        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name", nodeName);
        report.put("api_source", apiUrl.toString());
        report.put("total_services", total);
        report.set("services", servicesArray);

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFileJson.toFile(), report);

        // ── Footer ────────────────────────────────────────────────────────────

        log.log(Level.INFO, "Report generated: JSON: {0}", reportFileJson.toAbsolutePath());
    }

    /**
     * Issues an HTTP HEAD request to the given URL and returns the HTTP status code
     */
    private static int checkWebpage(HttpClient client, URI url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

        return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}