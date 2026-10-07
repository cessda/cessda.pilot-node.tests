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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Chooses which of a node's catalogue endpoints to read, and reads it.
 *
 * <p>The order is: capability type first (by default {@code Service Catalogue}, then {@code Resource
 * Catalogue}), then within a type the format that needs least conversion (a native EOSC Beyond list before a
 * Lot-1 one), then endpoints the node declares {@code OPERATIONAL}, then the node's own order. An endpoint
 * counts only if an adapter recognises what it returns: a type name alone is never enough, because a
 * Resource Catalogue may hold research products and not services, and an unreadable feed (DCAT, say) is
 * skipped and not taken for an empty list.</p>
 */
public final class CatalogueSelector {

    private final List<String> capabilityOrder;
    private final List<CatalogueAdapter> adapters;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CatalogueSelector(List<String> capabilityOrder, List<CatalogueAdapter> adapters, HttpClient http,
                             ObjectMapper mapper) {
        this.capabilityOrder = capabilityOrder.stream().map(CatalogueSelector::normalise).toList();
        this.adapters = adapters.stream().sorted(Comparator.comparingInt(CatalogueAdapter::rank)).toList();
        this.http = http;
        this.mapper = mapper;
    }

    /** The adapters shipped with the dashboard: the native Beyond list and Lot-1 (v1.0.0 and v2.0.0). */
    public static CatalogueSelector standard(List<String> capabilityOrder, HttpClient http, ObjectMapper mapper) {
        return new CatalogueSelector(capabilityOrder,
                List.of(new BeyondAdapter(), new Lot1Adapter(new Lot1Validator())), http, mapper);
    }

    /** What was chosen: the endpoint, how it was recognised, and its services. */
    public record Selection(CatalogueCandidate candidate, String adapterId, String format, CatalogueList list) {}

    /** What happened to one candidate. */
    public record Attempt(CatalogueCandidate candidate, String outcome) {}

    /** The choice (absent if no endpoint could be read), and what was tried on the way. */
    public record Result(Optional<Selection> selection, List<Attempt> attempts) {}

    // ── candidates ────────────────────────────────────────────────────────

    /** The node's catalogue endpoints in the order they will be tried: by type, then OPERATIONAL, then document order. */
    public List<CatalogueCandidate> candidates(JsonNode endpointReport) {
        List<CatalogueCandidate> all = new ArrayList<>();
        int order = 0;
        for (JsonNode cap : endpointReport.path("capabilities")) {
            String endpoint = cap.path("endpoint").asText("").trim();
            if (!endpoint.isEmpty() && capabilityOrder.contains(normalise(cap.path("capability_type").asText("")))) {
                all.add(new CatalogueCandidate(cap.path("capability_type").asText(), text(cap, "protocol"), endpoint,
                        text(cap, "api_spec"), text(cap, "version"), text(cap, "declared_status"), order));
            }
            order++;
        }
        all.sort(Comparator
                .comparingInt((CatalogueCandidate c) -> capabilityOrder.indexOf(normalise(c.capabilityType())))
                .thenComparing(c -> c.operational() ? 0 : 1)
                .thenComparingInt(CatalogueCandidate::order));
        return all;
    }

    // ── selection ─────────────────────────────────────────────────────────

    public Result select(JsonNode endpointReport, CatalogueContext context) throws InterruptedException {
        List<Attempt> attempts = new ArrayList<>();
        Map<URI, JsonFetcher.Fetched> fetched = new HashMap<>();
        List<CatalogueCandidate> candidates = candidates(endpointReport);

        for (String type : capabilityOrder) {
            // Every candidate of this type is looked at; the best readable one wins.
            List<Recognised> recognised = new ArrayList<>();
            for (CatalogueCandidate candidate : candidates) {
                if (!normalise(candidate.capabilityType()).equals(type)) {
                    continue;
                }
                Optional<Recognised> found = recognise(candidate, fetched, attempts);
                found.ifPresent(recognised::add);
            }
            recognised.sort(Comparator
                    .comparingInt((Recognised r) -> r.adapter().rank())
                    .thenComparing(r -> r.candidate().operational() ? 0 : 1)
                    .thenComparingInt(r -> r.candidate().order()));

            for (Recognised r : recognised) {
                try {
                    CatalogueList list = r.adapter().read(context, r.probe(), r.format(), http, mapper);
                    attempts.add(new Attempt(r.candidate(), "used (" + r.format() + ")"));
                    return new Result(Optional.of(new Selection(r.candidate(), r.adapter().id(), r.format(), list)),
                            attempts);
                } catch (IOException e) {
                    attempts.add(new Attempt(r.candidate(), "recognised as " + r.format() + " but could not be read: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
                }
            }
        }
        return new Result(Optional.empty(), attempts);
    }

    private record Recognised(CatalogueCandidate candidate, CatalogueAdapter adapter, String format,
                              CatalogueAdapter.Probe probe) {}

    private Optional<Recognised> recognise(CatalogueCandidate candidate, Map<URI, JsonFetcher.Fetched> cache,
                                           List<Attempt> attempts) throws InterruptedException {
        URI endpoint;
        try {
            endpoint = URI.create(candidate.endpoint());
        } catch (IllegalArgumentException e) {
            attempts.add(new Attempt(candidate, "not a usable URL"));
            return Optional.empty();
        }

        List<String> notes = new ArrayList<>();
        for (CatalogueAdapter adapter : adapters) {
            for (URI url : adapter.probeUrls(endpoint)) {
                JsonFetcher.Fetched response = cache.get(url);
                if (response == null) {
                    response = JsonFetcher.fetch(http, mapper, url);
                    cache.put(url, response);
                }
                if (!response.ok()) {
                    notes.add(response.problem() + " at " + url);
                    continue;
                }
                Optional<String> format = adapter.detect(response.root());
                if (format.isPresent()) {
                    return Optional.of(new Recognised(candidate, adapter, format.get(),
                            new CatalogueAdapter.Probe(endpoint, url, response.root())));
                }
                notes.add(describeUnrecognised(response.root()) + " at " + url);
            }
        }
        attempts.add(new Attempt(candidate, "not a catalogue format that can be read ("
                + String.join("; ", notes.stream().distinct().toList()) + ")"));
        return Optional.empty();
    }

    private static String describeUnrecognised(JsonNode root) {
        if (root.has("@context") || root.toString().contains("\"dcat:")) {
            return "a DCAT feed";
        }
        if (root.path("results").isArray()) {
            return "a list in a format that is not supported";
        }
        return "JSON that is not a service list";
    }

    private static String normalise(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
