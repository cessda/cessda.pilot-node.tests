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

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The findings of a strict check, and how they are written into its report. */
public final class Findings {

    private final List<Finding> all = new ArrayList<>();

    public Findings add(Finding finding) {
        all.add(finding);
        return this;
    }

    public Findings error(String code, String message, String subject, String url, String expected, String actual,
                          String hint) {
        return add(new Finding(Finding.Severity.ERROR, code, message, subject, url, expected, actual, hint));
    }

    public Findings warning(String code, String message, String subject, String url, String expected, String actual,
                            String hint) {
        return add(new Finding(Finding.Severity.WARNING, code, message, subject, url, expected, actual, hint));
    }

    public Findings info(String code, String message, String subject, String url, String expected, String actual,
                         String hint) {
        return add(new Finding(Finding.Severity.INFO, code, message, subject, url, expected, actual, hint));
    }

    public Findings addAll(Findings other) {
        all.addAll(other.all);
        return this;
    }

    public List<Finding> all() {
        return List.copyOf(all);
    }

    public long count(Finding.Severity severity) {
        return all.stream().filter(f -> f.severity() == severity).count();
    }

    public boolean hasErrors() {
        return count(Finding.Severity.ERROR) > 0;
    }

    /** The first error, which is the one to name when a single reason is needed. */
    public Finding firstError() {
        return all.stream().filter(f -> f.severity() == Finding.Severity.ERROR).findFirst().orElse(null);
    }

    public ArrayNode toJson(ObjectMapper mapper) {
        ArrayNode array = mapper.createArrayNode();
        all.forEach(f -> array.add(f.toJson(mapper)));
        return array;
    }

    /**
     * Writes {@code mode}, {@code findings}, the counts per severity and {@code strict_result} (PASS if there is no
     * error, otherwise FAIL) into a report.
     */
    public void writeTo(ObjectNode report, ObjectMapper mapper) {
        report.put("mode", CheckMode.STRICT.label());
        report.put("strict_result", hasErrors() ? "FAIL" : "PASS");
        report.put("findings_error", count(Finding.Severity.ERROR));
        report.put("findings_warning", count(Finding.Severity.WARNING));
        report.put("findings_info", count(Finding.Severity.INFO));
        report.set("findings", toJson(mapper));
    }
}
