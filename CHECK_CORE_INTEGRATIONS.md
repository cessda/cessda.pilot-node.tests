# Check Core Integrations

`eu.cessda.pilotnode.CheckCoreIntegrations` fetches the monitoring status of
a Node's Core Service integration endpoints (the fabric: AAI, Resource
Catalogue, Helpdesk, Monitoring ...) from the federation-wide ARGO tenant and
writes `core_integrations_report.json`. On the dashboard this check is
labelled **Core Service integrations**.

On the Node page the status is shown on the endpoint cards of the **Core
Services Integration Report** (each tier's cards gain an "ARGO monitoring"
status), with an "ARGO n/N OK" badge in the panel header.

It is separate from:

- `CheckNodeCapabilities`, which probes the endpoints listed in the
  Federation Registry itself (`endpoint_report.json`) — this check does not
  need the registry, so it keeps working while the registry is unavailable;
- `CheckServiceUptime` (Metric 12), which reports ARGO uptime for the Node's
  Exchange services;
- `CheckCatalogueServices` (Metric 13, Exchange Services).

## Data source

```text
GET <check.argo-status-api-base>/v1/public/tenants/<check.argo-federation-tenant>/status/Default/endpoints?start-time=<today>T00:00:00Z&end-time=<today>T23:59:59Z
```

The response is grouped by Node (`groups[].name`, matched to the Node name
case-insensitively), then by capability type, with a list of timestamped
statuses (`OK`, `WARNING`, `CRITICAL`) per endpoint. Defaults, overridable in
`application.properties`:

```properties
check.argo-status-api-base   = https://api-status.devel.mon.argo.grnet.gr
check.argo-federation-tenant = EOSC-BEYOND-FEDERATION
```

## Running via the Dashboard

Use **Core Service integrations** in a Node's Run checks menu on `node.html`,
or **Check All** (see [Dashboard](DASHBOARD_README.md)).

Triggered via: `POST /api/run/core-integrations` with optional JSON body
`{ "node": "..." }`. No credentials are required.

## Running Directly

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" \
  eu.cessda.pilotnode.CheckCoreIntegrations NODE_NAME [dashboard_dir] [api_base] [tenant]
```

| Argument        | Required | Default                                     | Description |
| --------------- | -------- | ------------------------------------------- | ----------- |
| `NODE_NAME`     | Yes      | —                                           | Node name; must match the ARGO service group name (case-insensitive) |
| `dashboard_dir` | No       | `../dashboard/data`                         | Output root directory |
| `api_base`      | No       | `https://api-status.devel.mon.argo.grnet.gr` | ARGO status API base URL |
| `tenant`        | No       | `EOSC-BEYOND-FEDERATION`                    | ARGO federation tenant |

## Output JSON

Written to `<dashboard_dir>/<NODE_NAME>/core_integrations_report.json`:

```json
{
  "generated": "2026-09-29T15:03:10Z",
  "node_name": "CESSDA",
  "api_source": "https://api-status.devel.mon.argo.grnet.gr/v1/public/tenants/EOSC-BEYOND-FEDERATION/status/Default/endpoints?start-time=...",
  "tenant": "EOSC-BEYOND-FEDERATION",
  "period_start": "2026-09-29T00:00:00Z",
  "period_end": "2026-09-29T23:59:59Z",
  "total_endpoints": 5,
  "ok_endpoints": 4,
  "endpoints": [
    {
      "capability_type": "Resource Catalogue",
      "url": "https://service-catalogue-staging.beyond.cessda.eu/api",
      "status": "WARNING",
      "worst_status": "WARNING",
      "last_checked": "2026-09-29T15:03:10Z"
    }
  ]
}
```

- `status` is the latest ARGO status in the window; `worst_status` is the
  worst seen in it.
- The dashboard matches each entry to a capability card by `capability_type`,
  preferring the same `url`. Entries with no matching card are not shown.
- This status is informational: compliance tiers are still computed from
  `endpoint_report.json` availability.

## Troubleshooting

### "no service group for node '…' in ARGO tenant …"

The ARGO feed has no group with this Node's name. Check the spelling of
`NODE_NAME` against the group names in the feed.
