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

package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Lenient mode is meant to be removable: deleting every {@code lenient} directory, and the lines that choose between
 * the modes, must leave the strict checks and what they share untouched. This fails if anything outside a
 * {@code lenient} directory uses a lenient class, other than the few classes that choose a mode.
 */
class LenientIsRemovableTest {

    private static final Path MAIN = Path.of("src/main/java/eu/cessda/pilotnode");

    /** The classes that choose between the modes, and so are edited when lenient mode is removed. */
    private static final Set<String> CHOOSE_A_MODE = Set.of(
            "CheckAll.java", "CheckAllScheduler.java", "CheckRunnerController.java", "CheckNodeCapabilities.java");

    @Test
    void nothingButTheModeChoosersUsesLenientCode() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/lenient/"))
                    .filter(f -> !CHOOSE_A_MODE.contains(f.getFileName().toString()))
                    .filter(f -> usesLenient(f))
                    .map(f -> MAIN.relativize(f).toString())
                    .sorted().toList();
        }
        assertEquals(List.of(), offenders, "these use lenient code, so lenient mode could not be removed");
    }

    @Test
    void lenientDirectoriesExistAndAreChecked() throws IOException {
        try (Stream<Path> dirs = Files.walk(MAIN)) {
            assertTrue(dirs.anyMatch(d -> d.getFileName().toString().equals("lenient") && Files.isDirectory(d)));
        }
    }

    private static boolean usesLenient(Path file) {
        try {
            return Files.readAllLines(file).stream().anyMatch(l -> l.contains(".lenient."));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
