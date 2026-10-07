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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads a catalogue that follows the EOSC-Lot-1 service catalogue model (releases v1.0.0 and v2.0.0): a
 * {@code {total, from, to, results: [{id, service: {...}}]}} list, served in pages. Each service is validated
 * against the model and converted to the EOSC Beyond shape.
 *
 * <p>Only what the checks and the Node page use is converted: identity, name, webpage, description and the few
 * fields that map directly. The category vocabulary is not translated back.</p>
 */
public final class Lot1Adapter implements CatalogueAdapter {

    static final String FORMAT_PREFIX = "Lot-1 ";
    /** The model's default page is 10; a larger page keeps the number of requests down. */
    static final int PAGE_SIZE = 50;
    /** Stops a catalogue that never reaches its own total from being read for ever. */
    static final int MAX_PAGES = 200;

    private final Lot1Validator validator;

    public Lot1Adapter(Lot1Validator validator) {
        this.validator = validator;
    }

    @Override
    public String id() {
        return "lot1";
    }

    @Override
    public int rank() {
        return 1;
    }

    @Override
    public List<URI> probeUrls(URI endpoint) {
        return List.of(page(endpoint, 0));
    }

    static URI page(URI endpoint, int from) {
        String url = endpoint.toString();
        return URI.create(url + (url.contains("?") ? "&" : "?") + "from=" + from + "&quantity=" + PAGE_SIZE);
    }

    @Override
    public Optional<String> detect(JsonNode sample) {
        if (!sample.isObject() || sample.has("@context") || !sample.path("results").isArray()) {
            return Optional.empty();
        }
        JsonNode results = sample.get("results");
        if (results.isEmpty()) {
            boolean paged = sample.path("total").isNumber() && sample.has("from") && sample.has("to");
            return paged && !sample.has("facets") ? Optional.of(label(Lot1Validator.Version.V2_0_0)) : Optional.empty();
        }
        int looked = 0;
        for (JsonNode item : results) {
            if (looked++ == 5) {
                break;
            }
            if (!item.path("service").isObject() || item.path("service").path("name").asText("").isBlank()) {
                return Optional.empty();
            }
        }
        return Optional.of(label(versionOf(results)));
    }

    /** v2.0.0 replaced the category vocabulary, which tells the two apart; with no categories assume the latest. */
    static Lot1Validator.Version versionOf(JsonNode bundles) {
        boolean sawV1 = false;
        for (JsonNode bundle : bundles) {
            for (JsonNode category : bundle.path("service").path("categories")) {
                String value = category.path("category").asText("");
                if (value.startsWith("service_classification-")) {
                    return Lot1Validator.Version.V2_0_0;
                }
                sawV1 |= value.startsWith("category-");
            }
        }
        return sawV1 ? Lot1Validator.Version.V1_0_0 : Lot1Validator.Version.V2_0_0;
    }

    private static String label(Lot1Validator.Version version) {
        return FORMAT_PREFIX + version.label();
    }

    @Override
    public CatalogueList read(CatalogueContext context, Probe probe, String format, HttpClient http, ObjectMapper mapper)
            throws IOException, InterruptedException {
        Lot1Validator.Version version = format.endsWith(Lot1Validator.Version.V1_0_0.label())
                ? Lot1Validator.Version.V1_0_0 : Lot1Validator.Version.V2_0_0;
        List<String> warnings = new ArrayList<>();

        // ── all pages ───────────────────────────────────────────────────
        List<JsonNode> bundles = new ArrayList<>();
        probe.root().path("results").forEach(bundles::add);
        long total = probe.root().path("total").asLong(bundles.size());
        int pages = 1;
        while (bundles.size() < total && pages < MAX_PAGES) {
            JsonFetcher.Fetched fetched = JsonFetcher.fetch(http, mapper, page(probe.endpoint(), bundles.size()));
            if (!fetched.ok()) {
                warnings.add("Read " + bundles.size() + " of " + total + " services; the next page failed ("
                        + fetched.problem() + ")");
                break;
            }
            JsonNode results = fetched.root().path("results");
            if (!results.isArray() || results.isEmpty()) {
                break;
            }
            results.forEach(bundles::add);
            pages++;
        }
        if (bundles.size() < total && warnings.isEmpty()) {
            warnings.add("The catalogue reports " + total + " services but " + bundles.size() + " could be read");
        }

        // ── validate and convert ───────────────────────────────────────
        ArrayNode converted = mapper.createArrayNode();
        List<CatalogueList.InvalidRecord> invalid = new ArrayList<>();
        for (JsonNode bundle : bundles) {
            List<String> problems = validator.validate(version, bundle);
            if (problems.isEmpty()) {
                converted.add(toBeyond(mapper, bundle, context));
            } else {
                invalid.add(new CatalogueList.InvalidRecord(bundle.path("id").asText("(no id)"), problems));
            }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("total", converted.size());
        root.put("from", 0);
        root.put("to", converted.size());
        root.set("results", converted);
        return new CatalogueList(root, invalid, warnings, true);
    }

    private static ObjectNode toBeyond(ObjectMapper mapper, JsonNode bundle, CatalogueContext context) {
        JsonNode service = bundle.path("service");
        ObjectNode out = mapper.createObjectNode();
        out.put("id", service.path("id").asText(bundle.path("id").asText()));
        out.put("name", service.path("name").asText());
        copyText(service, out, "abbreviation");
        copyText(service, out, "description");
        copyText(service, out, "webpage");
        String webpage = service.path("webpage").asText("");
        if (!webpage.isBlank()) {
            out.putArray("urls").add(webpage);
        }
        copyText(service, out, "logo");
        out.put("type", "Service");
        if (context.nodePid() != null && !context.nodePid().isBlank()) {
            out.put("nodePID", context.nodePid());
        }
        if (service.path("tags").isArray()) {
            out.set("tags", service.get("tags"));
        }
        for (String field : new String[]{"trl", "orderType", "termsOfUse", "privacyPolicy", "accessPolicy"}) {
            copyText(service, out, field);
        }
        String helpdesk = service.path("helpdeskEmail").asText("");
        if (!helpdesk.isBlank()) {
            out.putArray("publicContacts").add(helpdesk);
        }
        return out;
    }

    private static void copyText(JsonNode from, ObjectNode to, String field) {
        String value = from.path(field).asText("");
        if (!value.isBlank()) {
            to.put(field, value);
        }
    }
}
