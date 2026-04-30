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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Service Catalogue Resource Checker
 *
 * Reads JSON from API and checks availability of each resource webpage.
 *
 * Usage: java CheckCatalogueServices NODE_NAME [quantity] [dashboard_dir]
 *   NODE_NAME:     Node name to use as keyword filter (required)
 *   quantity:      Maximum number of services to retrieve (default: 10)
 *   dashboard_dir: Path to the dashboard data directory (default: ../dashboard/data)
 *                  Output is written to <dashboard_dir>/<NODE_NAME>/catalogue_services_report.json
 */
public class CheckCatalogueServices {

    // Use either the Sandbox Resource Catalogue API or your Node's
    // Resource Catalogue API, depending on the Metric being evaluated
    // private static final String API_BASE_URL = "https://providers.sandbox.eosc-beyond.eu/api/service/all";
    private static final String API_BASE_URL = "https://service-catalogue-staging.beyond.cessda.eu/api/service/all";

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String BLUE   = "\033[0;34m";
    private static final String NC     = "\033[0m";

    public static void main(String[] args) throws Exception {

        // ── Argument parsing ──────────────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Error: NODE_NAME is required");
            System.err.println("Usage: java CheckCatalogueServices NODE_NAME [quantity] [dashboard_dir]");
            System.exit(1);
        }

        String nodeName     = args[0];
        int    quantity     = args.length >= 2 ? Integer.parseInt(args[1]) : 10;
        String dashboardDir = args.length >= 3 ? args[2] : "../dashboard/data";

        // ── Output paths ──────────────────────────────────────────────────────
        Path outputDir      = Path.of(dashboardDir, nodeName);
        Files.createDirectories(outputDir);
        Path reportFileJson = outputDir.resolve("catalogue_services_report.json");

        // ── Build API URL ─────────────────────────────────────────────────────
        String apiUrl = "%s?keyword=%s&from=0&quantity=%d&order=asc"
                .formatted(API_BASE_URL, nodeName, quantity);

        // ── Header ────────────────────────────────────────────────────────────
        separator();
        System.out.println("Service Catalogue Resource Availability Report");
        separator();
        System.out.println("Generated : " + Instant.now());
        System.out.println("Node Name : " + nodeName);
        System.out.println("API Source: " + apiUrl);
        separator();
        System.out.println();

        // ── Fetch data ────────────────────────────────────────────────────────
        System.out.println(BLUE + "Fetching service data from API..." + NC);
        System.out.println();

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
            System.err.println(RED + "ERROR: HTTP request failed: " + e.getMessage() + NC);
            System.err.println("Please check network connectivity and API endpoint accessibility.");
            System.exit(1);
            return;
        }

        String jsonData = apiResponse.body();

        if (jsonData == null || jsonData.isBlank()) {
            System.err.println(RED + "ERROR: No data received from API (HTTP " + apiResponse.statusCode() + ")" + NC);
            System.exit(1);
        }

        System.out.println("Data retrieved successfully! (HTTP " + apiResponse.statusCode() + ")");
        System.out.println();
        System.out.println("=== JSON RESPONSE (first 500 characters) ===");
        System.out.println(jsonData.substring(0, Math.min(500, jsonData.length())));
        System.out.println("============================================");
        System.out.println();

        // ── Parse JSON ────────────────────────────────────────────────────────
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(jsonData);
        } catch (IOException e) {
            System.err.println(RED + "ERROR: Response is not valid JSON: " + e.getMessage() + NC);
            System.exit(1);
            return;
        }

        if (root.has("error")) {
            System.out.println(YELLOW + "WARNING: API response contains an 'error' field." + NC);
        }

        long total = root.path("total").asLong(0);
        System.out.println("Total services found: " + total);
        System.out.println();

        // ── Check each service webpage ────────────────────────────────────────
        System.out.println("Checking service webpages...");
        System.out.println();

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
        System.out.println();
        separator();
        System.out.println("Report generated:");
        System.out.println("  JSON: " + reportFileJson.toAbsolutePath());
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
        System.out.println("======================================");
    }
}
