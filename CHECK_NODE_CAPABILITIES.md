# Check Node Capabilities

## Overview

The `check_node_capabilities.sh` script queries the **EOSC Beyond Node
Registry** to automatically discover all registered nodes and test their
endpoint capabilities. This eliminates the need to hardcode individual node
endpoint URLs and enables comprehensive monitoring across the entire
federation.

### Node Registry Integration

- Queries the Node Registry API to discover all nodes dynamically

### Multi-Node Support

- Tests all nodes registered in the EOSC Beyond federation
- Generates separate reports for each node
- Creates a registry summary report across all nodes

### Output Location

- When `dashboard_dir` is supplied, per-node reports are written to
  `<dashboard_dir>/<NODE_NAME>/endpoint_report.json` and the registry summary
  to `<dashboard_dir>/node_registry_summary.json`
- When omitted, timestamped files are written to the current directory

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

The script handles both a bare JSON array and a wrapped object response
(for example `{"nodes": [...]}`). If the response cannot be parsed, the raw
output is printed and the script exits with an error.

## Usage

```bash
chmod +x check_node_capabilities.sh
./check_node_capabilities.sh API_KEY [format] [dashboard_dir]
```

### Arguments

| Argument | Required | Default | Description |
| --------- | -------- | ------- | ----------- |
| `API_KEY` | Yes | — | API key for Node Registry authentication |
| `format` | No | `both` | Output format: `text`, `json`, or `both` |
| `dashboard_dir` | No | `../dashboard/data` | Path to dashboard data directory |

### Examples

```bash
# Write JSON reports to the default dashboard data directory
./check_node_capabilities.sh YOUR_API_KEY json

# Write reports to an explicit path
./check_node_capabilities.sh YOUR_API_KEY json /path/to/dashboard/data

# Generate both text and JSON, writing to the current directory
./check_node_capabilities.sh YOUR_API_KEY both
```

### Prerequisites

- `curl` — for HTTP requests
- `jq` (recommended) — for reliable JSON parsing
- `bash` 4.0+ — for script execution

### Installing jq

```bash
# Ubuntu/Debian
sudo apt-get install jq

# macOS
brew install jq

# CentOS/RHEL
sudo yum install jq
```

## Output Files

### With dashboard_dir supplied

Per-node reports are written to `<dashboard_dir>/<NODE_NAME>/endpoint_report.json`:

```text
dashboard/data/CESSDA/endpoint_report.json
dashboard/data/EOSC-Beyond/endpoint_report.json
dashboard/data/NI4OS-EUROPE/endpoint_report.json
```

The registry summary is written to `<dashboard_dir>/node_registry_summary.json`.

### Without dashboard_dir

Timestamped files are written to the current directory:

```text
endpoint_report_CESSDA_20260225_143000.json
endpoint_report_EOSC-Beyond_20260225_143000.json
node_registry_summary_20260225_143000.json
```

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
  "capabilities": [
    {
      "capability_type": "CESSDA Data Catalogue",
      "endpoint": "https://datacatalogue.cessda.eu/api/DataSets/v2",
      "version": "2.0",
      "status": "Available",
      "http_code": "200"
    }
  ]
}
```

### Registry Summary JSON Report

```json
{
  "generated": "2026-02-25T14:30:00+00:00",
  "registry_source": "https://node-devel.eosc.grnet.gr/federation-backend/tenants/eosc-beyond/nodes",
  "nodes": [
    {
      "name": "CESSDA",
      "endpoint": "https://node-endpoint-staging.beyond.cessda.eu/api/endpoint",
      "total_capabilities": 5,
      "available_capabilities": 5,
      "report_file": "endpoint_report.json"
    },
    {
      "name": "EOSC-Beyond",
      "endpoint": "https://providers.sandbox.eosc-beyond.eu/node/endpoint",
      "total_capabilities": 8,
      "available_capabilities": 2,
      "report_file": "endpoint_report.json"
    },
    {
      "name": "NI4OS-EUROPE",
      "endpoint": "https://endpoint.mrezhi.net/api/endpoint",
      "total_capabilities": 0,
      "available_capabilities": 0,
      "report_file": "endpoint_report.json"
    }
  ]
}
```

## Status Indicators

| Status | Colour | HTTP Code | Description |
| ------- | ------ | --------- | ----------- |
| Available | Green | 200–399 | Service is accessible and responding |
| Not found | Yellow | 404 | Endpoint exists but resource not found |
| Not available | Red | 000, 400+ | Service is unreachable or returning an error |

## Script Workflow

```text
1. Query Node Registry
   └─> Fetch list of all registered nodes
   └─> Validate JSON response; extract node array

2. For each node:
   ├─> Extract node metadata (name, ID, PID, endpoint, legal entity)
   ├─> Query node's capabilities endpoint
   ├─> For each capability:
   │   ├─> Send HTTP HEAD request to endpoint
   │   ├─> Record HTTP status code
   │   └─> Determine availability status
   ├─> Write endpoint_report.json
   └─> Add entry to registry summary

3. Write node_registry_summary.json
```

## Error Handling

The script handles various error conditions:

- Registry returns invalid JSON: prints raw response and exits
- Registry returns a wrapped object: extracts the node array automatically
- Node endpoint unreachable: logs error in node report, continues with other nodes
- Invalid JSON from node: falls back to basic grep/sed parsing if `jq` is unavailable
- Timeout: 10-second timeout per endpoint check

## Integration with  Service Monitoring Systems

### Prometheus Integration

The JSON output can be scraped by Prometheus using a custom exporter:

```python
import json

with open('dashboard/data/node_registry_summary.json') as f:
    data = json.load(f)

for node in data['nodes']:
    path = f"dashboard/data/{node['name']}/endpoint_report.json"
    with open(path) as nf:
        node_data = json.load(nf)
        for cap in node_data['capabilities']:
            status_value = 1 if cap['status'] == 'Available' else 0
            print(
                f'endpoint_status{{node="{node["name"]}",'
                f'capability="{cap["capability_type"]}"}} {status_value}'
            )
```

### Cron Job Setup

```bash
# Run every 15 minutes from the scripts directory
*/15 * * * * cd /path/to/scripts && ./check_node_capabilities.sh YOUR_API_KEY json >> /var/log/endpoint_check.log 2>&1
```

### GitHub Actions Example

```yaml
name: Endpoint Health Check
on:
  schedule:
    - cron: '0 */6 * * *'
  workflow_dispatch:

jobs:
  check-endpoints:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Install jq
        run: sudo apt-get install -y jq
      - name: Run endpoint check
        run: ./src/main/scripts/check_node_capabilities.sh ${{ secrets.API_KEY }} json
      - name: Upload reports
        uses: actions/upload-artifact@v3
        with:
          name: endpoint-reports
          path: src/main/dashboard/data/
```

## Troubleshooting

### "jq not found" warning

Install `jq` for better reliability, or continue with basic parsing.

### Registry response is not valid JSON

The script prints the raw response when this occurs. Common causes:

- Invalid or expired API key (look for HTTP 401 or 403 in the output)
- Network or DNS failure
- API endpoint URL has changed

### All endpoints show "Not available"

Possible causes:

- Network connectivity issues
- Firewall blocking outbound requests
- Node endpoints are down

Check network connectivity and node status directly with `curl`.

### Empty capability lists

Possible causes:

- Node endpoint returns invalid JSON
- Node has no capabilities registered

Check the node endpoint manually with `curl`.

### Script hangs

The script has a 10-second timeout per endpoint; simply wait for completion.
If endpoints are consistently slow, the timeout value can be adjusted in the
script.

## Best Practices

- Run the script regularly via cron to keep dashboard data current
- When `dashboard_dir` is supplied, running the script updates the dashboard
  data in place without any manual file copying
- Monitor trends: look for patterns in availability over time
- Alert on failures: set up notifications for critical endpoints
- Document incidents: track when services go down and recovery time
