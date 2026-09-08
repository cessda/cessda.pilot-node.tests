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
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Front Office Cross-Visibility Checker.
 *
 * <p>Covers the Proposed Validation Metrics that depend on a Node's Front
 * Office (Discovery Hub) surfacing services filtered by Node PID:</p>
 *
 * <ul>
 *   <li>Metric 4  — this Node's own resources appear in its own Front Office</li>
 *   <li>Metric 5  — a peer Node's Exchange service is visible in this Node's
 *       Front Office</li>
 *   <li>Metric 6  — this Node's Exchange service is visible from the
 *       Sandbox Front Office</li>
 *   <li>Metric 9  — this Node's Research Output is visible in the Sandbox
 *       Discovery Hub</li>
 *   <li>Metric 10 — reported as an alias of Metric 4. Both metrics were
 *       only distinct while services were onboarded centrally and
 *       propagated out to each Node's Front Office; now that onboarding is
 *       local to each Node, they are mechanically identical, so Metric 10
 *       is not queried separately.</li>
 *   <li>Metric 11 — this Node's Exchange service is visible in a peer
 *       Node's Front Office</li>
 * </ul>
 *
 * <p>Front Office endpoints are resolved from the {@code capability_type}
 * entries already written to each Node's {@code endpoint_report.json} by
 * {@link CheckNodeCapabilities} — no direct Node Registry call is made
 * here. This means {@code CheckNodeCapabilities} must have been run first
 * (or {@code node_registry_summary.json} and the per-Node
 * {@code endpoint_report.json} files must otherwise already be present and
 * up to date) before this check is run.</p>
 *
 * <p>The Sandbox Front Office is not a separate configuration value: the
 * Sandbox is itself registered in the Node Registry (see
 * {@link #SANDBOX_NODE_NAME}), so its Front Office endpoint is resolved
 * from its own {@code endpoint_report.json} in exactly the same way as any
 * other Node's. It is excluded from the peer list used for Metrics 5 and
 * 11, since those metrics concern visibility across "networked Pilot
 * Nodes" specifically, not the central Sandbox.</p>
 *
 * <p>Query URL shape, per Node:</p>
 * <pre>
 *   {front_office_base}/federation/services?q=&amp;nodes%5B%5D={url-encoded PID}
 * </pre>
 *
 * <p>The {@code /federation} path element (see
 * {@link #FEDERATION_PATH_SEGMENT}) may be removed from this API in the
 * near future — it is isolated here as a single constant so that change is
 * a one-line fix.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   CheckOtherMetrics NODE_NAME [dashboard_dir]
 * </pre>
 */
public class CheckOtherMetrics {

    // ── Constants ─────────────────────────────────────────────────────────

    /**
     * The capability_type value used to identify a Node's Front Office /
     * Discovery Hub endpoint in endpoint_report.json.
     */
    static final String FRONT_OFFICE_CAPABILITY_TYPE = "Front Office";

    /**
     * The node_name under which the Sandbox is registered in the Node
     * Registry (and therefore the directory name under dashboardDir where
     * its endpoint_report.json is found). Confirmed: PID
     * {@code 21.T15999/EOSC-BEYOND}.
     */
    static final String SANDBOX_NODE_NAME = "EOSC-Beyond";

    /**
     * Path element inserted between the Front Office base URL and
     * "/services". May be removed in future — isolated here so that
     * change only needs to be made in one place.
     */
    static final String FEDERATION_PATH_SEGMENT = "/federation";

    private static final Logger log = Logger.getLogger(CheckOtherMetrics.class.getName());

    // ANSI colour codes
    private static final String RED    = "\033[0;31m";
    private static final String GREEN  = "\033[0;32m";
    private static final String YELLOW = "\033[1;33m";
    private static final String NC     = "\033[0m";

    private final HttpUtils http;
    private final ObjectMapper mapper;

    public CheckOtherMetrics(HttpUtils http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {

        if (args.length < 1) {
            System.err.println("Error: NODE_NAME is required");
            System.err.println("Usage: java CheckOtherMetrics <NODE_NAME> [<dashboard_dir>]");
            System.exit(-1);
        }

        String nodeName = args[0];
        String dashboardDir = args.length >= 2 ? args[1] : "../dashboard/data";

        // Validate that nodeName contains no directory elements
        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() != 1 || nodeNamePath.isAbsolute()) {
            throw new IllegalArgumentException("nodeName must be a file name");
        }

        run(Path.of(dashboardDir), nodeName, new HttpUtils(), new ObjectMapper());
    }

    /**
     * Substitutes the literal {@code <NODE_NAME>} placeholder used in the
     * Proposed Validation Metrics descriptions with the actual Node name.
     * Needed both for readability (a Node's real name is more useful than
     * a template placeholder) and correctness: left unsubstituted,
     * {@code <NODE_NAME>} is indistinguishable from an HTML tag to a
     * browser rendering the description directly, and gets silently
     * dropped along with anything that looked like an attribute inside it
     * (e.g. the {@code 's} immediately after it in Metric 5's text).
     */
    private static String withNodeName(String template, String nodeName) {
        return template.replace("<NODE_NAME>", nodeName);
    }

    // ── Main logic ────────────────────────────────────────────────────────

    public static void run(Path dashboardDir, String nodeName, HttpUtils http, ObjectMapper mapper) throws IOException {

        Path outputDir = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportPath = outputDir.resolve("front_office_metrics_report.json");

        new CheckOtherMetrics(http, mapper).runInternal(dashboardDir, nodeName, reportPath);
    }

    private void runInternal(Path dashboardDir, String nodeName, Path reportPath) throws IOException {
        log.log(Level.INFO, """
                        Front Office Cross-Visibility Report
                        Generated: {0}
                        Node Name: {1}""",
                new Object[]{Instant.now(), nodeName}
        );

        // ── Load registry + resolve PIDs ────────────────────────────────
        Map<String, String> registryPids = readRegistryPids(dashboardDir);

        String ownPid = registryPids.get(nodeName);
        if (ownPid == null || ownPid.isBlank()) {
            throw new IOException("Node '" + nodeName + "' was not found in "
                    + "node_registry_summary.json, or has no node_pid. Run "
                    + "CheckNodeCapabilities first.");
        }

        // Peers for Metrics 5/11 exclude both this Node and the Sandbox:
        // the Sandbox is the central network Node, not a "networked Pilot
        // Node" in the sense those two metrics mean.
        List<Map.Entry<String, String>> peers = new ArrayList<>();
        for (var entry : registryPids.entrySet()) {
            if (!entry.getKey().equals(nodeName)
                    && !entry.getKey().equals(SANDBOX_NODE_NAME)
                    && !entry.getValue().isBlank()) {
                peers.add(entry);
            }
        }
        log.log(Level.INFO, "Resolved {0} peer Node(s) for cross-visibility checks", peers.size());

        // ── Resolve own and Sandbox Front Office endpoints ──────────────
        URI ownFrontOffice = readFrontOfficeEndpoint(dashboardDir, nodeName, mapper);
        URI sandboxFrontOfficeUrl = readFrontOfficeEndpoint(dashboardDir, SANDBOX_NODE_NAME, mapper);
        if (sandboxFrontOfficeUrl == null) {
            log.log(Level.WARNING, "No Front Office endpoint found for Sandbox Node '{0}' "
                    + "— Metrics 6 and 9 will be reported as Not reported.", SANDBOX_NODE_NAME);
        }

        ArrayNode metricsOut = mapper.createArrayNode();

        // ── Metric 4: own resources visible in own Front Office ────────
        MetricResult metric4;
        if (ownFrontOffice == null) {
            metric4 = MetricResult.notReported();
            printLine(4, "Own resources in own Front Office", metric4);
        } else {
            metric4 = queryFrontOffice(ownFrontOffice, ownPid);
            printLine(4, "Own resources in own Front Office", metric4);
        }
        ObjectNode metric4Entry = buildMetricEntry(4,
                withNodeName("<NODE_NAME> resources appear in the Node's Front Office.", nodeName),
                "own", nodeName, ownPid, metric4, null);

        // ── Metric 5: peer resources visible in own Front Office ───────
        ArrayNode metric5Peers = mapper.createArrayNode();
        boolean metric5AnyVisible = false;
        if (ownFrontOffice == null) {
            log.warning("Metric 5 skipped: own Front Office endpoint not found");
        } else {
            for (var peer : peers) {
                MetricResult r = queryFrontOffice(ownFrontOffice, peer.getValue());
                printLine(5, "Peer '" + peer.getKey() + "' visible in own Front Office", r);
                metric5AnyVisible |= r.visible();
                metric5Peers.add(buildPeerEntry(mapper, peer.getKey(), peer.getValue(), r));
            }
        }
        ObjectNode metric5Entry = buildMetricEntry(5,
                withNodeName("An Exchange service from another networked Pilot Node is "
                        + "visible in the <NODE_NAME> Front Office.", nodeName),
                "own", nodeName, null, null, metric5Peers);
        metric5Entry.put("visible", metric5AnyVisible);

        // ── Metric 6: own resources visible in Sandbox Front Office ────
        MetricResult metric6 = sandboxFrontOfficeUrl == null
                ? MetricResult.notReported()
                : queryFrontOffice(sandboxFrontOfficeUrl, ownPid);
        printLine(6, "Own resources in Sandbox Front Office", metric6);
        ObjectNode metric6Entry = buildMetricEntry(6,
                withNodeName("One or more <NODE_NAME> Exchange services are visible and accessible "
                        + "from the Sandbox Front Office.", nodeName),
                "sandbox", SANDBOX_NODE_NAME, ownPid, metric6, null);

        // ── Metric 9: own Research Output visible in Sandbox Discovery Hub
        MetricResult metric9 = sandboxFrontOfficeUrl == null
                ? MetricResult.notReported()
                : queryResearchOutputVisibility(sandboxFrontOfficeUrl, ownPid);
        printLine(9, "Own Research Output in Sandbox Discovery Hub", metric9);
        ObjectNode metric9Entry = buildMetricEntry(9,
                withNodeName("At least one <NODE_NAME> Research Output is visible "
                        + "in the Sandbox Discovery Hub.", nodeName),
                "sandbox", SANDBOX_NODE_NAME, ownPid, metric9, null);

        // ── Metric 10: alias of Metric 4 ────────────────────────────────
        ObjectNode metric10Entry = buildMetricEntry(10,
                withNodeName("An Exchange service onboarded in the <NODE_NAME> Service "
                        + "Catalogue is visible in the <NODE_NAME> Front Office.", nodeName),
                "own", nodeName, ownPid, metric4, null);
        metric10Entry.put("alias_of_metric", 4);
        metric10Entry.put("alias_note", "Mechanically identical to Metric 4 now that "
                + "services are onboarded locally by each Node rather than "
                + "centrally and propagated. Not queried separately.");
        printLine(10, "(alias of Metric 4 — not queried separately)", metric4);

        // ── Metric 11: own resources visible in peer Front Offices ─────
        ArrayNode metric11Peers = mapper.createArrayNode();
        boolean metric11AnyVisible = false;
        for (var peer : peers) {
            URI peerFrontOffice = readFrontOfficeEndpoint(dashboardDir, peer.getKey(), mapper);
            MetricResult r;
            if (peerFrontOffice == null) {
                r = MetricResult.notReported();
            } else {
                r = queryFrontOffice(peerFrontOffice, ownPid);
            }
            printLine(11, "Own resources visible in peer '" + peer.getKey() + "'", r);
            metric11AnyVisible |= r.visible();
            metric11Peers.add(buildPeerEntry(mapper, peer.getKey(), peerFrontOffice, r));
        }
        ObjectNode metric11Entry = buildMetricEntry(11,
                withNodeName("An Exchange service onboarded in the <NODE_NAME> Service "
                        + "Catalogue is visible in another networked Pilot "
                        + "Node's Front Office.", nodeName),
                "peer", nodeName, null, null, metric11Peers);
        metric11Entry.put("visible", metric11AnyVisible);

        // Appended in numerical order — this is the only place display
        // order is decided, independent of the (dependency-driven) order
        // each metric was computed in above.
        metricsOut.add(metric4Entry);
        metricsOut.add(metric5Entry);
        metricsOut.add(metric6Entry);
        metricsOut.add(metric9Entry);
        metricsOut.add(metric10Entry);
        metricsOut.add(metric11Entry);

        // ── Write report ─────────────────────────────────────────────────
        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name", nodeName);
        report.put("node_pid", ownPid);
        report.put("front_office_endpoint",
                ownFrontOffice != null ? ownFrontOffice.toString() : null);
        report.put("sandbox_node_name", SANDBOX_NODE_NAME);
        report.put("sandbox_front_office_endpoint",
                sandboxFrontOfficeUrl != null ? sandboxFrontOfficeUrl.toString() : null);
        report.set("metrics", metricsOut);

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        log.log(Level.INFO, "Report generated: JSON: {0}", reportPath.toAbsolutePath());
    }

    // ── Registry / endpoint report readers ──────────────────────────────

    /**
     * Reads {@code node_registry_summary.json} (written by
     * {@link CheckNodeCapabilities}) and returns a map of node name to
     * node PID, in registry order.
     */
    private Map<String, String> readRegistryPids(Path dashboardDir)
            throws IOException {
        Path registryPath = dashboardDir.resolve("node_registry_summary.json");
        if (!Files.exists(registryPath)) {
            throw new IOException("node_registry_summary.json not found in "
                    + dashboardDir + " — run CheckNodeCapabilities first.");
        }

        Map<String, String> result = new LinkedHashMap<>();
        JsonNode root = mapper.readTree(registryPath.toFile());
        for (JsonNode node : root.path("nodes")) {
            String name = node.path("node_name").asText();
            String pid  = node.path("node_pid").asText();
            if (!name.isBlank()) {
                result.put(name, pid);
            }
        }
        return result;
    }

    /**
     * Reads {@code <dashboardDir>/<nodeName>/endpoint_report.json} (written
     * by {@link CheckNodeCapabilities}) and returns the endpoint URI for
     * the capability whose {@code capability_type} matches
     * {@link #FRONT_OFFICE_CAPABILITY_TYPE}, or {@code null} if the report
     * is missing or that capability is not reported for this Node.
     */
    private static URI readFrontOfficeEndpoint(Path dashboardDir, String nodeName, ObjectMapper mapper)
            throws IOException {
        Path reportPath = dashboardDir.resolve(nodeName).resolve("endpoint_report.json");
        if (!Files.exists(reportPath)) {
            log.log(Level.WARNING, "endpoint_report.json not found for {0}", nodeName);
            return null;
        }

        JsonNode root = mapper.readTree(reportPath.toFile());
        for (JsonNode cap : root.path("capabilities")) {
            if (FRONT_OFFICE_CAPABILITY_TYPE.equalsIgnoreCase(cap.path("capability_type").asText())) {
                String endpoint = cap.path("endpoint").asText();
                if (!endpoint.isBlank()) {
                    try {
                        return new URI(endpoint);
                    } catch (URISyntaxException e) {
                        log.log(Level.WARNING, "Front Office endpoint for {0} is not a valid URI: {1}",
                                new Object[]{nodeName, e.getMessage()});
                        return null;
                    }
                }
            }
        }
        log.log(Level.WARNING, "No ''{0}'' capability reported for {1}",
                new Object[]{FRONT_OFFICE_CAPABILITY_TYPE, nodeName});
        return null;
    }

    // ── Front Office query ───────────────────────────────────────────────

    /**
     * Strips any trailing slash from {@code frontOfficeBase}, then builds
     * the federation services query URL for {@code pid}:
     * <pre>{@code {base}/federation/services?q=&nodes%5B%5D={url-encoded pid}}</pre>
     */
    static URI buildFrontOfficeQueryUrl(URI frontOfficeBase, String pid) {
        String base = frontOfficeBase.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String encodedPid = URLEncoder.encode(pid, StandardCharsets.UTF_8);
        return URI.create(base + FEDERATION_PATH_SEGMENT + "/services?q=&nodes%5B%5D=" + encodedPid);
    }

    /**
     * Metric 9: builds the Research Product query URL for a Discovery Hub
     * — used against the Sandbox to check whether a Node's Research Output
     * is visible there. Unlike {@link #buildFrontOfficeQueryUrl}, there is
     * no {@link #FEDERATION_PATH_SEGMENT} in this path, and it adds a
     * {@code research_activities} filter flag alongside the same
     * {@code nodes[]} PID filter used everywhere else:
     * <pre>{@code {base}/services?sort=_score&research_activities&nodes%5B%5D={url-encoded pid}}</pre>
     *
     * <p>Confirmed against two live examples
     * ({@code enes-discovery-hub.pilot.eosc-beyond.eu} and
     * {@code marketplace-staging.cessda.eu}) — {@code research_activities}
     * takes no value; it's a bare filter flag, not
     * {@code research_activities[]=<id>} as in an earlier draft of this
     * metric.</p>
     */
    static URI buildResearchOutputQueryUrl(URI discoveryHubBase, String pid) {
        String base = discoveryHubBase.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String encodedPid = URLEncoder.encode(pid, StandardCharsets.UTF_8);
        return URI.create(base + "/services?sort=_score&research_activities&nodes%5B%5D=" + encodedPid);
    }

    /**
     * Queries a Front Office for Exchange services filtered to
     * {@code targetPid} and returns whether any results were found.
     */
    private MetricResult queryFrontOffice(URI frontOfficeBase, String targetPid) {
        var frontOfficeQueryUrl = buildFrontOfficeQueryUrl(frontOfficeBase, targetPid);
        return executeVisibilityQuery(frontOfficeQueryUrl);
    }

    /**
     * Queries a Discovery Hub for Research Outputs filtered to
     * {@code targetPid} (Metric 9) and returns whether any results were
     * found. Shares {@link #executeVisibilityQuery} with
     * {@link #queryFrontOffice} — the two report shapes
     * ({@code total}/{@code results}) are identical, only the query URL
     * differs.
     */
    private MetricResult queryResearchOutputVisibility(URI discoveryHubBase, String targetPid) {
        var researchOutputQueryUrl = buildResearchOutputQueryUrl(discoveryHubBase, targetPid);
        return executeVisibilityQuery(researchOutputQueryUrl);
    }

    private MetricResult executeVisibilityQuery(URI queryUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(queryUrl)
                .header("accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

            JsonNode root;
            try (InputStream body = response.body()) {
                root = mapper.readTree(body);
            }

            long total = root.path("total").asLong(-1);
            long resultCount = total >= 0 ? total : root.path("results").size();
            boolean visible = resultCount > 0;

            return new MetricResult(queryUrl, response.statusCode(), resultCount, visible,
                    visible ? "Available" : "Not visible", null);

        } catch (HTTPException e) {
            return MetricResult.error(queryUrl, e.getResponse().statusCode(), "HTTP " + e.getResponse().statusCode());
        } catch (IOException e) {
            return MetricResult.error(queryUrl, null, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MetricResult.error(queryUrl, null, "Interrupted");
        }
    }

    // ── JSON entry builders ──────────────────────────────────────────────

    private ObjectNode buildMetricEntry(
            int metric, String description,
            String frontOfficeQueried, String frontOfficeOwner, String targetPid,
            MetricResult result, ArrayNode peerResults) {

        ObjectNode entry = mapper.createObjectNode();
        entry.put("metric", metric);
        entry.put("description", description);
        entry.put("front_office_queried", frontOfficeQueried);
        entry.put("front_office_owner", frontOfficeOwner);
        if (targetPid != null) {
            entry.put("target_pid", targetPid);
        }
        if (result != null) {
            entry.put("query_url", result.queryUrl() != null ? result.queryUrl().toString() : null);
            entry.put("result_count", result.resultCount());
            entry.put("visible", result.visible());
            entry.put("status", result.status());
            if (result.httpCode() != null) {
                entry.put("http_code", result.httpCode());
            } else {
                entry.putNull("http_code");
            }
        }
        if (peerResults != null) {
            entry.set("peer_results", peerResults);
        }
        return entry;
    }

    private static ObjectNode buildPeerEntry(
            ObjectMapper mapper, String peerName, Object peerFrontOfficeOrPid, MetricResult result) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("peer_node", peerName);
        entry.put("peer_reference", String.valueOf(peerFrontOfficeOrPid));
        entry.put("query_url", result.queryUrl() != null ? result.queryUrl().toString() : null);
        entry.put("result_count", result.resultCount());
        entry.put("visible", result.visible());
        entry.put("status", result.status());
        if (result.httpCode() != null) {
            entry.put("http_code", result.httpCode());
        } else {
            entry.putNull("http_code");
        }
        return entry;
    }

    private static void printLine(int metric, String label, MetricResult result) {
        // GREEN: visible. YELLOW: legitimate negative result (not yet
        // visible, or the capability isn't reported for this Node).
        // RED: the check itself failed (bad HTTP status, network error).
        String colour = result.visible() ? GREEN
                : "Error".equals(result.status()) ? RED
                : YELLOW;
        System.out.printf("  Metric %-3d %-45s %s%s%s%n",
                metric, label, colour, result.status(), NC);
    }

    // ── Supporting types ──────────────────────────────────────────────────

    /**
     * Result of querying a single Front Office for a single target PID.
     */
    record MetricResult(URI queryUrl, Integer httpCode, long resultCount, boolean visible, String status, String error) {
        static MetricResult notReported() {
            return new MetricResult(null, null, 0, false, "Not reported", null);
        }

        static MetricResult error(URI queryUrl, Integer httpCode, String error) {
            return new MetricResult(queryUrl, httpCode, 0, false, "Error", error);
        }
    }
}