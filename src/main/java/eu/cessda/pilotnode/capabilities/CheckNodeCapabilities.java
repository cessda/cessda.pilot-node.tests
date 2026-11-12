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

package eu.cessda.pilotnode.capabilities;

import eu.cessda.pilotnode.HttpUtils;
import eu.cessda.pilotnode.JobRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cessda.pilotnode.capabilities.lenient.LenientNodeCapabilities;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.capabilities.StrictNodeCapabilities;

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

    public static final String NODE_REGISTRY_URL =
            "https://node-devel.eosc.grnet.gr/federation-backend/tenants/eosc-beyond/nodes";

    /** Base delay between attempts (attempt number times this). */
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final Logger log = Logger.getLogger(CheckNodeCapabilities.class.getName());

    private final EnumSet<OutputFormat> format;

    // ── Fields ────────────────────────────────────────────────────────────────

    private CheckNodeCapabilities(EnumSet<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper,
                                  Duration retryDelay, CheckMode mode) {
        this.retryDelay = retryDelay;
        this.mode = mode;
        // Each mode keeps a node's results in its own folder (see CheckMode#nodeDir), so that one never overwrites the other.
        this.dashboardDir = dashboardDir;
        this.http = http;
        this.mapper = mapper;
        this.format = format;
    }
    private final Duration retryDelay;
    private final CheckMode mode;
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
        HttpClient http = HttpUtils.httpClient();

        run(apiKey, format, dashboardDir, http, mapper);
    }

    /**
     * Outcome of a run: how many nodes the registry listed, and which of them could not be
     * processed. A node that failed is still listed in {@code node_registry_summary.json}, with an
     * {@code error} field and no capabilities, so that it stays visible on the dashboard.
     */
    public record Result(int totalNodes, List<NodeProblem> problems) {
        public Result {
            problems = List.copyOf(problems);
        }

        /** How many problem nodes {@link #message()} names. */
        private static final int NAMED_PROBLEMS = 5;

        /** The longest reason {@link #message()} gives for a single problem node. */
        private static final int REASON_CHARS = 100;

        /**
         * A short human-readable message, suitable for {@code JobRecord.markDone(...)}. One problem node is named with
         * its (shortened) reason; several are only named (the first few). Every node's reason is in its entry of
         * {@code node_registry_summary.json}.
         */
        public String message() {
            if (problems.isEmpty()) {
                return "node_registry_summary.json written: %d node(s)".formatted(totalNodes);
            }
            var sb = new StringBuilder("node_registry_summary.json written: %d node(s), %d with problems: "
                    .formatted(totalNodes, problems.size()));
            if (problems.size() == 1) {
                String reason = problems.get(0).reason();
                sb.append(problems.get(0).node()).append(" (")
                        .append(reason.length() > REASON_CHARS ? reason.substring(0, REASON_CHARS - 1).trim() + "…" : reason)
                        .append(')');
                return sb.toString();
            }
            for (int i = 0; i < Math.min(problems.size(), NAMED_PROBLEMS); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(problems.get(i).node());
            }
            if (problems.size() > NAMED_PROBLEMS) {
                sb.append(" and ").append(problems.size() - NAMED_PROBLEMS).append(" more");
            }
            return sb.toString();
        }
    }

    /** A node that the registry listed but whose capabilities could not be checked. */
    public record NodeProblem(String node, String reason) {}

    @SuppressWarnings("java:S6201")
    public static Result run(String apiKey, Set<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper) throws IOException {
        return run(apiKey, format, dashboardDir, http, mapper, URI.create(NODE_REGISTRY_URL));
    }

    public static Result run(String apiKey, Set<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper,
                      URI registry) throws IOException {
        return run(apiKey, format, dashboardDir, http, mapper, registry, DEFAULT_RETRY_DELAY);
    }

    /** Runs the check in the given mode against the Node Registry. */
    public static Result run(String apiKey, Set<OutputFormat> format, Path dashboardDir, HttpClient http,
                             ObjectMapper mapper, CheckMode mode) throws IOException {
        return run(apiKey, format, dashboardDir, http, mapper, URI.create(NODE_REGISTRY_URL), DEFAULT_RETRY_DELAY, mode);
    }

    public static Result run(String apiKey, Set<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper,
                      URI registry, Duration retryDelay) throws IOException {
        return run(apiKey, format, dashboardDir, http, mapper, registry, retryDelay, CheckMode.LENIENT);
    }

    @SuppressWarnings("java:S6201")
    public static Result run(String apiKey, Set<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper,
                      URI registry, Duration retryDelay, CheckMode mode) throws IOException {

        printBanner();

        // Copy formats to an EnumSet if they are not already
        EnumSet<OutputFormat> formatEnumSet;
        if (format instanceof EnumSet<OutputFormat>) {
            formatEnumSet = (EnumSet<OutputFormat>) format;
        } else {
            formatEnumSet = EnumSet.copyOf(format);
        }

        return new CheckNodeCapabilities(formatEnumSet, dashboardDir, http, mapper, retryDelay, mode).check(apiKey, registry);
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

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private Result check(String apiKey, URI registry) throws IOException {
        // ── Fetch node list ───────────────────────────────────────────────────

        log.info("Fetching node list from registry...");

        HttpRequest registryRequest = HttpRequest.newBuilder()
                .uri(registry)
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
            throw new IOException("Interrupted while fetching the node list", e);
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

        Files.createDirectories(dashboardDir);
        Path summaryJsonPath = dashboardDir.resolve("node_registry_summary.json");
        Path summaryTxtPath  = dashboardDir.resolve("node_registry_summary.txt");
        LenientNodeCapabilities lenientCheck = new LenientNodeCapabilities(format, dashboardDir, http, mapper, retryDelay);
        StrictNodeCapabilities strictCheck = new StrictNodeCapabilities(http, mapper, dashboardDir, retryDelay,
                format.contains(OutputFormat.JSON));

        if (format.contains(OutputFormat.TEXT) && mode != CheckMode.STRICT) {
            LenientNodeCapabilities.writeTextHeader(summaryTxtPath);
        }

        List<ObjectNode> nodeSummaries = new ArrayList<>();
        List<NodeProblem> problems = new ArrayList<>();

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

            ObjectNode summary;
            try {
                summary = mode == CheckMode.STRICT
                        ? strictCheck.check(new StrictNodeCapabilities.Node(nodeName, nodeId, nodePid, nodeEndpointString,
                                nodeLogo, legalEntityName, legalEntityRor))
                        : lenientCheck.check(
                                nodeName, nodeId, nodePid, new URI(nodeEndpointString),
                                nodeLogo, legalEntityName, legalEntityRor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while checking node " + nodeName, e);
            } catch (URISyntaxException | IOException | RuntimeException e) {
                // One bad node must not hide the others, nor stop the summary being written:
                // record it with an error so that it stays visible.
                String reason = describe(e);
                log.log(Level.SEVERE, "Node {0} could not be checked: {1}", new Object[]{nodeName, reason});
                summary = failedNodeSummary(mapper, mode, dashboardDir, nodeName, nodeId, nodePid, nodeEndpointString, nodeLogo,
                        legalEntityName, legalEntityRor, reason);
                writeFailureReport(nodeName, summary);
            }

            if (summary.hasNonNull("error")) {
                String reason = summary.get("error").asText();
                if (summary.path("capabilities_stale").asBoolean(false)) {
                    reason += "; showing capabilities last fetched " + summary.path("capabilities_last_fetched").asText("earlier");
                }
                problems.add(new NodeProblem(nodeName, reason));
            }
            if (!summary.hasNonNull("error") && "FAIL".equals(summary.path("strict_result").asText())) {
                problems.add(new NodeProblem(nodeName, strictReason(summary)));
            }
            nodeSummaries.add(summary);

            if (format.contains(OutputFormat.TEXT) && mode != CheckMode.STRICT) {
                LenientNodeCapabilities.appendTextSummaryEntry(summaryTxtPath, summary);
            }
        }

        // ── Write JSON summary ────────────────────────────────────────────────

        if (format.contains(OutputFormat.JSON)) {
            ObjectNode summaryJson = mapper.createObjectNode();
            summaryJson.put("generated", nowIso());
            summaryJson.put("registry_source", NODE_REGISTRY_URL);
            // The list of nodes only: it is the same whichever mode ran, so there is one copy. What each mode found for
            // a node is in the node's own reports.
            ArrayNode nodesOut = summaryJson.putArray("nodes");
            nodeSummaries.forEach(s -> nodesOut.add(registryEntry(s)));
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

        return new Result(nodeSummaries.size(), problems);
    }

    // ── Text report helpers ───────────────────────────────────────────────────

    /** What the Node Registry says about a node (not what a check found), as {@code node_registry_summary.json} lists it. */
    private ObjectNode registryEntry(ObjectNode summary) {
        ObjectNode n = mapper.createObjectNode();
        for (String field : List.of("node_name", "node_id", "node_pid", "node_endpoint", "logo")) {
            n.set(field, summary.path(field).isMissingNode() ? mapper.nullNode() : summary.get(field));
        }
        n.set("legal_entity", summary.path("legal_entity").isMissingNode() ? mapper.nullNode() : summary.get("legal_entity"));
        return n;
    }

    /** A node entry with no capabilities and an {@code error} explaining why. */
    public static ObjectNode failedNodeSummary(ObjectMapper mapper, CheckMode mode, Path dashboardDir,
            String nodeName, String nodeId, String nodePid, String nodeEndpoint, String nodeLogo,
            String legalEntityName, String legalEntityRor, String reason) {
        Path reportPath = mode.nodeDir(dashboardDir, nodeName).resolve("endpoint_report.json");
        ObjectNode n = buildNodeSummary(mapper, mode, nodeName, nodeId, nodePid, nodeEndpoint, nodeLogo,
                legalEntityName, legalEntityRor, 0, 0, mapper.createArrayNode(), reportPath);
        n.put("error", reason);
        return n;
    }

    public static ObjectNode buildNodeSummary(ObjectMapper mapper, CheckMode mode,
            
            String nodeName, String nodeId, String nodePid, String nodeEndpoint, String nodeLogo,
            String legalEntityName, String legalEntityRor,
            int total, int available, ArrayNode capabilities, Path reportPath) {

        ObjectNode n = mapper.createObjectNode();
        n.put("generated", nowIso());
        n.put("mode", mode.label());
        n.put("node_name", nodeName);
        n.put("node_id", nodeId);
        n.put("node_pid", nodePid);
        n.put("node_endpoint", nodeEndpoint);
        n.put("logo", nodeLogo == null || nodeLogo.isBlank() ? null : nodeLogo);
        ObjectNode le = n.putObject("legal_entity");
        le.put("name", legalEntityName);
        le.put("ror_id", legalEntityRor);
        n.put("total_capabilities", total);
        n.put("available_capabilities", available);
        n.put("report_file", reportPath.toString().replace('\\', '/'));
        n.set("capabilities", capabilities);
        return n;
    }

    /**
     * Replaces the node's own {@code endpoint_report.json} with the failure, so that its detail page
     * does not keep showing the result of an earlier, successful run. Best effort: if the report
     * cannot be written the summary entry still records the problem.
     */
    private void writeFailureReport(String nodeName, ObjectNode report) {
        if (!format.contains(OutputFormat.JSON) || nodeName.isBlank()) {
            return;
        }
        try {
            Path nodeDir = mode.nodeDir(dashboardDir, nodeName);
            Files.createDirectories(nodeDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(nodeDir.resolve("endpoint_report.json").toFile(), report);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not write the failure report for {0}: {1}", new Object[]{nodeName, e});
        }
    }

    /** One line saying why a strict check failed: how many errors, and the first. */
    private static String strictReason(ObjectNode summary) {
        long errors = summary.path("findings_error").asLong();
        String first = "";
        for (JsonNode finding : summary.path("findings")) {
            if ("ERROR".equals(finding.path("severity").asText())) {
                first = ", first: " + finding.path("message").asText();
                break;
            }
        }
        return "strict check failed with " + errors + " error finding(s)" + first;
    }

    public static String describe(Exception e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    public static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }

    public static String nowIso() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ── Supporting types ──────────────────────────────────────────────────────

    public enum OutputFormat {TEXT, JSON}

}