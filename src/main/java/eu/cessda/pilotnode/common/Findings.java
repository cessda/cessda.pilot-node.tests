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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** The findings of a strict check, and how they are written into its report. */
public final class Findings {

    private final EnumMap<Finding.Severity, ArrayList<Finding>> all = new EnumMap<>(Finding.Severity.class);

    public Findings add(Finding finding) {
        all.computeIfAbsent(finding.severity(), k -> new ArrayList<>()).add(finding);
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
        for (var finding : other.all.entrySet()) {
            all.merge(finding.getKey(), finding.getValue(), (a, b) -> {
                a.addAll(b);
                return a;
            });
        }
        return this;
    }

    public List<Finding> all() {
        var allList = new ArrayList<Finding>();
        for (var severity : all.values()) {
            allList.addAll(severity);
        }
        return allList;
    }

    private List<Finding> getBySeverity(Finding.Severity severity) {
        return Objects.requireNonNullElse(all.get(severity), Collections.emptyList());
    }

    public long count(Finding.Severity severity) {
        return getBySeverity(severity).size();
    }

    public boolean hasErrors() {
        return count(Finding.Severity.ERROR) > 0;
    }

    /** The first error, which is the one to name when a single reason is needed. */
    public Optional<Finding> firstError() {
        return getBySeverity(Finding.Severity.ERROR).stream().findFirst();
    }

    public ArrayNode toJson(ObjectMapper mapper) {
        ArrayNode array = mapper.createArrayNode();
        all().forEach(f -> array.add(mapper.valueToTree(f)));
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
