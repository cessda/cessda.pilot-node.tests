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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import eu.cessda.pilotnode.catalogue.CatalogueSelector;
import eu.cessda.pilotnode.catalogue.CatalogueList;
import eu.cessda.pilotnode.catalogue.CatalogueContext;
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

    // Some Exchange Service webpages sit behind a CDN/WAF that fast-rejects
    // (typically a quick 403) requests carrying the JDK's default
    // "Java-http-client/…" User-Agent, misclassifying a perfectly healthy
    // service as "Not available". A browser-shaped-but-identifiable UA
    // avoids that false negative while still being honest about what's
    // making the request.
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; CESSDA-PilotNode-Monitor/1.0; "
                    + "+https://github.com/cessda/cessda.pilot-node.tests)";
    private static final String ACCEPT_HEADER =
            "text/html,application/json;q=0.9,*/*;q=0.8";

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

        HttpClient httpClient = HttpUtils.httpClient();

        ObjectMapper objectMapper = new ObjectMapper();

        // Validate that nodeName contains no directory elements
        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() == 1 && !nodeNamePath.isAbsolute()) {
            run(Path.of(dashboardDir), nodeName, nodePid, apiBaseUrl, quantity, httpClient, objectMapper);
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
    public static URI buildApiServiceUrl(URI url) {
        String base = url.toString();
        // Some nodes advertise the service list itself (…/api/service/all) as their Resource Catalogue
        // endpoint; appending the path again would ask for …/api/service/all/api/service/all.
        if (base.endsWith("/api/service/all") || base.endsWith("/api/service/all/")) {
            return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        }
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
            throws IOException, URISyntaxException, InterruptedException {
        run(dashboardDir, nodeName, nodePid, apiBaseUrl, quantity, httpClient, mapper, FALLBACK_BASE_URL);
    }

    static void run(
            Path dashboardDir,
            String nodeName,
            String nodePid,
            URI apiBaseUrl,
            int quantity,
            HttpClient httpClient,
            ObjectMapper mapper,
            URI fallbackBase)
            throws IOException, URISyntaxException, InterruptedException {

        // When calling the node's own Resource Catalogue endpoint the API returns all services for that node
        // without filtering. The Sandbox fallback aggregates the services of many nodes, so it is filtered.
        URI apiUrl = buildApiServiceUrl(apiBaseUrl);

        log.log(Level.INFO, """
                        Service Catalogue Resource Availability Report
                        Generated : {0}
                        Node Name : {1}
                        API Source: {2}""",
                new Object[]{Instant.now(), nodeName, apiUrl}
        );

        log.info("Fetching service data from API");
        Source source;
        try {
            source = Source.of(readList(httpClient, mapper, apiUrl), apiUrl, nodePid);
        } catch (IOException e) {
            source = sandboxSource(httpClient, mapper, nodePid, apiUrl.toString(), describe(e), quantity, fallbackBase, e);
        }
        checkAndReport(dashboardDir, nodeName, source, httpClient, mapper);
    }

    /**
     * Reads the node's services from whichever of its catalogue endpoints can be read best (see
     * {@link CatalogueSelector}), falling back to the Sandbox, filtered to the node, if none can.
     *
     * @param nodePid the node's PID; if blank it is read from the node's {@code endpoint_report.json}
     */
    public static void runSelected(Path dashboardDir, String nodeName, String nodePid, int quantity,
                                   CatalogueSelector selector, HttpClient httpClient, ObjectMapper mapper)
            throws IOException, InterruptedException {
        runSelected(dashboardDir, nodeName, nodePid, quantity, selector, httpClient, mapper, FALLBACK_BASE_URL);
    }

    static void runSelected(Path dashboardDir, String nodeName, String nodePid, int quantity,
                            CatalogueSelector selector, HttpClient httpClient, ObjectMapper mapper, URI fallbackBase)
            throws IOException, InterruptedException {

        Path endpointReportPath = dashboardDir.resolve(nodeName).resolve("endpoint_report.json");
        JsonNode endpointReport = mapper.readTree(endpointReportPath.toFile());
        String pid = nodePid == null || nodePid.isBlank() ? endpointReport.path("node_pid").asText(null) : nodePid;

        log.log(Level.INFO, "Service Catalogue Resource Availability Report for {0} ({1}); choosing among {2}",
                new Object[]{nodeName, Instant.now(), selector.candidates(endpointReport)});

        if (selector.candidates(endpointReport).isEmpty()) {
            String error = endpointReport.path("error").asText("");
            throw new NoCatalogueEndpointException(error.isBlank()
                    ? "no Service Catalogue or Resource Catalogue endpoint in endpoint_report.json"
                    : "no Service Catalogue or Resource Catalogue endpoint: endpoint_report.json records an error ("
                            + error + ")");
        }

        CatalogueSelector.Result result = selector.select(endpointReport, new CatalogueContext(pid));
        ArrayNode tried = mapper.createArrayNode();
        for (CatalogueSelector.Attempt attempt : result.attempts()) {
            ObjectNode a = tried.addObject();
            a.put("capability_type", attempt.candidate().capabilityType());
            a.put("endpoint", attempt.candidate().endpoint());
            a.put("outcome", attempt.outcome());
        }

        Source source;
        if (result.selection().isPresent()) {
            CatalogueSelector.Selection chosen = result.selection().get();
            ObjectNode provenance = mapper.createObjectNode();
            provenance.put("capability_type", chosen.candidate().capabilityType());
            provenance.put("endpoint", chosen.candidate().endpoint());
            provenance.put("protocol", chosen.candidate().protocol());
            provenance.put("adapter", chosen.adapterId());
            provenance.put("format", chosen.format());
            provenance.put("converted", chosen.list().converted());
            log.log(Level.INFO, "Using {0} ({1})", new Object[]{chosen.candidate(), chosen.format()});
            source = new Source(chosen.list().root(), URI.create(chosen.candidate().endpoint()), null, pid, provenance,
                    tried, chosen.list().invalid(), chosen.list().warnings());
        } else {
            String reason = result.attempts().isEmpty()
                    ? "no Service Catalogue or Resource Catalogue endpoint"
                    : "no catalogue endpoint could be read (" + result.attempts().stream()
                            .map(a -> a.candidate().endpoint() + ": " + a.outcome()).collect(java.util.stream.Collectors.joining("; ")) + ")";
            source = sandboxSource(httpClient, mapper, pid, endpointReport.path("node_endpoint").asText(""), reason,
                    quantity, fallbackBase, null);
            source = new Source(source.root(), source.apiUrl(), source.fallbackReason(), source.nodePid(), null, tried,
                    List.of(), List.of());
        }
        checkAndReport(dashboardDir, nodeName, source, httpClient, mapper);
    }

    /** The node's {@code endpoint_report.json} lists no catalogue endpoint at all, so there is nothing to read. */
    public static final class NoCatalogueEndpointException extends IOException {
        private static final long serialVersionUID = 1L;

        NoCatalogueEndpointException(String message) {
            super(message);
        }
    }

    // ── where the services come from ──────────────────────────────────────

    /** The services to check, and how they were obtained. */
    private record Source(JsonNode root, URI apiUrl, String fallbackReason, String nodePid, ObjectNode provenance,
                          ArrayNode tried, List<CatalogueList.InvalidRecord> invalid, List<String> warnings) {

        static Source of(JsonNode root, URI apiUrl, String nodePid) {
            return new Source(root, apiUrl, null, nodePid, null, null, List.of(), List.of());
        }
    }

    private static JsonNode readList(HttpClient httpClient, ObjectMapper mapper, URI url)
            throws IOException, InterruptedException {
        try (InputStream in = fetchData(httpClient, url)) {
            return mapper.readTree(in);
        }
    }

    /**
     * The Sandbox lists the services of every node, so it can only stand in for the node's own catalogue if it is
     * asked for this node and nothing else.
     */
    private static Source sandboxSource(HttpClient httpClient, ObjectMapper mapper, String nodePid, String triedSource,
                                        String reason, int quantity, URI fallbackBase, Exception cause)
            throws IOException, InterruptedException {
        if (nodePid == null || nodePid.isBlank()) {
            throw new IOException("Failed to fetch Catalogue Services data from the node's own catalogue ("
                    + triedSource + ") and there is no node_pid to look the node up in the Sandbox", cause);
        }

        URI fallbackUrl = buildNodeFilterUrl(buildApiServiceUrl(fallbackBase), nodePid, quantity);
        log.log(Level.WARNING, "Own catalogue could not be read ({0}). Retrying with the Sandbox, filtered to {1}: {2}",
                new Object[]{reason, nodePid, fallbackUrl});

        JsonNode root;
        try {
            root = readList(httpClient, mapper, fallbackUrl);
        } catch (IOException fallbackException) {
            if (cause != null) {
                fallbackException.addSuppressed(cause);
            }
            throw new IOException("Failed to fetch Catalogue Services data from the node's own catalogue and"
                    + " from the Sandbox", fallbackException);
        }

        // Belt and braces: even if the Sandbox ignored the filter, never report another node's services.
        JsonNode results = root.path("results");
        ArrayNode own = mapper.createArrayNode();
        for (JsonNode service : results) {
            if (nodePid.equalsIgnoreCase(service.path("nodePID").asText())) {
                own.add(service);
            }
        }
        ObjectNode filtered = mapper.createObjectNode();
        if (own.size() != results.size()) {
            log.log(Level.WARNING, "The Sandbox returned {0} service(s) of other nodes; ignoring them",
                    results.size() - own.size());
            filtered.put("total", own.size());
        } else {
            filtered.put("total", root.path("total").asLong(0));
        }
        filtered.set("results", own);
        if (root.has("error")) {
            filtered.set("error", root.get("error"));
        }
        return new Source(filtered, fallbackUrl, reason, nodePid, null, null, List.of(), List.of());
    }

    // ── checking and reporting ────────────────────────────────────────────

    private static void checkAndReport(Path dashboardDir, String nodeName, Source source, HttpClient httpClient,
                                       ObjectMapper mapper) throws IOException {
        Path outputDir = dashboardDir.resolve(nodeName);
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
    private static InputStream fetchData(HttpClient httpClient, URI url) throws IOException, InterruptedException {
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
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    static URI buildNodeFilterUrl(URI fallbackServiceBase, String nodePid, int quantity) {
        String encodedPid = URLEncoder.encode(nodePid, StandardCharsets.UTF_8);
        return URI.create(fallbackServiceBase + "?node=" + encodedPid
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
    private static WebpageCheckResult checkWebpage(HttpClient client, URI url)
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