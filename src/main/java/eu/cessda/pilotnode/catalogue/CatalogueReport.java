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

package eu.cessda.pilotnode.catalogue;

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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import eu.cessda.pilotnode.common.Findings;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.catalogue.CatalogueList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the Exchange Services checks have in common, whichever mode they run in: checking the services of a list
 * (is each one's webpage available, and how fast) and writing {@code catalogue_services_report.json}.
 */
public final class CatalogueReport {

    private CatalogueReport() {}

    private static final Logger log = Logger.getLogger(CatalogueReport.class.getName());

    // ── Metric 13 thresholds ─────────────────────────────────────────────
    // From the Proposed Validation Metrics doc's Test Methodology /
    // Success Criteria for Exchange Services - Machine Actionable Tests.
    // A service exceeding RESPONSE_TIME_THRESHOLD_MS fails its own check;
    // RESPONSE_TIME_TARGET_MS is the doc's target for the *average* across
    // all services, reported but not itself pass/fail per service.
    public static final long RESPONSE_TIME_THRESHOLD_MS = 30_000L;

    public static final long RESPONSE_TIME_TARGET_MS    = 5_000L;

    // Some Exchange Service webpages sit behind a CDN/WAF that fast-rejects
    // (typically a quick 403) requests carrying the JDK's default
    // "Java-http-client/…" User-Agent, misclassifying a perfectly healthy
    // service as "Not available". A browser-shaped-but-identifiable UA
    // avoids that false negative while still being honest about what's
    // making the request.
    public static final String USER_AGENT =
            "Mozilla/5.0 (compatible; CESSDA-PilotNode-Monitor/1.0; "
                    + "+https://github.com/cessda/cessda.pilot-node.tests)";

    public static final String ACCEPT_HEADER =
            "text/html,application/json;q=0.9,*/*;q=0.8";

    // ANSI colour codes
    public static final String RED    = "\033[0;31m";

    public static final String GREEN  = "\033[0;32m";

    public static final String YELLOW = "\033[1;33m";

    public static final String NC     = "\033[0m";

    /** The services to check, and how they were obtained. */
    public record Source(JsonNode root, URI apiUrl, String fallbackReason, String nodePid, ObjectNode provenance,
                          ArrayNode tried, List<CatalogueList.InvalidRecord> invalid, List<String> warnings,
                          Findings findings) {

        public static Source of(JsonNode root, URI apiUrl, String nodePid) {
            return new Source(root, apiUrl, null, nodePid, null, null, List.of(), List.of(), null);
        }
    }

    public static void checkAndReport(Path outputDir, String nodeName, Source source, HttpClient httpClient,
                                       ObjectMapper mapper) throws IOException {
        Files.createDirectories(outputDir);
        Path reportFileJson = outputDir.resolve("catalogue_services_report.json");

        JsonNode root = source.root();
        URI apiUrl = source.apiUrl();
        String fallbackReason = source.fallbackReason();
        String nodePid = source.nodePid();

        if (root.has("error")) {
            log.log(Level.WARNING, "API response contains an 'error' field.");
        }
        long total = root.path("total").asLong(0);
        JsonNode results = root.path("results");
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
                } catch (URISyntaxException | IllegalArgumentException e) {
                    // IllegalArgumentException: well-formed but unusable, e.g. no scheme ("www.example.org")
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
        if (source.findings() != null) {
            source.findings().writeTo(report, mapper);
        } else {
            report.put("mode", CheckMode.LENIENT.label());
        }
        if (fallbackReason != null) {
            report.put("fallback", true);
            report.put("fallback_reason", fallbackReason);
            report.put("note", "The node's own catalogue could not be read (" + fallbackReason
                    + "). These are the services the Sandbox has registered for " + nodePid + "; the node may"
                    + " publish others.");
        }
        if (source.provenance() != null) {
            report.set("source", source.provenance());
        }
        if (source.tried() != null && !source.tried().isEmpty()) {
            report.set("tried", source.tried());
        }
        if (!source.invalid().isEmpty()) {
            report.put("invalid_records", source.invalid().size());
            ArrayNode details = report.putArray("invalid_details");
            for (CatalogueList.InvalidRecord r : source.invalid()) {
                ObjectNode d = details.addObject();
                d.put("id", r.id());
                d.put("problems", String.join("; ", r.messages()));
            }
        }
        if (!source.warnings().isEmpty()) {
            ArrayNode w = report.putArray("warnings");
            source.warnings().forEach(w::add);
        }
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
    public static InputStream fetchData(HttpClient httpClient, URI url) throws IOException, InterruptedException {
        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(url)
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> apiResponse = httpClient.send(
                apiRequest,
                HttpResponse.BodyHandlers.ofInputStream());

        int status = apiResponse.statusCode();
        if (status != 200) {
            throw new IOException("HTTP " + apiResponse.statusCode());
        }

        return apiResponse.body();
    }

    /**
     * The Sandbox lists services of every node, so it must be asked for one node. {@code node=<PID>} is its
     * own filter; a free-text {@code keyword} matches fragments of the node name ("Data" in "Data-Terra") in
     * other nodes' services.
     */
    public static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * Result of a single Metric 13 webpage/endpoint check.
     */
    public record WebpageCheckResult(
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
    public static WebpageCheckResult checkWebpage(HttpClient client, URI url)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url)
                .GET()
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HEADER)
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
    public static boolean isContentValid(String contentType, String body) {
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
