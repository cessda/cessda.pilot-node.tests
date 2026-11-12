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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.cessda.pilotnode.common.CheckMode;

/**
 * Finds a node's report of a given mode, allowing for the places earlier versions of the dashboard wrote them.
 *
 * <p>Earlier versions wrote lenient reports to {@code <data>/<NODE>/}, which is now where strict reports go, so a
 * report found there may be either. A strict report says so ({@code "mode": "strict"}); one that does not is a lenient
 * report written by an earlier version. A lenient run writes to {@code <data>/<NODE>/lenient/}, and until it has,
 * the earlier version's file is used, so that the dashboard keeps working until the first lenient run, and a strict
 * run (which overwrites those files) never mistakes one for its own.</p>
 */
public final class ReportFiles {

    private ReportFiles() {}

    /** Whether a report was written by a strict run. */
    public static boolean isStrict(JsonNode report) {
        return report != null && CheckMode.STRICT.label().equals(report.path("mode").asText(null));
    }

    /**
     * The file holding {@code mode}'s {@code file} report for {@code nodeName}, if there is one.
     */
    public static Optional<Path> find(Path dataDir, CheckMode mode, String nodeName, String file, ObjectMapper mapper) {
        Path own = mode.nodeDir(dataDir, nodeName).resolve(file);
        if (mode == CheckMode.STRICT) {
            return Files.isRegularFile(own) && isStrictFile(own, mapper) ? Optional.of(own) : Optional.empty();
        }
        if (Files.isRegularFile(own)) {
            return Optional.of(own);
        }
        Path legacy = CheckMode.STRICT.nodeDir(dataDir, nodeName).resolve(file);
        return Files.isRegularFile(legacy) && !isStrictFile(legacy, mapper) ? Optional.of(legacy) : Optional.empty();
    }

    /** The parsed report, or empty if there is none or it cannot be read. */
    public static Optional<JsonNode> read(Path dataDir, CheckMode mode, String nodeName, String file, ObjectMapper mapper) {
        return find(dataDir, mode, nodeName, file, mapper).flatMap(path -> {
            try {
                return Optional.of(mapper.readTree(path.toFile()));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    private static boolean isStrictFile(Path file, ObjectMapper mapper) {
        try {
            return isStrict(mapper.readTree(file.toFile()));
        } catch (IOException e) {
            return false;
        }
    }
}
