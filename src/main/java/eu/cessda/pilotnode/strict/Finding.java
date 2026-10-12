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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One thing a strict check found, stated so that it can be acted on: what was looked at, what was expected,
 * what was actually there, and (where there is one) what would help.
 *
 * @param code     short stable identifier, for example {@code endpoint-http-error}
 * @param subject  what the finding is about, for example a capability and its endpoint
 * @param url      the URL involved, exactly as requested
 * @param expected what the node's own declarations, or the model, call for
 * @param actual   what was found
 * @param hint     optional pointer to a remedy, for example what lenient mode would have done
 */
public record Finding(Severity severity, String code, String message, String subject, String url, String expected,
                      String actual, String hint) {

    /** ERROR: the node does not conform or something does not work. WARNING: accepted but not as recommended. INFO: worth knowing. */
    public enum Severity {
        ERROR, WARNING, INFO
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("severity", severity.name());
        n.put("code", code);
        n.put("message", message);
        putIfPresent(n, "subject", subject);
        putIfPresent(n, "url", url);
        putIfPresent(n, "expected", expected);
        putIfPresent(n, "actual", actual);
        putIfPresent(n, "hint", hint);
        return n;
    }

    private static void putIfPresent(ObjectNode n, String field, String value) {
        if (value != null && !value.isBlank()) {
            n.put(field, value);
        }
    }
}
