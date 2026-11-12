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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.common.Finding;
import eu.cessda.pilotnode.common.OpenApiPayloadCheck;
import eu.cessda.pilotnode.common.Findings;
import eu.cessda.pilotnode.common.CheckMode;
import eu.cessda.pilotnode.catalogue.JsonFetcher;

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
public final class StrictNodeCapabilities {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ATTEMPTS = 3;
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** A node as the Node Registry lists it. */
    public record Node(String name, String id, String pid, String endpoint, String logo, String legalEntityName,
                       String legalEntityRor) {}

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

    /** Checks one node, writes its {@code endpoint_report.json} in the node's own folder, and returns the report. */
    public ObjectNode check(Node node) throws InterruptedException {
        Findings findings = new Findings();
        ArrayNode rows = mapper.createArrayNode();
        Path reportPath = CheckMode.STRICT.nodeDir(outputDir, node.name()).resolve("endpoint_report.json");
        ObjectNode report = baseReport(node, rows, reportPath);

        // ── the node's own address, as the registry gives it ─────────────────
        if (!CapabilitiesModel.isHttp(node.endpoint())) {
            findings.error("registry-node-endpoint-invalid",
                    "The Node Registry gives no usable node_endpoint for this node", null, node.endpoint(),
                    "an absolute http(s) URL", node.endpoint() == null || node.endpoint().isBlank() ? "absent" : node.endpoint(), null);
            report.put("error", "node_endpoint from the registry is not a usable http(s) URL");
            return finish(report, findings, reportPath);
        }

        // ── the capability document ───────────────────────────────────────
        URI endpoint = URI.create(node.endpoint().trim());
        JsonFetcher.Fetched document = JsonFetcher.fetchWithRetries(http, mapper, endpoint, MAX_ATTEMPTS, retryDelay);
        if (!document.ok()) {
            String actual = document.problem() + (document.attempts() > 1 ? " (after " + document.attempts() + " attempts)" : "");
            findings.error("node-endpoint-unavailable", "The Node Endpoint document could not be read", null,
                    endpoint.toString(), "HTTP 200 with a JSON capability document", actual, null);
            report.put("error", "node_endpoint: " + actual);
            return finish(report, findings, reportPath);
        }
        if (document.attempts() > 1) {
            findings.warning("request-retried", "The Node Endpoint document was only read after " + document.attempts() + " attempts",
                    null, endpoint.toString(), "an answer at the first attempt", document.attempts() + " attempts",
                    "Earlier attempts failed; see the logs of the dashboard for why");
        }
        findings.addAll(CapabilitiesModel.validate(document.root()));
        if (document.root().path("node_details").isObject()) {
            report.set("node_details", document.root().get("node_details"));
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
            String endpointText = cap.path("endpoint").isTextual() ? cap.path("endpoint").asText().trim() : "";
            String declared = cap.hasNonNull("status") ? cap.get("status").asText() : null;

            ObjectNode row = rows.addObject();
            row.put("capability_type", cap.path("capability_type").asText(""));
            row.put("endpoint", endpointText);
            row.put("version", textOrNull(cap, "version"));
            row.put("api_spec", textOrNull(cap, "api_spec"));
            row.put("protocol", textOrNull(cap, "protocol"));
            row.put("declared_status", declared);

            total++;
            Probe probe = null;
            if (CapabilitiesModel.isHttp(endpointText)) {
                probe = probe(URI.create(endpointText));
                row.put("status", probe.ok() ? "Available" : "Not available");
                if (probe.code() != null) {
                    row.put("http_code", probe.code());
                } else {
                    row.putNull("http_code");
                }
                if (probe.ok()) {
                    available++;
                }
                probeFindings(findings, subject, endpointText, declared, probe);
            } else {
                // Already reported by the model check (missing, relative or not http); there is nothing to request.
                row.put("status", "Not available");
                row.putNull("http_code");
            }

            checkApiSpec(findings, subject, cap, specs);
            row.set("payload_check", checkPayload(findings, subject, endpointText, textOrNull(cap, "api_spec"), probe, specs));
        }

        report.put("total_capabilities", total);
        report.put("available_capabilities", available);
        return finish(report, findings, reportPath);
    }

    // ── one GET, as published ─────────────────────────────────────────────

    /** What a GET answered: the status, and the body unless it was larger than {@link #MAX_BODY_BYTES}. */
    private record Probe(Integer code, boolean ok, String finalUrl, boolean redirected, String error, String location,
                         byte[] body, boolean bodyTooLarge) {
        Probe(Integer code, boolean ok, String finalUrl, boolean redirected, String error, String location) {
            this(code, ok, finalUrl, redirected, error, location, null, false);
        }
    }

    /** A payload is only checked if it is smaller than this; the limit stops a misbehaving endpoint filling memory. */
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private Probe probe(URI url) throws InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .GET()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "*/*")
                    .timeout(PROBE_TIMEOUT)
                    .build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            boolean redirected = response.previousResponse().isPresent();
            byte[] body = null;
            boolean tooLarge = false;
            try (java.io.InputStream in = response.body()) {
                if (code >= 200 && code < 300) {
                    byte[] read = in.readNBytes(MAX_BODY_BYTES + 1);
                    tooLarge = read.length > MAX_BODY_BYTES;
                    body = tooLarge ? null : read;
                }
            }
            return new Probe(code, code >= 200 && code < 300, response.uri().toString(), redirected, null,
                    response.headers().firstValue("Location").orElse(null), body, tooLarge);
        } catch (IOException e) {
            return new Probe(null, false, null, false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            return new Probe(null, false, null, false, "unusable URL: " + e.getMessage(), null);
        }
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
                                : !note.isEmpty() ? "Publish the URL that answers, or the one the redirect leads to"
                                : null));
    }

    // ── the payload, against the api_spec ──────────────────────────────────

    /**
     * Checks what the endpoint answered against its {@code api_spec}, if that is an OpenAPI document, and returns the
     * result as the row's {@code payload_check}: checked (PASS or FAIL, with the violations), or not checked, with the
     * reason. A failure is an error finding and a pass a note; a payload that could not be checked is only recorded
     * in the row.
     */
    private ObjectNode checkPayload(Findings f, String subject, String url, String spec, Probe probe,
                                    Map<String, JsonFetcher.Fetched> specs) {
        String specUrl = spec == null || spec.isBlank() ? null : spec.trim();
        if (specUrl == null) {
            return notChecked("no api_spec is published", null);
        }
        if (probe == null) {
            return notChecked("the endpoint is not an http(s) URL, so there is no answer to check", specUrl);
        }
        if (!probe.ok()) {
            return notChecked("the endpoint did not answer successfully", specUrl);
        }
        if (!CapabilitiesModel.isHttp(specUrl)) {
            return notChecked("the api_spec is not an http(s) URL", specUrl);
        }
        JsonFetcher.Fetched fetched = specs.get(specUrl);
        if (fetched == null || !fetched.ok()) {
            return notChecked("the api_spec could not be read as a JSON document ("
                    + (fetched == null ? "not requested" : fetched.problem()) + ")", specUrl);
        }
        if (probe.bodyTooLarge() || probe.body() == null) {
            return notChecked("the answer is larger than " + MAX_BODY_BYTES / (1024 * 1024) + " MB", specUrl);
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(probe.body());
        } catch (IOException e) {
            payload = null;
        }
        OpenApiPayloadCheck.Result result = OpenApiPayloadCheck.check(fetched.root(), URI.create(url), payload);
        OpenApiPayloadCheck.addFindings(f, subject, url, specUrl, result);
        return result.toJson(mapper, specUrl);
    }

    private ObjectNode notChecked(String reason, String specUrl) {
        return OpenApiPayloadCheck.Result.notChecked(reason).toJson(mapper, specUrl);
    }

    // ── api_spec and version ──────────────────────────────────────────────

    private void checkApiSpec(Findings f, String subject, JsonNode cap, Map<String, JsonFetcher.Fetched> cache)
            throws InterruptedException {
        String spec = cap.hasNonNull("api_spec") && cap.get("api_spec").isTextual() ? cap.get("api_spec").asText().trim() : null;
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
        if (!root.has("openapi") && !root.has("swagger")) {
            f.warning("api-spec-not-openapi", "api_spec returns JSON that is not an OpenAPI document", subject, spec,
                    expected, "JSON without an openapi or swagger field", null);
            return;
        }
        String specVersion = root.path("info").path("version").asText(null);
        f.info("api-spec-openapi", "api_spec is an OpenAPI document" + (specVersion == null ? "" : " (info.version " + specVersion + ")"),
                subject, spec, null, null, null);
        String declaredVersion = cap.hasNonNull("version") && cap.get("version").isTextual() ? cap.get("version").asText().trim() : null;
        if (declaredVersion != null && specVersion != null && !declaredVersion.equals(specVersion)) {
            f.info("version-differs-from-api-spec", "The capability's version differs from the version in its api_spec", subject,
                    spec, "version " + specVersion + ", as in the specification", declaredVersion,
                    "The model does not say whether the two must match");
        }
    }

    // ── report ────────────────────────────────────────────────────────────

    private ObjectNode baseReport(Node node, ArrayNode rows, Path reportPath) {
        ObjectNode n = mapper.createObjectNode();
        n.put("generated", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        n.put("node_name", node.name());
        n.put("node_id", node.id());
        n.put("node_pid", node.pid());
        n.put("node_endpoint", node.endpoint());
        n.put("logo", node.logo() == null || node.logo().isBlank() ? null : node.logo());
        ObjectNode le = n.putObject("legal_entity");
        le.put("name", node.legalEntityName());
        le.put("ror_id", node.legalEntityRor());
        n.put("total_capabilities", 0);
        n.put("available_capabilities", 0);
        n.put("report_file", reportPath.toString().replace('\\', '/'));
        n.set("capabilities", rows);
        return n;
    }

    private ObjectNode finish(ObjectNode report, Findings findings, Path reportPath) {
        findings.writeTo(report, mapper);
        if (writeJson && !report.path("node_name").asText("").isBlank()) {
            try {
                Files.createDirectories(reportPath.getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
            } catch (IOException e) {
                report.put("report_write_error", e.getMessage());
            }
        }
        return report;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
