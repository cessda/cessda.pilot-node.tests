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

package eu.cessda.pilotnode.strict;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Checks a node's capability document against the EOSC Node Endpoint capabilities model, release 1.1.0
 * (https://github.com/madgeek-arc/eosc-node-endpoint, module {@code eosc-node-capabilities-model}).
 *
 * <p>The model: a document has a {@code node_endpoint} and a list of {@code capabilities}; a capability has a
 * {@code capability_type}, one {@code endpoint}, and optionally a {@code version}, an {@code api_spec} (a URI),
 * a {@code protocol} (default {@code REST}) and a {@code status} (default {@code OPERATIONAL}). Protocol and
 * status are extensible strings: the values the model recommends are accepted, others are accepted too and
 * normalised to upper case. There is no list of endpoints inside a capability.</p>
 */
public final class CapabilitiesModel {

    /** The protocols the model recommends. */
    public static final List<String> RECOMMENDED_PROTOCOLS = List.of("REST", "SOAP", "gRPC", "SSE", "WebSocket", "RSocket");
    /** The statuses the model recommends. */
    public static final List<String> RECOMMENDED_STATUSES = List.of("OPERATIONAL", "MAINTENANCE", "UNAVAILABLE");

    private static final Set<String> DOCUMENT_FIELDS = Set.of("node_endpoint", "capabilities");
    private static final Set<String> CAPABILITY_FIELDS =
            Set.of("capability_type", "endpoint", "version", "api_spec", "protocol", "status");

    private static final String MODEL = "the EOSC Node Endpoint capabilities model (release 1.1.0)";

    private CapabilitiesModel() {}

    /** A short name for a capability in findings: its type and, if it has one, its endpoint. */
    public static String subject(JsonNode capability, int index) {
        String type = capability.path("capability_type").asText("").trim();
        String endpoint = capability.path("endpoint").asText("").trim();
        String name = type.isEmpty() ? "capability #" + (index + 1) : type;
        return endpoint.isEmpty() ? name : name + " " + endpoint;
    }

    /** The statuses compared as the model does: trimmed and upper-cased. */
    public static String normaliseStatus(String status) {
        return status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    }

    /** What a node's declared status means for a capability that does not work. */
    public static boolean declaresLive(String declaredStatus) {
        return declaredStatus == null || declaredStatus.isBlank()
                || "OPERATIONAL".equals(normaliseStatus(declaredStatus));
    }

    /** Validates the whole document and returns what is wrong or noteworthy with it. */
    public static Findings validate(JsonNode document) {
        Findings f = new Findings();
        if (!document.isObject()) {
            return f.error("document-not-object", "The Node Endpoint document is not a JSON object", null, null,
                    "an object with node_endpoint and capabilities", document.getNodeType().name().toLowerCase(Locale.ROOT), null);
        }

        // ── document level ─────────────────────────────────────────────
        checkUnknownFields(document, DOCUMENT_FIELDS, null, f, "document");
        JsonNode nodeEndpoint = document.path("node_endpoint");
        if (nodeEndpoint.isMissingNode() || nodeEndpoint.isNull()) {
            f.warning("node-endpoint-missing", "The document does not give its node_endpoint", null, null,
                    "node_endpoint: an absolute URI", "absent", null);
        } else if (!isAbsoluteUri(nodeEndpoint.asText(""))) {
            f.error("node-endpoint-invalid", "node_endpoint is not an absolute URI", null, null,
                    "an absolute URI", nodeEndpoint.asText(""), null);
        }

        JsonNode capabilities = document.path("capabilities");
        if (!capabilities.isArray()) {
            return f.error("capabilities-missing", "The document has no list of capabilities", null, null,
                    "capabilities: an array", capabilities.isMissingNode() ? "absent" : capabilities.getNodeType().name().toLowerCase(Locale.ROOT), null);
        }
        if (capabilities.isEmpty()) {
            f.warning("capabilities-empty", "The document advertises no capabilities", null, null,
                    "at least one capability", "an empty list", null);
        }

        // ── each capability ────────────────────────────────────────────
        int index = 0;
        for (JsonNode capability : capabilities) {
            validateCapability(capability, index++, f);
        }
        return f;
    }

    private static void validateCapability(JsonNode cap, int index, Findings f) {
        if (!cap.isObject()) {
            f.error("capability-not-object", "Capability #" + (index + 1) + " is not a JSON object", "capability #" + (index + 1),
                    null, "an object", cap.getNodeType().name().toLowerCase(Locale.ROOT), null);
            return;
        }
        String subject = subject(cap, index);
        checkUnknownFields(cap, CAPABILITY_FIELDS, subject, f, "capability");

        // capability_type
        if (cap.path("capability_type").asText("").isBlank()) {
            f.error("capability-type-missing", "The capability has no capability_type", subject, null,
                    "capability_type: a non-empty string", "absent or empty", null);
        }

        // status first: it decides how serious a missing endpoint is
        String declared = cap.hasNonNull("status") ? cap.get("status").asText() : null;
        if (cap.has("status") && !cap.get("status").isNull()) {
            if (!cap.get("status").isTextual()) {
                f.warning("status-not-string", "status is not a string", subject, null, "a string", cap.get("status").toString(), null);
            } else if (!RECOMMENDED_STATUSES.contains(normaliseStatus(declared))) {
                f.warning("status-not-recommended", "status '" + declared + "' is not one of the values the model recommends",
                        subject, null, "one of " + String.join(", ", RECOMMENDED_STATUSES), declared,
                        "The model accepts other values, so this is a warning");
            }
        }

        // endpoint
        JsonNode endpoint = cap.path("endpoint");
        String endpointText = endpoint.isTextual() ? endpoint.asText().trim() : "";
        if (endpointText.isEmpty()) {
            boolean live = declaresLive(declared);
            String hint = cap.has("endpoints")
                    ? "The model has one endpoint per capability: list each interface as its own capability entry"
                    : null;
            Finding.Severity severity = live ? Finding.Severity.ERROR : Finding.Severity.WARNING;
            f.add(new Finding(severity, "endpoint-missing",
                    "The capability has no endpoint" + (live ? "" : ", but the node does not say it is operational"),
                    subject, null, "endpoint: an absolute URI",
                    endpoint.isMissingNode() ? "absent" : endpoint.isNull() ? "null" : endpoint.toString(),
                    hint == null && !live ? "A capability that is not live should not be advertised without an endpoint" : hint));
        } else if (!isAbsoluteUri(endpointText)) {
            f.error("endpoint-invalid", "The endpoint is not an absolute URI", subject, endpointText,
                    "an absolute URI such as https://host/path", endpointText, null);
        } else if (!isHttp(endpointText)) {
            f.warning("endpoint-not-http", "The endpoint does not use http or https, so it cannot be probed", subject,
                    endpointText, "http or https", URI.create(endpointText).getScheme(), null);
        }

        // version
        if (cap.has("version") && !cap.get("version").isNull()) {
            if (!cap.get("version").isTextual()) {
                f.warning("version-not-string", "version is not a string", subject, null, "a string such as \"1.0.0\"",
                        cap.get("version").toString(), null);
            } else if (cap.get("version").asText().isBlank()) {
                f.warning("version-empty", "version is empty", subject, null, "a version or no version", "an empty string", null);
            }
        }

        // api_spec
        if (cap.has("api_spec") && !cap.get("api_spec").isNull()) {
            String spec = cap.get("api_spec").asText("");
            if (!cap.get("api_spec").isTextual() || !isAbsoluteUri(spec)) {
                f.error("api-spec-invalid", "api_spec is not an absolute URI", subject, null, "an absolute URI",
                        cap.get("api_spec").toString(), null);
            }
        }

        // protocol
        if (cap.has("protocol") && !cap.get("protocol").isNull()) {
            if (!cap.get("protocol").isTextual()) {
                f.warning("protocol-not-string", "protocol is not a string", subject, null, "a string", cap.get("protocol").toString(), null);
            } else if (!isRecommendedProtocol(cap.get("protocol").asText())) {
                f.warning("protocol-not-recommended",
                        "protocol '" + cap.get("protocol").asText() + "' is not one of the values the model recommends",
                        subject, null, "one of " + String.join(", ", RECOMMENDED_PROTOCOLS), cap.get("protocol").asText(),
                        "The model accepts other values, so this is a warning");
            }
        }
    }

    private static void checkUnknownFields(JsonNode object, Set<String> known, String subject, Findings f, String level) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            String field = entry.getKey();
            if (known.contains(field)) {
                continue;
            }
            String hint = "endpoints".equals(field) && "capability".equals(level)
                    ? "The model has one endpoint per capability: list each interface as its own capability entry"
                    : null;
            f.warning("field-not-in-model", "'" + field + "' is not defined by " + MODEL + " and is ignored", subject, null,
                    "only " + String.join(", ", known), field, hint);
        }
    }

    /** Whether a protocol is a recommended one, compared as the model does (case, spaces, hyphens and underscores ignored). */
    public static boolean isRecommendedProtocol(String protocol) {
        String wanted = normaliseProtocol(protocol);
        return RECOMMENDED_PROTOCOLS.stream().anyMatch(p -> normaliseProtocol(p).equals(wanted));
    }

    private static String normaliseProtocol(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[_\\s-]", "");
    }

    public static boolean isAbsoluteUri(String text) {
        try {
            URI uri = new URI(text.trim());
            return uri.isAbsolute() && uri.getScheme() != null && (uri.getHost() != null || uri.getAuthority() != null
                    || uri.getSchemeSpecificPart() != null && !uri.getSchemeSpecificPart().isBlank() && !isHttpScheme(uri.getScheme()));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    public static boolean isHttp(String text) {
        try {
            URI uri = new URI(text.trim());
            return isHttpScheme(uri.getScheme()) && uri.getHost() != null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
