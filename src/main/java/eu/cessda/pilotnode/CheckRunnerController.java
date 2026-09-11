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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST controller that triggers the three data-collection checks
 * ({@code CheckNodeCapabilities}, {@code CheckCatalogueServices},
 * {@code CheckServiceUptime}) and exposes job status for polling.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   POST /api/run/node-capabilities   – run CheckNodeCapabilities
 *   POST /api/run/catalogue-services  – run CheckCatalogueServices
 *   POST /api/run/service-uptime      – run CheckServiceUptime
 *   POST /api/run/other-metrics       – run CheckOtherMetrics
 *   POST /api/run/check-all           – run CheckNodeCapabilities, then all of the
 *                                        above for every Node in turn (see {@link CheckAll})
 *   GET  /api/run/{jobId}/status      – poll the status of any job
 *   GET  /api/run/status              – list all recent job statuses
 * </pre>
 *
 * <h2>Configuration (application.properties)</h2>
 * <pre>
 *   dashboard.data-dir   = ../dashboard/data   # already used by DashboardDataController
 *   check.node-name      = CESSDA              # NODE_NAME arg for all three checks
 *   check.api-key-node   =                     # API key for CheckNodeCapabilities
 *   check.api-key-argo   =                     # optional legacy-ARGO-API fallback key for CheckServiceUptime
 * </pre>
 *
 * <p>Check All can also be run on a schedule instead of (or as well as) by
 * hand — see {@link CheckAllScheduler} for its
 * {@code check.check-all.scheduled.*} settings.</p>
 *
 * <p>Jobs run asynchronously on a dedicated single-thread executor so that
 * only one job of each type can run at a time, and the HTTP request returns
 * immediately with a job ID. The caller polls {@code /api/run/{jobId}/status}
 * until the status is {@code DONE} or {@code ERROR}.</p>
 */
@RestController
@RequestMapping("/api/run")
public class CheckRunnerController {

    private static final Logger log = Logger.getLogger(CheckRunnerController.class.getName());

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String ERROR_KEY = "error";
    private static final String HINT_KEY = "hint";


    // ── Config ────────────────────────────────────────────────────────────────

    private final Path dataDirPath;
    private final String nodeName;
    private final String argoApiKey;
    private final String nodeApiKey;
    private final JobRunner jobRunner;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // ── State ─────────────────────────────────────────────────────────────────

    /** Live and recent job records, keyed by jobId. */
    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CheckRunnerController(
            @Value("${dashboard.data-dir}")   Path dataDirPath,
            @Value("${check.node-name:}")     String nodeName,
            @Value("${check.api-key-argo:}")  String argoApiKey,
            @Value("${check.api-key-node:}") String nodeApiKey,
            JobRunner jobRunner,
            HttpClient httpClient,
            ObjectMapper mapper) {
        this.dataDirPath = dataDirPath;
        this.nodeName    = nodeName;
        this.argoApiKey  = argoApiKey;
        this.nodeApiKey  = nodeApiKey;
        this.jobRunner = jobRunner;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    // ── Trigger endpoints ─────────────────────────────────────────────────────

    /**
     * Triggers {@link CheckNodeCapabilities}.
     *
     * <p>Requires {@code check.node-name} and {@code check.api-key-node} to be
     * set in {@code application.properties}.</p>
     *
     * @throws IllegalStateException if {@code check.api-key-node} or
     *                               {@code check.node-name} is unset
     */
    @PostMapping("/node-capabilities")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRecord runNodeCapabilities() {
        if (nodeApiKey.isBlank()) {
            throw new IllegalStateException("check.api-key-node is not configured");
        }
        if (nodeName.isBlank()) {
            throw new IllegalStateException("check.node-name is not configured");
        }

        JobRecord rec = jobRunner.start("node-capabilities", record -> {
            CheckNodeCapabilities.run(
                    nodeApiKey,
                    EnumSet.of(CheckNodeCapabilities.OutputFormat.JSON),
                    dataDirPath,
                    httpClient,
                    mapper);
            record.markDone("node_registry_summary.json written");
        });

        jobs.put(rec.getJobId(), rec);

        return rec;
    }

    /**
     * Triggers {@link CheckOtherMetrics}.
     *
     * <p>Accepts an optional JSON body: {@code { "node": "..." } }.
     * {@code node} is the target node name (overrides {@code check.node-name}).</p>
     *
     * <p>Unlike {@code CheckCatalogueServices} and {@code CheckServiceUptime},
     * no endpoint URL needs to be supplied by the caller: this check resolves
     * every Front Office endpoint and Node PID it needs (including the
     * Sandbox's) from {@code node_registry_summary.json} and the per-Node
     * {@code endpoint_report.json} files already written by
     * {@code CheckNodeCapabilities}. Run {@code /api/run/node-capabilities}
     * first — or whenever the registry may have changed — so those PIDs and
     * endpoints are current before triggering this check.</p>
     *
     * @param body optional JSON body containing {@code node}
     */
    @PostMapping("/other-metrics")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRecord runOtherMetrics(
            @RequestBody(required = false) Map<String, String> body) {

        String targetNode = (body != null ? body.getOrDefault("node", nodeName) : nodeName).strip();

        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("No node specified in request body and check.node-name is not configured");
        }

        JobRecord rec = jobRunner.start("other-metrics", record -> {
            CheckOtherMetrics.run(dataDirPath, targetNode, httpClient, mapper);
            record.markDone("front_office_metrics_report.json written for " + targetNode);
        });

        jobs.put(rec.getJobId(), rec);

        return rec;
    }

    /**
     * Triggers {@link CheckCatalogueServices}.
     *
     * <p>Accepts a JSON body: {@code { "node": "...", "catalogueUrl": "..." }}.
     * {@code node} is the target node name (overrides {@code check.node-name});
     * {@code catalogueUrl} is the Resource Catalogue API base URL read from that
     * node's {@code endpoint_report.json} (capability_type "Resource Catalogue")
     * by the dashboard before making this call.</p>
     *
     * @param body JSON body containing {@code node} and {@code catalogueUrl}
     */
    @PostMapping("/catalogue-services")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRecord runCatalogueServices(
            @RequestBody Map<String, String> body) throws URISyntaxException {

        String targetNode   = body.getOrDefault("node", nodeName).strip();
        String catalogueUrlString = body.getOrDefault("catalogueUrl", "").strip();
        String nodePid      = body.getOrDefault("nodePid", "").strip();

        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("No node specified in request body and check.node-name is not configured");
        }
        if (catalogueUrlString.isBlank()) {
            throw new IllegalArgumentException("catalogueUrl is required — it must be the Resource Catalogue endpoint for this node");
        }

        URI catalogueUrl = new URI(catalogueUrlString);

        JobRecord rec = jobRunner.start("catalogue-services", record -> {
                // Arg order: NODE_NAME, node_pid, api_base_url, [quantity]
                CheckCatalogueServices.run(dataDirPath, targetNode,
                        nodePid.isBlank() ? null : nodePid,
                        catalogueUrl, 10, httpClient, mapper);
                record.markDone("catalogue_services_report.json written for " + targetNode);
            }
        );

        jobs.put(rec.getJobId(), rec);

        return rec;
    }

    /**
     * Triggers {@link CheckServiceUptime}.
     *
     * <p>Accepts an optional JSON body: {@code { "node": "...", "apiKey": "..." }}.
     * {@code node} is the target node name (overrides {@code check.node-name}).
     * No API key is required — the default capability-metrics API and the
     * dashboard scrape are both public. {@code apiKey} is optional and only
     * used if both of those sources are unavailable and this falls back to
     * the legacy ARGO API; it falls back to {@code check.api-key-argo} from
     * config if not supplied, and may be left blank entirely.</p>
     *
     * @param body optional JSON body containing {@code node} and/or {@code apiKey}
     */
    @PostMapping("/service-uptime")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRecord runServiceUptime(@RequestBody(required = false) Map<String, String> body) {

        String targetNode = (body != null ? body.getOrDefault("node", nodeName) : nodeName).strip();
        // Optional: only used by the legacy ARGO API fallback. User-supplied
        // key takes precedence; config value is the fallback; blank is fine.
        String targetArgoApiKey = (body != null ? body.getOrDefault("apiKey", argoApiKey) : argoApiKey).strip();

        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("No node specified in request body and check.node-name is not configured");
        }

        JobRecord rec = jobRunner.start("service-uptime", record -> {
            LocalDate start = LocalDate.now().minusMonths(1);
            LocalDate end = LocalDate.now();
            CheckServiceUptime.run(targetNode, targetArgoApiKey, start, end, dataDirPath, httpClient, mapper);
            record.markDone("argo_uptime_report.json written for " + targetNode);
        });

        jobs.put(rec.getJobId(), rec);

        return rec;
    }

    /**
     * Triggers {@link CheckAll}: runs {@link CheckNodeCapabilities} once for
     * the whole Node Registry, then Exchange Services, Service Monitoring
     * and Federated Search for every Node it just wrote a summary for, one
     * Node at a time.
     *
     * <p>Requires {@code check.api-key-node} to be set, same as
     * {@link #runNodeCapabilities()}. {@code check.api-key-argo} is used the
     * same way it is for {@link #runServiceUptime}: optional, only needed if
     * the legacy ARGO API fallback is reached.</p>
     *
     * <p>A failure checking one Node (or one of its three checks) does not
     * stop the run — see {@link CheckAll} for how failures and skips are
     * counted and reported in the job's final message.</p>
     *
     * @throws IllegalStateException if {@code check.api-key-node} is unset
     */
    @PostMapping("/check-all")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRecord runCheckAll() {
        if (nodeApiKey.isBlank()) {
            throw new IllegalStateException("check.api-key-node is not configured");
        }

        JobRecord rec = jobRunner.start("check-all", record -> {
            CheckAll.Result result = CheckAll.run(dataDirPath, nodeApiKey, argoApiKey, httpClient, mapper);
            record.markDone(result.summary());
        });

        jobs.put(rec.getJobId(), rec);

        return rec;
    }

    // ── Status endpoints ──────────────────────────────────────────────────────

    /** Returns the status of a single job by its ID. */
    @GetMapping("/{jobId}/status")
    public ResponseEntity<JobRecord> getJobStatus(@PathVariable String jobId) {
        JobRecord rec = jobs.get(jobId);
        if (rec == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rec);
    }

    /** Returns the status of all recent jobs (most-recent last). */
    @GetMapping("/status")
    public List<JobRecord> getAllStatuses() {
        List<JobRecord> list = new ArrayList<>(jobs.values());
        list.sort(null);
        return list;
    }

    // ── Exception handlers ────────────────────────────────────────────────────
    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    private Map<String, String> configError(IllegalArgumentException ex) {
        return Map.of(
                ERROR_KEY, ex.getMessage(),
                HINT_KEY, "Add the missing argument to the request"
        );
    }

    // ── Exception handlers ────────────────────────────────────────────────────
    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    private Map<String, String> configError(URISyntaxException ex) {
        return Map.of(
                ERROR_KEY, ex.getMessage(),
                HINT_KEY, "Correct the URI"
        );
    }

    @ExceptionHandler
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    private Map<String, String> configError(IllegalStateException ex) {
        return Map.of(
                ERROR_KEY, ex.getMessage(),
                HINT_KEY, "Set the missing property in application.properties"
        );
    }
}