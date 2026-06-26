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

import java.io.BufferedWriter;
import java.io.IOException;
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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    private static final Logger log = Logger.getLogger(CheckNodeCapabilities.class.getName());

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        if (args.length < 1 || args[0].isBlank()) {
            log.warning("Error: API_KEY is required.");
            log.warning("Usage: CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]");
            throw new RuntimeException("Failed to fetch data from Node Registry (HTTP 401)");
        }

        String apiKey = args[0];

        OutputFormat format = OutputFormat.JSON;
        if (args.length >= 2) {
            format = switch (args[1].toLowerCase()) {
                case "text", "txt" -> OutputFormat.TEXT;
                case "json"        -> OutputFormat.JSON;
                case "both"        -> OutputFormat.BOTH;
                default -> {
                    log.warning("Invalid format: " + args[1]);
                    log.warning("Usage: CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]");
                    throw new RuntimeException("Failed to fetch data from Node Registry (HTTP 401)");
                }
            };
        }

        Path dashboardDir = Path.of(args.length >= 3 ? args[2] : "../dashboard/data");
        Files.createDirectories(dashboardDir);

        new CheckNodeCapabilities(apiKey, format, dashboardDir).run();
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String apiKey;
    private final OutputFormat format;
    private final Path dashboardDir;
    private final HttpClient http;
    private final ObjectMapper mapper;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CheckNodeCapabilities(String apiKey, OutputFormat format, Path dashboardDir) {
        this.apiKey = apiKey;
        this.format = format;
        this.dashboardDir = dashboardDir;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    // ── Main logic ────────────────────────────────────────────────────────────

    public void run() throws Exception {

        printBanner();

        // ── Fetch node list ───────────────────────────────────────────────────

        log.info("Fetching node list from registry...");

        HttpRequest registryRequest = HttpRequest.newBuilder()
                .uri(URI.create(NODE_REGISTRY_URL))
                .header("X-Api-Key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> registryResponse =
                http.send(registryRequest, HttpResponse.BodyHandlers.ofString());

        if (registryResponse.statusCode() != 200) {
            log.warning("ERROR: Failed to fetch data from Node Registry" +
                    " (HTTP " + registryResponse.statusCode() + ")");
            throw new RuntimeException("Failed to fetch data from Node Registry (HTTP 401)");
        }

        JsonNode root;
        try {
            root = mapper.readTree(registryResponse.body());
        } catch (IOException e) {
            log.warning("ERROR: Registry response is not valid JSON");
            log.warning("Raw response: " + registryResponse.body());
            throw new RuntimeException("Failed to fetch data from Node Registry (HTTP 401)");
        }

        // Handle bare array or wrapped object
        ArrayNode nodesArray = switch (root.getNodeType()) {
            case ARRAY  -> (ArrayNode) root;
            case OBJECT -> extractNodesArray(root);
            default -> {
                log.warning("ERROR: Unexpected JSON type from registry: " + root.getNodeType());
                throw new RuntimeException("Failed to fetch data from Node Registry (HTTP 401)");
            }
        };

        log.info("Node registry data retrieved successfully!");
        

        // ── Initialise summary files ──────────────────────────────────────────

        Path summaryJsonPath = dashboardDir.resolve("node_registry_summary.json");
        Path summaryTxtPath  = dashboardDir.resolve("node_registry_summary.txt");

        if (format.includesText()) {
            writeTextHeader(summaryTxtPath);
        }

        List<ObjectNode> nodeSummaries = new ArrayList<>();

        // ── Process each node ─────────────────────────────────────────────────

        log.info("==========================================");
        log.info("Processing nodes from registry...");
        log.info("==========================================");
        

        for (JsonNode nodeJson : nodesArray) {
            String nodeName        = text(nodeJson, "name");
            String nodeId          = text(nodeJson, "id");
            String nodePid         = text(nodeJson, "pid");
            String nodeEndpoint    = text(nodeJson, "node_endpoint");
            String nodeLogo        = text(nodeJson, "logo");
            String legalEntityName = nodeJson.path("legal_entity").path("name").asText();
            String legalEntityRor  = nodeJson.path("legal_entity").path("ror_id").asText();

            ObjectNode summary = checkNodeCapabilities(
                    nodeName, nodeId, nodePid, nodeEndpoint,
                    nodeLogo, legalEntityName, legalEntityRor);

            nodeSummaries.add(summary);

            if (format.includesText()) {
                appendTextSummaryEntry(summaryTxtPath, summary);
            }
        }

        // ── Write JSON summary ────────────────────────────────────────────────

        if (format.includesJson()) {
            ObjectNode summaryJson = mapper.createObjectNode();
            summaryJson.put("generated", nowIso());
            summaryJson.put("registry_source", NODE_REGISTRY_URL);
            ArrayNode nodesOut = summaryJson.putArray("nodes");
            nodeSummaries.forEach(nodesOut::add);
            mapper.writerWithDefaultPrettyPrinter().writeValue(summaryJsonPath.toFile(), summaryJson);
        }

        // ── Final output ──────────────────────────────────────────────────────

        log.info("==========================================");
        log.info("All nodes processed!");
        log.info("==========================================");
        
        log.info("Summary reports:");
        if (format.includesText()) log.info("  Text: " + summaryTxtPath);
        if (format.includesJson()) log.info("  JSON: " + summaryJsonPath);
        log.info("==========================================");
    }

    // ── Per-node processing ───────────────────────────────────────────────────

    private ObjectNode checkNodeCapabilities(
            String nodeName, String nodeId, String nodePid, String nodeEndpoint,
            String nodeLogo, String legalEntityName, String legalEntityRor) throws Exception {

        log.info("========================================");
        log.info("Node: " + nodeName);
        log.info("========================================");
        log.info("ID:           " + nodeId);
        log.info("PID:          " + nodePid);
        log.info("Endpoint:     " + nodeEndpoint);
        log.info("Legal Entity: " + legalEntityName);
        

        Path nodeDir = dashboardDir.resolve(nodeName);
        Files.createDirectories(nodeDir);
        Path reportJsonPath = nodeDir.resolve("endpoint_report.json");
        Path reportTxtPath  = nodeDir.resolve("endpoint_report.txt");

        // ── Fetch capabilities ────────────────────────────────────────────────

        log.info("Fetching capabilities for " + nodeName + "...");

        String capabilitiesBody;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(nodeEndpoint))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            capabilitiesBody = resp.body();
        } catch (Exception e) {
            log.warning("ERROR: Failed to fetch capabilities from " + nodeEndpoint + ": " + e.getMessage());
            
            // Return an empty summary for this node
            return buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                    legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportJsonPath);
        }

        JsonNode capsRoot;
        try {
            capsRoot = mapper.readTree(capabilitiesBody);
        } catch (IOException e) {
            log.warning("ERROR: Capabilities response from " + nodeEndpoint + " is not valid JSON");
            
            return buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                    legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportJsonPath);
        }

        log.info("Capabilities retrieved successfully!");
        
        log.info("Checking capabilities...");


        BufferedWriter reportTxtPathWriter = null;

        try {

             if (format.includesText()) {
                 reportTxtPathWriter = Files.newBufferedWriter(reportTxtPath);
                 var header = buildNodeTextHeader(nodeName, nodeId, nodePid,
                         nodeEndpoint, nodeLogo, legalEntityName, legalEntityRor);
                 reportTxtPathWriter.write(header);
             }


             // ── Check each capability ─────────────────────────────────────────────

             ArrayNode capabilitiesOut = mapper.createArrayNode();
             int total = 0;
             int available = 0;

             JsonNode capsArray = capsRoot.path("capabilities");
             if (capsArray.isArray()) {
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

                         System.out.printf("%s (HTTP %s)%n", status.label(), status.httpCode());

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
             }


             // ── Write per-node JSON report ────────────────────────────────────────

             ObjectNode nodeReport = buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                     legalEntityName, legalEntityRor, total, available, capabilitiesOut, reportJsonPath);


             if (format.includesJson()) {
                 mapper.writerWithDefaultPrettyPrinter().writeValue(reportJsonPath.toFile(), nodeReport);
             }


             log.info("Node reports generated:");
             if (format.includesText()) log.info("  Text: " + reportTxtPath);
             if (format.includesJson()) log.info("  JSON: " + reportJsonPath);


             return nodeReport;
        } finally {
            if (reportTxtPathWriter != null) {
                reportTxtPathWriter.close();
            }
        }
    }

    // ── HTTP probe ────────────────────────────────────────────────────────────

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
            return CapabilityStatus.of(-1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CapabilityStatus.of(-1);
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private ArrayNode extractNodesArray(JsonNode obj) {
        for (String key : List.of("nodes", "results", "data")) {
            JsonNode candidate = obj.get(key);
            if (candidate != null && candidate.isArray()) return (ArrayNode) candidate;
        }
        // Last resort: the object itself (shouldn't happen but mirrors the shell fallback)
        log.warning("ERROR: Registry returned a JSON object but no recognised array key " +
                "(nodes/results/data) was found");
        throw new RuntimeException("Failed to fetch data from Node Registry (HTTP 401)");
    }

    private ObjectNode buildNodeSummary(
            String nodeName, String nodeId, String nodePid, String nodeEndpoint,
            String legalEntityName, String legalEntityRor,
            int total, int available, ArrayNode capabilities, Path reportPath) {

        ObjectNode n = mapper.createObjectNode();
        n.put("generated", nowIso());
        n.put("node_name", nodeName);
        n.put("node_id", nodeId);
        n.put("node_pid", nodePid);
        n.put("node_endpoint", nodeEndpoint);
        ObjectNode le = n.putObject("legal_entity");
        le.put("name", legalEntityName);
        le.put("ror_id", legalEntityRor);
        n.put("total_capabilities", total);
        n.put("available_capabilities", available);
        n.put("report_file", reportPath.toString());
        n.set("capabilities", capabilities);
        return n;
    }

    // ── Text report helpers ───────────────────────────────────────────────────

    private void printBanner() {
        log.info("==========================================");
        log.info("EOSC Beyond Node Registry");
        log.info("Endpoint Availability Report");
        log.info("==========================================");
        log.info("Generated: " + nowIso());
        log.info("Registry:  " + NODE_REGISTRY_URL);
        log.info("==========================================");
        
    }

    private void writeTextHeader(Path path) throws IOException {
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

    private String buildNodeTextHeader(
            String nodeName, String nodeId, String nodePid, String nodeEndpoint,
            String nodeLogo, String legalEntityName, String legalEntityRor) throws IOException {
        return """
                ==========================================
                Node: %s
                ==========================================
                Generated:          %s
                Node ID:            %s
                Node PID:           %s
                Node Endpoint:      %s
                Legal Entity:       %s
                Legal Entity ROR:   %s
                Logo:               %s
                ==========================================

                """.formatted(nodeName, nowIso(), nodeId, nodePid, nodeEndpoint,
                legalEntityName, legalEntityRor, nodeLogo);
    }

    private String appendCapabilityToText(String capType, String endpoint,
            String version, CapabilityStatus status) {
        return "%-40s %s (HTTP %s)%n  └─ Endpoint: %s%n  └─ Version: %s%n%n"
                .formatted(capType, status.label(), status.httpCode(), endpoint, version);
    }

    private void appendTextSummaryEntry(Path path, ObjectNode nodeSummary) throws IOException {
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

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }

    private static String nowIso() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ── Supporting types ──────────────────────────────────────────────────────

    enum OutputFormat {
        TEXT, JSON, BOTH;

        boolean includesText() { return this == TEXT || this == BOTH; }
        boolean includesJson() { return this == JSON || this == BOTH; }
    }

    /**
     * Represents the HTTP-probed status of a single capability endpoint.
     */
    record CapabilityStatus(int httpCode, boolean available) {
        static CapabilityStatus of(int code) {
            return switch (code) {
                case 0  -> new CapabilityStatus(-1, true);
                case 404  -> new CapabilityStatus(404, false);
                default -> {
                    if (code >= 200 && code < 400) {
                        yield new CapabilityStatus(code, true);
                    } else {
                        yield new CapabilityStatus(code, false);
                    }
                }
            };
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