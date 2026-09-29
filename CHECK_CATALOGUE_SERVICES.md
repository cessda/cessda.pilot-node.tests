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
- Falls back automatically to the Sandbox Resource Catalogue, then to a
  node-PID keyword search, if the node's own API is unavailable

## Running via the Dashboard

The normal way to run this check is from the dashboard: the **Exchange
Services** entry in a node's own Run checks menu on `node.html`, or as part
of **Check All** (see [Dashboard](DASHBOARD_README.md)).

Triggered via: `POST /api/run/catalogue-services` with JSON body
`{ "node": "...", "catalogueUrl": "...", "nodePid": "..." }`. `catalogueUrl`
is the node's Resource Catalogue endpoint, read from that node's
`endpoint_report.json` (capability_type `Resource Catalogue`) — the
dashboard's JavaScript does this lookup automatically before calling the
endpoint. `nodePid` is optional and only used for the third-level keyword
fallback described below. No credentials are required.

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
| `NODE_NAME`     | Yes      | —                                               | Node name; used as the output directory and, when falling back to the Sandbox catalogue, as the keyword filter |
| `api_base_url`  | No       | `https://providers.sandbox.eosc-beyond.eu`      | The node's own Resource Catalogue base URL — the `Resource Catalogue` capability's `endpoint` value from `endpoint_report.json`, passed as-is. Any trailing `/api` or `/api/` is stripped and `/api/service/all` appended |
| `quantity`      | No       | `10`                                            | Maximum number of services to request from a fallback (keyword-filtered) URL — has no effect on the primary node-catalogue call, which returns everything for that node unfiltered |
| `dashboard_dir` | No       | `../dashboard/data`                             | Output root directory |
| `node_pid`      | No       | —                                               | Node PID from `endpoint_report.json`, used as a keyword for a third-level fallback if both the primary and Sandbox-fallback URLs fail |

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
whichever source URL actually answered (primary, Sandbox fallback, or
node-PID fallback — see below); it isn't necessarily the same as
`services.length` if `quantity` limited a fallback query.

## API Endpoint and Fallback Order

1. **Primary**: the node's own Resource Catalogue, built from
   `api_base_url` — any trailing `/api` or `/api/` stripped, then
   `/api/service/all` appended. Returns every service for that node,
   unfiltered.
2. **Sandbox fallback** (only if the primary request fails): the Sandbox
   Resource Catalogue, `https://providers.sandbox.eosc-beyond.eu/api/service/all`,
   filtered by `NODE_NAME` as a keyword:

   ```text
   https://providers.sandbox.eosc-beyond.eu/api/service/all?keyword=NODE_NAME&from=0&quantity=QUANTITY&order=asc
   ```

3. **Node-PID fallback** (only if both of the above fail, and `node_pid`
   was supplied): the same Sandbox URL, but with `node_pid` as the
   `keyword` instead of `NODE_NAME`.

If all three fail, the whole check fails with an `IOException` describing
which URLs were tried.

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
service as unavailable. (The Resource Catalogue API fetch itself — primary,
Sandbox-fallback, or node-PID-fallback — only sets an `Accept:
application/json` header, no custom `User-Agent`.)

## Troubleshooting

### "Failed to fetch Catalogue Services data from ... URLs"

All of the primary, Sandbox-fallback, and (if attempted) node-PID-fallback
requests failed. Check:

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
