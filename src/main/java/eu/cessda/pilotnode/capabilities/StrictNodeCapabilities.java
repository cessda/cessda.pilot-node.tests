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

package eu.cessda.pilotnode.capabilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cessda.pilotnode.catalogue.JsonFetcher;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.common.Finding;
import eu.cessda.pilotnode.common.Findings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * The strict version of the Node Capabilities check for one node.
 *
 * <p>Every URL is used exactly as it was published: the node's Node Endpoint address as the registry gives it, and
 * each capability's endpoint and api_spec as the node's document gives them. Each endpoint is requested once with
 * {@code GET}; the dashboard does not try {@code HEAD} first, does not keep an earlier answer when the document
 * cannot be read, and does not interpret anything the model does not define. The document is checked against the
 * capabilities model (see {@link CapabilitiesModel}), and everything that is wrong or noteworthy is recorded as a
 * finding. A failed request to the node's endpoint is repeated (as it is in lenient mode) but a repeat is itself
 * reported, as a warning.</p>
 */
public final class StrictNodeCapabilities implements NodeCapabilities {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ATTEMPTS = 3;
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";


    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Path outputDir;
    private final Duration retryDelay;
    private final boolean writeJson;

    /**
     * @param outputDir the dashboard data directory (the reports go to {@link CheckMode#nodeDir})
     */
    public StrictNodeCapabilities(HttpClient http, ObjectMapper mapper, Path outputDir, Duration retryDelay, boolean writeJson) {
        this.http = http;
        this.mapper = mapper;
        this.outputDir = outputDir;
        this.retryDelay = retryDelay;
        this.writeJson = writeJson;
    }

    private static void probeFindings(Findings f, String subject, String url, String declared, Probe probe) {
        boolean live = CapabilitiesModel.declaresLive(declared);
        if (probe.ok()) {
            if (probe.redirected()) {
                f.warning("endpoint-redirected", "The endpoint redirects instead of answering at the published URL", subject, url,
                        "an answer at the URL as published", "redirected to " + probe.finalUrl(),
                        "Publish the URL that answers");
            }
            if (!live) {
                f.warning("status-differs-from-probe", "The node declares status " + declared + " but the endpoint answers", subject,
                        url, "an endpoint that does not answer, as declared", "HTTP " + probe.code(), null);
            }
            return;
        }
        String note = probe.code() == null ? "" : JsonFetcher.redirectNote(probe.code(), probe.location());
        String actual = probe.code() != null ? "HTTP " + probe.code() + note : probe.error();
        String code = probe.code() == null ? "endpoint-unreachable"
                : note.isEmpty() ? "endpoint-http-error" : "endpoint-redirect-not-followed";
        String message = "GET " + url + " did not succeed: " + actual;
        Finding.Severity severity = live ? Finding.Severity.ERROR : Finding.Severity.INFO;
        f.add(new Finding(severity, code, message + (live ? "" : " (the node declares status " + declared + ")"), subject, url,
                "a 2xx answer to GET " + url, actual,
                probe.code() != null && probe.code() == 405 ? "The endpoint refuses GET; lenient mode would retry with HEAD or GET only for 404, 405 and 501"
                        : probe.location() != null && probe.location().startsWith("http://") && url.startsWith("https://")
                                ? "The redirect leads from https to http, which is never followed. Publish the URL that answers."
                        : note.isEmpty() ? null
                        : "Publish the URL that answers, or the one the redirect leads to"));
    }

    // ── one GET, as published ─────────────────────────────────────────────

    private record Probe(Integer code, boolean ok, String finalUrl, boolean redirected, String error, String location) {}

    private Probe probe(URI url) throws InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .GET()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "*/*")
                    .timeout(PROBE_TIMEOUT)
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            boolean redirected = response.previousResponse().isPresent();
            return new Probe(code, code >= 200 && code < 300, response.uri().toString(), redirected, null,
                    response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            return new Probe(null, false, null, false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            return new Probe(null, false, null, false, "unusable URL: " + e.getMessage(), null);
        }
    }

    /** Checks one node, writes its {@code endpoint_report.json} in the strict directory, and returns the report. */
    public ObjectNode check(Node node) throws InterruptedException {
        Findings findings = new Findings();
        ArrayNode rows = mapper.createArrayNode();
        Path reportPath = CheckMode.STRICT.nodeDir(outputDir, node.name()).resolve("endpoint_report.json");
        ObjectNode report = baseReport(node, rows, reportPath);

        // ── the node's own address, as the registry gives it ─────────────────
        if (node.endpoint() == null || !CapabilitiesModel.isHttp(node.endpoint())) {
            findings.error("registry-node-endpoint-invalid",
                    "The Node Registry gives no usable node_endpoint for this node", null, node.endpoint(),
                    "an absolute http(s) URL", node.endpoint() == null ? "absent" : node.endpoint(), null);
            report.put("error", "node_endpoint from the registry is not a usable http(s) URL");
            return finish(report, findings, reportPath);
        }

        // ── the capability document ───────────────────────────────────────
        JsonFetcher.Fetched document = JsonFetcher.fetchWithRetries(http, mapper, URI.create(node.endpoint()), MAX_ATTEMPTS, retryDelay);
        if (!document.ok()) {
            String actual = document.problem() + (document.attempts() > 1 ? " (after " + document.attempts() + " attempts)" : "");
            findings.error("node-endpoint-unavailable", "The Node Endpoint document could not be read", null,
                    node.endpoint(), "HTTP 200 with a JSON capability document", actual, null);
            report.put("error", "node_endpoint: " + actual);
            return finish(report, findings, reportPath);
        }
        if (document.attempts() > 1) {
            findings.warning("request-retried", "The Node Endpoint document was only read after " + document.attempts() + " attempts",
                    null, node.endpoint(), "an answer at the first attempt", document.attempts() + " attempts",
                    "Earlier attempts failed; see the logs of the dashboard for why");
        }
        findings.addAll(CapabilitiesModel.validate(document.root()));
        if (document.root().path(NODE_DETAILS_FIELD).isObject()) {
            report.set(NODE_DETAILS_FIELD, document.root().get(NODE_DETAILS_FIELD));
        }

        // ── each capability, as published ────────────────────────────────
        Map<String, JsonFetcher.Fetched> specs = new HashMap<>();
        int total = 0;
        int available = 0;
        int index = 0;
        for (JsonNode cap : document.root().path("capabilities")) {
            int position = index++;
            if (!cap.isObject()) {
                continue;
            }
            String subject = CapabilitiesModel.subject(cap, position);
            String endpointText = cap.path(ENDPOINT_FIELD).isTextual() ? cap.path(ENDPOINT_FIELD).asText().trim() : "";
            String declared = cap.hasNonNull(STATUS_FIELD) ? cap.get(STATUS_FIELD).asText() : null;

            ObjectNode row = rows.addObject();
            row.put(CAPABILITY_TYPE_FIELD, cap.path(CAPABILITY_TYPE_FIELD).asText(""));
            row.put(ENDPOINT_FIELD, endpointText);
            row.put(VERSION_FIELD, textOrNull(cap, VERSION_FIELD));
            row.put(API_SPEC_FIELD, textOrNull(cap, API_SPEC_FIELD));
            row.put(PROTOCOL_FIELD, textOrNull(cap, PROTOCOL_FIELD));
            row.put(DECLARED_STATUS_FIELD, declared);

            total++;
            if (CapabilitiesModel.isHttp(endpointText)) {
                Probe probe = probe(URI.create(endpointText));
                row.put(STATUS_FIELD, probe.ok() ? "Available" : "Not available");
                if (probe.code() != null) {
                    row.put(HTTP_CODE_FIELD, probe.code());
                } else {
                    row.putNull(HTTP_CODE_FIELD);
                }
                if (probe.ok()) {
                    available++;
                }
                probeFindings(findings, subject, endpointText, declared, probe);
            } else {
                // Already reported by the model check (missing, relative or not http); there is nothing to request.
                row.put("status", "Not available");
                row.putNull(HTTP_CODE_FIELD);
            }

            checkApiSpec(findings, subject, cap, specs);
        }

        report.put(TOTAL_CAPABILITIES_FIELD, total);
        report.put(AVAILABLE_CAPABILITIES_FIELD, available);
        return finish(report, findings, reportPath);
    }

    // ── api_spec and version ──────────────────────────────────────────────

    private void checkApiSpec(Findings f, String subject, JsonNode cap, Map<String, JsonFetcher.Fetched> cache)
            throws InterruptedException {
        String spec = cap.hasNonNull(API_SPEC_FIELD) && cap.get(API_SPEC_FIELD).isTextual() ? cap.get(API_SPEC_FIELD).asText().trim() : null;
        if (spec == null || !CapabilitiesModel.isHttp(spec)) {
            return;                         // absent, or already reported as not a URI
        }
        JsonFetcher.Fetched fetched = cache.get(spec);
        if (fetched == null) {
            fetched = JsonFetcher.fetch(http, mapper, URI.create(spec));
            cache.put(spec, fetched);
        }
        String expected = "a machine-readable specification, such as an OpenAPI JSON document";
        if (!fetched.ok()) {
            if ("not JSON".equals(fetched.problem())) {
                f.warning("api-spec-not-machine-readable", "api_spec answers, but not with a machine-readable specification",
                        subject, spec, expected, "a document that is not JSON (probably a documentation page)",
                        "Point api_spec at the specification itself, for example /openapi.json, so that responses can be checked against it");
            } else {
                f.error("api-spec-unreachable", "api_spec could not be read", subject, spec, expected, fetched.problem(), null);
            }
            return;
        }
        JsonNode root = fetched.root();
        if (!root.has(OPENAPI_FIELD) && !root.has(SWAGGER_FIELD)) {
            f.warning("api-spec-not-openapi", "api_spec returns JSON that is not an OpenAPI document", subject, spec,
                    expected, "JSON without an openapi or swagger field", null);
            return;
        }
        String specVersion = root.path("info").path(VERSION_FIELD).asText(null);
        f.info("api-spec-openapi", "api_spec is an OpenAPI document" + (specVersion == null ? "" : " (info.version " + specVersion + ")"),
                subject, spec, null, null, null);
        String declaredVersion = cap.hasNonNull(VERSION_FIELD) && cap.get(VERSION_FIELD).isTextual() ? cap.get(VERSION_FIELD).asText().trim() : null;
        if (declaredVersion != null && specVersion != null && !declaredVersion.equals(specVersion)) {
            f.info("version-differs-from-api-spec", "The capability's version differs from the version in its api_spec", subject,
                    spec, "version " + specVersion + ", as in the specification", declaredVersion,
                    "The model does not say whether the two must match");
        }
    }

    // ── report ────────────────────────────────────────────────────────────

    private ObjectNode baseReport(Node node, ArrayNode rows, Path reportPath) {
        ObjectNode n = mapper.createObjectNode();
        n.put(GENERATED_FIELD, OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        n.put(NODE_NAME_PATH, node.name());
        n.put(NODE_ID_PATH, node.id());
        n.put(NODE_PID_PATH, node.pid());
        n.put(NODE_ENDPOINT_PATH, node.endpoint() != null ? node.endpoint() : null);
        n.put(LOGO_PATH, node.logo() == null || node.logo().isBlank() ? null : node.logo());
        ObjectNode le = n.putObject(LEGAL_ENTITY_PATH);
        le.put(NAME_PATH, node.legalEntityName());
        le.put(ROR_ID_PATH, node.legalEntityRor());
        n.put(TOTAL_CAPABILITIES_FIELD, 0);
        n.put(AVAILABLE_CAPABILITIES_FIELD, 0);
        n.put(REPORT_FILE_FIELD, reportPath.toString().replace('\\', '/'));
        n.set(CAPABILITIES_FIELD, rows);
        return n;
    }

    private ObjectNode finish(ObjectNode report, Findings findings, Path reportPath) {
        findings.writeTo(report, mapper);
        if (writeJson && !report.path(NODE_NAME_PATH).asText("").isBlank()) {
            try {
                Files.createDirectories(reportPath.getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
            } catch (IOException e) {
                report.put(REPORT_WRITE_ERROR_FIELD, e.getMessage());
            }
        }
        return report;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
