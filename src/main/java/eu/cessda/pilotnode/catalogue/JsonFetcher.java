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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Fetches a JSON document without letting a bad endpoint raise anything but a described failure. */
final class JsonFetcher {

    /** A catalogue page is far smaller than this; the limit stops a misbehaving endpoint from filling memory. */
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private JsonFetcher() {}

    /** The parsed document, or why there is none. */
    record Fetched(Integer status, JsonNode root, String problem) {
        boolean ok() {
            return root != null;
        }
    }

    static Fetched fetch(HttpClient http, ObjectMapper mapper, URI url) throws InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    return new Fetched(response.statusCode(), null, "HTTP " + response.statusCode());
                }
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    return new Fetched(200, null, "response larger than " + (MAX_BYTES / (1024 * 1024)) + " MB");
                }
                try {
                    return new Fetched(200, mapper.readTree(bytes), null);
                } catch (IOException e) {
                    return new Fetched(200, null, "not JSON");
                }
            }
        } catch (IOException e) {
            return new Fetched(null, null, "unreachable: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (IllegalArgumentException e) {
            return new Fetched(null, null, "unusable URL: " + e.getMessage());
        }
    }
}
