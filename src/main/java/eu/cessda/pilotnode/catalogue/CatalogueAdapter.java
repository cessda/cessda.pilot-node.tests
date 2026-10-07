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
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads one catalogue format and presents it in the EOSC Beyond shape that the checks understand.
 *
 * <p>Nodes do not reliably say which format an endpoint serves ({@code api_spec} is often null or points at
 * the node's own documentation), so an adapter recognises its format by looking at a sample response.</p>
 */
public interface CatalogueAdapter {

    /** Short stable name, used in reports. */
    String id();

    /** Lower is preferred: a native format, which needs no conversion, comes before a converted one. */
    int rank();

    /** URLs to request, in order, to see whether the endpoint serves this adapter's format. */
    List<URI> probeUrls(URI endpoint);

    /**
     * Recognises the format from a parsed response.
     *
     * @return a label for the recognised format (for example {@code Lot-1 v2.0.0}), or empty if this adapter
     *         cannot read the response
     */
    Optional<String> detect(JsonNode sample);

    /**
     * Reads the whole catalogue.
     *
     * @param probe the response that {@link #detect} accepted, so that it need not be requested again
     */
    CatalogueList read(CatalogueContext context, Probe probe, String format, HttpClient http, ObjectMapper mapper)
            throws IOException, InterruptedException;

    /** A response to one of the adapter's probe URLs. */
    record Probe(URI endpoint, URI url, JsonNode root) {}
}
