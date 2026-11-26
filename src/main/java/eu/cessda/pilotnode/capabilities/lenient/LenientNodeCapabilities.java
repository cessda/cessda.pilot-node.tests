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

package eu.cessda.pilotnode.capabilities.lenient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cessda.pilotnode.capabilities.Node;
import eu.cessda.pilotnode.capabilities.NodeCapabilities;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.ReportFiles;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static eu.cessda.pilotnode.capabilities.CheckNodeCapabilities.*;
import static eu.cessda.pilotnode.common.JsonConstants.*;

/**
 * The lenient check of one node's capabilities: the node's capability list is fetched, a failed fetch falls back to
 * what was last known (flagged as stale), every endpoint is probed, and the text and JSON reports are written to the
 * node's lenient folder.
 */
public final class LenientNodeCapabilities implements NodeCapabilities {

    private static final Logger log = Logger.getLogger(LenientNodeCapabilities.class.getName());

    private final EnumSet<OutputFormat> format;
    private final Duration retryDelay;
    private final Path dashboardDir;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public LenientNodeCapabilities(Set<OutputFormat> format, Path dashboardDir, HttpClient http, ObjectMapper mapper, Duration retryDelay) {
        this.format = EnumSet.copyOf(format);
        this.dashboardDir = dashboardDir;
        this.http = http;
        this.mapper = mapper;
        this.retryDelay = retryDelay;
    }

    public static void writeTextHeader(Writer writer) throws IOException {
        writer.write("""
                ==========================================
                EOSC Beyond Node Registry
                Endpoint Availability Summary
                ==========================================
                Generated: %s
                Registry:  %s
                ==========================================

                """.formatted(nowIso(), NODE_REGISTRY_URL));
    }

    public static void appendTextSummaryEntry(Writer writer, ObjectNode nodeSummary) throws IOException {
        writer.write("""
                Node: %s
                  Endpoint:               %s
                  Total Capabilities:     %d
                  Available Capabilities: %d

                """.formatted(
                nodeSummary.path("node_name").asText(),
                nodeSummary.path("node_endpoint").asText(),
                nodeSummary.path("total_capabilities").asInt(),
                nodeSummary.path("available_capabilities").asInt()));
    }

    /**
     * The endpoints a capability offers. Nodes report them in two shapes:
     * <ul>
     *   <li>flat: {@code endpoint}, {@code protocol} and {@code api_spec} on the capability itself;</li>
     *   <li>nested: an {@code endpoints} list of {@code {protocol, endpoint, api_spec}}, so that one capability
     *       (a catalogue with a REST and an OAI-PMH interface, say) can offer several.</li>
     * </ul>
     * Nested entries take their own protocol and API description, falling back to the capability's. A node
     * that gives both shapes has both reported, without repeating an endpoint. A capability with no
     * endpoint at all (for example one the node says is planned) still gets one row, with an empty endpoint.
     */
    static List<EndpointRow> endpointsOf(JsonNode cap) {
        String capProtocol = optionalText(cap, PROTOCOL_FIELD);
        String capApiSpec = optionalText(cap, API_SPEC_FIELD);
        List<EndpointRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        JsonNode nested = cap.path(ENDPOINTS_FIELD);
        if (nested.isArray()) {
            for (JsonNode entry : nested) {
                String url;
                String protocol = capProtocol;
                String apiSpec = capApiSpec;
                if (entry.isTextual()) {
                    url = entry.asText().trim();
                } else if (entry.isObject()) {
                    url = entry.path(ENDPOINT_FIELD).asText("").trim();
                    protocol = firstNonNull(optionalText(entry, PROTOCOL_FIELD), capProtocol);
                    apiSpec = firstNonNull(optionalText(entry, API_SPEC_FIELD), capApiSpec);
                } else {
                    continue;
                }
                if (!url.isEmpty() && seen.add(url)) {
                    rows.add(new EndpointRow(url, protocol, apiSpec));
                }
            }
        }

        String flat = cap.path(ENDPOINT_FIELD).asText("").trim();
        if (!flat.isEmpty() && seen.add(flat)) {
            rows.add(new EndpointRow(flat, capProtocol, capApiSpec));
        }

        if (rows.isEmpty()) {
            rows.add(new EndpointRow("", capProtocol, capApiSpec));
        }
        return rows;
    }

    /** Outcome of fetching a node's capability list: the parsed body, or why it could not be had. */
    private record Fetch(JsonNode root, String failure) {}

    @Override
    public ObjectNode check(Node node) throws IOException, InterruptedException {

        log.log(Level.INFO, """
                        Node: {0}
                        ========================================
                        ID:           {1}
                        PID:          {2}
                        Endpoint:     {3}
                        Legal Entity: {4}""",
                new Object[]{node.name(), node.id(), node.pid(), node.endpoint(), node.legalEntityName()}
        );


        Path nodeDir = CheckMode.LENIENT.nodeDir(dashboardDir, node.name());
        Files.createDirectories(nodeDir);
        Path reportJsonPath = nodeDir.resolve("endpoint_report.json");
        Path reportTxtPath  = nodeDir.resolve("endpoint_report.txt");

        // ── Fetch capabilities ────────────────────────────────────────────────

        log.log(Level.INFO, "Fetching capabilities for {0}...", node.name());

        JsonNode capsRoot;
        String staleReason = null;
        String lastFetched = null;

        Fetch fetched = fetchCapabilities(URI.create(node.endpoint()));
        if (fetched.root() != null) {
            capsRoot = fetched.root();
        } else {
            log.log(Level.WARNING, "Failed to fetch capabilities from {0}: {1}", new Object[]{node.endpoint(), fetched.failure()});

            // A failed fetch must not throw away what we last knew about the node: keep its last
            // capability list, re-probe those endpoints, and flag the report as stale.
            // The last lenient report: in this mode's folder or, until a lenient run has written there, where earlier
            // versions kept it.
            Previous previous = readPreviousCapabilities(
                    ReportFiles.find(dashboardDir, CheckMode.LENIENT, node.name(), "endpoint_report.json", mapper).orElse(reportJsonPath));
            if (previous == null) {
                return unreachableNode(node, reportJsonPath, fetched.failure());
            }
            capsRoot = previous.capsRoot();
            staleReason = fetched.failure();
            lastFetched = previous.lastFetched();
            log.log(Level.WARNING, "Using the capabilities of {0} last fetched {1}", new Object[]{node.name(), lastFetched});
        }

        log.info("Checking capabilities...");

        try (BufferedWriter reportTxtPathWriter = (format.contains(OutputFormat.TEXT) ? Files.newBufferedWriter(reportTxtPath) : null)) {

            if (reportTxtPathWriter != null) {
                var header = buildNodeTextHeader(node);
                 reportTxtPathWriter.write(header);
             }


             // ── Check each capability ─────────────────────────────────────────────

             ArrayNode capabilitiesOut = mapper.createArrayNode();
             int total = 0;
             int available = 0;

            JsonNode capsArray = capsRoot.path(CAPABILITIES_FIELD);
             for (JsonNode cap : capsArray) {
                 String capType = cap.path(CAPABILITY_TYPE_FIELD).asText();
                 String version = optionalText(cap, VERSION_FIELD);
                 // The node's own claim about the capability (e.g. OPERATIONAL). Kept apart from
                 // "status", which is the result of our own availability probe.
                 String declaredStatus = optionalText(cap, STATUS_FIELD);

                 // A capability may list several endpoints; each is probed and reported on its own row.
                 for (EndpointRow row : endpointsOf(cap)) {
                 String endpoint = row.endpoint();
                 String apiSpec = row.apiSpec();
                 String protocol = row.protocol();

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
                         String capability = appendCapabilityToText(capType, endpoint, version, protocol, apiSpec, status);
                         reportTxtPathWriter.write(capability);
                     }

                     ObjectNode capOut = mapper.createObjectNode();
                     capOut.put(CAPABILITY_TYPE_FIELD, capType);
                     capOut.put(ENDPOINT_FIELD, endpoint);
                     capOut.put(VERSION_FIELD, version);
                     capOut.put(API_SPEC_FIELD, apiSpec);
                     capOut.put(PROTOCOL_FIELD, protocol);
                     capOut.put(DECLARED_STATUS_FIELD, declaredStatus);
                     capOut.put(STATUS_FIELD, status.label());
                     capOut.put(HTTP_CODE_FIELD, status.httpCode());
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

            ObjectNode nodeReport = buildNodeSummary(mapper, CheckMode.LENIENT, node, total, available, capabilitiesOut, reportJsonPath);
             // Free-form description some nodes publish about themselves; kept as returned.
            if (capsRoot.path(NODE_DETAILS_FIELD).isObject()) {
                nodeReport.set(NODE_DETAILS_FIELD, capsRoot.get(NODE_DETAILS_FIELD));
             }
             if (staleReason != null) {
                 nodeReport.put(ERROR_PATH, staleReason);
                 nodeReport.put(CAPABILITIES_STALE_FIELD, true);
                 nodeReport.put(CAPABILITIES_LAST_FETCHED_FIELD, lastFetched);
             }


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

    /** The last known capability list of a node, rebuilt in the shape the node itself returns it. */
    private record Previous(ObjectNode capsRoot, String lastFetched) {}

    /**
     * Fetches the node's capability list. Network errors and 5xx responses are usually momentary
     * (a gateway restarting, a cold start), so they are retried; other statuses, such as 404, are not.
     */
    private Fetch fetchCapabilities(URI nodeEndpoint) throws InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(nodeEndpoint)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        String failure;
        for (int attempt = 1; true; attempt++) {
            boolean transientFailure;
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = resp.body()) {
                    if (resp.statusCode() == 200) {
                        try {
                            return new Fetch(mapper.readTree(body), null);
                        } catch (IOException e) {
                            return new Fetch(null, "node_endpoint returned invalid JSON: " + describe(e));
                        }
                    }
                }
                failure = "node_endpoint returned HTTP " + resp.statusCode();
                transientFailure = resp.statusCode() >= 500;
            } catch (IOException e) {
                failure = "node_endpoint unreachable: " + describe(e);
                transientFailure = true;
            }

            if (!transientFailure || attempt == MAX_FETCH_ATTEMPTS) {
                break;
            }
            log.log(Level.INFO, "{0} (attempt {1} of {2}); retrying", new Object[]{failure, attempt, MAX_FETCH_ATTEMPTS});
            Thread.sleep(retryDelay.toMillis() * attempt);
        }
        return new Fetch(null, failure);
    }

    private CapabilityStatus probeEndpoint(URI url) {
        return probe(http, url);
    }

    /**
     * Probes a capability endpoint with a {@code HEAD} request, retrying with {@code GET} if the
     * server does not handle {@code HEAD}.
     *
     * <p>Some APIs only route {@code GET}: FastAPI-based service catalogues answer {@code HEAD}
     * with 405, and others with 404 (a route that exists for {@code GET} only), so a bare
     * {@code HEAD} reported working endpoints as "Not available". The retry is triggered by 404,
     * 405 and 501 only; for a genuinely missing endpoint the {@code GET} returns 404 again, so
     * the result is unchanged and it costs one extra request. Any other status is taken at face
     * value.</p>
     */
    static CapabilityStatus probe(HttpClient http, URI url) {
        try {
            int code = send(http, url, "HEAD");
            if (code == 404 || code == 405 || code == 501) {
                code = send(http, url, "GET");
            }
            return CapabilityStatus.of(code);
        } catch (IOException e) {
            return CapabilityStatus.exceptionally(e);
        } catch (IllegalArgumentException e) {
            // A URL the JDK cannot send a request to (no scheme, not http/https): report this
            // capability as not available rather than failing the whole run.
            return CapabilityStatus.exceptionally(new IOException("Invalid endpoint URL: " + e.getMessage(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CapabilityStatus.of(-1);
        }
    }

    static int send(HttpClient http, URI url, String method) throws IOException, InterruptedException {
        // Unlike the registry and per-node capabilities requests above,
        // this probe was sending no User-Agent/Accept at all, so it
        // got the JDK's default "Java-http-client/..." UA — the same
        // thing that made CheckCatalogueServices misreport healthy
        // services as unavailable when a CDN/WAF fast-rejects it.
        //
        // Unlike those two requests, though, a capability endpoint here
        // isn't necessarily a JSON API — e.g. an AAI capability is
        // typically an HTML login/account portal (Keycloak and similar
        // often do strict content negotiation and will hand back a
        // non-2xx for a representation they don't have). This is a
        // bare availability probe with a discarded body, so accept
        // anything rather than asserting a content type we don't need.
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "*/*")
                .timeout(CAPABILITY_CHECK_TIMEOUT)
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * Reads the capabilities from the node's existing {@code endpoint_report.json}, if there are any.
     * Our own probe result is not carried over (it is measured again); the node's own declared status is.
     */
    private Previous readPreviousCapabilities(Path reportJsonPath) {
        if (!Files.isRegularFile(reportJsonPath)) {
            return null;
        }
        try {
            JsonNode previous = mapper.readTree(reportJsonPath.toFile());
            JsonNode caps = previous.path(CAPABILITIES_FIELD);
            if (!caps.isArray() || caps.isEmpty()) {
                return null;
            }
            ObjectNode root = mapper.createObjectNode();
            ArrayNode out = root.putArray(CAPABILITIES_FIELD);
            for (JsonNode c : caps) {
                ObjectNode o = out.addObject();
                for (String field : new String[]{CAPABILITY_TYPE_FIELD, ENDPOINT_FIELD, VERSION_FIELD, API_SPEC_FIELD, PROTOCOL_FIELD}) {
                    if (c.has(field)) {
                        o.set(field, c.get(field));
                    }
                }
                if (c.hasNonNull(DECLARED_STATUS_FIELD)) {
                    o.set(STATUS_FIELD, c.get(DECLARED_STATUS_FIELD));
                }
            }
            if (previous.path(NODE_DETAILS_FIELD).isObject()) {
                root.set(NODE_DETAILS_FIELD, previous.get(NODE_DETAILS_FIELD));
            }
            // A report that is itself stale keeps pointing at the last successful fetch.
            String lastFetched = previous.hasNonNull(CAPABILITIES_LAST_FETCHED_FIELD)
                    ? previous.get(CAPABILITIES_LAST_FETCHED_FIELD).asText()
                    : previous.path("generated").asText(null);
            return new Previous(root, lastFetched);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not read the previous report {0}: {1}", new Object[]{reportJsonPath, e});
            return null;
        }
    }

    private String appendCapabilityToText(String capType, String endpoint,
            String version, String protocol, String apiSpec, CapabilityStatus status) {
        return "%-40s %s (HTTP %s)%n  └─ Endpoint: %s%n  └─ Version: %s%n  └─ Protocol: %s%n  └─ API spec: %s%n%n"
                .formatted(capType, status.label(), status.httpCode(), endpoint,
                        orDash(version), orDash(protocol), orDash(apiSpec));
    }

    static String orDash(String value) {
        return value == null ? "-" : value;
    }

    /**
     * The summary entry for a node whose capabilities could not be fetched, also written as the
     * node's own {@code endpoint_report.json} so that its detail page shows the failure.
     */
    private ObjectNode unreachableNode(Node node, Path reportJsonPath, String reason) throws IOException {
        ObjectNode report = failedNodeSummary(mapper, CheckMode.LENIENT, dashboardDir, node, reason);
        if (format.contains(OutputFormat.JSON)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportJsonPath.toFile(), report);
        }
        return report;
    }

    /** One endpoint of a capability, with the protocol and API description that belong to it. */
    record EndpointRow(String endpoint, String protocol, String apiSpec) {}

    private String buildNodeTextHeader(Node node) {
        return ("==========================================\n" +
                "Node: " + node.name() + "\n" +
                "==========================================\n" +
                "Generated:          " + nowIso() + "\n" +
                "Node ID:            " + node.id() + "\n" +
                "Node PID:           " + node.pid() + "\n" +
                "Node Endpoint:      " + node.endpoint() + "\n" +
                "Legal Entity:       " + node.legalEntityName() + "\n" +
                "Legal Entity ROR:   " + node.legalEntityRor() + "\n" +
                "Logo:               " + node.logo() + "\n" +
                "==========================================\n\n");
    }

    static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    /** The field's text, or null if it is missing, JSON null or blank (never the string "null"). */
    static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

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

    static final Duration CAPABILITY_CHECK_TIMEOUT = Duration.ofSeconds(10);

    /** Attempts at fetching a node's capability list; a failed attempt other than a 4xx is retried. */
    static final int MAX_FETCH_ATTEMPTS = 3;

    static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
}
