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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Error: API_KEY is required.");
            System.err.println("Usage: CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]");
            System.exit(1);
        }

        String apiKey = args[0];

        OutputFormat format = OutputFormat.JSON;
        if (args.length >= 2) {
            format = switch (args[1].toLowerCase()) {
                case "text", "txt" -> OutputFormat.TEXT;
                case "json"        -> OutputFormat.JSON;
                case "both"        -> OutputFormat.BOTH;
                default -> {
                    System.err.println("Invalid format: " + args[1]);
                    System.err.println("Usage: CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]");
                    System.exit(1);
                    yield OutputFormat.JSON; // unreachable
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

        System.out.println("Fetching node list from registry...");

        HttpRequest registryRequest = HttpRequest.newBuilder()
                .uri(URI.create(NODE_REGISTRY_URL))
                .header("X-Api-Key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> registryResponse =
                http.send(registryRequest, HttpResponse.BodyHandlers.ofString());

        if (registryResponse.statusCode() != 200) {
            System.err.println("ERROR: Failed to fetch data from Node Registry" +
                    " (HTTP " + registryResponse.statusCode() + ")");
            System.exit(1);
        }

        JsonNode root;
        try {
            root = mapper.readTree(registryResponse.body());
        } catch (IOException e) {
            System.err.println("ERROR: Registry response is not valid JSON");
            System.err.println("Raw response: " + registryResponse.body());
            System.exit(1);
            return;
        }

        // Handle bare array or wrapped object
        ArrayNode nodesArray = switch (root.getNodeType()) {
            case ARRAY  -> (ArrayNode) root;
            case OBJECT -> extractNodesArray(root);
            default -> {
                System.err.println("ERROR: Unexpected JSON type from registry: " + root.getNodeType());
                System.exit(1);
                yield null; // unreachable
            }
        };

        System.out.println("Node registry data retrieved successfully!");
        System.out.println();

        // ── Initialise summary files ──────────────────────────────────────────

        Path summaryJsonPath = dashboardDir.resolve("node_registry_summary.json");
        Path summaryTxtPath  = dashboardDir.resolve("node_registry_summary.txt");

        if (format.includesText()) {
            writeTextHeader(summaryTxtPath);
        }

        List<ObjectNode> nodeSummaries = new ArrayList<>();

        // ── Process each node ─────────────────────────────────────────────────

        System.out.println("==========================================");
        System.out.println("Processing nodes from registry...");
        System.out.println("==========================================");
        System.out.println();

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
            Files.writeString(summaryJsonPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaryJson));
        }

        // ── Final output ──────────────────────────────────────────────────────

        System.out.println("==========================================");
        System.out.println("All nodes processed!");
        System.out.println("==========================================");
        System.out.println();
        System.out.println("Summary reports:");
        if (format.includesText()) System.out.println("  Text: " + summaryTxtPath);
        if (format.includesJson()) System.out.println("  JSON: " + summaryJsonPath);
        System.out.println("==========================================");
    }

    // ── Per-node processing ───────────────────────────────────────────────────

    private ObjectNode checkNodeCapabilities(
            String nodeName, String nodeId, String nodePid, String nodeEndpoint,
            String nodeLogo, String legalEntityName, String legalEntityRor) throws Exception {

        System.out.println("========================================");
        System.out.println("Node: " + nodeName);
        System.out.println("========================================");
        System.out.println("ID:           " + nodeId);
        System.out.println("PID:          " + nodePid);
        System.out.println("Endpoint:     " + nodeEndpoint);
        System.out.println("Legal Entity: " + legalEntityName);
        System.out.println();

        Path nodeDir = dashboardDir.resolve(nodeName);
        Files.createDirectories(nodeDir);
        Path reportJsonPath = nodeDir.resolve("endpoint_report.json");
        Path reportTxtPath  = nodeDir.resolve("endpoint_report.txt");

        if (format.includesText()) {
            writeNodeTextHeader(reportTxtPath, nodeName, nodeId, nodePid,
                    nodeEndpoint, nodeLogo, legalEntityName, legalEntityRor);
        }

        // ── Fetch capabilities ────────────────────────────────────────────────

        System.out.println("Fetching capabilities for " + nodeName + "...");

        String capabilitiesBody;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(nodeEndpoint))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            capabilitiesBody = resp.body();
        } catch (Exception e) {
            System.err.println("ERROR: Failed to fetch capabilities from " + nodeEndpoint + ": " + e.getMessage());
            if (format.includesText()) {
                Files.writeString(reportTxtPath,
                        Files.readString(reportTxtPath) +
                        "ERROR: Failed to fetch capabilities from endpoint\n");
            }
            System.out.println();
            // Return an empty summary for this node
            return buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                    legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportJsonPath);
        }

        JsonNode capsRoot;
        try {
            capsRoot = mapper.readTree(capabilitiesBody);
        } catch (IOException e) {
            System.err.println("ERROR: Capabilities response from " + nodeEndpoint + " is not valid JSON");
            System.out.println();
            return buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                    legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportJsonPath);
        }

        System.out.println("Capabilities retrieved successfully!");
        System.out.println();
        System.out.println("Checking capabilities...");
        System.out.println();

        // ── Check each capability ─────────────────────────────────────────────

        ArrayNode capabilitiesOut = mapper.createArrayNode();
        int total = 0;
        int available = 0;

        JsonNode capsArray = capsRoot.path("capabilities");
        if (capsArray.isArray()) {
            for (JsonNode cap : capsArray) {
                String capType  = cap.path("capability_type").asText();
                String endpoint = cap.path("endpoint").asText();
                String version  = cap.path("version").asText();

                System.out.printf("  %-35s ", capType);

                CapabilityStatus status = probeEndpoint(endpoint);
                total++;
                if (status == CapabilityStatus.AVAILABLE) available++;

                System.out.printf("%s (HTTP %s)%n", status.label(), status.httpCode());

                if (format.includesText()) {
                    appendCapabilityToText(reportTxtPath, capType, endpoint, version, status);
                }

                ObjectNode capOut = mapper.createObjectNode();
                capOut.put("capability_type", capType);
                capOut.put("endpoint", endpoint);
                capOut.put("version", version);
                capOut.put("status", status.label());
                capOut.put("http_code", status.httpCode());
                capabilitiesOut.add(capOut);
            }
        }

        // ── Write per-node JSON report ────────────────────────────────────────

        ObjectNode nodeReport = buildNodeSummary(nodeName, nodeId, nodePid, nodeEndpoint,
                legalEntityName, legalEntityRor, total, available, capabilitiesOut, reportJsonPath);

        if (format.includesJson()) {
            Files.writeString(reportJsonPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nodeReport));
        }

        System.out.println();
        System.out.println("Node reports generated:");
        if (format.includesText()) System.out.println("  Text: " + reportTxtPath);
        if (format.includesJson()) System.out.println("  JSON: " + reportJsonPath);
        System.out.println();

        return nodeReport;
    }

    // ── HTTP probe ────────────────────────────────────────────────────────────

    private CapabilityStatus probeEndpoint(String url) {
        if (url == null || url.isBlank()) return CapabilityStatus.of("000");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(CAPABILITY_CHECK_TIMEOUT)
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return CapabilityStatus.of(String.valueOf(resp.statusCode()));
        } catch (Exception e) {
            return CapabilityStatus.of("000");
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private ArrayNode extractNodesArray(JsonNode obj) {
        for (String key : List.of("nodes", "results", "data")) {
            JsonNode candidate = obj.get(key);
            if (candidate != null && candidate.isArray()) return (ArrayNode) candidate;
        }
        // Last resort: the object itself (shouldn't happen but mirrors the shell fallback)
        System.err.println("ERROR: Registry returned a JSON object but no recognised array key " +
                "(nodes/results/data) was found");
        System.exit(1);
        return null; // unreachable
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
        System.out.println("==========================================");
        System.out.println("EOSC Beyond Node Registry");
        System.out.println("Endpoint Availability Report");
        System.out.println("==========================================");
        System.out.println("Generated: " + nowIso());
        System.out.println("Registry:  " + NODE_REGISTRY_URL);
        System.out.println("==========================================");
        System.out.println();
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

    private void writeNodeTextHeader(Path path,
            String nodeName, String nodeId, String nodePid, String nodeEndpoint,
            String nodeLogo, String legalEntityName, String legalEntityRor) throws IOException {
        String header = """
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
        Files.writeString(path, header);
    }

    private void appendCapabilityToText(Path path, String capType, String endpoint,
            String version, CapabilityStatus status) throws IOException {
        String entry = "%-40s %s (HTTP %s)%n  └─ Endpoint: %s%n  └─ Version: %s%n%n"
                .formatted(capType, status.label(), status.httpCode(), endpoint, version);
        Files.writeString(path, entry, java.nio.file.StandardOpenOption.APPEND);
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
    record CapabilityStatus(String httpCode, String label) {

        public static final CapabilityStatus AVAILABLE = null;

        static CapabilityStatus of(String code) {
            return switch (code) {
                case "000"  -> new CapabilityStatus("000", "Not available");
                case "404"  -> new CapabilityStatus("404", "Not found");
                default     -> {
                    int c = Integer.parseInt(code);
                    yield (c >= 200 && c < 400)
                            ? new CapabilityStatus(code, "Available")
                            : new CapabilityStatus(code, "Not available");
                }
            };
        }
    }
}