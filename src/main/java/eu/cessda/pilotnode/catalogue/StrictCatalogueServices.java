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

package eu.cessda.pilotnode.catalogue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import eu.cessda.pilotnode.common.ReportFiles;
import eu.cessda.pilotnode.common.Findings;
import eu.cessda.pilotnode.common.CheckMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static eu.cessda.pilotnode.catalogue.CatalogueReport.*;

/** The strict Exchange Services check. */
public final class StrictCatalogueServices {

    private StrictCatalogueServices() {}

    private static final Logger log = Logger.getLogger(StrictCatalogueServices.class.getName());

    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    /** Reads the node's services exactly as it publishes them; never uses the Sandbox. */
    public static void run(Path dataDir, String nodeName, String nodePid, CatalogueCandidates selector,
                           HttpClient httpClient, ObjectMapper mapper) throws IOException, InterruptedException {
        run(dataDir, nodeName, nodePid, selector, httpClient, mapper, RETRY_DELAY);
    }

    static void run(Path dataDir, String nodeName, String nodePid, CatalogueCandidates selector,
                                  HttpClient httpClient, ObjectMapper mapper, Duration retryDelay)
            throws IOException, InterruptedException {

        Path endpointReportPath = ReportFiles.find(dataDir, CheckMode.STRICT, nodeName, "endpoint_report.json", mapper)
                .orElseThrow(() -> new NoCatalogueEndpointException("no strict endpoint_report.json for this node: run Node"
                        + " Capabilities in strict mode first"));
        JsonNode endpointReport = mapper.readTree(endpointReportPath.toFile());
        String pid = nodePid == null || nodePid.isBlank() ? endpointReport.path("node_pid").asText(null) : nodePid;

        List<eu.cessda.pilotnode.catalogue.CatalogueCandidate> candidates = selector.candidates(endpointReport);
        List<eu.cessda.pilotnode.catalogue.CatalogueCandidate> declared = selector.declaredCandidates(endpointReport);
        if (candidates.isEmpty() && !declared.isEmpty()) {
            // The node says it has a catalogue but publishes no endpoint for it: a finding, not a node to skip.
            Findings findings = new Findings();
            for (var d : declared) {
                findings.error("catalogue-endpoint-missing", "The node declares a " + d.capabilityType() + " but publishes no endpoint for it",
                        d.capabilityType(), null, "an absolute http(s) endpoint", "none",
                        "See the findings of the strict Node Capabilities report for this capability");
            }
            ObjectNode empty = mapper.createObjectNode();
            empty.put("total", 0).put("from", 0).put("to", 0);
            empty.putArray("results");
            Source source = new Source(empty, URI.create(endpointReport.path("node_endpoint").asText("http://invalid.invalid")), null,
                    pid, null, mapper.createArrayNode(), List.of(), List.of(), findings);
            checkAndReport(CheckMode.STRICT.nodeDir(dataDir, nodeName), nodeName, source, httpClient, mapper);
            return;
        }
        if (candidates.isEmpty()) {
            String error = endpointReport.path("error").asText("");
            throw new NoCatalogueEndpointException(error.isBlank()
                    ? "no Service Catalogue or Resource Catalogue endpoint in the strict endpoint_report.json"
                    : "no Service Catalogue or Resource Catalogue endpoint: the strict endpoint_report.json records an error ("
                            + error + ")");
        }
        log.log(Level.INFO, "Strict Exchange Services check for {0}; endpoints as published: {1}", new Object[]{nodeName, candidates});

        StrictCatalogueSelector.Outcome outcome = new StrictCatalogueSelector(selector, httpClient, mapper, retryDelay)
                .select(endpointReport, new CatalogueContext(pid));
        ArrayNode tried = mapper.createArrayNode();
        for (CatalogueCandidates.Attempt attempt : outcome.attempts()) {
            ObjectNode a = tried.addObject();
            a.put("capability_type", attempt.candidate().capabilityType());
            a.put("endpoint", attempt.candidate().endpoint());
            a.put("outcome", attempt.outcome());
        }

        Source source;
        if (outcome.selection().isPresent()) {
            CatalogueCandidates.Selection chosen = outcome.selection().get();
            ObjectNode provenance = mapper.createObjectNode();
            provenance.put("capability_type", chosen.candidate().capabilityType());
            provenance.put("endpoint", chosen.candidate().endpoint());
            provenance.put("protocol", chosen.candidate().protocol());
            provenance.put("adapter", chosen.adapterId());
            provenance.put("format", chosen.format());
            provenance.put("converted", chosen.list().converted());
            source = new Source(chosen.list().root(), URI.create(chosen.candidate().endpoint()), null, pid, provenance, tried,
                    chosen.list().invalid(), chosen.list().warnings(), outcome.findings());
        } else {
            ObjectNode empty = mapper.createObjectNode();
            empty.put("total", 0).put("from", 0).put("to", 0);
            empty.putArray("results");
            source = new Source(empty, URI.create(candidates.get(0).endpoint().trim()), null, pid, null, tried, List.of(),
                    List.of(), outcome.findings());
        }
        checkAndReport(CheckMode.STRICT.nodeDir(dataDir, nodeName), nodeName, source, httpClient, mapper);
    }
}
