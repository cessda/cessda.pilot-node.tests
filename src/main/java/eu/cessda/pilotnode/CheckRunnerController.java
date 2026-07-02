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

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 *   GET  /api/run/{jobId}/status      – poll the status of any job
 *   GET  /api/run/status              – list all recent job statuses
 * </pre>
 *
 * <h2>Configuration (application.properties)</h2>
 * <pre>
 *   dashboard.data-dir   = ../dashboard/data   # already used by DashboardDataController
 *   check.node-name      = CESSDA              # NODE_NAME arg for all three checks
 *   check.api-key-node   =                     # API key for CheckNodeCapabilities
 *   check.api-key-argo   =                     # fallback ARGO key if not supplied in request
 * </pre>
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

    // ── Config ────────────────────────────────────────────────────────────────

    private final Path dataDirPath;
    private final String nodeName;
    private final String argoApiKey;
    private final String nodeApiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * One executor per check type keeps jobs serialised per type while allowing
     * different check types to run concurrently.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    /** Live and recent job records, keyed by jobId. */
    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public CheckRunnerController(
            @Value("${dashboard.data-dir}")   Path dataDirPath,
            @Value("${check.node-name:}")     String nodeName,
            @Value("${check.api-key-argo:}")  String argoApiKey,
            @Value("${check.api-key-node:}")  String nodeApiKey) {
        this.dataDirPath = dataDirPath;
        this.nodeName    = nodeName;
        this.argoApiKey  = argoApiKey;
        this.nodeApiKey  = nodeApiKey;
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

        JobRecord rec = new JobRecord("node-capabilities");
        jobs.put(rec.getJobId(), rec);

        executor.submit(() -> {
            rec.markRunning();
            try {
                CheckNodeCapabilities checker = new CheckNodeCapabilities(
                        nodeApiKey,
                        CheckNodeCapabilities.OutputFormat.JSON,
                        dataDirPath);
                checker.run();
                rec.markDone("node_registry_summary.json written");
            } catch (Exception e) {
                log.warning("CheckNodeCapabilities failed: " + e.getMessage());
                rec.markError(e.getMessage());
            }
        });

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
            @RequestBody Map<String, String> body) {

        String targetNode   = body.getOrDefault("node", nodeName).strip();
        String catalogueUrl = body.getOrDefault("catalogueUrl", "").strip();
        String nodePid      = body.getOrDefault("nodePid", "").strip();

        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("No node specified in request body and check.node-name is not configured");
        }
        if (catalogueUrl.isBlank()) {
            throw new IllegalArgumentException("catalogueUrl is required — it must be the Resource Catalogue endpoint for this node");
        }

        JobRecord rec = new JobRecord("catalogue-services");
        jobs.put(rec.getJobId(), rec);

        executor.submit(() -> {
            rec.markRunning();
            try {
                // Arg order: NODE_NAME, node_pid, api_base_url, [quantity]
                CheckCatalogueServices.run(dataDirPath, targetNode,
                        nodePid.isBlank() ? null : nodePid,
                        catalogueUrl, 10);
                rec.markDone("catalogue_services_report.json written for " + targetNode);
            } catch (Exception e) {
                log.warning("CheckCatalogueServices failed: " + e.getMessage());
                rec.markError(e.getMessage());
            }
        });

        return rec;
    }

    /**
     * Triggers {@link CheckServiceUptime}.
     *
     * <p>Accepts a JSON body: {@code { "node": "...", "apiKey": "..." }}.
     * {@code node} is the target node name (overrides {@code check.node-name});
     * {@code apiKey} is the ARGO API key entered by the user in the dashboard
     * modal — it is used only for this invocation and never persisted.
     * Falls back to {@code check.api-key-argo} from config if not supplied.</p>
     *
     * @param body JSON body containing {@code node} and {@code apiKey}
     */
    @PostMapping("/service-uptime")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRecord runServiceUptime(@RequestBody Map<String, String> body) {

        String targetNode   = body.getOrDefault("node", nodeName).strip();
        // User-supplied key takes precedence; config value is the fallback
        String targetArgoApiKey = body.getOrDefault("apiKey", argoApiKey).strip();

        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("No node specified in request body and check.node-name is not configured");
        }
        if (targetArgoApiKey.isBlank()) {
            throw new IllegalArgumentException("No ARGO API key provided in request body and check.api-key-argo is not configured");
        }

        JobRecord rec = new JobRecord("service-uptime");
        jobs.put(rec.getJobId(), rec);

        // final for lambda capture; goes out of scope after submit

        executor.submit(() -> {
            rec.markRunning();
            try {
                LocalDate start = LocalDate.now().minusDays(30);
                LocalDate end = LocalDate.now();
                CheckServiceUptime checker = new CheckServiceUptime(targetNode, targetArgoApiKey, start, end, dataDirPath);
                checker.run();
                rec.markDone("argo_uptime_report.json written for " + targetNode);
            } catch (Exception e) {
                log.warning("CheckServiceUptime failed: " + e.getMessage());
                rec.markError(e.getMessage());
            }
        });

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    private ObjectNode configError(IllegalArgumentException ex) {
        ObjectNode err = mapper.createObjectNode();
        err.put("error", ex.getMessage());
        err.put("hint",  "Set the missing property in application.properties");
        return err;
    }
}