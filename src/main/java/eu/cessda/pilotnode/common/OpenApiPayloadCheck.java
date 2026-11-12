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

package eu.cessda.pilotnode.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.SpecVersion;

/**
 * Checks what an endpoint answered against the OpenAPI document its capability names as {@code api_spec}: the
 * operation is found from the endpoint's URL (its path, under the specification's servers or base path, as a
 * {@code GET}), and the answer is validated against that operation's success response schema.
 *
 * <p>The check says why when it cannot be made (the specification has no such path, no GET, no success response or no
 * schema, or it refers to other documents, which are never fetched). It is {@link Result#checked() checked} only when
 * a schema was found and the answer was validated against it.</p>
 */
public final class OpenApiPayloadCheck {

    /** How many violations a result keeps; the count is of all of them. */
    public static final int MAX_VIOLATIONS = 10;

    private OpenApiPayloadCheck() {}

    /**
     * The outcome of one check.
     *
     * @param checked    whether the answer was validated against a schema
     * @param valid      when checked, whether it conforms (always false when not checked)
     * @param reason     when not checked, why not
     * @param operation  the operation the answer was checked against, such as {@code GET /service/all}
     * @param violations the first {@link #MAX_VIOLATIONS} violations
     * @param violationCount how many there were in all
     */
    public record Result(boolean checked, boolean valid, String reason, String operation, List<String> violations,
                         int violationCount) {

        /** A result for a payload that could not be checked, with the reason. */
        public static Result notChecked(String reason) {
            return new Result(false, false, reason, null, List.of(), 0);
        }

        /** The result as it is written into a report. */
        public ObjectNode toJson(ObjectMapper mapper, String specUrl) {
            ObjectNode n = mapper.createObjectNode();
            n.put("status", checked ? "checked" : "not_checked");
            if (specUrl != null) {
                n.put("api_spec", specUrl);
            }
            if (checked) {
                n.put("result", valid ? "PASS" : "FAIL");
                n.put("operation", operation);
                n.put("violation_count", violationCount);
                var list = n.putArray("violations");
                violations.forEach(list::add);
            } else {
                n.put("reason", reason);
            }
            return n;
        }
    }

    /**
     * Checks {@code payload} (what a GET of {@code endpoint} answered) against {@code spec}.
     *
     * @param payload the parsed answer, or {@code null} if it was not JSON
     */
    public static Result check(JsonNode spec, URI endpoint, JsonNode payload) {
        boolean swagger2 = spec.has("swagger");
        if (!spec.has("openapi") && !swagger2) {
            return Result.notChecked("the api_spec is not an OpenAPI document");
        }
        String external = externalRef(spec);
        if (external != null) {
            return Result.notChecked("the specification refers to another document (" + external
                    + "), which is not fetched");
        }

        Located located = locate(spec, endpoint, swagger2);
        if (located.reason() != null) {
            return Result.notChecked(located.reason());
        }
        if (payload == null) {
            return new Result(true, false, null, located.operation(),
                    List.of("the answer is not valid JSON, but the specification describes a JSON response"), 1);
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("$ref", located.pointer());
            if (spec.has("components")) {
                wrapper.set("components", spec.get("components"));
            }
            if (spec.has("definitions")) {
                wrapper.set("definitions", spec.get("definitions"));
            }
            if (spec.has("paths")) {
                wrapper.set("paths", spec.get("paths"));
            }
            JsonMetaSchema meta = JsonMetaSchema.builder(JsonMetaSchema.getV202012())
                    .keyword(new NonValidationKeyword("components"))
                    .keyword(new NonValidationKeyword("definitions"))
                    .keyword(new NonValidationKeyword("paths"))
                    .build();
            JsonSchema schema = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                    .metaSchema(meta)
                    .build()
                    .getSchema(wrapper);
            List<String> all = schema.validate(payload).stream().map(m -> m.getMessage()).sorted().toList();
            return new Result(true, all.isEmpty(), null, located.operation(),
                    all.size() > MAX_VIOLATIONS ? all.subList(0, MAX_VIOLATIONS) : all, all.size());
        } catch (RuntimeException e) {
            return Result.notChecked("the response schema could not be used: " + e.getMessage());
        }
    }

    /**
     * Adds the findings for a result: an error when the answer breaks the specification (with the first violations), a
     * note when it follows it. A payload that could not be checked adds nothing; the caller records the reason.
     */
    public static void addFindings(Findings f, String subject, String url, String specUrl, Result result) {
        if (!result.checked()) {
            return;
        }
        if (result.valid()) {
            f.info("payload-matches-api-spec", "The answer follows the api_spec (" + result.operation() + ")", subject, url,
                    "an answer that follows the response schema of " + result.operation(), "conforms", null);
            return;
        }
        String first = String.join("; ", result.violations().subList(0, Math.min(3, result.violations().size())));
        f.error("payload-differs-from-api-spec", "The answer does not follow the api_spec (" + result.operation() + ")", subject, url,
                "an answer that follows the response schema of " + result.operation() + " in " + specUrl,
                first + (result.violationCount() > 3 ? " (and " + (result.violationCount() - 3) + " more)" : ""), null);
    }

    // ── finding the schema ───────────────────────────────────────────────────

    private record Located(String pointer, String operation, String reason) {
        static Located fail(String reason) {
            return new Located(null, null, reason);
        }
    }

    private static Located locate(JsonNode spec, URI endpoint, boolean swagger2) {
        String requestPath = endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
        JsonNode paths = spec.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            return Located.fail("the specification lists no paths");
        }

        String matched = null;
        int bestParams = Integer.MAX_VALUE;
        for (String base : basePaths(spec, swagger2)) {
            String remainder = strip(requestPath, base);
            if (remainder == null) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> it = paths.properties().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                int params = templateParams(entry.getKey());
                if (matches(entry.getKey(), remainder) && params < bestParams) {
                    matched = entry.getKey();
                    bestParams = params;
                }
            }
            if (matched != null) {
                break;
            }
        }
        if (matched == null) {
            return Located.fail("the specification has no path for " + requestPath);
        }

        String operation = "GET " + matched;
        JsonNode get = paths.path(matched).path("get");
        if (!get.isObject()) {
            return Located.fail("the specification defines no GET operation for " + matched);
        }
        String pointer = "#/paths/" + escape(matched) + "/get";

        JsonNode responses = get.path("responses");
        String code = null;
        for (String candidate : List.of("200", "201", "2XX", "2xx")) {
            if (responses.has(candidate)) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            Iterator<String> names = responses.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                if (n.length() == 3 && n.charAt(0) == '2') {
                    code = n;
                    break;
                }
            }
        }
        if (code == null) {
            return Located.fail("the specification defines no success (2xx) response for " + operation);
        }
        pointer += "/responses/" + code;
        JsonNode response = responses.get(code);
        String ref = response.path("$ref").asText(null);
        if (ref != null) {
            JsonNode target = resolve(spec, ref);
            if (target == null) {
                return Located.fail("the response of " + operation + " refers to " + ref + ", which is not in the specification");
            }
            pointer = ref;
            response = target;
        }

        JsonNode schema;
        if (swagger2) {
            schema = response.path("schema");
            pointer += "/schema";
        } else {
            String media = jsonMediaType(response.path("content"));
            if (media == null) {
                return Located.fail("the specification describes no JSON response for " + operation);
            }
            schema = response.path("content").path(media).path("schema");
            pointer += "/content/" + escape(media) + "/schema";
        }
        if (schema.isMissingNode() || schema.isNull()) {
            return Located.fail("the specification defines no response schema for " + operation);
        }
        return new Located(pointer, operation, null);
    }

    private static String jsonMediaType(JsonNode content) {
        if (!content.isObject()) {
            return null;
        }
        if (content.has("application/json")) {
            return "application/json";
        }
        Iterator<String> names = content.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n.contains("json")) {
                return n;
            }
        }
        return content.has("*/*") ? "*/*" : null;
    }

    /** The path parts of the specification's servers (OpenAPI 3) or its basePath (2.0); {@code [""]} if there are none. */
    private static List<String> basePaths(JsonNode spec, boolean swagger2) {
        List<String> bases = new ArrayList<>();
        if (swagger2) {
            bases.add(trimSlash(spec.path("basePath").asText("")));
        } else {
            for (JsonNode server : spec.path("servers")) {
                String url = server.path("url").asText("");
                int schemeEnd = url.indexOf("://");
                String path = schemeEnd >= 0 ? url.substring(Math.max(url.indexOf('/', schemeEnd + 3), schemeEnd + 3)) : url;
                if (path.startsWith("http")) {
                    path = "";
                }
                bases.add(trimSlash(path));
            }
        }
        if (bases.isEmpty()) {
            bases.add("");
        }
        if (!bases.contains("")) {
            bases.add("");
        }
        return bases;
    }

    private static String trimSlash(String path) {
        String p = path == null ? "" : path.trim();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String strip(String requestPath, String base) {
        if (base.isEmpty()) {
            return requestPath;
        }
        if (requestPath.equals(base)) {
            return "/";
        }
        return requestPath.startsWith(base + "/") ? requestPath.substring(base.length()) : null;
    }

    private static int templateParams(String template) {
        int n = 0;
        for (char c : template.toCharArray()) {
            if (c == '{') {
                n++;
            }
        }
        return n;
    }

    private static boolean matches(String template, String requestPath) {
        String a = trimSlash(template);
        String b = trimSlash(requestPath);
        if (a.equals(b)) {
            return true;
        }
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < a.length()) {
            int open = a.indexOf('{', i);
            if (open < 0) {
                re.append(Pattern.quote(a.substring(i)));
                break;
            }
            re.append(Pattern.quote(a.substring(i, open)));
            int close = a.indexOf('}', open);
            if (close < 0) {
                return false;
            }
            re.append("[^/]+");
            i = close + 1;
        }
        return Pattern.matches(re.toString(), b);
    }

    private static JsonNode resolve(JsonNode spec, String ref) {
        if (!ref.startsWith("#/")) {
            return null;
        }
        JsonNode node = spec.at(ref.substring(1));
        return node.isMissingNode() ? null : node;
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1")
                .replace("%", "%25").replace("{", "%7B").replace("}", "%7D").replace(" ", "%20");
    }

    /** The first {@code $ref} that does not point inside the document, or {@code null}. */
    private static String externalRef(JsonNode node) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
                return ref.asText();
            }
            for (JsonNode child : node) {
                String found = externalRef(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = externalRef(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
