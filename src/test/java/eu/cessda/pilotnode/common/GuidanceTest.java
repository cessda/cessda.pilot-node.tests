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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class GuidanceTest {

    @Test
    void errorsAndWarningsSayWhatToChangeInTheNodeEndpoint() {
        for (String code : new String[]{"endpoint-http-error", "api-spec-unreachable", "catalogue-unreachable",
                "node-endpoint-unavailable"}) {
            assertNotNull(Guidance.fix(code), code);
        }
        assertTrue(Guidance.fix("api-spec-not-machine-readable").contains("api_spec"));
    }

    @Test
    void notesThatNeedNoActionHaveNone() {
        assertNull(Guidance.fix("api-spec-openapi"));
        assertNull(Guidance.fix("payload-matches-api-spec"));
        assertNull(Guidance.fix(null));
    }

    @Test
    void aFindingCarriesItsFixInTheReport() {
        var json = new Finding(Finding.Severity.ERROR, "api-spec-unreachable", "api_spec could not be read", null, null,
                null, null, null).toJson(new ObjectMapper());
        assertEquals(Guidance.fix("api-spec-unreachable"), json.path("fix").asText());
        var note = new Finding(Finding.Severity.INFO, "api-spec-openapi", "ok", null, null, null, null, null)
                .toJson(new ObjectMapper());
        assertFalse(note.has("fix"));
    }
}
