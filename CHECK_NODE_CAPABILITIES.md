# Check Node Capabilities

## Overview

`eu.cessda.pilotnode.CheckNodeCapabilities` (a plain Java class, not a
shell script) queries the **EOSC Beyond Node Registry** to automatically
discover all registered nodes and test their endpoint capabilities. This
eliminates the need to hardcode individual node endpoint URLs and enables
comprehensive monitoring across the entire federation.

The normal way to run it is from the dashboard — either the **Node
Capabilities** entry in `index.html`'s Run checks menu, or **Check All**,
which runs it first and then every other check for every node (see
[Dashboard](DASHBOARD_README.md)). It can also be run directly from the
command line; see [Running Directly](#running-directly) below.

### Node Registry Integration

- Queries the Node Registry API to discover all nodes dynamically

### Multi-Node Support

- Tests all nodes registered in the EOSC Beyond federation
- Generates separate reports for each node
- Creates a registry summary report across all nodes

### Output Location

Per-node reports are written to
`<dashboard_dir>/<NODE_NAME>/endpoint_report.json` and the registry summary
to `<dashboard_dir>/node_registry_summary.json`. `dashboard_dir` defaults to
`dashboard/data` when run directly, or to `dashboard.data-dir` from
`application.properties` when triggered from the dashboard.

## Node Registry API

- Endpoint: `https://node-devel.eosc.grnet.gr/federation-backend/tenants/eosc-beyond/nodes`
- Authentication via API key in header: `X-Api-Key: API_KEY`
- Response format:

```json
[
  {
    "id": "6",
    "name": "CESSDA",
    "logo": "https://idp.cessda.eu/static/images/CESSDA_logo.svg",
    "pid": "21.T15999/CESSDA",
    "legal_entity": {
      "name": "Consortium of European Social Science Data Archives",
      "ror_id": "https://ror.org/02wg9xc72"
    },
    "node_endpoint": "https://node-endpoint-staging.beyond.cessda.eu/api/endpoint"
  }
]
```

`CheckNodeCapabilities` handles both a bare JSON array and a wrapped-object
response — it looks for a `nodes`, `results`, or `data` array key. If none
of those is found (and the response isn't a bare array), the whole check
fails with an `IOException`.

The registry request, and the per-node request to each node's own
`node_endpoint`, both send a browser-shaped `User-Agent` and
`Accept: application/json` header — some endpoints reject the JDK
HttpClient's default `User-Agent` outright.

## Running via the Dashboard

Triggered via: `POST /api/run/node-capabilities` (no request body). Requires
`check.api-key-node` and `check.node-name` to be set in
`application.properties` — the endpoint returns an error if either is
blank. It runs as an asynchronous job; poll
`GET /api/run/{jobId}/status` for completion, as described in
[Dashboard](DASHBOARD_README.md).

## Running Directly

```text
CheckNodeCapabilities API_KEY [text|json|both] [dashboard_dir]
```

| Argument | Required | Default | Description |
| --------- | -------- | ------- | ----------- |
| `API_KEY` | Yes | — | API key for Node Registry authentication |
| `format` | No | `json` | Output format: `text`, `json`, or `both` |
| `dashboard_dir` | No | `dashboard/data` | Path to dashboard data directory |

There's no packaged CLI jar for the individual checks — they're plain
classes inside the Spring Boot application — so running one directly means
putting the compiled classes and the project's runtime dependencies
(currently just Jackson) on the classpath yourself, for example:

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" \
  eu.cessda.pilotnode.CheckNodeCapabilities YOUR_API_KEY json dashboard/data
```

### Examples

```bash
# Write a JSON report to the default dashboard data directory
java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckNodeCapabilities YOUR_API_KEY json

# Write reports to an explicit path
java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckNodeCapabilities YOUR_API_KEY json /path/to/dashboard/data

# Generate both text and JSON
java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckNodeCapabilities YOUR_API_KEY both
```

## Output Files

Per-node reports are written to `<dashboard_dir>/<NODE_NAME>/endpoint_report.json`
(and, in `text`/`both` mode, `endpoint_report.txt` alongside it):

```text
dashboard/data/CESSDA/endpoint_report.json
dashboard/data/EOSC-Beyond/endpoint_report.json
dashboard/data/NI4OS-EUROPE/endpoint_report.json
```

The registry summary is written to `<dashboard_dir>/node_registry_summary.json`
(and `node_registry_summary.txt` in `text`/`both` mode).

## Report Structure

### Individual Node JSON Report

```json
{
  "generated": "2026-02-25T14:30:00+00:00",
  "node_name": "CESSDA",
  "node_id": "6",
  "node_pid": "21.T15999/CESSDA",
  "node_endpoint": "https://node-endpoint-staging.beyond.cessda.eu/api/endpoint",
  "legal_entity": {
    "name": "Consortium of European Social Science Data Archives",
    "ror_id": "https://ror.org/02wg9xc72"
  },
  "total_capabilities": 2,
  "available_capabilities": 2,
  "report_file": "dashboard/data/CESSDA/endpoint_report.json",
  "capabilities": [
    {
      "capability_type": "CESSDA Data Catalogue",
      "endpoint": "https://datacatalogue.cessda.eu/api/DataSets/v2",
      "version": "2.0",
      "status": "Available",
      "http_code": 200
    }
  ]
}
```

`http_code` is a JSON number, not a string. If the node's own
`node_endpoint` is unreachable or returns a non-200 status, the report is
still written, with `total_capabilities: 0`, `available_capabilities: 0`,
and an empty `capabilities` array, rather than failing the whole run.

### Registry Summary JSON Report

The summary's `nodes` array is simply every individual node report above,
collected together:

```json
{
  "generated": "2026-02-25T14:30:00+00:00",
  "registry_source": "https://node-devel.eosc.grnet.gr/federation-backend/tenants/eosc-beyond/nodes",
  "nodes": [
    {
      "generated": "2026-02-25T14:30:00+00:00",
      "node_name": "CESSDA",
      "node_id": "6",
      "node_pid": "21.T15999/CESSDA",
      "node_endpoint": "https://node-endpoint-staging.beyond.cessda.eu/api/endpoint",
      "legal_entity": { "name": "...", "ror_id": "..." },
      "total_capabilities": 5,
      "available_capabilities": 5,
      "report_file": "dashboard/data/CESSDA/endpoint_report.json",
      "capabilities": [ "..." ]
    }
  ]
}
```

(Some other parts of the dashboard — e.g. `index.html` — only read
`node_name` from each entry here; the rest of the fields are the same
per-node report written to `endpoint_report.json`.)

## Status Indicators

Each capability's endpoint is probed with an HTTP `HEAD` request (10-second
timeout). There are only two statuses — there is no separate "Not found"
status for a 404 here (unlike Exchange Services — see
[Check Catalogue Services](CHECK_CATALOGUE_SERVICES.md) — which does
distinguish 404):

| Status | Colour | HTTP Code | Description |
| ------- | ------ | --------- | ----------- |
| Available | Green | 200–399 | Service responded with a successful or redirect status |
| Not available | Red | Anything else (404, 000/timeout, 400+, connection error) | Service is unreachable or returned an error status |

## Script Workflow

```text
1. Query Node Registry
   └─> Fetch list of all registered nodes
   └─> Validate JSON response; extract node array (bare array, or nodes/results/data key)

2. For each node:
   ├─> Extract node metadata (name, ID, PID, endpoint, legal entity)
   ├─> Query the node's own node_endpoint for its declared capabilities
   ├─> For each capability:
   │   ├─> Send an HTTP HEAD request to its endpoint
   │   ├─> Record the HTTP status code
   │   └─> Determine Available / Not available
   ├─> Write endpoint_report.json (and endpoint_report.txt in text/both mode)
   └─> Add the same report as an entry in the registry summary

3. Write node_registry_summary.json (and node_registry_summary.txt in text/both mode)
```

## Error Handling

- Registry returns something that isn't a JSON array, object with a
  recognised array key: the whole run fails with an `IOException`.
- A node's `node_endpoint` is an invalid URI: that node is skipped (logged),
  processing continues with the rest.
- A node's `node_endpoint` is unreachable or returns a non-200 status: an
  empty report (`0`/`0` capabilities) is written for that node; processing
  continues.
- An individual capability's `endpoint` is an invalid URI: that one
  capability is skipped (logged); the rest of the node's capabilities are
  still checked.
- Timeout: 10 seconds per capability endpoint probe.

## Integration with Service Monitoring Systems

### Prometheus Integration

The JSON output can be scraped by Prometheus using a custom exporter:

```python
import json

with open('dashboard/data/node_registry_summary.json') as f:
    data = json.load(f)

for node in data['nodes']:
    path = f"dashboard/data/{node['node_name']}/endpoint_report.json"
    with open(path) as nf:
        node_data = json.load(nf)
        for cap in node_data['capabilities']:
            status_value = 1 if cap['status'] == 'Available' else 0
            print(
                f'endpoint_status{{node="{node["node_name"]}",'
                f'capability="{cap["capability_type"]}"}} {status_value}'
            )
```

### Running on a Schedule

Two options, depending on whether the dashboard's Spring Boot backend is
already running:

- **Via the dashboard**, no separate script needed: enable
  `check.check-all.scheduled.enabled` in `application.properties` (see
  [Dashboard](DASHBOARD_README.md#scheduled-check-all)) to run Node
  Capabilities plus every other check, for every node, on a cron schedule.
- **Standalone**, outside the dashboard process, using the classpath recipe
  from [Running Directly](#running-directly):

```bash
# Run every 15 minutes from the project root
*/15 * * * * cd /path/to/cessda.pilot-node.tests && java -cp "target/classes:$(cat cp.txt)" eu.cessda.pilotnode.CheckNodeCapabilities YOUR_API_KEY json >> /var/log/endpoint_check.log 2>&1
```

## Troubleshooting

### Registry response is not valid JSON, or has no recognised array key

Common causes:

- Invalid or expired API key (look for HTTP 401 or 403 in the logged
  response status)
- Network or DNS failure
- The Node Registry API endpoint has changed

### All endpoints show "Not available"

Possible causes:

- Network connectivity issues from wherever the check is running
- Firewall blocking outbound requests
- Node endpoints are genuinely down
- A CDN/WAF in front of the node is rejecting the request — check that the
  `User-Agent`/`Accept` headers sent (see above) aren't the issue

Check network connectivity and node status directly, e.g. with `curl`.

### A node's report shows 0 total capabilities

The node's own `node_endpoint` (as recorded in the Node Registry) was
unreachable or returned a non-200 status when `CheckNodeCapabilities` tried
to fetch its capability list. Check that endpoint directly.

## Best Practices

- Run regularly (by hand, via Check All's schedule, or a standalone cron
  job) to keep dashboard data current
- Running the check updates the dashboard data in place — no manual file
  copying needed
- Monitor trends: look for patterns in availability over time
- Alert on failures: set up notifications for critical endpoints
- Document incidents: track when services go down and recovery time
