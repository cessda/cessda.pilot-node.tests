# CheckServiceUptime

Java check that writes `argo_uptime_report.json` to `<dashboard_dir>/<NODE_NAME>/`.

## Data-source strategy

`CheckServiceUptime` uses a single output contract and tries sources in order:

1. **The declared Monitoring URL.** The `Monitoring` capability's endpoint in `endpoint_report.json`
   (read from `<dashboard_dir>/<NODE_NAME>/`, written earlier by `CheckNodeCapabilities`) is requested
   exactly as the node publishes it. The public results API is found from the script that page loads,
   and the ARGO tenant is the `/tenants/<TENANT>/` segment of that URL (URL-decoded). The report's
   `data_source` is `declared-monitoring-url`. If the node declares no Monitoring endpoint, the endpoint
   has no `/tenants/<TENANT>/` segment, or the page gives no service uptime values, the check moves on.
2. Capability-monitoring metrics API (fallback):  
   `GET https://api-status.devel.mon.argo.grnet.gr/v1/public/nodes/<TENANT>/capabilities/monitoring/metrics`  
   called with `start-date`, `end-date` (`YYYY-MM-DD`; the API ignores the underscore spellings and answers with today only) and `granularity=monthly`.
3. Public ARGO dashboard flow (fallback):  
   `https://status.devel.mon.argo.grnet.gr/public/tenants/<TENANT>/dashboard`
4. Legacy ARGO API fallback (requires API key)

The two public fallbacks need a tenant name, tried in this order:

1. The node name at the top of `endpoint_report.json` (`node_name`, from the Federation Registry).
2. The tenant named by the declared Monitoring URL, if it differs.
3. If the node name returns `404` and is hyphenated, the substring before the first hyphen,
   e.g. `LifeWatch-ERIC -> LifeWatch`.
4. If still unresolved (any `4xx`) and the node name contains whitespace, its first
   whitespace-separated token, e.g. `EGI Pilot Node -> EGI`.

This is the lenient behaviour. Strict mode will use the declared URL only, with no fallback.

## Usage

```text
CheckServiceUptime NODE_NAME [API_KEY] [START_DATE] [END_DATE] [dashboard_dir]
```

| Argument | Required | Default | Description |
| -------- | -------- | ------- | ----------- |
| `NODE_NAME` | Yes | — | Node name used for output directory and tenant resolution (the declared Monitoring URL and node name are also read from `<dashboard_dir>/NODE_NAME/endpoint_report.json`, if present) |
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
  "api_source": "https://api-status.devel.mon.argo.grnet.gr/v1/public/nodes/CESSDA/capabilities/monitoring/metrics?start-date=2026-08-10&end-date=2026-09-10&granularity=monthly",
  "resolved_data_endpoint": "https://api-status.devel.mon.argo.grnet.gr/v1/public/nodes/CESSDA/capabilities/monitoring/metrics?start-date=2026-08-10&end-date=2026-09-10&granularity=monthly",
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
