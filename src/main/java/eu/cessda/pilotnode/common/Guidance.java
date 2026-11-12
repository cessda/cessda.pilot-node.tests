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

import java.util.Map;

/**
 * What a node should change to put a finding right, in words a node operator can act on. The strict checks exist to
 * help nodes move from loose compliance to firm compliance, so each finding that a node can do something about says
 * what to change in the Node Endpoint (the JSON document the node publishes, with its {@code capabilities}, each with a
 * {@code capability_type}, {@code endpoint}, {@code version}, {@code api_spec}, {@code protocol} and {@code status}), or
 * in the service behind it. A finding that the node cannot put right says who can.
 *
 * <p>The text is looked up by the finding's {@code code} and written into the report as {@code fix}. Findings that are
 * only worth knowing (notes) have none, unless there is something to do.</p>
 */
public final class Guidance {

    private Guidance() {}

    private static final Map<String, String> FIX = Map.ofEntries(
            // The Node Endpoint document
            Map.entry("registry-node-endpoint-invalid", "The Node Registry has no usable address for your Node Endpoint. "
                    + "Ask the registry administrators to set your node's endpoint to the absolute https URL where your "
                    + "Node Endpoint document is published."),
            Map.entry("node-endpoint-unavailable", "Make your Node Endpoint document answer at the address held in the Node "
                    + "Registry, with HTTP 200 and a JSON document. Check the address, that the service is running, and "
                    + "that it can be reached from outside your network."),
            Map.entry("document-not-object", "Publish the Node Endpoint as a JSON object that has node_endpoint and a "
                    + "capabilities list, not as a list or a plain value."),
            Map.entry("node-endpoint-missing", "Add node_endpoint to your Node Endpoint: the absolute URL at which the "
                    + "document itself is published."),
            Map.entry("node-endpoint-invalid", "Set node_endpoint in your Node Endpoint to an absolute URL, for example "
                    + "https://node.example.org/api/endpoint."),
            Map.entry("capabilities-missing", "Add a capabilities list to your Node Endpoint: an array with one entry for "
                    + "each capability your node offers."),
            Map.entry("capabilities-empty", "List the capabilities your node offers in the capabilities array of your Node "
                    + "Endpoint. An empty list tells the federation that the node offers nothing."),
            Map.entry("capability-not-object", "Make each entry of capabilities a JSON object with capability_type, "
                    + "endpoint and the other fields of the model."),
            Map.entry("capability-type-missing", "Give each capability a capability_type, for example AAI, Resource "
                    + "Catalogue, Service Catalogue or Monitoring."),
            Map.entry("status-not-string", "Write status as text, using one of the values shown under Expected."),
            Map.entry("status-not-recommended", "Use one of the status values shown under Expected, unless yours is "
                    + "deliberately different. Other values are accepted, but tools cannot be sure what they mean."),
            Map.entry("endpoint-invalid", "Change the endpoint in your Node Endpoint to an absolute URL that starts with "
                    + "https://, for example https://host.example.org/path."),
            Map.entry("endpoint-not-http", "Publish the capability's endpoint with http or https, which is all that can be "
                    + "checked; https is preferred."),
            Map.entry("version-not-string", "Write version as text in your Node Endpoint, such as \"1.0.0\"."),
            Map.entry("version-empty", "Give the capability a version, such as \"1.0.0\", or leave the field out."),
            Map.entry("api-spec-invalid", "Set api_spec in your Node Endpoint to the absolute URL of the machine-readable "
                    + "specification, for example https://host.example.org/openapi.json, or remove it."),
            Map.entry("protocol-not-string", "Write protocol as text in your Node Endpoint, using one of the values shown "
                    + "under Expected."),
            Map.entry("protocol-not-recommended", "Use one of the protocol values shown under Expected, unless yours is "
                    + "deliberately different."),
            Map.entry("request-retried", "The first request failed and a retry succeeded. Look for intermittent failures "
                    + "(time-outs, a server that is overloaded or restarting): answers should be reliable at the first "
                    + "attempt."),
            Map.entry("endpoint-redirected", "Publish the address that answers, the one it redirects to, as the "
                    + "capability's endpoint in your Node Endpoint, so that clients are not sent elsewhere."),
            Map.entry("status-differs-from-probe", "Make the status in your Node Endpoint agree with the service. If the "
                    + "endpoint works, set status to an operational value; if it should not be used yet, keep the "
                    + "status and make sure the endpoint is not live."),
            Map.entry("api-spec-not-machine-readable", "Point api_spec at the specification document itself, for example "
                    + "https://host.example.org/openapi.json, not at a documentation page, so that answers can be "
                    + "checked against it."),
            Map.entry("api-spec-unreachable", "Make the api_spec URL answer with HTTP 200 and the specification, or "
                    + "correct the URL in your Node Endpoint."),
            Map.entry("api-spec-not-openapi", "Publish the api_spec as an OpenAPI (or Swagger) JSON document, so that "
                    + "answers can be checked against it."),
            Map.entry("version-differs-from-api-spec", "Check that the version in your Node Endpoint is the version you "
                    + "mean to advertise. If it should match the specification, correct whichever is wrong."),

            // Exchange Services
            Map.entry("catalogue-endpoint-missing", "Add an endpoint to this capability in your Node Endpoint: the address "
                    + "at which your catalogue returns its list of services."),
            Map.entry("catalogue-unreachable", "Set the capability's endpoint in your Node Endpoint to the exact address that "
                    + "returns your service list, with HTTP 200 and JSON. Strict mode does not try other addresses."),
            Map.entry("catalogue-format-unrecognised", "Make the endpoint return a service list in a format that can be "
                    + "read (an EOSC Beyond list, or EOSC-Lot-1 v1.0.0 or v2.0.0), and name the format in api_spec."),
            Map.entry("catalogue-read-failed", "The list was recognised but could not be read in full. Check that every page "
                    + "of the list can be fetched without an error."),
            Map.entry("no-readable-catalogue", "At least one of your catalogue capabilities needs an endpoint that returns "
                    + "a service list. The findings above say what happened at each endpoint that was tried."),
            Map.entry("api-spec-absent", "Add api_spec to the capability in your Node Endpoint, naming the specification "
                    + "(or the EOSC-Lot-1 schema) of the list, so that the format is confirmed rather than guessed."),
            Map.entry("format-differs-from-api-spec", "Make api_spec and the endpoint agree: either change what the endpoint "
                    + "returns, or change api_spec to name the format it does return."),
            Map.entry("api-spec-not-used", "To have answers checked, set api_spec in your Node Endpoint to an OpenAPI "
                    + "document or to the schema URL of an EOSC-Lot-1 release."),
            Map.entry("format-differs-from-version", "If version is meant to name the EOSC-Lot-1 release, make it match what "
                    + "the endpoint returns. If it is the version of your software, nothing needs to change."),
            Map.entry("record-invalid", "Correct the records named in your catalogue so that they conform to the format. "
                    + "Found says what is wrong with each."),
            Map.entry("list-incomplete", "The list says one thing and holds another. Check the total and the paging in the "
                    + "response of your catalogue so that every service can be read."),

            // Service Monitoring
            Map.entry("monitoring-endpoint-missing", "Add a Monitoring capability to your Node Endpoint, with the ARGO "
                    + "capability-metrics API as its endpoint: https://<host>/v1/public/nodes/<NODE>/capabilities/"
                    + "monitoring/metrics."),
            Map.entry("monitoring-endpoint-not-a-data-api", "Change the endpoint of your Monitoring capability to the ARGO "
                    + "capability-metrics API, whose address ends in /capabilities/monitoring/metrics, instead of the "
                    + "dashboard page."),
            Map.entry("endpoint-unreachable", "Make the endpoint reachable: check the address in your Node Endpoint, that "
                    + "the service is running and that it can be reached from outside your network."),
            Map.entry("endpoint-http-error", "Make the endpoint answer with HTTP 200. Check the address in your Node "
                    + "Endpoint and the service behind it."),
            Map.entry("monitoring-no-services", "ARGO answered but lists no services for your node. Check the node name in "
                    + "the endpoint, and ask the federation's monitoring team to add the node's endpoints."),
            Map.entry("monitoring-payload-unreadable", "Make the Monitoring endpoint return the ARGO metrics JSON, "
                    + "{\"data\": [...]}. Check that its address is the capability-metrics API."),

            // Core Service integrations
            Map.entry("argo-feed-unavailable", "Nothing to change in your Node Endpoint: the federation's ARGO status "
                    + "service could not be read. Try again later, or tell the federation operators."),
            Map.entry("node-not-monitored", "Ask the federation operators to add your node's Core Service endpoints to the "
                    + "ARGO tenant, in a group named exactly as your node is named."),
            Map.entry("core-endpoint-critical", "ARGO reports this endpoint as failing. Make it answer correctly; the ARGO "
                    + "status page, linked under More, shows which probes fail."),
            Map.entry("declared-unavailable-but-ok", "Change the status of this capability in your Node Endpoint to "
                    + "OPERATIONAL, as ARGO finds it working, or stop it answering if it is meant to be unavailable."),
            Map.entry("monitored-capability-not-declared", "Add this capability to your Node Endpoint, with the endpoint that "
                    + "ARGO monitors, or ask the federation to stop monitoring it."),
            Map.entry("monitored-url-differs", "Make the capability's endpoint in your Node Endpoint the same as the URL that "
                    + "ARGO monitors, or ask the federation to monitor the URL you have published.")
    );

    /**
     * What to change for a finding with this code, or {@code null} if there is nothing for the node to do. The Core
     * Service statuses other than CRITICAL (WARNING, UNKNOWN, MISSING) share one text.
     */
    public static String fix(String code) {
        if (code == null) {
            return null;
        }
        String text = FIX.get(code);
        if (text == null && code.startsWith("core-endpoint-") && !code.equals("core-endpoint-recovered")) {
            return "ARGO is not reporting this endpoint as healthy. Make it answer correctly; the ARGO status page, "
                    + "linked under More, shows which probes fail.";
        }
        return text;
    }
}
