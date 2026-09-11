# CheckServiceUptime

Java check that writes `argo_uptime_report.json` to `<dashboard_dir>/<NODE_NAME>/`.

## Data-source strategy

`CheckServiceUptime` uses a single output contract and tries sources in order:

1. Capability-monitoring metrics API (default):  
   `GET https://api-status.devel.mon.argo.grnet.gr/v1/public/nodes/<TENANT>/capabilities/monitoring/metrics`  
   called with `start_date`, `end_date` (`YYYY-MM-DD`) and `granularity=monthly`. No HTML/JS
   scraping is needed — this is a direct JSON call.
2. Public ARGO dashboard flow (fallback):  
   `https://status.devel.mon.argo.grnet.gr/public/tenants/<TENANT>/dashboard`
3. Legacy ARGO API fallback (requires API key)

For both the capability-metrics API and the public-dashboard flow, the ARGO
tenant name is resolved with a 4-phase cascade:

1. The tenant declared by the node's own `Monitoring` capability in
   `endpoint_report.json` (read from `<dashboard_dir>/<NODE_NAME>/`,
   written earlier by `CheckNodeCapabilities`), if present — extracted from
   a `.../tenants/<TENANT>/...` endpoint URL and URL-decoded. This is
   authoritative and is tried alone, with no further fallback: a node's
   registry name doesn't always match its real ARGO tenant name (e.g.
   `LifeWatch-ERIC`'s registry name resolves to ARGO tenant `LIFEWATCH`,
   which no amount of splitting the registry name would ever produce).
2. Otherwise, the node name as-is.
3. If that specifically returns `404` and the node name is hyphenated, the
   substring before the first hyphen, e.g. `LifeWatch-ERIC -> LifeWatch`.
4. If still unresolved (any `4xx`) and the node name contains whitespace,
   its first whitespace-separated token, e.g. `EGI Pilot Node -> EGI`.

## Usage

```text
CheckServiceUptime NODE_NAME [API_KEY] [START_DATE] [END_DATE] [dashboard_dir]
```

| Argument | Required | Default | Description |
| -------- | -------- | ------- | ----------- |
| `NODE_NAME` | Yes | — | Node name used for output directory and tenant resolution (phase 1 of tenant resolution also reads `<dashboard_dir>/NODE_NAME/endpoint_report.json`, if present) |
| `API_KEY` | No | — | Legacy ARGO API key used only if the capability-metrics API and public-dashboard flow are both unavailable |
| `START_DATE` | No | 1 month ago | Start date (`YYYY-MM-DD`) |
| `END_DATE` | No | Today | End date (`YYYY-MM-DD`) |
| `dashboard_dir` | No | `../dashboard/data` | Output root directory |

## Output JSON

The report is written to:

```text
dashboard/data/<NODE_NAME>/argo_uptime_report.json
```

Example shape (capability-metrics API source):

```json
{
  "generated": "2026-09-10T16:06:30.392182+01:00",
  "api_source": "https://api-status.devel.mon.argo.grnet.gr/v1/public/nodes/CESSDA/capabilities/monitoring/metrics?start_date=2026-08-10&end_date=2026-09-10&granularity=monthly",
  "resolved_data_endpoint": "https://api-status.devel.mon.argo.grnet.gr/v1/public/nodes/CESSDA/capabilities/monitoring/metrics?start_date=2026-08-10&end_date=2026-09-10&granularity=monthly",
  "data_source": "capability-metrics-api",
  "period": {
    "start": "2026-08-10T00:00:00Z",
    "end": "2026-09-10T23:59:59Z"
  },
  "project": "CESSDA",
  "endpoints": [
    {
      "name": "CESSDA Data Catalogue",
      "type": "SERVICE",
      "uptime_percentage": 100.0,
      "average_availability": 100.0,
      "average_reliability": 100.0,
      "days_monitored": 1
    }
  ]
}
```

## Metric mapping (shared by every source)

Every source (capability-metrics API, dashboard, legacy API) returns the same
`name` + `results[]` shape upstream, and each service's `results` entries are
averaged the same way into a single endpoint summary:

- `uptime_percentage`: uses average availability when present (fallback to uptime metric if availability is missing)
- `average_availability`: average of the `results` entries' `availability` values
- `average_reliability`: average of the `results` entries' `reliability` values when provided
- `days_monitored`: number of `results` entries available for the selected period/granularity
  (with monthly granularity this is typically one entry per month in range, not one per day)
