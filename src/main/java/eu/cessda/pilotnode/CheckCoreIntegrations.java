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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Core Service integrations monitoring.
 *
 * <p>The federation-wide ARGO tenant monitors the status of each Node's Core
 * Service integration endpoints (the fabric: AAI, Resource Catalogue,
 * Helpdesk, Monitoring ...), grouped by Node name. This check fetches that
 * status for one Node and writes {@code core_integrations_report.json},
 * which the Node page overlays on the endpoints listed in the Core Services
 * Integration Report (matched by capability type and URL).</p>
 *
 * <p>This is independent of {@link CheckNodeCapabilities} (which probes the
 * endpoints listed in the Federation Registry), of {@link CheckServiceUptime}
 * (Metric 12, the Node's Exchange services) and of
 * {@link CheckCatalogueServices} (Metric 13), so it keeps working while the
 * Federation Registry is unavailable.</p>
 *
 * <p>The ARGO status API base URL and tenant are configurable via
 * {@code check.argo-status-api-base} and {@code check.argo-federation-tenant}
 * in {@code application.properties}.</p>
 *
 * Usage: java CheckCoreIntegrations NODE_NAME [dashboard_dir] [api_base] [tenant] [ui_base]
 *   NODE_NAME:     Node name, as in node_registry_summary.json (required)
 *   dashboard_dir: Path to the dashboard data directory
 *                  (default: ../dashboard/data). Output is written to
 *                  &lt;dashboard_dir&gt;/&lt;NODE_NAME&gt;/core_integrations_report.json
 *   api_base:      ARGO status API base URL (default: {@link Source#DEFAULT_API_BASE})
 *   tenant:        ARGO federation tenant (default: {@link Source#DEFAULT_TENANT})
 *   ui_base:       ARGO status web UI base URL (default: {@link Source#DEFAULT_UI_BASE})
 */
public class CheckCoreIntegrations {

    /** Where to find the ARGO federation tenant's status API. */
    public record Source(String apiBase, String tenant, String uiBase) {
        public static final String DEFAULT_API_BASE = "https://api-status.devel.mon.argo.grnet.gr";
        public static final String DEFAULT_TENANT   = "EOSC-BEYOND-FEDERATION";
        /** The ARGO status web UI, linked to from the dashboard when an endpoint is not OK. */
        public static final String DEFAULT_UI_BASE  = "https://status.devel.mon.argo.grnet.gr";
        public static final Source DEFAULT = new Source(DEFAULT_API_BASE, DEFAULT_TENANT, DEFAULT_UI_BASE);
    }

    // Worst-first, so the worst value seen in the window can be picked.
    private static final List<String> STATUS_SEVERITY =
            List.of("CRITICAL", "WARNING", "UNKNOWN", "MISSING", "OK");

    private static final Logger log = Logger.getLogger(CheckCoreIntegrations.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1) {
            System.err.println("Error: NODE_NAME is required");
            System.err.println("Usage: java CheckCoreIntegrations <NODE_NAME> [<dashboard_dir>] [<api_base>] [<tenant>] [<ui_base>]");
            System.exit(-1);
        }
        String nodeName     = args[0];
        String dashboardDir = args.length >= 2 ? args[1] : "../dashboard/data";
        Source source = new Source(
                args.length >= 3 && !args[2].isBlank() ? args[2] : Source.DEFAULT_API_BASE,
                args.length >= 4 && !args[3].isBlank() ? args[3] : Source.DEFAULT_TENANT,
                args.length >= 5 && !args[4].isBlank() ? args[4] : Source.DEFAULT_UI_BASE);

        var nodeNamePath = Path.of(nodeName);
        if (nodeNamePath.normalize().getNameCount() != 1 || nodeNamePath.isAbsolute()) {
            throw new IllegalArgumentException("nodeName must be a file name");
        }
        run(Path.of(dashboardDir), nodeName, source, HttpUtils.httpClient(), new ObjectMapper());
    }

    /**
     * Fetches today's Core Service integration status for {@code nodeName}
     * and writes {@code core_integrations_report.json}.
     *
     * @throws IOException if the ARGO feed cannot be read, or has no group for
     *                      this Node
     */
    public static void run(Path dashboardDir, String nodeName, Source source,
                           HttpClient httpClient, ObjectMapper mapper)
            throws IOException, InterruptedException {

        Path outputDir = dashboardDir.resolve(nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("core_integrations_report.json");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String start = today + "T00:00:00Z";
        String end   = today + "T23:59:59Z";
        URI url = URI.create(source.apiBase() + "/v1/public/tenants/"
                + URLEncoder.encode(source.tenant(), StandardCharsets.UTF_8)
                + "/status/Default/endpoints?start-time="
                + URLEncoder.encode(start, StandardCharsets.UTF_8)
                + "&end-time=" + URLEncoder.encode(end, StandardCharsets.UTF_8));

        JsonNode root;
        try (InputStream in = fetchData(httpClient, url)) {
            root = mapper.readTree(in);
        }

        JsonNode group = null;
        for (JsonNode g : root.path("groups")) {
            if (nodeName.equalsIgnoreCase(g.path("name").asText())) {
                group = g;
                break;
            }
        }
        if (group == null) {
            throw new IOException("no service group for node '" + nodeName
                    + "' in ARGO tenant " + source.tenant());
        }

        ArrayNode endpoints = mapper.createArrayNode();
        int okCount = 0;
        for (JsonNode serviceType : group.path("service-types")) {
            for (JsonNode endpoint : serviceType.path("endpoints")) {
                JsonNode statuses = endpoint.path("statuses");
                if (!statuses.isArray() || statuses.isEmpty()) {
                    continue;
                }
                JsonNode last = statuses.get(statuses.size() - 1);
                String latest = last.path("value").asText();
                String worst = latest;
                for (JsonNode st : statuses) {
                    String v = st.path("value").asText();
                    if (severityRank(v) < severityRank(worst)) {
                        worst = v;
                    }
                }
                JsonNode info = endpoint.path("info");
                ObjectNode entry = mapper.createObjectNode();
                entry.put("capability_type",
                        info.path("capability_type").asText(serviceType.path("name").asText()));
                if (info.hasNonNull("URL")) {
                    entry.put("url", info.get("URL").asText());
                }
                entry.put("status", latest);
                entry.put("worst_status", worst);
                entry.put("last_checked", last.path("timestamp").asText());
                if (!"OK".equals(latest) || !"OK".equals(worst)) {
                    // Best effort: explains *why* the endpoint isn't OK.
                    try {
                        ArrayNode probes = fetchProbes(httpClient, mapper, source,
                                group.path("name").asText(), endpoint.path("name").asText(), start, end);
                        if (!probes.isEmpty()) {
                            entry.set("probes", probes);
                        }
                    } catch (IOException | RuntimeException e) {
                        log.log(Level.WARNING, "Probe detail unavailable for {0} / {1}: {2}",
                                new Object[]{nodeName, entry.path("capability_type").asText(), e.getMessage()});
                    }
                }
                endpoints.add(entry);
                if ("OK".equals(latest)) {
                    okCount++;
                }
            }
        }

        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name", nodeName);
        report.put("api_source", url.toString());
        report.put("tenant", source.tenant());
        report.put("argo_ui_url", source.uiBase() + "/public/tenants/" + pathSegment(source.tenant())
                + "/dashboard/groups/" + pathSegment(group.path("name").asText(nodeName)) + "?report=Default");
        report.put("period_start", start);
        report.put("period_end", end);
        report.put("total_endpoints", endpoints.size());
        report.put("ok_endpoints", okCount);
        report.set("endpoints", endpoints);

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        log.log(Level.INFO, "Report generated: JSON: {0}", reportFile.toAbsolutePath());
    }

    /**
     * Fetches the per-probe (ARGO metric) statuses behind one endpoint's
     * status, worst probes first. ARGO's public API reports which probe is
     * failing and for how long, but not a failure message.
     */
    private static ArrayNode fetchProbes(HttpClient httpClient, ObjectMapper mapper, Source source,
                                         String groupName, String endpointName, String start, String end)
            throws IOException, InterruptedException {

        URI url = URI.create(source.apiBase() + "/v1/public/tenants/"
                + pathSegment(source.tenant()) + "/status/Default/groups/"
                + pathSegment(groupName) + "/endpoints/" + pathSegment(endpointName)
                + "/metrics?start-time=" + URLEncoder.encode(start, StandardCharsets.UTF_8)
                + "&end-time=" + URLEncoder.encode(end, StandardCharsets.UTF_8));

        JsonNode root;
        try (InputStream in = fetchData(httpClient, url)) {
            root = mapper.readTree(in);
        }

        List<ObjectNode> probes = new java.util.ArrayList<>();
        for (JsonNode g : root.path("groups")) {
            for (JsonNode st : g.path("service-types")) {
                for (JsonNode ep : st.path("endpoints")) {
                    for (JsonNode metric : ep.path("metrics")) {
                        JsonNode statuses = metric.path("statuses");
                        if (!statuses.isArray() || statuses.isEmpty()) {
                            continue;
                        }
                        JsonNode last = statuses.get(statuses.size() - 1);
                        String latest = last.path("value").asText();
                        String worst = latest;
                        int nonOk = 0;
                        String firstNonOk = null;
                        for (JsonNode s : statuses) {
                            String v = s.path("value").asText();
                            if (severityRank(v) < severityRank(worst)) {
                                worst = v;
                            }
                            if (!"OK".equals(v)) {
                                nonOk++;
                                if (firstNonOk == null) {
                                    firstNonOk = s.path("timestamp").asText();
                                }
                            }
                        }
                        ObjectNode probe = mapper.createObjectNode();
                        probe.put("name", metric.path("name").asText());
                        probe.put("status", latest);
                        probe.put("worst_status", worst);
                        probe.put("total_checks", statuses.size());
                        probe.put("non_ok_checks", nonOk);
                        if (firstNonOk != null) {
                            probe.put("first_non_ok", firstNonOk);
                        }
                        probe.put("last_checked", last.path("timestamp").asText());
                        probes.add(probe);
                    }
                }
            }
        }
        probes.sort(java.util.Comparator
                .comparingInt((ObjectNode p) -> severityRank(p.path("status").asText()))
                .thenComparingInt(p -> severityRank(p.path("worst_status").asText())));

        ArrayNode out = mapper.createArrayNode();
        probes.forEach(out::add);
        return out;
    }

    /** URL-encodes a single path segment (spaces as %20, not '+'). */
    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int severityRank(String status) {
        int i = STATUS_SEVERITY.indexOf(status);
        return i >= 0 ? i : STATUS_SEVERITY.indexOf("UNKNOWN");
    }

    private static InputStream fetchData(HttpClient httpClient, URI url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .header("accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url.getHost());
        }
        return response.body();
    }
}
