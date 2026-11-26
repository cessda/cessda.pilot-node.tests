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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cessda.pilotnode.common.ReportFiles;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.ReportFiles;

/**
 * What the Service Monitoring checks have in common, whichever mode they run in: reading the capability-metrics API
 * of ARGO, summarising its results and finding what a node declares about its monitoring.
 */
public final class ArgoMetrics {

    private ArgoMetrics() {}

    private static final Logger log = Logger.getLogger(ArgoMetrics.class.getName());

    public static final Pattern METRICS_URL_PATTERN =
            Pattern.compile("/capabilities/monitoring/metrics/?$");

    public static final Pattern TENANT_FROM_URL_PATTERN =
            Pattern.compile("/tenants/([^/?#]+)");

    /** Summarises a capability-metrics payload ({@code {"data":[{"name":…,"results":[…]}]}}) per service. */
    public static ArrayNode summariseMetrics(ObjectMapper mapper, String body) throws IOException {
        JsonNode services = mapper.readTree(body).path("data");
        if (!services.isArray()) {
            throw new IOException("Unexpected capability metrics payload shape");
        }
        ArrayNode endpointsOut = mapper.createArrayNode();
        for (JsonNode service : services) {
            String serviceName = service.path("name").asText("(unnamed)");
            endpointsOut.add(summariseResults(mapper, serviceName, "SERVICE", service.path("results")));
        }
        return endpointsOut;
    }

    public record HttpTextResponse(int statusCode, String body) { }

    public static HttpTextResponse getBodyWithStatus(HttpClient http, URI uri, String acceptHeader)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", acceptHeader)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpTextResponse(response.statusCode(), response.body());
    }

    /**
     * Averages a service's daily/monthly {@code results} entries (each carrying
     * {@code uptime}, {@code availability}, {@code reliability}) into the single
     * endpoint summary object used in {@code argo_uptime_report.json}. Shared by
     * every source, since the capability-metrics API and the dashboard's
     * {@code results/groups} API return the same {@code name} + {@code results[]}
     * shape.
     */
    public static ObjectNode summariseResults(ObjectMapper mapper, String name, String type, JsonNode results) {
        double uptimeTotal = 0.0;
        int uptimeDays = 0;
        double availabilityTotal = 0.0;
        int availabilityDays = 0;
        double reliabilityTotal = 0.0;
        int reliabilityDays = 0;

        if (results.isArray()) {
            for (JsonNode day : results) {
                Double uptime = parseMetric(day.path("uptime"));
                if (uptime != null) {
                    uptimeTotal += normalizeUptime(uptime);
                    uptimeDays++;
                }
                Double availability = parseMetric(day.path("availability"));
                if (availability != null) {
                    availabilityTotal += availability;
                    availabilityDays++;
                }
                Double reliability = parseMetric(day.path("reliability"));
                if (reliability != null) {
                    reliabilityTotal += reliability;
                    reliabilityDays++;
                }
            }
        }

        ObjectNode endpointOut = mapper.createObjectNode();
        endpointOut.put("name", name);
        endpointOut.put("type", type);
        Double uptimePercentage = null;
        if (availabilityDays > 0) {
            double avgAvailability = round2(availabilityTotal / availabilityDays);
            endpointOut.put("average_availability", avgAvailability);
            uptimePercentage = avgAvailability;
        } else {
            endpointOut.putNull("average_availability");
        }
        if (uptimePercentage == null && uptimeDays > 0) {
            uptimePercentage = round2(uptimeTotal / uptimeDays);
        }
        if (uptimePercentage != null) {
            endpointOut.put("uptime_percentage", uptimePercentage);
        } else {
            endpointOut.putNull("uptime_percentage");
        }
        if (reliabilityDays > 0) {
            endpointOut.put("average_reliability", round2(reliabilityTotal / reliabilityDays));
        } else {
            endpointOut.putNull("average_reliability");
        }
        endpointOut.put("days_monitored", Math.max(uptimeDays, Math.max(availabilityDays, reliabilityDays)));
        return endpointOut;
    }

    public static Double parseMetric(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    public static double normalizeUptime(double uptime) {
        return uptime <= 1.0 ? uptime * 100.0 : uptime;
    }

    /**
     * What the node says about its monitoring: the node name the Federation Registry gave (the top of
     * {@code endpoint_report.json}), the Monitoring capability's endpoint exactly as declared, and the
     * ARGO tenant in its {@code /tenants/<name>} segment. Each is {@code null} when it is not there.
     */
    public record MonitoringDeclaration(String nodeName, String endpoint, String tenant) { }

    public static MonitoringDeclaration monitoringDeclaration(Path dashboardDir, CheckMode mode, String nodeName,
                                                               ObjectMapper mapper) {
        Path reportPath = ReportFiles.find(dashboardDir, mode, nodeName, "endpoint_report.json", mapper).orElse(null);
        if (reportPath == null) {
            return new MonitoringDeclaration(nodeName, null, null);
        }
        try {
            JsonNode root = mapper.readTree(reportPath.toFile());
            String reportedName = root.path("node_name").asText("").trim();
            String name = reportedName.isEmpty() ? nodeName : reportedName;
            for (JsonNode cap : root.path("capabilities")) {
                if (!"Monitoring".equals(cap.path("capability_type").asText())) {
                    continue;
                }
                String endpoint = cap.path("endpoint").asText("").trim();
                if (endpoint.isEmpty()) {
                    return new MonitoringDeclaration(name, null, null);
                }
                Matcher matcher = TENANT_FROM_URL_PATTERN.matcher(endpoint);
                if (matcher.find()) {
                    return new MonitoringDeclaration(name, endpoint,
                            URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8));
                }
                return new MonitoringDeclaration(name, endpoint, null);
            }
            return new MonitoringDeclaration(name, null, null);
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read {0} to resolve a Monitoring tenant: {1}",
                    new Object[]{reportPath, e.getMessage()});
        }
        return new MonitoringDeclaration(nodeName, null, null);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
