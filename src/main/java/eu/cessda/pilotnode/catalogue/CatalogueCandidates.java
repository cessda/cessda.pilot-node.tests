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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The catalogue endpoints a node declares, in the order they are to be tried, and what became of them. This is the
 * same in both modes: the capability types that count and their order ({@code check.catalogue.capability-order}).
 */
public final class CatalogueCandidates {

    private final List<String> capabilityOrder;

    public CatalogueCandidates(List<String> capabilityOrder) {
        this.capabilityOrder = capabilityOrder.stream().map(CatalogueCandidates::normalise).toList();
    }

    /** The capability types that count, in order, normalised. */
    public List<String> capabilityOrder() {
        return capabilityOrder;
    }

    /** What was chosen: the endpoint, how it was recognised, and its services. */
    public record Selection(CatalogueCandidate candidate, String adapterId, String format, CatalogueList list) {}

    /** What happened to one candidate. */
    public record Attempt(CatalogueCandidate candidate, String outcome) {}

    /** The node's catalogue endpoints in the order they will be tried: by type, then OPERATIONAL, then document order. */
    public List<CatalogueCandidate> candidates(JsonNode endpointReport) {
        return collect(endpointReport, true);
    }

    /** The node's catalogue capabilities including any that give no endpoint, to tell "none declared" from "declared without one". */
    public List<CatalogueCandidate> declaredCandidates(JsonNode endpointReport) {
        return collect(endpointReport, false);
    }

    private List<CatalogueCandidate> collect(JsonNode endpointReport, boolean requireEndpoint) {
        List<CatalogueCandidate> all = new ArrayList<>();
        int order = 0;
        for (JsonNode cap : endpointReport.path("capabilities")) {
            String endpoint = cap.path("endpoint").asText("").trim();
            if ((!requireEndpoint || !endpoint.isEmpty()) && capabilityOrder.contains(normalise(cap.path("capability_type").asText("")))) {
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

    public static String normalise(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
