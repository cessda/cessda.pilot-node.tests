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

package eu.cessda.pilotnode.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * What the Core Service integration checks have in common, whichever mode they run in: reading the ARGO federation
 * tenant's status of every endpoint and collecting a node's endpoints from it.
 */
public final class ArgoStatus {

    private ArgoStatus() {}

    private static final Logger log = Logger.getLogger(ArgoStatus.class.getName());

    /** Where to find the ARGO federation tenant's status API. */
    public record Source(String apiBase, String tenant, String uiBase) {
        public static final String DEFAULT_API_BASE = "https://api-status.devel.mon.argo.grnet.gr";
        public static final String DEFAULT_TENANT   = "EOSC-BEYOND-FEDERATION";
        /** The ARGO status web UI, linked to from the dashboard when an endpoint is not OK. */
        public static final String DEFAULT_UI_BASE  = "https://status.devel.mon.argo.grnet.gr";
        public static final Source DEFAULT = new Source(DEFAULT_API_BASE, DEFAULT_TENANT, DEFAULT_UI_BASE);
    }

    // Worst-first, so the worst value seen in the window can be picked.
    public static final List<String> STATUS_SEVERITY =
            List.of("CRITICAL", "WARNING", "UNKNOWN", "MISSING", "OK");

    /** The ARGO federation tenant's status of every endpoint, for today, as the API answered it. */
    public record Feed(URI url, String start, String end, JsonNode root) { }

    public static Feed fetchFeed(HttpClient httpClient, ObjectMapper mapper, Source source)
            throws IOException, InterruptedException {
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
        return new Feed(url, start, end, root);
    }

    /** A node's endpoints as the report lists them, and how many are OK now. */
    public record Collected(ArrayNode endpoints, int okCount) { }

    public static Collected collectEndpoints(JsonNode group, String nodeName, Feed feed, Source source,
                                              HttpClient httpClient, ObjectMapper mapper)
            throws InterruptedException {
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
                                group.path("name").asText(), endpoint.path("name").asText(), feed.start(), feed.end());
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
        return new Collected(endpoints, okCount);
    }

    public static ObjectNode baseReport(ObjectMapper mapper, String nodeName, Feed feed, Source source,
                                         JsonNode group, Collected collected) {
        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name", nodeName);
        report.put("api_source", feed.url().toString());
        report.put("tenant", source.tenant());
        report.put("argo_ui_url", source.uiBase() + "/public/tenants/" + pathSegment(source.tenant())
                + "/dashboard/groups/" + pathSegment(group.path("name").asText(nodeName)) + "?report=Default");
        report.put("period_start", feed.start());
        report.put("period_end", feed.end());
        report.put("total_endpoints", collected.endpoints().size());
        report.put("ok_endpoints", collected.okCount());
        report.set("endpoints", collected.endpoints());
        return report;
    }

    /**
     * Fetches the per-probe (ARGO metric) statuses behind one endpoint's
     * status, worst probes first. ARGO's public API reports which probe is
     * failing and for how long, but not a failure message.
     */
    public static ArrayNode fetchProbes(HttpClient httpClient, ObjectMapper mapper, Source source,
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
    public static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static int severityRank(String status) {
        int i = STATUS_SEVERITY.indexOf(status);
        return i >= 0 ? i : STATUS_SEVERITY.indexOf("UNKNOWN");
    }

    public static InputStream fetchData(HttpClient httpClient, URI url) throws IOException, InterruptedException {
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
