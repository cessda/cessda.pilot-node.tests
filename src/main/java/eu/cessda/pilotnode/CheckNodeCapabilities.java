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

import java.io.BufferedWriter;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * EOSC Beyond Node Registry Endpoint Capabilities Checker.
 *
 * <p>Queries the Node Registry and checks availability of capabilities for all
 * registered nodes. Per-node reports are written to
 * {@code <dashboardDir>/<nodeName>/endpoint_report.json} and an overall
 * summary is written to {@code <dashboardDir>/node_registry_summary.json}.
 *
 * <p>Usage:
 * <pre>
 *   CheckNodeCapabilities &lt;API_KEY&gt; [text|json|both] [dashboard_dir]
 * </pre>
 */
public class CheckNodeCapabilities {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String NODE_REGISTRY_URL =
            "https://node-devel.eosc.grnet.gr/federation-backend/tenants/eosc-beyond/nodes";

    private static final Duration CAPABILITY_CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final Logger log = Logger.getLogger(CheckNodeCapabilities.class.getName());

    private final EnumSet<OutputFormat> format;

    // ── Fields ────────────────────────────────────────────────────────────────

    private CheckNodeCapabilities(EnumSet<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper) {
        this.dashboardDir = dashboardDir;
        this.http = http;
        this.mapper = mapper;
        this.format = format;
    }
    private final Path dashboardDir;
    private final HttpClient http;
    private final ObjectMapper mapper;

    // ── Constructor ───────────────────────────────────────────────────────────

    // ── Entry point ───────────────────────────────────────────────────────────
    @SuppressWarnings("java:S106")
    public static void main(String[] args) throws IOException {

        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Error: API_KEY is required.");
            System.err.println("Usage: CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]");
            System.exit(-1);
        }

        String apiKey = args[0];

        EnumSet<OutputFormat> format = EnumSet.of(OutputFormat.JSON);
        if (args.length >= 2) {
            format = switch (args[1].toLowerCase()) {
                case "text", "txt" -> EnumSet.of(OutputFormat.TEXT);
                case "json" -> EnumSet.of(OutputFormat.JSON);
                case "both" -> EnumSet.of(OutputFormat.TEXT, OutputFormat.JSON);
                default -> {
                    System.err.println("Invalid format: " + args[1]);
                    System.err.println("Usage: CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]");
                    System.exit(-1);
                    yield EnumSet.noneOf(OutputFormat.class); // Needed to satisfy the compiler but will never be called
                }
            };
        }

        Path dashboardDir = Path.of(args.length >= 3 ? args[2] : "dashboard/data");

        ObjectMapper mapper = new ObjectMapper();

        // Instance an HTTP client
        HttpClient http = HttpUtils.trustAllHttpClient();

        run(apiKey, format, dashboardDir, http, mapper);
    }

    @SuppressWarnings("java:S6201")
    public static void run(String apiKey, Set<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper) throws IOException {

        printBanner();

        // Copy formats to an EnumSet if they are not already
        EnumSet<OutputFormat> formatEnumSet;
        if (format instanceof EnumSet<OutputFormat>) {
            formatEnumSet = (EnumSet<OutputFormat>) format;
        } else {
            formatEnumSet = EnumSet.copyOf(format);
        }

        new CheckNodeCapabilities(formatEnumSet, dashboardDir, http, mapper).check(apiKey);
    }

    // ── Main logic ────────────────────────────────────────────────────────────

    private static ArrayNode extractNodesArray(JsonNode obj) throws IOException {
        for (String key : new String[]{"nodes", "results", "data"}) {
            JsonNode candidate = obj.get(key);
            if (candidate != null && candidate.isArray()) return (ArrayNode) candidate;
        }
        throw new IOException("Registry returned a JSON object but no recognised array key (nodes/results/data) was found");
    }

    // ── Per-node processing ───────────────────────────────────────────────────

    private static void printBanner() {
        log.info("==========================================");
        log.info("EOSC Beyond Node Registry");
        log.info("Endpoint Availability Report");
        log.info("==========================================");
        log.info("Generated: " + nowIso());
        log.info("Registry:  " + NODE_REGISTRY_URL);
        log.info("==========================================");

    }

    // ── HTTP probe ────────────────────────────────────────────────────────────

    private static void writeTextHeader(Path path) throws IOException {
        String header = """
                ==========================================
                EOSC Beyond Node Registry
                Endpoint Availability Summary
                ==========================================
                Generated: %s
                Registry:  %s
                ==========================================

                """.formatted(nowIso(), NODE_REGISTRY_URL);
        Files.writeString(path, header);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static void appendTextSummaryEntry(Path path, ObjectNode nodeSummary) throws IOException {
        String entry = """
                Node: %s
                  Endpoint:               %s
                  Total Capabilities:     %d
                  Available Capabilities: %d

                """.formatted(
                nodeSummary.path("node_name").asText(),
                nodeSummary.path("node_endpoint").asText(),
                nodeSummary.path("total_capabilities").asInt(),
                nodeSummary.path("available_capabilities").asInt());
        Files.writeString(path, entry, java.nio.file.StandardOpenOption.APPEND);
    }

    private void check(String apiKey) throws IOException {
        // ── Fetch node list ───────────────────────────────────────────────────

        log.info("Fetching node list from registry...");

        HttpRequest registryRequest = HttpRequest.newBuilder()
                .uri(URI.create(NODE_REGISTRY_URL))
                .header("X-Api-Key", apiKey)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> registryResponse;

        try {
            registryResponse = http.send(registryRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (registryResponse.statusCode() != 200) {
                throw new IOException("Failed to fetch data from Node Registry" +
                        " (HTTP " + registryResponse.statusCode() + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        JsonNode root;
        try (InputStream body = registryResponse.body()) {
            root = mapper.readTree(body);
        }

        // Handle bare array or wrapped object
        ArrayNode nodesArray = switch (root.getNodeType()) {
            case ARRAY  -> (ArrayNode) root;
            case OBJECT -> extractNodesArray(root);
            default -> throw new IOException("Unexpected JSON type from registry: " + root.getNodeType());
        };

        log.info("Node registry data retrieved successfully!");


        // ── Initialise summary files ──────────────────────────────────────────

        Path summaryJsonPath = dashboardDir.resolve("node_registry_summary.json");
        Path summaryTxtPath  = dashboardDir.resolve("node_registry_summary.txt");

        if (format.contains(OutputFormat.TEXT)) {
            writeTextHeader(summaryTxtPath);
        }

        List<ObjectNode> nodeSummaries = new ArrayList<>();

        // ── Process each node ─────────────────────────────────────────────────

        log.info("Processing nodes from registry...");

        for (JsonNode nodeJson : nodesArray) {
            String nodeName = text(nodeJson, "name");
            String nodeId = text(nodeJson, "id");
            String nodePid = text(nodeJson, "pid");
            String nodeEndpointString = text(nodeJson, "node_endpoint");
            String nodeLogo = text(nodeJson, "logo");
            String legalEntityName = nodeJson.path("legal_entity").path("name").asText();
            String legalEntityRor = nodeJson.path("legal_entity").path("ror_id").asText();

            try {
                URI nodeEndpoint = new URI(nodeEndpointString);


                ObjectNode summary = checkNodeCapabilities(
                        nodeName, nodeId, nodePid, nodeEndpoint,
                        nodeLogo, legalEntityName, legalEntityRor);

                nodeSummaries.add(summary);

                if (format.contains(OutputFormat.TEXT)) {
                    appendTextSummaryEntry(summaryTxtPath, summary);
                }
            } catch (URISyntaxException e) {
                log.log(Level.SEVERE, "Node {0} - node_endpoint is an invalid URI: {1}", new Object[]{nodeName, e.getMessage()});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // ── Write JSON summary ────────────────────────────────────────────────

        if (format.contains(OutputFormat.JSON)) {
            ObjectNode summaryJson = mapper.createObjectNode();
            summaryJson.put("generated", nowIso());
            summaryJson.put("registry_source", NODE_REGISTRY_URL);
            ArrayNode nodesOut = summaryJson.putArray("nodes");
            nodeSummaries.forEach(nodesOut::add);
            mapper.writerWithDefaultPrettyPrinter().writeValue(summaryJsonPath.toFile(), summaryJson);
        }

        // ── Final output ──────────────────────────────────────────────────────
        String summaryMessage = "All nodes processed. Summary reports:";
        if (format.contains(OutputFormat.TEXT)) {
            summaryMessage += "\n  Text: " + summaryTxtPath;
        }
        if (format.contains(OutputFormat.JSON)) {
            summaryMessage += "\n  JSON: " + summaryJsonPath;
        }
        log.info(summaryMessage);
    }

    // ── Text report helpers ───────────────────────────────────────────────────

    private ObjectNode checkNodeCapabilities(
            String nodeName, String nodeId, String nodePid, URI nodeEndpoint,
            String nodeLogo, String legalEntityName, String legalEntityRor) throws IOException, InterruptedException {

        log.log(Level.INFO, """
                        Node: {0}
                        ========================================
                        ID:           {1}
                        PID:          {2}
                        Endpoint:     {3}
                        Legal Entity: {4}""",
                new Object[]{nodeName, nodeId, nodePid, nodeEndpoint, legalEntityName}
        );


        Path nodeDir = dashboardDir.resolve(nodeName);
        Files.createDirectories(nodeDir);
        Path reportJsonPath = nodeDir.resolve("endpoint_report.json");
        Path reportTxtPath  = nodeDir.resolve("endpoint_report.txt");

        // ── Fetch capabilities ────────────────────────────────────────────────

        log.log(Level.INFO, "Fetching capabilities for {0}...", nodeName);

        JsonNode capsRoot;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(nodeEndpoint)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                log.log(Level.WARNING, "Failed to fetch capabilities from {0} (HTTP {1})", new Object[]{nodeEndpoint, resp.statusCode()});

                // Return an empty summary for this node
                return buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                        legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportJsonPath);
            }

            try (InputStream capabilitiesBody = resp.body()) {
                capsRoot = mapper.readTree(capabilitiesBody);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to fetch capabilities from {0}: {1}", new Object[]{nodeEndpoint, e});

            // Return an empty summary for this node
            return buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                    legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportJsonPath);
        }

        log.info("Checking capabilities...");

        try (BufferedWriter reportTxtPathWriter = (format.contains(OutputFormat.TEXT) ? Files.newBufferedWriter(reportTxtPath) : null)) {

            if (reportTxtPathWriter != null) {
                 var header = buildNodeTextHeader(nodeName, nodeId, nodePid,
                         nodeEndpoint, nodeLogo, legalEntityName, legalEntityRor);
                 reportTxtPathWriter.write(header);
             }


             // ── Check each capability ─────────────────────────────────────────────

             ArrayNode capabilitiesOut = mapper.createArrayNode();
             int total = 0;
             int available = 0;

             JsonNode capsArray = capsRoot.path("capabilities");
             for (JsonNode cap : capsArray) {
                 String capType = cap.path("capability_type").asText();
                 String endpoint = cap.path("endpoint").asText();
                 String version = cap.path("version").asText();

                 System.out.printf("  %-35s ", capType);

                 try {
                     URI uri = new URI(endpoint);
                     CapabilityStatus status = probeEndpoint(uri);
                     total++;
                     if (status.available()) {
                         available++;
                     }

                     if (status.exception() == null) {
                             System.out.printf("%s (HTTP %s)%n", status.label(), status.httpCode());
                         } else {
                             System.out.printf("%s (%s)%n", status.label(), status.exception());
                         }

                     if (reportTxtPathWriter != null) {
                         String capability = appendCapabilityToText(capType, endpoint, version, status);
                         reportTxtPathWriter.write(capability);
                     }

                     ObjectNode capOut = mapper.createObjectNode();
                     capOut.put("capability_type", capType);
                     capOut.put("endpoint", endpoint);
                     capOut.put("version", version);
                     capOut.put("status", status.label());
                     capOut.put("http_code", status.httpCode());
                     capabilitiesOut.add(capOut);
                 } catch (URISyntaxException e) {
                     LogRecord logRecord = new LogRecord(Level.SEVERE, "URI {} is not a valid URI");
                     logRecord.setParameters(new Object[]{endpoint});
                     logRecord.setThrown(e);
                     log.log(logRecord);
                 }
             }


             // ── Write per-node JSON report ────────────────────────────────────────

             ObjectNode nodeReport = buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                     legalEntityName, legalEntityRor, total, available, capabilitiesOut, reportJsonPath);


            if (format.contains(OutputFormat.JSON)) {
                 mapper.writerWithDefaultPrettyPrinter().writeValue(reportJsonPath.toFile(), nodeReport);
             }

            String summaryMessage = "Node reports generated:";
            if (format.contains(OutputFormat.TEXT)) {
                summaryMessage += "\n  Text: " + reportTxtPath;
            }
            if (format.contains(OutputFormat.JSON)) {
                summaryMessage += "\n  JSON: " + reportJsonPath;
            }
            log.info(summaryMessage);

            return nodeReport;
        }
    }

    private CapabilityStatus probeEndpoint(URI url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(url)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(CAPABILITY_CHECK_TIMEOUT)
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return CapabilityStatus.of(resp.statusCode());
        } catch (IOException e) {
            return CapabilityStatus.exceptionally(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CapabilityStatus.of(-1);
        }
    }

    private ObjectNode buildNodeSummary(
            String nodeName, String nodeId, String nodePid, URI nodeEndpoint,
            String legalEntityName, String legalEntityRor,
            int total, int available, ArrayNode capabilities, Path reportPath) {

        ObjectNode n = mapper.createObjectNode();
        n.put("generated", nowIso());
        n.put("node_name", nodeName);
        n.put("node_id", nodeId);
        n.put("node_pid", nodePid);
        n.put("node_endpoint", nodeEndpoint.toString());
        ObjectNode le = n.putObject("legal_entity");
        le.put("name", legalEntityName);
        le.put("ror_id", legalEntityRor);
        n.put("total_capabilities", total);
        n.put("available_capabilities", available);
        n.put("report_file", reportPath.toString().replace('\\', '/'));
        n.set("capabilities", capabilities);
        return n;
    }

    private String appendCapabilityToText(String capType, String endpoint,
            String version, CapabilityStatus status) {
        return "%-40s %s (HTTP %s)%n  └─ Endpoint: %s%n  └─ Version: %s%n%n"
                .formatted(capType, status.label(), status.httpCode(), endpoint, version);
    }

    private String buildNodeTextHeader(
            String nodeName, String nodeId, String nodePid, URI nodeEndpoint,
            String nodeLogo, String legalEntityName, String legalEntityRor) {
        return ("==========================================\n" +
                "Node: " + nodeName + "\n" +
                "==========================================\n" +
                "Generated:          " + nowIso() + "\n" +
                "Node ID:            " + nodeId + "\n" +
                "Node PID:           " + nodePid + "\n" +
                "Node Endpoint:      " + nodeEndpoint + "\n" +
                "Legal Entity:       " + legalEntityName + "\n" +
                "Legal Entity ROR:   " + legalEntityRor + "\n" +
                "Logo:               " + nodeLogo + "\n" +
                "==========================================\n\n");
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }

    private static String nowIso() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ── Supporting types ──────────────────────────────────────────────────────

    public enum OutputFormat {TEXT, JSON}

    /**
     * Represents the HTTP-probed status of a single capability endpoint.
     */
    record CapabilityStatus(int httpCode, boolean available, IOException exception) {
        static CapabilityStatus exceptionally(IOException exception) {
            return new CapabilityStatus(-1, false, exception);
        }

        static CapabilityStatus of(int code) {
            if (code == 404) {
                return new CapabilityStatus(404, false, null);
            } else if (code >= 200 && code < 400) {
                return new CapabilityStatus(code, true, null);
            } else {
                return new CapabilityStatus(code, false, null);
            }
        }

        String label() {
            if (available) {
                return "Available";
            } else {
                return "Not available";
            }
        }
    }
}