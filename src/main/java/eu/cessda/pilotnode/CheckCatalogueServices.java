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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

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
 *                 Should be the endpoint value for capability_type "Resource Catalogue"
 *                 from that node's endpoint_report.json, with "/api/service/all" appended.
 *   quantity:     Maximum number of services to retrieve (default: 10)
 *   dashboard_dir: Path to the dashboard data directory (default: ../dashboard/data)
 *                 Output is written to <dashboard_dir>/<NODE_NAME>/catalogue_services_report.json
 */
public class CheckCatalogueServices {

    /** Fallback API base URL used when no api_base_url argument is supplied. */
    private static final String DEFAULT_API_BASE_URL =
            "https://service-catalogue-staging.beyond.cessda.eu/api/service/all";

    private static final Logger log = Logger.getLogger(CheckCatalogueServices.class.getName());

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String BLUE   = "\033[0;34m";
    private static final String NC     = "\033[0m";

    public static void main(String[] args) throws Exception {

        // ── Argument parsing ──────────────────────────────────────────────────
        if (args.length < 1) {
            log.warning("Error: NODE_NAME is required");
            log.warning("Usage: java CheckCatalogueServices NODE_NAME [api_base_url] [quantity] [dashboard_dir]");
            throw new RuntimeException("Failed to fetch Catalogue Services data");
        }

        String nodeName     = args[0];
        // 2nd argument: the node's Resource Catalogue API base URL.
        // The dashboard passes the "Resource Catalogue" endpoint from endpoint_report.json
        // with "/api/service/all" appended. Falls back to the CESSDA staging URL.
        String apiBaseUrl   = args.length >= 2 && !args[1].isBlank()
                ? args[1]
                : DEFAULT_API_BASE_URL;
        int    quantity     = args.length >= 3 ? Integer.parseInt(args[2]) : 10;
        String dashboardDir = args.length >= 4 ? args[3] : "../dashboard/data";

        // ── Output paths ──────────────────────────────────────────────────────
        Path outputDir      = Path.of(dashboardDir, nodeName);
        Files.createDirectories(outputDir);
        Path reportFileJson = outputDir.resolve("catalogue_services_report.json");

        // ── Build API URL ─────────────────────────────────────────────────────
        String apiUrl = "%s?keyword=%s&from=0&quantity=%d&order=asc"
                .formatted(apiBaseUrl, nodeName, quantity);

        // ── Header ────────────────────────────────────────────────────────────
        separator();
        log.info("Service Catalogue Resource Availability Report");
        separator();
        log.info("Generated : " + Instant.now());
        log.info("Node Name : " + nodeName);
        log.info("API Source: " + apiUrl);
        separator();

        // ── Fetch data ────────────────────────────────────────────────────────
        log.info(BLUE + "Fetching service data from API..." + NC);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> apiResponse;
        try {
            apiResponse = httpClient.send(apiRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warning(RED + "ERROR: HTTP request failed: " + e.getMessage() + NC);
            log.warning("Please check network connectivity and API endpoint accessibility.");
            throw new RuntimeException("Failed to fetch Catalogue Services data");
        }

        String jsonData = apiResponse.body();

        if (jsonData == null || jsonData.isBlank()) {
            log.warning(RED + "ERROR: No data received from API (HTTP " + apiResponse.statusCode() + ")" + NC);
            throw new RuntimeException("Failed to fetch Catalogue Services data");
        }

        log.info("Data retrieved successfully! (HTTP " + apiResponse.statusCode() + ")");
        
        log.info("=== JSON RESPONSE (first 500 characters) ===");
        log.info(jsonData.substring(0, Math.min(500, jsonData.length())));
        log.info("============================================");
        

        // ── Parse JSON ────────────────────────────────────────────────────────
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(jsonData);
        } catch (IOException e) {
            log.warning(RED + "ERROR: Response is not valid JSON: " + e.getMessage() + NC);
            throw new RuntimeException("Failed to fetch Catalogue Services data");
        }

        if (root.has("error")) {
            log.info(YELLOW + "WARNING: API response contains an 'error' field." + NC);
        }

        long total = root.path("total").asLong(0);
        log.info("Total services found: " + total);
        

        // ── Check each service webpage ────────────────────────────────────────
        log.info("Checking service webpages...");
        

        JsonNode results = root.path("results");
        List<ObjectNode> serviceResults = new ArrayList<>();

        for (JsonNode service : results) {
            String name         = service.path("name").asText();
            String webpage      = service.path("webpage").isMissingNode() || service.path("webpage").isNull()
                                      ? null : service.path("webpage").asText().trim();
            String serviceId    = service.path("id").asText();
            String abbreviation = service.path("abbreviation").isMissingNode() || service.path("abbreviation").isNull()
                                      ? null : service.path("abbreviation").asText();

            String status;
            String httpCode;
            String colour;

            if (webpage == null || webpage.isEmpty()) {
                status   = "No webpage defined";
                httpCode = null;
                colour   = YELLOW;
            } else {
                httpCode = checkWebpage(httpClient, webpage);

                int code;
                try { code = Integer.parseInt(httpCode); } catch (NumberFormatException e) { code = 0; }

                if (code == 0) {
                    status = "Not available";
                    colour = RED;
                } else if (code == 404) {
                    status = "Not found";
                    colour = YELLOW;
                } else if (code >= 200 && code < 400) {
                    status = "Available";
                    colour = GREEN;
                } else {
                    status = "Not available";
                    colour = RED;
                }
            }

            // Console output
            System.out.printf("%-50s %s%s%s%n", name, colour, status, NC);

            // Build JSON entry
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name",         name);
            entry.put("abbreviation", abbreviation != null ? abbreviation : "");
            entry.put("service_id",   serviceId);
            if (webpage != null) entry.put("webpage",   webpage);   else entry.putNull("webpage");
            entry.put("status",       status);
            if (httpCode != null)     entry.put("http_code", httpCode); else entry.putNull("http_code");

            serviceResults.add(entry);
        }

        // ── Write JSON report ─────────────────────────────────────────────────
        ObjectNode report = mapper.createObjectNode();
        report.put("generated",      Instant.now().toString());
        report.put("node_name",      nodeName);
        report.put("api_source",     apiUrl);
        report.put("total_services", total);

        ArrayNode servicesArray = mapper.createArrayNode();
        serviceResults.forEach(servicesArray::add);
        report.set("services", servicesArray);

        Files.writeString(reportFileJson, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));

        // ── Footer ────────────────────────────────────────────────────────────
        
        separator();
        log.info("Report generated:");
        log.info("  JSON: " + reportFileJson.toAbsolutePath());
        separator();
    }

    /**
     * Issues an HTTP HEAD request to the given URL and returns the HTTP status
     * code as a string, or "000" if the request could not be completed.
     */
    private static String checkWebpage(HttpClient client, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return String.valueOf(resp.statusCode());

        } catch (Exception e) {
            return "000";
        }
    }

    private static void separator() {
        log.info("======================================");
    }
}