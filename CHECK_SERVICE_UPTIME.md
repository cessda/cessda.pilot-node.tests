# CheckServiceUptime

Java check that writes `argo_uptime_report.json` to `<dashboard_dir>/<NODE_NAME>/`.

## Data-source strategy

`CheckServiceUptime` uses a single output contract and tries sources in order:

1. Public ARGO dashboard flow (default):  
   `https://status.devel.mon.argo.grnet.gr/public/tenants/<TENANT>/dashboard`
2. Legacy ARGO API fallback (requires API key)

For the public-dashboard flow, tenant matching is resilient:

- Try full node name first
- If a tenant URL returns `40x`, retry with the first component (split on space/hyphen),
  e.g. `EGI Pilot Node -> EGI`, `NI4OS-EUROPE -> NI4OS`

## Usage

```text
CheckServiceUptime NODE_NAME [API_KEY] [START_DATE] [END_DATE] [dashboard_dir]
```

| Argument | Required | Default | Description |
| -------- | -------- | ------- | ----------- |
| `NODE_NAME` | Yes | — | Node name used for output directory and tenant resolution |
| `API_KEY` | No | — | Legacy ARGO API key used only if public-dashboard flow is unavailable |
| `START_DATE` | No | 6 days ago | Start date (`YYYY-MM-DD`) |
| `END_DATE` | No | Today | End date (`YYYY-MM-DD`) |
| `dashboard_dir` | No | `../dashboard/data` | Output root directory |

## Output JSON

The report is written to:

```text
dashboard/data/<NODE_NAME>/argo_uptime_report.json
```

Example shape:

```json
{
  "generated": "2026-08-04T16:06:30.392182+01:00",
  "api_source": "https://status.devel.mon.argo.grnet.gr/public/tenants/CESSDA/dashboard",
  "resolved_data_endpoint": "https://api-status.devel.mon.argo.grnet.gr/v1/public/tenants/CESSDA/results/groups?start_time=2026-07-29T00%3A00%3A00Z&end_time=2026-08-04T23%3A59%3A59Z",
  "data_source": "dashboard-public-results",
  "period": {
    "start": "2026-07-29T00:00:00Z",
    "end": "2026-08-04T23:59:59Z"
  },
  "project": "CESSDA",
  "endpoints": [
    {
      "name": "CESSDA Data Catalogue",
      "type": "SERVICEGROUP",
      "uptime_percentage": 100.0,
      "average_availability": 100.0,
      "average_reliability": null,
      "days_monitored": 7
    }
  ]
}
```

## Metric mapping used by the dashboard source

- `uptime_percentage`: uses average availability when present (fallback to uptime metric if availability is missing)
- `average_availability`: average of daily availability values
- `average_reliability`: average of daily reliability values when provided
- `days_monitored`: number of daily points available for the selected period
