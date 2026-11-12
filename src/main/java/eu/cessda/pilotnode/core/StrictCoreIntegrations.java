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
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cessda.pilotnode.common.ReportFiles;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.Findings;
import static eu.cessda.pilotnode.core.ArgoStatus.*;

/** The strict Core Service integrations check. */
public final class StrictCoreIntegrations {

    private StrictCoreIntegrations() {}

    private static final Logger log = Logger.getLogger(StrictCoreIntegrations.class.getName());

    /**
     * The strict version of the check: the node's group must be named exactly as the node, and every endpoint ARGO
     * reports on that is not OK is a finding (CRITICAL is an error; WARNING, UNKNOWN and MISSING are warnings; an
     * endpoint that is OK now but was not earlier today is a note). The endpoints are also compared with what the
     * node declares in its strict {@code endpoint_report.json}. The result is written under the {@code strict}
     * directory, and a failure to get a result is itself a finding rather than an exception.
     */
    public static void run(Path dashboardDir, String nodeName, Source source,
                                 HttpClient httpClient, ObjectMapper mapper)
            throws IOException, InterruptedException {

        Path outputDir = CheckMode.STRICT.nodeDir(dashboardDir, nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("core_integrations_report.json");

        Findings findings = new Findings();
        ObjectNode report;

        Feed feed = null;
        try {
            feed = fetchFeed(httpClient, mapper, source);
        } catch (IOException | RuntimeException e) {
            findings.error("argo-feed-unavailable", "The ARGO status API could not be read",
                    "ARGO federation tenant " + source.tenant(), source.apiBase(),
                    "the status of every endpoint of the tenant", String.valueOf(e.getMessage()), null);
        }

        if (feed == null) {
            report = emptyReport(mapper, nodeName, source);
        } else {
            JsonNode group = null;
            String differentCase = null;
            for (JsonNode g : feed.root().path("groups")) {
                String name = g.path("name").asText();
                if (nodeName.equals(name)) {
                    group = g;
                    break;
                }
                if (nodeName.equalsIgnoreCase(name)) {
                    differentCase = name;
                }
            }
            if (group == null) {
                findings.error("node-not-monitored", "ARGO has no group for this node",
                        "ARGO federation tenant " + source.tenant(), feed.url().toString(),
                        "a group named exactly '" + nodeName + "'",
                        differentCase == null ? "no such group" : "a group named '" + differentCase + "' (different case)",
                        differentCase == null ? "The node's Core Service endpoints need to be added to the federation tenant"
                                : "The group name has to match the node's name exactly; lenient mode ignores the case");
                report = emptyReport(mapper, nodeName, source);
                report.put("api_source", feed.url().toString());
            } else {
                Collected collected = collectEndpoints(group, nodeName, feed, source, httpClient, mapper);
                report = baseReport(mapper, nodeName, feed, source, group, collected);
                judgeEndpoints(findings, collected.endpoints(), report.path("argo_ui_url").asText(),
                        declaredCapabilities(dashboardDir, nodeName, mapper));
            }
        }

        findings.writeTo(report, mapper);
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        log.log(Level.INFO, "Strict report generated ({0}): {1}",
                new Object[]{report.path("strict_result").asText(), reportFile.toAbsolutePath()});
    }

    private static ObjectNode emptyReport(ObjectMapper mapper, String nodeName, Source source) {
        ObjectNode report = mapper.createObjectNode();
        report.put("generated", Instant.now().toString());
        report.put("node_name", nodeName);
        report.put("tenant", source.tenant());
        report.put("total_endpoints", 0);
        report.put("ok_endpoints", 0);
        report.set("endpoints", mapper.createArrayNode());
        return report;
    }

    /** The node's declared capabilities from its strict endpoint report, or {@code null} if there is none. */
    private static JsonNode declaredCapabilities(Path dataDir, String nodeName, ObjectMapper mapper) {
        return ReportFiles.read(dataDir, CheckMode.STRICT, nodeName, "endpoint_report.json", mapper)
                .map(report -> report.path("capabilities")).orElse(null);
    }

    private static void judgeEndpoints(Findings findings, ArrayNode endpoints, String uiUrl, JsonNode declared) {
        for (JsonNode endpoint : endpoints) {
            String type = endpoint.path("capability_type").asText();
            String url = endpoint.path("url").asText(null);
            String status = endpoint.path("status").asText();
            String worst = endpoint.path("worst_status").asText();
            String failing = failingProbes(endpoint.path("probes"));
            String actual = status + ("OK".equals(worst) || worst.equals(status) ? "" : " (worst today: " + worst + ")")
                    + (failing.isEmpty() ? "" : "; failing probes: " + failing);
            String hint = "ARGO status page: " + uiUrl;
            String declaredStatus = declaredNotLive(declared, type);
            if (declaredStatus != null) {
                // The node says that the capability is not operational, so ARGO agreeing is not a failure.
                if (!"OK".equals(status)) {
                    findings.info("declared-unavailable-confirmed",
                            "ARGO reports the endpoint as " + status + ", as the node declares it " + declaredStatus,
                            type, url, "not OK, as declared", actual, hint);
                } else {
                    findings.warning("declared-unavailable-but-ok",
                            "The node declares the capability " + declaredStatus + " but ARGO reports the endpoint as OK",
                            type, url, "a declared status that matches the endpoint", "declared " + declaredStatus + ", ARGO OK", hint);
                }
            } else if ("CRITICAL".equals(status)) {
                findings.error("core-endpoint-critical", "ARGO reports the endpoint as CRITICAL", type, url, "OK", actual, hint);
            } else if (!"OK".equals(status)) {
                findings.warning("core-endpoint-" + status.toLowerCase(java.util.Locale.ROOT),
                        "ARGO reports the endpoint as " + status, type, url, "OK", actual, hint);
            } else if (!"OK".equals(worst)) {
                findings.info("core-endpoint-recovered", "The endpoint is OK now but was " + worst + " earlier today",
                        type, url, "OK all day", actual, hint);
            }
            compareWithDeclaration(findings, type, url, declared);
        }
        if (declared == null) {
            findings.info("declared-capabilities-not-compared",
                    "ARGO's endpoints were not compared with what the node declares", null, null,
                    "a strict endpoint_report.json to compare with", "none",
                    "Run Node Capabilities in strict mode first");
        }
    }

    /**
     * The status the node declares for the capability type if it declares it, and none of the declarations of that
     * type is operational; otherwise {@code null} (also when there is no declaration to go by).
     */
    private static String declaredNotLive(JsonNode declared, String type) {
        if (declared == null) {
            return null;
        }
        String notLive = null;
        for (JsonNode cap : declared) {
            if (type.equalsIgnoreCase(cap.path("capability_type").asText())) {
                String status = cap.path("declared_status").asText(null);
                if (eu.cessda.pilotnode.capabilities.CapabilitiesModel.declaresLive(status)) {
                    return null;
                }
                notLive = status;
            }
        }
        return notLive;
    }

    private static void compareWithDeclaration(Findings findings, String type, String url, JsonNode declared) {
        if (declared == null || url == null) {
            return;
        }
        if ("Monitoring".equalsIgnoreCase(type)) {
            // The two are different by design: the node's Monitoring capability is the metrics of its own Exchange
            // services, whereas the federation monitors the status of the Core Services that the node uses.
            return;
        }
        List<String> declaredUrls = new java.util.ArrayList<>();
        for (JsonNode cap : declared) {
            if (type.equalsIgnoreCase(cap.path("capability_type").asText())) {
                declaredUrls.add(cap.path("endpoint").asText("").trim());
            }
        }
        if (declaredUrls.isEmpty()) {
            findings.info("monitored-capability-not-declared", "ARGO monitors an endpoint the node does not declare",
                    type, url, "a " + type + " capability in the node's Endpoint", "none declared", null);
        } else if (!declaredUrls.contains(url.trim())) {
            findings.warning("monitored-url-differs", "ARGO monitors a different URL from the one the node declares",
                    type, url, String.join(", ", declaredUrls), url,
                    "Either the node's Endpoint or the federation's monitoring needs updating");
        }
    }

    /** The names of the probes that are not OK, worst first, at most three. */
    private static String failingProbes(JsonNode probes) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode probe : probes) {
            if (!"OK".equals(probe.path("status").asText()) && names.size() < 3) {
                names.add(probe.path("name").asText());
            }
        }
        return String.join(", ", names);
    }
}
