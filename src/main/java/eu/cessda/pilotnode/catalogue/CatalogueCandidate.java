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

/**
 * One endpoint that a node offers as a catalogue, as recorded in its {@code endpoint_report.json}.
 *
 * @param order position among the node's capabilities, to keep the node's own ordering as a tie-break
 */
public record CatalogueCandidate(String capabilityType, String protocol, String endpoint, String apiSpec,
                                 String version, String declaredStatus, int order) {

    /** Whether the node itself says the capability is operational (as opposed to planned, for example). */
    public boolean operational() {
        return "OPERATIONAL".equalsIgnoreCase(declaredStatus);
    }

    @Override
    public String toString() {
        return capabilityType + " " + endpoint;
    }
}
