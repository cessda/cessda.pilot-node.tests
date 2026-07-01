# Check Service Uptime

A Bash script for monitoring and reporting on service uptime from the ARGO
Service Monitoring API. The script queries the ARGO API for a specified time period
and writes `argo_uptime_report.json` directly to the node's subdirectory in
the dashboard data directory.

## Features

- Uptime reporting with availability and reliability metrics
- Writes JSON directly to the dashboard data directory
- Colour-coded terminal output for quick status assessment
- Flexible date ranges with sensible defaults
- Input validation and error handling

## Requirements

- macOS (uses macOS-specific `date` commands)
- `curl` — for API requests
- `jq` — for JSON parsing
- `bc` — for calculations

### Installing Dependencies

```bash
# Install jq using Homebrew
brew install jq

# bc is usually pre-installed on macOS
```

## Installation

```bash
chmod +x check_service_uptime.sh
```

## Usage

```bash
./check_service_uptime.sh NODE_NAME API_KEY [START_DATE] [END_DATE] [dashboard_dir]
```

### Arguments

| Argument | Required | Default | Description |
| ---------- | -------- | ------- | ----------- |
| `NODE_NAME` | Yes | — | Node name used for the output directory |
| `API_KEY` | Yes | — | API key for ARGO authentication |
| `START_DATE` | No | 30 days ago | Start date in `YYYY-MM-DD` format |
| `END_DATE` | No | Today | End date in `YYYY-MM-DD` format |
| `dashboard_dir` | No | `../dashboard/data` | Path to dashboard data directory |

### Examples

```bash
# Last 30 days, write to default dashboard directory
./check_service_uptime.sh CESSDA your-api-key

# Specific date range
./check_service_uptime.sh CESSDA your-api-key 2026-02-01 2026-02-28

# Explicit dashboard path
./check_service_uptime.sh CESSDA your-api-key 2026-02-01 2026-02-28 /path/to/dashboard/data

# Custom start date, default end date
./check_service_uptime.sh CESSDA your-api-key 2026-01-01
```

## Output

### Console Output

The script provides colour-coded terminal output:

- Green — uptime ≥ 99% (excellent)
- Yellow — uptime 95–98% (good)
- Red — uptime < 95% (requires attention)

### Generated File

The report is written to `<dashboard_dir>/<NODE_NAME>/argo_uptime_report.json`:

```text
dashboard/data/CESSDA/argo_uptime_report.json
```

### JSON Report Format

```json
{
  "generated": "2026-03-02T10:30:00+00:00",
  "api_source": "https://api.devel.mon.argo.grnet.gr/api/v2/results/CORE/SERVICEGROUPS?start_time=2026-02-01T00:00:00Z&end_time=2026-02-28T23:59:59Z",
  "period": {
    "start": "2026-02-01T00:00:00Z",
    "end": "2026-02-28T23:59:59Z"
  },
  "project": "CESSDA",
  "endpoints": [
    {
      "name": "Data Catalogue",
      "type": "SERVICEGROUPS",
      "uptime_percentage": 100.00,
      "average_availability": 100.00,
      "average_reliability": 100.00,
      "days_monitored": 28
    }
  ]
}
```

## Metrics Explained

### Uptime Percentage

The proportion of time the service was operational, calculated as the average
of daily uptime values across the monitoring period.

Formula: `(Sum of daily uptime values / Number of days) × 100`

### Availability

The percentage of time the service was reachable and responding, averaged
across all monitoring days.

### Reliability

The percentage of time the service provided correct and consistent responses,
averaged across all monitoring days.

### Days Monitored

The total number of days within the specified period for which monitoring data
is available.

## Error Handling

The script includes comprehensive error handling:

- Validates date formats (must be `YYYY-MM-DD`)
- Ensures start date is before end date
- Checks for required dependencies (`jq`, `bc`)
- Validates API responses (HTTP status codes)
- Handles missing or empty data gracefully
- Creates the output directory if it does not already exist

### Common Errors

#### "jq is required but not installed"

```bash
brew install jq
```

#### "Invalid date format"

Ensure dates are in `YYYY-MM-DD` format (for example, `2026-02-01`).

#### "Start date must be before end date"

Check that your start date precedes your end date.

#### "API request failed with HTTP status code XXX"

- `401`: Invalid or missing API key
- `404`: Resource not found
- `500`: Server error — try again later

## API Details

Endpoint: `https://api.devel.mon.argo.grnet.gr/api/v2/results/CORE/SERVICEGROUPS`

Timestamps are automatically formatted as:

- Start time: `YYYY-MM-DDT00:00:00Z`
- End time: `YYYY-MM-DDT23:59:59Z`

You do not need to specify the time component; it is handled automatically.

## Integration Examples

### Automated Daily Reports

```bash
# Run daily at 09:00 from the scripts directory
0 9 * * * cd /path/to/scripts && ./check_service_uptime.sh CESSDA $API_KEY >> /var/log/uptime-reports.log 2>&1
```

### Processing JSON Output

```bash
# Extract endpoints with uptime below 99%
jq '.endpoints[] | select(.uptime_percentage < 99)' \
    ../dashboard/data/CESSDA/argo_uptime_report.json
```

### Alert on Low Uptime

```bash
#!/bin/bash
REPORT="../dashboard/data/CESSDA/argo_uptime_report.json"
LOW_UPTIME=$(jq -r '.endpoints[] | select(.uptime_percentage < 99) | .name' "$REPORT")

if [ -n "$LOW_UPTIME" ]; then
    echo "Alert: services with uptime below 99%:"
    echo "$LOW_UPTIME"
fi
```

## Security Considerations

Do not hardcode API keys in scripts. Use one of these methods instead.

### Environment variable

```bash
export ARGO_API_KEY="your-api-key-here"
./check_service_uptime.sh CESSDA $ARGO_API_KEY
```

### Configuration file (with restricted permissions)

```bash
echo "ARGO_API_KEY=your-api-key-here" > ~/.argo_config
chmod 600 ~/.argo_config
source ~/.argo_config
./check_service_uptime.sh CESSDA $ARGO_API_KEY
```

## Troubleshooting

### Script does not run

```bash
# Ensure the script is executable
chmod +x check_service_uptime.sh

# Check the shebang line
head -1 check_service_uptime.sh
# Should output: #!/bin/bash
```

### Date parsing errors

The script uses macOS-specific `date` commands. On Linux, adapt the date
calculations as follows:

```bash
# macOS (current):
date -v-30d "+%Y-%m-%d"

# Linux equivalent:
date -d "30 days ago" "+%Y-%m-%d"
```

### Empty or missing data

- Verify the API endpoint is accessible
- Check that your API key is valid and has appropriate permissions
- Ensure the date range contains monitoring data

## Support

For issues related to:

- Script functionality: check this README or review error messages
- ARGO API: consult the [ARGO Service Monitoring API documentation](https://argoeu.github.io/argo-web-api/)
- Missing data: contact the Service Monitoring Team at GRNET
