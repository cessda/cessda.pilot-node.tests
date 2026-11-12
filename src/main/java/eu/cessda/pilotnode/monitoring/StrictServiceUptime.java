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

package eu.cessda.pilotnode.monitoring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cessda.pilotnode.common.ReportFiles;
import eu.cessda.pilotnode.capabilities.CapabilitiesModel;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.Findings;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.ReportFiles;
import static eu.cessda.pilotnode.monitoring.ArgoMetrics.*;

/** The strict Service Monitoring check. */
public final class StrictServiceUptime {

    private StrictServiceUptime() {}

    private static final Logger log = Logger.getLogger(StrictServiceUptime.class.getName());

    /**
     * The strict version of the check: only the Monitoring endpoint the node published is used, exactly as published.
     * There are no fallbacks, no tenant guessing, no page scraping and no legacy API. The one thing added is the
     * reporting period, if the URL names none: the last month, which is reported as a note. The endpoint has to be the ARGO capability-metrics API ({@code …/capabilities/monitoring/metrics});
     * anything else (a dashboard page, no endpoint at all) is a finding saying what would be needed. Everything that
     * goes wrong is a finding in the report, which is written to the node's own folder.
     *
     * @throws IOException if the node has no strict {@code endpoint_report.json} (run Node Capabilities in strict mode first)
     */
    public static void run(String nodeName, Path dashboardDir, HttpClient http, ObjectMapper mapper)
            throws IOException, InterruptedException {
        run(nodeName, dashboardDir, http, mapper, java.time.LocalDate.now());
    }

    /** As {@link #run(String, Path, HttpClient, ObjectMapper)}, with the day that the reporting period ends on. */
    public static void run(String nodeName, Path dashboardDir, HttpClient http, ObjectMapper mapper,
                           java.time.LocalDate today) throws IOException, InterruptedException {

        if (ReportFiles.find(dashboardDir, CheckMode.STRICT, nodeName, "endpoint_report.json", mapper).isEmpty()) {
            throw new IOException("no strict endpoint_report.json for this node: run Node Capabilities in strict mode first");
        }
        MonitoringDeclaration declared = monitoringDeclaration(dashboardDir, CheckMode.STRICT, nodeName, mapper);
        Findings findings = new Findings();
        ArrayNode endpoints = mapper.createArrayNode();
        String dataSource = "unavailable";
        String resolved = null;
        String subject = "Monitoring";
        String endpoint = declared.endpoint();
        String periodStart = null;
        String periodEnd = null;
        String metricsApi = "https://<host>/v1/public/nodes/<NODE>/capabilities/monitoring/metrics";

        if (endpoint == null) {
            findings.error("monitoring-endpoint-missing", "The node declares no Monitoring endpoint", subject, null,
                    "a Monitoring capability whose endpoint is the ARGO capability-metrics API", "none",
                    "Publish a Monitoring capability with endpoint " + metricsApi);
        } else if (!CapabilitiesModel.isHttp(endpoint)) {
            findings.error("endpoint-invalid", "The Monitoring endpoint is not an absolute http(s) URL", subject, endpoint,
                    "an absolute http(s) URL", endpoint, null);
        } else if (!METRICS_URL_PATTERN.matcher(URI.create(endpoint).getPath()).find()) {
            findings.error("monitoring-endpoint-not-a-data-api",
                    "The Monitoring endpoint is not the ARGO capability-metrics API, so it gives no data to read", subject,
                    endpoint, "an endpoint ending in /capabilities/monitoring/metrics", endpoint,
                    "Lenient mode reads the dashboard page to find the results; strict mode needs the API itself: " + metricsApi);
        } else {
            URI uri = URI.create(endpoint);
            String query = uri.getRawQuery();
            boolean namesPeriod = query != null && (query.contains("start-date=") || query.contains("end-date="));
            if (!namesPeriod) {
                // The reporting period is a parameter of the question, not part of the endpoint's identity, and a fixed
                // period cannot be kept up to date by hand: the dashboard asks for the last month, as lenient mode does.
                String start = today.minusMonths(1).toString();
                String end = today.toString();
                uri = URI.create(endpoint + (query == null ? "?" : "&") + "start-date=" + start + "&end-date=" + end);
                periodStart = start;
                periodEnd = end;
                findings.info("monitoring-period-added",
                        "The Monitoring endpoint names no reporting period, so the dashboard asked for the last month",
                        subject, endpoint, "start-date and end-date in the URL, or the dashboard's default period",
                        "requested " + uri, "To report a different period, put start-date and end-date in the endpoint");
            }
            HttpTextResponse response = null;
            try {
                response = getBodyWithStatus(http, uri, "application/json");
            } catch (IOException | RuntimeException e) {
                findings.error("endpoint-unreachable", "The Monitoring endpoint could not be reached", subject, endpoint,
                        "HTTP 200 with the monitoring data", String.valueOf(e.getMessage()), null);
            }
            if (response != null && response.statusCode() != 200) {
                findings.error("endpoint-http-error", "The Monitoring endpoint answered HTTP " + response.statusCode(),
                        subject, endpoint, "HTTP 200 with the monitoring data", "HTTP " + response.statusCode(), null);
            } else if (response != null) {
                try {
                    endpoints = summariseMetrics(mapper, response.body());
                    if (endpoints.isEmpty()) {
                        findings.error("monitoring-no-services", "The Monitoring endpoint listed no services", subject,
                                endpoint, "at least one service with monitoring results", "an empty list", null);
                    } else {
                        dataSource = "declared-monitoring-url";
                        resolved = uri.toString();
                    }
                } catch (IOException e) {
                    findings.error("monitoring-payload-unreadable", "The Monitoring endpoint's answer is not the expected data",
                            subject, endpoint, "{\"data\": [{\"name\": …, \"results\": […]}]}", String.valueOf(e.getMessage()), null);
                }
            }
        }

        ObjectNode report = mapper.createObjectNode();
        report.put("generated", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        report.put("api_source", endpoint == null ? "" : endpoint);
        if (resolved != null) {
            report.put("resolved_data_endpoint", resolved);
        }
        if (periodStart != null) {
            report.put("period_start", periodStart);
            report.put("period_end", periodEnd);
        }
        report.put("data_source", dataSource);
        report.put("project", nodeName);
        report.set("endpoints", endpoints);
        findings.writeTo(report, mapper);

        Path outputDir = CheckMode.STRICT.nodeDir(dashboardDir, nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("argo_uptime_report.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        log.log(Level.INFO, "Strict report complete [{0}] ({1}) - JSON: {2}",
                new Object[]{dataSource, report.path("strict_result").asText(), reportFile});
    }
}
