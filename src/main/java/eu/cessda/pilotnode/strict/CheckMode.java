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

package eu.cessda.pilotnode.strict;

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
 * <p>The two modes keep their results apart: strict results live in a {@code strict} directory that mirrors the
 * data directory, so that a lenient run never overwrites a strict one.</p>
 */
public enum CheckMode {
    LENIENT("lenient", null),
    STRICT("strict", "strict");

    private final String label;
    private final String subdirectory;

    CheckMode(String label, String subdirectory) {
        this.label = label;
        this.subdirectory = subdirectory;
    }

    /** The name used in reports and requests: {@code lenient} or {@code strict}. */
    public String label() {
        return label;
    }

    /** Where this mode keeps its results, given the dashboard data directory. */
    public Path resolve(Path dataDir) {
        return subdirectory == null ? dataDir : dataDir.resolve(subdirectory);
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
