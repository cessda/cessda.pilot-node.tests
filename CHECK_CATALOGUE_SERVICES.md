# Check Catalogue Services

`eu.cessda.pilotnode.CheckCatalogueServices` (a plain Java class, not a
shell script) checks the availability of the Exchange services listed in a
Node's Resource Catalogue and generates a JSON report. On the dashboard
this check is labelled **Exchange Services**.

## Features

- Fetches service data from the node's Resource Catalogue API
- Checks HTTP availability, response time, and content validity of each
  service's webpage (Metric 13 — see below)
- Writes `catalogue_services_report.json` directly to the dashboard data
  directory
- If the node's own API cannot be read, falls back to the Sandbox Resource
  Catalogue filtered to the node (and never lists another node's services)

## Running via the Dashboard

The normal way to run this check is from the dashboard: the **Exchange
Services** entry in a node's own Run checks menu on `node.html`, or as part
of **Check All** (see [Dashboard](DASHBOARD_README.md)).

Triggered via: `POST /api/run/catalogue-services` with JSON body
`{ "node": "...", "catalogueUrl": "...", "nodePid": "..." }`. `catalogueUrl`
is the node's Resource Catalogue endpoint, read from that node's
`endpoint_report.json` (capability_type `Resource Catalogue`) — the
dashboard's JavaScript does this lookup automatically before calling the
endpoint. `nodePid` is optional and is needed for the Sandbox fallback
described below. No credentials are required.

## Running Directly

There's no packaged CLI jar for the individual checks — they're plain
classes inside the Spring Boot application — so running one directly means
putting the compiled classes and the project's runtime dependencies
(currently just Jackson) on the classpath yourself:

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" \
  eu.cessda.pilotnode.CheckCatalogueServices NODE_NAME [api_base_url] [quantity] [dashboard_dir] [node_pid]
```

### Arguments

| Argument        | Required | Default                                        | Description |
| --------------- | -------- | ----------------------------------------------- | ----------- |
| `NODE_NAME`     | Yes      | —                                               | Node name; used as the output directory |
| `api_base_url`  | No       | `https://providers.sandbox.eosc-beyond.eu`      | The node's own Resource Catalogue base URL — the `Resource Catalogue` capability's `endpoint` value from `endpoint_report.json`, passed as-is. Any trailing `/api` or `/api/` is stripped and `/api/service/all` appended |
| `quantity`      | No       | `10`                                            | Maximum number of services to request from the Sandbox fallback — has no effect on the primary node-catalogue call, which returns everything for that node unfiltered |
| `dashboard_dir` | No       | `../dashboard/data`                             | Output root directory |
| `node_pid`      | No       | —                                               | Node PID from `endpoint_report.json`; the Sandbox fallback filters on it, and is not attempted without it |

### Examples

```bash
# Write a JSON report to the default dashboard data directory,
# querying the node's own Resource Catalogue
java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckCatalogueServices \
  CESSDA https://datacatalogue.cessda.eu/api

# Retrieve up to 20 services if falling back to the Sandbox catalogue
java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckCatalogueServices \
  CESSDA https://datacatalogue.cessda.eu/api 20

# Write to an explicit dashboard path, with a node_pid fallback
java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckCatalogueServices \
  CESSDA https://datacatalogue.cessda.eu/api 10 /path/to/dashboard/data 21.T15999/CESSDA
```

## Output Files

The report is written to `<dashboard_dir>/<NODE_NAME>/catalogue_services_report.json`:

```text
dashboard/data/CESSDA/catalogue_services_report.json
```

There is no text/terminal-only output format — JSON is always written; the
console log lines shown in this document are what appears in the
application's logs while it runs, not a separate report file.

## Status Categories

Each service with a `webpage` is categorised as:

- **Available** — HTTP status 200–399, and (if checked) valid content and a
  response under the slow-response threshold
- **Available (content check failed)** — 200–399, but the body failed
  content validation (see Metric 13 below)
- **Available (slow: `N`ms)** — 200–399, but the response took longer than
  30 seconds
- **Not found** — HTTP status 404
- **Not available** — any other HTTP status, a connection error, or an
  invalid webpage URL
- **No webpage defined** — the service entry has no `webpage` field

## Example Log Output

```text
Service Catalogue Resource Availability Report
Generated : 2026-02-18T16:30:45Z
Node Name : CESSDA
API Source: https://datacatalogue.cessda.eu/api/service/all
Fetching service data from API
Total services found: 7
Checking service webpages...
CESSDA Data Catalogue                             Available
CESSDA Vocabulary Service                         Available
CESSDA European Language Social Science Thesaurus Available
CESSDA Data Management Expert Guide               Available
CESSDA Data Archiving Guide                       Available
Report generated: JSON: dashboard/data/CESSDA/catalogue_services_report.json
```

### JSON Report Format

```json
{
  "generated": "2026-02-18T16:30:45.123456Z",
  "node_name": "CESSDA",
  "api_source": "https://datacatalogue.cessda.eu/api/service/all",
  "total_services": 7,
  "healthy_services": 5,
  "pct_healthy": 71,
  "avg_response_time_ms": 812,
  "response_time_target_ms": 5000,
  "response_time_threshold_ms": 30000,
  "services": [
    {
      "name": "CESSDA Data Catalogue",
      "abbreviation": "CDC",
      "service_id": "21.15132/2shDkg",
      "webpage": "https://www.cessda.eu/Tools/Data-Catalogue",
      "status": "Available",
      "http_code": 200,
      "content_type": "text/html; charset=utf-8",
      "content_valid": true,
      "response_time_ms": 214
    }
  ]
}
```

`http_code`, `content_valid`, and `response_time_ms` are `null` for a
service with no webpage (status `"No webpage defined"`) or whose webpage
URL couldn't be parsed. `avg_response_time_ms` is `null` if no service in
the report had a measurable response time. `total_services` reflects
whichever source URL actually answered (the node's own catalogue or the
Sandbox fallback — see below); it isn't necessarily the same as
`services.length` if `quantity` limited a fallback query.

## Choosing the Catalogue Endpoint

A node can offer several catalogue endpoints, in different formats (Data-Terra, for instance, lists a Service
Catalogue with a DCAT feed and a Lot-1 list, and a Resource Catalogue of research products). When the check is
run from the dashboard, or by Check All, it chooses among them from the node's `endpoint_report.json`:

1. **By capability type**, in the order set by `check.catalogue.capability-order` (default
   `Service Catalogue,Resource Catalogue`, matched ignoring case). A Service Catalogue states that it lists
   services, whereas a Resource Catalogue may hold other resource types.
2. **By format**, within a type: a native EOSC Beyond list (no conversion) before a Lot-1 list.
3. **Operational** endpoints (as the node declares them) before others, then the node's own order.

An endpoint counts only if an adapter **recognises what it returns**, from a sample of the response. A
capability name alone is never enough, and an endpoint that returns something unreadable, such as a DCAT feed,
is skipped and not taken for an empty list. The first readable endpoint of the first type that has one is used;
if reading it fails, the next is tried.

| Format | How it is recognised | Handling |
| ------ | -------------------- | -------- |
| EOSC Beyond list | `<base>/api/service/all` returns `results` of services with a `name` | used as it is |
| EOSC-Lot-1 v1.0.0 / v2.0.0 | `results` of `{id, service}` bundles; the release from the category vocabulary (`category-*` is v1.0.0, `service_classification-*` is v2.0.0) | every page is read, each record validated against the bundled model and converted |
| anything else | not recognised | skipped and listed under `tried` |

The `api_spec` and `version` a node reports are not used to decide: they are often missing, or point at the
node's own documentation.

**Lot-1 conversion.** Only what the checks and the Node page use is converted: `id`, `name`, `abbreviation`,
`description`, `webpage` (also as `urls`), `logo`, `tags`, `trl`, the order and policy links, and the helpdesk
email as `publicContacts`. `nodePID` is taken from the node's registry entry and `type` is `Service`. Records that
do not conform to the model are left out and counted in `invalid_records`.

If no endpoint can be read, or the node has none, the [Sandbox fallback](#sandbox-fallback) applies.

## API Endpoint and Fallback Order

1. **The node's own catalogue**: chosen as described above, or, when a `catalogueUrl` is given explicitly (the
   command line, or the `catalogueUrl` field of the REST call), that one endpoint, built from `api_base_url`:
   any trailing `/api` or `/api/` stripped and `/api/service/all` appended; an endpoint that already ends in
   `/api/service/all` is used as it is.
2. <a id="sandbox-fallback"></a>**Sandbox fallback** (only if the first step fails, and `node_pid` was
   supplied): the Sandbox Resource Catalogue, which lists the services of every node, asked for this node only
   with its `node` filter:

   ```text
   https://providers.sandbox.eosc-beyond.eu/api/service/all?node=NODE_PID&from=0&quantity=QUANTITY&order=asc
   ```

   Only services whose `nodePID` is this node's are ever reported, even if the Sandbox ignores the filter, so a
   node that has no services registered in the Sandbox gets an empty report and never another node's services.
   Without a `node_pid` there is no safe way to look the node up and the check fails.

A node whose `endpoint_report.json` lists no Service Catalogue or Resource Catalogue endpoint at all is skipped
by Check All, and fails (with that message) when run on its own; the Sandbox is not asked.

A report produced by the fallback has `"fallback": true`, a `fallback_reason` and a `note`, which the Node page
shows above the table. The fallback lists what the Sandbox has registered for the node, which can be fewer than
the node publishes.

If both the node's catalogue and the fallback fail, the whole check fails with an `IOException`.

**Why not a keyword search.** An earlier version searched the Sandbox with the node name as a free-text
`keyword`. That matches fragments of the name inside other nodes' services (`Data-Terra` matches "Data …"), so
the report listed dozens of services that did not belong to the node.

### What the report says about its source

```json
"source": { "capability_type": "Service Catalogue", "protocol": "REST",
            "endpoint": "https://services.earth-data.eu/services",
            "adapter": "lot1", "format": "Lot-1 v2.0.0", "converted": true },
"tried": [ { "capability_type": "Service Catalogue", "endpoint": "https://…/api/resources?types=Service&format=dcat",
             "outcome": "not a catalogue format that can be read (… a DCAT feed …)" },
           { "capability_type": "Service Catalogue", "endpoint": "https://services.earth-data.eu/services",
             "outcome": "used (Lot-1 v2.0.0)" } ],
"invalid_records": 0,
"warnings": []
```

`source` is absent when the Sandbox fallback was used. `invalid_records` (with `invalid_details`) and `warnings`
(for example a list that ended early) appear only when there is something to report. The Node page shows the
source, any warnings, and the endpoints that were not used.

## Data Extracted

From each service in the API response, the check extracts:

- `name`: Service name
- `webpage`: Service webpage URL
- `id` → `service_id`: Service identifier
- `abbreviation`: Service abbreviation (if available)

And, for each service with a `webpage`, from the webpage check itself:
`http_code`, `content_type`, `content_valid`, `response_time_ms`.

## Metric 13 (Proposed Validation Metrics document)

The per-service webpage check evidences Metric 13. For each service with a
`webpage`:

- **REST API services** (content-type contains `json`): valid means the
  body parses as JSON and isn't an empty object/array.
- **Everything else** (treated as a Web Service): valid means the body is
  simply non-blank. The Resource Catalogue's service listing doesn't carry
  a per-service "expected keyword", so this is a non-empty-body check
  rather than a keyword match — see the check's Javadoc if that changes.
- **Response time**: measured for every checked webpage. The code marks a
  response exceeding 30 seconds as `Available (slow: …ms)` rather than
  simply `Available` — but the HTTP request itself is also given a
  30-second timeout, so in practice a webpage that's genuinely that slow
  usually times out first and is reported `Not available` instead; the
  slow-but-successful case is more of a safety margin than something
  expected to show up often. The report's `avg_response_time_ms` is the
  mean across all checked services, compared against the document's
  5-second target for the average (not a per-service pass/fail).

The per-service webpage check sends a browser-shaped `User-Agent`, since
some Exchange Service webpages sit behind a CDN/WAF that fast-rejects the
JDK HttpClient's default `User-Agent`, otherwise misreporting a healthy
service as unavailable. (The Resource Catalogue API fetch itself — primary
or Sandbox fallback — only sets an `Accept: application/json` header, no
custom `User-Agent`.)

## Troubleshooting

### "Failed to fetch Catalogue Services data from ... URLs"

Both the node's own catalogue and the Sandbox fallback failed (or the own
catalogue failed and no `node_pid` was available for the fallback). Check:

- Network connectivity from wherever the check is running
- Whether `api_base_url` (from `endpoint_report.json`) is actually correct
  and reachable
- The application logs (`logging.level.eu.cessda.pilotnode` in
  `application.properties`) for the specific HTTP status or exception from
  each attempt

### A service shows "Not available" despite being reachable in a browser

Likely a CDN/WAF rejecting the request based on its `User-Agent` or
`Accept` header. The check already sends a browser-shaped `User-Agent`
(see above) — if a specific service still misbehaves, that's worth raising
separately, since it may need a further header adjustment.

### "API response contains an 'error' field" in the logs

This is only a warning — the check continues processing the response. It
can be a false positive if the Resource Catalogue's response happens to
have an unrelated field literally named `error`.
