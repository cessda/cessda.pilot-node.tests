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

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A node's services in the EOSC Beyond shape ({@code {total, from, to, results: [...]}}), whatever format they
 * were read from.
 *
 * @param root      the list itself
 * @param invalid   records of the source that failed validation and were left out
 * @param warnings  anything noteworthy about the read, for example a list that ended early
 * @param converted whether the records were translated from another format (false for a native Beyond list)
 */
public record CatalogueList(ObjectNode root, List<InvalidRecord> invalid, List<String> warnings, boolean converted) {

    public CatalogueList {
        invalid = List.copyOf(invalid);
        warnings = List.copyOf(warnings);
    }

    /** A record that was read but did not conform to the source's schema. */
    public record InvalidRecord(String id, List<String> messages) {
        public InvalidRecord {
            messages = List.copyOf(messages);
        }
    }
}
