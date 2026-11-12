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

package eu.cessda.pilotnode.catalogue.lenient;

import eu.cessda.pilotnode.catalogue.NoCatalogueEndpointException;
import eu.cessda.pilotnode.catalogue.BeyondAdapter;
import eu.cessda.pilotnode.catalogue.CatalogueCandidates;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import eu.cessda.pilotnode.HttpUtils;
import eu.cessda.pilotnode.catalogue.*;
import static eu.cessda.pilotnode.catalogue.CatalogueReport.*;
import eu.cessda.pilotnode.common.ReportFiles;
import eu.cessda.pilotnode.common.CheckMode;
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

    private static final Logger log =
            Logger.getLogger(CheckCatalogueServices.class.getName());

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
        URI apiUrl = BeyondAdapter.buildApiServiceUrl(apiBaseUrl);

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
        checkAndReport(CheckMode.LENIENT.nodeDir(dashboardDir, nodeName), nodeName, source, httpClient, mapper);
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

        // This mode's folder or, until a lenient run has written there, where earlier versions kept the report.
        Path endpointReportPath = ReportFiles.find(dashboardDir, CheckMode.LENIENT, nodeName, "endpoint_report.json", mapper)
                .orElse(CheckMode.LENIENT.nodeDir(dashboardDir, nodeName).resolve("endpoint_report.json"));
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
        for (CatalogueCandidates.Attempt attempt : result.attempts()) {
            ObjectNode a = tried.addObject();
            a.put("capability_type", attempt.candidate().capabilityType());
            a.put("endpoint", attempt.candidate().endpoint());
            a.put("outcome", attempt.outcome());
        }

        Source source;
        if (result.selection().isPresent()) {
            CatalogueCandidates.Selection chosen = result.selection().get();
            ObjectNode provenance = mapper.createObjectNode();
            provenance.put("capability_type", chosen.candidate().capabilityType());
            provenance.put("endpoint", chosen.candidate().endpoint());
            provenance.put("protocol", chosen.candidate().protocol());
            provenance.put("adapter", chosen.adapterId());
            provenance.put("format", chosen.format());
            provenance.put("converted", chosen.list().converted());
            log.log(Level.INFO, "Using {0} ({1})", new Object[]{chosen.candidate(), chosen.format()});
            source = new Source(chosen.list().root(), URI.create(chosen.candidate().endpoint()), null, pid, provenance,
                    tried, chosen.list().invalid(), chosen.list().warnings(), null);
        } else {
            String reason = result.attempts().isEmpty()
                    ? "no Service Catalogue or Resource Catalogue endpoint"
                    : "no catalogue endpoint could be read (" + result.attempts().stream()
                            .map(a -> a.candidate().endpoint() + ": " + a.outcome()).collect(java.util.stream.Collectors.joining("; ")) + ")";
            source = sandboxSource(httpClient, mapper, pid, endpointReport.path("node_endpoint").asText(""), reason,
                    quantity, fallbackBase, null);
            source = new Source(source.root(), source.apiUrl(), source.fallbackReason(), source.nodePid(), null, tried,
                    List.of(), List.of(), null);
        }
        checkAndReport(CheckMode.LENIENT.nodeDir(dashboardDir, nodeName), nodeName, source, httpClient, mapper);
    }

    // ── where the services come from ──────────────────────────────────────

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

        URI fallbackUrl = buildNodeFilterUrl(BeyondAdapter.buildApiServiceUrl(fallbackBase), nodePid, quantity);
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
        return new Source(filtered, fallbackUrl, reason, nodePid, null, null, List.of(), List.of(), null);
    }

    // ── checking and reporting ────────────────────────────────────────────



    static URI buildNodeFilterUrl(URI fallbackServiceBase, String nodePid, int quantity) {
        String encodedPid = URLEncoder.encode(nodePid, StandardCharsets.UTF_8);
        return URI.create(fallbackServiceBase + "?node=" + encodedPid
                + "&from=0&quantity=" + quantity + "&order=asc");
    }

}