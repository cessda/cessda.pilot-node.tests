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

package eu.cessda.pilotnode.core.lenient;

import eu.cessda.pilotnode.catalogue.lenient.CheckCatalogueServices;
import eu.cessda.pilotnode.monitoring.lenient.CheckServiceUptime;
import eu.cessda.pilotnode.capabilities.CheckNodeCapabilities;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.HttpUtils;
import static eu.cessda.pilotnode.core.ArgoStatus.*;
import eu.cessda.pilotnode.common.CheckMode;

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

        Path outputDir = CheckMode.LENIENT.nodeDir(dashboardDir, nodeName);
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("core_integrations_report.json");

        Feed feed = fetchFeed(httpClient, mapper, source);

        JsonNode group = null;
        for (JsonNode g : feed.root().path("groups")) {
            if (nodeName.equalsIgnoreCase(g.path("name").asText())) {
                group = g;
                break;
            }
        }
        if (group == null) {
            throw new IOException("no service group for node '" + nodeName
                    + "' in ARGO tenant " + source.tenant());
        }

        Collected collected = collectEndpoints(group, nodeName, feed, source, httpClient, mapper);

        ObjectNode report = baseReport(mapper, nodeName, feed, source, group, collected);

        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        log.log(Level.INFO, "Report generated: JSON: {0}", reportFile.toAbsolutePath());
    }

}
