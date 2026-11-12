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

import eu.cessda.pilotnode.federatedsearch.lenient.CheckOtherMetrics;
import eu.cessda.pilotnode.capabilities.CheckNodeCapabilities;
import eu.cessda.pilotnode.core.lenient.CheckCoreIntegrations;
import eu.cessda.pilotnode.core.StrictCoreIntegrations;
import eu.cessda.pilotnode.core.ArgoStatus;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.cessda.pilotnode.monitoring.StrictServiceUptime;
import eu.cessda.pilotnode.monitoring.lenient.CheckServiceUptime;
import eu.cessda.pilotnode.common.ReportFiles;
import eu.cessda.pilotnode.catalogue.NoCatalogueEndpointException;
import eu.cessda.pilotnode.catalogue.StrictCatalogueServices;
import eu.cessda.pilotnode.catalogue.lenient.CatalogueSelector;
import eu.cessda.pilotnode.catalogue.lenient.CheckCatalogueServices;
import eu.cessda.pilotnode.common.CheckMode;

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
     * @param discoveryHubNodeAliases Node name to Sandbox Discovery Hub name, for Metric 9
     *                     (a Node with no entry is queried by its own name)
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
                              ArgoStatus.Source coreSource, CatalogueSelector catalogueSelector,
                              Map<String, String> discoveryHubNodeAliases,
                              HttpClient http, ObjectMapper mapper) throws IOException {
        return run(dashboardDir, nodeApiKey, argoApiKey, coreSource, catalogueSelector, discoveryHubNodeAliases, http,
                mapper, CheckMode.LENIENT);
    }

    /**
     * As {@link #run(Path, String, String, ArgoStatus.Source, CatalogueSelector, Map, HttpClient, ObjectMapper)},
     * in the given mode. In {@link CheckMode#STRICT} every check except Federated Search (which cannot be strict) runs
     * in strict mode; Federated Search is left alone and the result says so. Strict results are written to each node's
     * own folder and never replace the lenient ones, which are in its {@code lenient} folder.
     */
    public static Result run(Path dashboardDir, String nodeApiKey, String argoApiKey,
                              ArgoStatus.Source coreSource, CatalogueSelector catalogueSelector,
                              Map<String, String> discoveryHubNodeAliases,
                              HttpClient http, ObjectMapper mapper, CheckMode mode) throws IOException {

        log.log(Level.INFO, "Check All ({0}) — starting with CheckNodeCapabilities", mode.label());
        String capabilitiesError = null;
        List<CheckNodeCapabilities.NodeProblem> nodeProblems = List.of();
        try {
            CheckNodeCapabilities.Result capabilities = CheckNodeCapabilities.run(
                    nodeApiKey,
                    EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON),
                    dashboardDir,
                    http,
                    mapper,
                    mode);
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
        result.mode = mode;
        result.capabilitiesError = capabilitiesError;
        result.nodeProblems = nodeProblems;
        LocalDate startDate = LocalDate.now().minusMonths(1);
        LocalDate endDate = LocalDate.now();

        for (String nodeName : nodeNames) {
            try {
                runCatalogueServices(dashboardDir, nodeName, catalogueSelector, mapper, http, mode);
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
                if (mode == CheckMode.STRICT) {
                    StrictServiceUptime.run(nodeName, dashboardDir, http, mapper);
                } else {
                    CheckServiceUptime.run(nodeName, argoApiKey, startDate, endDate, dashboardDir, http, mapper);
                }
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
                if (mode == CheckMode.STRICT) {
                    StrictCoreIntegrations.run(dashboardDir, nodeName, coreSource, http, mapper);
                } else {
                    CheckCoreIntegrations.run(dashboardDir, nodeName, coreSource, http, mapper);
                }
                result.coreOk++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (Exception e) {
                result.coreFailed++;
                log.log(Level.WARNING, "Check All — Core Service integrations failed for {0}: {1}",
                        new Object[]{nodeName, e.getMessage()});
            }

            if (mode == CheckMode.STRICT) {
                continue;   // Federated Search has no strict mode; see Result.summary()
            }

            try {
                CheckOtherMetrics.run(dashboardDir, nodeName, http, mapper, discoveryHubNodeAliases);
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
    public static void runCatalogueServices(Path dashboardDir, String nodeName, CatalogueSelector selector,
                                      ObjectMapper mapper, HttpClient http)
            throws IOException, InterruptedException, SkippedException {
        runCatalogueServices(dashboardDir, nodeName, selector, mapper, http, CheckMode.LENIENT);
    }

    /** As above, in the given mode: a strict run reads and writes each node's own folder. */
    public static void runCatalogueServices(Path dashboardDir, String nodeName, CatalogueSelector selector,
                                      ObjectMapper mapper, HttpClient http, CheckMode mode)
            throws IOException, InterruptedException, SkippedException {

        if (ReportFiles.find(dashboardDir, mode, nodeName, "endpoint_report.json", mapper).isEmpty()) {
            throw new SkippedException((mode == CheckMode.STRICT ? "no strict " : "no ") + "endpoint_report.json for this node");
        }

        try {
            if (mode == CheckMode.STRICT) {
                StrictCatalogueServices.run(dashboardDir, nodeName, null, selector.candidateSource(), http, mapper);
            } else {
                CheckCatalogueServices.runSelected(dashboardDir, nodeName, null, CATALOGUE_QUANTITY, selector, http, mapper);
            }
        } catch (NoCatalogueEndpointException e) {
            throw new SkippedException(e.getMessage());
        }
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
        /** The mode the run was in. */
        public CheckMode mode = CheckMode.LENIENT;
        /** Non-null if the initial CheckNodeCapabilities pass failed and existing data was used. */
        public String capabilitiesError;
        /** Nodes listed by the registry whose capabilities could not be checked (they still appear in the summary). */
        public List<CheckNodeCapabilities.NodeProblem> nodeProblems = List.of();

        Result(int totalNodes) {
            this.totalNodes = totalNodes;
        }

        /**
         * A short human-readable summary, suitable for {@code JobRecord.markDone(...)}. It names problem nodes and counts
         * results; why a node failed is in the logs and in its reports, not here.
         */
        public String summary() {
            return (mode == CheckMode.STRICT ? "Strict mode. " : "") + (capabilitiesError == null ? ""
                    : "Node Capabilities failed — used existing data. ")
                    + (nodeProblems.isEmpty() ? ""
                    : "Node Capabilities had problems with %d node(s): %s. ".formatted(nodeProblems.size(),
                            nodeProblems.stream().limit(3).map(CheckNodeCapabilities.NodeProblem::node)
                                    .collect(java.util.stream.Collectors.joining(", "))
                                    + (nodeProblems.size() > 3 ? " and " + (nodeProblems.size() - 3) + " more" : "")))
                    + "Checked %d node(s). Exchange Services: %d ok, %d skipped, %d failed. "
                    .formatted(totalNodes, catalogueOk, catalogueSkipped, catalogueFailed)
                    + "Service Monitoring: %d ok, %d failed. ".formatted(uptimeOk, uptimeFailed)
                    + "Core Service integrations: %d ok, %d failed. ".formatted(coreOk, coreFailed)
                    + (mode == CheckMode.STRICT
                    ? "Federated Search has no strict mode and was not run."
                    : "Federated Search: %d ok, %d failed.".formatted(metricsOk, metricsFailed));
        }
    }
}
