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

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.CheckCatalogueServices;

/**
 * The EOSC Beyond Resource Catalogue's own list ({@code <base>/api/service/all}). It already is in the shape
 * the checks use, so nothing is converted. Lists from other catalogues that happen to have the same shape (a
 * flat list of services with a {@code name}) are read the same way.
 */
public final class BeyondAdapter implements CatalogueAdapter {

    static final String FORMAT = "EOSC Beyond service list";

    @Override
    public String id() {
        return "beyond";
    }

    @Override
    public int rank() {
        return 0;
    }

    @Override
    public List<URI> probeUrls(URI endpoint) {
        return List.of(CheckCatalogueServices.buildApiServiceUrl(endpoint));
    }

    @Override
    public Optional<String> detect(JsonNode sample) {
        if (!sample.isObject() || sample.has("@context") || !sample.path("results").isArray()) {
            return Optional.empty();
        }
        JsonNode results = sample.get("results");
        if (results.isEmpty()) {
            // An empty list could be any format; the Beyond catalogue's list carries its facets.
            return sample.has("facets") ? Optional.of(FORMAT) : Optional.empty();
        }
        int looked = 0;
        for (JsonNode item : results) {
            if (looked++ == 5) {
                break;
            }
            // Lot-1 wraps each service as {id, service: {...}}; a Beyond list has the service's fields directly.
            if (!item.isObject() || item.path("name").asText("").isBlank() || item.path("service").isObject()) {
                return Optional.empty();
            }
        }
        return Optional.of(FORMAT);
    }

    @Override
    public CatalogueList read(CatalogueContext context, Probe probe, String format, HttpClient http, ObjectMapper mapper) {
        return new CatalogueList((ObjectNode) probe.root(), List.of(), List.of(), false);
    }
}
