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

import java.nio.file.Path;
import java.util.Locale;

/**
 * How strictly a check treats what a node publishes.
 *
 * <ul>
 *   <li>{@link #LENIENT}: the dashboard helps. It completes URLs, tries alternatives, falls back to other
 *       sources and converts formats, so as to get an answer wherever it can.</li>
 *   <li>{@link #STRICT}: URLs are used exactly as the node published them, nothing is guessed, and every
 *       deviation from what the node's own declarations say is reported as a finding.</li>
 * </ul>
 *
 * <p>A node's results are kept together, in the data directory's folder for the node, and the two modes keep them
 * apart: strict results are in {@code <data>/<NODE>/}, lenient ones in {@code <data>/<NODE>/lenient/}. Strict is
 * the node's own folder because it is meant to become the default, and the lenient folder can then be dropped. The
 * list of nodes, {@code <data>/node_registry_summary.json}, is the same for both and exists once.</p>
 */
public enum CheckMode {
    LENIENT("lenient"),
    STRICT("strict");

    /** The folder, inside a node's folder, that holds its lenient results. */
    public static final String LENIENT_FOLDER = "lenient";

    private final String label;

    CheckMode(String label) {
        this.label = label;
    }

    /** The name used in reports and requests: {@code lenient} or {@code strict}. */
    public String label() {
        return label;
    }

    /**
     * Where this mode keeps a node's reports, given the dashboard data directory: {@code <data>/<NODE>} for strict,
     * {@code <data>/<NODE>/lenient} for lenient.
     */
    public Path nodeDir(Path dataDir, String nodeName) {
        Path node = dataDir.resolve(nodeName);
        return this == LENIENT ? node.resolve(LENIENT_FOLDER) : node;
    }

    /** Reads a mode from a request or setting; blank means lenient. */
    public static CheckMode parse(String text) {
        if (text == null || text.isBlank()) {
            return LENIENT;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (CheckMode mode : values()) {
            if (mode.label.equals(t)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown mode '" + text + "'. Supported: lenient, strict");
    }
}
