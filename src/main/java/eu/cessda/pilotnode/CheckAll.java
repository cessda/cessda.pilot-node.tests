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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs every data-collection check in one pass: {@link CheckNodeCapabilities}
 * once for the whole Node Registry, then — for every Node it just wrote a
 * summary for — {@link CheckCatalogueServices} (Exchange Services),
 * {@link CheckServiceUptime} (Service Monitoring),
 * {@link CheckCoreIntegrations} (Core Service integrations status) and
 * {@link CheckOtherMetrics} (Federated Search) in turn.
 *
 * <p>This mirrors what an operator would otherwise do by hand from the
 * dashboard's "Run checks" menu, one Node at a time. It is triggered either
 * via {@code POST /api/run/check-all} ({@link CheckRunnerController}) or by
 * the scheduled task in {@link CheckAllScheduler}.</p>
 *
 * <p>A failure in one Node's checks does not stop the run: each of the
 * per-Node checks is attempted independently and failures are counted and
 * logged, so a single unreachable service doesn't prevent every other Node
 * from being checked. The same applies to the initial
 * {@code CheckNodeCapabilities} pass: if it fails (for example while the
 * Federation Registry is unavailable) the run carries on using the Node
 * Registry summary and per-Node {@code endpoint_report.json} files left by the
 * last successful run, and says so in the result. Only if there is no
 * existing {@code node_registry_summary.json} to fall back on is the run
 * aborted, since there is then no list of Nodes to check.</p>
 *
 * <p>Exchange Services specifically needs a Resource Catalogue endpoint for
 * the Node, which is read from that Node's freshly-written
 * {@code endpoint_report.json} — exactly as the "Exchange Services" button
 * on the Node detail page does in JavaScript. A Node with no Resource
 * Catalogue capability is skipped for that one check (not counted as a
 * failure) and still gets Service Monitoring and Federated Search checked.</p>
 */
public class CheckAll {

    private static final Logger log = Logger.getLogger(CheckAll.class.getName());

    /** Quantity used for the Exchange Services check, matching {@link CheckRunnerController}. */
    private static final int CATALOGUE_QUANTITY = 10;

    private CheckAll() {
    }

    /**
     * Runs {@code CheckNodeCapabilities} once, then Exchange Services,
     * Service Monitoring and Federated Search for every Node in the
     * resulting registry, in turn.
     *
     * @param dashboardDir Node Registry / report output directory
     * @param nodeApiKey   API key for {@code CheckNodeCapabilities}
     * @param coreSource   ARGO federation tenant used for the Core Service
     *                     integrations status
     * @param argoApiKey   optional legacy-ARGO-API fallback key for
     *                     {@code CheckServiceUptime}; may be blank
     * @param http         shared HTTP client
     * @param mapper       shared Jackson mapper
     * @return a summary of what ran and what was skipped or failed
     * @throws IOException if {@code node_registry_summary.json} cannot be
     *                      read — including when the initial
     *                      {@code CheckNodeCapabilities} pass failed and there
     *                      is no earlier summary to fall back on
     */
    public static Result run(Path dashboardDir, String nodeApiKey, String argoApiKey,
                              CheckCoreIntegrations.Source coreSource,
                              HttpClient http, ObjectMapper mapper) throws IOException {

        log.info("Check All — starting with CheckNodeCapabilities");
        String capabilitiesError = null;
        List<CheckNodeCapabilities.NodeProblem> nodeProblems = List.of();
        try {
            CheckNodeCapabilities.Result capabilities = CheckNodeCapabilities.run(
                    nodeApiKey,
                    EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON),
                    dashboardDir,
                    http,
                    mapper);
            nodeProblems = capabilities.problems();
        } catch (IOException | RuntimeException e) {
            capabilitiesError = String.valueOf(e.getMessage());
            log.log(Level.WARNING,
                    "Check All — CheckNodeCapabilities failed ({0}); continuing with existing data",
                    capabilitiesError);
        }

        List<String> nodeNames;
        try {
            nodeNames = readNodeNames(dashboardDir, mapper);
        } catch (IOException e) {
            if (capabilitiesError != null) {
                throw new IOException("CheckNodeCapabilities failed (" + capabilitiesError
                        + ") and there is no existing node_registry_summary.json to fall back on", e);
            }
            throw e;
        }
        log.log(Level.INFO, "Check All — CheckNodeCapabilities {0}, checking {1} node(s)",
                new Object[]{capabilitiesError == null ? "done" : "failed (using existing data)",
                        nodeNames.size()});

        Result result = new Result(nodeNames.size());
        result.capabilitiesError = capabilitiesError;
        result.nodeProblems = nodeProblems;
        LocalDate startDate = LocalDate.now().minusMonths(1);
        LocalDate endDate = LocalDate.now();

        for (String nodeName : nodeNames) {
            try {
                runCatalogueServices(dashboardDir, nodeName, mapper, http);
                result.catalogueOk++;
            } catch (SkippedException e) {
                result.catalogueSkipped++;
                log.log(Level.INFO, "Check All — Exchange Services skipped for {0}: {1}",
                        new Object[]{nodeName, e.getMessage()});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (Exception e) {
                result.catalogueFailed++;
                log.log(Level.WARNING, "Check All — Exchange Services failed for {0}: {1}",
                        new Object[]{nodeName, e.getMessage()});
            }

            try {
                CheckServiceUptime.run(nodeName, argoApiKey, startDate, endDate, dashboardDir, http, mapper);
                result.uptimeOk++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (Exception e) {
                result.uptimeFailed++;
                log.log(Level.WARNING, "Check All — Service Monitoring failed for {0}: {1}",
                        new Object[]{nodeName, e.getMessage()});
            }

            try {
                CheckCoreIntegrations.run(dashboardDir, nodeName, coreSource, http, mapper);
                result.coreOk++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (Exception e) {
                result.coreFailed++;
                log.log(Level.WARNING, "Check All — Core Service integrations failed for {0}: {1}",
                        new Object[]{nodeName, e.getMessage()});
            }

            try {
                CheckOtherMetrics.run(dashboardDir, nodeName, http, mapper);
                result.metricsOk++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (Exception e) {
                result.metricsFailed++;
                log.log(Level.WARNING, "Check All — Federated Search failed for {0}: {1}",
                        new Object[]{nodeName, e.getMessage()});
            }
        }

        log.log(Level.INFO, "Check All — finished: {0}", result.summary());
        return result;
    }

    // ── Node list ────────────────────────────────────────────────────────────

    private static List<String> readNodeNames(Path dashboardDir, ObjectMapper mapper) throws IOException {
        Path summaryPath = dashboardDir.resolve("node_registry_summary.json");
        JsonNode root = mapper.readTree(summaryPath.toFile());
        List<String> names = new ArrayList<>();
        for (JsonNode node : root.path("nodes")) {
            String name = node.path("node_name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    // ── Exchange Services (Resource Catalogue) ──────────────────────────────

    /**
     * Reads {@code <dashboardDir>/<nodeName>/endpoint_report.json} and runs
     * {@link CheckCatalogueServices} against its "Resource Catalogue"
     * capability endpoint — the same lookup the Node detail page's
     * "Exchange Services" button performs client-side.
     *
     * @throws SkippedException if the Node has no Resource Catalogue
     *                           capability endpoint to check
     */
    private static void runCatalogueServices(Path dashboardDir, String nodeName, ObjectMapper mapper,
                                              HttpClient http)
            throws IOException, URISyntaxException, InterruptedException, SkippedException {

        Path reportPath = dashboardDir.resolve(nodeName).resolve("endpoint_report.json");
        if (!java.nio.file.Files.isRegularFile(reportPath)) {
            throw new SkippedException("no endpoint_report.json for this node");
        }

        JsonNode root = mapper.readTree(reportPath.toFile());
        String nodePid = root.path("node_pid").asText(null);
        String catalogueUrlString = null;
        for (JsonNode cap : root.path("capabilities")) {
            if ("Resource Catalogue".equals(cap.path("capability_type").asText())) {
                catalogueUrlString = cap.path("endpoint").asText("");
                break;
            }
        }

        if (catalogueUrlString == null || catalogueUrlString.isBlank()) {
            throw new SkippedException("no Resource Catalogue endpoint in endpoint_report.json");
        }

        URI catalogueUrl = new URI(catalogueUrlString);
        CheckCatalogueServices.run(dashboardDir, nodeName,
                (nodePid == null || nodePid.isBlank()) ? null : nodePid,
                catalogueUrl, CATALOGUE_QUANTITY, http, mapper);
    }

    /** Signals that a per-Node check was deliberately skipped, not failed. */
    private static final class SkippedException extends Exception {
        private static final long serialVersionUID = 1L;

        SkippedException(String message) {
            super(message);
        }
    }

    // ── Result ───────────────────────────────────────────────────────────────

    /** Counts of what happened across all Nodes checked in one Check All run. */
    public static final class Result {
        public final int totalNodes;
        public int catalogueOk;
        public int catalogueSkipped;
        public int catalogueFailed;
        public int uptimeOk;
        public int uptimeFailed;
        public int metricsOk;
        public int metricsFailed;
        public int coreOk;
        public int coreFailed;
        /** Non-null if the initial CheckNodeCapabilities pass failed and existing data was used. */
        public String capabilitiesError;
        /** Nodes listed by the registry whose capabilities could not be checked (they still appear in the summary). */
        public List<CheckNodeCapabilities.NodeProblem> nodeProblems = List.of();

        Result(int totalNodes) {
            this.totalNodes = totalNodes;
        }

        /** A short human-readable summary, suitable for {@code JobRecord.markDone(...)}. */
        public String summary() {
            return (capabilitiesError == null ? ""
                    : "Node Capabilities failed (%s) — used existing data. ".formatted(capabilitiesError))
                    + (nodeProblems.isEmpty() ? ""
                    : "Node Capabilities had problems with %d node(s): %s. ".formatted(nodeProblems.size(),
                            nodeProblems.stream().map(p -> p.node() + " (" + p.reason() + ")")
                                    .collect(java.util.stream.Collectors.joining("; "))))
                    + "Checked %d node(s). Exchange Services: %d ok, %d skipped, %d failed. "
                    .formatted(totalNodes, catalogueOk, catalogueSkipped, catalogueFailed)
                    + "Service Monitoring: %d ok, %d failed. ".formatted(uptimeOk, uptimeFailed)
                    + "Core Service integrations: %d ok, %d failed. ".formatted(coreOk, coreFailed)
                    + "Federated Search: %d ok, %d failed.".formatted(metricsOk, metricsFailed);
        }
    }
}
