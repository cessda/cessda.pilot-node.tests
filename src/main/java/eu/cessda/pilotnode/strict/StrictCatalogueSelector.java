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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.cessda.pilotnode.catalogue.BeyondAdapter;
import eu.cessda.pilotnode.catalogue.CatalogueAdapter;
import eu.cessda.pilotnode.catalogue.CatalogueCandidate;
import eu.cessda.pilotnode.catalogue.CatalogueContext;
import eu.cessda.pilotnode.catalogue.CatalogueList;
import eu.cessda.pilotnode.catalogue.CatalogueSelector;
import eu.cessda.pilotnode.catalogue.JsonFetcher;
import eu.cessda.pilotnode.catalogue.Lot1Adapter;
import eu.cessda.pilotnode.catalogue.Lot1Validator;

/**
 * The strict version of choosing and reading a node's catalogue.
 *
 * <p>Each of the node's catalogue endpoints (the capability types and their order are those of the lenient
 * {@link CatalogueSelector}) is requested <em>exactly as published</em>, once (a failed request is repeated, and the
 * repeat is reported). Nothing is appended, no alternative URL is tried, there is no fallback to the Sandbox, and
 * every endpoint is accounted for: the dashboard says what it asked, what came back, and what would have been
 * different in lenient mode. The first endpoint that returns a readable service list is the one used.</p>
 *
 * <p>What the node publishes about the endpoint is used as a check on what came back. An {@code api_spec} that
 * names a release of the EOSC-Lot-1 model means the payload must be that release; an {@code api_spec} that is
 * absent or that the dashboard cannot check against is noted, and the format is then taken from the content.</p>
 */
public final class StrictCatalogueSelector {

    private static final int MAX_ATTEMPTS = 3;
    /** Individual invalid records are listed up to this many, then summarised. */
    private static final int MAX_RECORD_FINDINGS = 20;
    private static final Pattern LOT1_SPEC =
            Pattern.compile("eosc-service-catalogues-specs/(?:blob/|raw/)?(v\\d+\\.\\d+\\.\\d+)/");
    private static final Pattern RELEASE_NUMBER = Pattern.compile("v?(\\d+\\.\\d+\\.\\d+)");

    /** What happened to the strict selection. */
    public record Outcome(Optional<CatalogueSelector.Selection> selection, Findings findings,
                          List<CatalogueSelector.Attempt> attempts) {}

    private final CatalogueSelector candidateSource;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration retryDelay;
    private final BeyondAdapter beyond = new BeyondAdapter();
    private final Lot1Adapter lot1 = new Lot1Adapter(new Lot1Validator());

    public StrictCatalogueSelector(CatalogueSelector candidateSource, HttpClient http, ObjectMapper mapper,
                                   Duration retryDelay) {
        this.candidateSource = candidateSource;
        this.http = http;
        this.mapper = mapper;
        this.retryDelay = retryDelay;
    }

    private record Recognised(CatalogueCandidate candidate, CatalogueAdapter adapter, String format,
                              CatalogueAdapter.Probe probe, String subject) {}

    public Outcome select(JsonNode endpointReport, CatalogueContext context) throws InterruptedException {
        Findings findings = new Findings();
        List<CatalogueSelector.Attempt> attempts = new ArrayList<>();
        Optional<CatalogueSelector.Selection> selection = Optional.empty();

        for (CatalogueCandidate candidate : candidateSource.candidates(endpointReport)) {
            if (selection.isPresent()) {
                // A higher-ranked endpoint was readable. The rest are not requested: a Resource Catalogue of
                // research products, for example, is not expected to list services.
                attempts.add(new CatalogueSelector.Attempt(candidate, "not requested: an earlier endpoint was readable"));
                continue;
            }
            String subject = candidate.capabilityType() + " " + candidate.endpoint();
            if (!CapabilitiesModel.isHttp(candidate.endpoint())) {
                findings.error("endpoint-invalid", "The endpoint cannot be requested", subject, candidate.endpoint(),
                        "an absolute http(s) URL", candidate.endpoint(), null);
                attempts.add(new CatalogueSelector.Attempt(candidate, "not an absolute http(s) URL"));
                continue;
            }
            URI url = URI.create(candidate.endpoint().trim());

            JsonFetcher.Fetched fetched = JsonFetcher.fetchWithRetries(http, mapper, url, MAX_ATTEMPTS, retryDelay);
            String expected = "a service list in the EOSC Beyond or EOSC-Lot-1 format";
            if (!fetched.ok()) {
                String actual = fetched.problem() + (fetched.attempts() > 1 ? " (after " + fetched.attempts() + " attempts)" : "");
                findings.error("catalogue-unreachable", "GET " + url + " did not return a service list: " + actual, subject,
                        url.toString(), expected, actual, lenientHint(url));
                attempts.add(new CatalogueSelector.Attempt(candidate, actual));
                continue;
            }
            if (fetched.attempts() > 1) {
                findings.warning("request-retried", "The catalogue was only read after " + fetched.attempts() + " attempts",
                        subject, url.toString(), "an answer at the first attempt", fetched.attempts() + " attempts", null);
            }

            CatalogueAdapter adapter = null;
            String format = null;
            for (CatalogueAdapter option : List.of(beyond, lot1)) {
                Optional<String> detected = option.detect(fetched.root());
                if (detected.isPresent()) {
                    adapter = option;
                    format = detected.get();
                    break;
                }
            }
            if (adapter == null) {
                // The dashboard cannot read this format. That is its limit, not necessarily the node's fault, so it is a
                // warning; it becomes an error only if nothing at all could be read (below).
                String actual = describe(fetched.root());
                findings.warning("catalogue-format-unrecognised", "The response from " + url + " is not a service list that this check can read",
                        subject, url.toString(), expected, actual, lenientHint(url));
                attempts.add(new CatalogueSelector.Attempt(candidate, "not a catalogue format that can be read (" + actual + ")"));
                continue;
            }

            checkAgainstDeclarations(findings, candidate, subject, adapter, format);
            Recognised recognised = new Recognised(candidate, adapter, format,
                    new CatalogueAdapter.Probe(url, url, fetched.root()), subject);
            try {
                CatalogueList list = adapter.read(context, recognised.probe(), format, http, mapper);
                reportList(findings, recognised, list);
                attempts.add(new CatalogueSelector.Attempt(candidate, "used (" + format + ")"));
                selection = Optional.of(new CatalogueSelector.Selection(candidate, adapter.id(), format, list));
            } catch (IOException e) {
                findings.error("catalogue-read-failed", "The catalogue was recognised but could not be read in full", subject,
                        candidate.endpoint(), "the whole list", e.getMessage(), null);
                attempts.add(new CatalogueSelector.Attempt(candidate, "recognised as " + format + " but could not be read: " + e.getMessage()));
            }
        }
        if (selection.isEmpty()) {
            findings.error("no-readable-catalogue", "None of the node's catalogue endpoints returned a service list", null, null,
                    "at least one readable service list", attempts.size() + " endpoint(s) tried", null);
        }
        return new Outcome(selection, findings, attempts);
    }

    // ── what the node says about the endpoint ───────────────────────────────

    private void checkAgainstDeclarations(Findings f, CatalogueCandidate candidate, String subject, CatalogueAdapter adapter,
                                          String format) {
        String spec = candidate.apiSpec();
        String url = candidate.endpoint();
        if (spec == null || spec.isBlank()) {
            f.info("api-spec-absent", "No api_spec is published, so the response was checked only against the format recognised from it",
                    subject, url, "an api_spec naming the format", "absent (recognised: " + format + ")", null);
        } else {
            Matcher m = LOT1_SPEC.matcher(spec);
            if (m.find()) {
                String declared = "Lot-1 " + m.group(1);
                if (declared.equals(format)) {
                    f.info("payload-matches-api-spec", "The response is " + format + ", as the api_spec says", subject, url, declared, format, null);
                } else {
                    f.error("format-differs-from-api-spec", "The response is not the format that api_spec names", subject, url,
                            declared + " (from api_spec " + spec + ")", format, null);
                }
            } else {
                f.info("api-spec-not-used", "api_spec is not a specification this check can compare the response with", subject, spec,
                        null, "response recognised as " + format, "Lot-1 releases are recognised by their schema URL");
            }
        }

        // A version that looks like a Lot-1 release number is compared when api_spec did not settle the question.
        String version = candidate.version();
        if (version != null && adapter instanceof Lot1Adapter && !LOT1_SPEC.matcher(spec == null ? "" : spec).find()) {
            Matcher v = RELEASE_NUMBER.matcher(version.trim());
            if (v.matches() && (v.group(1).equals("1.0.0") || v.group(1).equals("2.0.0"))) {
                String declared = "Lot-1 v" + v.group(1);
                if (!declared.equals(format)) {
                    f.info("format-differs-from-version", "The capability's version looks like a Lot-1 release, but the response is another",
                            subject, url, declared, format, "The version may simply be the software's version");
                }
            }
        }
    }

    private void reportList(Findings f, Recognised r, CatalogueList list) {
        int shown = 0;
        for (CatalogueList.InvalidRecord invalid : list.invalid()) {
            if (shown++ == MAX_RECORD_FINDINGS) {
                f.error("record-invalid", (list.invalid().size() - MAX_RECORD_FINDINGS) + " more record(s) do not conform to " + r.format(),
                        r.subject(), r.candidate().endpoint(), "records that conform to " + r.format(), "see invalid_details", null);
                break;
            }
            f.error("record-invalid", "Record " + invalid.id() + " does not conform to " + r.format(), r.subject(),
                    r.candidate().endpoint(), "a record that conforms to " + r.format(), String.join("; ", invalid.messages()), null);
        }
        list.warnings().forEach(w -> f.warning("list-incomplete", w, r.subject(), r.candidate().endpoint(),
                "every service in the list", w, null));
        long reported = r.probe().root().path("total").asLong(0);
        int returned = r.probe().root().path("results").size();
        if (!(r.adapter() instanceof Lot1Adapter) && reported > returned) {
            // A list in this format is read as returned; how it is paged is not known.
            f.warning("list-incomplete", "The list reports " + reported + " services but the response holds " + returned,
                    r.subject(), r.candidate().endpoint(), "every service in the response", returned + " of " + reported,
                    "Paging is only followed for the EOSC-Lot-1 format");
        }
        if (r.adapter() instanceof Lot1Adapter && r.probe().root().path("total").asLong(0) > r.probe().root().path("results").size()) {
            f.info("paged-with-from-quantity", "The first response held part of the list; the rest was read with the from and quantity "
                    + "parameters that the Lot-1 model defines", r.subject(), r.candidate().endpoint(), null,
                    r.probe().root().path("results").size() + " of " + r.probe().root().path("total").asLong() + " in the first response", null);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** What lenient mode would have asked for instead, if that differs from the published URL. */
    private String lenientHint(URI published) {
        URI lenient = beyond.probeUrls(published).get(0);
        return lenient.equals(published) ? null
                : "Lenient mode would request " + lenient + " instead of the URL as published";
    }

    private static String describe(JsonNode root) {
        if (root.has("@context") || root.toString().contains("\"dcat:")) {
            return "a DCAT feed";
        }
        if (root.path("results").isArray()) {
            return "a list in a format that is not supported";
        }
        return "JSON that is not a service list";
    }
}
